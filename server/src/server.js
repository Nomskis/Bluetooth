import http from 'node:http';
import { WebSocketServer } from 'ws';
import { buildIceServers } from './ice.js';
import { ErrorCode, ProtocolError, parseClientMessage } from './protocol.js';
import { Inbox } from './inbox.js';
import { RoomManager } from './rooms.js';
import { createStaticHandler } from './static.js';
import { createTurnService } from './turn-service.js';

const MAX_MESSAGE_BYTES = 64 * 1024;
// Token bucket per connection: bursts of ICE candidates are fine, floods are not.
const RATE_BUCKET_SIZE = 200;
const RATE_REFILL_PER_SECOND = 50;

/**
 * Creates the HTTP + WebSocket server. Call `listen()` to start it.
 *
 *   GET  /healthz   liveness probe
 *   GET  /, /r/:id  browser client
 *   WS   /ws        signaling (see docs/protocol.md)
 */
export function createEarshotServer(config, { log = console, fetchImpl = globalThis.fetch } = {}) {
  const turnService = createTurnService(config.ice, { fetchImpl, log });
  const rooms = new RoomManager({
    maxPeersPerRoom: config.maxPeersPerRoom,
    reconnectGraceMs: config.reconnectGraceMs,
    iceServersFor: (peerId) => [...buildIceServers(config.ice, peerId), ...(turnService?.current() ?? [])],
  });
  const inbox = new Inbox({ ringTimeoutMs: config.ringTimeoutMs });
  const serveStatic = createStaticHandler(config.webRoot);

  const httpServer = http.createServer(async (req, res) => {
    const { pathname } = new URL(req.url ?? '/', 'http://localhost');
    if (pathname === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      // Whether calls can fall back to a relay, so the apps can say when they can't.
      const relay = config.ice.turnUrls.length > 0 || (turnService?.current().length ?? 0) > 0;
      res.end(JSON.stringify({ status: 'ok', relay }));
      return;
    }
    try {
      if (await serveStatic(req, res, pathname)) return;
    } catch (err) {
      log.error('static file error', err);
    }
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });

  httpServer.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url ?? '/', 'http://localhost');
    if (pathname !== '/ws') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (ws) => {
    const conn = {
      member: null,
      send(msg) {
        if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
      },
      close(code, reason) {
        ws.close(code, reason);
      },
    };
    let tokens = RATE_BUCKET_SIZE;
    let lastRefill = Date.now();
    ws.isAlive = true;

    ws.on('pong', () => {
      ws.isAlive = true;
    });

    ws.on('message', (data, isBinary) => {
      ws.isAlive = true;
      const now = Date.now();
      tokens = Math.min(RATE_BUCKET_SIZE, tokens + ((now - lastRefill) / 1000) * RATE_REFILL_PER_SECOND);
      lastRefill = now;
      if (tokens < 1) {
        conn.send({ type: 'error', code: ErrorCode.RATE_LIMITED, message: 'Slow down.' });
        return;
      }
      tokens -= 1;

      if (isBinary) {
        conn.send({ type: 'error', code: ErrorCode.BAD_REQUEST, message: 'Binary frames are not supported.' });
        return;
      }

      let msg;
      try {
        msg = parseClientMessage(data.toString('utf8'));
      } catch (err) {
        if (err instanceof ProtocolError) {
          conn.send({ type: 'error', code: err.code, message: err.message });
          return;
        }
        throw err;
      }

      switch (msg.type) {
        case 'join':
          rooms.join(conn, msg);
          break;
        case 'signal':
          rooms.signal(conn, msg);
          break;
        case 'leave':
          rooms.leave(conn);
          break;
        case 'ping':
          conn.send({ type: 'pong' });
          break;
        case 'listen':
          inbox.listen(conn, msg.inbox);
          break;
        case 'ring':
          inbox.ring(conn, msg);
          break;
        case 'ring-cancel':
          inbox.cancel(conn, msg);
          break;
        case 'ring-answer':
          inbox.answer(conn, msg);
          break;
        case 'message':
          inbox.message(conn, msg);
          break;
        case 'message-ack':
          inbox.messageAck(conn, msg);
          break;
        case 'message-read':
          inbox.messageRead(conn, msg);
          break;
      }
    });

    ws.on('close', () => {
      inbox.disconnected(conn);
      rooms.disconnected(conn);
    });
    ws.on('error', (err) => log.warn('websocket error', err.message));
  });

  // Detect sockets that died without a close frame (common on mobile networks).
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, config.heartbeatMs);
  heartbeat.unref();

  return {
    httpServer,
    rooms,
    turnService,
    inbox,
    async listen(port = config.port, host = config.host) {
      // Credentials first, so the first caller after a cold start gets a relay too.
      await turnService?.start();
      return new Promise((resolve, reject) => {
        httpServer.once('error', reject);
        httpServer.listen(port, host, () => {
          httpServer.off('error', reject);
          resolve(httpServer.address());
        });
      });
    },
    async close() {
      clearInterval(heartbeat);
      turnService?.stop();
      for (const ws of wss.clients) ws.terminate();
      rooms.dispose();
      inbox.dispose();
      await new Promise((resolve) => wss.close(() => resolve()));
      await new Promise((resolve) => httpServer.close(() => resolve()));
    },
  };
}
