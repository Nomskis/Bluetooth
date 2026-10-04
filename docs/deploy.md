# Running the server

The server is a single Node.js process (Node 20 or newer) with one dependency.
It needs very little: a few MB of RAM per call, almost no CPU, and its traffic
is tiny because the media goes directly between the phones.

It needs to be reachable over **HTTPS** from both sides. Browsers only allow
the camera and microphone on secure pages, and the Android release build only
connects over TLS.

## Try it on your Wi-Fi

```sh
cd server
npm install
npm start
```

- Android **debug** builds can connect to `http://<your-laptop-ip>:8080`
  (type `192.168.1.20:8080` in Settings).
- A browser on the laptop itself can use `http://localhost:8080`.
- A browser on another device needs HTTPS, so for a full test use one of the
  options below, or a tunnel like `cloudflared tunnel --url http://localhost:8080`.

## Docker on a VPS, with automatic HTTPS

On any small Linux server with Docker and a domain pointing at it:

```sh
git clone https://github.com/Nomskis/Bluetooth.git earshot && cd earshot
cp .env.example .env        # set EARSHOT_DOMAIN=calls.example.com
docker compose --profile https up -d
```

[Caddy](https://caddyserver.com) gets a Let's Encrypt certificate and proxies
`https://calls.example.com` (WebSockets included) to the server. Put
`https://calls.example.com` into the app's Settings.

## The quickest way: Render or Fly.io

- **Render, free:** sign in at [render.com](https://render.com), choose
  **New › Blueprint**, pick your copy of this repository and confirm. The
  included [`render.yaml`](../render.yaml) does the rest. You get an address
  like `https://earshot-xxxx.onrender.com`; put it into the app's Settings.
  The free plan sleeps after a while without calls, so the first connection
  afterwards takes about a minute. The Android app wakes it in the background
  whenever you open the app, so it's usually up by the time you've picked a
  room; the browser side wakes it by loading the invite page.
  The included `render.yaml` puts the service in Frankfurt. The call itself
  never goes through the server, but every step of setting one up (and of
  ringing, and of reconnecting) makes a round trip to it: from Europe or North
  Africa that's about 40 ms to Frankfurt against about 170 ms each way to
  Oregon, Render's default. For people elsewhere, change `region` before
  creating the service. Render can't move a service that already exists:
  create a new one instead (New › Blueprint with this repository, or New ›
  Web Service, this repository, Docker, the region you want, free plan), put
  its address into Settings on both phones, then delete the old one (free
  hours are shared between services). Contacts keep working, since they
  don't depend on the server's address.
- **Fly.io:** install `flyctl`, then in the repository run
  `fly launch --copy-config --no-deploy` and `fly deploy`. The included
  [`fly.toml`](../fly.toml) keeps one small machine running so calls connect
  instantly.

Both deploy from the repository's default branch.

## Hosting platforms

Anything that runs a Dockerfile or a Node app and terminates TLS for you works,
for example Render, Fly.io or Railway:

- **Build:** the [`Dockerfile`](../Dockerfile) at the repository root, or
  `cd server && npm ci` with start command `node server/src/index.js` from the
  repository root.
- **Port:** the platform's `PORT` variable is respected.
- **Health check:** `GET /healthz`.
- WebSockets must be allowed (they are by default on the platforms above).
- Free tiers that sleep when idle add a delay to the first connection.

## TURN (when calls won't connect)

Most calls connect directly. Some networks (certain mobile carriers, strict
corporate or gym Wi-Fi) block that, and then a TURN relay is needed to carry
the media. Symptoms: both sides see each other "connecting…" forever; after
15 seconds the apps say so and point here.

### The easy way: a hosted relay

This works on any host, including Render's free plan (which can't run a relay
itself). The server fetches short-lived credentials from the relay service and
hands them to each caller; nothing else to run.

- **Cloudflare** (the first 1,000 GB a month are free, shared with its SFU
  product; a relayed video call uses roughly 1 to 2 GB an hour). In the Cloudflare
  dashboard, open Realtime, create a TURN key, and set its two values (on
  Render: your service's **Environment** tab, then save, which redeploys):

  ```sh
  CLOUDFLARE_TURN_KEY_ID=...
  CLOUDFLARE_TURN_API_TOKEN=...
  ```

- **Any service that serves an ICE server list over GET**, for example
  Metered's Open Relay:

  ```sh
  TURN_CREDENTIALS_URL=https://<your-app>.metered.live/api/v1/turn/credentials?apiKey=...
  ```

Credentials last `TURN_TTL_SECONDS` (12 hours by default; Cloudflare uses the
value you set) and are renewed every quarter of that, so a caller always gets
ones with most of their lifetime left. If the service can't be reached, the
server keeps handing out the last good set and retries every minute. The
server's start-up log says which relay it uses.

### Running your own

The bundled compose file can run [coturn](https://github.com/coturn/coturn):

```sh
# in .env
TURN_SECRET=$(openssl rand -hex 32)
TURN_URLS=turn:calls.example.com:3478?transport=udp,turn:calls.example.com:3478?transport=tcp

docker compose --profile https --profile turn up -d
```

Open UDP/TCP 3478 and UDP 49160–49200 in your firewall. The server hands each
peer time-limited TURN credentials derived from `TURN_SECRET` (coturn's
`use-auth-secret` scheme), so the secret itself never leaves the server.

For calls between countries, a hosted relay can do more than get through
blocked networks: with **Route calls through the relay** on (Settings, "Calls
abroad"), both phones send the call through it, and Cloudflare can carry the
stretch between the two countries over its own network. See
[how-it-works.md](how-it-works.md#calls-abroad-through-the-relays-network).

A relay with fixed credentials works too: set `TURN_URLS`, `TURN_USERNAME`
and `TURN_CREDENTIAL`. A relay never sees the call's content: WebRTC media is encrypted end to end
(DTLS-SRTP), and the relay only forwards the encrypted packets. Gyms with
locked-down Wi-Fi are a common reason to set one up; pick a relay that
offers TCP or TLS on port 443, which almost every network allows.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `HOST` | `0.0.0.0` | Interface to listen on |
| `WEB_ROOT` | `../web` relative to `server/src` | Where the browser client's files are |
| `MAX_PEERS_PER_ROOM` | `2` | Room size. The clients are one-to-one for now. |
| `RECONNECT_GRACE_MS` | `20000` | How long a dropped peer keeps its slot |
| `HEARTBEAT_MS` | `25000` | WebSocket ping interval for detecting dead connections |
| `RING_TIMEOUT_MS` | `60000` | How long a call rings before it counts as unanswered |
| `STUN_URLS` | Google's public STUN servers | Comma-separated |
| `TURN_URLS` | none | Comma-separated TURN URLs |
| `TURN_SECRET` | none | Shared secret for time-limited TURN credentials |
| `TURN_USERNAME`, `TURN_CREDENTIAL` | none | Fixed TURN credentials, if not using a secret |
| `TURN_TTL_SECONDS` | `43200` | Lifetime of issued TURN credentials |
| `CLOUDFLARE_TURN_KEY_ID`, `CLOUDFLARE_TURN_API_TOKEN` | none | Cloudflare's hosted relay; the server fetches credentials |
| `TURN_CREDENTIALS_URL` | none | Any URL that returns an ICE server list (`[...]` or `{ "iceServers": [...] }`) on GET |

## Privacy

The server sees room codes, display names and the connection metadata needed
to introduce the peers. It never sees or stores audio or video, and it keeps
nothing on disk. Anyone who knows a room code can join it while it has a free
slot, so use the generated codes rather than something guessable.
