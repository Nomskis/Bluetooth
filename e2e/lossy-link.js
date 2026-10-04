import dgram from 'node:dgram';
import { expect } from '@playwright/test';

/**
 * A bad connection between two test browsers, for the features that only
 * show on one: a UDP relay that both sides' media goes through, and which
 * drops whatever `link.drop` says. Each browser's ICE candidates are
 * rewritten on their way to the server (see joinThrough) to point at the
 * relay, so connectivity checks, DTLS and media all pass through it. STUN,
 * DTLS and RTCP always get through; `drop` decides for each RTP packet,
 * looking at its unencrypted header:
 *
 *   link.drop = ({ from, pt, seq, timestamp }) => boolean
 *
 * A resend of an RTP packet carries the same seq and timestamp, so a rule on
 * those loses it for good, like a network that's down for that moment.
 */
export async function createLossyLink() {
  const bind = () =>
    new Promise((resolve) => {
      const socket = dgram.createSocket('udp4');
      socket.bind(0, '0.0.0.0', () => resolve(socket));
    });
  // standIn.A is where B sends what it thinks goes to A, and the other way round.
  const standIn = { A: await bind(), B: await bind() };
  const real = { A: null, B: null };
  const link = {
    drop: () => false,
    dropped: { A: 0, B: 0 },
    forwarded: { A: 0, B: 0 },
    /** The relay's stand-in for [side], as a candidate the other side can use. */
    rewrite(side, candidate) {
      const parts = candidate.candidate.split(' ');
      // foundation component protocol priority address port typ type ...
      const [, component, protocol, , address, port, , type] = parts;
      if (component !== '1' || protocol.toLowerCase() !== 'udp' || type !== 'host' || !/^\d+\.\d+\.\d+\.\d+$/.test(address)) return null;
      // One network interface per side (machines with several would offer each).
      if (real[side] && real[side].address !== address) return null;
      real[side] = { address, port: Number(port) };
      parts[5] = String(standIn[side].address().port);
      return { ...candidate, candidate: parts.join(' ') };
    },
    close() {
      standIn.A.close();
      standIn.B.close();
    },
  };
  // From A (arriving at B's stand-in) to B, sent from A's stand-in, and vice versa.
  for (const [from, to] of [['A', 'B'], ['B', 'A']]) {
    standIn[to].on('message', (packet) => {
      const target = real[to];
      if (!target) return;
      if (isRtp(packet) && link.drop({ from, ...rtpHeader(packet) })) {
        link.dropped[from]++;
        return;
      }
      link.forwarded[from]++;
      standIn[from].send(packet, target.port, target.address);
    });
  }
  return link;
}

/** RFC 7983: RTP and RTCP start at 128-191; RTCP's packet types are 192-223 in the second byte. */
function isRtp(packet) {
  return packet.length >= 12 && packet[0] >= 128 && packet[0] <= 191 && !(packet[1] >= 192 && packet[1] <= 223);
}

function rtpHeader(packet) {
  return { pt: packet[1] & 0x7f, seq: packet.readUInt16BE(2), timestamp: packet.readUInt32BE(4), ssrc: packet.readUInt32BE(8) };
}

/**
 * Joins [room] as [name] with every candidate this side sends replaced by the
 * link's stand-in (and candidates inside descriptions removed), so the call
 * can only connect through the link. [side] is 'A' or 'B'.
 */
export async function joinThrough(browser, link, side, room, name) {
  const context = await browser.newContext();
  await context.exposeBinding('__earshotRewriteCandidate', (_source, candidate) => link.rewrite(side, candidate));
  await context.addInitScript(() => {
    const send = WebSocket.prototype.send;
    WebSocket.prototype.send = function (text) {
      let msg;
      try {
        msg = JSON.parse(text);
      } catch {
        return send.call(this, text);
      }
      const data = msg?.type === 'signal' ? msg.data : null;
      if (data?.kind === 'candidate') {
        window.__earshotRewriteCandidate(data.candidate).then((rewritten) => {
          if (rewritten) send.call(this, JSON.stringify({ ...msg, data: { ...data, candidate: rewritten } }));
        });
        return undefined;
      }
      if (data?.sdp) {
        const sdp = data.sdp.split('\r\n').filter((l) => !l.startsWith('a=candidate:') && l !== 'a=end-of-candidates').join('\r\n');
        return send.call(this, JSON.stringify({ ...msg, data: { ...data, sdp } }));
      }
      return send.call(this, text);
    };
  });
  const page = await context.newPage();
  page.on('pageerror', (err) => console.error(`[${name}] page error:`, err));
  await page.goto(`/r/${room}`);
  await page.fill('#name-input', name);
  await page.click('#join-button');
  await expect(page.locator('#call')).toBeVisible();
  return { context, page };
}

/** One kind's RTP stats from a page: inbound or outbound, audio or video. */
export function rtpStats(page, type, kind) {
  return page.evaluate(
    async ({ type, kind }) => {
      let found = null;
      (await window.earshot.engine.getStats())?.forEach((s) => {
        if (s.type === type && s.kind === kind) found = { ...s };
      });
      return found;
    },
    { type, kind },
  );
}
