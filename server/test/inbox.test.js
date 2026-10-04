import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { INBOX_ADDRESS_PATTERN, Inbox, inboxAddress } from '../src/inbox.js';
import { ProtocolError, parseClientMessage } from '../src/protocol.js';

/** A connection that records what the server sends it. */
function conn(name) {
  return {
    name,
    sent: [],
    send(msg) {
      this.sent.push(msg);
    },
    last(type) {
      return [...this.sent].reverse().find((m) => m.type === type);
    },
  };
}

/** Timers the test fires by hand. */
function fakeTimers() {
  const pending = new Map();
  let id = 0;
  return {
    setTimeout(fn, ms) {
      const handle = { id: ++id, fn, ms };
      pending.set(handle.id, handle);
      return handle;
    },
    clearTimeout(handle) {
      if (handle) pending.delete(handle.id);
    },
    fireAll() {
      for (const handle of [...pending.values()]) {
        pending.delete(handle.id);
        handle.fn();
      }
    },
    get count() {
      return pending.size;
    },
  };
}

const SAM_KEY = 'sam-secret-inbox-key-0001';
const SALMA_KEY = 'salma-secret-inbox-key-01';
const SAM = inboxAddress(SAM_KEY);
const SALMA = inboxAddress(SALMA_KEY);

function ring(to, extra = {}) {
  return { type: 'ring', to, ringId: 'ring-0001', room: 'calm-otter-4821', name: 'Salma', video: true, inbox: SALMA_KEY, ...extra };
}

