# Signaling protocol (v1)

Clients talk to the server over one WebSocket at `/ws`, exchanging JSON text
messages. The server only manages rooms and relays messages; audio and video
go directly between the peers (or through TURN) with WebRTC, encrypted end to
end with DTLS-SRTP.

Example messages for every type live in [`protocol/fixtures`](../protocol/fixtures).
Both the server tests and the Android tests check them, so a change to the
format has to update the examples and keep both sides passing.

## Client → server

| `type` | Fields | Meaning |
| --- | --- | --- |
| `join` | `room`, `peerId`, `name?`, `client?` | Enter a room, or resume your place in it. Send again after every reconnect. |
| `signal` | `to`, `data` | Relay `data` to another peer in the room. |
| `leave` | | Hang up. Frees your slot immediately. |
| `ping` | | Answered with `pong`. Optional; the server also uses WebSocket pings. |

- `room`: 3 to 64 characters, `a-z`, `0-9` and `-`, not starting or ending with
  `-`. Case-insensitive; the server lower-cases it.
- `peerId`: 8 to 64 URL-safe characters, chosen by the client. Keep it stable
  for as long as you want to be able to resume (Android: per install; web: per
  tab).
- `client`: `{ "platform": "android" | "web" | ..., "version": "0.1.0", "capabilities": ["hifi-audio", "chat"] }`.
  `chat` means the client has text chat (below); clients show the chat only
  when the other side lists it. `renegotiate` means it answers
  `request-offer` with `iceRestart: false` by renegotiating in place.
  `relay-route` means this side wants the call through the TURN relay at
  both ends; the other side relays too (relay-only ICE) when it has a relay,
  and goes direct if that doesn't connect within 12 s. `ringing` means this
  peer joined while its phone is still ringing (see "Connecting while it
  rings" below).

## Server → client

| `type` | Fields | Meaning |
| --- | --- | --- |
| `joined` | `protocol`, `room`, `peerId`, `seq`, `resumed`, `peers[]`, `iceServers[]` | You're in. `peers` is everyone else in the room right now. |
| `peer-joined` | `peer` | Someone new entered the room. |
| `peer-left` | `peerId`, `reason` | Someone left (`left`) or didn't come back in time (`timeout`). |
| `signal` | `from`, `data` | A relayed message from another peer. |
| `error` | `code`, `message` | `bad-request`, `bad-room`, `room-full`, `not-in-room`, `unknown-peer`, `rate-limited`. The socket stays open. |
| `pong` | | Reply to `ping`. |

A peer is described as `{ peerId, name, client, seq }`. `seq` is the order in
which peers joined the room, starting at 1. It never changes while the peer
stays in the room, including across reconnects.

`iceServers` is in the browser's `RTCIceServer` format. When the server has a
TURN secret configured, it issues time-limited TURN credentials per peer.

## Ringing

A client can wait for calls, and another can ring it, without either being in
a room. The server only passes rings along and forgets them once they're
answered, declined or over.

Each install keeps a secret **inbox key** (22 to 128 URL-safe characters). Its
public **address** is the first 22 characters of the base64url (no padding)
SHA-256 of `earshot-inbox:` followed by the key. Knowing an address lets you
ring it, not listen on it. Clients exchange addresses over the chat data
channel during a call (see `contact` below).

| Client → server | Fields | Meaning |
| --- | --- | --- |
| `listen` | `inbox` | Deliver rings for this key's address to this socket. Several devices may listen on one address. |
| `ring` | `to`, `ringId`, `room`, `name?`, `video?`, `inbox?`, `preconnect?` | Ring an address. `ringId` is chosen by the caller (8 to 64 URL-safe characters). `inbox` is the caller's own key, which proves its address to the callee. `preconnect: true`: the callee may connect while it rings (below). |
| `ring-cancel` | `ringId` | The caller gave up. Closing the socket does the same. |
| `ring-answer` | `ringId`, `accepted`, `reason?` (`declined`, `busy`) | From a device listening on the rung address. The first answer wins. |

| Server → client | Fields | Meaning |
| --- | --- | --- |
| `listening` | `address` | Rings for this address will come here. |
| `incoming` | `ringId`, `room`, `from: { name, address }`, `video`, `preconnect?` | Someone is ringing. `address` is null if the caller didn't prove one. `preconnect` is passed on from the ring, only when true. |
| `ring-status` | `ringId`, `status` (`ringing`, `unreachable`), `devices?` | To the caller, straight after `ring`. |
| `ring-cancelled` | `ringId`, `reason` (`cancelled`, `timeout`, `answered-elsewhere`) | To listening devices: stop ringing. |
| `ring-answered` | `ringId`, `accepted`, `reason?` (`declined`, `busy`, `no-answer`) | To the caller. |

Rings time out after `RING_TIMEOUT_MS` (60 s). To accept, the callee joins
`room`, where the caller is already waiting; the call then sets up as usual.
Listening sockets get the same heartbeat as any other (a WebSocket ping every
`HEARTBEAT_MS`, dropped if unanswered), so a phone that lost its connection
stops counting as reachable within seconds. Listening clients also send a
`ping` message every minute, which some hosts (Render's free plan) need to see
to stay awake.

How the Android app uses it: the caller joins a fresh room (`call-` and 16
random lowercase letters and digits, so nobody can guess it) and rings once
it's there alone. A ring is lost when the caller's connection drops, so it
rings again with a new `ringId` after rejoining. While the answer is
`unreachable` it rings again every 5 s for a minute, so a phone coming back
online starts ringing straight away; after that the room stays open for the
invite link. A decline, `busy` or no answer ends the call after a short
message. If the server answers `ring` with `bad-request` (a server older than
ringing), the app treats the contact as unreachable.

### Connecting while it rings

Setting a call up takes several trips: the callee's socket to the server, the
offer and answer through it, the connectivity checks, the encryption
handshake. Over a slow route (a call abroad) that's seconds of "Connecting…"
after the callee has already said hello. So the callee's phone may do all of
it while it rings, and only switch the sound on when it's answered:

- The caller says it can take this with `preconnect: true` on its `ring`; the
  server passes it on in `incoming`. An older caller doesn't, and an older
  server drops it, and the call works as before.
- The callee (the Android app: only for a caller saved as a contact, since
  connecting shows the caller the phone's network addresses before anyone
  answers) joins `room` straight away with `ringing` in its
  `client.capabilities` and negotiates as usual. It sends no audio or video
  and plays nothing (the microphone doesn't even start), and it leaves the
  phone's audio mode, the music and the camera alone. Its `media-state`
  carries `ringing: true`.
- The caller, seeing a peer join with `ringing` while its ring is unanswered,
  keeps ringing (ringback tone, "Ringing…") and sends nothing either, but
  answers the offer, so the connection comes up.
- Accepting sends `ring-answer` as always; the callee then starts its
  microphone, playback and camera on the connection that's already up (in
  headset mode it first waits, up to 1.5 s, for Bluetooth earbuds' call link)
  and sends a `media-state` without `ringing`. The caller starts sending on
  that `media-state`, or on the callee's voice arriving (a ringing phone sends
  none), whichever comes first. `ring-answered` alone only shows "answered,
  connecting": the callee may be answering on a fresh connection instead.
- Declining, the caller hanging up and the timeout end the ring as always;
  the callee's early connection then leaves the room. Answering with
  something the connection wasn't made for (voice only on a video call, or a
  changed audio mode) leaves it too, and joins afresh. A peer's `ringing`
  only counts while the ring is unanswered: once answered, or once the peer
  has been in the call, its join is just a join.
- The server ends a ring with the caller's socket, so a caller that rejoins
  while the callee's early connection is still there rings again. The callee
  takes an `incoming` for the room that's ringing, from the same proven
  address, as the same call: it keeps ringing (and its early connection)
  under the new `ringId` and ignores the old ring's cancel. A ring for the
  room of the call it's already on is answered `accepted: true`.

When two people ring each other at once, each phone gets an `incoming` from
the person it's ringing. Both settle it the same way, by comparing the two
addresses as strings: the call from the lower address goes ahead. The phone
with the lower address ignores the other ring; the other phone answers the
winning ring with `accepted: true`, hangs up its own call (which cancels its
ring) and joins the winner's room.

## Messages between contacts

Contacts can write to each other outside calls, over the same inbox
connection that rings the phone. The sender has to be listening on its own
inbox, so the server knows (rather than trusts) who a message is from.

| Direction | `type` | Fields | Meaning |
| --- | --- | --- | --- |
| client → server | `message` | `to`, `id`, `text`, `name?` | A message to the inbox address `to`. `id`: 8 to 64 URL-safe characters, made by the sender; `text`: 1 to 4000 characters. |
| server → client | `message` | `id`, `from: { address, name }`, `text`, `sentAt` | A message for us. `from.address` is the sender's proven address. |
| client → server | `message-ack` | `to`, `id` | We have message `id` from `to`. Sent for every copy, repeats included. |
| client → server | `message-read` | `to`, `id` | We've seen `to`'s messages up to `id` (their latest we have). Sent when the conversation is on screen. |
| server → client | `message-status` | `id`, `to`, `status` | How our message to `to` is doing: `sent` (a device of theirs is online), `queued` (none is; it waits), `delivered` (one of their devices has it), `read` (they've seen it, and the ones before it that were delivered). |

The server delivers a message to every device listening on `to`, and holds
it in memory until one of them acks it (at most 14 days, 500 per inbox);
a device that starts listening gets the waiting ones first. It keeps them in
memory only: the sending app keeps every message it hasn't heard `delivered`
for and sends it again each time its inbox is listening again, so a server
restart loses nothing. A message sent twice is held once (same sender and
`id`), and the receiving app keeps it once. More than 30 messages in 10
seconds from one connection gets `rate-limited`; sending without listening
gets `not-listening`.

A `message-read` is passed to the sender's devices that are online and not
held: one that misses them leaves "delivered" showing until the reader opens
the conversation again. Blocking someone happens on the phone alone: their
rings are ignored (on their side the call rings out) and their messages are
acked and dropped, so the server stops holding them and they aren't told.

## Reconnecting

If a socket closes without `leave`, the server keeps the peer's slot for
`RECONNECT_GRACE_MS` (20 s by default). Signals sent to it in the meantime are
queued (up to 100). When the peer connects again and sends `join` with the
same `peerId`, it gets `joined` with `resumed: true`, the current peer list,
and then the queued signals. The other peers aren't told anything happened.
The media connection usually keeps running the whole time.

If the grace period runs out, the others get `peer-left` with reason `timeout`.

A second socket joining with a `peerId` that's already connected replaces the
first one, which is closed with code `4000`. Clients shouldn't reconnect after
a `4000` close.

## Peer-to-peer messages (`signal.data`)

| `kind` | Fields | Sent by |
| --- | --- | --- |
| `offer` | `session`, `sdp` | the offerer |
| `answer` | `session`, `sdp` | the answerer |
| `candidate` | `session`, `candidate: { candidate, sdpMid, sdpMLineIndex, usernameFragment? }` | both |
| `request-offer` | `session` (may be null), `iceRestart?` | the answerer, when it needs a fresh offer |
| `media-state` | `micMuted`, `cameraOff`, `audioMode?` (`hifi`, `headset`, `standard`), `inPocket?`, `weakConnection?`, `network?`, `uplink?`, `radioShared?`, `ringing?` | both, after connecting and on every change |

`inPocket: true` (with `cameraOff: true`) means the camera paused itself
because the phone's proximity sensor is covered, a pocket usually; show that
rather than "camera off". `weakConnection: true` (with `cameraOff: true`)
means the camera is on but the sender paused its video because the
connection can't carry it next to the voice; it resumes by itself.

`network` (`wifi`, `cellular`), `uplink` (`tight`, `starved`; absent when
fine) and `radioShared` describe the sender's half of the route, so the other
side can help: lighter video towards a starving Wi-Fi uplink, and at least
20 ms audio packets towards a phone whose 2.4 GHz Wi-Fi shares its radio
with Bluetooth earbuds (see how-it-works.md). Clients send them when they
change.

`ringing: true` means the sender's phone is still ringing (it connected
early, see Ringing); a `media-state` without it from such a peer means it was
answered.

`request-offer` with `iceRestart: false` means the connection is fine and
the answerer only wants to change what it asks for (the audio packet length,
on a rough link): the offerer renegotiates on the same session without
restarting ICE, and ignores the request while it's mid-negotiation (the
answerer asks again). Answerers only send it to peers whose `client.capabilities`
list `renegotiate`; older offerers would restart ICE instead.

