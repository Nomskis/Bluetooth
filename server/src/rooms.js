import { ErrorCode, PROTOCOL_VERSION } from './protocol.js';

const MAX_QUEUED_SIGNALS = 100;

/**
 * A connection is anything with `send(object)` and `close(code, reason)`.
 * The WebSocket layer wraps real sockets; tests pass fakes.
 *
 * @typedef {{ send(msg: object): void, close(code?: number, reason?: string): void, member?: Member | null }} Connection
 *
 * @typedef {object} Member
 * @property {string} peerId
 * @property {string} name
 * @property {object} client
 * @property {number} seq        Join order inside the room. The newer peer makes the offer.
 * @property {Room} room
 * @property {Connection | null} conn  null while the peer is inside its reconnect grace period
 * @property {any} graceTimer
 * @property {object[]} queue    Signals waiting for the peer to reconnect
 *
 * @typedef {{ code: string, members: Map<string, Member>, nextSeq: number }} Room
 */

/**
 * Tracks rooms and their members and relays signaling messages between them.
 *
 * Membership survives a dropped socket for `reconnectGraceMs`: a phone moving
 * between Wi-Fi and mobile data reconnects with the same peerId and picks up
 * where it left off, while the peer-to-peer media keeps flowing.
 */
export class RoomManager {
  /**
   * @param {object} options
   * @param {number} options.maxPeersPerRoom
   * @param {number} options.reconnectGraceMs
   * @param {(peerId: string) => object[]} options.iceServersFor
   * @param {(room: Room) => object} [options.describeRoom] more to tell a joining member (screen sharing)
   * @param {(member: Member) => void} [options.onRemoved] a member left for good
   * @param {{ setTimeout: typeof setTimeout, clearTimeout: typeof clearTimeout }} [options.timers]
   */
  constructor({ maxPeersPerRoom, reconnectGraceMs, iceServersFor, describeRoom = () => ({}), onRemoved = () => {}, timers = globalThis }) {
    this.maxPeersPerRoom = maxPeersPerRoom;
    this.reconnectGraceMs = reconnectGraceMs;
    this.iceServersFor = iceServersFor;
    this.describeRoom = describeRoom;
    this.onRemoved = onRemoved;
    this.timers = timers;
    /** @type {Map<string, Room>} */
    this.rooms = new Map();
  }

  get roomCount() {
    return this.rooms.size;
  }

  /** @param {Connection} conn */
  join(conn, { room: code, peerId, name, client }) {
    if (conn.member) {
      if (conn.member.room.code === code && conn.member.peerId === peerId) {
        // Duplicate join on the same socket: just repeat the answer.
        this.#sendJoined(conn.member, true);
        return;
      }
      this.leave(conn);
    }

    let room = this.rooms.get(code);
    if (!room) {
      room = { code, members: new Map(), nextSeq: 1 };
      this.rooms.set(code, room);
    }

    const existing = room.members.get(peerId);
    if (existing) {
      // Same peer coming back (reconnect, or a second socket replacing the first).
      if (existing.conn && existing.conn !== conn) {
        const old = existing.conn;
        old.member = null;
        old.close(4000, 'replaced by a newer connection');
      }
      this.timers.clearTimeout(existing.graceTimer);
      existing.graceTimer = null;
      existing.conn = conn;
      existing.name = name;
      existing.client = client;
      conn.member = existing;
      this.#sendJoined(existing, true);
      const queued = existing.queue.splice(0);
      for (const msg of queued) conn.send(msg);
      return;
    }

    if (room.members.size >= this.maxPeersPerRoom) {
      conn.send({ type: 'error', code: ErrorCode.ROOM_FULL, message: 'This room is full.' });
      if (room.members.size === 0) this.rooms.delete(code);
      return;
    }

    /** @type {Member} */
    const member = {
      peerId,
      name,
      client,
      seq: room.nextSeq++,
      room,
      conn,
      graceTimer: null,
      queue: [],
    };
    room.members.set(peerId, member);
    conn.member = member;
    this.#sendJoined(member, false);
    this.#broadcast(room, member, { type: 'peer-joined', peer: describe(member) });
  }

  /** @param {Connection} conn */
  signal(conn, { to, data }) {
    const member = conn.member;
    if (!member) {
      conn.send({ type: 'error', code: ErrorCode.NOT_IN_ROOM, message: 'Join a room first.' });
      return;
    }
    const target = member.room.members.get(to);
    if (!target || target === member) {
      conn.send({ type: 'error', code: ErrorCode.UNKNOWN_PEER, message: `No peer "${to}" in this room.` });
      return;
    }
    const msg = { type: 'signal', from: member.peerId, data };
    if (target.conn) {
      target.conn.send(msg);
    } else {
      target.queue.push(msg);
      if (target.queue.length > MAX_QUEUED_SIGNALS) target.queue.shift();
    }
  }

  /** Explicit hang-up: free the slot immediately. @param {Connection} conn */
  leave(conn) {
    const member = conn.member;
    if (!member) return;
    conn.member = null;
    this.#remove(member, 'left');
  }

  /** Socket closed without a leave: hold the slot for the grace period. @param {Connection} conn */
  disconnected(conn) {
    const member = conn.member;
    if (!member || member.conn !== conn) return;
    conn.member = null;
    member.conn = null;
    if (this.reconnectGraceMs === 0) {
      this.#remove(member, 'timeout');
      return;
    }
    member.graceTimer = this.timers.setTimeout(() => {
      member.graceTimer = null;
      if (!member.conn) this.#remove(member, 'timeout');
    }, this.reconnectGraceMs);
  }

  /** Clears every timer; used on shutdown and in tests. */
  dispose() {
    for (const room of this.rooms.values()) {
      for (const member of room.members.values()) {
        this.timers.clearTimeout(member.graceTimer);
      }
    }
    this.rooms.clear();
  }

  #remove(member, reason) {
    const room = member.room;
    if (room.members.get(member.peerId) !== member) return;
    this.timers.clearTimeout(member.graceTimer);
    room.members.delete(member.peerId);
    this.onRemoved(member);
    if (room.members.size === 0) {
      this.rooms.delete(room.code);
      return;
    }
    for (const other of room.members.values()) {
      // Signals from a peer that is gone are stale; don't deliver them later.
      other.queue = other.queue.filter((msg) => msg.from !== member.peerId);
    }
    this.#broadcast(room, member, { type: 'peer-left', peerId: member.peerId, reason });
  }

  #sendJoined(member, resumed) {
    const peers = [...member.room.members.values()].filter((m) => m !== member).map(describe);
    member.conn.send({
      type: 'joined',
      protocol: PROTOCOL_VERSION,
      room: member.room.code,
      peerId: member.peerId,
      seq: member.seq,
      resumed,
      peers,
      iceServers: this.iceServersFor(member.peerId),
      ...this.describeRoom(member.room),
    });
  }

  /**
   * Membership events only go to connected peers. A peer inside its grace
   * period gets the current member list in its `joined` reply when it comes
   * back, so replaying old join/leave events would only confuse it.
   */
  #broadcast(room, from, msg) {
    for (const other of room.members.values()) {
      if (other !== from && other.conn) other.conn.send(msg);
    }
  }
}

function describe(member) {
  return {
    peerId: member.peerId,
    name: member.name,
    client: member.client,
    seq: member.seq,
  };
}
