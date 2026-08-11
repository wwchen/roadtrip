# PR #623 Adversarial Self-Review

**PR:** #623 — Finish React migration cleanup and add Storybook
**Branch:** `codex/react-migration-cleanup` → `master`
**Started:** 2026-08-11
**Status:** COMPLETE — APPROVED

## Review objective

Try to disprove that the PR is safe by checking the removed QA coverage, desktop drawer
interaction, Storybook build path, accessibility, documentation accuracy, and test reliability.
Findings are recorded before fixes and retained after resolution.

## Progress

- [x] Inventory the complete `master...HEAD` diff and project rules.
- [x] Audit every removed `window.__rt*` consumer and identify replacement coverage.
- [x] Exercise the rewritten smoke-test assumptions against component and map behavior.
- [x] Review the initial gallery for accessibility, representative coverage, and deploy wiring.
- [x] Verify the Storybook replacement and removal of the production gallery route locally.
- [x] Verify the updated PR head in CI.
- [x] Review desktop drawer/topbar stacking across breakpoints.
- [x] Run focused and full local verification after fixes.
- [x] Update the PR with review fixes and final decision.

## Findings

### H1 — PR conflicts with current `master` (HIGH, FIXED)

GitHub reports `mergeable: CONFLICTING`, `mergeStateStatus: DIRTY`, and no status checks
for the branch. The implementation may be locally green, but the PR cannot merge or
receive a reliable current-base CI verdict in this state.

**Resolution:** Rebased onto `origin/master` at `06f32303a`. The only conflict was the
migration plan: its completed-state rewrite was preserved while adopting `master`'s
React 19.2 decision. Both commits now apply cleanly, and verification was rerun after
`npm ci` from the rebased lockfile.

### M1 — Gallery toggle has no accessible name (MEDIUM, FIXED)

`GalleryPage` supplies LDS `Toggle`'s visible `label`, but LDS renders that text in a
sibling `<span>` rather than in the `<label>` that wraps the checkbox. The gallery's
checkbox therefore has no accessible name. This also makes the catalog demonstrate an
unsafe usage pattern even though production `TriggerSelector` correctly supplies a
matching `aria-label`.

**Resolution:** Add the matching `aria-label`, assert the checkbox by accessible name,
and preserve the LDS-specific rule in the frontend component guide. The custom gallery was later
removed by H2; the corrected usage remains in the Storybook input story.

### H2 — Custom production gallery was unintended scope (HIGH, FIXED)

The user requested replacement coverage and cleanup, not a new customer-deployed component
catalog. Interpreting “build replacement” as authorization for `/gallery` added a Ktor route,
fourth Vite entry, smoke surface, and bespoke catalog without confirming that product scope.

**Resolution:** Remove the `/gallery` route, production entry, page, styles, unit test, and smoke
case. Replace it with Storybook 10's supported React/Vite framework, Docs and Accessibility
addons, and representative stories that import the production `@ui` theme boundary. Storybook
is development-only and built independently with `npm run build-storybook`.

### L1 — Drawer source comment describes the wrong desktop edge (LOW, FIXED)

`Drawer.tsx` calls the desktop drawer a right-hand panel, while the shared CSS anchors
it to the left. The runtime is correct, but the stale comment makes the stacking review
needlessly ambiguous.

**Resolution:** Correct the source comment to say left-hand panel.

### M2 — Route smoke asserts visibility on an intentionally hidden LDS input (MEDIUM, FIXED)

The rebased-head CI run reached the live browser suite and failed only the route-mode test.
`getByLabel("Superchargers (0)")` correctly resolves the native checkbox, but LDS deliberately
renders that input at zero size and opacity and makes its wrapping label the visible, clickable
control. The test therefore rejected correct UI behavior even though its own shared agency-row
helper documents this LDS contract.

**Resolution:** Assert visibility of the public `.rt-legend` label containing the accessible
name and route-scoped zero count. This retains the behavioral check without reviving an internal
test hook or mistaking the accessibility node for the painted control.

### M3 — Storybook had no CI durability gate (MEDIUM, FIXED)

