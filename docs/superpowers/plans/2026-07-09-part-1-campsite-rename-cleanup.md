# Campsite Rename Cleanup Implementation Plan (Part 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the canonical-catalog rename (Workstream A of the approved master plan): retire every `Reservable`/`reservable`-flavored Kotlin type, field, function, YAML key, JS identifier, and doc sentence that now means "campsite", delete the dead `AvailabilityClient.reservableAvailability` port method, and rename the `PoiReservableJoiner` seam to `CampsiteParentJoiner` — without touching the DB (columns are already `campsite_*`), the semantic FCFS wire field `reservable`, or the provisioned Grafana uid string `reservable-watch-drill`.

**Architecture:** Pure mechanical rename + dead-code deletion over the existing layering: `models/domain` → `repo` → `service/availability` (+ `provider` adapters) → `routes`, plus the ETL framework (`service/etl/framework` + vendor joiners + `config/poi-registry.yaml`) and the `web/availability` frontend modules. No schema migrations, no behavior changes except one frontend bug fix (stale `w.reservable` wire read in `web/topbar/alerts.js`, and the stale `reservable_filters` key in the watch-create payload).

**Tech Stack:** Kotlin (Ktor, jOOQ, kotlinx.serialization), vanilla ES-module JS with `node:test`, YAML registry, Python unittest for the Grafana regression script.

## Global Constraints

