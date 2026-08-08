// Types for the legacy vanilla modules that migrated code compares itself
// against, imported through the `@legacy/*` aliases (see `vite.config.ts`).
//
// TRANSITION ONLY. Nothing in `src/` may import these at runtime — they exist so
// parity tests can run a port and its original side by side over the same
// fixtures, which is the strongest available check that a port is
// behavior-faithful. Phase 5 deletes `web/`, these aliases, and this file.
declare module '@legacy/core' {
  export function flattenHydratedPoi(f: unknown): {
    properties: Record<string, unknown>;
    [key: string]: unknown;
  };
}
