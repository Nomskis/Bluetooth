# Screen sharing that stays sharp on a bad long-distance link

Written for one phone showing its screen to the other person's phone (or a browser),
Finland to Morocco, often on mobile data: round trips of about 260 ms (1.7 s at worst),
2 to 4 % loss, and sometimes only 60 to 300 kbps going up (from a real call report).

## Why the built-in sharing in WhatsApp and Snapchat looks bad

- **The screen is treated like a camera.** It goes through the call's own video path,
  which is tuned for faces: when bandwidth drops it lowers the resolution first, which
  is exactly what makes text unreadable. WebRTC's own engine knows better for screens
  (it keeps the resolution and lowers the frame rate instead), but only when the source
  is marked as a screen ([WebRTC video engine][engine]).
- **It shares the call's leftover bandwidth.** Voice, camera and screen fight over one
  connection, and on these networks real video bitrates are 10 to 400 kbps
  ([Meta][meta-av1]).
- **Old codecs for text.** H.264 at low bitrates blurs text; AV1 has tools made for
  screens (palette mode for flat colours and sharp edges, intra block copy for repeated
  glyphs) that "drastically improve performance for screen content" ([Meta][meta-av1]).
  In a 157-run comparison of WebRTC screen sharing, AV1 pulled far ahead on scrolling
  text ([benchmark][bench]).
- **Every lost packet is repaired across the whole route.** In a phone-to-phone stream,
  a lost packet on her end is asked for again across 260 ms, and the picture waits.

## What keeps text sharp elsewhere

- **Chrome Remote Desktop** tunes VP9 for screen content, keeps the quantiser low (max 30
  instead of the usual 56) so text never turns to mush, and re-encodes unchanged areas
  at better quality over time ("top-off", cyclic refresh) ([source][crd]).
- **WebRTC's zero-hertz screenshare** repeats the last frame when nothing changes until
  its quality has converged, then slows right down ([frame cadence adapter][fca]). It
  needs frame-rate constraints that Android's video source never sets
  ([android_video_track_source.cc][avts]), so on Android it never turns on: an app has to
  do the same itself.

## Why a server in the middle helps here

Cloudflare's Realtime SFU (the same service as the TURN relay Earshot already uses):

- Each phone connects to its **nearest Cloudflare site** (Helsinki, Casablanca) by
  anycast; media crosses Cloudflare's own backbone between them ([Cloudflare][anycast]).
- **Lost packets are resent from the site nearest the viewer**: "Calls goes beyond this
  and can handle NACK packets in the location closest to the user, which decreases
  overall latency" ([Cloudflare][anycast]). A loss on her mobile link is repaired over a
  hop of a few tens of milliseconds instead of the full 260 ms round trip, so it no longer
  shows as a stall.
- **Cost**: $0.05 per GB sent out of Cloudflare, with the first 1,000 GB each month free,
  shared with TURN; what's sent in is free ([pricing][pricing]). An hour of a 1 Mbps share
  is about 0.45 GB.
- Codecs: H.264, H.265, VP8, VP9 and AV1 ([limits][limits]).
- **A track with no packets for 30 seconds is garbage-collected** ([limits][limits]), so a
  still screen must keep sending something.
- The API (sessions and tracks over HTTPS) needs an app secret that has to stay on the
  server ([sessions and tracks][tracks], [example][echo]).

Render, where the Earshot server runs, has no UDP, so a self-hosted SFU isn't an option
there; Cloudflare's is free at this scale and closer to both people than any one server.

## Android's rules for capturing the screen

- One consent per share; on Android 14 and later `createVirtualDisplay()` may be called
  only once per `MediaProjection` ([Android][projection]). WebRTC's
  `ScreenCapturerAndroid` (in stream-webrtc-android 1.3.10) changes size by releasing and
  recreating its virtual display, so on Android 14+ **rotating the phone would end the
  share**. Earshot uses its own capturer that calls `VirtualDisplay.resize()`.
