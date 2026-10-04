# Concealing lost voice: NetEq's Expand or Opus's own

When a voice packet is lost and RED's copies, Opus's in-band FEC and a resend
all miss it, the receiver has to make the missing audio up. NetEq's default is
its own generic **Expand**. With the field trial
`WebRTC-Audio-OpusGeneratePlc/Enabled/`, NetEq asks the **Opus decoder for its
own concealment** instead (`NetEqImpl::DoCodecPlc` calls
`AudioDecoderOpusImpl::GeneratePlc`, which decodes with no payload). The Opus
decoder knows the voice it was just decoding and stays in step with it for the
next real packet. The trial is compiled into the WebRTC the app ships
(stream-webrtc-android 1.3.10, branch-heads/6367), and the app's Opus decoder
is that same `AudioDecoderOpusImpl`.

**Result: Opus's own concealment sounds better in 31 of 32 loss and jitter
conditions, by 0.11 wideband PESQ on average and up to 0.22 on the worst
links, and is as good or better through 0.2–1 s Wi-Fi stalls.** The app turns
it on (`call/WebRtcTuning.kt`). Browsers don't take field trials, so the web
client keeps NetEq's default.

## Method

- **Real WebRTC code.** WebRTC 6367's NetEq and Opus decoder, built
  standalone from the branch's sources (`build_webrtc_objects.sh`), with
  libopus at the commit 6367's DEPS pins (Chromium builds it in floating
  point on Android too). The driver, `neteq_sim.cc`, encodes speech the way
  WebRTC configures Opus on Android (VoIP application, VBR, complexity 5,
  in-band FEC, the loss percentage the encoder would be told), wraps each
  packet with 3 RED copies exactly as `AudioEncoderCopyRed` builds them, and
  feeds NetEq with the app's settings (100-packet buffer, fast accelerate,
  delay quantile 0.97).