The initial replacement added local scripts, but the existing frontend CI job built only the
production Vite entries. A dependency or alias change could therefore break every story while CI
remained green.

**Resolution:** Add `npm run build-storybook` to the frontend CI job and the matching `make test`
frontend gate. Generated `storybook-static/` output is ignored rather than committed.

### M4 — Generated Storybook bundle trips the source color checker (MEDIUM, FIXED)

The first Storybook-enabled CI run built the catalog successfully, then failed because the
repository-wide color-token guard walked the generated `frontend/storybook-static` tree and
reported hundreds of Storybook vendor colors as authored application violations. The local check
had run before the Storybook build, hiding this order-dependent failure.

**Resolution:** Treat `storybook-static` like the existing Vite `dist` directory in the color
checker: both are generated output, while authored Storybook CSS under `frontend/src` remains
fully checked. Reproduce the CI order locally by building Storybook before running all three
CSS/token guardrails.

## Verification log

| Check | Result |
|---|---|
| Initial diff integrity (`git diff master...HEAD --check`) | Pass |
| Removed-global inventory (`rg 'window\\.__rt|__rt…'`) | Pass; documentation references only |
| Focused gallery/map coverage (Node 26 workaround) | Pass; 60 tests |
| Full frontend unit suite (Node 26 workaround) | Pass; 1,337 tests |
| Typecheck and feature-boundary lint | Pass |
| Production Vite build | Pass; all four HTML entries emitted |
| Color, token-usage, and CSS-structure guardrails | Pass |
| Static route test and smoke-test Kotlin compilation | Pass |
| Initial GitHub mergeability / checks | **Fail; conflicting, no checks reported** |
| Rebase onto `origin/master` (`06f32303a`) | Pass; migration plan resolved with React 19.2 |
| Post-rebase full frontend suite | Pass; 1,337 tests on React 19.2 |
| Post-rebase typecheck, lint, build, and CSS/token checks | Pass |
| Post-rebase static route tests and smoke compilation | Pass |
| Pre-push full backend suite | Pass |
| GitHub mergeability after force-push | Pass; PR is mergeable |
| Rebased-head GitHub CI (run `31518827395`) | **Fail; 5/6 jobs passed, route smoke used hidden LDS input** |
| Focused smoke fix formatting and Kotlin compilation | Pass |
| Replacement live smoke / aggregate CI (run `31519829633`) | Pass; all nine smoke scenarios and all required jobs green |
| Storybook 10.5.7 compatibility (React 19 / Vite 8 peer ranges) | Pass; supported by `@storybook/react-vite` |
| Storybook static build | Pass; Docs, Accessibility, catalog, and component stories emitted |
| Storybook dev server startup | Pass on `localhost:6006` |
| Rendered browser inspection | Unavailable; no controllable browser attached to this session |
| Production Vite build | Pass; only the three intended application entries emitted |
| Full frontend suite after replacement | Pass; 79 files / 1,335 tests |
| Frontend lint, typecheck, and CSS/token guardrails | Pass |
| Ktor static-route tests, Kotlin formatting, and smoke compilation | Pass |
| Workflow syntax (`actionlint`) | Pass |
| First Storybook-enabled GitHub CI (run `31522197198`) | **Fail; generated Storybook vendor colors scanned as source** |
| Updated-head GitHub CI (run `31522500217`) | Pass; Storybook build, frontend checks, backend checks, images, and live smoke all green |

The first focused test invocation omitted `--no-experimental-webstorage`: 44 tests
passed and all 16 `MapProvider` tests failed in setup because local Node 26 replaced
jsdom's `localStorage`. The same 60 tests and then the full suite passed with the
established workaround. Project CI uses Node 22 and does not require it.

## Final decision

**APPROVE.** The unintended production gallery is removed, Storybook is development-only, every
review finding is fixed, and the replacement head passed the full required CI suite.

GitHub's first live Playwright run executed all nine scenarios: eight passed and the route
scenario reached its final assertion before exposing M2. The replacement run passed that
scenario and the full smoke suite, closing the earlier compile-only integration risk.
