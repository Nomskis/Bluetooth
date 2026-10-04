# Roadmap

Roughly in order. Each item stands on its own; pick any.

## Next

- **Confirm on real phones.** Run the [first test](../README.md#first-test-at-home)
  on as many phones as possible and keep a compatibility table (phone, Android
  version, earbuds, result), including the delay tuner's numbers with and
  without earbud game mode.
- **Confirm the earbud drivers on real earbuds**, starting with the ones
  marked experimental (Huawei/Honor, EarFun), and add brands as their
  protocols get documented. Next candidates: QCY (game mode over Bluetooth
  LE GATT, with a different protocol per chip vendor; documented by the
  unlicensed QuickyAndroid project, so only its facts could be used), Edifier
  (one model documented, payloads XOR-masked). Sony, JBL and Jabra have no
  public game-mode commands.
- **Collect radio-test results** per phone model to learn which chips suffer
  from 2.4 GHz coexistence, and default the mobile-data switch accordingly.
- **Web lip sync.** The browser side has the talking cue and delay readout;
  holding video back there needs the output latency, which browsers report
  inconsistently.
- **Headset mode to Hi-Fi mid-call.** Hi-Fi calls can now borrow the earbud
  mic and come back; a call started in headset mode still can't move to
  Hi-Fi without rebuilding the audio device module.
- **Codec and bitrate in the readout.** The delay readout already says
  which way the connection is weak, the loss each way, whether the call is
  direct or relayed and the audio packet length; the negotiated video codec
  and the bitrates in use would round it off.
- **A private release key.** The latest release carries an optimized,
  non-debuggable APK signed with the shared debug key, so builds install over
  each other; signing with a key kept in the repository's secrets would stop
  anyone else's build from installing over it.
- **Ringing in the browser.** Android apps already ring each other through
  the server. The browser side could get a real ring through Web Push (the
  server signs with a stable VAPID key derived from a generated secret, and
  the caller's app keeps the callee's subscription, so the server stores
  nothing).
- **App links** so `https://<server>/r/<room>` opens the Android app when it's
  installed (`earshot://join/<room>` already works).

- **Try the relay route by itself.** The relay route (Calls abroad) is a
  switch to compare by hand. WebRTC prunes a relayed path while a direct one
  works on the same network, so the two can't be measured side by side; a
  call that stays weak could instead try the relay for a minute, compare loss
  and delay, and keep the better one.
- **Confirm the bad-connection features on a real long-distance call.**
  Voice first, longer packets and resends are tested against WebRTC's own
  code and a simulated lossy link; a real call between two countries on weak
  Wi-Fi or mobile data, with the readout's numbers noted, would tune the
  thresholds.

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
