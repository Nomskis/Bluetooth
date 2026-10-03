# Earshot for Android

Kotlin, Jetpack Compose and WebRTC (`io.getstream:stream-webrtc-android`).
Min Android 8.0 (API 26), targets Android 16.

## Build

Open this folder in Android Studio, or from the command line with the Android
SDK installed (platform 37):

```sh
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
```

Pre-fill the server address in a build with
`./gradlew assembleDebug -Pearshot.serverUrl=https://calls.example.com`.

Debug builds share the signing key in `app/debug.keystore`, so an APK from CI
installs over one you built yourself. For release builds, create
`keystore.properties` here (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); it is git-ignored.

## Layout

```
app/src/main/java/io/github/nomskis/earshot/
  audio/      Hi-Fi vs headset audio profiles, route monitoring, AudioManager handling
  call/       CallManager (app-wide), CallSession (negotiation), RtcEngine (WebRTC objects)
  signaling/  Protocol messages, WebSocket client with reconnects, server URL helpers
  service/    Foreground service that keeps calls alive in the background
  settings/   Settings model and DataStore persistence
  ui/         Compose screens: home, call, settings
```

Start with `audio/AudioProfile.kt` for the core idea, and `call/CallSession.kt`
for the call flow. [docs/how-it-works.md](../docs/how-it-works.md) explains the
audio routing; [docs/protocol.md](../docs/protocol.md) the signaling.
