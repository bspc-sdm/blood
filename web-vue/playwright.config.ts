import { defineConfig } from '@playwright/test';

// e2e (CI): বানানো পাতা (npm run build:test) আর অ্যাডমিন (npm run build:admin) — test/serve.mjs দেয়,
// /api/* এর উত্তর test/fixtures থেকে (আসল সার্ভার ছাড়া)। আসল সার্ভারের পুরো যাত্রা: e2e/journeys.mjs।
const PORT = Number(process.env.E2E_MOCK_PORT || 4179);
export default defineConfig({
  testDir: 'e2e',
  testMatch: /.*\.spec\.ts$/,
  timeout: 30_000,
  expect: { timeout: 6_000 },
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    locale: 'bn-BD',
    timezoneId: 'Asia/Dhaka',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'mobile', use: { browserName: 'chromium', viewport: { width: 390, height: 844 } } },
    { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 960 } }, grep: /@desktop/ },
  ],
  webServer: {
    command: `node test/serve.mjs ${PORT}`,
    url: `http://localhost:${PORT}/w/`,
    reuseExistingServer: !process.env.CI,
    env: { FIXTURES_EXTRA: '1' },
  },
});
