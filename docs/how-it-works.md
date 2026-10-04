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
recording.

Hi-Fi calls play as media, so they follow Android's rule for media too: when
headphones go away, playback pauses instead of carrying on out loud. If the
earbuds drop mid-call, their voice is paused (they can still hear you, and
the talking cue still lights up) until the earbuds are back or you tap
**Play on speaker**. A call that starts on the speaker is never paused. You can force it either way in Settings. Noise suppression and
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
| Ride out bad Wi-Fi | Each audio packet repeats the 3 before it, lost voice asked for again (NACK), longer packets while gaps outrun the copies, voice first when bandwidth is short, a jitter buffer sized for spiky networks, VP8 temporal layers, Wi-Fi kept out of power save (see below) | everything |
| Fast playback path | `PERFORMANCE_MODE_LOW_LATENCY` with a self-adjusting buffer (`setUseLowLatency`), game-audio label | everything; low-latency Bluetooth where the phone supports it |
| Measure it | The sonar meter in the delay tuner: chirps through an earbud held to the mic, matched filter, calibrated against the phone speaker | everything |
| See them talk first | A voice detector on their decoded audio, 100–250 ms ahead of your ears: the call screen glows, music dips (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), 8-second replay | everything |
| Free the radio | On 2.4 GHz Wi-Fi with Bluetooth audio: video capped at 800 kbps both ways; optionally media moved to mobile data | everything |
| Lip sync | Their video held back by the Bluetooth delay WebRTC doesn't know about | everything |
| Earbud game mode | Each brand's own command, on for the call and back after | OPPO/OnePlus/realme, Nothing/CMF, Xiaomi/Redmi, Huawei/Honor, Soundcore, EarFun |
| Turbo | Android's privileged Bluetooth controls, through Shizuku: for each call, low-latency mode, the codec measured fastest, the shortest buffer; undone after | Android 13+ with Wireless debugging |
| Fast failover | ICE tuned to swap a stalled path in ~1 s; mobile data on standby if you allow it | everything |
| Live readout | Mouth-to-ear delay from stats plus the measured app-to-ear figure, and which way the connection is weak | everything |

## Riding out bad Wi-Fi

Gym and café Wi-Fi rarely runs out of bandwidth first. It loses packets in
bursts and delivers others late, in clumps. Each setting below was checked
against the WebRTC source the app ships with
([`call/WebRtcTuning.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/WebRtcTuning.kt)):

- **Audio repeats itself.** With RED, every 10 ms packet also carries the 3
  before it (`WebRTC-Audio-Red-For-Opus/Enabled-3/`; WebRTC's default is 1),
  so up to 30 ms of consecutive loss is repaired exactly instead of
  concealed. That adds bytes, not packets, and on Wi-Fi each packet's airtime
  costs more than its size: about 100 kbps more at HD voice. Opus's own
  in-band FEC stays on underneath.
- **Lost voice is asked for again.** Both clients put `a=rtcp-fb:<opus> nack`
  in the descriptions they send. WebRTC switches audio NACK on from the
  description it receives
  ([`pc/channel.cc`](https://webrtc.googlesource.com/src/+/refs/branch-heads/6367/pc/channel.cc),
  `SetReceiveNackEnabled(SenderNackEnabled())`), so each sender keeps 5 s of
  packets and each receiver asks for the ones RED couldn't repair. NetEq only
  asks for a packet a resend can still bring in before it's due to play, so
  this never adds delay; it rescues the longer gaps on links where the jitter
  buffer is deep anyway, which is exactly a long-distance call over weak Wi-Fi
  or mobile data. The browser tests check it over a simulated lossy link
  (`e2e/lossy-link.js`): with a sixth of the voice packets dropped, the
  receiver asks and the sender resends.
- **Voice first, then video.** WebRTC splits its bandwidth estimate between
  voice and video itself, but it only counts the voice's codec bitrate, not
  RED's copies (`audio_send_stream.cc` registers the codec rate with the
  allocator). HD voice with its copies is about 250 kbps on the wire and
  WebRTC reserves about 100, so video is handed ~150 kbps that aren't there.
  On a fast connection that's noise; under about 1.2 Mbps (a weak uplink in
  another country, say) the call sends more than the connection carries all
  the time, a standing queue that turns into delay and then loss, for the
  voice too. So every two seconds, from the estimate
  (`availableOutgoingBitrate`) and what the voice really sends
  ([`call/MediaBudget.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/MediaBudget.kt)):
  - video is capped at what the voice really leaves (`maxBitrateBps`), while
    the estimate is under 1.5 Mbps;
  - the voice steps down to 32, then 20 kbps Opus (still clear speech) when
    the estimate can't carry it with room for some video, since WebRTC sends
    audio at a fixed bitrate whatever its estimate says;
  - video pauses (`active = false` on its encoding; the camera keeps running)
    when even the leanest voice would leave it under 60 kbps, so the voice
    gets through. The other side is told (`weakConnection` in `media-state`)
    and says why instead of showing a frozen picture.

  Coming back is a probe: video resumes after 20 s, and the voice steps back
  up the same way; one that doesn't hold makes the next try wait twice as
  long, up to almost three minutes.
