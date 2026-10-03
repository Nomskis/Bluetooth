import { defineConfig } from '@playwright/test';

const PORT = 8787;

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'list' : 'line',
  use: {
    baseURL: `http://localhost:${PORT}`,
    launchOptions: {
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
    },
    permissions: ['camera', 'microphone'],
  },
  webServer: {
    command: 'node ../server/src/index.js',
    url: `http://localhost:${PORT}/healthz`,
    env: { PORT: String(PORT), HOST: '127.0.0.1', RECONNECT_GRACE_MS: '3000' },
    reuseExistingServer: false,
    stdout: 'ignore',
  },
});
