/**
 * Screen sharing through Cloudflare's Realtime SFU (docs/research/screen-share.md).
 *
 * The sharer publishes its screen to the Cloudflare site nearest it and the viewer pulls
 * it from the site nearest them, over Cloudflare's own network in between. Lost packets
 * are resent from the site nearest the viewer, a hop of a few tens of milliseconds
 * instead of the whole Finland-Morocco round trip. The app secret stays on this server;
 * clients only ever exchange SDP with it.
 *
 * Configured with CLOUDFLARE_SFU_APP_ID and CLOUDFLARE_SFU_APP_SECRET (docs/deploy.md).
 * Cloudflare's API: https://developers.cloudflare.com/realtime/sfu/https-api/
 */

const API_BASE = 'https://rtc.live.cloudflare.com/v1/apps/';
const FETCH_TIMEOUT_MS = 8000;
/** Every share is published under this name in its own session. */
export const TRACK_NAME = 'screen';
/** And the shared app's sound, when it has some. */
export const AUDIO_TRACK_NAME = 'screen-audio';

export class ScreenRelayError extends Error {}

/** Returns null when no Cloudflare SFU app is configured. */
export function createScreenRelay({ appId, appSecret }, { fetchImpl = globalThis.fetch } = {}) {
  if (!appId || !appSecret) return null;
  const base = `${API_BASE}${encodeURIComponent(appId)}`;

  async function call(method, path, body) {
    let res;
    try {
      res = await fetchImpl(`${base}${path}`, {
        method,
        headers: { Authorization: `Bearer ${appSecret}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
    } catch (err) {
      throw new ScreenRelayError(`Cloudflare unreachable: ${err.message}`);
    }
    let json = null;
    try {
      json = await res.json();
    } catch {
      // An empty or non-JSON body; the status says what happened.
    }
    if (!res.ok || json?.errorCode) {
      throw new ScreenRelayError(json?.errorDescription || json?.errorCode || `Cloudflare said HTTP ${res.status}`);
    }
    return json ?? {};
  }

  async function newSession() {
    const { sessionId } = await call('POST', '/sessions/new');
    if (typeof sessionId !== 'string' || !sessionId) throw new ScreenRelayError('Cloudflare gave no session');
    return sessionId;
  }

  function trackErrors(json) {
    const failed = json.tracks?.find((track) => track?.errorCode);
    if (failed) throw new ScreenRelayError(failed.errorDescription || failed.errorCode);
  }

  return {
    /**
     * The sharer's offer, with the screen on transceiver [mid] and its sound, if any, on
     * [audioMid]: Cloudflare answers. Returns where viewers find it, and the answer for the sharer.
     */
    async publish(offerSdp, mid, audioMid) {
      const sessionId = await newSession();
      const tracks = [{ location: 'local', mid, trackName: TRACK_NAME }];
      if (audioMid) tracks.push({ location: 'local', mid: audioMid, trackName: AUDIO_TRACK_NAME });
      const json = await call('POST', `/sessions/${sessionId}/tracks/new`, {
        sessionDescription: { type: 'offer', sdp: offerSdp },
        tracks,
      });
      trackErrors(json);
      const sdp = json.sessionDescription?.sdp;
      if (typeof sdp !== 'string') throw new ScreenRelayError('Cloudflare gave no answer');
      return { sessionId, trackName: TRACK_NAME, ...(audioMid ? { audioTrackName: AUDIO_TRACK_NAME } : {}), sdp };
    },

    /** A new session for a viewer, pulling [publisher]'s screen (and sound): Cloudflare makes the offer. */
    async pull(publisher) {
      const sessionId = await newSession();
      const tracks = [{ location: 'remote', sessionId: publisher.sessionId, trackName: publisher.trackName }];
      if (publisher.audioTrackName) tracks.push({ location: 'remote', sessionId: publisher.sessionId, trackName: publisher.audioTrackName });
      const json = await call('POST', `/sessions/${sessionId}/tracks/new`, { tracks });
      trackErrors(json);
      const sdp = json.sessionDescription?.sdp;
      if (!json.requiresImmediateRenegotiation || typeof sdp !== 'string') {
        throw new ScreenRelayError('Cloudflare gave no offer for the screen');
      }
      return { sessionId, sdp };
    },

    /** The viewer's answer to [pull]'s offer. */
    async answer(sessionId, answerSdp) {
      await call('PUT', `/sessions/${sessionId}/renegotiate`, {
        sessionDescription: { type: 'answer', sdp: answerSdp },
      });
    },
  };
}
