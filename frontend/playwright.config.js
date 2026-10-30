import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './browser-tests', workers: 1, reporter: 'list', timeout: 30000,
  use: { baseURL: process.env.FRONTEND_TEST_ORIGIN || 'http://127.0.0.1:5173', headless: true, trace: 'off', screenshot: 'off', video: 'off' },
  outputDir: '../target/frontend-browser-results',
});
