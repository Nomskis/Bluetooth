import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

export const DEFAULT_STUN_URLS = [
  'stun:stun.l.google.com:19302',
  'stun:stun1.l.google.com:19302',
];

function int(value, fallback) {
  if (value === undefined || value === '') return fallback;
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n) || n < 0) {
    throw new Error(`Expected a non-negative integer, got "${value}"`);
  }
  return n;
}

function list(value, fallback) {
  if (value === undefined) return fallback;
  return value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

/**
 * Reads server configuration from environment variables.
 * Every option is documented in docs/deploy.md.
 */
export function loadConfig(env = process.env) {
  return {
    port: int(env.PORT, 8080),
    host: env.HOST || '0.0.0.0',
    webRoot: env.WEB_ROOT ? path.resolve(env.WEB_ROOT) : path.resolve(here, '../../web'),
    maxPeersPerRoom: Math.max(2, int(env.MAX_PEERS_PER_ROOM, 2)),
    reconnectGraceMs: int(env.RECONNECT_GRACE_MS, 20_000),
    heartbeatMs: int(env.HEARTBEAT_MS, 25_000),
    ringTimeoutMs: int(env.RING_TIMEOUT_MS, 60_000),
    ice: {
      stunUrls: list(env.STUN_URLS, DEFAULT_STUN_URLS),
      turnUrls: list(env.TURN_URLS, []),
      turnSecret: env.TURN_SECRET || null,
      turnUsername: env.TURN_USERNAME || null,
      turnCredential: env.TURN_CREDENTIAL || null,
      turnTtlSeconds: int(env.TURN_TTL_SECONDS, 12 * 60 * 60),
      // A hosted TURN service the server fetches credentials from (see turn-service.js).
      cloudflareTurnKeyId: env.CLOUDFLARE_TURN_KEY_ID || null,
      cloudflareTurnApiToken: env.CLOUDFLARE_TURN_API_TOKEN || null,
      turnCredentialsUrl: env.TURN_CREDENTIALS_URL || null,
    },
  };
}
