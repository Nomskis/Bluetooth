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

## What's in this repository

| Part | What it is |
| --- | --- |
| [`android/`](android) | The Android app (Kotlin, Jetpack Compose, WebRTC). Hi-Fi audio mode, live "where is my audio going" check, background calls, picture-in-picture. |
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

Every push builds a debug APK in GitHub Actions: open the latest **CI** run,
download **earshot-debug-apk**, and install it (allow "install unknown apps"
for your browser or file manager). Or build it yourself:

```sh
cd android
./gradlew assembleDebug    # needs the Android SDK; output in app/build/outputs/apk/debug/
```

In the app, open **Settings** and enter your server address (for example
`https://calls.example.com`, or `192.168.1.20:8080` for a laptop on the same
Wi-Fi while testing; debug builds allow plain http).

### 3. Call someone

Pick a room code (or tap the dice), tap **Send invite link**, and join. The
other person opens the link in their browser, or uses the Android app with the
same room code.

## First test, at home

Before relying on it at the gym:

1. Connect your earbuds and start some music in YouTube Music.
2. In Earshot, check the **Audio output** card on the home screen. It should
   name your earbuds and say *High-quality music link (A2DP)*.
3. Join a call (call yourself from a laptop browser if nobody's around).
4. During the call, the chip at the top of the call screen should still say
   **Hi-Fi · (your earbuds)**, and the music should sound exactly as good as
   before the call.

If the chip turns yellow and says *call quality*, something switched the
phone into call mode. That's a bug or a phone quirk worth
[reporting](../../issues) with your phone model. The app reads Android's own
routing to tell you this; it isn't guessing.

## Status

Early but complete end-to-end: one-to-one video calls, reconnection after
network switches, browser and Android clients.

| Tested | How |
| --- | --- |
| Server | 28 unit and integration tests |
| Browser calls | 5 end-to-end tests: two real Chromium browsers calling each other through the server (video, audio, mute state, reloads, dropped connections, room full, camera-less join) |
| Android app | 29 unit tests (protocol against the shared examples, signaling reconnects against a scripted server, audio-mode decisions), Android lint, debug and release builds |
| Android on a real phone | **Not yet.** The audio routing has to be confirmed on real hardware; that's what the in-app audio check is for. |

## Trade-offs to know about

- **Your voice comes from the phone's mic, not the earbuds.** With the phone
  propped up a meter or two away that works well for chatting between sets;
  it won't sound like a headset mic. If you want the earbud mic, switch the
  call to **Headset mic** mode, which behaves like a normal call.
- **A little extra delay.** The music link buffers more than the call link, so
  the other person's voice reaches you a bit later (typically 0.1 to 0.3
  seconds, depending on the earbuds). Fine for chatting; you won't notice it
  while they're watching.
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
