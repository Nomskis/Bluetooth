import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createEarshotServer } from '../src/server.js';
import { loadConfig } from '../src/config.js';
import { cleanIceServers, createTurnService } from '../src/turn-service.js';

const CLOUDFLARE_BODY = {
  iceServers: [
    { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.cloudflare.com:53'] },
    {
      urls: [
        'turn:turn.cloudflare.com:3478?transport=udp',
        'turn:turn.cloudflare.com:53?transport=udp',
        'turns:turn.cloudflare.com:443?transport=tcp',
      ],
      username: 'u1',
      credential: 'c1',
    },
  ],
};

const quiet = { error() {}, log() {} };

function fakeFetch(responses) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url, init });
    const next = responses.shift();
    if (next instanceof Error) throw next;
    return { ok: next.status === undefined || next.status < 300, status: next.status ?? 201, json: async () => next.body };
  };
  return { impl, calls };
}

const fakeTimers = { setTimeout: () => ({ unref() {} }), clearTimeout() {} };

describe('hosted TURN credentials', () => {
  it('asks Cloudflare for credentials with the configured lifetime, dropping port 53', async () => {
    const { impl, calls } = fakeFetch([{ body: CLOUDFLARE_BODY }]);
    const ice = { cloudflareTurnKeyId: 'key/1', cloudflareTurnApiToken: 'tok', turnTtlSeconds: 86400 };
    const service = createTurnService(ice, { fetchImpl: impl, log: quiet, timers: fakeTimers });
    assert.equal(await service.start(), true);
    assert.equal(calls[0].url, 'https://rtc.live.cloudflare.com/v1/turn/keys/key%2F1/credentials/generate-ice-servers');
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.headers.Authorization, 'Bearer tok');
    assert.deepEqual(JSON.parse(calls[0].init.body), { ttl: 86400 });
    assert.deepEqual(service.current(), [
      { urls: ['stun:stun.cloudflare.com:3478'] },
      { urls: ['turn:turn.cloudflare.com:3478?transport=udp', 'turns:turn.cloudflare.com:443?transport=tcp'], username: 'u1', credential: 'c1' },
    ]);
  });

  it('keeps the last good credentials when a refresh fails', async () => {
    const { impl } = fakeFetch([{ body: CLOUDFLARE_BODY }, new Error('offline'), { status: 401, body: {} }, { body: { iceServers: [] } }]);
    const ice = { cloudflareTurnKeyId: 'k', cloudflareTurnApiToken: 't', turnTtlSeconds: 3600 };
    const service = createTurnService(ice, { fetchImpl: impl, log: quiet, timers: fakeTimers });
    await service.start();
    const good = service.current();
    for (let i = 0; i < 3; i++) assert.equal(await service.refresh(), false);
    assert.deepEqual(service.current(), good);
  });

  it('reads a plain list from any credentials URL (Metered style)', async () => {
    const body = [{ urls: 'stun:stun.relay.metered.ca:80' }, { urls: 'turn:global.relay.metered.ca:80', username: 'a', credential: 'b' }];
    const { impl, calls } = fakeFetch([{ status: 200, body }]);
    const service = createTurnService(
      { turnCredentialsUrl: 'https://demo.metered.live/api/v1/turn/credentials?apiKey=x', turnTtlSeconds: 3600 },
      { fetchImpl: impl, log: quiet, timers: fakeTimers },
    );
    assert.equal(service.name, 'demo.metered.live');
    await service.start();
    assert.equal(calls[0].init.method, undefined);
    assert.equal(service.current()[1].urls[0], 'turn:global.relay.metered.ca:80');
  });

  it('is off unless configured, and ignores junk', () => {
    assert.equal(createTurnService({ turnTtlSeconds: 1 }), null);
    assert.deepEqual(cleanIceServers({ iceServers: [null, { urls: 'http://nope' }, { urls: [] }] }), []);
    assert.deepEqual(cleanIceServers({ iceServers: { urls: 'turn:t:3478', username: 'u', credential: 'c' } }), [
      { urls: ['turn:t:3478'], username: 'u', credential: 'c' },
    ]);
  });

  it('hands the relay to clients when they join', async () => {
    const { impl } = fakeFetch([{ body: CLOUDFLARE_BODY }]);
    const config = loadConfig({ PORT: '0', HOST: '127.0.0.1', CLOUDFLARE_TURN_KEY_ID: 'k', CLOUDFLARE_TURN_API_TOKEN: 't' });
    const server = createEarshotServer(config, { log: quiet, fetchImpl: impl });
    await server.listen(0, '127.0.0.1');
    try {
      const servers = server.rooms.iceServersFor('peer-aaaa');
      assert.ok(servers.some((s) => s.username === 'u1' && s.urls.includes('turn:turn.cloudflare.com:3478?transport=udp')));
      assert.ok(servers.some((s) => s.urls.includes('stun:stun.l.google.com:19302')));
    } finally {
      await server.close();
    }
  });
});
