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
 * Keeps the previous report's cumulative jitter-buffer counters so the
 * buffer figure is the recent average, not the call's lifetime one.
 */
export class DelayTracker {
  #lastDelay = null;
  #lastEmitted = null;
  #jitterMs = null;

  /** stats: an iterable of stats objects (RTCStatsReport values). outputMs: the device's output latency, if known. */
  update(stats, outputMs) {
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
    const networkMs = typeof rtt === 'number' ? Math.round((rtt * 1000) / 2) : null;
    const parts = { senderMs: SENDER_ESTIMATE_MS, networkMs, jitterBufferMs: this.#jitterMs, outputMs: outputMs ?? null };
    const known = networkMs !== null && this.#jitterMs !== null && parts.outputMs !== null;
    return { ...parts, totalMs: known ? SENDER_ESTIMATE_MS + networkMs + this.#jitterMs + parts.outputMs : null };
  }
}
