# How Earshot keeps your earbuds in music quality

## The problem

Classic Bluetooth earbuds (almost all earbuds sold today) talk to your phone
over two very different links:

| Link | Direction | Quality | Used for |
| --- | --- | --- | --- |
| **A2DP** | phone → earbuds only | High: stereo, SBC/AAC/aptX/LDAC/LHDC, hundreds of kbit/s | Music, videos, everything "media" |
| **HFP** (audio over SCO) | both ways | Low: mono, 8 kHz (CVSD) or 16 kHz (mSBC), newer phones and earbuds up to 32 kHz | Calls, because it also carries the earbuds' microphone back |

There's no classic profile that gives you the A2DP quality *and* the earbud
microphone at the same time. So the moment anything needs the earbud mic, the
earbuds switch to HFP and everything you hear drops to call quality.

On Android it's even stricter. When a call is active, which includes VoIP
calls, media is routed to the same device as the call. VoIP apps like WhatsApp
put the phone into `MODE_IN_COMMUNICATION`, play the other person with
`USAGE_VOICE_COMMUNICATION` and record with the `VOICE_COMMUNICATION` mic
preset. Android then makes the Bluetooth headset the communication device,
brings up the SCO link, and your YouTube Music drops into that same narrow
channel.

Turning off "Phone calls" for the earbuds in Bluetooth settings doesn't help
either: the call moves to the phone's speaker, and because media follows the
call, so does your music.

## The trick

Earshot's **Hi-Fi mode** simply never declares a call:

1. **Playback is labelled as game audio (or media).** The other person's
   voice is played with `AudioAttributes.USAGE_GAME` by default, or
   `USAGE_MEDIA` if you turn the "game audio label" off. Both are routed
   exactly like music: to your earbuds, over A2DP. Game audio has one extra
   effect: on phones whose Bluetooth stack supports a low-latency mode,
   Android switches to it while a game-audio track plays on the fast path
   ([research §2.1](research/latency.md)).
2. **The phone's own microphone records you.** Capture uses the plain
   `MediaRecorder.AudioSource.MIC` preset (configurable) and is pinned to the
   built-in mic (`AudioDeviceInfo.TYPE_BUILTIN_MIC`), so the earbud mic, and
   with it HFP, is never touched.
3. **The audio mode stays `MODE_NORMAL`.** Earshot never calls
   `AudioManager.setMode`, `setCommunicationDevice` or `startBluetoothSco` in
   Hi-Fi mode. It also doesn't register with Android's Telecom framework,
   because a Telecom-managed call would switch the mode for us.
4. **No audio focus request.** Your music app isn't told to pause or duck, so
   it keeps playing. Android mixes both streams onto the A2DP link.

Since nothing on the Bluetooth side changes, it works with any earbuds, old or
new, and needs no root, no modified Bluetooth stack and no special firmware.

In code:

