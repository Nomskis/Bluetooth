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
