/**
 * TURN credentials from a hosted relay service, fetched by the server and
 * cached. A deployment that can't run its own relay (Render, for example,
 * has no UDP) still gets calls through networks that block direct ones:
 * mobile data behind carrier NAT, gym and office Wi-Fi.
 *
 * Two kinds:
 *  - Cloudflare (CLOUDFLARE_TURN_KEY_ID + CLOUDFLARE_TURN_API_TOKEN): POSTs to
 *    its generate-ice-servers endpoint for credentials that last TURN_TTL_SECONDS.
 *  - Any service that hands out an RTCIceServer list over GET
 *    (TURN_CREDENTIALS_URL), such as Metered's
 *    https://<app>.metered.live/api/v1/turn/credentials?apiKey=...
 *
 * Credentials are refreshed every quarter of their lifetime, so the ones a
 * client gets at join still have at least three quarters of it left: TURN
 * keeps checking them for as long as a relayed call runs.
 */

const CLOUDFLARE_URL = (keyId) =>
  `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`;
const FETCH_TIMEOUT_MS = 5000;
/** Retry a failed fetch after this long, whatever the lifetime. */
const RETRY_MS = 60_000;

/** Returns null when no hosted TURN service is configured. */
export function createTurnService(ice, { fetchImpl = globalThis.fetch, log = console, timers = globalThis } = {}) {
  let request;
  let name;
  if (ice.cloudflareTurnKeyId && ice.cloudflareTurnApiToken) {
    name = 'Cloudflare';
    request = () =>
      fetchImpl(CLOUDFLARE_URL(ice.cloudflareTurnKeyId), {
        method: 'POST',
        headers: { Authorization: `Bearer ${ice.cloudflareTurnApiToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ttl: ice.turnTtlSeconds }),
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
  } else if (ice.turnCredentialsUrl) {
    name = new URL(ice.turnCredentialsUrl).host;
    request = () => fetchImpl(ice.turnCredentialsUrl, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  } else {
    return null;
  }

  let servers = [];
  let timer = null;

  async function refresh() {
    try {
      const res = await request();
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const fresh = cleanIceServers(await res.json());
      if (!fresh.some(isRelay)) throw new Error('no TURN servers in the response');
      servers = fresh;
      return true;
    } catch (err) {
      // Keep handing out the last good set; it's still valid for a while.
      log.error(`TURN credentials from ${name} failed: ${err.message}`);
      return false;
    }
  }

  function schedule(ok) {
    const every = ok ? Math.max(RETRY_MS, (ice.turnTtlSeconds * 1000) / 4) : RETRY_MS;
    timer = timers.setTimeout(async () => schedule(await refresh()), every);
    timer.unref?.();
  }

  return {
    name,
    /** Fetches the first set and keeps it fresh. Resolves once the first attempt is over. */
    async start() {
      const ok = await refresh();
      schedule(ok);
      return ok;
    },
    stop() {
      timers.clearTimeout(timer);
      timer = null;
    },
    /** What to hand a client right now (empty until the first fetch succeeds). */
    current() {
      return servers;
    },
    refresh,
  };
}

function isRelay(server) {
  return server.urls.some((u) => /^turns?:/i.test(u));
}

/** Port 53, which Cloudflare also offers, is blocked by browsers and only slows them down. */
function usableUrl(url) {
  return typeof url === 'string' && /^(stun|turns?):/i.test(url) && !/:53(\?|$)/.test(url);
}

/** Accepts `[...]` or `{ iceServers: [...] }` (or a single server); normalises `urls` to arrays. */
export function cleanIceServers(body) {
  const list = Array.isArray(body) ? body : body?.iceServers;
  const entries = Array.isArray(list) ? list : list ? [list] : [];
  const out = [];
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') continue;
    const urls = [entry.urls ?? entry.url].flat().filter(usableUrl);
    if (urls.length === 0) continue;
    const server = { urls };
    if (typeof entry.username === 'string') server.username = entry.username;
    if (typeof entry.credential === 'string') server.credential = entry.credential;
    out.push(server);
  }
  return out;
}