- **Longer packets when the copies can't keep up.** 10 ms packets each
  carrying the three before them repair gaps up to 30 ms. Weak Wi-Fi and
  mobile data at the far end of a long international path lose longer runs,
  and then NetEq has to conceal what's missing. Each side watches the voice
  it receives: when packets go missing *and* audio still has to be concealed
  after RED, Opus FEC and resends have done what they can, it asks the other
  side for 20 ms packets, then 40 ms (`a=ptime`, which WebRTC senders take as
  Opus's frame length). The same three copies then cover 60 or 120 ms, at
  half or a quarter of the packet rate, for 10 or 30 ms more delay. After a
  calm minute it steps back down; a step down that doesn't hold makes the
  next wait twice as long
  ([`call/PacketTime.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/PacketTime.kt),
  [`web/js/ptime.js`](../web/js/ptime.js)). What we ask for travels in our
  description, so the offering side renegotiates in place, without
  restarting ICE, and the answering side asks it to (`request-offer` with
  `iceRestart: false`). The browser tests run it over a simulated link that
  loses 100 ms of voice every second, both ways round.
- **A jitter buffer sized for spikes.** WebRTC sizes the audio buffer to
  absorb 95% of the delay spikes it has seen; Earshot asks for 97%
  (`WebRTC-Audio-NetEqDelayManagerConfig/quantile:0.97/`), so a jittery
  network causes fewer dropouts. On a steady network the two are the same;
  the extra delay only appears while the network is that bad. The buffer can
  hold a full second (`audioJitterBufferMaxPackets` 100), so a long stall
  doesn't overflow it.
- **Video that doesn't freeze on a lost packet.** VP8 sent with three
  temporal layers (`scalabilityMode` L1T3): half the frames are referenced by
  nothing and a quarter by one other, so a loss usually costs one frame
  instead of stalling the picture until it's resent or a new keyframe
  arrives. WebRTC already keeps the frame rate and lowers resolution when
  bandwidth drops (`MAINTAIN_FRAMERATE`), so motion stays smooth.
- **Software encoding when the link is weak.** Android's WebRTC uses the
  phone's hardware VP8 encoder whenever there is one, with software only as
  a fallback for errors (`sdk/android/src/jni/video_encoder_fallback.cc`
  passes `prefer_temporal_support=false`). Hardware encoders make no
  temporal layers, so on most phones the line above did nothing, and their
  rate control is at its worst at low bitrates. WebRTC only scales the camera
  down to 360p or less when bandwidth is short, and each resolution change
  re-initialises the encoder; with `WebRTC-VP8-Forced-Fallback-Encoder-v2`
  set, the fallback wrapper then switches to libvpx below that size. So a
  weak link gets temporal layers and libvpx's rate control, and a good one
  keeps the cheaper hardware encoder
  ([`call/WebRtcTuning.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/WebRtcTuning.kt)).
- **Wi-Fi out of power save.** A phone in power save lets the router hold its
  packets and fetches them in bursts. The call holds Android's low-latency
  Wi-Fi lock (screen on, app in front) and the high-performance one, which
  also covers the screen being off on Android 10 to 13. From Android 14 there
  is no way for an app to do that with the screen off.
- **Fast failover** (above), and mobile data on standby if you allow it.
- **Which way it's weak.** On a call between two countries the weak part is
  usually one person's uplink, so the call screen says which direction
  struggles: "Weak connection from Sam" when their voice reaches you with
  loss or gaps that had to be filled in, "to Sam" when their side reports
  your packets going missing (RTCP `fractionLost`) or your voice had to get
  leaner, or video pause, to fit. Tap the delay readout for both directions
  (good, fair or poor, with the numbers) and the packet length in use.

## The two phones look after each other's network

Each direction of a call has its own congestion control, and each only sees
its own direction. Some of what makes a call rough is on the other phone's
side of the route, where only the other phone can see it, so each side tells
the other about its half (`network`, `uplink` and `radioShared` in
`media-state`, sent when they change and have held for two stats intervals):

- **Their Wi-Fi uplink starving: lighter video from us.** Wi-Fi is
  half-duplex, one shared channel taking turns. The video we send comes down
  through their access point on the same airtime their phone needs to get its
  own voice up, so on weak or busy Wi-Fi our downstream can be what starves
  their upstream. When they report their uplink tight (voice leaner, or
  packets going missing) or starved (video paused for the voice) on Wi-Fi for
  a few seconds, our video to them is capped at 800 or 400 kbps. If their
  uplink gets any better within 45 seconds, the cap stays until it has been
  fine for a minute; if not, it wasn't our downstream, the cap lifts and
  isn't tried again for five minutes. Mobile data has separate up and down
  channels, so it never applies there
  ([`call/AirtimeShare.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/AirtimeShare.kt)).
- **A radio shared with earbuds: half as many packets, both ways.** On
  2.4 GHz Wi-Fi next to Bluetooth audio, every Wi-Fi frame is airtime the
  earbuds can't use, and the voice's 100 packets a second each way are as many
  frames as the video's. While either phone is in that spot, both ask for
  20 ms audio packets at least ([`PacketTime.floor`](../android/app/src/main/java/io/github/nomskis/earshot/call/PacketTime.kt)):
  10 ms more delay, half the voice's frames, the earbuds get their turns
  back. The phone on the shared radio can't ask for its own sending to change
  (the other side decides that), so it says `radioShared` and the other side
  asks for it.

## Calls that remember the route

Every call otherwise starts blind: WebRTC guesses 300 kbps and finds the real
figure over the first seconds, the voice starts as HD voice in 10 ms packets,
and on a weak international route it takes the first half minute to settle on
what works (voice first, longer packets). Two people who call each other keep
calling over much the same route, so each call remembers, per contact and per
kind of network on our side (Wi-Fi or mobile data): a cautious figure for
what we could send (the lower quartile of WebRTC's estimate over the last
minutes), the audio packet length that held, and the voice level that fit
([`call/LinkMemory.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/LinkMemory.kt)).
The next call starts there: the bandwidth estimate through
`PeerConnection.setBitrate` (which resets WebRTC's estimators and aims its
start-up probes at that rate; capped at 1 Mbps, since it probes up from there
in a second or two anyway), the packet length and voice level as the
starting points of their planners. Both still adapt as usual, so a route
that has got better is found again within a minute. A reconnect within a
call starts from what the call has learned so far. Memories last two weeks
and never leave the phone.

## Answering a call that's already connected

Setting a call up takes several trips over the route: the answering phone's
connection to the server, the offer and answer through it, the connectivity
checks, the encryption handshake. Between Finland and Morocco that's a few
seconds of "Connecting…" after you've tapped Accept, while the other person
is already saying hello. So when a saved contact rings, the phone does all of
that while it rings: it joins the call and connects, but sends nothing and
plays nothing. The microphone isn't even prepared: the voice's stream is
held inactive (its encoding's `active` flag) and WebRTC's habit of preparing
the recorder as soon as a connection can send is switched off
(`InitAudioRecordingOnSend`), with recording off as well
(`setAudioRecording(false)`). Playback is off (`setAudioPlayout(false)`),
the camera stays off and video is inactive, and the phone's audio mode and
your music are left alone. The
caller's app sees the phone join "still ringing", keeps its ringing tone and
"Ringing…", and holds its own microphone and video back the same way. Tapping
Accept then only has to switch the sound (and camera) on, so the call is live
straight away. The caller starts sending when the answering phone says so, or
as soon as that phone's voice arrives, so a message lost with a dropped
connection to the server can't leave it on hold. If the caller's own
connection to the server drops while it rings (a switch from Wi-Fi to mobile
data, say), it rings again and the ringing phone takes that as the same call.

Both apps have to know about it (the ring says so, through the server), so
with an older app on either side, or an older server, calls set up after the
answer as before. It's only done for saved contacts, because connecting shows
the caller the phone's network addresses before you've answered. Declining,
or the caller giving up, closes the early connection. Answering in a way it
wasn't made for (voice only on a video call, or after switching audio mode)
starts afresh like before; earbuds put in while it rang don't matter, since
the echo canceller follows the earbuds during a call anyway. The details are
in [protocol.md](protocol.md) ("Connecting while it rings").

## Calls abroad: through the relay's network

A direct path between two countries takes whatever route the two internet
providers' transit gives it, and on a busy evening that middle stretch can
lose or delay packets on its own. **Route calls through the relay** (Settings,
"Calls abroad", off by default) sends the call through the server's TURN relay
at both ends instead. Cloudflare's relay is anycast: each phone reaches the
Cloudflare city nearest to it, and when both ends relay through it, Cloudflare
can carry the stretch between those cities over its own backbone
([Cloudflare's TURN docs](https://developers.cloudflare.com/calls/turn/overview/)).

Whether that beats the direct route depends on the providers and the hour, so
it's a switch to try, with the delay readout ("through a relay", loss each
way) to compare. Either side turning it on is enough: the app lists
`relay-route` in its join message and the other side, app or browser, relays
too when its server gave it a relay (`iceTransportPolicy` `relay`,
[`call/RelayRoute.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/RelayRoute.kt)).
A connection that hasn't come up through the relay within 12 seconds goes
direct for the rest of the call, so a relay that's down never stops a call.

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

**Mobile data is never used next to working Wi-Fi unless you turn it on.**
By default a call gathers no candidates on mobile data while the phone's own
network is Wi-Fi that reaches the internet (WebRTC's `LOW_COST` candidate
policy, decided per connection). When Wi-Fi is gone, or stuck at a gym's
login page so Android itself is on mobile data, the call uses mobile data
like any other app would.

WebRTC's defaults check standby paths every 25 seconds and call a path dead
after 5 seconds without an answer. Earshot switches when the path in use
goes 1 second without packets and checks standby paths every 2 seconds.
With **Mobile data as a backup** switched on (Settings, off by default), it
also keeps mobile data up next to Wi-Fi during calls. ICE prefers Wi-Fi as
the cheaper network, moves to mobile data when Wi-Fi stalls, and comes back
when it recovers; until then mobile data only carries connection checks.

With the backup on, crowded gym Wi-Fi more often degrades than stalls: it drops packets but never
goes silent, so ICE never leaves it. Those connection checks are a fair test
of each path, though, so Earshot compares them
([`call/PathSteering.kt`](../android/app/src/main/java/io/github/nomskis/earshot/call/PathSteering.kt)):
when the Wi-Fi path loses a fifth of its checks over ten seconds while the
mobile-data path loses almost none, the problem is this phone's Wi-Fi rather
than the other side's network, and the call prefers mobile data. It stays
there at least a minute, then goes back once Wi-Fi's checks come through
cleanly again (or if mobile data gets as bad). When both paths lose checks,
nothing moves: the trouble is on the other end.

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

If a call gets killed anyway, it doesn't vanish: a running call marks itself
alive every minute and clears the mark when it ends properly, so the next
launch within 15 minutes offers **Rejoin**. The peer id is per install, so
inside the server's grace period the call simply resumes.

## Long calls at the gym

- **Pocket guard.** While the call screen is up, Earshot holds a proximity
  wake lock, like a phone call: covered, the screen goes dark and ignores
  touches. The activity isn't stopped by that, so "is the call screen
  visible" also checks that the display is on, and chat messages become
  notifications you can hear.
- **Camera pause.** Covered for 2 seconds, the camera stops (it would only
  film the pocket) and `media-state` tells the other side `inPocket`, so they
  see why. That also gives the Wi-Fi airtime back to the earbuds on 2.4 GHz.
- **Overheating.** Video stays at full quality unless Android reports severe
  or critical heat (Android 10+ thermal status), where it throttles hard and
  starts switching the camera off. Then outgoing video drops to 800 kbps,
  24 fps and two thirds of the resolution (severe) or 400 kbps, 15 fps and
  half (critical), which keeps it going; the voice is left alone.
- **Dropped earbuds.** Their voice pauses rather than playing out loud, and
  echo cancellation comes on while it does play out loud (see Echo above).
- **Chat.** For when one of you can't talk or hear: a data channel, so
  encrypted and phone to phone, with quick replies from the notification.

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