| File | Role |
| --- | --- |
| [`audio/AudioProfile.kt`](../android/app/src/main/java/io/github/nomskis/earshot/audio/AudioProfile.kt) | Decides, per call, playback usage, mic preset, built-in mic pinning and echo cancellation |
| [`call/RtcEngine.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/RtcEngine.kt) | Builds WebRTC's `JavaAudioDeviceModule` from that profile (`setAudioAttributes`, `setAudioSource`, `setPreferredInputDevice`) |
| [`audio/CallAudioController.kt`](../android/app/src/main/java/io/github/nomskis/earshot/audio/CallAudioController.kt) | Touches `AudioManager` only in the classic headset mode, and restores it afterwards |
| [`audio/AudioRouteMonitor.kt`](../android/app/src/main/java/io/github/nomskis/earshot/audio/AudioRouteMonitor.kt) | Asks Android where audio really goes, for the in-app check |

We checked WebRTC's Android audio code (the `stream-webrtc-android` build the
app uses): it never changes the audio mode, starts SCO or requests audio focus
by itself. Those decisions are entirely the app's.

## Echo

A speakerphone call needs echo cancellation, because the microphone hears the
other person coming out of the speaker. With earbuds in, the phone's mic, a
meter away on a bench, can't hear what's playing inside your ears, so there's
nothing to cancel, and an echo canceller would only make your voice worse.

Earshot's **Automatic** echo cancellation follows the output: off for
earbuds, headphones and hearing aids, on for the loudspeaker (WebRTC's
software canceller). It keeps following it during the call: if the earbuds
drop out (flat battery, back in the case) and the call moves to the
loudspeaker, the canceller comes on at once, so the other person doesn't hear
themselves; it goes off again a couple of seconds after the earbuds are back.
WebRTC takes the setting from the microphone's audio source, so the switch
moves the call onto a fresh source, without renegotiating or stopping the
recording. You can force it either way in Settings. Noise suppression and
automatic gain stay on by default; a gym is loud.

## Latency

A2DP buffers more than SCO, so the other person's voice reaches your ears later
than in a normal call: typically 0.15 to 0.3 seconds from the app to your ear,
depending on the earbuds and codec. Your own voice isn't affected: the phone
mic doesn't go through Bluetooth at all.

Most of that delay sits in a buffer inside the earbuds, and classic Bluetooth
has no standard command to make it smaller. Earshot attacks the rest of the
path and makes the part it can't remove matter less. The research and sources
are in [research/latency.md](research/latency.md); in short:

| What | How | Works with |
| --- | --- | --- |
| Shorter network path | 10 ms Opus packets (`a=ptime:10`), redundant audio (RED) preferred, a jitter buffer that shrinks quickly (`audioJitterBufferFastAccelerate`) | everything |
| Fast playback path | `PERFORMANCE_MODE_LOW_LATENCY` with a self-adjusting buffer (`setUseLowLatency`), game-audio label | everything; low-latency Bluetooth where the phone supports it |
| Measure it | The sonar meter in the delay tuner: chirps through an earbud held to the mic, matched filter, calibrated against the phone speaker | everything |
| See them talk first | A voice detector on their decoded audio, 100–250 ms ahead of your ears: the call screen glows, music dips (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), 8-second replay | everything |
| Free the radio | On 2.4 GHz Wi-Fi with Bluetooth audio: video capped at 800 kbps both ways; optionally media moved to mobile data | everything |
| Lip sync | Their video held back by the Bluetooth delay WebRTC doesn't know about | everything |
| Earbud game mode | Each brand's own command, on for the call and back after | OPPO/OnePlus/realme, Nothing/CMF, Xiaomi/Redmi, Huawei/Honor, Soundcore, EarFun |
| Turbo | Android's privileged Bluetooth controls, through Shizuku: for each call, low-latency mode, the codec measured fastest, the shortest buffer; undone after | Android 13+ with Wireless debugging |
| Fast failover | Mobile data on standby, ICE tuned to swap a stalled path in ~1 s | everything |
| Live readout | Mouth-to-ear delay from stats plus the measured app-to-ear figure | everything |

## Sharing the radio with Bluetooth

Phones run Wi-Fi and Bluetooth on one combo chip, and on 2.4 GHz they take
turns on the same antenna. Each Wi-Fi packet of a video call is time the A2DP
stream can't use; retransmissions go up, and earbuds with adaptive buffers
answer by buffering more, which means more delay and the occasional dropout.
5 GHz, 6 GHz and mobile data don't share the band.

When the phone is on 2.4 GHz Wi-Fi and audio is on Bluetooth
([`call/RadioPlan.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/RadioPlan.kt)):

- **Lighter video**, on by default: our video is capped through the sender's
  encoding parameters (`maxBitrateBps`), and theirs by `b=AS` / `b=TIAS` in
  the descriptions we send, which WebRTC and browsers treat as a ceiling.
- **Mobile data instead**, off by default because it uses your data plan:
  Earshot keeps mobile data up next to Wi-Fi (`ConnectivityManager.requestNetwork`)
  and sets ICE's `networkPreference` to cellular. That preference ranks above
  network cost, so a working mobile-data path wins, but it's only a
  preference: if mobile data fails, the call stays on Wi-Fi. WebRTC's stats
  show which network the media really uses, and the video cap lifts once
  it's on mobile data.

Two tuner tools tie this together. **Find my fastest setup** measures the
setup as it is, then with the earbuds' game mode, then every codec (with
Turbo), and turns on for calls whatever beats the meter's spread. The
**radio test** measures the earbuds with Wi-Fi quiet and again while the
phone transmits call-sized traffic to its own router, which shows what
coexistence costs on that phone.

## Surviving gym Wi-Fi

WebRTC's defaults check standby paths every 25 seconds and call a path dead
after 5 seconds without an answer. Earshot switches when the path in use
goes 1 second without packets, checks standby paths every 2 seconds, and
keeps mobile data up next to Wi-Fi during calls (on by default). ICE prefers
Wi-Fi as the cheaper network, moves to mobile data when Wi-Fi stalls, and
comes back when it recovers; until then mobile data only carries connection
checks.

## Lip sync

WebRTC lines video up with audio assuming the audio takes a fixed time to
play once it leaves the jitter buffer. In the Android library that is 75 ms
(the Java audio module is built with a 150 ms "high latency" estimate and
reports half of it). Over A2DP the real figure is typically 150–300 ms, so
their lips move before you hear the words, often by more than the ~125 ms at
which people notice (ITU-R BT.1359).

