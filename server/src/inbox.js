import crypto from 'node:crypto';

/**
 * Ringing. A client waits for calls by listening on its inbox; another
 * client rings that inbox with a room to meet in. The server only passes the
 * ring along and keeps nothing once it's answered, declined or over.
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

export class Inbox {
  /** address -> Set of listening connections (one per device). */
  #listeners = new Map();
  /** ringId -> { caller, to, room, timer } */
  #rings = new Map();
  #ringTimeoutMs;
  #timers;

  constructor({ ringTimeoutMs = DEFAULT_RING_TIMEOUT_MS, timers = globalThis } = {}) {
    this.#ringTimeoutMs = ringTimeoutMs;
    this.#timers = timers;
  }

  /** Starts delivering rings for this key's address to `conn`. */
  listen(conn, key) {
    const address = inboxAddress(key);
    if (conn.inboxAddress && conn.inboxAddress !== address) this.#removeListener(conn);
    conn.inboxAddress = address;
    let set = this.#listeners.get(address);
    if (!set) this.#listeners.set(address, (set = new Set()));
    set.add(conn);
    conn.send({ type: 'listening', address });
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
    for (const device of devices) {
      device.send({ type: 'incoming', ringId: msg.ringId, room: msg.room, from, video: msg.video });
    }
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
