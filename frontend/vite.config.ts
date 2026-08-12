import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

// In dev, /api, /auth and /data are proxied to the running Ktor backend so HMR
// works against real data. `/data` is the GeoJSON overlays (state lines), which
// are repo data files the backend serves rather than bundle inputs. `/web` was
// here too until the last three files under it moved into this tree. Override the
// target with VITE_BACKEND_ORIGIN when the backend runs elsewhere.
const BACKEND_ORIGIN = process.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8765';
const MAPLIBRE_MODULE_PATH = '/maplibre-gl/';
const MAPLIBRE_CHUNK_NAME = 'maplibre';
/** The CommonJS react-dom entry `@lew-ds/lds-react` reaches for; see `optimizeDeps`. */
const REACT_DOM_SERVER_ENTRY = 'react-dom/server';
const proxy = Object.fromEntries(
  ['/api', '/auth', '/data'].map((path) => [
    path,
    { target: BACKEND_ORIGIN, changeOrigin: true },
  ]),
);

export default defineConfig({
  root: here('.'),
  plugins: [react()],
  optimizeDeps: {
    // `@lew-ds/lds-react` imports `renderToStaticMarkup`, which react-dom 19
    // ships as CommonJS. Without this the dev server serves the raw CJS and the
    // browser rejects it, so nothing mounts. Dev-only — the production build
    // interops it correctly.
    include: [REACT_DOM_SERVER_ENTRY],
  },
  resolve: {
    alias: {
      '@': here('./src'),
      '@ui': here('./src/ui'),
      // The `--rt-*` token bridge. Aliased rather than imported by path because it
      // is not ordinary source: `src/tokens/tokens.css` beside it is the single
      // source of truth for colour and this module is the only sanctioned way to
      // read one from JS, so the specifier says so at every call site. It used to
      // point outside the Vite root, at `web/design-system/tokens.js`, which is why
      // it needed a hand-written `.d.ts`; it is a plain tsconfig `paths` entry now.
      '@tokens': here('./src/tokens/tokens.ts'),
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
        manualChunks: (id) => id.includes(MAPLIBRE_MODULE_PATH) ? MAPLIBRE_CHUNK_NAME : undefined,
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
