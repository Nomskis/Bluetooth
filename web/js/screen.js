/**
 * Watching the other person's shared screen in the browser. The Android app does the same in
 * ScreenWatch.kt; docs/protocol.md ("Screen sharing") describes the messages.
 *
 * The screen comes through Cloudflare on a connection of its own, next to the call: the server
 * gets us an offer from the Cloudflare site nearest us, we answer it, and the screen arrives
 * from there. What arrives is measured and the sharer is asked for less when our link can't
 * keep up (ScreenPace, the same rules as the app's).
 *
 * Events:
 *   'screen' detail = { stream: MediaStream | null, from: string | null }
 */

/** Listed in our join message: we can watch a shared screen. */
export const SCREEN_CAPABILITY = 'screen';
/** Listed when this browser decodes AV1, the sharpest codec for a screen. */
export const SCREEN_AV1_CAPABILITY = 'screen-av1';

const CLOUDFLARE_STUN = 'stun:stun.cloudflare.com:3478';
const STATS_INTERVAL_MS = 2000;
const RETRY_MS = 2000;
const MAX_FAILURES = 6;
const CONNECT_TIMEOUT_MS = 15_000;
/** The sharer sends a frame at least every second and a half, even of a still screen. */
const NO_FRAMES_MS = 8000;

/**
 * [sdp] with every Opus codec asking for stereo music: the shared app's sound arrives in
 * stereo only when our answer asks for it (same as ScreenTuning.stereoOpus).
 */
export function stereoOpus(sdp, kbps = 128) {
  const eol = sdp.includes('\r\n') ? '\r\n' : '\n';
  const lines = sdp.split(eol);
  const opus = new Set(lines.map((l) => /^a=rtpmap:(\d+) opus\/48000\/2$/i.exec(l)?.[1]).filter(Boolean));
  const wanted = { stereo: '1', 'sprop-stereo': '1', maxaveragebitrate: String(kbps * 1000) };
  for (const pt of opus) {
    const fmtp = lines.findIndex((l) => l.startsWith(`a=fmtp:${pt} `));
    if (fmtp >= 0) {
      const params = new Map(
        lines[fmtp].slice(lines[fmtp].indexOf(' ') + 1).split(';').map((p) => p.trim()).filter(Boolean)
          .map((p) => [p.split('=')[0], p.includes('=') ? p.slice(p.indexOf('=') + 1) : '']),
      );
      for (const [k, v] of Object.entries(wanted)) params.set(k, v);
      lines[fmtp] = `a=fmtp:${pt} ${[...params].map(([k, v]) => `${k}=${v}`).join(';')}`;
    } else {
      const rtpmap = lines.findIndex((l) => l.startsWith(`a=rtpmap:${pt} `));
      lines.splice(rtpmap + 1, 0, `a=fmtp:${pt} ${Object.entries(wanted).map(([k, v]) => `${k}=${v}`).join(';')}`);
    }
  }
  return lines.join(eol);
}

/** Whether this browser can decode AV1, so a sharer may send it. */
export function decodesAv1() {
  try {
    return (RTCRtpReceiver.getCapabilities?.('video')?.codecs ?? []).some((c) => /\/av1$/i.test(c.mimeType));
  } catch {
    return false;
  }
}

/**
 * How much to ask the sharer for, from what arrives: just under what got through when
 * packets go missing or the picture froze, more after a calm spell while the ceiling is in
 * use. Same numbers as ScreenPace.kt.
 */
export class ScreenPace {
  static MIN_KBPS = 150;
  static MAX_KBPS = 2500;
  static LOSS_LIMIT = 0.05;
  static MIN_PACKETS = 20;
  static BACK_OFF = 0.8;
  static HOLD_MS = 6000;
  static CALM_MS = 10_000;
  static RAISE = 1.25;
  static BINDING = 0.7;

  capKbps = null;
  #last = null;
  #lastAt = 0;
  #calmSince = 0;
  #heldUntil = 0;