- A foreground service of type `mediaProjection` (permission
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`), started after consent and before
  `getMediaProjection()` ([Android][projection]).
- Android 14+ lets people share a single app, which leaves out the status bar and
  notifications; Android 15 adds a status bar chip to stop sharing, stops it when the
  phone locks, hides private notification content and one-time passwords, and hides
  password fields ([Android][projection], [Google][a15]).
- A virtual display only produces a frame when something on screen changes.
  `SurfaceTextureHelper.forceFrame()` sends the last one again, with its old timestamp
  ([source][sth]), which the encoder would drop as a repeat, so repeats need fresh
  timestamps.

## What's in the WebRTC build Earshot ships (stream-webrtc-android 1.3.10, WebRTC 6367)

Checked in the AAR: `VideoSource.setIsScreencast`, `RtpParameters.DegradationPreference`,
`RtpTransceiver.setCodecPreferences`, `LibaomAv1Encoder` (software AV1) and `Dav1dDecoder`,
VP9. `JavaAudioDeviceModule.Builder.setAudioRecordDataCallback` is there too, but in
1.3.10 the builder stores it and never hands it to the recorder (checked in the bytecode),
so it can't mix in a shared app's sound; see "The shared app's sound" below. The AV1 encoder, for a screen, tunes for screen content and turns on palette mode
([source][aom]). Screenshare probing (keeping the bandwidth estimate up while a still screen
sends little) is on by default ([alr_experiment.cc][alr]).

## The design this leads to

1. **Its own stream, not the call's video.** The screen goes on a separate one-way
   connection, published to Cloudflare by the sharer and pulled by the viewer. The call
   (voice, chat) carries on as before; the sharer's camera pauses while sharing, so the
   voice and the screen don't fight over the upload.
2. **Marked as a screen**: resolution is kept and the frame rate gives way; AV1 when both
   ends can, VP9 or VP8 otherwise.
3. **Sharpening a still screen**: when nothing changes, the last frame is sent again a
   few times a second for a couple of seconds (what zero-hertz mode does on desktop), then
   every 1.5 seconds to keep the stream alive at Cloudflare.
4. **The viewer steers**: her phone watches loss and freezes on what it receives and asks
   the sharer for a lower or higher bitrate ceiling, so her weak link sets the pace, not
   the sharer's good one.
5. **Zoom without blur**: pinch and double-tap zoom crop the received frame itself, so
   zooming in shows the real pixels at full resolution.
6. **Privacy**: Android's single-app sharing, the system's own protections, a clear
   "Stop sharing" in the call and in the notification.
7. **Usage tied to calls**: the server only talks to Cloudflare for two people in the
   same call, so the free allowance can't be used by strangers.

## The shared app's sound

For watching a video together. Android 10 added playback capture
(`AudioPlaybackCaptureConfiguration`, from the same MediaProjection consent): the sound of
apps that allow it (media, games, and unlabelled), with Earshot's own left out
(`excludeUid`), since in Hi-Fi mode the call plays as media and the other person's voice
would otherwise go back to them. Android's own screen recorder records this next to the
microphone, so the two don't fight.

WebRTC on Android only records from a microphone, so the share has its own factory and
audio device next to the call's. Its recorder starts muted; when WebRTC starts it, the
`AudioRecord` its recording thread reads from (`WebRtcAudioRecord.audioRecord`, read before
every buffer) is swapped for the playback capture, on that thread, before the first read,
and only then unmuted. If anything about the swap fails, the share goes without sound and
the microphone is never heard through it. The library's keep rules keep both names in
minified builds.

It's stereo Opus at 128 kbps with no voice processing. WebRTC encodes and decodes Opus in
mono unless the SDP says `stereo=1`, so the sharer adds it to Cloudflare's answer and the
viewer to its own answer. On the viewer's phone it plays with the call, so the call's echo
canceller knows about it. On the sharer's loudspeaker the video's sound also reaches their
microphone; earbuds avoid that.

Later, if wanted: a pointer the viewer can tap to show something on the sharer's screen,
and a direct fallback when no Cloudflare app is set up.

[engine]: https://webrtc.googlesource.com/src/+/refs/heads/main/media/engine/webrtc_video_engine.cc
[meta-av1]: https://engineering.fb.com/2026/06/22/video-engineering/adopting-av1-for-real-time-communication-rtc-meta/
[bench]: https://hackernoon.com/how-good-is-webrtc-screen-sharing-really-i-put-4-codecs-to-the-test
[crd]: https://chromium.googlesource.com/chromium/src/+/71.0.3553.2/remoting/codec/webrtc_video_encoder_vpx.cc
[fca]: https://webrtc.googlesource.com/src/+/refs/heads/main/video/frame_cadence_adapter.cc
[avts]: https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/jni/android_video_track_source.cc
[anycast]: https://blog.cloudflare.com/cloudflare-calls-anycast-webrtc
[pricing]: https://developers.cloudflare.com/realtime/sfu/pricing
[limits]: https://developers.cloudflare.com/realtime/sfu/limits/
[tracks]: https://developers.cloudflare.com/realtime/sfu/concepts/sessions-tracks/
[echo]: https://github.com/cloudflare/realtime-examples/blob/main/echo/index.html
[projection]: https://developer.android.com/media/grow/media-projection
[a15]: https://security.googleblog.com/2024/05/io-2024-whats-new-in-android-security.html
[sth]: https://webrtc.googlesource.com/src/+/refs/branch-heads/6367/sdk/android/api/org/webrtc/SurfaceTextureHelper.java
[aom]: https://webrtc.googlesource.com/src/+/refs/heads/main/modules/video_coding/codecs/av1/libaom_av1_encoder.cc
[alr]: https://webrtc.googlesource.com/src/+/refs/branch-heads/6367/rtc_base/experiments/alr_experiment.cc