Ignore kinds you don't know; newer clients may send more.

## Negotiation

The web client (`web/js/call.js`) and the Android app (`CallSession.kt`)
implement the same algorithm:

- **Roles.** Between two peers, the one with the higher `seq` (who joined
  later) is the **offerer**. The other answers. Since both learn the same
  `seq` values from the server, there's never offer glare.
- **Sessions.** Each RTCPeerConnection has a random `session` id, chosen by the
  offerer. Messages carry it; anything for a session other than the current one
  is ignored. An offer with a new session id means "start over": the answerer
  closes its connection and builds a new one.
- **Starting.** On `joined` with a peer present, the offerer starts a session.
  On `peer-joined`, the existing peer waits for the newcomer's offer.
- **Resuming.** On `joined` after a reconnect, a peer whose connection is still
  healthy does nothing. Otherwise the offerer restarts ICE (or starts a new
  session), and the answerer waits 1.5 s for a queued offer before sending
  `request-offer`.
- **Recovering.** If ICE reports `disconnected` for 4 s, or `failed`, the
  offerer sends an ICE-restart offer on the same session; the answerer sends
  `request-offer` with its session id. If an offer gets no answer within
  10 s, the offerer starts a new session, unless the connection is still
  working (a renegotiation whose answer got lost): then it sends the offer
  again.
