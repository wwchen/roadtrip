import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { runtimeServedAssets } from './vite/runtime-served-assets';

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

// The retained token bridge. `web/design-system/tokens.js` stays the single
// source of truth for `--rt-*` colors (it holds the fallback table that
// scripts/check-color-tokens.mjs verifies against tokens.css), so the React app
// imports that module rather than owning a TS copy. Typed by
// src/types/tokens.d.ts; dropped in Phase 5 when the bridge is reconciled with
// LDS's `--c-*` names.
const LEGACY_WEB_DIR = here('../web');

export default defineConfig({
  root: here('.'),
  plugins: [react(), runtimeServedAssets()],
  resolve: {
    alias: {
      '@': here('./src'),
      '@ui': here('./src/ui'),
      '@tokens': `${LEGACY_WEB_DIR}/design-system/tokens.js`,
      // Transition-only: lets parity tests run a port against the original it
      // was ported from. Typed by src/types/legacy.d.ts; removed in Phase 5.
      '@legacy/core': `${LEGACY_WEB_DIR}/core.js`,
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
      output: {
        // MapLibre is ~800kB minified and changes only when we bump it, while the
        // map page's own code changes constantly. In one chunk together, every app
        // deploy would invalidate the whole 800kB for every returning user.
        //
        // It still trips Rollup's 500kB chunk-size warning, and that is expected:
        // the warning names `maplibre` rather than an app chunk, which is the
        // point. A NEW warning naming something else is worth reading.
        manualChunks: { maplibre: ['maplibre-gl'] },
      },
    },
  },
  server: {
    port: 5173,
    proxy,
    // `@tokens` resolves outside the Vite root, so the dev server has to be
    // allowed to serve it.
    fs: { allow: [here('.'), LEGACY_WEB_DIR] },
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
