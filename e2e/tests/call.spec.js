import { expect, test } from '@playwright/test';

/** Opens a fresh browser context (own storage = own peerId) and joins a room. */
async function joinAs(browser, room, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('pageerror', (err) => console.error(`[${name}] page error:`, err));
  await page.goto(`/r/${room}`);
  await page.fill('#name-input', name);
  await page.click('#join-button');
  await expect(page.locator('#call')).toBeVisible();
  return { context, page };
}

/** True once the remote <video> is actually rendering frames. */
async function expectRemoteVideo(page) {
  await expect
    .poll(() => page.evaluate(() => document.getElementById('remote-video').videoWidth), { timeout: 20_000 })
    .toBeGreaterThan(0);
  await expect(page.locator('#call-overlay')).toBeHidden();
}

function uniqueRoom(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
}

test('two browsers see and hear each other', async ({ browser }) => {
  const room = uniqueRoom('e2e');
  const a = await joinAs(browser, room, 'Alice');
  await expect(a.page.locator('#call-status')).toHaveText(/Waiting/);

  const b = await joinAs(browser, room, 'Bob');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);
  await expect(a.page.locator('#peer-name')).toHaveText('Bob');
  await expect(b.page.locator('#peer-name')).toHaveText('Alice');

  // Remote audio is flowing too.
  await expect
    .poll(
      () =>
        a.page.evaluate(() => {
          const stream = document.getElementById('remote-video').srcObject;
          return stream ? stream.getAudioTracks().filter((t) => t.readyState === 'live').length : 0;
        }),
      { timeout: 10_000 },
    )
    .toBe(1);

  // Mute state is shown on the other side.
  await b.page.click('#toggle-mic');
  await expect(a.page.locator('#peer-badges')).toContainText('Muted');
  await b.page.click('#toggle-mic');
  await expect(a.page.locator('#peer-badges')).not.toContainText('Muted');

  // Leaving puts the other side back to waiting.
  await b.page.click('#hang-up');
  await expect(a.page.locator('#call-status')).toHaveText(/Waiting/);

  await a.context.close();
  await b.context.close();
});

test('a third person is turned away', async ({ browser }) => {
  const room = uniqueRoom('full');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  const c = await joinAs(browser, room, 'C');
  await expect(c.page.locator('#toast')).toContainText('two people');
  for (const p of [a, b, c]) await p.context.close();
});

test('reloading a tab resumes the call', async ({ browser }) => {
  const room = uniqueRoom('reload');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);

  // Reload B: same tab keeps its peerId in sessionStorage, so the server
  // resumes its slot instead of treating it as a stranger.
  await b.page.reload();
  await b.page.click('#join-button');
  await expectRemoteVideo(b.page);
  await expectRemoteVideo(a.page);

  // Reload A too (A was the first to join, so it answers rather than offers).
  await a.page.reload();
  await a.page.click('#join-button');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);

  await a.context.close();
  await b.context.close();
});

test('a dropped server connection does not interrupt the call', async ({ browser }) => {
  const room = uniqueRoom('drop');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);

  await b.page.evaluate(() => window.earshot.signaling.simulateNetworkDrop());

  // B reconnects to the server and resumes its slot; media never stopped.
  await expect.poll(() => b.page.evaluate(() => window.earshot.engine.status)).toBe('connected');
  await a.page.waitForTimeout(500);
  await expect(a.page.locator('#call-overlay')).toBeHidden();
  await expect(a.page.locator('#peer-name')).toHaveText('B');

  // And signaling still works after the resume.
  await b.page.click('#toggle-camera');
  await expect(a.page.locator('#peer-badges')).toContainText('Camera off');

  await a.context.close();
  await b.context.close();
});

test('someone without a camera still sees the other side', async ({ browser }) => {
  const room = uniqueRoom('nocam');
  const withCamera = await joinAs(browser, room, 'Cam');

  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`/r/${room}`);
  await page.fill('#name-input', 'NoCam');
  await page.uncheck('#video-input');
  await page.click('#join-button');

  // The camera-less side made the offer, and still receives video.
  await expectRemoteVideo(page);
  await expect(withCamera.page.locator('#call-overlay')).toBeHidden();

  await withCamera.context.close();
  await context.close();
});

test('both sides send 10 ms audio packets for lower delay', async ({ browser }) => {
  const room = uniqueRoom('ptime');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);

  const packetsPerSecond = (page) =>
    page.evaluate(async () => {
      const sent = async () => {
        let n = 0;
        (await window.earshot.engine.getStats())?.forEach((s) => {
          if (s.type === 'outbound-rtp' && s.kind === 'audio') n = s.packetsSent;
        });
        return n;
      };
      const before = await sent();
      await new Promise((r) => setTimeout(r, 2000));
      return ((await sent()) - before) / 2;
    });

  // 20 ms packets would be 50 per second.
  for (const side of [a, b]) {
    const rate = await packetsPerSecond(side.page);
    expect(rate).toBeGreaterThan(85);
    expect(rate).toBeLessThan(115);
  }

  await a.context.close();
  await b.context.close();
});

test('both sides negotiate redundant audio (RED) so lost packets are repaired instantly', async ({ browser }) => {
  const room = uniqueRoom('red');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);
  for (const side of [a, b]) {
    const codec = await side.page.evaluate(() => window.earshot.engine.negotiated.audioCodec);
    expect(codec).toBe('red');
  }
  await a.context.close();
  await b.context.close();
});

test('both sides ask for HD voice and encode at that bitrate', async ({ browser }) => {
  const room = uniqueRoom('hdvoice');
  const a = await joinAs(browser, room, 'A');
  const b = await joinAs(browser, room, 'B');
  await expectRemoteVideo(a.page);
  await expectRemoteVideo(b.page);
  for (const side of [a, b]) {
    const negotiated = await side.page.evaluate(() => window.earshot.engine.negotiated);
    expect(negotiated.opusBitrate).toBe(48000);
    // The encoder's own target, where the browser reports it.
    const target = await side.page.evaluate(async () => {
      let t = null;
      (await window.earshot.engine.getStats())?.forEach((s) => {
        if (s.type === 'outbound-rtp' && s.kind === 'audio' && s.targetBitrate) t = s.targetBitrate;
      });
      return t;
    });
    if (target !== null) expect(target).toBeGreaterThanOrEqual(40000);
  }
  await a.context.close();
  await b.context.close();
});