  /** One sample of cumulative counters; true when capKbps changed enough to tell the sharer. */
  update(counters, nowMs) {
    const before = this.#last;
    const beforeAt = this.#lastAt;
    this.#last = counters;
    this.#lastAt = nowMs;
    if (!before || nowMs <= beforeAt) {
      this.#calmSince = nowMs;
      return false;
    }
    const received = counters.packetsReceived - before.packetsReceived;
    const lost = Math.max(0, counters.packetsLost - before.packetsLost);
    if (received < 0 || counters.bytesReceived < before.bytesReceived) {
      this.#calmSince = nowMs;
      return false;
    }
    const kbps = ((counters.bytesReceived - before.bytesReceived) * 8) / 1000 / ((nowMs - beforeAt) / 1000);
    const loss = received + lost > 0 ? lost / (received + lost) : 0;
    const froze = (counters.freezeCount ?? 0) > (before.freezeCount ?? 0);
    const struggling = (loss > ScreenPace.LOSS_LIMIT && received + lost >= ScreenPace.MIN_PACKETS) || froze;
    const old = this.capKbps;
    if (struggling) {
      this.#calmSince = nowMs;
      if (nowMs < this.#heldUntil && old !== null) return false;
      const next = Math.min(ScreenPace.MAX_KBPS, Math.max(ScreenPace.MIN_KBPS, Math.round(kbps * ScreenPace.BACK_OFF)));
      this.capKbps = old === null ? next : Math.min(old, next);
      this.#heldUntil = nowMs + ScreenPace.HOLD_MS;
    } else if (old !== null && nowMs - this.#calmSince >= ScreenPace.CALM_MS) {
      this.#calmSince = nowMs;
      if (kbps >= old * ScreenPace.BINDING) {
        const next = Math.round(old * ScreenPace.RAISE);
        this.capKbps = next >= ScreenPace.MAX_KBPS ? null : next;
      }
    }
    const now = this.capKbps;
    if (old === now) return false;
    if (old === null || now === null) return true;
    return Math.abs(now - old) >= old * 0.1;
  }

  reset() {
    this.capKbps = null;
    this.#last = null;
    this.#lastAt = 0;
    this.#heldUntil = 0;
  }
}

export class ScreenWatcher extends EventTarget {
  #signaling;
  #engine;
  #pc = null;
  #from = null;
  #failures = 0;
  #timer = null;
  #statsTimer = null;
  #pace = new ScreenPace();
  #ops = Promise.resolve();
  #connectedAt = 0;
  #frames = 0;
  #frameAt = 0;

  /**
   * @param {import('./signaling.js').SignalingClient} signaling
   * @param {import('./call.js').CallEngine} engine the call, for ICE servers, who's in it, and our media-state
   */
  constructor(signaling, engine) {
    super();
    this.#signaling = signaling;
    this.#engine = engine;
    signaling.addEventListener('message', (e) => this.#enqueue(() => this.#onMessage(e.detail)));
    engine.addEventListener('peer', (e) => {
      if (!e.detail) this.#end();
    });
  }

  /** Who's sharing, while there's a share to watch. */
  get from() {
    return this.#from;
  }

  #enqueue(fn) {
    this.#ops = this.#ops.then(fn).catch((err) => console.error('[earshot] screen error', err));
  }

