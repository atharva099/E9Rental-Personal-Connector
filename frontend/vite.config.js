import { defineConfig, createLogger } from 'vite';
// Server-side configuration only. Never put tokens in VITE_* variables.
const target = process.env.BACKEND_ORIGIN || 'http://127.0.0.1:8080';
if (!/^http:\/\/127\.0\.0\.1:\d{4,5}$/.test(target)) throw new Error('Backend must be a loopback HTTP origin');
const proxy = { '/api': { target }, '/health': { target } };
const logger = createLogger();
const logError = logger.error.bind(logger);
logger.error = (message, options) => logError(String(message).replace(/\/api\/[^\s]*/g, '/api/[redacted]'), options);
export default defineConfig({
  customLogger: logger,
  server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false, proxy },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, cors: false, proxy },
  test: { environment: 'jsdom', setupFiles: './src/test-setup.js', include: ['src/**/*.test.{js,jsx}'] },
});