- **Speech.** 60 s of WebRTC's own test recording,
  `resources/audio_coding/speech_mono_32_48kHz.pcm` (SHA-1 checked against
  the branch's `.sha1`).
- **Network.** A Gilbert–Elliott channel (average loss, mean burst length in
  packets) plus exponential jitter. Both variants of a pair get the same seed,
  so the same packets are lost and delayed; 5 seeds per condition.
- **Score.** Wideband PESQ (ITU-T P.862.2, the `pesq` package) per 10 s
  stretch, each stretch aligned on its own (NetEq's time stretching moves the
  alignment a little over a minute), averaged. A loss-free call scores 4.14.
- **Scripts.** `plc_eval.py` (loss and jitter) and `stall_eval.py` (stalls);
  raw rows in `results-loss.jsonl` and `results-stalls.jsonl`.

## Results

Loss and jitter, 3 RED copies, FEC on. Gain = Opus concealment − Expand,
averaged over 5 seeds; "better" = seeds where Opus concealment won.

| Packet | Opus bitrate | Loss, mean burst | Jitter | Expand | Opus PLC | Gain | Better |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 10 ms | 48 kbps | 3 %, 2 | 0 | 3.90 | 3.91 | +0.01 | 3/5 |
| 10 ms | 48 kbps | 3 %, 2 | 15 ms | 3.87 | 3.94 | +0.08 | 5/5 |
| 10 ms | 48 kbps | 5 %, 4 | 0 | 3.26 | 3.35 | +0.09 | 4/5 |
| 10 ms | 48 kbps | 5 %, 4 | 15 ms | 3.20 | 3.33 | +0.13 | 5/5 |
| 10 ms | 48 kbps | 10 %, 4 | 0 | 2.75 | 2.94 | +0.20 | 5/5 |
| 10 ms | 48 kbps | 10 %, 4 | 15 ms | 2.61 | 2.83 | +0.22 | 5/5 |
| 10 ms | 48 kbps | 10 %, 8 | 0 | 2.33 | 2.45 | +0.12 | 5/5 |
| 10 ms | 48 kbps | 10 %, 8 | 15 ms | 2.21 | 2.39 | +0.18 | 4/5 |
| 10 ms | 20 kbps | 3 %, 2 | 0 | 3.04 | 3.08 | +0.04 | 5/5 |
| 10 ms | 20 kbps | 3 %, 2 | 15 ms | 2.96 | 3.06 | +0.10 | 5/5 |
| 10 ms | 20 kbps | 5 %, 4 | 0 | 2.51 | 2.61 | +0.11 | 5/5 |
| 10 ms | 20 kbps | 5 %, 4 | 15 ms | 2.40 | 2.57 | +0.16 | 5/5 |
| 10 ms | 20 kbps | 10 %, 4 | 0 | 2.12 | 2.28 | +0.16 | 5/5 |
| 10 ms | 20 kbps | 10 %, 4 | 15 ms | 1.99 | 2.21 | +0.22 | 5/5 |
| 10 ms | 20 kbps | 10 %, 8 | 0 | 1.87 | 2.00 | +0.13 | 5/5 |
| 10 ms | 20 kbps | 10 %, 8 | 15 ms | 1.82 | 1.97 | +0.15 | 5/5 |
| 20 ms | 48 kbps | 3 %, 2 | 0 | 3.67 | 3.68 | +0.01 | 2/5 |
| 20 ms | 48 kbps | 3 %, 2 | 15 ms | 3.93 | 3.95 | +0.02 | 3/5 |
| 20 ms | 48 kbps | 5 %, 4 | 0 | 3.08 | 3.13 | +0.05 | 4/5 |
| 20 ms | 48 kbps | 5 %, 4 | 15 ms | 3.41 | 3.49 | +0.08 | 5/5 |
| 20 ms | 48 kbps | 10 %, 4 | 0 | 2.87 | 3.00 | +0.14 | 5/5 |
| 20 ms | 48 kbps | 10 %, 4 | 15 ms | 2.87 | 3.03 | +0.16 | 5/5 |
| 20 ms | 48 kbps | 10 %, 8 | 0 | 2.47 | 2.58 | +0.10 | 5/5 |
| 20 ms | 48 kbps | 10 %, 8 | 15 ms | 2.48 | 2.62 | +0.14 | 5/5 |
| 20 ms | 20 kbps | 3 %, 2 | 0 | 2.89 | 2.87 | −0.03 | 1/5 |
| 20 ms | 20 kbps | 3 %, 2 | 15 ms | 3.17 | 3.31 | +0.14 | 5/5 |
| 20 ms | 20 kbps | 5 %, 4 | 0 | 2.38 | 2.39 | +0.01 | 3/5 |
| 20 ms | 20 kbps | 5 %, 4 | 15 ms | 2.73 | 2.87 | +0.15 | 5/5 |
| 20 ms | 20 kbps | 10 %, 4 | 0 | 2.35 | 2.45 | +0.10 | 5/5 |
| 20 ms | 20 kbps | 10 %, 4 | 15 ms | 2.35 | 2.45 | +0.10 | 5/5 |
| 20 ms | 20 kbps | 10 %, 8 | 0 | 2.03 | 2.10 | +0.07 | 5/5 |
| 20 ms | 20 kbps | 10 %, 8 | 15 ms | 2.05 | 2.16 | +0.12 | 5/5 |

Wi-Fi stalls (outages of the given length, 48 kbps, 15 ms jitter):

| Packet | Loss | Outage | Expand | Opus PLC | Gain | Better |
| --- | --- | --- | --- | --- | --- | --- |
| 10 ms | 3 % | 200 ms | 3.47 | 3.57 | +0.10 | 5/5 |
| 10 ms | 5 % | 500 ms | 3.15 | 3.18 | +0.03 | 4/5 |
| 10 ms | 2 % | 1 s | 3.75 | 3.78 | +0.03 | 2/5 |
| 20 ms | 3 % | 200 ms | 3.35 | 3.45 | +0.10 | 5/5 |
| 20 ms | 5 % | 500 ms | 3.18 | 3.18 | −0.00 | 1/5 |
| 20 ms | 2 % | 1 s | 3.79 | 3.79 | +0.00 | 3/5 |

The gain grows with how much is left to conceal: little where RED repairs
nearly everything, 0.1–0.2 once bursts outrun its copies. Through long stalls
both end up concealing a gap neither can fill, and score the same.

The one cost: with 20 ms packets and jitter, NetEq's buffer averaged 5–13 ms
longer with Opus concealment (for example 93 → 106 ms); with 10 ms packets it
was the same or shorter (raw rows: `jb_ms`, Expand first).

## Not covered

- PESQ is a model of listening tests, not one. The direction is consistent
  across seeds and conditions, which is what the decision rests on.
- No resends (NACK) in the simulation; they repair some of the same gaps on
  links where the buffer is deep enough, which shrinks what either method
  has to conceal but doesn't change which conceals better.
- `WebRTC-Audio-OpusPlcUsePrevDecodedSamples` was not tested; it only matters
  for 120 ms Opus frames, which the app never uses.
