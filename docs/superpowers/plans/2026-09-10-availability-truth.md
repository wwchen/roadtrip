# Availability Truth Server-Side Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The POI availability response carries the fused week (campground rollup per day, per-campsite cells with a backend-owned `watchable`), the add-to-cart state, and the provider's real date ceiling; the browser stops re-deriving any of it, stops pinning watch cadence, and stops guessing the horizon.

**Architecture:** One fusion in `service/availability` over the existing `PoiAvailabilitySlice` and `AvailabilityResponseMapper.rollupStatus`; `WatchCapabilityService` grows the add-to-cart state; `AvailabilityDateResolver` already knows the horizon. Typed DTOs on the wire; the frontend's `fuse.ts`, id-list cell logic, watchable-kind set, cart-gate arithmetic, cadence literal, and 365-day constant are deleted.

**Tech Stack:** Kotlin 2 / Ktor / kotlinx.serialization / jOOQ; React + TypeScript + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-10-availability-truth-design.md`. Audit finding 8 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issue #737.

## Global Constraints

- Layering per `docs/backend-architecture.md`: routes parse and respond; rules live in `service/`; no Ktor in `service/`; models depend on stdlib + serialization.
- No inline magic constants; comments short and rare; no migrations in this phase.
- Wire names and enum values exactly as the spec's Wire block; `explicitNulls = false`, `encodeDefaults = true` (lists always emitted).
- Rollup precedence unchanged: `available > first_come > unknown > reserved`, `closed` only when every cell is closed, `unknown` for an empty set.
- Backend gate: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` (Docker running); frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- One commit per task, conventional prefix, `Refs #737`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

---

### Task 1: Fused POI availability on the wire (#737)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityCellDto.kt`, `model/api/AvailabilityWindowState.kt` (enum `SUCCESS("success")`, `EMPTY("empty")`, `CLOSED_FOR_SEASON("closed_for_season")`, `@SerialName` per constant), `service/availability/PoiAvailabilityFusion.kt` (pure: slice + per-campsite polling support + earliest date → `days`, `state`, `season`, `cache`)
- Modify: `model/api/AvailabilityDayDto.kt` (`date`, `status`, `watchable`, `cells: Map<Long, AvailabilityCellDto>`; delete `availableCampsiteIds`/`campsiteStatuses`), `model/api/PoiCampsitesAvailabilityResponseDto.kt` (per the spec: `latest_date`, `state`, `season`, `cache`, `days`; delete `campsites`), `model/availability/AvailabilityStatus.kt` (`val watchable: Boolean` true for `RESERVED` and `FIRST_COME`), `service/api/AvailabilityResponseMapper.kt` (`DayClassification` and the per-stream day shaping emit `cells` with one entry and `watchable`; `rollupStatus` unchanged), `service/availability/CampsiteAvailabilityController.kt` (`poiCampsitesAvailability` builds the fused response; `latestDate` from `AvailabilityDateResolver`), `BulkAvailabilityController.kt` only as far as the shared day DTO requires (bulk keeps per-campsite envelopes and `longest_run_nights`), `WatchCapabilityService.internalPollingSupportFor` gains a per-campsite variant `pollingSupported(campsite): Boolean` used by the fusion, route `describeApi` text for both endpoints.
- Test: `service/availability/PoiAvailabilityFusionTest.kt` (rollup cases ported one-for-one from `frontend/src/features/availability/fuse.test.ts` lines 33-58 and the `fuseDay` cases; watchable per cell and per day, including a provider with `supportsInternalPolling = false` and a day before the earliest date; `empty`; `closed_for_season` with and without a season block; the oldest cache block), `service/api/AvailabilityResponseTest.kt` (cells shape), `route/CampsiteRoutesTest.kt` (the JSON of one fused day), `route/api/pois/BulkAvailabilityRoutesTest.kt` (single-cell days).

**Interfaces:** the spec's Wire block verbatim. `PoiAvailabilityFusion.fuse(slice: PoiAvailabilitySlice, pollingSupported: (Campsite) -> Boolean, earliestDate: LocalDate): FusedWindow(state, season, cache, days)`.

- [ ] Steps: failing fusion test → DTOs → fusion → controller → mapper/bulk compile fixes → gate → commit `refactor(availability): fused campground days with backend-owned rollup and watchable cells`.

---

### Task 2: Add-to-cart state, latest date on POI detail, cadence null (#737)

