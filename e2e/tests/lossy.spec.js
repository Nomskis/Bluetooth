import { expect, test } from '@playwright/test';
import { createLossyLink, joinThrough, rtpStats } from '../lossy-link.js';

// Calls over a simulated bad connection (see lossy-link.js). Real host addresses
// in candidates, so the relay knows where each side is.
test.use({
  launchOptions: {
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--disable-features=WebRtcHideLocalIpsWithMdns'],
  },
});

function uniqueRoom(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
}

/** Chrome's payload types for RED and Opus. */
const AUDIO_PAYLOAD_TYPES = new Set([63, 111]);

async function callThrough(browser, link, prefix) {
  const room = uniqueRoom(prefix);
  const a = await joinThrough(browser, link, 'A', room, 'A');
  const b = await joinThrough(browser, link, 'B', room, 'B');
  for (const side of [a, b]) {
    await expect.poll(() => side.page.evaluate(() => window.earshot.engine.status), { timeout: 20_000 }).toBe('connected');
  }
  return { a, b };
}

test('lost voice packets are asked for again and resent', async ({ browser }) => {
  const link = await createLossyLink();
  const { a, b } = await callThrough(browser, link, 'nack');
  for (const side of [a, b]) {
    expect(await side.page.evaluate(() => window.earshot.engine.negotiated.audioNack)).toBe(true);
  }

  // A sixth of B's voice packets go missing on the way to A.
  link.drop = ({ from, pt }) => from === 'B' && AUDIO_PAYLOAD_TYPES.has(pt) && Math.random() < 0.15;
  await expect
    .poll(async () => (await rtpStats(a.page, 'inbound-rtp', 'audio'))?.nackCount ?? 0, { timeout: 10_000 })
    .toBeGreaterThan(5);
  // B hears the requests and sends the packets again.
  await expect
    .poll(async () => (await rtpStats(b.page, 'outbound-rtp', 'audio'))?.retransmittedPacketsSent ?? 0, { timeout: 10_000 })
    .toBeGreaterThan(5);
  expect(link.dropped.B).toBeGreaterThan(0);

  await a.context.close();
  await b.context.close();
  link.close();
});

/** Audio packets [page] sends per second, over two seconds. */
async function audioPacketsPerSecond(page) {
  const before = (await rtpStats(page, 'outbound-rtp', 'audio'))?.packetsSent ?? 0;
  await page.waitForTimeout(2000);
  const after = (await rtpStats(page, 'outbound-rtp', 'audio'))?.packetsSent ?? 0;
  return (after - before) / 2;
}

/**
 * The voice from [from] loses 100 ms out of every second, resends included
 * (the rule is on the RTP timestamp, which a resend keeps): longer than the
 * redundant copies of 10 ms packets can cover.
 */
function dropRunsOfVoiceFrom(link, from) {
  link.drop = (p) => p.from === from && AUDIO_PAYLOAD_TYPES.has(p.pt) && p.timestamp % 48_000 < 4_800;
}

for (const { rough, role } of [
  { rough: 'B', role: 'the answering side asks for an offer' },
  { rough: 'A', role: 'the offering side renegotiates itself' },
]) {
  test(`voice that keeps losing runs gets longer packets, without restarting the connection (${role})`, async ({ browser }) => {
    const link = await createLossyLink();
    const { a, b } = await callThrough(browser, link, 'ptime');
    const sides = { A: a, B: b };
    const sender = sides[rough];
    const receiver = sides[rough === 'A' ? 'B' : 'A'];
    const ufrags = await Promise.all([a, b].map((s) => s.page.evaluate(() => window.earshot.engine.negotiated.iceUfrag)));
    expect(ufrags.every(Boolean)).toBe(true);
    expect(await audioPacketsPerSecond(sender.page)).toBeGreaterThan(85);

    dropRunsOfVoiceFrom(link, rough);
    await expect.poll(() => receiver.page.evaluate(() => window.earshot.engine.packetTimeMs), { timeout: 20_000 }).toBe(20);
    // The sender switches to 20 ms packets: about 50 a second instead of 100.
    link.drop = () => false;
    await expect.poll(() => audioPacketsPerSecond(sender.page), { timeout: 15_000 }).toBeLessThan(65);
    // Renegotiated in place: same ICE credentials, still connected.
    expect(await Promise.all([a, b].map((s) => s.page.evaluate(() => window.earshot.engine.negotiated.iceUfrag)))).toEqual(ufrags);
    for (const side of [a, b]) expect(await side.page.evaluate(() => window.earshot.engine.status)).toBe('connected');

    await a.context.close();
    await b.context.close();
    link.close();
  });
}
