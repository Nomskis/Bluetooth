/**
 * Where the other person's voice spends its time on the way to your ears,
 * from WebRTC stats. Same rules as android/.../call/DelayBreakdown.kt.
 */

/** Their capture buffer, a 10 ms packet and Opus look-ahead: an estimate, not visible from here. */
export const SENDER_ESTIMATE_MS = 30;

function selectedPair(stats) {
  let transport = null;
  const byId = new Map();
  for (const s of stats) {
    byId.set(s.id, s);
    if (s.type === 'transport' && s.selectedCandidatePairId) transport = s;
  }
  if (transport) return byId.get(transport.selectedCandidatePairId) ?? null;
  for (const s of stats) {
    if (s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated) return s;
  }
  return null;
}

/**
 * How well their voice gets to us: 'good', 'fair' or 'poor', null until
 * known. On a call between two countries this is mostly their uplink. Same
 * thresholds as DelayBreakdown.kt.
 */
export function fromThem(d) {
  if (d.lossPercent == null && d.concealedPercent == null && d.jitterBufferMs == null && d.networkMs == null) return null;
  const loss = d.lossPercent ?? 0;
  const concealed = d.concealedPercent ?? 0;
  const buffer = d.jitterBufferMs ?? 0;
  const network = d.networkMs ?? 0;
  if (loss >= 8 || concealed >= 3 || buffer >= 250 || network >= 300) return 'poor';
  if (loss >= 2 || concealed >= 0.5 || buffer >= 120 || network >= 150) return 'fair';
  return 'good';
}

/** How well our voice gets to them, from what their side reports back. */
export function toThem(d) {
  if (d.sendLossPercent == null && d.networkMs == null) return null;
  const loss = d.sendLossPercent ?? 0;
  const network = d.networkMs ?? 0;
  if (loss >= 8 || network >= 300) return 'poor';
  if (loss >= 2 || network >= 150) return 'fair';
  return 'good';
}

/** Loss, network delay or jitter, either way, bad enough to stutter or lag. */
export function isWeak(d) {
  return fromThem(d) === 'poor' || toThem(d) === 'poor';
}

/** "Weak connection from Sam", "to Sam", or just "Weak connection" both ways; null when it isn't. */
export function weakLabel(d, name) {
  const from = fromThem(d) === 'poor';
  const to = toThem(d) === 'poor';
  const who = name || 'them';
  if (from && to) return 'Weak connection';
  if (from) return `Weak connection from ${who}`;
  if (to) return `Weak connection to ${who}`;
  return null;
}

/** True when the connection in use goes through a TURN relay; null until known. */
export function relayed(stats) {
  const list = [...stats];
  const pair = selectedPair(list);
  if (!pair) return null;
  const types = [pair.localCandidateId, pair.remoteCandidateId]
    .map((id) => list.find((s) => s.id === id)?.candidateType)
    .filter(Boolean);
  return types.length ? types.includes('relay') : null;
}

/**
 * Keeps the previous report's cumulative jitter-buffer counters so the
 * buffer figure is the recent average, not the call's lifetime one.
 */
export class DelayTracker {
  #lastDelay = null;
  #lastEmitted = null;
  #jitterMs = null;
  #lastReceived = null;
  #lastLost = null;
  #lossPercent = null;
  #lastConcealed = null;
  #lastSamples = null;
  #concealedPercent = null;

  /**
   * stats: an iterable of stats objects (RTCStatsReport values). outputMs: the
   * device's output latency, if known. packetMs: the audio packet length we
   * ask them for (longer on a rough link, see ptime.js).
   */
  update(stats, outputMs, packetMs = 10) {
    const senderMs = SENDER_ESTIMATE_MS - 10 + packetMs;
    const list = [...stats];
    const pair = selectedPair(list);
    const rtt = pair?.currentRoundTripTime;
    const inbound = list.find((s) => s.type === 'inbound-rtp' && s.kind === 'audio');
    const delay = inbound?.jitterBufferDelay;
    const emitted = inbound?.jitterBufferEmittedCount;
    if (typeof delay === 'number' && typeof emitted === 'number') {
      if (this.#lastDelay !== null && emitted > this.#lastEmitted && delay >= this.#lastDelay) {
        this.#jitterMs = Math.round(((delay - this.#lastDelay) / (emitted - this.#lastEmitted)) * 1000);
      } else if (this.#lastDelay === null && emitted > 0) {
        this.#jitterMs = Math.round((delay / emitted) * 1000);
      }
      this.#lastDelay = delay;
      this.#lastEmitted = emitted;
    }
    const received = inbound?.packetsReceived;
    const lost = inbound?.packetsLost;
    if (typeof received === 'number' && typeof lost === 'number') {
      if (this.#lastReceived !== null && received >= this.#lastReceived && lost >= this.#lastLost) {
        const expected = received - this.#lastReceived + (lost - this.#lastLost);
        if (expected > 0) this.#lossPercent = ((lost - this.#lastLost) / expected) * 100;
      }
      this.#lastReceived = received;
      this.#lastLost = lost;
    }
    const concealed = inbound?.concealedSamples;
    const samples = inbound?.totalSamplesReceived;
    if (typeof concealed === 'number' && typeof samples === 'number') {
      if (this.#lastSamples !== null && samples > this.#lastSamples && concealed >= this.#lastConcealed) {
        this.#concealedPercent = ((concealed - this.#lastConcealed) / (samples - this.#lastSamples)) * 100;
      }
      this.#lastConcealed = concealed;
      this.#lastSamples = samples;
    }
    // Their side's last receiver report on what we send: audio, or video until audio has one.
    const remote = ['audio', 'video']
      .map((kind) => list.find((s) => s.type === 'remote-inbound-rtp' && s.kind === kind)?.fractionLost)
      .find((f) => typeof f === 'number');
    const networkMs = typeof rtt === 'number' ? Math.round((rtt * 1000) / 2) : null;
    const parts = {
      senderMs,
      packetMs,
      networkMs,
      jitterBufferMs: this.#jitterMs,
      outputMs: outputMs ?? null,
      lossPercent: this.#lossPercent,
      relayed: relayed(list),
      concealedPercent: this.#concealedPercent,
      sendLossPercent: typeof remote === 'number' ? remote * 100 : null,
    };
    const known = networkMs !== null && this.#jitterMs !== null && parts.outputMs !== null;
    return { ...parts, totalMs: known ? senderMs + networkMs + this.#jitterMs + parts.outputMs : null };
  }
}
