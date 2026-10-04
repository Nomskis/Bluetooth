import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { ProtocolError, normalizeRoom, parseClientMessage } from '../src/protocol.js';

const fixturesDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../protocol/fixtures');

function expectError(raw, code) {
  assert.throws(
    () => parseClientMessage(typeof raw === 'string' ? raw : JSON.stringify(raw)),
    (err) => err instanceof ProtocolError && err.code === code,
  );
}

describe('protocol', () => {
  it('accepts every shared client fixture', () => {
    const dir = path.join(fixturesDir, 'client');
    const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json'));
    assert.ok(files.length >= 5);
    for (const file of files) {
      const raw = fs.readFileSync(path.join(dir, file), 'utf8');
      const msg = parseClientMessage(raw);
      assert.equal(msg.type, JSON.parse(raw).type, file);
    }
  });

  it('passes on a ring that may connect ahead of the answer, and only a real true', () => {
    const raw = fs.readFileSync(path.join(fixturesDir, 'client', 'ring.json'), 'utf8');
    assert.equal(parseClientMessage(raw).preconnect, true);
    const ring = { ...JSON.parse(raw), preconnect: 'yes' };
    assert.equal(parseClientMessage(JSON.stringify(ring)).preconnect, false);
    delete ring.preconnect;
    assert.equal(parseClientMessage(JSON.stringify(ring)).preconnect, false);
  });

  it('normalizes room codes', () => {
    assert.equal(normalizeRoom('  Blue-Otter-42 '), 'blue-otter-42');
    for (const bad of ['', 'ab', '-abc', 'abc-', 'has space', 'ä-room', 'x'.repeat(65)]) {
      assert.throws(() => normalizeRoom(bad), ProtocolError, bad);
    }
  });

  it('cleans names and client info', () => {
    const msg = parseClientMessage(
      JSON.stringify({
        type: 'join',
        room: 'gym',
        peerId: 'abcdefgh',
        name: '  Sal\u0000ma \n  ',
        client: { platform: 'web', capabilities: ['a', 3, 'b'] },
      }),
    );
    assert.equal(msg.name, 'Salma');
    assert.deepEqual(msg.client, { platform: 'web', version: '', capabilities: ['a', 'b'] });
  });

  it('rejects malformed messages', () => {
    expectError('not json', 'bad-request');
    expectError([], 'bad-request');
    expectError({ type: 'nope' }, 'bad-request');
    expectError({ type: 'join', room: 'gym', peerId: 'short' }, 'bad-request');
    expectError({ type: 'join', room: 'a', peerId: 'abcdefgh' }, 'bad-room');
    expectError({ type: 'signal', to: 'abcdefgh' }, 'bad-request');
    expectError({ type: 'signal', to: 'abcdefgh', data: { sdp: 'x' } }, 'bad-request');
  });
});
