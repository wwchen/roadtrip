import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

// In dev, /api, /auth, and the retained legacy static assets (/web, /data) are
// proxied to the running Ktor backend so HMR works against real data. Override
// the target with VITE_BACKEND_ORIGIN when the backend runs elsewhere.
const BACKEND_ORIGIN = process.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8765';
const proxy = Object.fromEntries(
  ['/api', '/auth', '/web', '/data'].map((path) => [
    path,
    { target: BACKEND_ORIGIN, changeOrigin: true },
  ]),
);

export default defineConfig({
  root: here('.'),
  plugins: [react()],
  resolve: {
    alias: {
      '@': here('./src'),
      '@ui': here('./src/ui'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      // Multi-page build: one entry per current URL so the Ktor routes and
      // deep-links keep working unchanged.
      input: {
        map: here('./index.html'),
        availability: here('./availability.html'),
        watches: here('./watches.html'),
      },
    },
  },
  server: {
    port: 5173,
    proxy,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    // Component styles are validated by the color-token checker, not by the
    // test runner; skip CSS processing to keep tests fast.
    css: false,
  },
});
