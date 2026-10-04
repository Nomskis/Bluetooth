# Earshot

**Video calls that keep your Bluetooth earbuds in full music quality.**

Start a WhatsApp, Messenger or Discord video call on Android and your earbuds
drop into "call mode": everything in your ears, your music included, gets
squeezed into a narrow phone-quality channel. Earshot is an open-source calling
app that doesn't do that. The other person's voice plays through your earbuds
next to your music, both at full quality, and the phone's own microphone picks
up your voice.

It works with **any** Bluetooth earbuds. Nothing has to change on the earbuds,
the phone's Bluetooth software, or your music app.

> Built for one specific situation, then made general: video calling someone
> while you train at the gym, with the phone propped up facing you and music in
> your ears.

## How it works, in one paragraph

Classic Bluetooth earbuds have a high-quality one-way music link (A2DP) and a
low-quality two-way call link (HFP/SCO). Android moves *all* audio onto the call
link as soon as an app declares a call: it switches the phone into
communication mode, and media follows the call. Earshot never declares a call.
It plays the other person's voice as ordinary media and records from the
phone's built-in mic, so Android has no reason to leave the music link. The
full story, with the Android rules involved, is in
[docs/how-it-works.md](docs/how-it-works.md).

## Built to be fast, with any earbuds

Music-quality Bluetooth has more delay than the call link, so most of Earshot
is about winning that back, or making what's left not matter. Everything in
this list works with every pair of classic Bluetooth earbuds:

- **Leaner network path.** 10 ms audio packets instead of 20, redundant audio
  (RED) so a lost packet is repaired without the jitter buffer growing, and a
  jitter buffer that shrinks right back after a hiccup.
- **Android's low-latency path.** Their voice is played on Android's fast audio
  path and labelled as game audio, which on phones that support it switches
  the Bluetooth link into its low-latency mode by itself.
- **Measure it, by sound.** The delay tuner plays chirps through an earbud held
  to the phone's mic and measures the real app-to-ear delay (calibrated against
  the phone's own speaker). One tap tries your earbuds' game mode and every
  codec and keeps whatever is fastest for calls.
- **See the delay live.** During a call the screen shows roughly how long
  their voice takes from their mouth to your ear, and where the time goes.
- **See them talk before you hear them.** Earshot sees their voice arrive
  100–250 ms before the earbuds play it: the call screen lights up as they
  start talking, your music dips while they talk, and you can replay the last
  8 seconds.
- **Keeps the radio free for your earbuds.** On 2.4 GHz Wi-Fi, which shares
  the phone's radio with Bluetooth, video is kept lighter in both directions;
  optionally the call moves to mobile data, which doesn't share it at all. A
  radio test in the tuner shows what Wi-Fi traffic costs your earbuds.
- **Survives gym Wi-Fi.** A stalled path is swapped in about a second
  instead of WebRTC's usual 5–25. Mobile data is never touched while Wi-Fi
  works, unless you allow it as a backup in Settings: then a stalled Wi-Fi, or
  one that's up but dropping packets, hands the call to mobile data until it
  recovers.
  On 5 GHz Wi-Fi the call's packets are marked for Wi-Fi's priority queue,
  so a crowded network lets them through ahead of everyone's downloads.
- **Lips in time with the voice.** Their video is held back by exactly the
  Bluetooth delay that WebRTC doesn't know about, using your measurement when
  there is one.
- **HD voice.** The earbuds stay on the music link, so voices are sent at
  near-transparent quality (48 kbps Opus instead of 32).
- **Earbud mic when you need it.** One tap moves a Hi-Fi call to the earbuds'
  mic (call quality) for a noisy moment, and back, without reconnecting.
- **Calls survive your pocket.** A per-phone-brand guide through the battery
  switches HyperOS, ColorOS, EMUI, Funtouch and One UI use to stop background
  apps, and one tap to rejoin if a call gets cut off anyway. In a pocket the
  screen goes dark (proximity sensor), so it can't mute or hang up by itself.
- **Earbuds dying mid-call don't embarrass you.** Their voice pauses instead
  of jumping to the loudspeaker (one tap plays it there), and echo
  cancellation switches on whenever the call plays out loud.
