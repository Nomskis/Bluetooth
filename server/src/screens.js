/**
 * Screen shares in rooms (docs/protocol.md, "Screen sharing"). One person in a room can
 * share at a time; the other watches. The media goes through Cloudflare ([relay]), and
 * this server only ever talks to Cloudflare for people who are in a call together, so
 * the free allowance can't be used by strangers.
 */

/** Asking Cloudflare for anything more often than this per person is refused. */
const MIN_ASK_INTERVAL_MS = 1000;

export class ScreenShares {
  /**
   * @param {object} options
   * @param {ReturnType<import('./screen-relay.js').createScreenRelay>} options.relay null when not set up
   * @param {{ warn(...args: any[]): void }} [options.log]
   * @param {() => number} [options.now]
   */
  constructor({ relay, log = console, now = Date.now }) {
    this.relay = relay;
    this.log = log;
    this.now = now;
    /** Room code -> the share going on in it: who, and where Cloudflare has it. */
    this.shares = new Map();
    /** Viewer member -> the Cloudflare session it watches through. */
    this.watching = new WeakMap();
    /** Member -> when it last asked Cloudflare for something. */
    this.lastAsk = new WeakMap();
  }

  get available() {
    return this.relay !== null;
  }

  /** What a joining member hears about the room: whether sharing works here, and who's sharing. */
  describe(room) {
    const share = this.shares.get(room.code);
    return {
      features: this.available ? ['screen'] : [],
      ...(share ? { screen: { from: share.from } } : {}),
    };
  }

  /** The sharer's offer: published to Cloudflare, the answer back, and the room told. */
  async publish(conn, { sdp, mid }) {
    const member = this.#member(conn);
    if (!member || !this.#ready(conn, member)) return;
    const code = member.room.code;
    const current = this.shares.get(code);
    if (current && current.from !== member.peerId) {
      fail(conn, 'in-use', 'The other person is already sharing their screen.');
      return;
    }
    let published;
    try {
      published = await this.relay.publish(sdp, mid);
    } catch (err) {
      this.log.warn('screen publish failed:', err.message);
      fail(conn, 'relay', "Couldn't reach the screen relay. Try again.");
      return;
    }
    // Left, or someone else started sharing, while Cloudflare answered.
    if (conn.member !== member || !member.room.members.has(member.peerId)) return;
    const now = this.shares.get(code);
    if (now && now.from !== member.peerId) {
      fail(conn, 'in-use', 'The other person is already sharing their screen.');
      return;
    }
    this.shares.set(code, { from: member.peerId, sessionId: published.sessionId, trackName: published.trackName });
    conn.send({ type: 'screen-published', sdp: published.sdp });
    broadcast(member, { type: 'screen-started', from: member.peerId });
  }

  /** The sharer stopped. */
  stop(conn) {
    const member = this.#member(conn);
    if (member) this.#end(member);
  }

  /** A viewer wants the room's screen: a Cloudflare session pulling it, and its offer. */
  async watch(conn) {
    const member = this.#member(conn);
    if (!member) return;
    const share = this.shares.get(member.room.code);
    if (!share || share.from === member.peerId) {
      conn.send({ type: 'screen-stopped', from: share?.from ?? null });
      return;
    }
    if (!this.#ready(conn, member)) return;
    let pulled;
    try {
      pulled = await this.relay.pull(share);
    } catch (err) {
      this.log.warn('screen pull failed:', err.message);
      fail(conn, 'relay', "Couldn't reach the screen relay. Trying again.");
      return;
    }
    if (conn.member !== member) return;
    // The share ended or changed while Cloudflare answered; the viewer hears about the new one.
    if (this.shares.get(member.room.code) !== share) return;
    this.watching.set(member, pulled.sessionId);
    conn.send({ type: 'screen-offer', watch: pulled.sessionId, sdp: pulled.sdp });
  }

  /** The viewer's answer to the offer [watch] gave it. */
  async answer(conn, { watch, sdp }) {
    const member = this.#member(conn);
    if (!member || !this.relay) return;
    if (this.watching.get(member) !== watch) {
      fail(conn, 'stale', 'That screen session is over.');
      return;
    }
    try {
      await this.relay.answer(watch, sdp);
    } catch (err) {
      this.log.warn('screen answer failed:', err.message);
      fail(conn, 'relay', "Couldn't reach the screen relay. Trying again.");
    }
  }

  /** Someone left the room for good: their share ends with them. */
  memberRemoved(member) {
    this.#end(member);
  }

  #end(member) {
    const code = member.room.code;
    const share = this.shares.get(code);
    if (!share || share.from !== member.peerId) return;
    this.shares.delete(code);
    broadcast(member, { type: 'screen-stopped', from: member.peerId });
  }

  #member(conn) {
    if (conn.member) return conn.member;
    conn.send({ type: 'error', code: 'not-in-room', message: 'Join a room first.' });
    return null;
  }

  /** Sharing is set up here, and this person isn't asking too often. */
  #ready(conn, member) {
    if (!this.relay) {
      fail(conn, 'unavailable', "Screen sharing isn't set up on this server.");
      return false;
    }
    const now = this.now();
    if (now - (this.lastAsk.get(member) ?? -Infinity) < MIN_ASK_INTERVAL_MS) {
      fail(conn, 'busy', 'Slow down.');
      return false;
    }
    this.lastAsk.set(member, now);
    return true;
  }
}

function fail(conn, code, message) {
  conn.send({ type: 'screen-error', code, message });
}

/** To everyone else in the room who's connected; someone reconnecting hears it in `joined`. */
function broadcast(member, msg) {
  for (const other of member.room.members.values()) {
    if (other !== member && other.conn) other.conn.send(msg);
  }
}