Earshot holds their video back by the difference
([`call/LipSync.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/LipSync.kt),
[`call/DelayedVideoSink.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/DelayedVideoSink.kt)).
The figure comes from your delay-tuner measurement that best matches the setup
(earbuds, game mode, codec); without one, from a silent probe that reads
Android's own playback timestamps, which over A2DP include the delay the
earbuds report. Holding video back adds nothing to the conversation's delay,
since the voice is the slower stream anyway.

## Earbud game mode

Many earbuds have a low-latency "game" mode that roughly halves their buffer,
normally switched from the brand's app. Earshot speaks those apps' control
protocols over RFCOMM for the brands whose protocols are publicly documented
([`earbuds/`](../android/app/src/main/java/io/github/nomskis/earshot/earbuds)).
Each driver reads the current state first, writes the switch, reads it back
(an acknowledgement alone doesn't prove the earbuds applied it), and reports
what the state was, so the call can restore it afterwards. Earbuds without a
driver lose nothing. It's off by default; turn it on in Settings or try it from
the delay tuner, where you can measure the difference.

The control channel is the one the brand's own app uses. If that app is
connected to the earbuds at the same moment, Earshot can't get in and says so.

## HD voice

On the call link voice is squeezed to 16 kHz or less, so WebRTC's default of
32 kbps Opus is plenty. In Hi-Fi mode the earbuds play the full music-quality
stream, so both clients ask for 48 kbps Opus (`maxaveragebitrate` on the Opus
line, RFC 7587), which WebRTC encoders take as their target.

## Keeping calls alive with the screen off

The call runs in a foreground service with a microphone (and camera) type and
holds a low-latency Wi-Fi lock. That's enough on stock Android. Xiaomi
(HyperOS/MIUI), Huawei and Honor, OPPO, realme and OnePlus, vivo and Samsung
add their own battery managers that can stop it anyway, so the home screen
shows the switches for the phone in hand, with a button to each maker's own
screen ([`system/BackgroundHealth.kt`](../android/app/src/main/java/io/github/nomskis/earshot/system/BackgroundHealth.kt)).

## Borrowing the earbuds' mic mid-call

A Hi-Fi call can switch to the earbuds' microphone for a while (a loud
moment, walking away from the phone) and back. On: communication mode with
the Bluetooth headset as the communication device, and the running recording
moved to the headset's input with `setPreferredInputDevice`, which WebRTC
applies live, so nothing is renegotiated. The earbuds drop to the call link
until you switch back to the phone mic and full-quality music.

## Headset mic mode

Sometimes you do want the earbud mic: a long talk while walking, a noisy street.
**Headset mic** mode is a classic VoIP call: `MODE_IN_COMMUNICATION`,
`USAGE_VOICE_COMMUNICATION`, the `VOICE_COMMUNICATION` mic preset, hardware
echo cancellation, and the headset chosen as the communication device. Audio
quality drops to call quality like any other app. You pick the mode on the home
screen before joining.

Earbuds with **LE Audio** (LC3) can carry a microphone and decent playback at
the same time. With those, headset mode sounds much better than with classic
earbuds, and the in-app check labels the link "LE Audio".

## Checking it on your phone

The **Audio output** card (home screen) and the chip at the top of the call
screen are built from what Android reports:

- On Android 13+, `AudioManager.getAudioDevicesForAttributes()` with the exact
  attributes Earshot plays with, i.e. where *our* audio is routed right now.
- `AudioManager.getMode()`. Anything other than `MODE_NORMAL` means some app
  (or a real phone call) has switched the phone into call mode. The card turns
  yellow and says so.
- `AudioManager.getCommunicationDevice()` (Android 12+), which shows whether a
  Bluetooth headset is being used for calls.

If you have a computer with `adb`, `adb shell dumpsys audio` prints the current
audio mode and communication routing as well, which is a useful second
opinion when reporting a problem.

## What isn't known yet

This approach follows Android's documented routing rules (people have
reported the same effect with Discord when its mic permission is removed, which
makes it fall back to media playback). But phone makers customise audio
routing, and nobody has run Earshot on a POCO/HyperOS phone yet. Things worth watching on a first test:

- The chip stays on **Hi-Fi** for the whole call, including after the screen
  turns off or you switch to your music app.
- Your voice is clear from where the phone sits. If not, try the other
  microphone presets in Settings; phones map them to different physical mics.
- No echo on the other side. If there is, set echo cancellation to **Always
  on**.

Please open an issue with your phone model and what the chip showed, good or
bad. A compatibility list is on the roadmap.
