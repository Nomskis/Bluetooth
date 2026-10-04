import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import WebSocket from 'ws';
import { loadConfig } from '../src/config.js';
import { createEarshotServer } from '../src/server.js';

const silentLog = { error() {}, warn() {}, log() {} };

/** A tiny test client that records messages and lets tests await specific ones. */
class TestClient {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.messages = [];
    this.waiters = [];
    this.ws.on('message', (data) => {
      const msg = JSON.parse(data.toString());
      this.messages.push(msg);
      this.waiters = this.waiters.filter((w) => {
        if (w.match(msg)) {
          w.resolve(msg);
          return false;
        }
        return true;
      });
    });
  }
  open() {
    return new Promise((resolve, reject) => {
      this.ws.once('open', resolve);
      this.ws.once('error', reject);
    });
  }
  send(msg) {
    this.ws.send(JSON.stringify(msg));
  }
  next(type, timeoutMs = 2000) {
    const found = this.messages.find((m) => m.type === type && !m.__seen);
    if (found) {
      found.__seen = true;
      return Promise.resolve(found);
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${type}`)), timeoutMs);
      this.waiters.push({
        match: (m) => m.type === type,
        resolve: (m) => {
          clearTimeout(timer);
          m.__seen = true;
          resolve(m);
        },
      });
    });
  }
  close() {
    this.ws.close();
  }
}

describe('earshot server', () => {
  let server;
  let baseUrl;
  let wsUrl;
  let webRoot;

  before(async () => {
    webRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'earshot-web-'));
    fs.writeFileSync(path.join(webRoot, 'index.html'), '<!doctype html><title>Earshot</title>');
    fs.mkdirSync(path.join(webRoot, 'js'));
    fs.writeFileSync(path.join(webRoot, 'js', 'app.js'), 'console.log(1)');
    // The real one, which Android checks before it opens room links in the app.
    fs.mkdirSync(path.join(webRoot, '.well-known'));
    fs.copyFileSync(new URL('../../web/.well-known/assetlinks.json', import.meta.url), path.join(webRoot, '.well-known', 'assetlinks.json'));
    fs.writeFileSync(path.join(os.tmpdir(), 'earshot-secret.txt'), 'secret');

    const config = { ...loadConfig({}), webRoot, reconnectGraceMs: 200, heartbeatMs: 60_000 };
    server = createEarshotServer(config, { log: silentLog });
    const address = await server.listen(0, '127.0.0.1');
    baseUrl = `http://127.0.0.1:${address.port}`;
    wsUrl = `ws://127.0.0.1:${address.port}/ws`;
  });

  after(async () => {
    await server.close();
    fs.rmSync(webRoot, { recursive: true, force: true });
  });

  it('answers health checks', async () => {
    const res = await fetch(`${baseUrl}/healthz`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'ok', relay: false });
  });

  it('serves the web client for / and room links', async () => {
    for (const p of ['/', '/r/blue-otter-42']) {
      const res = await fetch(baseUrl + p);
      assert.equal(res.status, 200, p);
      assert.match(res.headers.get('content-type'), /text\/html/);
      assert.ok(res.headers.get('content-security-policy'));
      assert.match(await res.text(), /Earshot/);
    }
    const js = await fetch(`${baseUrl}/js/app.js`);
    assert.match(js.headers.get('content-type'), /javascript/);
  });

  it('opens the web client for a mangled room link instead of "Not found"', async () => {
    for (const p of ['//r/blue-otter-42', '/blue-otter-42', '/healthz/r/blue-otter-42', '/r/blue-otter-42/extra']) {
      const res = await fetch(baseUrl + p);
      assert.equal(res.status, 200, p);
      assert.match(res.headers.get('content-type'), /text\/html/, p);
    }
    // A file that isn't there is still missing.
    assert.equal((await fetch(`${baseUrl}/js/nothing-here.js`)).status, 404);
  });

  it('lets Android open room links in the app', async () => {
    const res = await fetch(`${baseUrl}/.well-known/assetlinks.json`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /application\/json/);
    const [statement] = await res.json();
    assert.deepEqual(statement.relation, ['delegate_permission/common.handle_all_urls']);
    assert.equal(statement.target.package_name, 'io.github.nomskis.earshot.debug');
  });

  it('does not serve files outside the web root', async () => {
    // Send raw paths: fetch() would normalize the dot segments away client-side.
    const rawGet = (p) =>
      new Promise((resolve, reject) => {
        http.get(`${baseUrl}${p}`, (res) => {
          res.resume();
          resolve(res.statusCode);
        }).on('error', reject);
      });
    for (const p of [
      '/../earshot-secret.txt',
      '/%2e%2e/earshot-secret.txt',
      '/..%2fearshot-secret.txt',
      '/js/..%2f..%2fearshot-secret.txt',
      '/%2e%2e%2fearshot-secret.txt',
    ]) {
      assert.equal(await rawGet(p), 404, p);
    }
  });

  it('connects two peers and relays signals', async () => {
    const a = new TestClient(wsUrl);
    const b = new TestClient(wsUrl);
    await Promise.all([a.open(), b.open()]);

    a.send({ type: 'join', room: 'Integration-Room', peerId: 'peer-aaaa', name: 'A' });
    const joinedA = await a.next('joined');
    assert.equal(joinedA.room, 'integration-room');
    assert.ok(joinedA.iceServers.length > 0);

    b.send({ type: 'join', room: 'integration-room', peerId: 'peer-bbbb', name: 'B' });
    const joinedB = await b.next('joined');
    assert.equal(joinedB.peers[0].peerId, 'peer-aaaa');
    assert.equal((await a.next('peer-joined')).peer.peerId, 'peer-bbbb');

    b.send({ type: 'signal', to: 'peer-aaaa', data: { kind: 'offer', session: 's1', sdp: 'v=0' } });
    const relayed = await a.next('signal');
    assert.equal(relayed.from, 'peer-bbbb');
    assert.equal(relayed.data.sdp, 'v=0');

    a.send({ type: 'ping' });
    await a.next('pong');

    b.send({ type: 'leave' });
    assert.equal((await a.next('peer-left')).reason, 'left');
    a.close();
    b.close();
  });

  it('keeps the slot when a socket drops and resumes on reconnect', async () => {
    const a = new TestClient(wsUrl);
    const b = new TestClient(wsUrl);
    await Promise.all([a.open(), b.open()]);
    a.send({ type: 'join', room: 'resume-room', peerId: 'peer-aaaa' });
    await a.next('joined');
    b.send({ type: 'join', room: 'resume-room', peerId: 'peer-bbbb' });
    await b.next('joined');

    b.ws.terminate();
    await new Promise((r) => setTimeout(r, 50));
    a.send({ type: 'signal', to: 'peer-bbbb', data: { kind: 'request-offer', session: 's1' } });

    const b2 = new TestClient(wsUrl);
    await b2.open();
    b2.send({ type: 'join', room: 'resume-room', peerId: 'peer-bbbb' });
    const joined = await b2.next('joined');
    assert.equal(joined.resumed, true);
    assert.equal((await b2.next('signal')).data.kind, 'request-offer');

    await new Promise((r) => setTimeout(r, 300));
    assert.equal(a.messages.filter((m) => m.type === 'peer-left').length, 0);
    a.close();
    b2.close();
  });

  it('reports bad messages without dropping the connection', async () => {
    const a = new TestClient(wsUrl);
    await a.open();
    a.ws.send('{oops');
    assert.equal((await a.next('error')).code, 'bad-request');
    a.send({ type: 'join', room: 'x', peerId: 'peer-aaaa' });
    assert.equal((await a.next('error')).code, 'bad-room');
    a.send({ type: 'join', room: 'still-works', peerId: 'peer-aaaa' });
    await a.next('joined');
    a.close();
  });

  it('rings a phone that is only listening, and passes the answer back', async () => {
    const phone = new TestClient(wsUrl);
    const caller = new TestClient(wsUrl);
    await Promise.all([phone.open(), caller.open()]);
    phone.send({ type: 'listen', inbox: 'sam-secret-inbox-key-0001' });
    const { address } = await phone.next('listening');

    // The caller waits in a room, then rings.
    caller.send({ type: 'join', room: 'ring-room', peerId: 'peer-cccc', name: 'Salma' });
    await caller.next('joined');
    caller.send({ type: 'ring', to: address, ringId: 'ring-0001', room: 'ring-room', name: 'Salma', video: true });
    assert.equal((await caller.next('ring-status')).status, 'ringing');
    const incoming = await phone.next('incoming');
    assert.equal(incoming.room, 'ring-room');
    assert.equal(incoming.from.name, 'Salma');
    assert.equal(incoming.video, true);

    phone.send({ type: 'ring-answer', ringId: 'ring-0001', accepted: true });
    assert.equal((await caller.next('ring-answered')).accepted, true);
    phone.close();
    caller.close();
  });

  it('cancels the ring when the caller drops before an answer', async () => {
    const phone = new TestClient(wsUrl);
    const caller = new TestClient(wsUrl);
    await Promise.all([phone.open(), caller.open()]);
    phone.send({ type: 'listen', inbox: 'sam-secret-inbox-key-0002' });
    const { address } = await phone.next('listening');
    caller.send({ type: 'ring', to: address, ringId: 'ring-0002', room: 'ring-room', name: 'Salma' });
    await phone.next('incoming');
    caller.ws.terminate();
    assert.equal((await phone.next('ring-cancelled')).reason, 'cancelled');
    phone.close();
  });

  it('only upgrades WebSockets on /ws', async () => {
    const ws = new WebSocket(wsUrl.replace('/ws', '/other'));
    const err = await new Promise((resolve) => ws.once('error', resolve));
    assert.match(err.message, /404/);
  });
});
