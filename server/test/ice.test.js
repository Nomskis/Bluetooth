import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { describe, it } from 'node:test';
import { buildIceServers } from '../src/ice.js';

const base = {
  stunUrls: ['stun:stun.example.test:3478'],
  turnUrls: [],
  turnSecret: null,
  turnUsername: null,
  turnCredential: null,
  turnTtlSeconds: 3600,
};

describe('buildIceServers', () => {
  it('returns STUN only by default', () => {
    assert.deepEqual(buildIceServers(base, 'peer-aaaa'), [{ urls: ['stun:stun.example.test:3478'] }]);
  });

  it('issues time-limited TURN credentials from a shared secret', () => {
    const ice = { ...base, turnUrls: ['turn:turn.example.test:3478'], turnSecret: 's3cret' };
    const servers = buildIceServers(ice, 'peer-aaaa', 1_700_000_000_000);
    const turn = servers[1];
    assert.equal(turn.username, '1700003600:peer-aaaa');
    const expected = crypto.createHmac('sha1', 's3cret').update(turn.username).digest('base64');
    assert.equal(turn.credential, expected);
  });

  it('supports fixed TURN credentials', () => {
    const ice = { ...base, stunUrls: [], turnUrls: ['turn:t'], turnUsername: 'u', turnCredential: 'p' };
    assert.deepEqual(buildIceServers(ice, 'peer-aaaa'), [{ urls: ['turn:t'], username: 'u', credential: 'p' }]);
  });

  it('skips TURN when no credentials are configured', () => {
    const ice = { ...base, turnUrls: ['turn:t'] };
    assert.equal(buildIceServers(ice, 'peer-aaaa').length, 1);
  });
});
