import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { RoomManager } from '../src/rooms.js';

class FakeConn {
  constructor() {
    this.sent = [];
    this.closed = null;
    this.member = null;
  }
  send(msg) {
    this.sent.push(msg);
  }
  close(code, reason) {
    this.closed = { code, reason };
  }
  last(type) {
    return this.sent.filter((m) => m.type === type).at(-1);
  }
  all(type) {
    return this.sent.filter((m) => m.type === type);
  }
}

class FakeTimers {
  constructor() {
    this.pending = new Map();
    this.nextId = 1;
  }
  setTimeout(fn) {
    const id = this.nextId++;
    this.pending.set(id, fn);
    return id;
  }
  clearTimeout(id) {
    this.pending.delete(id);
  }
  runAll() {
    const fns = [...this.pending.values()];
    this.pending.clear();
    fns.forEach((fn) => fn());
  }
}

const join = (room, peerId, name = peerId) => ({
  type: 'join',
  room,
  peerId,
  name,
  client: { platform: 'test', version: '0', capabilities: [] },
});

describe('RoomManager', () => {
  let timers;
  let rooms;

  beforeEach(() => {
    timers = new FakeTimers();
    rooms = new RoomManager({
      maxPeersPerRoom: 2,
      reconnectGraceMs: 20_000,
      iceServersFor: (peerId) => [{ urls: ['stun:example.test'], peer: peerId }],
      timers,
    });
  });

  it('first peer joins an empty room', () => {
    const a = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    const joined = a.last('joined');
    assert.equal(joined.room, 'gym');
    assert.equal(joined.peerId, 'peer-aaaa');
    assert.equal(joined.seq, 1);
    assert.equal(joined.resumed, false);
    assert.deepEqual(joined.peers, []);
    assert.equal(joined.protocol, 1);
    assert.deepEqual(joined.iceServers, [{ urls: ['stun:example.test'], peer: 'peer-aaaa' }]);
  });

  it('second peer sees the first and the first is told about the second', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa', 'A'));
    rooms.join(b, join('gym', 'peer-bbbb', 'B'));

    const joinedB = b.last('joined');
    assert.equal(joinedB.seq, 2);
    assert.equal(joinedB.peers.length, 1);
    assert.equal(joinedB.peers[0].peerId, 'peer-aaaa');
    assert.equal(joinedB.peers[0].name, 'A');
    assert.equal(joinedB.peers[0].seq, 1);

    const peerJoined = a.last('peer-joined');
    assert.equal(peerJoined.peer.peerId, 'peer-bbbb');
    assert.equal(peerJoined.peer.seq, 2);
  });

  it('rejects a third peer when the room is full', () => {
    rooms.join(new FakeConn(), join('gym', 'peer-aaaa'));
    rooms.join(new FakeConn(), join('gym', 'peer-bbbb'));
    const c = new FakeConn();
    rooms.join(c, join('gym', 'peer-cccc'));
    assert.equal(c.last('error').code, 'room-full');
    assert.equal(c.member, null);
  });

  it('relays signals between peers', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));
    rooms.signal(b, { to: 'peer-aaaa', data: { kind: 'offer', session: 's1', sdp: 'x' } });
    assert.deepEqual(a.last('signal'), {
      type: 'signal',
      from: 'peer-bbbb',
      data: { kind: 'offer', session: 's1', sdp: 'x' },
    });
  });

  it('refuses signals before joining or to unknown peers', () => {
    const a = new FakeConn();
    rooms.signal(a, { to: 'peer-bbbb', data: { kind: 'offer' } });
    assert.equal(a.last('error').code, 'not-in-room');

    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.signal(a, { to: 'peer-zzzz', data: { kind: 'offer' } });
    assert.equal(a.last('error').code, 'unknown-peer');

    rooms.signal(a, { to: 'peer-aaaa', data: { kind: 'offer' } });
    assert.equal(a.all('error').length, 3, 'cannot signal yourself');
  });

  it('leave frees the slot and tells the other peer', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));
    rooms.leave(b);
    assert.deepEqual(a.last('peer-left'), { type: 'peer-left', peerId: 'peer-bbbb', reason: 'left' });

    const c = new FakeConn();
    rooms.join(c, join('gym', 'peer-cccc'));
    assert.equal(c.last('joined').seq, 3);
  });

  it('empty rooms are deleted', () => {
    const a = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    assert.equal(rooms.roomCount, 1);
    rooms.leave(a);
    assert.equal(rooms.roomCount, 0);
  });

  it('a dropped socket keeps the slot during the grace period and resumes', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));

    rooms.disconnected(b);
    assert.equal(a.all('peer-left').length, 0, 'no peer-left during grace');

    // Signals sent while B is away are queued...
    rooms.signal(a, { to: 'peer-bbbb', data: { kind: 'candidate', session: 's1' } });

    // ...and delivered after B comes back on a new socket.
    const b2 = new FakeConn();
    rooms.join(b2, join('gym', 'peer-bbbb'));
    const joined = b2.last('joined');
    assert.equal(joined.resumed, true);
    assert.equal(joined.seq, 2, 'seq is kept across a resume');
    assert.equal(joined.peers[0].peerId, 'peer-aaaa');
    assert.equal(b2.last('signal').data.kind, 'candidate');
    assert.equal(a.all('peer-joined').length, 1, 'no duplicate peer-joined on resume');

    timers.runAll();
    assert.equal(a.all('peer-left').length, 0, 'grace timer was cancelled');
  });

  it('grace period expiry removes the peer', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));
    rooms.disconnected(b);
    timers.runAll();
    assert.deepEqual(a.last('peer-left'), { type: 'peer-left', peerId: 'peer-bbbb', reason: 'timeout' });
  });

  it('a newer socket for the same peer replaces the old one', () => {
    const a = new FakeConn();
    const a2 = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(a2, join('gym', 'peer-aaaa'));
    assert.equal(a.closed.code, 4000);
    assert.equal(a.member, null);
    assert.equal(a2.last('joined').resumed, true);

    // The old socket closing afterwards must not start a grace timer.
    rooms.disconnected(a);
    assert.equal(timers.pending.size, 0);
  });

  it('queued signals from a peer that leaves are dropped', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));
    rooms.disconnected(a);
    rooms.signal(b, { to: 'peer-aaaa', data: { kind: 'offer', session: 's1' } });
    rooms.leave(b);

    const a2 = new FakeConn();
    rooms.join(a2, join('gym', 'peer-aaaa'));
    assert.equal(a2.all('signal').length, 0);
    assert.deepEqual(a2.last('joined').peers, []);
  });

  it('joining another room leaves the current one', () => {
    const a = new FakeConn();
    const b = new FakeConn();
    rooms.join(a, join('gym', 'peer-aaaa'));
    rooms.join(b, join('gym', 'peer-bbbb'));
    rooms.join(b, join('home', 'peer-bbbb'));
    assert.equal(a.last('peer-left').peerId, 'peer-bbbb');
    assert.equal(b.last('joined').room, 'home');
  });

  it('larger rooms are possible when configured', () => {
    rooms = new RoomManager({ maxPeersPerRoom: 3, reconnectGraceMs: 0, iceServersFor: () => [], timers });
    const conns = ['peer-aaaa', 'peer-bbbb', 'peer-cccc'].map((id) => {
      const c = new FakeConn();
      rooms.join(c, join('team', id));
      return c;
    });
    assert.equal(conns[2].last('joined').peers.length, 2);
    assert.equal(conns[0].all('peer-joined').length, 2);
  });
});
