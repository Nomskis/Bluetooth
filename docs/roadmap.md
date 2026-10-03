# Roadmap

Roughly in order. Each item stands on its own; pick any.

## Next

- **Confirm on real phones.** Run the [first test](../README.md#first-test-at-home)
  on as many phones as possible and keep a compatibility table (phone, Android
  version, earbuds, result).
- **Switch audio mode during a call.** Today Hi-Fi vs headset mic is chosen
  before joining. Switching mid-call means rebuilding the audio device module
  and renegotiating.
- **Text chat over a data channel**, handy when one side is muted at the gym.
- **Connection quality indicator** from WebRTC stats (round-trip time, packet
  loss, bitrate), plus a debug screen with the selected codec and candidate type.
- **Release builds on GitHub Releases** with a proper signing key, so updates
  install over each other without uninstalling.
- **App links** so `https://<server>/r/<room>` opens the Android app when it's
  installed (`earshot://join/<room>` already works).

## Later

- **Group calls.** The server already supports bigger rooms
  (`MAX_PEERS_PER_ROOM`); the clients need a mesh of peer connections, or an
  SFU for more than about four people.
- **Share your music.** Send what you're listening to as a second, stereo
  audio track, so the other person hears the same song.
- **iOS app.** iOS has the same A2DP vs HFP split; whether the trick carries
  over to AVAudioSession's playback category needs investigating.
- **Desktop.** On Linux and macOS you can already pick a different mic and keep
  headphones on A2DP; the web client covers most of this.
- **Upstream the idea.** A proposal for an Android setting ("keep Bluetooth
  media quality during calls, use the phone mic") in AOSP's audio policy, so
  every app could benefit.
