# Research: cutting delay without giving up music quality (Android)

Goal: hear the other person through Bluetooth earbuds, next to full-quality
music, with as little delay as possible, on as many phones and earbuds as
possible.

Method: web research plus reading Android's own source code (AOSP
`frameworks/base`, `frameworks/av`, `packages/modules/Bluetooth`) and the
WebRTC library the app ships. Findings marked **[AOSP]** were checked in source,
not taken from articles.

## 1. Where the delay comes from

Their voice → your ears, classic Bluetooth earbuds, typical values:

| Stage | Typical | Notes |
| --- | --- | --- |
| Their mic + encoding | 25–40 ms | Opus frame (20 ms default) + lookahead + capture buffer |
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
  WebRTC delays video to match audio by its own estimate, but on Android that
  estimate is a fixed 75 ms **[WebRTC source]**: the Java audio module is
  created with `kHighLatencyModeDelayEstimateInMilliseconds` (150 ms) and
  `PlayoutDelay()` reports half of it. With A2DP at 150–300 ms app-to-ear the
  leftover is 75–225 ms, often past the threshold. (An earlier version of this
  document assumed WebRTC's estimate tracked the real path and ranked
  lip-sync correction low; the source says otherwise. Built, see §2.10.)

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

**Built:** drivers for OPPO/OnePlus/realme, Nothing/CMF, Xiaomi/Redmi,
Huawei/Honor and EarFun (`android/.../earbuds/`), from facts documented by
QuickBuds (Apache-2.0), Gadgetbridge, BudsLink and OppoPods. Details that
matter:

- OPPO family: game mode is feature switch `06` on most models but `28` on
  models with "game sound" (handshake bitmap bit 49 = command `0x0423`); the
  driver asks the earbuds which ids they have (`0x010D`) instead of guessing,
  and reads the switch back after writing, because an OK ack doesn't prove the
  write took (QuickBuds §2). realme Buds Air 8 Pro is in HeyMelody's model
  list (`066C12`).
- Xiaomi/Redmi refuse settings until a mutual challenge-response. The function
  is the Bluetooth Core spec's SAFER+ Ar' keyed with the challenge (last byte
  XOR 6) over a fixed block; Earshot's implementation is written from the spec
  and matches reference vectors from two independent projects.
- Nothing/CMF: one low-latency command (`0xF040`) across ~20 models; frames
  carry CRC-16/MODBUS.
- Huawei: service `0x2B`, command `0x6C`, TLV `01 = on`, unencrypted on
  earbuds; only FreeClip 2 is confirmed by Gadgetbridge, so it's marked
  experimental, as is EarFun (Qualcomm GAIA framing, command `0x0312`).
- Galaxy Buds have a game-mode message (`0x87`), but Samsung phones send it
  themselves and it appears to need Samsung's own codec; not built.

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

### 2.6 The app can hear their voice *before* you do

WebRTC's `AudioTrack.addSink(AudioTrackSink)` delivers the decoded remote audio
as it's handed to Android for playback, which is 100–250 ms before it leaves
the earbuds. A voice detector on that feed knows they're talking before you hear
them, with no help from their side, so it works with any client.

**Uses:**

- **Head-start cue.** The screen lights up as they start talking, so you don't
  talk over them.
