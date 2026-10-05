import crypto from 'node:crypto';

/**
 * Ringing and chat. A client waits for calls by listening on its inbox; another
 * client rings that inbox with a room to meet in. The server only passes the
 * ring along and keeps nothing once it's answered, declined or over. Chat
 * messages go the same way, and wait in memory for a phone that's offline
 * until one of its devices confirms them.
 *
 * Each install keeps a secret inbox key. Its public address is derived from
 * it (a hash), so knowing someone's address lets you ring them but not
 * listen for their calls. The caller may prove its own address the same way,
 * so the callee sees who's calling (and can call back) without trusting a
 * claim.
 */

export const INBOX_KEY_PATTERN = /^[A-Za-z0-9_-]{22,128}$/;
export const INBOX_ADDRESS_PATTERN = /^[A-Za-z0-9_-]{22}$/;

/** Public address of an inbox key: the first 22 base64url characters (132 bits) of its SHA-256. */
export function inboxAddress(key) {
  return crypto.createHash('sha256').update(`earshot-inbox:${key}`).digest('base64url').slice(0, 22);
}

const DEFAULT_RING_TIMEOUT_MS = 60_000;
/** One person ringing several people at once is fine; a flood isn't. */
const MAX_RINGS_PER_CONNECTION = 5;

/** How long a chat message waits for a phone that's offline, and how many can wait per inbox. */
const MESSAGE_TTL_MS = 14 * 24 * 60 * 60 * 1000;
const MAX_QUEUED_PER_INBOX = 500;
/** A conversation is fine; a flood isn't. */
const MAX_MESSAGES_PER_WINDOW = 30;
const MESSAGE_WINDOW_MS = 10_000;

export class Inbox {
  /** address -> Set of listening connections (one per device). */
  #listeners = new Map();
  /** ringId -> { caller, to, room, timer } */
  #rings = new Map();
  /**
   * address -> chat messages for it that no device has confirmed yet: [{ out, from, id, at }].
   * Kept in memory only; the sending phone keeps each message until it hears "delivered" and
   * sends it again after a reconnect, so a server restart loses nothing.
   */
  #messages = new Map();
  #ringTimeoutMs;
  #timers;
  #now;

  constructor({ ringTimeoutMs = DEFAULT_RING_TIMEOUT_MS, timers = globalThis, now = Date.now } = {}) {
    this.#ringTimeoutMs = ringTimeoutMs;
    this.#timers = timers;
    this.#now = now;
  }

