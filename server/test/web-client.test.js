// Unit tests for the browser client's pure logic (web/js), run with the server's tests.
import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import { ChatLog, MAX_CHAT_LENGTH, parseChat } from '../../web/js/chat.js';
import { DelayTracker, SENDER_ESTIMATE_MS, isWeak, relayed } from '../../web/js/delay.js';
import { opusMaxAverageBitrate, preferHdVoice } from '../../web/js/sdp.js';
import { VoiceActivityDetector, rms } from '../../web/js/voice.js';

test('voice detector: quick to start, slow to stop', () => {
  const vad = new VoiceActivityDetector();
  for (let i = 0; i < 50; i++) assert.equal(vad.process(0.001), null); // quiet room
  assert.equal(vad.process(0.2), null);
  assert.equal(vad.process(0.2), true); // two loud frames: 20 ms
  for (let i = 0; i < 29; i++) assert.equal(vad.process(0.001), null); // a pause between words
  assert.equal(vad.process(0.001), false); // 300 ms of quiet ends it
});

test('rms of a sine is amplitude / sqrt(2)', () => {
  const samples = Float32Array.from({ length: 480 }, (_, i) => 0.5 * Math.sin((2 * Math.PI * i) / 48));
  assert.ok(Math.abs(rms(samples) - 0.5 / Math.SQRT2) < 1e-3);
});

test('delay tracker adds up network, buffer and output', () => {
  const tracker = new DelayTracker();
  const report = (buffer, emitted) => [
    { id: 'T', type: 'transport', selectedCandidatePairId: 'P' },
    { id: 'P', type: 'candidate-pair', currentRoundTripTime: 0.06 },
    { id: 'I', type: 'inbound-rtp', kind: 'audio', jitterBufferDelay: buffer, jitterBufferEmittedCount: emitted },
  ];
  const first = tracker.update(report(2400, 48000), 40);
  assert.equal(first.networkMs, 30);
  assert.equal(first.jitterBufferMs, 50);
  assert.equal(first.totalMs, SENDER_ESTIMATE_MS + 30 + 50 + 40);
  const second = tracker.update(report(2400 + 96000 * 0.04, 144000), 40);
  assert.equal(second.jitterBufferMs, 40);
  assert.equal(new DelayTracker().update(report(2400, 48000), null).totalMs, null);
});

test('HD voice sets maxaveragebitrate on the Opus line only', () => {
  const sdp = ['m=audio 9 UDP/TLS/RTP/SAVPF 63 111', 'a=rtpmap:63 red/48000/2', 'a=fmtp:63 111/111', 'a=rtpmap:111 opus/48000/2', 'a=fmtp:111 minptime=10;useinbandfec=1', ''].join('\r\n');
  const tuned = preferHdVoice(sdp);
  assert.match(tuned, /a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=48000/);
  assert.match(tuned, /a=fmtp:63 111\/111/);
  assert.equal(preferHdVoice(tuned), tuned);
  assert.equal(opusMaxAverageBitrate(tuned), 48000);
  assert.equal(opusMaxAverageBitrate(sdp), null);
});

test('delay tracker reports recent packet loss', () => {
  const tracker = new DelayTracker();
  const report = (received, lost) => [{ id: 'I', type: 'inbound-rtp', kind: 'audio', packetsReceived: received, packetsLost: lost }];
  assert.equal(tracker.update(report(1000, 10), null).lossPercent, null);
  assert.equal(tracker.update(report(1196, 14), null).lossPercent, 2);
  assert.equal(tracker.update(report(1396, 14), null).lossPercent, 0);
});

/** Stands in for an RTCDataChannel; `wire` collects what was sent. */
class FakeChannel extends EventTarget {
  readyState = 'connecting';
  wire = [];
  send(text) {
    this.wire.push(JSON.parse(text));
  }
  open() {
    this.readyState = 'open';
    this.dispatchEvent(new Event('open'));
  }
  deliver(frame) {
    this.dispatchEvent(Object.assign(new Event('message'), { data: JSON.stringify(frame) }));
  }
}

function chatLog() {
  let n = 0;
  return new ChatLog({ newId: () => `m-${++n}`, now: () => 1000 });
}

