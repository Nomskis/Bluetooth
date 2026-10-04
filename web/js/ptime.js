/**
 * How long the audio packets we ask the other side for should be. Same rules
 * as android/.../call/PacketTime.kt, which explains them: 10 ms packets
 * normally; longer ones (whose redundant copies then cover longer gaps) while
 * the audio we receive keeps losing runs that still have to be concealed;
 * shorter again after a calm minute.
 */

export const PACKET_STEPS_MS = [10, 20, 40];

export const ROUGH_LOSS = 0.03;
export const ROUGH_CONCEALED = 0.015;
export const ROUGH_SAMPLES = 2;
export const CALM_LOSS = 0.01;
export const CALM_CONCEALED = 0.003;
export const SETTLE_MS = 10_000;
export const CALM_MS = 60_000;
export const MAX_HOLD_MS = 240_000;
export const FAILED_MS = 30_000;
export const HELD_MS = 60_000;

export class PacketTime {
  #step = 0;
  #last = null;
  #roughSamples = 0;
  #calmSince = null;
  #changedAt = null;
  #holdMs = CALM_MS;
  #downAt = null;

  get ms() {
    return PACKET_STEPS_MS[this.#step];
  }

  /**
   * Feeds one stats interval: cumulative { packetsReceived, packetsLost,
   * concealedSamples, totalSamplesReceived } for the audio we receive.
   * Returns true when the packet length changed.
   */
  update(counters, nowMs) {
    const before = this.#last;
    this.#last = counters;
    // A new connection's counters start again from zero.
    if (!before || counters.packetsReceived < before.packetsReceived || counters.totalSamplesReceived < before.totalSamplesReceived) {
      return false;
    }
    const received = counters.packetsReceived - before.packetsReceived;
    const lost = Math.max(0, counters.packetsLost - before.packetsLost);
    const samples = counters.totalSamplesReceived - before.totalSamplesReceived;
    if (received + lost <= 0 || samples <= 0) return false;
    const loss = lost / (received + lost);
    const concealed = Math.max(0, counters.concealedSamples - before.concealedSamples) / samples;

    const rough = loss >= ROUGH_LOSS && concealed >= ROUGH_CONCEALED;
    this.#roughSamples = rough ? this.#roughSamples + 1 : 0;
    this.#calmSince = loss < CALM_LOSS && concealed < CALM_CONCEALED ? (this.#calmSince ?? nowMs) : null;
    if (this.#downAt !== null && nowMs - this.#downAt >= HELD_MS) {
      this.#holdMs = CALM_MS;
      this.#downAt = null;
    }

    const sinceChange = this.#changedAt === null ? Infinity : nowMs - this.#changedAt;
    if (this.#roughSamples >= ROUGH_SAMPLES && this.#step < PACKET_STEPS_MS.length - 1 && sinceChange >= SETTLE_MS) {
      // Rough again this soon after stepping down: the next step down waits longer.
      if (this.#downAt !== null && nowMs - this.#downAt < FAILED_MS) this.#holdMs = Math.min(this.#holdMs * 2, MAX_HOLD_MS);
      this.#downAt = null;
      this.#change(this.#step + 1, nowMs);
      return true;
    }
    if (this.#calmSince === null) return false;
    if (this.#step > 0 && nowMs - this.#calmSince >= this.#holdMs && sinceChange >= this.#holdMs) {
      this.#change(this.#step - 1, nowMs);
      this.#downAt = nowMs;
      return true;
    }
    return false;
  }

  #change(step, nowMs) {
    this.#step = step;
    this.#changedAt = nowMs;
    this.#roughSamples = 0;
    this.#calmSince = null;
  }
}

/** The counters PacketTime reads, from a stats report; null until all are there. */
export function inboundAudioCounters(stats) {
  for (const s of stats) {
    if (s.type !== 'inbound-rtp' || s.kind !== 'audio') continue;
    const { packetsReceived, packetsLost, concealedSamples, totalSamplesReceived } = s;
    if ([packetsReceived, packetsLost, concealedSamples, totalSamplesReceived].every((n) => typeof n === 'number')) {
      return { packetsReceived, packetsLost, concealedSamples, totalSamplesReceived };
    }
    return null;
  }
  return null;
}
