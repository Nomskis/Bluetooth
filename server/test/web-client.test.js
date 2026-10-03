// Unit tests for the browser client's pure logic (web/js), run with the server's tests.
import assert from 'node:assert/strict';
import test from 'node:test';
import { DelayTracker, SENDER_ESTIMATE_MS } from '../../web/js/delay.js';
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