- **Text chat for loud moments.** Muted, or the gym is too loud? Type, or tap
  a quick reply ("Can't hear you", "One sec"), also straight from the
  notification with the phone in your pocket. Messages go phone to phone,
  encrypted, and are re-sent if the connection drops.

And two optional extras that go further:

- **Earbud game mode, automatically.** Classic Bluetooth has no standard
  "less delay, please" command, but many brands have their own. Earshot can
  switch it on for the call and back afterwards on OPPO, OnePlus and realme,
  Nothing and CMF, Xiaomi and Redmi, and (new) Huawei, Honor, Soundcore and EarFun earbuds.
- **Turbo (no root, via Shizuku).** Unlocks Android's system-only Bluetooth
  controls. For each call it turns on Bluetooth low-latency mode, uses the
  codec measured fastest for your earbuds, and shrinks the phone-side buffer,
  then puts your music codec back afterwards.

The research behind all of this, with sources, is in
[docs/research/latency.md](docs/research/latency.md).

## What's in this repository

| Part | What it is |
| --- | --- |
| [`android/`](android) | The Android app (Kotlin, Jetpack Compose, WebRTC). Hi-Fi audio mode, live "where is my audio going" check, delay tuner, earbud game mode, background calls, picture-in-picture. |
| [`web/`](web) | Browser client. The other person can join from a link on any phone or computer, no install needed. |
| [`server/`](server) | Small Node.js signaling server (one dependency). Introduces the two sides; the call itself goes directly between devices. Also serves the web client. |
| [`protocol/`](protocol) | Example messages shared by the server and Android tests, so the implementations can't drift apart. |
| [`docs/`](docs) | How it works, the protocol, deployment, roadmap. |

## Quick start

### 1. Run the server

You need a server that both phones can reach over HTTPS (browsers only allow
the camera on secure pages). The simplest options are in
[docs/deploy.md](docs/deploy.md): Docker on any small VPS with automatic
HTTPS, or a free-tier host like Render or Fly.io.

To try it on your own Wi-Fi first:

```sh
cd server
npm install
npm start          # http://0.0.0.0:8080
```

### 2. Install the Android app

On the phone, download
[**earshot.apk**](https://github.com/Nomskis/Bluetooth/releases/latest/download/earshot.apk)
(also under **Releases** on the right of the repository page) and open it
(allow "install unknown apps" for your browser). Each new build installs over
the last and keeps your settings and contacts. The other person installs the
same file on theirs.

That's the optimized build, for the smoothest calls. The release also has
**earshot-debug.apk**: the same app with debugging on, slower, for
troubleshooting. Every push also leaves both APKs on its GitHub Actions run.
Or build it yourself:

```sh
cd android
./gradlew assembleOptimized    # needs the Android SDK; output in app/build/outputs/apk/optimized/
```

In the app, open **Settings** and enter your server address (for example
`https://calls.example.com`). For a test server on a laptop on the same Wi-Fi
without https (`192.168.1.20:8080`), use the debug APK; only it allows plain
http.

### 3. Call someone

The first time, pick a room code (or tap the dice), tap **Send invite link**,
and join. The other person opens the link in their browser, or uses the
Android app with the same room code.

If you both have the Android app, that first call saves you to each other's
**Call** list on the home screen. From then on it works like a phone call:

- Tap the phone or camera button next to their name. Their phone **rings and
  vibrates** (following its ringer and Do Not Disturb settings), shows the call
  full screen over the lock screen, and they tap **Accept** or **Decline**.
  You hear the ringing tone meanwhile.
- If they decline, are on another call, or don't answer within a minute, you're
  told and the call ends by itself. If their phone can't be reached (offline,
  or Earshot was force-stopped), Earshot keeps trying for a minute and offers
  the invite link instead.
- A call you miss leaves a notification with **Call back**.
- If you both tap call at the same moment, you don't both get "busy": the two
  phones agree on one of the calls and you're connected.

