import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { parseClientMessage } from '../src/protocol.js';
import { RoomManager } from '../src/rooms.js';
import { createScreenRelay, TRACK_NAME } from '../src/screen-relay.js';
import { ScreenShares } from '../src/screens.js';

class FakeConn {
  constructor() {
    this.sent = [];
    this.member = null;
  }
  send(msg) {
    this.sent.push(msg);
  }
  close() {}
  last(type) {
    return this.sent.filter((m) => m.type === type).at(-1);
  }
}

const join = (room, peerId) => ({ type: 'join', room, peerId, name: peerId, client: { platform: 'test', version: '0', capabilities: [] } });
const quiet = { warn() {} };

/** Stands in for Cloudflare: publishing answers, pulling offers, and records what it was asked. */
function fakeRelay() {
  const calls = [];
  let next = 0;
  return {
    calls,
    failNext: false,
    async publish(sdp, mid) {
      calls.push({ op: 'publish', sdp, mid });
      if (this.failNext) throw new Error('down');
      return { sessionId: `pub-session-${++next}`, trackName: TRACK_NAME, sdp: 'answer-sdp' };
    },
    async pull(share) {
      calls.push({ op: 'pull', share });
      if (this.failNext) throw new Error('down');
      return { sessionId: `watch-session-${++next}`, sdp: 'offer-sdp' };
    },
    async answer(sessionId, sdp) {
      calls.push({ op: 'answer', sessionId, sdp });
    },
  };
}

describe('screen sharing', () => {
  let relay;
  let screens;
  let rooms;
  let clock;
  let sam;
  let salma;

  beforeEach(() => {
    relay = fakeRelay();
    clock = 0;
    screens = new ScreenShares({ relay, log: quiet, now: () => clock });
    rooms = new RoomManager({
      maxPeersPerRoom: 2,
      reconnectGraceMs: 0,
      iceServersFor: () => [],
      describeRoom: (room) => screens.describe(room),
      onRemoved: (member) => screens.memberRemoved(member),
    });
    sam = new FakeConn();
    salma = new FakeConn();
    rooms.join(sam, join('calm-otter-4821', 'sam-peer-0001'));
    rooms.join(salma, join('calm-otter-4821', 'salma-peer-01'));
  });

  it('says in joined that this server can share screens', () => {
    assert.deepEqual(sam.last('joined').features, ['screen']);
    const off = new ScreenShares({ relay: null });
    assert.deepEqual(off.describe({ code: 'x' }), { features: [] });
  });

  it('publishes the sharer, answers it and tells the other person', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    assert.deepEqual(relay.calls[0], { op: 'publish', sdp: 'offer', mid: '0' });
    assert.deepEqual(sam.last('screen-published'), { type: 'screen-published', sdp: 'answer-sdp' });
    assert.deepEqual(salma.last('screen-started'), { type: 'screen-started', from: 'sam-peer-0001' });
  });

  it('lets the viewer pull the screen and passes its answer on', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    await screens.watch(salma);
    const offer = salma.last('screen-offer');
    assert.equal(offer.sdp, 'offer-sdp');
    assert.deepEqual(relay.calls[1].share, { from: 'sam-peer-0001', sessionId: 'pub-session-1', trackName: TRACK_NAME });
    await screens.answer(salma, { watch: offer.watch, sdp: 'answer' });
    assert.deepEqual(relay.calls[2], { op: 'answer', sessionId: offer.watch, sdp: 'answer' });
  });

  it('only answers for the session it handed out', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    await screens.watch(salma);
    await screens.answer(salma, { watch: 'someone-elses-session', sdp: 'answer' });
    assert.equal(salma.last('screen-error').code, 'stale');
    assert.equal(relay.calls.filter((c) => c.op === 'answer').length, 0);
  });

  it('tells someone who joins later that a share is on', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    rooms.leave(salma);
    const back = new FakeConn();
    rooms.join(back, join('calm-otter-4821', 'salma-peer-01'));
    assert.deepEqual(back.last('joined').screen, { from: 'sam-peer-0001' });
  });

  it('allows one sharer per room', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    await screens.publish(salma, { sdp: 'offer', mid: '0' });
    assert.equal(salma.last('screen-error').code, 'in-use');
    assert.equal(relay.calls.length, 1);
  });

  it('ends the share when the sharer stops or leaves', async () => {
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    screens.stop(sam);
    assert.deepEqual(salma.last('screen-stopped'), { type: 'screen-stopped', from: 'sam-peer-0001' });
    clock += 5000;
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    salma.sent = [];
    rooms.leave(sam);
    assert.deepEqual(salma.last('screen-stopped'), { type: 'screen-stopped', from: 'sam-peer-0001' });
    await screens.watch(salma);
    assert.equal(salma.last('screen-offer'), undefined);
  });

  it('never calls Cloudflare for someone outside a call, or too often', async () => {
    const stranger = new FakeConn();
    await screens.publish(stranger, { sdp: 'offer', mid: '0' });
    assert.equal(stranger.last('error').code, 'not-in-room');
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    assert.equal(sam.last('screen-error').code, 'busy');
    assert.equal(relay.calls.length, 1);
  });

  it('says so when Cloudflare is down or not set up', async () => {
    relay.failNext = true;
    await screens.publish(sam, { sdp: 'offer', mid: '0' });
    assert.equal(sam.last('screen-error').code, 'relay');
    assert.equal(salma.last('screen-started'), undefined);
    const off = new ScreenShares({ relay: null, log: quiet });
    await off.publish(sam, { sdp: 'offer', mid: '0' });
    assert.equal(sam.last('screen-error').code, 'unavailable');
  });

  it('checks the messages', () => {
    assert.deepEqual(parseClientMessage(JSON.stringify({ type: 'screen-publish', sdp: 'v=0', mid: '0' })), { type: 'screen-publish', sdp: 'v=0', mid: '0' });
    assert.deepEqual(parseClientMessage(JSON.stringify({ type: 'screen-watch' })), { type: 'screen-watch' });
    assert.deepEqual(parseClientMessage(JSON.stringify({ type: 'screen-stop' })), { type: 'screen-stop' });
    assert.throws(() => parseClientMessage(JSON.stringify({ type: 'screen-publish', sdp: 'v=0' })));
    assert.throws(() => parseClientMessage(JSON.stringify({ type: 'screen-answer', sdp: 'v=0', watch: '../x' })));
  });
});

