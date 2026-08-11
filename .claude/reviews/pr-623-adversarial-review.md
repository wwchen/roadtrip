# PR #623 Adversarial Self-Review

**PR:** #623 — Finish React migration cleanup and add UI gallery  
**Branch:** `codex/react-migration-cleanup` → `master`  
**Started:** 2026-08-11  
**Status:** LOCAL REVIEW COMPLETE — PR UPDATE PENDING

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
- [ ] Update the PR with review fixes and final decision.

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

The first focused test invocation omitted `--no-experimental-webstorage`: 44 tests
passed and all 16 `MapProvider` tests failed in setup because local Node 26 replaced
jsdom's `localStorage`. The same 60 tests and then the full suite passed with the
established workaround. Project CI uses Node 22 and does not require it.

## Final decision

**APPROVE LOCALLY.** The two implementation findings are fixed, the PR conflict is
resolved, and the rebased tree passes the available local gates. Force-push and the
resulting GitHub CI state remain to be recorded.

The live Playwright smoke suite was compiled but not executed because no QA stack was
running in this review. Its rewritten interactions therefore retain the ordinary
integration risk that CI/live-stack execution is intended to cover.