Ringing doesn't use Google's push service: while **Receive calls** is on
(Settings), the app keeps a small connection to your own server open, with a
quiet "Ready for calls" notification. The **Ready for calls** card on the home
screen says whether your phone can be rung right now and walks through the
switches that make it ring properly: notifications, full-screen calls on
Android 14+, and on Xiaomi/POCO/Redmi phones "Show on Lock screen" and
"Display pop-up windows while running in the background". On those phones,
also work through **Keep calls going with the screen off** (battery saver
"No restrictions", autostart, lock Earshot in recent apps), or HyperOS may
stop the connection and calls won't ring.

## First test, at home

Before relying on it at the gym:

1. Connect your earbuds and start some music in YouTube Music.
2. In Earshot, check the **Audio output** card on the home screen. It should
   name your earbuds and say *High-quality music link (A2DP)*. Work through
   the **Keep calls going with the screen off** card if your phone shows it.
3. Open the **delay tuner**, hold one earbud's speaker against the phone's
   microphone, and tap **Find my fastest setup**. It measures your earbuds,
   tries their game mode and (with Turbo) every codec, and keeps the fastest
   for calls. On 2.4 GHz Wi-Fi, run the **radio test** too.
4. Join a call (call yourself from a laptop browser if nobody's around).
5. During the call, the chip at the top of the call screen should still say
   **Hi-Fi · (your earbuds)**, the music should sound exactly as good as
   before the call, and the readout below it shows roughly how long their
   voice takes to reach you.

If the chip turns yellow and says *call quality*, something switched the
phone into call mode. That's a bug or a phone quirk worth
[reporting](../../issues) with your phone model (the tuner's **Copy report**
button gathers the details). The app reads Android's own routing to tell you
this; it isn't guessing.

## Status

Early but complete end-to-end: one-to-one video and voice calls, ringing
between Android phones, reconnection after network switches, browser and
Android clients.

| Tested | How |
| --- | --- |
| Server | 58 unit and integration tests, including ringing (who can ring whom, first answer wins, cancel and timeout), hosted-TURN credentials and the browser client's voice detector, delay readout, chat and SDP tweaks |
| Browser calls | 12 end-to-end tests: two real Chromium browsers calling each other through the server (video, audio, reloads, dropped connections, room full, camera-less join, 10 ms packets, redundant audio, HD voice, talking cue, text chat across a reload, the open-in-app link, home-screen install) |
| Android app | 218 tests: protocol and chat against the shared examples, signaling reconnects against a scripted server, incoming calls end to end against a scripted server (ringing, accept, decline, busy, missed call, calling each other at once), the outgoing ring's states, audio-mode decisions, the sonar meter's signal processing on simulated recordings, every earbud protocol against a simulated pair of earbuds, radio, path-steering, voice-bitrate, heat and lip-sync planning, the bad-Wi-Fi tuning, SDP tweaks, plus Robolectric tests that start the real app and render every screen. Android lint, debug and release builds |
| Android on a real phone | **Not yet.** The audio routing and the earbud drivers have to be confirmed on real hardware; the in-app audio check and the delay tuner are there for exactly that. |

## Trade-offs to know about

- **Your voice comes from the phone's mic, not the earbuds.** With the phone
  propped up a meter or two away that works well for chatting between sets;
  it won't sound like a headset mic. If you want the earbud mic, switch the
  call to **Headset mic** mode, which behaves like a normal call.
- **A little extra delay.** The music link buffers more than the call link, so
  the other person's voice reaches your ears later than on a normal call:
  typically 0.15 to 0.3 seconds from the phone to your ear, depending on the
  earbuds, and much less in game mode. Everything listed above exists to
  shrink that or hide it; the delay tuner tells you what your earbuds do.
- **The call follows your media volume**, so the volume keys change music and
  voice together. The call screen has a separate slider for their voice.

## Contributing

Issues and pull requests are welcome. Good places to start are in
[docs/roadmap.md](docs/roadmap.md). Each part runs its own tests:

```sh
cd server && npm test
cd e2e && npm install && npx playwright test
cd android && ./gradlew testDebugUnitTest lintDebug
```

## License

[Apache 2.0](LICENSE).
