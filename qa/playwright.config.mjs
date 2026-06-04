import { defineConfig, devices } from '@playwright/test';

// Local-only smoke. Real browser (Chromium) so MapLibre's WebGL context
// initializes — headless Chrome bundled with Playwright has SwiftShader,
// which works fine for MapLibre at this scale (tested at z=12 over Banff).
export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.mjs$/,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.QA_BASE_URL || 'http://127.0.0.1:8765',
    trace: 'on-first-retry',
    viewport: { width: 1280, height: 800 },
  },
  reporter: [['list']],
  retries: 0,
  workers: 1,
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
