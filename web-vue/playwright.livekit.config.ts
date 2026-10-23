import { defineConfig } from '@playwright/test';

// আসল LiveKit-এর সাথে দুই ট্যাবের কল (e2e-livekit): নকল API (test/serve.mjs) + লোকাল livekit-server --dev।
// LIVEKIT_BIN = livekit-server-এর পথ (CI নামিয়ে দেয়); না থাকলে পরীক্ষাগুলো বাদ।
// ব্রাউজার নকল ক্যামেরা/মাইক দেয় (--use-fake-device-for-media-stream), অনুমতি আগেই দেওয়া।
const PORT = Number(process.env.E2E_MOCK_PORT || 4181);
const BIN = process.env.LIVEKIT_BIN;
export default defineConfig({
  testDir: 'e2e-livekit',
  testMatch: /.*\.spec\.ts$/,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-livekit' }]] : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    locale: 'bn-BD',
    timezoneId: 'Asia/Dhaka',
    viewport: { width: 390, height: 844 },
    permissions: ['camera', 'microphone'],
    launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] },
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: [
    { command: `node test/serve.mjs ${PORT}`, url: `http://localhost:${PORT}/w/`, reuseExistingServer: !process.env.CI, env: { FIXTURES_EXTRA: '1' } },
    ...(BIN
      ? [{ command: `${BIN} --dev --bind 127.0.0.1 --node-ip 127.0.0.1`, url: 'http://127.0.0.1:7880/', reuseExistingServer: !process.env.CI, timeout: 30_000 }]
      : []),
  ],
});
