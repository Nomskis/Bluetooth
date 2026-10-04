# Call quality checklist

Every stage a call goes through, and what Earshot does at each, so nothing
is left unexamined. Each line is one of:

- ✅ **done**: built, tested, and checked against the WebRTC source the app
  ships (branch-heads/6367);
- ✔️ **already right**: checked, and WebRTC or Android already does the best
  thing, so nothing to change;
- ❌ **rejected**: checked, and it wouldn't help (why, briefly);
- 🔬 **to research**: not looked at properly yet;
- 📞 **needs real calls**: can't be settled without data from real calls
  (Settings › Call reports).

When something new comes up, it goes on this list first.

**After the first real calls (October 2026), every tuning of Earshot's own is
off** (`call/CallTuning.kt`). Those calls, Finland to Morocco with one phone on
mobile data, had an echo, voice that barely got through, a 577 ms smoothing
buffer with a fifth of the voice made up while nothing was lost, and video in
pieces, with 2.5 Mbps or more to spare. A call is now WebRTC's own defaults,
plus the phone's call audio when no earbuds are on (its echo canceller instead
of WebRTC's weak mobile one) and two changes upstream WebRTC itself shipped or
that were measured on 6367's own NetEq (Opus concealment, keyframe flushing).
The ✅ rows below that `CallTuning` switches off come back one at a time, each
with a call report to show it helps.

## 1. Capture

