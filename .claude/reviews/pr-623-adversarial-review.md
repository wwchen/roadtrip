# PR #623 Adversarial Self-Review

**PR:** #623 — Finish React migration cleanup and add UI gallery  
**Branch:** `codex/react-migration-cleanup` → `master`  
**Started:** 2026-08-11  
**Status:** IN PROGRESS — CI SMOKE FIX UNDER VERIFICATION

## Review objective

Try to disprove that the PR is safe by checking the removed QA coverage, desktop drawer
interaction, new gallery serving/build path, accessibility, documentation accuracy, and
test reliability. Findings are recorded before fixes and retained after resolution.

## Progress

- [x] Inventory the complete `master...HEAD` diff and project rules.
- [x] Audit every removed `window.__rt*` consumer and identify replacement coverage.
- [x] Exercise the rewritten smoke-test assumptions against component and map behavior.
- [x] Review the gallery for accessibility, representative coverage, and deploy wiring.
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
and preserve the LDS-specific rule in the frontend component guide.

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
| Replacement live smoke / aggregate CI | Pending |

The first focused test invocation omitted `--no-experimental-webstorage`: 44 tests
passed and all 16 `MapProvider` tests failed in setup because local Node 26 replaced
jsdom's `localStorage`. The same 60 tests and then the full suite passed with the
established workaround. Project CI uses Node 22 and does not require it.

## Final decision

**CHANGES REQUESTED UNTIL REPLACEMENT CI PASSES.** The CI-found smoke defect is fixed locally,
but the review remains open until the corrected test is exercised and the replacement head is
green.

GitHub's live Playwright run executed all nine scenarios: eight passed and the route scenario
reached its final assertion before exposing M2. That signal replaces the earlier compile-only
risk assessment; the corrected assertion still requires a replacement run.
