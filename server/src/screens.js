/**
 * Screen shares in rooms (docs/protocol.md, "Screen sharing"). One person in a room can
 * share at a time; the other watches. The media goes through Cloudflare ([relay]), and
 * this server only ever talks to Cloudflare for people who are in a call together, so
 * the free allowance can't be used by strangers.
 *
 * A share is announced once the sharer says its connection to Cloudflare is up
 * (`screen-live`): Cloudflare holds a pull of a screen that isn't flowing yet for up to
 * five seconds and then fails it, so watching starts only when there's something to watch.
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
      ...(share?.live ? { screen: { from: share.from } } : {}),
    };
  }

  /**
   * The sharer's offer: published to Cloudflare and the answer sent back, with the
   * sharer's [id] for this attempt so a late answer to an earlier one can be told apart.
   * The room hears about it at `screen-live`.
   */
  async publish(conn, { sdp, mid, audioMid, id }) {
    const member = this.#member(conn);
    if (!member || !this.#ready(conn, member, id)) return;
    const code = member.room.code;
    const current = this.shares.get(code);
    if (current && current.from !== member.peerId) {
      fail(conn, 'in-use', 'The other person is already sharing their screen.', id);
      return;
    }
    let published;
    try {
      published = await this.relay.publish(sdp, mid, audioMid);
    } catch (err) {
      this.log.warn('screen publish failed:', err.message);
      fail(conn, 'relay', "Couldn't reach the screen relay. Try again.", id);
      return;
    }
    // Left, or someone else started sharing, while Cloudflare answered.
    if (conn.member !== member || !member.room.members.has(member.peerId)) return;
    const now = this.shares.get(code);
    if (now && now.from !== member.peerId) {
      fail(conn, 'in-use', 'The other person is already sharing their screen.', id);
      return;
    }
    // A share published again (its connection dropped) stays out of sight until it's up.
    this.shares.set(code, {
      from: member.peerId,
      sessionId: published.sessionId,
      trackName: published.trackName,
      ...(published.audioTrackName ? { audioTrackName: published.audioTrackName } : {}),
      live: false,
    });
    conn.send({ type: 'screen-published', sdp: published.sdp, ...(id !== undefined ? { id } : {}) });
  }

  /** The sharer's connection to Cloudflare is up: the room is told, and can watch. */
  live(conn) {
    const member = this.#member(conn);
    if (!member) return;
    const share = this.shares.get(member.room.code);
    if (!share || share.from !== member.peerId || share.live) return;
    share.live = true;
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
    // Nothing to watch yet: a share being published (again) is announced when it's up.
    if (!share || !share.live || share.from === member.peerId) {
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
  #ready(conn, member, id) {
    if (!this.relay) {
      fail(conn, 'unavailable', "Screen sharing isn't set up on this server.", id);
      return false;
    }
    const now = this.now();
    if (now - (this.lastAsk.get(member) ?? -Infinity) < MIN_ASK_INTERVAL_MS) {
      fail(conn, 'busy', 'Slow down.', id);
      return false;
    }
    this.lastAsk.set(member, now);
    return true;
  }
}

/** [id]: the publish attempt it's about, when it's about one. */
function fail(conn, code, message, id) {
  conn.send({ type: 'screen-error', code, message, ...(id !== undefined ? { id } : {}) });
}

/** To everyone else in the room who's connected; someone reconnecting hears it in `joined`. */
function broadcast(member, msg) {
  for (const other of member.room.members.values()) {
    if (other !== member && other.conn) other.conn.send(msg);
  }
}
