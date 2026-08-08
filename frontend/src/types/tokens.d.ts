// Types for the retained token bridge, imported as `@tokens`.
//
// `web/design-system/tokens.js` is deliberately NOT ported to TypeScript. It
// carries the fallback hex table that `scripts/check-color-tokens.mjs` verifies
// against `tokens.css` key by key; a TS copy would be a second source of truth
// for those colors and would itself trip the raw-hex check. The React app
// therefore imports the one real module through a Vite alias (see
// `vite.config.ts`), and this file is its hand-written declaration.
//
// Phase 5 reconciles the `--rt-*` bridge with LDS's `--c-*` token names; until
// then MapLibre paint and Chart.js keep resolving colors here.
declare module '@tokens' {
  /**
   * Resolve a design token to a concrete color string, e.g.
   * `token('--rt-layer-np')`. Reads the live computed style of the document
   * root, memoized after first read; falls back to a baked table before the
   * stylesheet loads (early boot, jsdom without CSS).
   */
  export function token(name: string): string;

  /** Drop memoized values. Call after swapping themes at runtime. */
  export function resetTokenCache(): void;

  /** Search-result kind → pin color token name. */
  export const KIND_TOKEN: Readonly<Record<'PLACE' | 'ADDR' | 'CG' | 'SC' | 'NP' | 'SP' | 'PF', string>>;

  /** Number of categorical chart series before the ramp wraps. */
  export const SERIES_COUNT: number;

  /** Categorical chart color for series `i` (wraps past `SERIES_COUNT`). */
  export function seriesColor(i: number): string;
}
