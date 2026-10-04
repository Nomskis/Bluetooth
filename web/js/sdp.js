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

/** Opus target when the listener's earbuds stay on the music link (default is 32 kbps). */
export const HD_VOICE_BITRATE = 48000;

/**
 * Asks the other side to encode their voice at a higher Opus bitrate
 * (maxaveragebitrate in the Opus fmtp line we send; RFC 7587). The listener's
 * earbuds stay on the music link, so they can actually play the difference.
 * WebRTC encoders use this as their target.
 */
export function preferHdVoice(sdp, bitrate = HD_VOICE_BITRATE) {
  const opus = sdp.match(/^a=rtpmap:(\d+) opus\/48000/im);
  if (!opus) return sdp;
  const pt = opus[1];
  const lines = sdp.split('\r\n');
  const fmtpIndex = lines.findIndex((l) => l.startsWith(`a=fmtp:${pt} `));
  if (fmtpIndex >= 0) {
    const params = lines[fmtpIndex]
      .slice(`a=fmtp:${pt} `.length)
      .split(';')
      .map((p) => p.trim())
      .filter((p) => p && !p.toLowerCase().startsWith('maxaveragebitrate='));
    params.push(`maxaveragebitrate=${bitrate}`);
    lines[fmtpIndex] = `a=fmtp:${pt} ${params.join(';')}`;
  } else {
    const rtpmapIndex = lines.findIndex((l) => l.startsWith(`a=rtpmap:${pt} `));
    lines.splice(rtpmapIndex + 1, 0, `a=fmtp:${pt} maxaveragebitrate=${bitrate}`);
  }
  return lines.join('\r\n');
}

/**
 * Lets the other side's receiver ask for lost voice packets again (generic
 * NACK on the Opus line, RFC 4585). WebRTC switches audio NACK on from the
 * description it receives: the side reading this keeps a few seconds of sent
 * packets, and its own receiver asks for the ones RED couldn't repair, but
 * only those a resend can still bring in before they're due to play, so it
 * never adds delay. Same as SdpTuning.kt.
 */
export function requestAudioResends(sdp) {
  const opus = sdp.match(/^a=rtpmap:(\d+) opus\/48000/im);
  if (!opus) return sdp;
  const pt = opus[1];
  const nack = `a=rtcp-fb:${pt} nack`;
  const lines = sdp.split('\r\n');
  if (lines.includes(nack)) return sdp;
  let last = -1;
  lines.forEach((l, i) => {
    if (l.startsWith(`a=rtpmap:${pt} `) || l.startsWith(`a=rtcp-fb:${pt} `) || l.startsWith(`a=fmtp:${pt} `)) last = i;
  });
  lines.splice(last + 1, 0, nack);
  return lines.join('\r\n');
}

/** True when a description lets its reader's receiver ask for lost voice packets again. */
export function opusHasNack(sdp) {
  const opus = sdp?.match(/^a=rtpmap:(\d+) opus\/48000/im);
  return !!opus && new RegExp(`^a=rtcp-fb:${opus[1]} nack\\r?$`, 'm').test(sdp);
}

/** maxaveragebitrate on the Opus line of a description, or null. */
export function opusMaxAverageBitrate(sdp) {
  const opus = sdp?.match(/^a=rtpmap:(\d+) opus\/48000/im);
  if (!opus) return null;
  const fmtp = sdp.match(new RegExp(`^a=fmtp:${opus[1]} (.*)$`, 'm'));
  const m = fmtp?.[1].match(/maxaveragebitrate=(\d+)/i);
  return m ? Number(m[1]) : null;
}

/** The codec name of the first payload type on the audio line, e.g. "red" or "opus". */
export function firstAudioCodec(sdp) {
  const m = sdp?.match(/^m=audio \S+ \S+ (\d+)/m);
  if (!m) return null;
  const rtpmap = sdp.match(new RegExp(`^a=rtpmap:${m[1]} ([^/\\s]+)`, 'm'));
  return rtpmap ? rtpmap[1].toLowerCase() : null;
}