test('chat: queued until the channel opens, then delivered on acknowledgement', () => {
  const chat = chatLog();
  const channel = new FakeChannel();
  chat.attach(channel);
  assert.equal(chat.send('   '), null);
  chat.send('  One sec ');
  assert.deepEqual(channel.wire, []);
  channel.open();
  assert.deepEqual(channel.wire, [{ kind: 'chat', id: 'm-1', text: 'One sec', sentAt: 1000 }]);
  assert.equal(chat.messages[0].status, 'sending');
  channel.deliver({ kind: 'chat-ack', id: 'm-1' });
  assert.equal(chat.messages[0].status, 'delivered');
});

test('chat: unacknowledged messages go again on the next connection, and repeats are dropped', () => {
  const chat = chatLog();
  const first = new FakeChannel();
  chat.attach(first);
  first.open();
  chat.send('Can you hear me?');
  chat.detach();
  const second = new FakeChannel();
  chat.attach(second);
  second.open();
  assert.equal(second.wire.length, 1);
  assert.equal(second.wire[0].id, 'm-1');

  const incoming = [];
  chat.addEventListener('message', (e) => incoming.push(e.detail.text));
  second.deliver({ kind: 'chat', id: 'x-1', text: 'Yes!', sentAt: 5 });
  second.deliver({ kind: 'chat', id: 'x-1', text: 'Yes!', sentAt: 5 });
  assert.deepEqual(incoming, ['Yes!']);
  // Both copies are acknowledged: the first acknowledgement may have been lost.
  assert.deepEqual(second.wire.filter((f) => f.kind === 'chat-ack').map((f) => f.id), ['x-1', 'x-1']);
  // Frames from a channel that was replaced are ignored.
  first.deliver({ kind: 'chat', id: 'x-2', text: 'old', sentAt: 6 });
  assert.equal(chat.messages.filter((m) => !m.mine).length, 1);
});

test('chat: a different person in the room means undelivered messages failed', () => {
  const chat = chatLog();
  chat.send('hello?');
  chat.peerChanged();
  assert.equal(chat.messages[0].status, 'failed');
});

test('chat: parser accepts the shared fixtures and rejects junk', () => {
  const dir = new URL('../../protocol/fixtures/peer/', import.meta.url);
  for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json'))) {
    const raw = fs.readFileSync(new URL(file, dir), 'utf8');
    assert.equal(parseChat(raw)?.kind, JSON.parse(raw).kind, file);
  }
  assert.equal(parseChat('nope'), null);
  assert.equal(parseChat(JSON.stringify({ kind: 'chat', id: 'a', text: '  ' })), null);
  assert.equal(parseChat(JSON.stringify({ kind: 'chat', text: 'no id' })), null);
  assert.equal(parseChat(JSON.stringify({ kind: 'typing', id: 'a' })), null);
  assert.equal(parseChat(JSON.stringify({ kind: 'chat', id: 'a', text: 'x'.repeat(5000) })).text.length, MAX_CHAT_LENGTH);
});

test('delay tracker says whether the connection is relayed', () => {
  const base = [
    { id: 'T', type: 'transport', selectedCandidatePairId: 'P' },
    { id: 'P', type: 'candidate-pair', localCandidateId: 'L', remoteCandidateId: 'R' },
    { id: 'L', type: 'local-candidate', candidateType: 'srflx' },
  ];
  assert.equal(relayed(base), false);
  assert.equal(relayed([...base, { id: 'R', type: 'remote-candidate', candidateType: 'relay' }]), true);
  assert.equal(relayed([]), null);
});

test('weak connection: loss, network delay or jitter past the thresholds', () => {
  const fine = { lossPercent: 1, networkMs: 40, jitterBufferMs: 60 };
  assert.equal(isWeak(fine), false);
  assert.equal(isWeak({ ...fine, lossPercent: 12 }), true);
  assert.equal(isWeak({ ...fine, networkMs: 350 }), true);
  assert.equal(isWeak({ ...fine, jitterBufferMs: 300 }), true);
  assert.equal(isWeak({ lossPercent: null, networkMs: null, jitterBufferMs: null }), false);
});
