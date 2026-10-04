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
  when the other side lists it.

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
| `ring` | `to`, `ringId`, `room`, `name?`, `video?`, `inbox?` | Ring an address. `ringId` is chosen by the caller (8 to 64 URL-safe characters). `inbox` is the caller's own key, which proves its address to the callee. |
| `ring-cancel` | `ringId` | The caller gave up. Closing the socket does the same. |
| `ring-answer` | `ringId`, `accepted`, `reason?` (`declined`, `busy`) | From a device listening on the rung address. The first answer wins. |

| Server → client | Fields | Meaning |
| --- | --- | --- |
| `listening` | `address` | Rings for this address will come here. |
| `incoming` | `ringId`, `room`, `from: { name, address }`, `video` | Someone is ringing. `address` is null if the caller didn't prove one. |
| `ring-status` | `ringId`, `status` (`ringing`, `unreachable`), `devices?` | To the caller, straight after `ring`. |
| `ring-cancelled` | `ringId`, `reason` (`cancelled`, `timeout`, `answered-elsewhere`) | To listening devices: stop ringing. |
| `ring-answered` | `ringId`, `accepted`, `reason?` (`declined`, `busy`, `no-answer`) | To the caller. |

Rings time out after `RING_TIMEOUT_MS` (60 s). To accept, the callee joins
`room`, where the caller is already waiting; the call then sets up as usual.
A socket that only listens isn't pinged by the server's heartbeat (that would
keep waking the phone); it sends its own `ping` every few minutes, and is
dropped after `LISTENER_IDLE_MS` (10 minutes) without hearing from it.

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
| `request-offer` | `session` (may be null) | the answerer, when it needs a fresh offer |
| `media-state` | `micMuted`, `cameraOff`, `audioMode?` (`hifi`, `headset`, `standard`), `inPocket?` | both, after connecting and on every change |

`inPocket: true` (with `cameraOff: true`) means the camera paused itself
because the phone's proximity sensor is covered, a pocket usually; show that
rather than "camera off".

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
  10 s, the offerer starts a new session.
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
