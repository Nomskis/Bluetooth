# Research: cutting delay without giving up music quality (Android)

Goal: hear the other person through Bluetooth earbuds, next to full-quality
music, with as little delay as possible, on as many phones and earbuds as
possible.

Method: web research plus reading Android's own source code (AOSP
`frameworks/base`, `frameworks/av`, `packages/modules/Bluetooth`) and the
WebRTC library the app ships. Findings marked **[AOSP]** were checked in source,
not taken from articles.

## 1. Where the delay comes from

Her voice → your ears, classic Bluetooth earbuds, typical values:

| Stage | Typical | Notes |
| --- | --- | --- |
| Her mic + encoding | 25–40 ms | Opus frame (20 ms default) + lookahead + capture buffer |
| Network, one way | 10–50 ms | Direct peer-to-peer; more through a relay |
| Jitter buffer | 20–80 ms | WebRTC's NetEq grows it when packets arrive unevenly or get lost |
| Android audio path | 30–100 ms | Mixer, A2DP encoder, buffers ([Oboe wiki](https://github.com/google/oboe/wiki/TechNote_BluetoothAudio)) |
| Earbuds | 100–250 ms | Mostly the earbuds' own buffer, sized for radio retransmissions and the left↔right relay |

Codec ranges: SBC 150–220 ms, AAC 120–180, aptX 100–120, aptX Low Latency
32–40, aptX Adaptive ~80 (~40 in low-latency mode), LDAC 100+
([Feasycom](https://www.feasycom.com/bluetooth-audio-codecs-comparison/),
[Headphonesaddict](https://headphonesaddict.com/bluetooth-codecs/)). LHDC's
low-latency variants (LLAC / LHDC LL) reach 30–80 ms
([Wikipedia](https://en.wikipedia.org/wiki/LHDC_(codec))).

**Conclusion:** the earbuds' buffer is the largest single piece. Software tuning
of the network side saves tens of milliseconds; getting the earbuds into a
low-latency mode saves around a hundred.

### What people notice

- Conversation: above ~150 ms one way people start talking over each other;
  above ~250 ms it feels like a satellite call (ITU-T G.114,
  [summary](https://telcobridges.com/learning/voip-security/voip-call-quality-issues-diagnosis-and-fixes/)).
- Lip sync: audio lagging video becomes noticeable at ~125 ms and
  unacceptable at ~185 ms (ITU-R BT.1359,
  [summary](https://en.wikipedia.org/wiki/Audio-to-video_synchronization)).
  WebRTC already delays video to match audio by its own estimate, so the
  leftover mismatch is usually under the threshold. **Lip-sync correction is
  low priority.**

## 2. Findings that open doors

### 2.1 Android puts Bluetooth into low-latency mode for "game" audio [AOSP]

- `AudioFlinger.h`: `mBluetoothLatencyModesEnabled = true` by default.
- `Threads.cpp`, `MixerThread::setHalLatencyMode_l()`: on an A2DP or LE Audio
  output whose HAL reports more than one latency mode, AudioFlinger requests
  `AUDIO_LATENCY_MODE_LOW` while **any active track is a fast track with
  `USAGE_GAME`** (or uses `USAGE_ASSISTANCE_ACCESSIBILITY`).
- The Bluetooth stack turns that into a low-latency ACL link
  (`bta_av_api_set_latency` → `L2CA_SetAclLatency(L2CAP_LATENCY_LOW)`).
- Stock A2DP only allows it when the Opus A2DP codec is available
  (`A2dpService.updateLowLatencyAudioSupport`), i.e. Pixel phones with Pixel
  Buds Pro. LE Audio outputs support latency modes too. Qualcomm's aptX Adaptive
  also switches to ~24–40 ms when it detects gaming
  ([Digital Trends](https://www.digitaltrends.com/home-theater/what-is-aptx-qualcomm-bluetooth-codecs-explained/)).
- WebRTC's `JavaAudioDeviceModule.setUseLowLatency(true)` builds its AudioTrack
  with `PERFORMANCE_MODE_LOW_LATENCY` (checked in the shipped library).

**Use:** play the call with `USAGE_GAME` on the low-latency path. Where the
phone and earbuds support it, Bluetooth latency drops automatically. Elsewhere
nothing changes: `USAGE_GAME` routes exactly like media.

### 2.2 LE Audio has a "game" mode that keeps quality *and* the earbud mic [AOSP]

- `le_audio_utils.cc`: `USAGE_GAME` → `GAME` context; recording with
  `AudioSource.MIC` → `LIVE`, with `VOICE_COMMUNICATION` → `CONVERSATIONAL`.
- `client.cc`: *"Gaming scenario detected. Use this audio context for the other
  direction if supported"*. With game playback, a recording from the earbuds
  is configured as the game voice back channel (GMAP): 48 kHz output plus mic,
  low latency.
- LE Audio phones include Pixel 8+, Galaxy S23+, Xiaomi 14/15, POCO X6 Pro,
  F6 Pro, F7 Pro and F7 Ultra; earbuds include Pixel Buds Pro 2, Galaxy Buds2
  Pro and newer, Sony WF-1000XM5, LinkBuds S and INZONE, OnePlus Buds Pro 2 and
  Nothing Ear ([Android Central](https://www.androidcentral.com/what-you-need-know-about-bluetooth-le-audio),
  [Avantree](https://avantree.com/blogs/bluetooth/which-bluetooth-le-audio-headphones-are-compatible-today)).
  Measured LE Audio latency is ~48–68 ms
  ([soundlatencytest](https://soundlatencytest.com/blog/le-audio-lc3-latency/)).

**Use:** on LE Audio earbuds, Hi-Fi mode can use the earbud mic too, with no
quality drop. That's the best case for anyone with the hardware.

### 2.3 Earbud game modes roughly halve the delay

Measured on a Galaxy S21 with AAC
([Headphonesaddict](https://headphonesaddict.com/game-mode-in-headphones-earbuds/)):
EarFun Free Pro 200 → 100 ms, SoundPEATS Air3 Deluxe 140 → 60 ms,
SoundPEATS RunFree 170 → 90 ms, EarFun Free Pro 3 220 → 120 ms, and almost no
change on Galaxy Buds2 (170 → 160) or Edifier. realme rates the Buds Air8 Pro
game mode at 45 ms
([Smartprix](https://www.smartprix.com/bytes/realme-buds-air8-pro-review-more-refined-easier-to-use-and-packed-with-features/));
measured figures generally come in above advertised ones.

Game modes are switched from each brand's companion app, often behind a login.
Several brands' protocols are reverse-engineered and work without root over
RFCOMM: OPPO, OnePlus and realme (one shared protocol: QuickBuds, BudsLink,
AirBuds, Gadgetbridge), Huawei FreeBuds and FreeClip (Gadgetbridge has a
low-latency toggle), Sony, Nothing, Soundcore and Xiaomi
([Gadgetbridge](https://gadgetbridge.org/gadgets/headphones/)). Those projects
are GPL or AGPL. Earshot (Apache-2.0) has to reimplement from protocol facts
and must not copy their code.

### 2.4 The privileged controls are reachable without root, through Shizuku [AOSP]

The useful system controls are `@SystemApi`:
`BluetoothA2dp.getCodecStatus/setCodecConfigPreference`,
`setBufferLengthMillis` and `getDynamicBufferSupport` (all need
`BLUETOOTH_PRIVILEGED`), and
`AudioManager.setBluetoothVariableLatencyEnabled` (needs
`MODIFY_AUDIO_ROUTING`). The `adb shell` identity holds both permissions
(`packages/Shell/AndroidManifest.xml`). [Shizuku](https://github.com/RikkaApps/Shizuku-API)
(MIT) lets an app use that identity after a one-time setup over Wireless
debugging (Android 11+, no computer, no root). Existing tools already switch
codecs this way, including one written for HyperOS
([awesome-shizuku](https://github.com/timschneeb/awesome-shizuku)).

**Use:** an optional "Turbo" setup that reads the real codec, picks the
lowest-latency one, shrinks the phone-side Bluetooth buffer, and forces
variable latency on.

### 2.5 Android's playback timestamps include the earbuds' reported delay [AOSP]

The Bluetooth audio HAL's `GetPresentationPosition` returns the AVDTP delay
report from the earbuds (`remote_delay_report_`). `AudioTrack.getTimestamp()`
therefore estimates when sound actually plays, as long as the earbuds report
honestly. A short acoustic test (a chirp through the earbud, heard by the phone
mic, located by correlation, as OboeTester does) gives the true value.

### 2.6 The app can hear her voice *before* you do

WebRTC's `AudioTrack.addSink(AudioTrackSink)` delivers the decoded remote audio
as it's handed to Android for playback, which is 100–250 ms before it leaves
the earbuds. A voice detector on that feed knows she's talking before you hear
her, with no help from her side, so it works with any client.

**Uses:**

- **Head-start cue.** The screen lights up as she starts talking, so you don't
  talk over her.
- **Smart duck.** Briefly request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so
  Android dips YouTube Music just as her voice arrives, then restores it.
  Android 8+ ducks focus holders automatically unless they play speech or opted
  to pause ([docs](https://developer.android.com/media/optimize/audio-focus)).
  Watch the active playback list to detect apps that pause instead, and back
  off.
- **Replay.** Keep the last ~10 s of her voice for "what did you say?".
- **Captions.** Feed the audio to on-device speech recognition when the gym is
  too loud.

### 2.7 Network side

- **10 ms audio packets.** Chrome honours `a=ptime:10` in the remote
  description: 100 vs 50 packets/s, about 10 ms less per direction (tested).
- **RED (redundant audio).** Both clients prefer `audio/red`; each packet also
  carries the previous one, so a single loss is repaired without the jitter
  buffer growing. Chrome sends it when negotiated first (tested: bytes per
  packet roughly doubled); Android's WebRTC has the encoder and the
  `WebRTC-Audio-Red-For-Opus` field trial.
- **Jitter buffer.** `audioJitterBufferFastAccelerate` and a lower
  `audioJitterBufferMaxPackets` make it shrink quickly after a spike.
- **DRED** (Opus 1.5/1.6 neural redundancy, up to 1 s of recovery) isn't in
  libWebRTC or Chrome yet ([BlogGeek](https://bloggeek.me/webrtcglossary/dred/)).
  Revisit later.
- **Wi-Fi plus mobile data.** WebRTC on Android already tracks both networks
  and can request mobile data (`NetworkMonitorAutoDetect.requestMobileNetwork`,
  in the shipped library). A second, muted, already-primed connection on mobile
  data could take over instantly when gym Wi-Fi stalls
  (`RTCConfiguration.networkPreference`). Experimental.

### 2.8 Phone makers

HyperOS stops background apps aggressively, even with a foreground service.
Users need to allow Background autostart, set battery to No restrictions, and
lock the app in Recents
([guide](https://docs.sportstracklive.com/android-battery-saving/xiaomi)). The
app should detect Xiaomi and walk through it. HyperOS also resets codec choices
on reconnect (the reason a codec-fixing tool for it exists).

## 3. Ideas researched and set aside

| Idea | Why not now |
| --- | --- |
| Lip-sync correction (delay her video) | Leftover mismatch is usually below the ~125 ms detection threshold; costs a GPU copy per frame |
| Replace WebRTC with a custom audio engine | Saves ~10–30 ms at most, against ~100+ ms from the earbuds; very large effort |
| DRED | Not available in WebRTC or Chrome |
| Predicting speech to hide delay | Research-grade (tens of ms, artefacts) |
| Talking to the earbuds below the Bluetooth stack | The OS owns the A2DP channel; impossible for an app |
| Codec or buffer changes without Shizuku | `@SystemApi`, privileged |
| Open earbud firmware with a call-first buffer | Only on open hardware (PineBuds Pro); long-term |

## 4. Expected result

Her voice → your ears, classic earbuds:

| Setup | Estimate |
| --- | --- |
| Earshot today | ~250–400 ms |
| Network tuning (10 ms packets, RED, fast jitter buffer, low-latency playback) | −30 to −60 ms |
| Earbud game mode (manual, or automatic where supported) | −50 to −150 ms |
| Game audio on capable phones and earbuds, or Shizuku Turbo | further cut, device-dependent |
| **Classic earbuds, everything on** | **~140–230 ms** |
| **LE Audio earbuds, game mode** | **~100–160 ms, with the earbud mic** |

On top of that, the head-start cue and smart duck make the delay that's left
easier to live with: you see she's talking before you hear her, and the music
gets out of her way.

These are estimates from the sources above. The in-app sonar measurement is
what counts on a given phone and pair of earbuds.