| Item | Status | Notes |
| --- | --- | --- |
| Microphone source (phone's own mic in Hi-Fi, so earbuds stay on A2DP) | ✅ | The app's core (how-it-works.md, "The trick") |
| Echo cancellation following the audio route | ✅ | On for the speaker, off with earbuds in |
| High-pass filter, noise suppression, auto gain | ✅ | Settings; WebRTC's AEC3/NS |
| Newer gain controller (`WebRTC-Audio-GainController2`) | 🔬 | In the shipped library; does it level a quiet or distant voice better? |
| Camera capture size versus what's actually sent | 🔬 | Capturing 720p to send 360p on a weak link costs CPU and heat for nothing |

## 2. Voice encoding

| Item | Status | Notes |
| --- | --- | --- |
| HD voice (Opus 48 kbps), stepping to 32 and 20 kbps when it doesn't fit | ✅ | MediaBudget |
| Opus encoder complexity (5 on Android, 9 on desktop) | ❌ | Can't be set in the shipped library: `kDefaultComplexity` is 5 on Android (audio_encoder_opus_config.cc), `SdpToConfig` reads no parameter for it, no field trial touches it, and Java only offers the built-in encoder factory. It would take a custom WebRTC build |
| Opus in-band FEC | ✔️ | On (`useinbandfec=1`); the encoder is told the loss rate |
| DTX (sending nothing in silence) | ✔️ | Off by default, which keeps the voice and background continuous |
| Audio packet length | ✅ | Starts at 20 ms, 40 ms on rough links, 10 ms once calm (PacketTime) |

## 3. Voice protection and receiving

| Item | Status | Notes |
| --- | --- | --- |
| Redundant copies (RED ×3) | ✅ | 60 ms of consecutive loss repaired at 20 ms packets |
| Resends of lost voice (NACK) | ✅ | Both clients, verified end to end |
| Real round-trip time for NACK (`rrtr`) | ❌ | Already measured from the regular reports (rtcp_receiver.cc) |
| Jitter buffer target (quantile 0.97) and size (100 packets) | ✅ | |
| Jitter buffer decision logic | ✔️ | 6367 already uses stable delay mode and combined concealment decisions |
| Concealing what nothing repaired | ✅ | Opus's own concealment: research/concealment.md |

## 4. Video encoding

| Item | Status | Notes |
| --- | --- | --- |
| Codec: VP9 preferred | ❌ | Most Android phones have no hardware VP9 encoder, so it ran on the CPU and the picture fell apart in real calls. WebRTC's own order (VP8 first) again |
| AV1 | 📞 | In the shipped library (libaom encoder, dav1d decoder; `LibaomAv1Encoder`, `Dav1dDecoder`). Fewer bits again than VP9, but software-only on nearly every phone: promising at 360p and below on a weak uplink, once its CPU and heat are measured on the actual phones |
| Temporal layers (L1T3) | ❌ | Off with the rest of the video experiments; hardware encoders ignore it anyway |
| Software encoder on a weak link (≤360p) | ❌ | Switching encoders as the resolution changed costs a keyframe each time, and software encoding costs CPU; off |
| Keep frame rate, lower resolution (`MAINTAIN_FRAMERATE`) | ✔️ | WebRTC's default for a camera |
| Quality scaler thresholds at low bitrates | 🔬 | When it drops resolution, and whether VP9's thresholds suit a weak uplink |

## 5. Video protection

| Item | Status | Notes |
| --- | --- | --- |
| Resends (NACK, RTX) | ✔️ | WebRTC default |
| Forward error correction (ULPFEC) | ✔️ | Negotiated by default and used by WebRTC's NACK/FEC hybrid by RTT and loss |
| FlexFEC | 📞 | In the shipped library (`WebRTC-FlexFEC-03`, `-Advertised`); worth its bandwidth only if call reports show lost video packets, not bandwidth, as what freezes the picture |
| Keyframe out first after a freeze (`WebRTC-Pacer-KeyframeFlushing`) | ✅ | A keyframe drops the stale video still queued ahead of it, so a frozen picture recovers sooner on a congested uplink. Off in 6367; upstream launched it and made it the only behaviour |
| How long a receiver waits before asking for a keyframe | 📞 | 3 s without a decodable frame in 6367 (`kMaxWaitForFrame`); an `rtx-time` of 500 ms in our descriptions would make it 1.5 s, but also re-ask for keyframes sooner while congestion stalls the encoder. Worth it only if call reports show long freezes |
| Resends sent ahead of the pacing budget (`WebRTC-Pacer-FastRetransmissions`) | ❌ | Still an experiment upstream, never launched; bursts on a link that's already short |

## 6. Bandwidth and congestion

| Item | Status | Notes |
| --- | --- | --- |
| Voice first: video gets what RED-inflated voice leaves | ✅ | MediaBudget |
| Start bitrate from the last call (route memory) | ✅ | LinkMemory |
| Lighter video towards a starving Wi-Fi uplink | ✅ | AirtimeShare |
| Congestion window pushback (limits queued data) | ✔️ | On by default in 6367 (350 ms) |
| Loss-based estimate on random, non-congestion loss | ✔️ | 6367 already runs the loss-based estimator v2 by default, which models the loss a link always has rather than backing off on every lost packet |

## 7. Network and route

| Item | Status | Notes |
| --- | --- | --- |
| Fast failover, continual gathering, mobile data on standby | ✅ | |
| Wi-Fi power save off during calls (low-latency and high-perf locks) | ✅ | |
| Priority marks on 5 GHz (DSCP) | ✅ | |
| Fewer packets next to Bluetooth on 2.4 GHz | ✅ | PacketTime floor |
| Relay over TLS on port 443 (blocked or throttled networks) | ✔️ | Cloudflare's relay offers it and the server passes it on; needs the Cloudflare key set up |
| Opt-in route through the relay ("Calls abroad") | ✅ | |
| Trying the relay automatically when a call is weak | 📞 | Worth it only if real calls show the relay route is better |
| Server near both callers (Frankfurt) | ✅ | render.yaml; Test connection shows the distance |
| IPv6 versus IPv4 on Moroccan and Finnish networks | 🔬 | Which one ICE prefers, and whether either is worse |

## 8. Playback

| Item | Status | Notes |
| --- | --- | --- |
| Call played as media (Hi-Fi), low-latency path, game-audio label | ✅ | |
| Lip sync with the measured Bluetooth delay | ✅ | |
| Video jitter buffer and render timing | 🔬 | Whether the video side waits longer than it needs to |

## 9. Connecting

| Item | Status | Notes |
| --- | --- | --- |
| Instant answer (connect while it rings, media held) | ✅ | Adversarially reviewed and fixed |
| Re-ring after the caller reconnects; full-room retry | ✅ | |
| Server wake on app start (free plans) | ✅ | |

## 10. Measuring

| Item | Status | Notes |
| --- | --- | --- |
| A report for every call (route, loss, repairs, freezes) | ✅ | Settings › Call reports |
| Real Finland–Morocco calls, with and without the relay route | 📞 | The data the 📞 items wait for |
