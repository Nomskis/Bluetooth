/**
 * SDP tweaks applied to descriptions before they're sent to the other peer.
 * Same rules as android/.../call/SdpTuning.kt.
 */

/**
 * Asks the other side to send 10 ms audio packets instead of the default 20 ms.
 * Each packet then waits half as long to fill before it's sent, which saves
 * about 10 ms of delay per direction for a little more packet overhead.
 */
export function preferLowLatencyAudio(sdp) {
  const lines = sdp.split('\r\n');
  const out = [];
  let inAudio = false;
  for (const line of lines) {
    if (line.startsWith('m=')) {
      inAudio = line.startsWith('m=audio');
      out.push(line);
      if (inAudio) out.push('a=ptime:10');
      continue;
    }
    // Drop any existing ptime in the audio section; ours replaces it.
    if (inAudio && line.startsWith('a=ptime:')) continue;
    out.push(line);
  }
  return out.join('\r\n');
}

/**
 * Puts RED (RFC 2198 redundant audio) first in the audio codec preferences.
 * Each packet then also carries the previous one, so a single lost packet is
 * repaired from the next instead of making the jitter buffer grow. Peers
 * that don't support RED fall back to plain Opus during negotiation.
 */
export function preferRedundantAudio(pc) {
  const caps = globalThis.RTCRtpReceiver?.getCapabilities?.('audio');
  if (!caps) return;
  const isRed = (c) => c.mimeType.toLowerCase() === 'audio/red';
  const red = caps.codecs.filter(isRed);
  if (red.length === 0) return;
  const ordered = [...red, ...caps.codecs.filter((c) => !isRed(c))];
  for (const t of pc.getTransceivers()) {
    const kind = t.receiver.track?.kind;
    if (kind === 'audio' && typeof t.setCodecPreferences === 'function') {
      try {
        t.setCodecPreferences(ordered);
      } catch (err) {
        console.warn('[earshot] could not prefer RED', err);
      }
    }
  }
}

/** The codec name of the first payload type on the audio line, e.g. "red" or "opus". */
export function firstAudioCodec(sdp) {
  const m = sdp?.match(/^m=audio \S+ \S+ (\d+)/m);
  if (!m) return null;
  const rtpmap = sdp.match(new RegExp(`^a=rtpmap:${m[1]} ([^/\\s]+)`, 'm'));
  return rtpmap ? rtpmap[1].toLowerCase() : null;
}