- **Build stays green per task; each task = exactly one commit.** Never commit a state where `./gradlew --no-daemon build` fails.
- **Gradle runs from the repo root** (`settings.gradle.kts` and `gradlew` live at `/Users/wc/code/github/wwchen/roadtrip-map/`; `backend` is an included subproject). Compile gate: `./gradlew --no-daemon :backend:compileKotlin :backend:compileTestKotlin`. Full gate: `./gradlew --no-daemon build` (compiles + tests + ktlint).
- **Web tests** are Node built-in `node:test` files with no package.json runner: `node --test web/*.test.mjs` from the repo root. The four existing files are `web/layers.test.mjs`, `web/supercharger-detail.test.mjs`, `web/campsite-api.test.mjs`, `web/campground-detail.test.mjs`. (Verified: none of them reference the reservable-flavored functions being renamed — they are a regression gate only.)
- **Grafana regression:** `python3 scripts/test_grafana_canonical_catalog_dashboards.py` (unittest-based, runs directly).
- **Graphite is local-only:** use `gt track` / `gt restack` only. Never run `gt sync` (no Graphite cloud account; it errors).
- **Do NOT change the Grafana dashboard uid string** `"reservable-watch-drill"` at `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt:21`. Provisioned dashboard uids must stay stable. The Kotlin const is already neutrally named `WATCH_DASHBOARD_UID`, so no const rename is needed — only the *value* is sacred. Same for the test fixtures that embed `https://grafana.test/d/reservable-watch-drill?...` URLs (`SlackContentWatchStatusRendererTest.kt:28`, `SlackNotificationServiceImplTest.kt:73`): leave them.
- **The API wire field `reservable`** (boolean "reservation system vs first-come-first-served") on campground POI responses is **semantic, not residue**. Keep the wire field and its reads in `web/topbar.js` (lines 1801, 1851), `web/campground-card.js` (lines 216–265), and `web/drawer/campground.js:194`, and keep the `reservable` output of `RecGovCampgroundsEtl.isReservable` (it projects the vendor's `Reservable` field).
- **DB columns are already `campsite_*`** (V38 renamed `reservable_id` → `campsite_id`, `reservable_filters` → `campsite_filters`, `reservable_count` → `campsite_count`). This plan is Kotlin/JS/YAML/docs-only: **no new migrations**, and never edit historical migrations (`V11`, `V12`, `V13`, `V18`, `V22`, `V38`, …) — their `reservable` mentions are permanent history.
- **TDD mode for this plan is compile-driven.** These are mechanical renames of existing, already-tested code: the "failing test" for a rename is the compiler (`:backend:compileKotlin :backend:compileTestKotlin` fails between "rename declaration" and "update call sites"), and the "passing test" is the existing suite going green again. Each task says so explicitly and gives exact commands. The only new-behavior steps (dead-code deletion, frontend fallback removal) get explicit verification greps instead of new tests.
- **Commit trailer:** every commit adds `-m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"` as a second `-m` flag (never heredocs, never literal newlines in `-m`).

---

## File Structure

No files are created (two are deleted-by-rename via `git mv`, and dead code is removed from existing files). Every path is relative to the repo root.

**Renamed (git mv) — backend main:**

- `backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Reservable.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Campsite.kt` (Task 1)
- `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/ReservableDayObservation.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/CampsiteDayObservation.kt` (Task 3)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ReservableTags.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteTags.kt` (Task 3)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/PoiReservableJoiner.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteParentJoiner.kt` (Task 5)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/recgov/RecgovPoiReservableJoiner.kt` → `.../recgov/RecgovCampsiteParentJoiner.kt` (Task 5)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraPoiReservableJoiner.kt` → `.../aspira/AspiraCampsiteParentJoiner.kt` (Task 5)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoiner.kt` → `.../reserveamerica/ReserveAmericaCampsiteParentJoiner.kt` (Task 5)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reservecalifornia/ReserveCaliforniaPoiReservableJoiner.kt` → `.../reservecalifornia/ReserveCaliforniaCampsiteParentJoiner.kt` (Task 5)

**Modified — backend main (`backend/src/main/kotlin/ca/floo/roadtrip/`):**

- `models/availability/CellTransition.kt` — `reservableId` → `campsiteId` (Task 2)
- `models/availability/AvailabilityObservationBatch.kt` — `ReservableDayObservation` type ref (Task 3)
- `models/metadata/registry/PoiRegistry.kt` — joiner entry/property renames + stale comments (Tasks 5, 6)
- `models/metadata/ingest/Phase.kt` — `Section.POI_RESERVABLE_JOINER` → `CAMPSITE_PARENT_JOINER` (Task 5)
- `repo/AvailabilityRepo.kt`, `repo/AvailabilityWatchTargetRepo.kt`, `repo/AvailabilityWatchRepo.kt` — field/param renames (Task 2)
- `repo/CampsiteRepo.kt` — `Reservable` type refs (Task 1)
- `routes/AvailabilityWatchRoutes.kt`, `routes/AvailabilityDashboardRoutes.kt` — call-site renames (Tasks 1, 2)
- `service/api/AvailabilityLoader.kt` — `TargetReservable` → `CampsiteTarget`, `row.reservableId` (Tasks 2, 3)
- `service/api/AvailabilityResponseMapper.kt` — `ReservableDayObservation` refs (Task 3)
- `service/availability/ResolvedAvailabilityTarget.kt`, `CatalogAvailabilityBatcher.kt`, `AvailabilityTargetResolver.kt`, `CampsiteCatalogService.kt`, `WatchAlertDispatcher.kt`, `CampsiteAvailabilityComposer.kt`, `WatchScopeResolver.kt` — type/field/local renames + comments (Tasks 1, 2, 3, 6)
- `service/availability/provider/AvailabilityClient.kt` — delete `reservableAvailability`, rename `reservables` param (Tasks 3, 4)
- `service/availability/provider/AvailabilityProvider.kt` — `CatalogReservableRef` → `CatalogCampsiteRef`, delete default `reservableAvailability` impl, `reservable:` params (Tasks 1, 3, 4)
- `service/availability/provider/AvailabilityProviderRegistry.kt` — stale comment (Task 6)
- `service/availability/provider/adapters/aspira/AspiraAvailabilityProvider.kt`, `aspira/AspiraObservations.kt` — renames + dead-code deletion (Tasks 1, 3, 4)
- `service/availability/provider/adapters/recgov/RecGovAvailabilityProvider.kt`, `recgov/RecGovObservations.kt` — renames + dead-code deletion (Tasks 1, 3, 4)
- `service/availability/provider/adapters/reserveamerica/ReserveAmericaAvailabilityProvider.kt`, `reservecalifornia/ReserveCaliforniaAvailabilityProvider.kt`, `campflare/CampflareAvailabilityProvider.kt` — renames + override deletion (Tasks 3, 4)
- `service/scheduler/jobs/AvailabilityPollExecutor.kt` — field renames + comments (Tasks 2, 6)
- `service/etl/framework/EtlOrchestrator.kt`, `IngestController.kt`, `RegistryTargets.kt`, `CampsiteEtlOutput.kt` — joiner renames + comments (Tasks 5, 6)
- `service/etl/vendors/recgov/RecGovCampsitesEtl.kt`, `aspira/AspiraResourcesEtl.kt` — `reservableTagKey` → `campsiteTagKey` (Task 3)
- `service/etl/vendors/reserveamerica/ReserveAmericaSitesEtl.kt` — comment class-name ref (Task 5)

**Modified — backend tests (`backend/src/test/kotlin/ca/floo/roadtrip/`):**

- `repo/AvailabilityRepoTest.kt`, `repo/AvailabilityWatchRepoTest.kt`, `repo/AvailabilityWatchTargetRepoTest.kt` (Task 2)
- `routes/AvailabilityWatchRoutesTest.kt`, `routes/AvailabilityDashboardRoutesTest.kt` (Task 2)
- `service/api/AvailabilityResponseTest.kt` (Task 3)
- `service/availability/CatalogAvailabilityBatcherTest.kt`, `AvailabilityPollerMembershipTest.kt`, `AvailabilityWatchServiceTest.kt`, `WatchScopeResolverTest.kt`, `DbAvailabilityTargetResolverTest.kt` (Tasks 1, 2, 3)
- `service/availability/provider/AspiraAvailabilityProviderTest.kt`, `RecGovAvailabilityProviderTest.kt`, `CampflareAvailabilityProviderTest.kt`, `ReserveAmericaAvailabilityProviderTest.kt`, `ReserveCaliforniaAvailabilityProviderTest.kt`, `AvailabilityClientContractTest.kt` (Tasks 1, 3, 4)
- `service/availability/provider/adapters/aspira/AspiraObservationsTest.kt`, `adapters/recgov/RecGovObservationsTest.kt` (Tasks 3, 4)
- `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`, `service/scheduler/PollerBackfillTest.kt` (Tasks 1, 2)
- `service/notification/SlackInteractivityHandlerTest.kt` (Task 2)
- `models/metadata/registry/PoiRegistryValidatorTest.kt`, `service/etl/framework/RegistryTargetsTest.kt` (Task 5)

**Modified — config, web, docs, scripts:**

- `config/poi-registry.yaml` — `poi_reservable_joiner:` key → `campsite_parent_joiner:`, adapter names, comments (Task 5)
- `web/availability/site-matrix.js`, `web/availability/site-list.js`, `web/availability/availability-week.js` — function/param renames (Task 7)
- `web/topbar/alerts.js` — `w.reservable` → `w.campsite` wire-read fix (Task 7)
- `web/availability/day-fields.js`, `web/availability/site-matrix.js`, `web/availability/availability-week.js` — camelCase dual-shape fallback removal (Task 8)
- `docs/reservation-providers.md`, `docs/adding-a-reservation-provider.md`, `docs/reservation-providers/reserveamerica.md`, `scripts/fetch_aspira_inventory.py`, `scripts/fetch_aspira_dictionaries.py` (Tasks 4, 9)

**Deleted (code, not files):** `AvailabilityClient.reservableAvailability` + default impl + 5 adapter overrides + 2 orphaned fetch helpers (`fetchRecgovReservableObservations`, `fetchAspiraResourceObservations`) + 3 tests exercising them (Task 4).

---

### Task 1: Rename domain type `Reservable` → `Campsite`

**Files:**
- Rename: `backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Reservable.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Campsite.kt`
- Modify (main, 12 files): `repo/CampsiteRepo.kt`, `routes/AvailabilityWatchRoutes.kt`, `service/availability/ResolvedAvailabilityTarget.kt`, `service/availability/CatalogAvailabilityBatcher.kt`, `service/availability/AvailabilityTargetResolver.kt`, `service/availability/CampsiteCatalogService.kt`, `service/availability/WatchAlertDispatcher.kt`, `service/availability/CampsiteAvailabilityComposer.kt`, `service/availability/WatchScopeResolver.kt`, `service/availability/provider/AvailabilityProvider.kt`, `service/availability/provider/adapters/aspira/AspiraAvailabilityProvider.kt`, `service/availability/provider/adapters/recgov/RecGovAvailabilityProvider.kt`
- Modify (test, 5 files): `service/availability/CatalogAvailabilityBatcherTest.kt`, `service/availability/AvailabilityPollerMembershipTest.kt`, `service/availability/provider/AspiraAvailabilityProviderTest.kt`, `service/availability/provider/RecGovAvailabilityProviderTest.kt`, `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**

| Old | New |
|---|---|
| `ca.floo.roadtrip.models.domain.Reservable` (data class) | `ca.floo.roadtrip.models.domain.Campsite` |
| properties `id, vendor, vendorId, name, loop, siteType, raw, tags, providerRef` | unchanged (property names stay) |

**Collision facts (verified):** a *different* `data class Campsite` already exists at `backend/src/main/kotlin/ca/floo/roadtrip/clients/recgov/RecGovAvailabilityClient.kt:106` (the rec.gov wire DTO). In **main** code the two never meet in one file: `RecGovObservations.kt` imports `clients.recgov.Campsite` but not the domain type; `RecGovAvailabilityProvider.kt` imports the domain type but not `clients.recgov.Campsite`. In **tests**, exactly one file imports both: `RecGovAvailabilityProviderTest.kt` (line 3 `import ca.floo.roadtrip.clients.recgov.Campsite`, line 7 `import ca.floo.roadtrip.models.domain.Reservable`). That file gets an import alias. `CampsiteSummarySchema`, `CampsiteDataEntry`, `Campsite*EtlRecord` are distinct names — no conflict.

- [ ] **Step 1: Confirm the collision scope has not drifted**

```bash
grep -rn "class Campsite\b" backend/src/main/kotlin
grep -rln "clients.recgov.Campsite" backend/src | grep -v build
```

Expected: exactly one main declaration (`clients/recgov/RecGovAvailabilityClient.kt:106`) plus importers `RecGovObservations.kt` (main) and the four test files `AvailabilityClientContractTest.kt`, `RecGovAvailabilityProviderTest.kt`, `RecGovObservationsTest.kt`, `AvailabilityProviderRegistryFactoryTest.kt`. Of those, only `RecGovAvailabilityProviderTest.kt` also imports the domain `Reservable`.

- [ ] **Step 2: git mv and rename the declaration (compile-driven "failing test")**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Reservable.kt backend/src/main/kotlin/ca/floo/roadtrip/models/domain/Campsite.kt
```

In `Campsite.kt` change `data class Reservable(` → `data class Campsite(` and rewrite the KDoc first line `A reservable as we store it.` → `A campsite as we store it.` (rest of the KDoc already speaks in campsite/canonical terms; leave it).

Run:

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: **FAIL** with unresolved reference `Reservable` in the 12 main files listed above (this is the compile-driven red step).

- [ ] **Step 3: Update all main-source references**

The full occurrence map (verified by grep; all in `backend/src/main/kotlin/ca/floo/roadtrip/`):

| File | Lines | What changes |
|---|---|---|
| `repo/CampsiteRepo.kt` | 3, 29, 36, 42, 65, 119, 122 | import + `Reservable?`/`List<Reservable>` return types + `return Reservable(` |
| `service/availability/ResolvedAvailabilityTarget.kt` | 5, 10 | import + `val reservable: Reservable` → `val campsite: Campsite` (**property rename too** — callers fixed below) |
| `service/availability/CatalogAvailabilityBatcher.kt` | 8, 28, 37, 45, 98 | import, `val reservables: List<Reservable>` type side only (field renamed in Task 3), `internal fun Reservable.toCatalogReservableRef()` receiver, `private fun Reservable.aspiraProviderRefLong`, `groupTargets.map { it.reservable }` → `it.campsite` |
| `service/availability/AvailabilityTargetResolver.kt` | 4, 24, 40, 41, 53, 56, 77, 80, 83, 88 | import, `fun resolve(reservable: Reservable)` → `fun resolve(campsite: Campsite)` (interface + override + body refs), `reservable = reservable` → `campsite = campsite` |
| `service/availability/CampsiteCatalogService.kt` | 6, 42, 61, 71 | import + `Reservable.` extension receivers + `List<Reservable>` |
| `service/availability/WatchAlertDispatcher.kt` | 4, 153, 175, 207 | import + `Map<Long, Reservable>` / `List<Reservable>` types (locals renamed in Task 2) |
| `service/availability/CampsiteAvailabilityComposer.kt` | 7, 29, 138, 148 | import + `campsites: List<Reservable>` + extension receivers |
| `service/availability/WatchScopeResolver.kt` | 3, 21, 22, 36 | import + return/`LinkedHashMap<Long, Reservable>` types |
| `service/availability/provider/AvailabilityProvider.kt` | 5, 99, 110 | import + `bookingUrlTemplate(reservable: Reservable, …)` / `bookingUrl(reservable: Reservable, …)` → param `campsite: Campsite` |
| `service/availability/provider/adapters/aspira/AspiraAvailabilityProvider.kt` | 7, 130 | import + `bookingUrlTemplate` override param (body refs `reservable.providerRef` → `campsite.providerRef`) |
| `service/availability/provider/adapters/recgov/RecGovAvailabilityProvider.kt` | 6, 95 | import + `bookingUrlTemplate` override param (`reservable.vendorId` → `campsite.vendorId`) |
| `routes/AvailabilityWatchRoutes.kt` | 15, 324 | import + `compareBy<Reservable, String?>(nullsLast())` → `compareBy<Campsite, String?>` |

Mechanical sweep for the type name (then hand-fix the `resolve(reservable:)`/`bookingUrlTemplate(reservable:)` param renames and `it.reservable` → `it.campsite`):

```bash
grep -rln "models.domain.Reservable\|Reservable(" backend/src/main/kotlin | xargs sed -i '' 's/models\.domain\.Reservable/models.domain.Campsite/g'
grep -rn "\bReservable\b" backend/src/main/kotlin | grep -v build   # then edit each remaining hit by hand
```

Also update `ResolvedAvailabilityTarget.reservable` → `.campsite` call sites: `CatalogAvailabilityBatcher.kt:98`, `AvailabilityPollExecutor.kt:121` (`.distinctBy { it.reservable.id }` → `it.campsite.id`), `CampsiteAvailabilityComposer.kt:113` (`dbId = reservable.id` → `campsite.id` inside `toAvailabilityTarget()`), `AvailabilityTargetResolver.kt:53`.

Run:

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: PASS.

- [ ] **Step 4: Update the 5 test files**

`import ca.floo.roadtrip.models.domain.Reservable` → `...domain.Campsite` and constructor calls `Reservable(` → `Campsite(` in: `CatalogAvailabilityBatcherTest.kt:9`, `AvailabilityPollExecutorTest.kt:10`, `AvailabilityPollerMembershipTest.kt:6`, `AspiraAvailabilityProviderTest.kt:9`, and `RecGovAvailabilityProviderTest.kt:7`.

In `RecGovAvailabilityProviderTest.kt` **only**, resolve the name clash by aliasing the wire DTO:

```kotlin
import ca.floo.roadtrip.clients.recgov.Campsite as RecGovCampsite
```

and change its usages (constructor calls at former lines 24, 34, 75, 115, 129, 163, 177 and the `Map<String, Campsite>` in the fake-client type) to `RecGovCampsite`. The domain type keeps the plain name `Campsite` (matching how every other file uses it).

Run:

```bash
./gradlew --no-daemon :backend:compileTestKotlin
```

Expected: PASS.

- [ ] **Step 5: Full build**

```bash
./gradlew --no-daemon build
```

Expected: PASS (all existing tests green — no assertion touched semantics).

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "refactor: rename Reservable domain type to Campsite" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Rename `reservableId`/`reservableFilters` fields → `campsiteId`/`campsiteFilters`

**Files:**
- Modify (main): `models/availability/CellTransition.kt`, `repo/AvailabilityRepo.kt`, `repo/AvailabilityWatchTargetRepo.kt`, `repo/AvailabilityWatchRepo.kt`, `routes/AvailabilityWatchRoutes.kt`, `routes/AvailabilityDashboardRoutes.kt`, `service/api/AvailabilityLoader.kt`, `service/availability/WatchAlertDispatcher.kt`, `service/availability/WatchScopeResolver.kt`, `service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify (test): `repo/AvailabilityRepoTest.kt`, `repo/AvailabilityWatchRepoTest.kt`, `repo/AvailabilityWatchTargetRepoTest.kt`, `routes/AvailabilityWatchRoutesTest.kt`, `routes/AvailabilityDashboardRoutesTest.kt`, `service/availability/AvailabilityWatchServiceTest.kt`, `service/availability/WatchScopeResolverTest.kt`, `service/notification/SlackInteractivityHandlerTest.kt`, `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`, `service/scheduler/PollerBackfillTest.kt`

**Interfaces:** (all Kotlin-only; the DB columns and wire fields are already snake_case `campsite_*`)

| Old member | New member | Where declared |
|---|---|---|
| `CellTransition.reservableId: Long` | `CellTransition.campsiteId` | `models/availability/CellTransition.kt:13` |
| `AvailabilityRepo.Observation.reservableId: Long` | `.campsiteId` | `repo/AvailabilityRepo.kt:24` |
| `AvailabilityRepo.CurrentCell.reservableId: Long` | `.campsiteId` | `repo/AvailabilityRepo.kt:31` |
| `AvailabilityRepo.StatusRun.reservableId: Long` | `.campsiteId` | `repo/AvailabilityRepo.kt:177` |
| `AvailabilityRepo.markElapsedAsPast(reservableIds:)` / `readCurrent(reservableIds:)` params | `campsiteIds` | `repo/AvailabilityRepo.kt:107, 148` |
| `AvailabilityRepo.listForReservable(reservableId:)` | `listForCampsite(campsiteId:)` | `repo/AvailabilityRepo.kt:208-209` |
| `AvailabilityWatchTargetRepo.TargetInput.reservableId: Long?` | `.campsiteId` | `repo/AvailabilityWatchTargetRepo.kt:19` (+ `require` message at :22-23) |
| `AvailabilityWatchTargetRepo.WatchTarget.reservableId: Long?` | `.campsiteId` | `repo/AvailabilityWatchTargetRepo.kt:32` |
| `AvailabilityWatchRepo.CreateInput.reservableFilters` / `UpdateInput.reservableFilters` / `Watch.reservableFilters` | `.campsiteFilters` | `repo/AvailabilityWatchRepo.kt:30, 42, 55` |
| `AvailabilityWatchRepo.listByStatus(...reservableId:)` / `countByStatus(...reservableId:)` / `scopeConditions(...reservableId:)` params | `campsiteId` | `repo/AvailabilityWatchRepo.kt:113, 173, 191` |

- [ ] **Step 1: Rename `CellTransition.reservableId` and see the compiler fail**

In `models/availability/CellTransition.kt`: `val reservableId: Long,` → `val campsiteId: Long,`; in the KDoc change `` `(reservableId, targetDate)` `` → `` `(campsiteId, targetDate)` ``.

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: **FAIL** — unresolved `reservableId` at `repo/AvailabilityRepo.kt:88`, `routes/AvailabilityWatchRoutes.kt:319`, `service/availability/WatchAlertDispatcher.kt:69, 113, 180`.

- [ ] **Step 2: Rename the repo members and fix every call site**

Apply the interface table above. Complete verified call-site list for `reservableId`/`reservableFilters` outside the declaring files:

- `routes/AvailabilityWatchRoutes.kt:319` (`it.reservableId to it.targetDate`), `:385`, `:407` (`TargetInput(poiId = …, reservableId = …)` named args → `campsiteId = …`), `:458` (`?.reservableId`), `:474`, `:476` (`it.reservableId` / `firstTarget?.reservableId`), `:478` (`campsiteFilters = reservableFilters` → `= campsiteFilters`), `:184`, `:255` (`reservableFilters = req.campsiteFilters` → `campsiteFilters = req.campsiteFilters`)
- `routes/AvailabilityDashboardRoutes.kt:245` (`availability.listForReservable(campsiteId, …)` → `listForCampsite`), `:340` (`campsiteId = reservableId` inside `StatusRun.toSchema()` → `campsiteId = campsiteId`)
- `service/api/AvailabilityLoader.kt:137` (`campsiteId = row.reservableId` → `row.campsiteId`)
- `service/availability/WatchAlertDispatcher.kt:69` (`t.reservableId in reservablesById`), `:113` (`CellTransition(it.reservableId, …)`), `:180` (`reservablesById.getValue(t.reservableId)`) — the `reservablesById`/`reservables` locals themselves are renamed here too: `:66, 98, 109, 110, 129, 130, 153, 155, 175, 207, 215, 219` → `campsitesById` / `campsites`
- `service/availability/WatchScopeResolver.kt:25` (`target.reservableId?.let …` → `target.campsiteId`), `:26` (`watch.reservableFilters` → `watch.campsiteFilters`)
- `service/scheduler/jobs/AvailabilityPollExecutor.kt:250` (`reservableId = dbId` → `campsiteId = dbId`)

Run:

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: PASS.

- [ ] **Step 3: Fix test call sites**

Verified test hits (named args + fixture helpers):

- `repo/AvailabilityRepoTest.kt:110` (`listForReservable` → `listForCampsite`; `Observation(reservableId=…)` constructor args)
- `repo/AvailabilityWatchTargetRepoTest.kt`, `repo/AvailabilityWatchRepoTest.kt` — `TargetInput(poiId=…, reservableId=…)`, `reservableFilters =` named args
- `routes/AvailabilityWatchRoutesTest.kt:359-360, 599-600, 713-716, 766, 781-796, 803-814, 852-853, 905-910` — helper fns `seedReservable`/`linkReservableToPoi` → rename to `seedCampsite`/`linkCampsiteToPoi` and their `reservableId` params
- `routes/AvailabilityDashboardRoutesTest.kt:71, 82, 91, 230-237` — `seedReservable` → `seedCampsite`, `reservableId` locals/params
- `service/availability/AvailabilityWatchServiceTest.kt:42, 73-74, 86-99, 119-164` — `seedReservable`, `TargetInput(… reservableId=…)`, `reservableFilters =`
- `service/availability/WatchScopeResolverTest.kt:43, 63, 74-110` — `insertReservable` → `insertCampsite`, locals `reservableInA1/…`, `reservableId =` args
- `service/notification/SlackInteractivityHandlerTest.kt:115-116` — `WatchTarget(… reservableId = null)`, `reservableFilters =`
- `service/scheduler/jobs/AvailabilityPollExecutorTest.kt:1173-1203` — `listForReservable`, `reservableId` locals
- `service/scheduler/PollerBackfillTest.kt:43, 90` — `seedReservable` helper

```bash
./gradlew --no-daemon :backend:compileTestKotlin
./gradlew --no-daemon build
```

Expected: both PASS.

- [ ] **Step 4: Commit**

```bash
git add -A backend/src
git commit -m "refactor: rename reservableId/reservableFilters fields to campsiteId/campsiteFilters" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Class/file renames — `ReservableDayObservation`, `CatalogReservableRef`, `TargetReservable`, `AspiraCatalogReservable`, `reservableTagKey`, `GroupFetchResult.reservables`

**Files:**
- Rename: `models/availability/ReservableDayObservation.kt` → `models/availability/CampsiteDayObservation.kt`
- Rename: `service/etl/framework/ReservableTags.kt` → `service/etl/framework/CampsiteTags.kt`
- Modify (main): `models/availability/AvailabilityObservationBatch.kt`, `service/api/AvailabilityLoader.kt`, `service/api/AvailabilityResponseMapper.kt`, `service/availability/ResolvedAvailabilityTarget.kt`, `service/availability/CatalogAvailabilityBatcher.kt`, `service/availability/AvailabilityTargetResolver.kt`, `service/availability/CampsiteAvailabilityComposer.kt`, `service/availability/provider/AvailabilityClient.kt`, `service/availability/provider/AvailabilityProvider.kt`, all 5 adapters (`adapters/{recgov,aspira,campflare,reserveamerica,reservecalifornia}/*AvailabilityProvider.kt`), `adapters/recgov/RecGovObservations.kt`, `adapters/aspira/AspiraObservations.kt`, `service/scheduler/jobs/AvailabilityPollExecutor.kt`, `service/etl/vendors/recgov/RecGovCampsitesEtl.kt`, `service/etl/vendors/aspira/AspiraResourcesEtl.kt`
- Modify (test): `service/api/AvailabilityResponseTest.kt`, `service/availability/CatalogAvailabilityBatcherTest.kt`, `service/availability/provider/adapters/aspira/AspiraObservationsTest.kt`, `service/availability/provider/adapters/recgov/RecGovObservationsTest.kt`, `service/availability/provider/{AspiraAvailabilityProviderTest,CampflareAvailabilityProviderTest,RecGovAvailabilityProviderTest,ReserveAmericaAvailabilityProviderTest,ReserveCaliforniaAvailabilityProviderTest}.kt`, `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**

| Old | New | Declared at |
|---|---|---|
| `ReservableDayObservation` (data class: `campsiteId, date, observedAt, status`) | `CampsiteDayObservation` | `models/availability/ReservableDayObservation.kt:6` |
| `CatalogReservableRef` (data class: `campsiteId, vendorId, mapId, resourceLocationId`) | `CatalogCampsiteRef` | `service/availability/provider/AvailabilityProvider.kt:116` |
| `Reservable.toCatalogReservableRef(): CatalogReservableRef` ext fn | `Campsite.toCatalogCampsiteRef(): CatalogCampsiteRef` | `CatalogAvailabilityBatcher.kt:37` |
| `ProviderRef.toCatalogReservableRef(campsiteId, fallback)` private ext | `ProviderRef.toCatalogCampsiteRef(campsiteId, fallback)` | `AvailabilityTargetResolver.kt:91` |
| `AvailabilityLoader.TargetReservable(dbId: Long)` | `AvailabilityLoader.CampsiteTarget(dbId: Long)` | `service/api/AvailabilityLoader.kt:25` |
| `AspiraCatalogReservable` (internal data class: `campsiteId, resourceId, mapId, resourceLocationId`) | `AspiraCatalogCampsite` | `adapters/aspira/AspiraObservations.kt:287` |
| `fun reservableTagKey(label: String): String` | `fun campsiteTagKey(label: String): String` | `service/etl/framework/ReservableTags.kt:5` |
| `GroupFetchResult.reservables: List<Reservable>` | `GroupFetchResult.campsites: List<Campsite>` | `CatalogAvailabilityBatcher.kt:28` |
| `catalogAvailability(ref, reservables: List<CatalogReservableRef>, …)` param | `campsites: List<CatalogCampsiteRef>` | `AvailabilityClient.kt:22`, `AvailabilityProvider.kt:61`, + 5 adapter overrides |
| `reservableVendor: String` params in Aspira observations helpers | `campsiteVendor` | `AspiraObservations.kt:38, 160, 193, 234, 304` |

**Note on `ReservableAvailabilityComposer`:** the master plan listed it, but it was **already renamed** — `CampsiteAvailabilityComposer` exists at `service/availability/CampsiteAvailabilityComposer.kt:20` (used by `CampsiteAvailabilityService.kt:15` and `routes/CampsiteRoutes.kt`). Only its stale doc mention remains (fixed in Task 9). No code change here.

- [ ] **Step 1: `ReservableDayObservation` → `CampsiteDayObservation`**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/models/availability/ReservableDayObservation.kt backend/src/main/kotlin/ca/floo/roadtrip/models/availability/CampsiteDayObservation.kt
```

Rename the class in the file, then run `./gradlew --no-daemon :backend:compileKotlin` — expected **FAIL** at: `AvailabilityObservationBatch.kt:9` (`val observations: List<ReservableDayObservation>`), `service/api/AvailabilityResponseMapper.kt:11, 53`, `service/api/AvailabilityLoader.kt:136`, `adapters/aspira/AspiraObservations.kt:10, 194, 220, 236, 251, 268, 307`, `adapters/recgov/RecGovObservations.kt:189`, `adapters/reservecalifornia/ReserveCaliforniaAvailabilityProvider.kt:141`, `adapters/reserveamerica/ReserveAmericaAvailabilityProvider.kt:157, 170`, `adapters/campflare/CampflareAvailabilityProvider.kt:149`. Fix every one (import + type refs), re-run, expected PASS.

- [ ] **Step 2: `CatalogReservableRef` → `CatalogCampsiteRef` (+ ext fns + params)**

In `AvailabilityProvider.kt:116` rename the data class. In `CatalogAvailabilityBatcher.kt:37` rename the ext fn to `Campsite.toCatalogCampsiteRef()`. In `AvailabilityTargetResolver.kt:80, 88, 91` rename the caller + private overload. Rename the `catalogAvailability(… reservables: List<CatalogReservableRef> …)` parameter to `campsites:` in `AvailabilityClient.kt:22`, `AvailabilityProvider.kt:61`, and the 5 adapter overrides (`AspiraAvailabilityProvider.kt:83`, `RecGovAvailabilityProvider.kt:54`, `ReserveCaliforniaAvailabilityProvider.kt:65`, `CampflareAvailabilityProvider.kt:68`, `ReserveAmericaAvailabilityProvider.kt:85`) plus the pass-through in `adapters/recgov/RecGovObservations.kt:96` and the single-ref param `reservable: CatalogReservableRef` at `ReserveAmericaAvailabilityProvider.kt:153` → `campsite: CatalogCampsiteRef`. Update the named-argument call sites `reservables = rows.map { it.catalogRef }` (`CampsiteAvailabilityComposer.kt:106`) and `reservables = rows.map { it.catalogRef }` (`AvailabilityPollExecutor.kt:179`) to `campsites = …`.

```bash
grep -rn "CatalogReservableRef\|toCatalogReservableRef" backend/src | grep -v build
```

Expected after edits: zero hits. `./gradlew --no-daemon :backend:compileKotlin` — PASS.

- [ ] **Step 3: `TargetReservable` → `CampsiteTarget`; `AspiraCatalogReservable` → `AspiraCatalogCampsite`; `reservableVendor` → `campsiteVendor`**

- `service/api/AvailabilityLoader.kt:25` (`data class TargetReservable`), `:39` (`val targets: List<TargetReservable>`); caller `CampsiteAvailabilityComposer.kt:111-112` (`ResolvedAvailabilityTarget.toAvailabilityTarget(): AvailabilityLoader.TargetReservable` → `AvailabilityLoader.CampsiteTarget`).
- `adapters/aspira/AspiraObservations.kt:287` (`internal data class AspiraCatalogReservable`), param sites `:64, 113, 264`.
- `AspiraObservations.kt` `reservableVendor` params at `:38, 160, 193, 234, 304` plus internal refs `:47, 195, 196, 201` → `campsiteVendor`; caller `AspiraAvailabilityProvider.kt:76, 147` (`reservableVendor = tenant.vendorCode` → `campsiteVendor =`).

`./gradlew --no-daemon :backend:compileKotlin` — PASS.

- [ ] **Step 4: `ReservableTags.kt` → `CampsiteTags.kt` and `GroupFetchResult.reservables` → `campsites`**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ReservableTags.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteTags.kt
```

Rename `fun reservableTagKey` → `fun campsiteTagKey` (file body is just that one function + a `NON_ALNUM` regex). Callers (verified, complete): `service/etl/vendors/recgov/RecGovCampsitesEtl.kt:11` (import) + `:140`, `service/etl/vendors/aspira/AspiraResourcesEtl.kt:10` (import) + `:440`.

In `CatalogAvailabilityBatcher.kt` rename `GroupFetchResult.reservables` (decl `:28`, named-arg constructor sites `:105, 120, 132`, and the local `val reservables = groupTargets.map { it.campsite }` at `:98`) → `campsites`. External readers (verified): `CampsiteAvailabilityComposer.kt:81` (`result.reservables.forEach { campsite -> …}`), `AvailabilityPollExecutor.kt:192` (`r.reservables.map { it.id }`), `:245` (`result.reservables.mapTo…`), `:275` (`r.reservables.size`).

`./gradlew --no-daemon :backend:compileKotlin` — PASS.

- [ ] **Step 5: Fix tests, full build**

Test call sites (verified): `AvailabilityResponseTest.kt:7, 86, 92, 98, 104, 110, 142` (`ReservableDayObservation` → `CampsiteDayObservation`; also the test names at `:75`/`:175` say "reservable day observations"/"unknown reservable status" — rename to campsite wording); `AvailabilityPollExecutorTest.kt:8, 391`; `AspiraObservationsTest.kt` (`AspiraCatalogReservable` at `:146-149, 193-195, 236, 287-289`; `reservableVendor =` at `:53, 91`); `AspiraAvailabilityProviderTest.kt:70, 76, 140, 146, 202, 305-306`; `RecGovObservationsTest.kt:7` (`CatalogReservableRef` import); `CatalogAvailabilityBatcherTest.kt` (`reservables`/`toCatalogReservableRef` refs); the five provider tests' `catalogAvailability(reservables = …)` named args → `campsites = …`.

```bash
./gradlew --no-daemon :backend:compileTestKotlin
./gradlew --no-daemon build
grep -rn "ReservableDayObservation\|AspiraCatalogReservable\|TargetReservable\|reservableTagKey\|reservableVendor" backend/src | grep -v build
```

Expected: build PASS; grep zero hits.

- [ ] **Step 6: Commit**

```bash
git add -A backend/src
git commit -m "refactor: rename reservable-flavored availability and etl types to campsite vocabulary" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Delete the dead port method `AvailabilityClient.reservableAvailability`

**Files:**
- Modify: `service/availability/provider/AvailabilityClient.kt` (delete method, lines 27-32)
- Modify: `service/availability/provider/AvailabilityProvider.kt` (delete default impl, lines 77-82, including the `AvailabilityProviderError.Unsupported("reservableAvailability", id)` throw)
- Modify (delete overrides): `adapters/recgov/RecGovAvailabilityProvider.kt:73-89`, `adapters/aspira/AspiraAvailabilityProvider.kt:134-152`, `adapters/reserveamerica/ReserveAmericaAvailabilityProvider.kt:110-134`, `adapters/reservecalifornia/ReserveCaliforniaAvailabilityProvider.kt:89-111`, `adapters/campflare/CampflareAvailabilityProvider.kt:101-125`
- Modify (delete orphaned helpers): `adapters/recgov/RecGovObservations.kt` (`fetchRecgovReservableObservations`, decl `:131`, plus its KDoc `:128` referencing `[AvailabilityProvider.reservableAvailability]`), `adapters/aspira/AspiraObservations.kt` (`fetchAspiraResourceObservations`, decl `:155`)
- Modify (delete tests): `service/availability/provider/AvailabilityClientContractTest.kt` (`reservableAvailability` call inside the test at `:62-92`), `service/availability/provider/RecGovAvailabilityProviderTest.kt` (test `` `reservable availability narrows fetched campground data to one campsite` `` at `:108`), `service/availability/provider/AspiraAvailabilityProviderTest.kt` (test `` `reservable availability stays unkeyed without a canonical campsite id` `` at `:219`), `service/availability/provider/adapters/recgov/RecGovObservationsTest.kt` (test `` `reservable availability keeps requested site omitted by upstream as unknown` `` at `:231`), `service/availability/provider/adapters/aspira/AspiraObservationsTest.kt` (test `` `aspira resource availability narrows fetched map response to one resource` `` at `:33`)
- Modify: `docs/reservation-providers.md:107` (delete the stale table row)

**Interfaces:** Consumes nothing new; **removes** `suspend fun reservableAvailability(ref: ProviderRef, vendorId: String, startDate: LocalDate, endDate: LocalDate): AvailabilityObservationBatch` from the `AvailabilityClient` port and all implementations. After this task `AvailabilityClient` has exactly two methods: `availability(ref, startDate, endDate)` and `catalogAvailability(ref, campsites, startDate, endDate)`.

- [ ] **Step 1: Verify zero live callers (explicit gate — do not skip)**

```bash
grep -rn "reservableAvailability" backend/src docs web scripts config grafana | grep -v build
```

Expected hits, and ONLY these (verified 2026-07-09): declaration `AvailabilityClient.kt:27`; default impl `AvailabilityProvider.kt:77, 82`; the 5 adapter overrides (`RecGovAvailabilityProvider.kt:73`, `AspiraAvailabilityProvider.kt:134`, `ReserveAmericaAvailabilityProvider.kt:110`, `ReserveCaliforniaAvailabilityProvider.kt:89`, `CampflareAvailabilityProvider.kt:101`); KDoc ref `RecGovObservations.kt:128`; test invocations `AvailabilityClientContractTest.kt:82`, `RecGovAvailabilityProviderTest.kt:143`, `AspiraAvailabilityProviderTest.kt:248`; doc row `docs/reservation-providers.md:107` (which itself says "no live caller … remove if it stays dead"). **No route, service, loader, or poller calls it.** If any unexpected hit appears, STOP and investigate before deleting.

- [ ] **Step 2: Delete port method + default impl + 5 overrides**

Remove the six-line declaration from `AvailabilityClient.kt`, the default `override … = throw AvailabilityProviderError.Unsupported("reservableAvailability", id)` from `AvailabilityProvider.kt`, and the whole override fun (signature through closing brace) in each of the 5 adapters.

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: PASS (main code never called it). Then:

```bash
./gradlew --no-daemon :backend:compileTestKotlin
```

Expected: **FAIL** — unresolved `reservableAvailability` in the three provider-level tests. This is the deletion's red step proving the tests were the only callers.

- [ ] **Step 3: Delete the tests and the now-orphaned helpers**

- `AvailabilityClientContractTest.kt`: inside `` `shared availability client accepts direct arguments instead of request wrappers` `` delete the `val reservable = client.reservableAvailability(…)` block and the `assertEquals(null, reservable.campsiteId)` assertion; keep the `availability`/`catalogAvailability` halves of the test.
- Delete whole test funs: `RecGovAvailabilityProviderTest` `` `reservable availability narrows fetched campground data to one campsite` ``; `AspiraAvailabilityProviderTest` `` `reservable availability stays unkeyed without a canonical campsite id` ``.
- Delete `fetchRecgovReservableObservations` from `RecGovObservations.kt` (only remaining caller after Step 2 is its test) and its test `` `reservable availability keeps requested site omitted by upstream as unknown` `` in `RecGovObservationsTest.kt` (uses it at `:236`).
- Delete `fetchAspiraResourceObservations` from `AspiraObservations.kt` (only remaining caller is its test) and its test `` `aspira resource availability narrows fetched map response to one resource` `` in `AspiraObservationsTest.kt` (uses it at `:48`).

```bash
grep -rn "reservableAvailability\|fetchRecgovReservableObservations\|fetchAspiraResourceObservations" backend/src | grep -v build
./gradlew --no-daemon build
```

Expected: grep zero hits; build PASS.

- [ ] **Step 4: Delete the stale doc row**

In `docs/reservation-providers.md`, in the "Supported monitoring actions" table, delete the row (line 107):

```
| Reservable availability | `AvailabilityClient.reservableAvailability(ref, vendorId, startDate, endDate)` | Narrow projection for a single reservable. Currently unused: availability is always requested by collection (POI), so the port method has no live caller since the single-reservable endpoint was retired. Kept as a capability; remove if it stays dead. |
```

Also update the neighboring row at line 106: `` `AvailabilityClient.catalogAvailability(ref, reservables, startDate, endDate)` `` → `` `AvailabilityClient.catalogAvailability(ref, campsites, startDate, endDate)` `` (parameter renamed in Task 3).

- [ ] **Step 5: Commit**

```bash
git add -A backend/src docs/reservation-providers.md
git commit -m "refactor: delete dead AvailabilityClient.reservableAvailability port method" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Joiner rename — `PoiReservableJoiner` → `CampsiteParentJoiner` (Kotlin + YAML, same commit)

**Files:**
- Rename: `service/etl/framework/PoiReservableJoiner.kt` → `service/etl/framework/CampsiteParentJoiner.kt`
- Rename: `service/etl/vendors/recgov/RecgovPoiReservableJoiner.kt` → `RecgovCampsiteParentJoiner.kt`; `service/etl/vendors/aspira/AspiraPoiReservableJoiner.kt` → `AspiraCampsiteParentJoiner.kt`; `service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoiner.kt` → `ReserveAmericaCampsiteParentJoiner.kt`; `service/etl/vendors/reservecalifornia/ReserveCaliforniaPoiReservableJoiner.kt` → `ReserveCaliforniaCampsiteParentJoiner.kt`
- Modify: `models/metadata/registry/PoiRegistry.kt`, `models/metadata/ingest/Phase.kt`, `service/etl/framework/EtlOrchestrator.kt`, `service/etl/framework/IngestController.kt`, `service/etl/framework/RegistryTargets.kt`, `service/etl/vendors/reserveamerica/ReserveAmericaSitesEtl.kt` (comment at `:18`), `config/poi-registry.yaml`
- Modify (test): `models/metadata/registry/PoiRegistryValidatorTest.kt`, `service/etl/framework/RegistryTargetsTest.kt`
- Modify (docs): `docs/reservation-providers/reserveamerica.md:45, 100` (class-name mentions)

**Interfaces:**

| Old | New |
|---|---|
| `interface PoiReservableJoiner` (members: `adapter`, `discoverLinks(ctx): List<Link>`, `sweepStaleLinks(ctx): Int`, nested `data class Link(campsiteId, campgroundId)`) | `interface CampsiteParentJoiner` (members unchanged) |
| `class RecgovPoiReservableJoiner` / `ADAPTER_NAME = "RecgovPoiReservableJoiner"` | `class RecgovCampsiteParentJoiner` / `ADAPTER_NAME = "RecgovCampsiteParentJoiner"` |
| `class AspiraPoiReservableJoiner` / `"AspiraPoiReservableJoiner"` | `class AspiraCampsiteParentJoiner` / `"AspiraCampsiteParentJoiner"` |
| `class ReserveAmericaPoiReservableJoiner` / `"ReserveAmericaPoiReservableJoiner"` | `class ReserveAmericaCampsiteParentJoiner` / `"ReserveAmericaCampsiteParentJoiner"` |
| `class ReserveCaliforniaPoiReservableJoiner` / `"ReserveCaliforniaPoiReservableJoiner"` | `class ReserveCaliforniaCampsiteParentJoiner` / `"ReserveCaliforniaCampsiteParentJoiner"` |
| `PoiRegistry.poiReservableJoiners: List<PoiReservableJoinerEntry>` with `@SerialName("poi_reservable_joiner")` (PoiRegistry.kt:64-65) | `PoiRegistry.campsiteParentJoiners: List<CampsiteParentJoinerEntry>` with `@SerialName("campsite_parent_joiner")` |
| `PoiRegistry.enabledPoiReservableJoiners()` / `poiReservableJoinerByName(name)` (`:297, :300`) | `enabledCampsiteParentJoiners()` / `campsiteParentJoinerByName(name)` |
| `data class PoiReservableJoinerEntry(name, enabled, adapter, args)` (`:547-552`) | `data class CampsiteParentJoinerEntry(…)` |
| `Phase.Import.Section.POI_RESERVABLE_JOINER("poi_reservable_joiner")` (Phase.kt:44) | `Section.CAMPSITE_PARENT_JOINER("campsite_parent_joiner")` |
| YAML section key `poi_reservable_joiner:` (config/poi-registry.yaml:516) | `campsite_parent_joiner:` |
| YAML `adapter:` values (`:518, 521, 524, 527`) | the four new class names above |

The YAML `Section.rowValue` also lands in `ingest_runs.phase`-adjacent metadata via `IngestRunRepo` — but `rowValue` there is only the Fetch/Import kind (`fetch`/`import`), not the section, so changing the Section rowValue affects only YAML parsing and lock keys. **Only this repo reads `config/poi-registry.yaml`** (verified: PoiRegistry.load + RegistryTargetsTest), so the key and the Kotlin `@SerialName` must move in the same commit.

- [ ] **Step 1: Rename interface file + declaration**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/PoiReservableJoiner.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteParentJoiner.kt
```

In the file: `interface PoiReservableJoiner` → `interface CampsiteParentJoiner`. Rewrite the header comment's stale sentence (line 17): `` with the current `campsites.campground_id`, `EtlOrchestrator.runJoiner` `` stays, but the doc's opening line `Post-import parent reconciler for vendor-specific campsite → campground relationships.` is already canonical — keep it. (`JoinerCtx` and `Link` keep their names.)

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: **FAIL** — unresolved `PoiReservableJoiner` in the 4 vendor joiners, `EtlOrchestrator.kt`, `PoiRegistry.kt`.

- [ ] **Step 2: Rename the four vendor implementations**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/recgov/RecgovPoiReservableJoiner.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/recgov/RecgovCampsiteParentJoiner.kt
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraPoiReservableJoiner.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampsiteParentJoiner.kt
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoiner.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaCampsiteParentJoiner.kt
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reservecalifornia/ReserveCaliforniaPoiReservableJoiner.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reservecalifornia/ReserveCaliforniaCampsiteParentJoiner.kt
```

In each: class name, `import …framework.PoiReservableJoiner` → `CampsiteParentJoiner`, `PoiReservableJoiner.Link(` → `CampsiteParentJoiner.Link(`, and the companion `const val ADAPTER_NAME = "<OldName>"` → `"<NewName>"` (recgov `:60`, aspira `:87`, reserveamerica `:79`, reservecalifornia `:55`). Also fix the comment ref in `ReserveAmericaSitesEtl.kt:18` (`[ReserveAmericaPoiReservableJoiner]` → `[ReserveAmericaCampsiteParentJoiner]`).

- [ ] **Step 3: Registry model + orchestration wiring**

- `PoiRegistry.kt:64-65`: `@kotlinx.serialization.SerialName("poi_reservable_joiner")` → `("campsite_parent_joiner")`; `val poiReservableJoiners: List<PoiReservableJoinerEntry>` → `val campsiteParentJoiners: List<CampsiteParentJoinerEntry>`.
- `PoiRegistry.kt:296-300`: rename the two accessors and their `/** poi_reservable_joiner rows … */` comments to `campsite_parent_joiner`.
- `PoiRegistry.kt:547`: `data class PoiReservableJoinerEntry` → `CampsiteParentJoinerEntry` (docstring rewritten in Task 6).
- `Phase.kt:44`: `POI_RESERVABLE_JOINER("poi_reservable_joiner")` → `CAMPSITE_PARENT_JOINER("campsite_parent_joiner")`; fix the KDoc at `:24` (`POI_RESERVABLE_JOINER → runJoiner(name)` → `CAMPSITE_PARENT_JOINER → runJoiner(name)`).
- `EtlOrchestrator.kt`: imports `:10-13`, field `:55` (`Map<String, PoiReservableJoiner>` → `Map<String, CampsiteParentJoiner>`), `runJoiner` KDoc `:175, 196-197` (`poi_reservable_joiner` → `campsite_parent_joiner`), `poiRegistry.poiReservableJoinerByName(name)` → `campsiteParentJoinerByName(name)` at `:196`, error string `"no poi_reservable_joiner row with name=…"` → `"no campsite_parent_joiner row with name=…"`, companion `:475-481` (type + the four constructors).
- `IngestController.kt:232` comment, `:268` (`Phase.Import.Section.POI_RESERVABLE_JOINER ->` branch).
- `RegistryTargets.kt:132-140` (comment + `for (row in registry.poiReservableJoiners)` → `registry.campsiteParentJoiners`, log strings `"poi_reservable_joiner '{}' …"` → `"campsite_parent_joiner '{}' …"`), `:157` (`Section.POI_RESERVABLE_JOINER` → `Section.CAMPSITE_PARENT_JOINER`).

```bash
./gradlew --no-daemon :backend:compileKotlin
```

Expected: PASS.

- [ ] **Step 4: YAML — same commit**

`config/poi-registry.yaml`:
- Line 516: `poi_reservable_joiner:` → `campsite_parent_joiner:`.
- Lines 518/521/524/527: `adapter: RecgovPoiReservableJoiner` → `adapter: RecgovCampsiteParentJoiner`, `AspiraPoiReservableJoiner` → `AspiraCampsiteParentJoiner`, `ReserveCaliforniaPoiReservableJoiner` → `ReserveCaliforniaCampsiteParentJoiner`, `ReserveAmericaPoiReservableJoiner` → `ReserveAmericaCampsiteParentJoiner`.
- Section banner comment (lines 510-515): `# poi_reservable_joiner — post-import parent reconciliation.` → `# campsite_parent_joiner — post-import parent reconciliation.` (rest of the comment already speaks campsite/campground — keep).
- Header comment line 20-21: replace

```yaml
# 4. poi_reservable_joiner — retired parent resolver declarations kept only as
#    registry history.
```

with

```yaml
# 4. campsite_parent_joiner — post-import parent reconciliation rows; each
#    names an adapter that reparents campsites whose campground_id disagrees
#    with its vendor-ref lookup.
```

(The current text is doubly stale: the section is not "retired", it is live — `RegistryTargets.kt` builds import targets from it.)

- [ ] **Step 5: Tests that parse the YAML / build entries**

- `PoiRegistryValidatorTest.kt:289-302`: the inline-YAML test `` `poi_reservable_joiner row with adapter loads` `` → rename fun to `` `campsite_parent_joiner row with adapter loads` ``, change the embedded YAML key `poi_reservable_joiner:` → `campsite_parent_joiner:`, `adapter: RecgovPoiReservableJoiner` → `RecgovCampsiteParentJoiner`, and assertions `r.poiReservableJoiners` → `r.campsiteParentJoiners` / expected string. Check the sibling test `` `joiner row with blank adapter fails` `` (~`:305`) for the same embedded key and fix it too.
- `RegistryTargetsTest.kt:10` (import `PoiReservableJoinerEntry` → `CampsiteParentJoinerEntry`), `:113-119` (`poiReservableJoiners = listOf(PoiReservableJoinerEntry(…))` → new names), and the production-YAML fan-out test at `:129+` loads `config/poi-registry.yaml` directly — it must pass against the edited file.

```bash
./gradlew --no-daemon :backend:compileTestKotlin
./gradlew --no-daemon build
grep -rn "PoiReservableJoiner\|poi_reservable_joiner\|poiReservableJoiner" backend/src config | grep -v build
```

Expected: build PASS; grep zero hits.

- [ ] **Step 6: Docs class-name mentions**

`docs/reservation-providers/reserveamerica.md:45` (`ReserveAmericaPoiReservableJoiner`) and `:100` — update to `ReserveAmericaCampsiteParentJoiner`.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src config/poi-registry.yaml docs/reservation-providers/reserveamerica.md
git commit -m "refactor: rename PoiReservableJoiner seam to CampsiteParentJoiner" -m "Includes the YAML key poi_reservable_joiner -> campsite_parent_joiner in the same commit; only this repo reads the registry." -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Stale comment cleanup (PoiRegistry, AvailabilityProviderRegistry, WatchScopeResolver, misc)

**Files:**
- Modify: `models/metadata/registry/PoiRegistry.kt` (comment blocks at `:24-28`, `:303-304`, `:325`, `:346`, `:537-541`)
- Modify: `service/availability/provider/AvailabilityProviderRegistry.kt` (KDoc `:6-20`)
- Modify: `service/availability/WatchScopeResolver.kt` (KDoc `:13-20`)
- Modify: `service/scheduler/jobs/AvailabilityPollExecutor.kt` (comments `:38, 101, 104, 119`)
- Modify: `service/etl/framework/CampsiteEtlOutput.kt` (comment `:9`)
- Modify: `models/availability/CellTransition.kt` — already fixed in Task 2; verify only.

**Interfaces:** none — comments only. Compile gate still runs to catch typos in KDoc references.

- [ ] **Step 1: PoiRegistry.kt**

Replace lines 24-28 (current text, verbatim):

```
//   - poi_reservable_joiner: N:M-link discovery (RFC 0008). Each entry
//     names an adapter that reads the current state of `pois` +
//     canonical campsite catalog rows and writes the POI/campsite link rows
//     into `reservable_pois`. No etl chain — joiners don't transform raw
//     data, they query DB tables.
```

with:

```
//   - campsite_parent_joiner: post-import parent reconciliation. Each entry
//     names an adapter that reads canonical campsite/campground vendor refs
//     and reparents campsites whose campground_id disagrees with the
//     vendor-ref lookup. No etl chain — joiners don't transform raw data,
//     they query DB tables.
```

Replace the `PoiReservableJoinerEntry` docstring (lines 537-541, current text starts `Row in the `poi_reservable_joiner` section. Names a single adapter … writes the POI/campsite link rows into `reservable_pois`.`) with:

```
/**
 * Row in the `campsite_parent_joiner` section. Names a single adapter that
 * recomputes each campsite's campground parent from vendor refs and
 * reparents rows whose current `campsites.campground_id` disagrees. No etl
 * chain; joiners don't transform raw data, they query DB tables.
```

Fix the three `pois.source` mentions — the `pois` table no longer has a `source` column (V38 wrapper model); the keying is the terminal etl slug:
- Line 303-304: `Static subcategory lookup keyed by terminal etl slug (== pois.source).` → `Static subcategory lookup keyed by terminal etl slug.`
- Line 325: `Aspira upstream host keyed by terminal etl slug (== pois.source).` → `Aspira upstream host keyed by terminal etl slug.`
- Line 346: `by the availability-provider registry to map `pois.source` → `RECGOV`.` → `` by the availability-provider registry to map the terminal etl slug → `RECGOV`. ``

- [ ] **Step 2: AvailabilityProviderRegistry.kt**

In the class KDoc (lines 6-20), two stale phrases:
- `share a wire shape but have different hosts, caches, and reservable vendor codes` → `share a wire shape but have different hosts, caches, and campsite vendor codes`
- `The registry is keyed by `pois.source`, not by id` → `` The registry is keyed by the catalog source slug (`vendor_refs.vendor` tenant key), not by id ``

- [ ] **Step 3: WatchScopeResolver.kt**

Replace the `resolve` KDoc (lines 13-20, current text, verbatim):

```
     * Resolves a watch's full target SET to the flat, de-duplicated list of
     * reservables it covers. A reservable target resolves to itself; a POI
     * target expands to that POI's site-type children, filtered by the
     * watch's shared `reservableFilters`. Union across all targets,
     * first-seen order preserved — this is the entire seam
     * [AvailabilityPollerMembership.sync] depends on, unchanged since PR1.
```

with:

```
     * Resolves a watch's full target SET to the flat, de-duplicated list of
     * campsites it covers. A campsite target resolves to itself; a POI
     * target expands to that POI's site-type children, filtered by the
     * watch's shared `campsiteFilters`. Union across all targets,
     * first-seen order preserved — this is the entire seam
     * [AvailabilityPollerMembership.sync] depends on.
```

- [ ] **Step 4: AvailabilityPollExecutor.kt + CampsiteEtlOutput.kt + WatchAlertDispatcher.kt comments**

- `AvailabilityPollExecutor.kt:38` `reservable under its representative POI` → `campsite under its representative POI`; `:101` `every child reservable` → `every child campsite`; `:104` `regardless of the reservable list` → `regardless of the campsite list`; `:119` `findByPoi returns distinct reservables` → `distinct campsites`.
- `CampsiteEtlOutput.kt:9`: the comment references retired `` `reservables` contracts `` — reword to `the retired reservables-table contracts` only if the sentence reads as *current*; it is historical framing ("replaces the old `reservables` contracts") so keep if already past-tense. Read the sentence and keep historical references that describe the migration; rewrite only present-tense usage.
- `WatchAlertDispatcher.kt` prose comments at `:30` (`reservable set and date window` → `campsite set and date window`), `:170, 179, 189, 201-213` (`reservable's display`, `covered was filtered to reservables in this map`, `the reservable's provider`, `A single-reservable watch reports…`, `reservable-scoped targets`, `a reservable-scoped watch names the site`, `resolved reservable count`) → campsite wording. (Identifiers were renamed in Task 2; this pass is prose only.)

- [ ] **Step 5: Compile + grep gate, commit**

```bash
./gradlew --no-daemon :backend:compileKotlin :backend:compileTestKotlin
grep -rn "pois.source\|reservableFilters\|reservable_pois" backend/src/main/kotlin | grep -v build
```

Expected: compile PASS; grep zero hits (historical migrations excluded by path).

```bash
git add -A backend/src
git commit -m "docs: rewrite stale reservable/pois.source comments to canonical vocabulary" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Frontend rename — `reservable(s)` functions/params in `web/availability/`

**Files:**
- Modify: `web/availability/site-matrix.js`, `web/availability/site-list.js`, `web/availability/availability-week.js`, `web/topbar/alerts.js`

**Interfaces:** (JS module-internal except `renderSiteMatrix`/`renderSiteList` named options)

| Old (file:line, verified) | New |
|---|---|
| `renderSiteMatrix({ … reservables … })` option (site-matrix.js:30) | `campsites` |
| `sortedReservables(reservables, days)` (site-matrix.js:492; called :63) | `sortedCampsites(campsites, days)` |
| `fallbackReservablesFromDays(days)` (site-matrix.js:498; called :494) | `fallbackCampsitesFromDays(days)` |
| `fallbackReservable(id)` (site-matrix.js:510; called :507) | `fallbackCampsite(id)` |
| `filterReservables(rows, filters)` (site-matrix.js:527; called :76) | `filterCampsites(rows, filters)` |
| `sortReservables(rows, sortKey, context)` (site-matrix.js:549; called :76) | `sortCampsites(rows, sortKey, context)` |
| `compareReservable(a, b)` (site-matrix.js:590; called :495, 555, 558, 560, 607) | `compareCampsite(a, b)` |
| `renderSiteList({ … reservables … })` option (site-list.js:27; JSDoc :19) | `campsites` |
| `renderRows(reservables, dateWindow)` param (site-list.js:89) | `campsites` |
| `reservablesForIds(reservables, ids)` (site-list.js:100; called :52) | `campsitesForIds(campsites, ids)` |
| `fallbackReservable(id)` (site-list.js:105; called :102) | `fallbackCampsite(id)` |
| `compareReservable(a, b)` (site-list.js:185; called :95) | `compareCampsite(a, b)` |
| callers `reservables: ctx.sites` (availability-week.js:138, 180) | `campsites: ctx.sites` |
| `oldestCacheBlock(reservables)` (availability-week.js:798; param used :802) | `oldestCacheBlock(campsites)` |
| watch payload key `reservable_filters: {}` (availability-week.js:905) | `campsite_filters: {}` — **wire fix**: the backend `CreateWatchRequest` reads `@SerialName("campsite_filters")` (AvailabilityWatchSchemas.kt:19, default empty), so the old key was silently ignored; sending the right key is a no-op behaviorally but correct |
| `const r = w.reservable;` (topbar/alerts.js:171) + comment :170 | `const r = w.campsite;` — **wire fix**: `AvailabilityWatchSchema` serializes the single-site summary as `campsite` (`AvailabilityWatchSchemas.kt:49`), never `reservable`; the current read is dead and site-scoped watch names fall through to `Watch #id` |

Do **not** touch: `p.reservable` reads in `web/topbar.js:1801, 1851`, `web/campground-card.js:216-265`, `web/drawer/campground.js:194` (semantic FCFS field), and the user-facing copy string `'No reservable sites found for this campground.'` (site-matrix.js, inside `renderSiteMatrix`) — "reservable" as an English adjective is correct there.

- [ ] **Step 1: Rename inside site-matrix.js and site-list.js**

Apply the table. Both files are module-scoped; no other file imports these helper names (verified: only `renderSiteMatrix`, `renderSiteMatrixSkeleton`, `renderSiteList` are exported, imported solely by `availability-week.js:21-22`).

```bash
grep -n "eservable" web/availability/site-matrix.js web/availability/site-list.js
```

Expected: exactly one hit — the `'No reservable sites found…'` copy string.

- [ ] **Step 2: Update availability-week.js callers + payload key, and the alerts.js wire fix**

- availability-week.js:138 and :180 — `reservables: ctx.sites,` → `campsites: ctx.sites,` (these are the option objects passed to `renderSiteList`/`renderSiteMatrix`).
- availability-week.js:798-802 — `oldestCacheBlock` param.
- availability-week.js:905 — `reservable_filters: {},` → `campsite_filters: {},` in `buildWatchPayload`.
- topbar/alerts.js:170-171 — comment `// Reservable-targeted watches (no POI scope) carry a reservable object.` → `// Campsite-targeted watches (no POI scope) carry a campsite object.`; `const r = w.reservable;` → `const r = w.campsite;`.

- [ ] **Step 3: Run web tests (regression gate) + backend smoke-relevant grep**

```bash
node --test web/*.test.mjs
grep -rn "eservable" web/availability web/topbar/alerts.js web/api
```

Expected: all tests PASS (none reference the renamed internals — verified); grep shows only the `'No reservable sites found…'` copy string.

- [ ] **Step 4: Commit**

```bash
git add web/availability web/topbar/alerts.js
git commit -m "refactor: rename reservable-flavored frontend identifiers to campsite vocabulary" -m "Also fixes two stale wire reads: watch payload key reservable_filters -> campsite_filters and w.reservable -> w.campsite in alerts list." -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Remove camelCase dual-shape fallbacks in the frontend

**Files:**
- Modify: `web/availability/day-fields.js` (lines 4, 9), `web/availability/site-matrix.js` (lines 481, 488, 501 pre-Task-7 numbering), `web/availability/availability-week.js` (line 769)

**Interfaces:** none exported change; the day/row readers stop accepting camelCase keys.

- [ ] **Step 1: Prove the backend emits snake_case only (gate before deleting)**

```bash
grep -rn "SerialName(\"campsite" backend/src/main/kotlin
grep -rn "JsonNames\|namingStrategy" backend/src/main/kotlin
```

Expected (verified 2026-07-09): `models/api/AvailabilityDayDto.kt:11` `@SerialName("available_campsite_ids")`, `:12` `@SerialName("campsite_statuses")`; `models/api/AvailabilityResponseDto.kt:14` `@SerialName("campsite_id")`; `models/api/AvailabilityWatchSchemas.kt` `campsite_id`/`campsite_filters` rows — every wire field carries an explicit snake_case `@SerialName`. The `JsonNames`/`namingStrategy` grep returns **zero hits**, so kotlinx.serialization can never emit camelCase for these fields. The FE fallbacks are dead branches. If either grep disagrees, STOP — do not remove the fallbacks.

- [ ] **Step 2: Delete the fallbacks**

- `web/availability/day-fields.js:4`: `const statuses = day?.campsite_statuses ?? day?.campsiteStatuses;` → `const statuses = day?.campsite_statuses;`
- `web/availability/day-fields.js:9`: `const ids = day?.available_campsite_ids ?? day?.availableCampsiteIds;` → `const ids = day?.available_campsite_ids;`
- `web/availability/site-matrix.js` (three sites; at pre-Task-7 lines 481, 488, 501): `day?.campsite_statuses ?? day?.campsiteStatuses` → `day?.campsite_statuses` and `day?.available_campsite_ids ?? day?.availableCampsiteIds` → `day?.available_campsite_ids`
- `web/availability/availability-week.js:769`: `const campsiteId = r?.campsite_id ?? r?.campsiteId;` → `const campsiteId = r?.campsite_id;`

```bash
grep -rn "campsiteStatuses\|availableCampsiteIds\|?? r?.campsiteId" web/
```

Expected: zero hits.

- [ ] **Step 3: Test + commit**

```bash
node --test web/*.test.mjs
git add web/availability
git commit -m "refactor: drop dead camelCase dual-shape fallbacks in availability readers" -m "Backend emits explicit @SerialName snake_case for every availability wire field; the camelCase branches were unreachable." -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Expected: tests PASS.

---

### Task 9: Docs/scripts sweep + final acceptance grep

**Files:**
- Modify: `docs/reservation-providers.md` (lines 77, 106 [done in Task 4], 194-198, 206, 210, 239)
- Modify: `docs/adding-a-reservation-provider.md` (lines 3, 80, 82-83, 131, 139, 141)
- Modify: `scripts/fetch_aspira_inventory.py` (docstring lines 4-10)
- Modify: `scripts/fetch_aspira_dictionaries.py` (docstring lines 2, 13)

**Interfaces:** none — prose and docstrings.

- [ ] **Step 1: docs/reservation-providers.md**

Verified current text → replacement:

- Line 77 (package tree): `├── AvailabilityTargetResolver.kt        # reservable → parent provider + date context` → `├── AvailabilityTargetResolver.kt        # campsite → parent provider + date context`
- Same tree block, line ~76 lists `├── ReservableAvailabilityComposer.kt    # grouping, window policy, per-collection availability load` — the class is actually `CampsiteAvailabilityComposer.kt` (renamed before this plan): update the filename in the tree. Also check the tree's first line `AvailabilityService.kt # POI availability contract used by routes` against reality while there (grep `class AvailabilityService` — adjust only if wrong).
- Lines 194-198 (poller prose): `A run can cover many reservables (a POI-scope watch fans out to every child reservable), but the poller does not issue one upstream call per reservable.` → `A run can cover many campsites (a POI-scope watch fans out to every child campsite), but the poller does not issue one upstream call per campsite.`; `so N reservables under one campground` → `so N campsites under one campground`.
- Line 206: `` `reservable_count`, `window_start` / `window_end`, `outcome` `` → `` `campsite_count`, `window_start` / `window_end`, `outcome` `` (the DB column was renamed in V38; the doc lags).
- Line 210: `table is surfaced in the "Reservable Availability Watch drill down" Grafana` → `table is surfaced in the "Availability Watch drill down" Grafana` — **title text only**; the dashboard uid `reservable-watch-drill` is NOT mentioned here and must not be introduced/changed. (If the provisioned dashboard's on-screen title still says "Reservable Availability Watch drill down", either update the title in its dashboard JSON — title changes are safe, uid changes are not — or quote the title as-is; check `grep -rn "Reservable Availability" grafana/` first: as of writing grafana/ has zero "reservable" hits, so the dashboard title is already neutral and the doc is simply stale.)
- Line 239: `reservable/date/status observation with the shared status enum:` → `campsite/date/status observation with the shared status enum:`

- [ ] **Step 2: docs/adding-a-reservation-provider.md**

- Line 3: `campsite/reservable booking` → `campsite booking`
- Line 80: `- Implement catalog availability when linked local reservables should narrow` → `- Implement catalog availability when linked local campsites should narrow`
- Lines 82-83: `- Implement reservable-level availability only if the upstream has a stable per-reservable path.` → delete these two lines entirely — the per-campsite port method was removed in Task 4, so the guidance is obsolete.
- Line 131: `pipeline details, then add reservable-specific pieces.` → `pipeline details, then add campsite-specific pieces.`
- Line 139: `- Import reservables with stable vendor ids, types, names, loops, site types,` → `- Import campsites with stable vendor ids, types, names, loops, site types,`
- Line 141: `- Add a POI/reservable joiner when catalog rows need linking to parent POIs.` → `- Add a campsite parent joiner (see \`CampsiteParentJoiner\`) when campsites need reparenting to the right campground.`

- [ ] **Step 3: scripts docstrings**

`scripts/fetch_aspira_inventory.py` lines 4-10: change `every reservable site's short label` → `every campsite's short label` and `what we need to populate \`reservables.name\` / \`reservables.description\`.` → `what we need to populate \`campsites.name\` / campsite descriptions.` (the `reservables` table no longer exists).

`scripts/fetch_aspira_dictionaries.py`: line 2 `dictionaries used by reservable ETL` → `dictionaries used by campsite ETL`; line 13 `into each reservable's existing raw JSON` → `into each campsite's existing raw JSON`.

- [ ] **Step 4: Full verification battery**

```bash
./gradlew --no-daemon build
node --test web/*.test.mjs
python3 scripts/test_grafana_canonical_catalog_dashboards.py
```

Expected: all PASS.

- [ ] **Step 5: Final acceptance grep — enumerate survivors**

```bash
grep -rin "reservable" backend/src/main backend/src/test web docs config scripts grafana \
  --exclude-dir=build \
  | grep -v "docs/superpowers/plans" \
  | grep -v "docs/superpowers/specs" \
  | grep -v "rfcs/" \
  | grep -v "backend/src/main/resources/db/migration"
```

**Expected surviving hits — everything else is a miss to fix:**

1. **Semantic FCFS wire field** (`reservable` = "has a reservation system"): `web/topbar.js:1801, 1851`; `web/campground-card.js:216, 218, 223, 265`; `web/drawer/campground.js:194`; `backend/.../service/etl/vendors/recgov/RecGovCampgroundsEtl.kt:40, 121, 412-413` (projects rec.gov's `Reservable` vendor field); any `PoiCtaTest.kt` test names about reservable vs non-reservable campgrounds.
2. **Grafana uid value**: `WatchAlertDispatcher.kt:21` (`WATCH_DASHBOARD_UID = "reservable-watch-drill"`); test fixtures `SlackContentWatchStatusRendererTest.kt:28`, `SlackNotificationServiceImplTest.kt:73` (URLs embedding the uid).
3. **Vendor API field names / vendor-doc wire shapes**: `scripts/fetch_recgov_campsites.py`, `scripts/fetch_recgov_campground_enrichment.py`, `scripts/fetch_reserveamerica.py` (rec.gov RIDB `Reservable=true` filtering — upstream field name); `docs/reservation-providers/aspira.md`, `reserveamerica.md`, `reservecalifornia.md` where "reservable" quotes upstream concepts (park/unit ids); update only sentences that reference OUR retired tables (e.g. reserveamerica.md:46, 69, 102 mention `reservables.raw`/`reservables.name`/linked `reservables` — reword those three to `campsites`, keep upstream-vocabulary sentences).
4. **English adjective in UI copy**: `web/availability/site-matrix.js` `'No reservable sites found for this campground.'`; `web/design-system/HANDOFF.md:109` (`reservable-by-date site matrix` — descriptive prose, keep); `web/design-system/roadtrip-design-system.html` (same).
5. **Historical/retirement references that are correct as history**: `OpenApiSmokeTest.kt:126` (asserts the retired `/api/poi/{poi_id}/reservables/availability` path is GONE — keep, it guards the retirement); `CanonicalCatalogSchemaTest.kt:297-320` (asserts `reservables`/`reservable_id` columns are retired — keep); `scripts/test_grafana_canonical_catalog_dashboards.py` + `scripts/test_grafana_dashboard_consolidation.py` (banned-pattern regexes hunting `reservable_id` etc. — keep, they are the tripwire); `service/etl/framework/CampsiteEtlOutput.kt:9` if the sentence is past-tense history (per Task 6).
6. **grafana/**: zero hits expected (verified — the uid string lives only in Kotlin/test code).

Anything outside these six buckets = incomplete rename; fix before committing.

- [ ] **Step 6: Commit**

```bash
git add docs/reservation-providers.md docs/adding-a-reservation-provider.md docs/reservation-providers/reserveamerica.md scripts/fetch_aspira_inventory.py scripts/fetch_aspira_dictionaries.py
git commit -m "docs: sweep reservable vocabulary out of current-contract docs and script docstrings" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task order & PR stacking

Tasks 1→6 are one Graphite branch (PR 1, backend renames + dead port deletion); Tasks 7→9 are a second branch stacked on it (PR 2, frontend + docs). Track with `gt track`, restack with `gt restack`; **never `gt sync`**. Every task compiles and passes the full suite in isolation, so the stack can pause at any task boundary.

## Self-Review

**Scope coverage vs. master plan (Workstream A):**
- A1 backend domain rename: Task 1 (collision handled via import alias in `RecGovAvailabilityProviderTest.kt` — the domain type keeps the plain `Campsite` name; the rec.gov wire DTO aliases to `RecGovCampsite` in the one dual-import file).
- A2 field/class renames: Tasks 2–3; dead-port deletion: Task 4; joiner + YAML: Task 5; stale comments: Task 6.
- A3 frontend: Tasks 7–8; docs/scripts: Task 9 (plus doc rows folded into Tasks 4–5 where they belong to those commits).
- Grafana uid untouched; FCFS wire field untouched; no migrations.

**Reality corrections discovered while verifying the scope (executor: trust these, not the master plan's guesses):**
- `ReservableAvailabilityComposer` does not exist — it is already `CampsiteAvailabilityComposer` (`service/availability/CampsiteAvailabilityComposer.kt:20`). Only the doc tree in `docs/reservation-providers.md` still shows the old filename (Task 9).
- A `Campsite` name collision DOES exist: `clients/recgov/RecGovAvailabilityClient.kt:106` (rec.gov wire DTO). One test file imports both; handled with an import alias (Task 1 Step 4).
- `CatalogReservableRef` lives at `AvailabilityProvider.kt:116` (not :116 of AvailabilityClient), and there is a *second* private ext `ProviderRef.toCatalogReservableRef` at `AvailabilityTargetResolver.kt:91` the master plan missed (Task 3 Step 2).
- The dead port method also strands two internal fetch helpers (`fetchRecgovReservableObservations`, `fetchAspiraResourceObservations`) and two extra observation tests; deleting only the overrides would leave dead main-source code (Task 4 Step 3).
- `AvailabilityWatchRepo` carries `reservableFilters`/`reservableId` members the master plan's list omitted (Task 2).
- The YAML header comment (poi-registry.yaml:20-21) wrongly calls the joiner section "retired"; it is live (Task 5 Step 4).
- Two live frontend wire bugs found: `availability-week.js:905` still sends `reservable_filters` (backend ignores it — schema key is `campsite_filters`), and `topbar/alerts.js:171` reads `w.reservable` but the schema serializes `campsite` (Task 7).
- Grafana dashboards contain zero "reservable" strings — the "watch drill down" dashboard title in docs is stale doc text, not a dashboard change (Task 9 Step 1).
- Web tests do not cover the renamed helpers; they act as regression gates only (Task 7 Step 3).

**Placeholder scan:** no `TBD`, no "similar to Task N", no "update as needed". Every rename gives declared-at line numbers and the exact call-site list or the exact grep that enumerates them, and every step has a runnable command with an expected outcome.

**Constraint audit:** each task ends in one commit with the co-author trailer via multiple `-m` flags; every gate command is repo-root-relative; uid string, FCFS field, and historical migrations are explicitly fenced off in Global Constraints and re-fenced at the point of temptation (Tasks 4, 7, 9).

