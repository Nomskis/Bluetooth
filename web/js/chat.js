/**
 * Text chat between the two sides, handy when one of you is muted or the gym
 * is too loud. It runs over a WebRTC data channel, so it is end-to-end
 * encrypted like the call and never passes through the server. The Android
 * app implements the same thing in Chat.kt; docs/protocol.md describes the
 * messages.
 *
 * Every message is acknowledged. Anything not acknowledged yet is sent again
 * when the next connection's channel opens (a reconnect can mean a new peer
 * connection, and with it a new channel), and the receiver drops repeats by id.
 */

/** Both sides create this channel themselves (negotiated), so neither has to wait for the other's. */
export const CHAT_CHANNEL = Object.freeze({ label: 'earshot-chat', id: 0 });
export const MAX_CHAT_LENGTH = 1000;
/** The other side shows the chat only when it sees this in our join message's capabilities. */
export const CHAT_CAPABILITY = 'chat';
export const QUICK_REPLIES = Object.freeze(['👍', 'One sec', "Can't hear you", 'Call you back', '❤️']);

/** The wire messages, or null for anything else (newer kinds, garbage). */
export function parseChat(raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    return null;
  }
  if (msg?.kind === 'contact') {
    // Someone's inbox address, for calling them directly later (docs/protocol.md, Ringing).
    if (typeof msg.address !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(msg.address)) return null;
    return { kind: 'contact', name: typeof msg.name === 'string' ? msg.name.trim().slice(0, 64) : '', address: msg.address };
  }
  if (!msg || typeof msg !== 'object' || typeof msg.id !== 'string' || !msg.id || msg.id.length > 64) return null;
  if (msg.kind === 'chat-ack') return { kind: 'chat-ack', id: msg.id };
  if (msg.kind !== 'chat' || typeof msg.text !== 'string') return null;
  const text = msg.text.trim().slice(0, MAX_CHAT_LENGTH);
  if (!text) return null;
  return { kind: 'chat', id: msg.id, text, sentAt: Number.isFinite(msg.sentAt) ? msg.sentAt : null };
}

/**
 * The conversation for one call. Messages are
 * `{ id, text, mine, at, status }` with status 'sending', 'delivered',
 * 'failed' (mine) or 'received' (theirs).
 *
 * Events: 'change' after any update, 'message' with an incoming message as
 * detail, 'contact' with the other side's contact card as detail.
 */
export class ChatLog extends EventTarget {
  messages = [];
  #channel = null;
  #seen = new Set();
  #newId;
  #now;

  constructor({ newId, now = () => Date.now() } = {}) {
    super();
    this.#newId = newId ?? (() => Math.random().toString(36).slice(2, 12));
    this.#now = now;
  }

  /** Plugs in the current connection's channel; unacknowledged messages go out once it opens. */
  attach(channel) {
    this.#channel = channel;
    channel.addEventListener('open', () => {
      if (this.#channel === channel) this.#flush();
    });
    channel.addEventListener('message', (event) => {
      if (this.#channel === channel && typeof event.data === 'string') this.receive(event.data);
    });
    if (channel.readyState === 'open') this.#flush();
  }

  detach() {
    this.#channel = null;
  }

  /** Queues and, if the channel is open, sends a message. Returns it, or null when empty. */
  send(text) {
    const trimmed = String(text ?? '').trim().slice(0, MAX_CHAT_LENGTH);
    if (!trimmed) return null;
    const message = { id: this.#newId(), text: trimmed, mine: true, at: this.#now(), status: 'sending' };
    this.messages.push(message);
    this.#transmit(message);
    this.#changed();
    return message;
  }

  /** Handles one frame from the channel. */
  receive(raw) {
    const msg = parseChat(raw);
    if (!msg) return;
    if (msg.kind === 'contact') {
      this.dispatchEvent(new CustomEvent('contact', { detail: { name: msg.name, address: msg.address } }));
      return;
    }
    if (msg.kind === 'chat-ack') {
      const mine = this.messages.find((m) => m.mine && m.id === msg.id);
      if (mine && mine.status !== 'delivered') {
        mine.status = 'delivered';
        this.#changed();
      }
      return;
    }
    // Acknowledge repeats too: the first acknowledgement may be what got lost.
    this.#sendFrame({ kind: 'chat-ack', id: msg.id });
    if (this.#seen.has(msg.id)) return;
    this.#seen.add(msg.id);
    const message = { id: msg.id, text: msg.text, mine: false, at: this.#now(), status: 'received' };
    this.messages.push(message);
    this.dispatchEvent(new CustomEvent('message', { detail: message }));
    this.#changed();
  }

  /** Someone else took the other seat: what we couldn't deliver was meant for the person who left. */
  peerChanged() {
    let changed = false;
    for (const m of this.messages) {
      if (m.mine && m.status === 'sending') {
        m.status = 'failed';
        changed = true;
      }
    }
    if (changed) this.#changed();
  }

  #flush() {
    for (const m of this.messages) if (m.mine && m.status === 'sending') this.#transmit(m);
  }

  #transmit(message) {
    this.#sendFrame({ kind: 'chat', id: message.id, text: message.text, sentAt: message.at });
  }

  #sendFrame(frame) {
    const channel = this.#channel;
    if (!channel || channel.readyState !== 'open') return false;
    try {
      channel.send(JSON.stringify(frame));
      return true;
    } catch {
      return false;
    }
  }

  #changed() {
    this.dispatchEvent(new Event('change'));
  }
}
