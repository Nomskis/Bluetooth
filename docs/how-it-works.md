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

1. **Playback is labelled as media.** The other person's voice is played with
   `AudioAttributes.USAGE_MEDIA` (content type speech). To Android it looks the
   same as a podcast. It goes wherever media goes: your earbuds, over A2DP.
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

Earshot's **Automatic** echo cancellation follows the output at call start:
off for earbuds, headphones and hearing aids, on for the loudspeaker (WebRTC's
software canceller). You can force it either way in Settings. Noise suppression
and automatic gain stay on by default; a gym is loud.

## Latency

A2DP buffers more than SCO, so the other person's voice reaches your ears
somewhat later than in a normal call, typically 0.1 to 0.3 seconds depending
on the earbuds and codec. For a conversation between sets that's fine. Your own
voice isn't affected: the phone mic doesn't go through Bluetooth at all.

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
