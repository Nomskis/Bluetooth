# Calls between countries over weak Wi-Fi

The case this is written for: calls between Morocco and Finland, with the
Moroccan side on home Wi-Fi that isn't good. Most of it holds for any long
route with one weak end.

## Where the time and the trouble are

A call's audio goes: microphone → encoder → phone's Wi-Fi → home router →
ISP → international links → the other ISP → the other phone. On this route:

- **The long haul is the easy part.** Morocco reaches Europe over several
  subsea cables (Maroc Telecom's own Casablanca–Lisbon segment opened in
  2022, among others), and Europe to Finland is well served. A direct path
  should give a round trip somewhere around 70 to 110 ms. That's audible as a
  slight lag in conversation, but it's steady. Nothing an app does makes the
  speed of light shorter.
- **The weak end is where calls break.** Home Wi-Fi through thick concrete
  and brick walls, on a crowded 2.4 GHz band, loses packets in bursts and
  holds others back. Moroccan fixed broadband ranks around the middle of the
  world for speed, but its median latency is high (about 64 ms to nearby test
  servers in 2025), which points at queueing in home routers and access
  lines rather than distance. Phones test slower than desktops there (14 vs
  20 Mbps), a sign of weak home Wi-Fi. ADSL is still common outside fibre
  areas, typically 10 to 20 Mbps down with a small upload, and 4G home boxes
  have data caps after which they slow right down.
- **Her upload is what you see.** Her video to you has to squeeze through her
  line's upload and her router's queue, shared with everyone else at home.
  When someone starts a big upload or a video stream, that queue fills and
  delay jumps by hundreds of milliseconds ("bufferbloat"); the call has to
  ride it out.
- **Connecting at all.** Mobile networks, and some home lines, sit behind
  carrier-grade NAT. Two phones behind that kind of NAT often can't reach
  each other directly, and then the call needs a relay (TURN). Without one,
  those calls fail to connect.

## What Earshot does about each

| Problem | What the app does |
| --- | --- |
| Bursts of lost audio | Packets that each repeat the 3 before them, starting at 20 ms (60 ms of consecutive loss repaired exactly) and going to 40 ms (120 ms) while gaps outrun the copies, Opus in-band FEC, resends of lost packets when a resend can still arrive in time, and Opus's own concealment for what's still missing ([concealment.md](concealment.md)) |
| Delay spikes (router queues, weak Wi-Fi) | A jitter buffer sized for 97% of spikes instead of 95%, able to hold 2 s, so a spike stretches the delay briefly instead of punching holes in the audio |
| A slow, shared upload | VP9 video (about a third fewer bits than VP8 for the same picture), WebRTC's bandwidth estimate steering the video bitrate, resolution lowered before frame rate, and the voice stepping down from HD to leaner Opus if even the voice doesn't fit |
| Lost video packets | Three temporal layers, so most losses cost one frame instead of a freeze; on a weak link (360p and below) the video is encoded in software, where the layers work |
| Wi-Fi power save | Android's Wi-Fi locks for the length of the call |
| Wi-Fi that's up but bad | Fast ICE failover, and (if allowed) mobile data on standby that the call moves to when Wi-Fi loses pings |
| Strict NATs, blocked UDP | TURN relays when the server has them; Cloudflare's include TLS on port 443, which gets through almost anything |

And a report for every call: route (direct or relayed), round trip, audio
lost and repaired, jitter buffer, video freezes, what held the video back.
Settings → Call reports → Copy report. That's what to look at, or send,
after a call that went badly.

## What to set up

1. **A relay.** Cloudflare's TURN service is free for the first 1,000 GB a
   month and runs in every Cloudflare location (anycast: each phone reaches
   the nearest one). Add `CLOUDFLARE_TURN_KEY_ID` and
   `CLOUDFLARE_TURN_API_TOKEN` to the server ([deploy.md](../deploy.md)).
   Calls still go direct when they can; the relay only carries calls that
   couldn't connect otherwise.
2. **The server near both of you.** The server only sets calls up (and rings
   phones); the call itself doesn't go through it. But every setup step and
   every reconnect makes a round trip to it. Render puts new services in
   Oregon unless told otherwise, which adds a transatlantic round trip (about
   170 ms each way from Morocco or Finland) to each step. Frankfurt is
   around 40 ms from both. Render can't move an existing service, so this
   means creating a new one in Frankfurt and updating the server address on
   both phones; contacts keep working (they don't depend on the server's
   address).
3. **On the Moroccan side:**
   - Use the router's 5 GHz network if it has one, and stay in the same room
     as the router or one wall away; concrete walls cost more than distance.
   - If the Wi-Fi keeps dropping out, turn on **Mobile data as a backup**
     (Settings). The call then moves onto 4G while the Wi-Fi is bad, and
     back. It uses the data plan: roughly 0.5 to 1.5 GB an hour of video.
   - Big uploads at home during the call (photo backups, someone else's video
     call) fill the router's queue; the call rides it out, but it shows.

## What's next, from real calls

These need data from real calls before they're worth doing; the call report
is how that data arrives.

- **Relay when it's better, not only when it's needed.** ICE picks a direct
  path whenever one works, even a worse one. Cloudflare's network between its
  Moroccan-facing and Finnish locations might beat the public route; if
  reports show direct calls with high loss or jitter, the app could test the
  relay path during the call and switch.
- **FlexFEC for video**, if video losses turn out to be the main problem.
- **AV1 video** on phones fast enough to encode it, for even fewer bits than
  VP9 on a slow upload.

## Sources

- Speed and latency in Morocco: [SpeedGEO Morocco internet guide](https://www.speedgeo.net/reports/morocco-internet-guide),
  [Ookla figures via TelecomLead](https://telecomlead.com/broadband/morocco-broadband-market-2026-best-isps-fiber-internet-speeds-ftth-expansion-and-gigabit-broadband-plans-126008),
  [Expat Focus on internet in Morocco](https://www.expatfocus.com/morocco/guide/morocco-internet)
- Subsea cables: [Maroc Telecom's cable investment (Ecofin)](https://www.ecofinagency.com/telecom/0206-44591-maroc-telecom-invests-150-mln-in-subsea-cable-to-connect-african-subsidiaries)
- VoIP in Morocco: blocked on mobile networks in January 2016, lifted in
  November 2016 ([Africanews](https://www.africanews.com/2016/11/06/morocco-lifts-ban-on-free-mobile-internet-calls-ahead-of-cop-22/))
- Cloudflare TURN: [service docs](https://developers.cloudflare.com/realtime/turn/),
  [TURN and anycast](https://blog.cloudflare.com/webrtc-turn-using-anycast/)
- Render regions: [Blueprint spec](https://render.com/docs/blueprint-spec)
  (`region` defaults to `oregon` and can't be changed after creation)
- WebRTC behaviour, from the source of the version the app ships
  (branch-heads/6367): `modules/audio_coding/codecs/red/audio_encoder_copy_red.cc`
  (RED redundancy field trial), `modules/audio_coding/neteq/delay_manager.cc`
  (jitter buffer quantile), `media/engine/webrtc_voice_engine.cc` (audio NACK
  and send bitrate), `media/engine/webrtc_video_engine.cc` (degradation
  preference), `api/video_codecs/video_encoder_software_fallback_wrapper.cc`
  (temporal layers with hardware encoders)