**Files:**
- Create: `model/api/AddToCartCapabilityDto.kt` with `enum class AddToCartState { READY("ready"), NO_CREDENTIALS("no_credentials"), SIGNED_OUT("signed_out"), UNSUPPORTED("unsupported") }`.
- Modify: `model/api/AvailabilityWatchCapabilitiesDto.kt` (`add_to_cart`), `service/availability/WatchCapabilityService.kt` (`addToCartState(campsites, requester)` per the spec's rule; `capabilitiesFor` fills it), `model/api/poi/PoiCategoryDetailSchema.kt` and `service/poi/CampgroundService.kt` (`latest_date` from the date context, next to `earliest_date`), `describeApi` text where capabilities are described.
- Test: `WatchCapabilityServiceTest` (four states: unsupported scope; supported scope + no requester → `signed_out`; requester without credentials → `no_credentials`; with credentials → `ready`, and `trigger_kinds` contains `atc` only in the last), `PoiServiceTest` (`latest_date` = earliest + horizon), `route/AvailabilityWatchRoutesTest` (a create without `cadence_sec` stores NULL; extend the cadence resolution test in `DbAvailabilityTargetResolverTest` so a NULL watch cadence falls to the POI override and then the global default).

- [ ] Steps: failing capability test → enum + DTO → service → POI detail → gate → commit `feat(availability): add-to-cart state and latest_date served; cadence left to the resolver`.

---

### Task 3: Frontend renders the fused week (#737)

**Files:**
- Modify: `frontend/src/api/availability-api.ts` (mirror the new response: `AvailabilityCell`, `AvailabilityDay {date,status,watchable,cells}`, `AddToCartState`, `WatchCapabilities.add_to_cart`, `PoiCampsitesAvailabilityResponse {…, latest_date, state, season, cache, days}`), `features/availability/useWeekAvailability.ts` (no fusion; the response is the week), `SiteMatrix.tsx` (cells from `day.cells[id]`; watch button when `cell.watchable` and the client watch-list state allows; no `isWatchableKind`), `DayDetail.tsx` (`day.watchable` replaces the status rule; counts from `cells`), `SiteList.tsx` and `lib/day-fields.ts` (`availableCount`/`campsiteCount`/`availableCampsiteIds` over `cells`, or inline them and delete the module), `matrix-rows.ts` (`cellState(row, day)` = `day.cells[id]?.status ?? 'unknown'`; delete `availabilityIndex`, `isWatchableKind`, `WATCHABLE_KINDS`; the available-first sort reads `cells`), `AvailabilityWeek.tsx` (`maxDate` = `latest_date`; delete `CALENDAR_MAX_DAYS_OUT`; `cart` = `capabilities.add_to_cart.state`; delete the `cartGate` call), `CellBookPopover.tsx` and `domain/watch/WatchEditor.tsx` (gate keyed on `add_to_cart.state`), `lib/watch-windows.ts` (delete `cartGate`, `CartGate`, `supportsAddToCart`, `scopeSupportsAddToCart`, `DEFAULT_WATCH_CADENCE_SEC`), `useWatches.ts` (no `cadence_sec`).
- Delete: `features/availability/fuse.ts` and `fuse.test.ts` (the rollup cases moved to the backend in Task 1).
- Tests: `matrix-rows.test.ts`, `AvailabilityWeek.test.tsx` (response builder → fused shape), `DayDetail`/`SiteMatrix` tests, `watch-windows.test.ts`, `useWatches` tests (payload has no `cadence_sec`), `CampgroundPanel.test.tsx` stub, `AvailabilityTable.stories.tsx` and any story feeding the old shape; `group.tsx` untouched.
- Exit criterion: `grep -rn 'rollupStatus\|fuseDay\|available_campsite_ids\|campsite_statuses\|isWatchableKind\|cartGate\|DEFAULT_WATCH_CADENCE_SEC\|CALENDAR_MAX_DAYS_OUT\|supportsAddToCart' frontend/src` returns nothing.

- [ ] Steps: rewrite tests to the fused shape → implement → grep → gate → commit `refactor(frontend): render the fused availability week the API serves`.

---

### Task 4: Docs (#737)

**Files:** `docs/reservation-providers.md` (availability wire: fused days, cells, watchable, add-to-cart state, latest_date; cadence rungs now reachable), `docs/frontend-components.md` (the availability grid renders `cells`; no rollup or gate logic in the browser), `docs/backend-architecture.md` if it lists the availability DTOs.

- [ ] Steps: edit → commit `docs: availability truth is served, not derived`.