  async #onMessage(msg) {
    switch (msg.type) {
      case 'joined': {
        const from = msg.screen?.from;
        if (from && msg.peers?.some((p) => p.peerId === from)) this.#begin(from);
        else this.#end();
        return;
      }
      case 'screen-started':
        if (msg.from === this.#engine.remotePeer?.peerId) this.#begin(msg.from);
        return;
      case 'screen-stopped':
        if (!msg.from || msg.from === this.#from) this.#end();
        return;
      case 'screen-offer':
        return this.#onOffer(msg.watch, msg.sdp);
      case 'screen-error':
        if (this.#from) this.#retryLater();
        return;
      default:
        return;
    }
  }

  #begin(from) {
    this.#from = from;
    this.#failures = 0;
    this.#signaling.send({ type: 'screen-watch' });
    this.#watchdog(CONNECT_TIMEOUT_MS);
  }

  async #onOffer(watch, sdp) {
    if (!this.#from) return;
    this.#close();
    const pc = new RTCPeerConnection({
      iceServers: [...(this.#engine.iceServers ?? []), { urls: CLOUDFLARE_STUN }],
      bundlePolicy: 'max-bundle',
    });
    this.#pc = pc;
    // The picture and, when the sharer's app makes some, its sound, in one stream to play.
    const stream = new MediaStream();
    pc.ontrack = (e) => {
      if (pc !== this.#pc) return;
      stream.addTrack(e.track);
      if (e.track.kind !== 'video') return;
      // A little more buffer than a call: a shared screen would rather wait than stutter.
      try {
        e.receiver.jitterBufferTarget = 200;
      } catch {
        // Older browsers.
      }
      this.dispatchEvent(new CustomEvent('screen', { detail: { stream, from: this.#from } }));
    };
    pc.oniceconnectionstatechange = () => {
      if (pc !== this.#pc) return;
      const state = pc.iceConnectionState;
      if (state === 'connected' || state === 'completed') {
        clearTimeout(this.#timer);
        this.#connectedAt = Date.now();
        this.#watchStats(pc);
      } else if (state === 'failed') {
        this.#retryLater();
      } else if (state === 'disconnected') {
        this.#watchdog(6000);
      }
    };
    await pc.setRemoteDescription({ type: 'offer', sdp });
    const created = await pc.createAnswer();
    if (pc !== this.#pc) return;
    const answer = { type: 'answer', sdp: stereoOpus(created.sdp) };
    await pc.setLocalDescription(answer);
    this.#signaling.send({ type: 'screen-answer', watch, sdp: answer.sdp });
  }

  #watchStats(pc) {
    clearInterval(this.#statsTimer);
    this.#statsTimer = setInterval(async () => {
      if (pc !== this.#pc) return;
      const report = await pc.getStats();
      if (pc !== this.#pc) return;
      let counters = null;
      let frames = 0;
      report.forEach((s) => {
        if (s.type === 'inbound-rtp' && s.kind === 'video') {
          counters = { bytesReceived: s.bytesReceived ?? 0, packetsReceived: s.packetsReceived ?? 0, packetsLost: s.packetsLost ?? 0, freezeCount: s.freezeCount ?? 0 };
          frames = s.framesDecoded ?? 0;
        }
      });
      const now = Date.now();
      if (frames > this.#frames) {
        // Only a picture counts as working: a connection without one is tried again.
        this.#frames = frames;
        this.#frameAt = now;
        this.#failures = 0;
      } else if (now - Math.max(this.#frameAt, this.#connectedAt) > NO_FRAMES_MS) {
        // The share went away under us (published again, or dropped): ask for it again.
        clearInterval(this.#statsTimer);
        this.#retryLater();
        return;
      }
      if (counters && this.#pace.update(counters, now)) this.#engine.setScreenKbps(this.#pace.capKbps);
    }, STATS_INTERVAL_MS);
  }

  #watchdog(afterMs) {
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => {
      const state = this.#pc?.iceConnectionState;
      if (this.#from && state !== 'connected' && state !== 'completed') this.#retryLater();
    }, afterMs);
  }

  #retryLater() {
    const who = this.#from;
    if (!who) return;
    this.#failures += 1;
    if (this.#failures > MAX_FAILURES) {
      // Back to the call until they share again.
      clearTimeout(this.#timer);
      this.#close();
      this.dispatchEvent(new CustomEvent('screen', { detail: { stream: null, from: null } }));
      return;
    }
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => {
      if (this.#from === who) this.#begin(who);
    }, RETRY_MS * this.#failures);
  }

  #end() {
    if (!this.#from && !this.#pc) return;
    this.#from = null;
    clearTimeout(this.#timer);
    this.#close();
    this.#pace.reset();
    this.#engine.setScreenKbps(null);
    this.dispatchEvent(new CustomEvent('screen', { detail: { stream: null, from: null } }));
  }

  #close() {
    clearInterval(this.#statsTimer);
    this.#pc?.close();
    this.#pc = null;
    this.#frames = 0;
    this.#frameAt = 0;
  }
}