describe('Cloudflare screen relay', () => {
  function fakeFetch(responses) {
    const calls = [];
    const impl = async (url, init) => {
      calls.push({ url, init });
      const next = responses.shift();
      return { ok: (next.status ?? 200) < 300, status: next.status ?? 200, json: async () => next.body };
    };
    return { impl, calls };
  }

  it('is off without an app id and secret', () => {
    assert.equal(createScreenRelay({}), null);
    assert.equal(createScreenRelay({ appId: 'a' }), null);
  });

  it('publishes with the secret, a new session and the screen track', async () => {
    const { impl, calls } = fakeFetch([
      { status: 201, body: { sessionId: 'S1' } },
      { body: { sessionDescription: { type: 'answer', sdp: 'A' }, tracks: [{ mid: '0', trackName: 'screen' }] } },
    ]);
    const relay = createScreenRelay({ appId: 'app', appSecret: 'secret' }, { fetchImpl: impl });
    assert.deepEqual(await relay.publish('O', '0'), { sessionId: 'S1', trackName: 'screen', sdp: 'A' });
    assert.equal(calls[0].url, 'https://rtc.live.cloudflare.com/v1/apps/app/sessions/new');
    assert.equal(calls[0].init.headers.Authorization, 'Bearer secret');
    assert.equal(calls[1].url, 'https://rtc.live.cloudflare.com/v1/apps/app/sessions/S1/tracks/new');
    assert.deepEqual(JSON.parse(calls[1].init.body), {
      sessionDescription: { type: 'offer', sdp: 'O' },
      tracks: [{ location: 'local', mid: '0', trackName: 'screen' }],
    });
  });

  it('pulls into a new session and sends the answer back', async () => {
    const { impl, calls } = fakeFetch([
      { body: { sessionId: 'V1' } },
      { body: { requiresImmediateRenegotiation: true, sessionDescription: { type: 'offer', sdp: 'OFF' }, tracks: [{ mid: '0' }] } },
      { body: {} },
    ]);
    const relay = createScreenRelay({ appId: 'app', appSecret: 's' }, { fetchImpl: impl });
    assert.deepEqual(await relay.pull({ sessionId: 'S1', trackName: 'screen' }), { sessionId: 'V1', sdp: 'OFF' });
    assert.deepEqual(JSON.parse(calls[1].init.body), { tracks: [{ location: 'remote', sessionId: 'S1', trackName: 'screen' }] });
    await relay.answer('V1', 'ANS');
    assert.equal(calls[2].url, 'https://rtc.live.cloudflare.com/v1/apps/app/sessions/V1/renegotiate');
    assert.equal(calls[2].init.method, 'PUT');
    assert.deepEqual(JSON.parse(calls[2].init.body), { sessionDescription: { type: 'answer', sdp: 'ANS' } });
  });

  it('turns Cloudflare errors into one kind of error', async () => {
    const { impl } = fakeFetch([
      { body: { sessionId: 'S1' } },
      { body: { tracks: [{ errorCode: 'bad', errorDescription: 'track refused' }] } },
    ]);
    const relay = createScreenRelay({ appId: 'app', appSecret: 's' }, { fetchImpl: impl });
    await assert.rejects(relay.publish('O', '0'), /track refused/);
    const { impl: denied } = fakeFetch([{ status: 401, body: { errorCode: 'auth', errorDescription: 'bad token' } }]);
    await assert.rejects(createScreenRelay({ appId: 'a', appSecret: 's' }, { fetchImpl: denied }).publish('O', '0'), /bad token/);
  });
});
