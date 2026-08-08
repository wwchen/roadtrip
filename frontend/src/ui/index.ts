// ---------------------------------------------------------------------------
// LDS adapter layer (`@ui`).
//
// LDS (private repo `matthewlew/lds`) is the target React component library
// that replaces the legacy `web/design-system/*` primitives. Its package name,
// registry, and component API are not yet wired up (see the migration plan's
// "LDS onboarding" prerequisite).
//
// Every migrated page imports its primitives from `@ui` rather than reaching
// for LDS directly, so when LDS lands we swap the re-exports here in ONE place
// and no call-site changes. Until then this module is intentionally empty; the
// first migrated page (watches) will add the shims it needs.
//
// TODO(lds): re-export real LDS components once the package is available.
// ---------------------------------------------------------------------------

export {};
