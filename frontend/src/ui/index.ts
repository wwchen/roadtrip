// ---------------------------------------------------------------------------
// LDS adapter layer (`@ui`).
//
// LDS (`matthewlew/lds`) is the React component library that replaces the
// legacy `web/design-system/*` primitives. It is vendored into
// `frontend/vendor/` for now (npm cannot install a single workspace member of a
// git monorepo) and consumed through the `vendor/*` npm workspaces.
//
// Every migrated page imports its primitives from `@ui` rather than reaching
// for `@lew/lds-react` directly. That keeps the swap to a published
// `@lew/lds-react` registry dependency a change to this file's specifier and
// `package.json` only — no call-site churn.
//
// Styles are NOT re-exported here; they are a side effect, loaded once per page
// entry via `@ui/styles.css` (see that file for the theme contract).
// ---------------------------------------------------------------------------

export * from '@lew/lds-react';
