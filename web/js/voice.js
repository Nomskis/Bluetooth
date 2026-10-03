/**
 * The head-start cue for the browser: notices the other person starting to
 * talk as their audio is decoded, before the speakers or Bluetooth
 * headphones play it. Same detector as the Android app
 * (android/.../audio/VoiceActivityDetector.kt).
 */

/** Small adaptive voice detector over 10 ms frames' RMS levels (0..1). */
export class VoiceActivityDetector {
  constructor({ attackFrames = 2, hangoverFrames = 30, minLevel = 0.003, ratio = 3 } = {}) {
    this.attackFrames = attackFrames;
    this.hangoverFrames = hangoverFrames;
    this.minLevel = minLevel;
    this.ratio = ratio;
    this.speaking = false;
    this.noiseFloor = 0.01;
    this.loud = 0;
    this.quiet = 0;
  }

  /** Feeds one frame's level; returns the new state when it changes, else null. */
  process(rms) {
    // The floor drops quickly and rises slowly, so speech doesn't become the floor.
    this.noiseFloor = rms < this.noiseFloor ? this.noiseFloor * 0.9 + rms * 0.1 : this.noiseFloor * 0.998 + rms * 0.002;
    const threshold = Math.max(this.minLevel, this.noiseFloor * this.ratio);
    if (rms > threshold) {
      this.loud++;
      this.quiet = 0;
    } else {
      this.quiet++;
      this.loud = 0;
    }
    if (!this.speaking && this.loud >= this.attackFrames) {
      this.speaking = true;
      return true;
    }
    if (this.speaking && this.quiet >= this.hangoverFrames) {
      this.speaking = false;
      return false;
    }
    return null;
  }
}

/** RMS of a block of float samples. */
export function rms(samples) {
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  return samples.length ? Math.sqrt(sum / samples.length) : 0;
}

/**
 * Watches a remote MediaStream and calls onChange(speaking). Also exposes the
 * AudioContext's output latency, the browser's estimate of how long audio
 * takes to reach the speakers or headphones.
 */
export class RemoteVoiceWatcher {
  #context = null;
  #source = null;
  #analyser = null;
  #timer = null;
  #detector = new VoiceActivityDetector();
  #buffer = null;

  constructor(onChange) {
    this.onChange = onChange;
  }

  get speaking() {
    return this.#detector.speaking;
  }

  /** Output latency in ms (outputLatency + baseLatency), or null when the browser doesn't say. */
  get outputLatencyMs() {
    const ctx = this.#context;
    if (!ctx) return null;
    const total = (ctx.outputLatency || 0) + (ctx.baseLatency || 0);
    return total > 0 ? Math.round(total * 1000) : null;
  }

  /**
   * Call from the Join tap: Safari only lets an AudioContext start inside a
   * user gesture, and the other side's stream arrives much later.
   */
  prime() {
    const Ctx = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!Ctx) return;
    try {
      this.#context ??= new Ctx({ latencyHint: 'interactive' });
      this.#context.resume?.().catch(() => {});
    } catch {
      this.#context = null;
    }
  }

  watch(stream) {
    this.stop();
    if (!stream || stream.getAudioTracks().length === 0) return;
    this.prime();
    if (!this.#context) return;
    this.#source = this.#context.createMediaStreamSource(stream);
    this.#analyser = this.#context.createAnalyser();
    // 480 samples is 10 ms at 48 kHz, one detector frame.
    this.#analyser.fftSize = 512;
    this.#buffer = new Float32Array(480);
    this.#source.connect(this.#analyser);
    this.#detector = new VoiceActivityDetector();
    this.#timer = setInterval(() => this.#tick(), 10);
  }

  /** Call from a user gesture if the browser started the context suspended. */
  resume() {
    this.#context?.resume?.().catch(() => {});
  }

  #tick() {
    if (!this.#analyser) return;
    this.#analyser.getFloatTimeDomainData(this.#buffer);
    const change = this.#detector.process(rms(this.#buffer));
    if (change !== null) this.onChange(change);
  }

  stop() {
    clearInterval(this.#timer);
    this.#timer = null;
    this.#source?.disconnect();
    this.#source = null;
    this.#analyser = null;
    if (this.#detector.speaking) {
      this.#detector.speaking = false;
      this.onChange(false);
    }
  }
}