- **Media.** The offerer always offers to receive audio and video, even if it
  sends no camera itself, so the other side can still send video.
- **Chat.** Both sides create the chat data channel on every new
  RTCPeerConnection, before the offer or answer, so the offer carries an
  `m=application` section and nobody waits for the other's channel.
- **Leaving.** On `peer-left`, close the connection and wait for someone new.

## Text chat (data channel)

Chat messages go over a WebRTC data channel, not the server, so they're
end-to-end encrypted (DTLS) like the media. The channel is pre-negotiated:
label `earshot-chat`, `negotiated: true`, `id: 0`, ordered and reliable. Each
frame is one JSON text message:

| `kind` | Fields | Meaning |
| --- | --- | --- |
| `chat` | `id`, `text`, `sentAt?` | A message. `id` is 1 to 64 characters, unique per sender; `text` is trimmed and at most 1000 characters; `sentAt` is the sender's clock in ms since 1970. |
| `chat-ack` | `id` | Received. Sent for every `chat` frame, repeats included. |

A third frame, `contact` with `name` and `address`, introduces each side's
inbox address (see Ringing), so the apps can call each other directly next
time. It isn't acknowledged; it's sent again whenever the channel opens.

A client sends each message again, with the same `id`, when a new
connection's channel opens and the message hasn't been acknowledged yet. The
receiver shows each `id` once. Messages still unacknowledged when someone with
a different `peerId` takes the seat are marked as not sent. Examples live in
[`protocol/fixtures/peer`](../protocol/fixtures/peer); ignore kinds you don't
know.

## Versioning

`joined.protocol` is the server's protocol version (currently 1). Additive
changes (new optional fields, new `signal.data` kinds) keep the version.
Breaking changes bump it.