describe('inbox', () => {
  it('derives a stable 22-character address that does not reveal the key', () => {
    assert.match(SAM, INBOX_ADDRESS_PATTERN);
    assert.equal(inboxAddress(SAM_KEY), SAM);
    assert.notEqual(SAM, SALMA);
    assert.ok(!SAM.includes(SAM_KEY.slice(0, 8)));
  });

  it('rings every listening device, with the caller proven by its own key', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    const tablet = conn('tablet');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.listen(tablet, SAM_KEY);
    assert.deepEqual(phone.last('listening'), { type: 'listening', address: SAM });
    inbox.ring(caller, ring(SAM));
    const incoming = { type: 'incoming', ringId: 'ring-0001', room: 'calm-otter-4821', from: { name: 'Salma', address: SALMA }, video: true };
    assert.deepEqual(phone.last('incoming'), incoming);
    assert.deepEqual(tablet.last('incoming'), incoming);
    assert.deepEqual(caller.last('ring-status'), { type: 'ring-status', ringId: 'ring-0001', status: 'ringing', devices: 2 });
  });

  it('says unreachable when nobody is listening', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const caller = conn('caller');
    inbox.ring(caller, ring(SAM));
    assert.deepEqual(caller.last('ring-status'), { type: 'ring-status', ringId: 'ring-0001', status: 'unreachable' });
  });

  it('the first answer wins and the other devices stop ringing', () => {
    const timers = fakeTimers();
    const inbox = new Inbox({ timers });
    const phone = conn('phone');
    const tablet = conn('tablet');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.listen(tablet, SAM_KEY);
    inbox.ring(caller, ring(SAM));
    inbox.answer(phone, { ringId: 'ring-0001', accepted: true });
    assert.deepEqual(caller.last('ring-answered'), { type: 'ring-answered', ringId: 'ring-0001', accepted: true, reason: undefined });
    assert.deepEqual(tablet.last('ring-cancelled'), { type: 'ring-cancelled', ringId: 'ring-0001', reason: 'answered-elsewhere' });
    assert.equal(phone.last('ring-cancelled'), undefined);
    assert.equal(timers.count, 0);
    // A late second answer goes nowhere.
    inbox.answer(tablet, { ringId: 'ring-0001', accepted: false, reason: 'declined' });
    assert.equal(caller.sent.filter((m) => m.type === 'ring-answered').length, 1);
  });

  it('only a device listening on the rung address can answer', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    const stranger = conn('stranger');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.listen(stranger, 'some-other-inbox-key-0001');
    inbox.ring(caller, ring(SAM));
    inbox.answer(stranger, { ringId: 'ring-0001', accepted: false, reason: 'declined' });
    assert.equal(caller.last('ring-answered'), undefined);
  });

  it('a decline says why', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.ring(caller, ring(SAM));
    inbox.answer(phone, { ringId: 'ring-0001', accepted: false, reason: 'busy' });
    assert.deepEqual(caller.last('ring-answered'), { type: 'ring-answered', ringId: 'ring-0001', accepted: false, reason: 'busy' });
  });

  it('the caller hanging up, or leaving, stops the ringing', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.ring(caller, ring(SAM));
    inbox.cancel(conn('someone-else'), { ringId: 'ring-0001' });
    assert.equal(phone.last('ring-cancelled'), undefined);
    inbox.cancel(caller, { ringId: 'ring-0001' });
    assert.deepEqual(phone.last('ring-cancelled'), { type: 'ring-cancelled', ringId: 'ring-0001', reason: 'cancelled' });

    inbox.ring(caller, ring(SAM, { ringId: 'ring-0002' }));
    inbox.disconnected(caller);
    assert.deepEqual(phone.last('ring-cancelled'), { type: 'ring-cancelled', ringId: 'ring-0002', reason: 'cancelled' });
  });

  it('nobody answering times out on both sides', () => {
    const timers = fakeTimers();
    const inbox = new Inbox({ timers });
    const phone = conn('phone');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.ring(caller, ring(SAM));
    timers.fireAll();
    assert.deepEqual(phone.last('ring-cancelled'), { type: 'ring-cancelled', ringId: 'ring-0001', reason: 'timeout' });
    assert.deepEqual(caller.last('ring-answered'), { type: 'ring-answered', ringId: 'ring-0001', accepted: false, reason: 'no-answer' });
  });

  it('a device that goes away stops being rung', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    inbox.listen(phone, SAM_KEY);
    assert.equal(inbox.listening(SAM), 1);
    inbox.disconnected(phone);
    assert.equal(inbox.listening(SAM), 0);
    const caller = conn('caller');
    inbox.ring(caller, ring(SAM));
    assert.equal(caller.last('ring-status').status, 'unreachable');
  });

  it('refuses a reused ring id and a flood of rings', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    const caller = conn('caller');
    inbox.listen(phone, SAM_KEY);
    inbox.ring(caller, ring(SAM));
    inbox.ring(conn('other'), ring(SAM));
    assert.equal(phone.sent.filter((m) => m.type === 'incoming').length, 1);
    for (let i = 2; i <= 6; i++) inbox.ring(caller, ring(SAM, { ringId: `ring-000${i}` }));
    assert.equal(caller.last('error').code, 'rate-limited');
  });

  it('a call without the caller proving an address still rings, anonymously', () => {
    const inbox = new Inbox({ timers: fakeTimers() });
    const phone = conn('phone');
    inbox.listen(phone, SAM_KEY);
    inbox.ring(conn('caller'), ring(SAM, { inbox: null }));
    assert.deepEqual(phone.last('incoming').from, { name: 'Salma', address: null });
  });
});

describe('ring messages', () => {
  it('parse and validate', () => {
    assert.deepEqual(parseClientMessage(JSON.stringify({ type: 'listen', inbox: SAM_KEY })), { type: 'listen', inbox: SAM_KEY });
    assert.deepEqual(parseClientMessage(JSON.stringify(ring(SAM, { room: ' Calm-Otter-4821 ', name: ' Salma\n' }))), ring(SAM));
    assert.equal(parseClientMessage(JSON.stringify(ring(SAM, { video: 'yes' }))).video, false);
    assert.deepEqual(parseClientMessage(JSON.stringify({ type: 'ring-answer', ringId: 'ring-0001', accepted: false, reason: 'whatever' })), {
      type: 'ring-answer',
      ringId: 'ring-0001',
      accepted: false,
      reason: 'declined',
    });
    for (const bad of [
      { type: 'listen', inbox: 'short' },
      { type: 'ring', to: 'not-an-address', ringId: 'ring-0001', room: 'gym' },
      { type: 'ring', to: SAM, ringId: 'x', room: 'gym' },
      { type: 'ring', to: SAM, ringId: 'ring-0001', room: 'gym', inbox: 'short' },
      { type: 'ring-answer', ringId: 'ring-0001' },
    ]) {
      assert.throws(() => parseClientMessage(JSON.stringify(bad)), ProtocolError, JSON.stringify(bad));
    }
  });
});