- **Smart duck.** Briefly request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so
  Android dips YouTube Music just as their voice arrives, then restores it.
  Android 8+ ducks focus holders automatically unless they play speech or opted
  to pause ([docs](https://developer.android.com/media/optimize/audio-focus)).
  Watch the active playback list to detect apps that pause instead, and back
  off.
- **Replay.** Keep the last ~10 s of their voice for "what did you say?".
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
- **Wi-Fi plus mobile data.** WebRTC on Android tracks every network and binds
  sockets to each, so with mobile data kept up next to Wi-Fi
  (`ConnectivityManager.requestNetwork`), ICE gathers candidates on both.
  `RTCConfiguration.networkPreference` ranks above network cost in ICE's
  choice, so it can steer media onto mobile data while keeping Wi-Fi as the
  fallback. **Built** as part of §2.9.
- **HD voice.** WebRTC's Opus encoder takes `maxaveragebitrate` from the
  receiver's fmtp line as its target (tested in Chrome: the outbound stream
  reports a 48 kbps target). Not a latency gain, but the music link can play
  it. **Built** (48 kbps).

### 2.8 Phone makers

HyperOS stops background apps aggressively, even with a foreground service.
Users need to allow Background autostart, set battery to No restrictions, and
lock the app in Recents
([guide](https://docs.sportstracklive.com/android-battery-saving/xiaomi),
[dontkillmyapp](https://dontkillmyapp.com/xiaomi)). HyperOS also resets codec
choices on reconnect (the reason a codec-fixing tool for it exists).
**Built:** a per-brand guide on the home screen with direct links into each
maker's battery and autostart screens.

### 2.8b LHDC's own low-latency mode

LHDC 5.0 earbuds advertise a low-latency capability (feature bit `0x40` in
the codec information element, from the LHDC v5 A2DP integration code),
and the related LLAC / LHDC LL variant claims ~30 ms. But Android's own
Bluetooth stack (`packages/modules/Bluetooth/system/stack/a2dp`, checked on
`main`) contains no LHDC encoder: phones that offer LHDC, including Android
17's newly native support, get it from the vendor's codec implementation, so
there's no standard codec-specific value an app (even with Shizuku) can set to
request the LL mode. On realme, OPPO and OnePlus earbuds the brand's own game
mode command, which Earshot's driver sends, is the way in.

### 2.9 Wi-Fi and Bluetooth share one radio on 2.4 GHz

Phone combo chips run Bluetooth and 2.4 GHz Wi-Fi on a shared antenna with
time-division coexistence. A video call over 2.4 GHz Wi-Fi (1–3 Mbit/s each
way, more with retransmissions on a crowded gym network) takes airtime from
A2DP; earbuds respond to the extra retransmissions by holding more in their
buffer, or drop out. 5/6 GHz and mobile data avoid it.
**Built:** with Bluetooth audio on 2.4 GHz Wi-Fi, video is capped at 800 kbps
both ways (`maxBitrateBps` on our sender, `b=AS`/`b=TIAS` for theirs), and
optionally media moves to mobile data (§2.7), with the cap lifted once stats
show it's there. The effect varies by chip, so the tuner's radio test
measures it: the sonar meter quiet, then while the phone transmits ~2.5 Mbit/s
to its router's UDP discard port.

### 2.10 Lip sync

See §1. **Built:** their video is held back by (app-to-ear delay − 75 ms −
display time), from the sonar measurement that best matches the current
setup, or else from a silent probe of Android's playback timestamps (which
over A2DP include the earbuds' reported delay, §2.5). Hardware-decoded frames
are copied to I420 while held, so the decoder never waits.

## 3. Ideas researched and set aside

| Idea | Why not now |
| --- | --- |
| Voice-clarity processing on their voice (compressor, presence EQ via `DynamicsProcessing` on WebRTC's track) | **[AOSP]** `PlaybackThread::checkEffectCompatibility_l` refuses software effects on a session with a fast track ("non HW effect on playback thread in fast mode"), so it would cost the fast path and its low-latency Bluetooth trigger. The same rule means OEM global effects (Dolby, Mi Sound) skip fast tracks, and their processing delay with them |
| Replace WebRTC with a custom audio engine | Saves ~10–30 ms at most, against ~100+ ms from the earbuds; very large effort |
| DRED | Not available in WebRTC or Chrome |
| Predicting speech to hide delay | Research-grade (tens of ms, artefacts) |
| Talking to the earbuds below the Bluetooth stack | The OS owns the A2DP channel; impossible for an app |
| Codec or buffer changes without Shizuku | `@SystemApi`, privileged |
| Open earbud firmware with a call-first buffer | Only on open hardware (PineBuds Pro); long-term |

## 4. Expected result

Their voice → your ears, classic earbuds:

| Setup | Estimate |
| --- | --- |
| Earshot today | ~250–400 ms |
| Network tuning (10 ms packets, RED, fast jitter buffer, low-latency playback) | −30 to −60 ms |
| Earbud game mode (manual, or automatic where supported) | −50 to −150 ms |
| Game audio on capable phones and earbuds, or Shizuku Turbo | further cut, device-dependent |
| **Classic earbuds, everything on** | **~140–230 ms** |
| **LE Audio earbuds, game mode** | **~100–160 ms, with the earbud mic** |

On top of that, the head-start cue and smart duck make the delay that's left
easier to live with: you see they're talking before you hear them, and the music
gets out of their way.

These are estimates from the sources above. The in-app sonar measurement is
what counts on a given phone and pair of earbuds.
