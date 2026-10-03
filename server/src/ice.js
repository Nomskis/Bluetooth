import crypto from 'node:crypto';

/**
 * Builds the ICE server list handed to a client when it joins.
 *
 * TURN supports two styles:
 *  - TURN_SECRET: time-limited credentials using the "TURN REST API" scheme that
 *    coturn implements with `use-auth-secret` / `static-auth-secret`.
 *  - TURN_USERNAME + TURN_CREDENTIAL: fixed credentials.
 */
export function buildIceServers(ice, peerId, nowMs = Date.now()) {
  const servers = [];
  if (ice.stunUrls.length > 0) {
    servers.push({ urls: ice.stunUrls });
  }
  if (ice.turnUrls.length > 0) {
    if (ice.turnSecret) {
      const expiry = Math.floor(nowMs / 1000) + ice.turnTtlSeconds;
      const username = `${expiry}:${peerId}`;
      const credential = crypto
        .createHmac('sha1', ice.turnSecret)
        .update(username)
        .digest('base64');
      servers.push({ urls: ice.turnUrls, username, credential });
    } else if (ice.turnUsername && ice.turnCredential) {
      servers.push({
        urls: ice.turnUrls,
        username: ice.turnUsername,
        credential: ice.turnCredential,
      });
    }
  }
  return servers;
}
