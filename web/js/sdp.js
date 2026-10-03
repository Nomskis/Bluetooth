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