  /** Starts delivering rings (and chat messages, the waiting ones first) for this key's address to `conn`. */
  listen(conn, key) {
    const address = inboxAddress(key);
    if (conn.inboxAddress && conn.inboxAddress !== address) this.#removeListener(conn);
    conn.inboxAddress = address;
    let set = this.#listeners.get(address);
    if (!set) this.#listeners.set(address, (set = new Set()));
    set.add(conn);
    conn.send({ type: 'listening', address });
    for (const waiting of this.#waitingFor(address)) conn.send(waiting.out);
  }

  /**
   * A chat message to `msg.to`, from the address `conn` listens on (so the sender can't
   * be faked). Delivered to each of their devices now, or when one comes online; it waits
   * until one confirms it (message-ack).
   */
  message(conn, msg) {
    const from = conn.inboxAddress;
    if (!from) {
      conn.send({ type: 'error', code: 'not-listening', message: 'Listen on your inbox before sending messages.' });
      return;
    }
    if (!this.#allowMessage(conn)) {
      conn.send({ type: 'error', code: 'rate-limited', message: 'Too many messages at once.' });
      return;
    }
    const out = { type: 'message', id: msg.id, from: { address: from, name: msg.name }, sentAt: this.#now() };
    if (msg.text !== undefined) out.text = msg.text;
    if (msg.reply) out.reply = msg.reply;
    if (msg.unsend) out.unsend = msg.unsend;
    const waiting = this.#waitingFor(msg.to);
    // Withdrawn before their phone took it: it never arrives. The unsend still goes, for a
    // device of theirs that took it already.
    if (msg.unsend) {
      const kept = waiting.filter((m) => !(m.from === from && m.id === msg.unsend));
      waiting.splice(0, waiting.length, ...kept);
    }
    // Sent again after a reconnect: still the one message.
    if (!waiting.some((m) => m.from === from && m.id === msg.id)) {
      waiting.push({ out, from, id: msg.id, at: out.sentAt });
      while (waiting.length > MAX_QUEUED_PER_INBOX) waiting.shift();
      this.#messages.set(msg.to, waiting);
    }
    const devices = [...(this.#listeners.get(msg.to) ?? [])];
    for (const device of devices) device.send(out);
    conn.send({ type: 'message-status', id: msg.id, to: msg.to, status: devices.length > 0 ? 'sent' : 'queued' });
  }

  /** A device of the recipient has the message: stop holding it, and tell the sender's devices. */
  messageAck(conn, msg) {
    const me = conn.inboxAddress;
    if (!me) return;
    const waiting = this.#messages.get(me);
    if (waiting) {
      const left = waiting.filter((m) => !(m.from === msg.to && m.id === msg.id));
      if (left.length > 0) this.#messages.set(me, left);
      else this.#messages.delete(me);
    }
    for (const device of this.#listeners.get(msg.to) ?? []) {
      device.send({ type: 'message-status', id: msg.id, to: me, status: 'delivered' });
    }
  }

  /**
   * The recipient has seen their messages up to `msg.id` (one of the sender's, `msg.to`):
   * passed to the sender's devices that are online. Not held: a read receipt that misses
   * them only leaves "delivered" showing.
   */
  messageRead(conn, msg) {
    const me = conn.inboxAddress;
    if (!me) return;
    for (const device of this.#listeners.get(msg.to) ?? []) {
      device.send({ type: 'message-status', id: msg.id, to: me, status: 'read' });
    }
  }

  /** How many chat messages wait for an address; for tests and logs. */
  waitingMessages(address) {
    return this.#waitingFor(address).length;
  }

  /** Rings everyone listening on `msg.to`. */
  ring(conn, msg) {
    if (this.#rings.has(msg.ringId)) {
      conn.send({ type: 'error', code: 'bad-request', message: 'That ringId is already in use.' });
      return;
    }
    let own = 0;
    for (const r of this.#rings.values()) if (r.caller === conn) own++;
    if (own >= MAX_RINGS_PER_CONNECTION) {
      conn.send({ type: 'error', code: 'rate-limited', message: 'Too many calls ringing at once.' });
      return;
    }
    const devices = [...(this.#listeners.get(msg.to) ?? [])].filter((c) => c !== conn);
    if (devices.length === 0) {
      conn.send({ type: 'ring-status', ringId: msg.ringId, status: 'unreachable' });
      return;
    }
    const from = { name: msg.name, address: msg.inbox ? inboxAddress(msg.inbox) : null };
    const timer = this.#timers.setTimeout(() => this.#expire(msg.ringId), this.#ringTimeoutMs);
    timer.unref?.();
    this.#rings.set(msg.ringId, { caller: conn, to: msg.to, room: msg.room, timer });
    const incoming = { type: 'incoming', ringId: msg.ringId, room: msg.room, from, video: msg.video };
    // The ringing device may connect ahead of the answer; only said when the caller's app can take it.
    if (msg.preconnect) incoming.preconnect = true;
    for (const device of devices) device.send(incoming);
    conn.send({ type: 'ring-status', ringId: msg.ringId, status: 'ringing', devices: devices.length });
  }

  /** The caller gave up before an answer. */
  cancel(conn, msg) {
    const ring = this.#rings.get(msg.ringId);
    if (!ring || ring.caller !== conn) return;
    this.#end(msg.ringId, ring);
    this.#tellDevices(ring, { type: 'ring-cancelled', ringId: msg.ringId, reason: 'cancelled' });
  }

  /** A listening device accepted or declined. The first answer wins; the other devices stop ringing. */
  answer(conn, msg) {
    const ring = this.#rings.get(msg.ringId);
    if (!ring || conn.inboxAddress !== ring.to) return;
    this.#end(msg.ringId, ring);
    ring.caller.send({ type: 'ring-answered', ringId: msg.ringId, accepted: msg.accepted, reason: msg.reason });
    this.#tellDevices(ring, { type: 'ring-cancelled', ringId: msg.ringId, reason: 'answered-elsewhere' }, conn);
  }

  disconnected(conn) {
    this.#removeListener(conn);
    for (const [ringId, ring] of [...this.#rings]) {
      if (ring.caller !== conn) continue;
      this.#end(ringId, ring);
      this.#tellDevices(ring, { type: 'ring-cancelled', ringId, reason: 'cancelled' });
    }
  }

  /** How many devices are listening on an address; for tests and logs. */
  listening(address) {
    return this.#listeners.get(address)?.size ?? 0;
  }

  dispose() {
    for (const ring of this.#rings.values()) this.#timers.clearTimeout(ring.timer);
    this.#rings.clear();
    this.#listeners.clear();
    this.#messages.clear();
  }

  /** The messages still waiting for an address, dropping any too old to keep. */
  #waitingFor(address) {
    const now = this.#now();
    const waiting = (this.#messages.get(address) ?? []).filter((m) => now - m.at < MESSAGE_TTL_MS);
    if (waiting.length > 0) this.#messages.set(address, waiting);
    else this.#messages.delete(address);
    return waiting;
  }

  #allowMessage(conn) {
    const now = this.#now();
    conn.messageTimes = (conn.messageTimes ?? []).filter((t) => now - t < MESSAGE_WINDOW_MS);
    if (conn.messageTimes.length >= MAX_MESSAGES_PER_WINDOW) return false;
    conn.messageTimes.push(now);
    return true;
  }

  #expire(ringId) {
    const ring = this.#rings.get(ringId);
    if (!ring) return;
    this.#rings.delete(ringId);
    this.#tellDevices(ring, { type: 'ring-cancelled', ringId, reason: 'timeout' });
    ring.caller.send({ type: 'ring-answered', ringId, accepted: false, reason: 'no-answer' });
  }

  #end(ringId, ring) {
    this.#timers.clearTimeout(ring.timer);
    this.#rings.delete(ringId);
  }

  #tellDevices(ring, msg, except = null) {
    for (const device of this.#listeners.get(ring.to) ?? []) {
      if (device !== except) device.send(msg);
    }
  }

  #removeListener(conn) {
    const address = conn.inboxAddress;
    if (!address) return;
    const set = this.#listeners.get(address);
    set?.delete(conn);
    if (set && set.size === 0) this.#listeners.delete(address);
    conn.inboxAddress = null;
  }
}
