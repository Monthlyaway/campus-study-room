import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';

// Playwright 1.64.1's automatic AI/ARIA failure snapshot includes password input values.
// Disable that text snapshot, retaining masked screenshots and ordinary assertion errors.
// The pinned-version report-safety probe verifies the actual generated failure artifacts.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

export default defineConfig({
  testDir: '.',
  globalSetup: './global-setup.mjs',
  testMatch: '**/*.spec.mjs',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  outputDir: '../../output/browser/results',
  reporter: [
    [fileURLToPath(new URL('./privacy-reporter.mjs', import.meta.url))],
    ['line'],
    ['junit', { outputFile: '../../output/browser/junit.xml' }],
    ['json', { outputFile: '../../output/browser/results.json' }],
    ['html', { outputFolder: '../../output/browser/html', open: 'never' }],
  ],
  use: {
    baseURL: process.env.STUDYROOM_E2E_BASE_URL || 'http://127.0.0.1:19282',
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    browserName: 'chromium',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    // Auth request bodies contain real fixture credentials. Never upload traces/HAR.
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
  },
});
