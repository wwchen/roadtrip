# Availability read model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the availability read path eight units with one job each, by giving the port a facts-only return type and introducing the `CampgroundWindow` read model every answer is derived from.

**Architecture:** The port returns `CampgroundObservations` (a campground ref and keyed observations). `AvailabilityLoader` owns the store and returns cells. `AvailabilityReadPolicy` resolves windows, provider and TTL. `CampgroundAvailabilityReader` puts those together into `CampgroundWindow`, which carries per-campsite streams built once. Fusion, stay summary, per-campsite envelopes and the season block become pure functions over that model.

**Tech Stack:** Kotlin, Ktor, jOOQ over Postgres, JUnit 5 with the shared Testcontainers DB (`SharedDbTest`).

**Spec:** `docs/superpowers/specs/2026-10-17-availability-read-path-design.md`

## Global Constraints

- This is PR 1 of three. PR 2 (repo read surface) and PR 3 (adapters) get their own plans after this lands.
- Behaviour-preserving except where a step says otherwise. `make test` green before each commit.
- The wire contract does not move: `make api-types` must produce no diff, and `:backend:contractLedgerCheck` must stay green.
- `org.jooq` appears only under `repo/`, `db/` and `di/InfraModule.kt` — `LayeringGuardTest` fails the build otherwise.
- No inline magic constants: numeric, string and duration literals at call sites become named `const val`.
- No code comments. Reasoning goes in the commit message.
- Renames change no test assertion. If a rename step changes an expectation, that is a behaviour change and gets its own commit with its own reasoning.
- `./gradlew :backend:test --tests '...'` needs `-x :backend:contractLedgerCheck`; the gate fails on filtered runs.

## Review Focus

Five conditions the spec implies that no task's happy path exercises; each has a test added to the task that owns the code.

- **A campground whose site-type filter matches no campsite.** Today the controller returns a slice with `batch = null`; the model needs an explicit answer (empty streams, no cache block, window and horizon still present). Task 6.
- **Some cells fresh, some stale.** Per-cell freshness means one stale cell fetches the whole window; `CampgroundWindow.cache.hit` must report false, not true-for-the-fresh-majority. Task 4.
- **Observations for a campsite outside `campsites`.** A fetch covers the campground, so the loader stores more than the read answers for; the model must not grow a stream for one. Task 1.
- **The same campsite named twice by the caller.** One stream, not two, and no change to the freshness decision. Task 1.
- **No provider claims the campground.** `latestDate` is null and the read must not throw on the way to saying so. Task 5.

---

### Task 1: `CampsiteStream` becomes a built-once type

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteStream.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilityFusion.kt` (delete its private `CampsiteStream`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteStreamTest.kt`

**Interfaces:**
- Consumes: `CampsiteDayObservation`, `AvailabilityStatus`, `campsiteWindowStatuses` (from `service/api/AvailabilityResponseMapper.kt`).
- Produces:
  ```kotlin
  internal data class CampsiteStream(val campsiteId: Long, val statuses: List<AvailabilityStatus>)

  internal fun streamsFor(
      campsites: List<Campsite>,
      window: ResolvedDateWindow,
      observations: List<CampsiteDayObservation>,
  ): List<CampsiteStream>
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a campsite with no observation is a stream of unknowns`()
@Test fun `statuses follow the window's dates in order`()
@Test fun `an observation outside the window is ignored`()
@Test fun `an observation for a campsite outside the list makes no stream`()
@Test fun `a campsite named twice makes one stream`()
@Test fun `the latest observation for a date wins`()
```
Assertions: streams come back one per distinct campsite in ascending id order; `statuses.size == window day count`; an unobserved date is `UNKNOWN`.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.CampsiteStreamTest' -x :backend:contractLedgerCheck`
Expected: FAIL, unresolved reference `streamsFor`.

- [ ] **Step 3: Implement `CampsiteStream` and `streamsFor` in the new file**

`streamsFor` deduplicates campsites by id, sorts ascending, and delegates per campsite to `campsiteWindowStatuses`, which already drops out-of-window dates and takes the latest observation per date.

- [ ] **Step 4: Run to verify they pass**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteStream.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilityFusion.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteStreamTest.kt
git commit -m "refactor(availability): one type for a campsite's window of statuses"
```

---

### Task 2: fusion and stay summary consume streams

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilityFusion.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/StaySummary.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilityFusionTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/StaySummaryTest.kt`

**Interfaces:**
- Consumes: `streamsFor`, `CampsiteStream` (Task 1).
- Produces: `fuseWindow(streams, window, earliestDate, pollingSupported, cache, season: AvailabilitySeasonBlock?): FusedWindow` and `staySummaryOf(streams, checkedSites, cache): BulkPoiSummaryDto` — both pure, neither taking a batch.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `fusion and the stay summary agree on closed for season for one set of streams`()
```
Both must classify the same streams the same way; today each re-derives it.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Change both signatures to take `List<CampsiteStream>`**

Delete the `groupBy { it.campsiteId }` and `campsiteWindowStatuses` calls in both; the callers pass streams from Task 1.

- [ ] **Step 4: Run both test classes to verify they pass**

Existing assertions must not change — only how the fixtures build their input.

- [ ] **Step 5: Commit**

---

### Task 3: the port returns facts

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/CampgroundObservations.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/AvailabilityObservationBatch.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProvider.kt`, all five adapters in that package, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/FailoverAvailabilityFetcher.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProviderContractTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/FailoverAvailabilityFetcherTest.kt` (the error and cooldown paths keep their assertions; only the result's shape changes)

**Interfaces:**
- Produces:
  ```kotlin
  data class CampgroundObservations(
      val campgroundRef: BookingProviderRef?,
      val observations: List<CampsiteDayObservation>,
  )

  suspend fun AvailabilityProvider.availability(
      campground: Campground,
      campsites: List<Campsite>,
      startDate: LocalDate,
      endDate: LocalDate,
  ): CampgroundObservations
  ```
  `FailoverAvailabilityFetcher.FailoverResult.observations` replaces `.batch`.

- [ ] **Step 1: Write the failing test**

In the contract test, assert the port's return type carries no window, provider string or cache block: `CampgroundObservations` has exactly `campgroundRef` and `observations`. Keep the existing contract cases (every campsite given gets keyed cells; nothing unkeyed).

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Introduce `CampgroundObservations` and change the port and five adapters to return it**

Each adapter drops `provider = "..."`, `startDate`, `endDate`, `cacheBlock = directFetchCacheBlock()` and `campsiteId`; `scope` becomes `campgroundRef`. Delete `directFetchCacheBlock` from every adapter.

- [ ] **Step 4: Run the provider package's tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.provider.*' -x :backend:contractLedgerCheck`

- [ ] **Step 5: Commit**

---

### Task 4: the loader returns cells

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt`

**Interfaces:**
- Consumes: `CampgroundObservations` (Task 3).
- Produces:
  ```kotlin
  data class LoadedCells(
      val cells: List<CampsiteDayObservation>,
      val hit: Boolean,
      val ageSeconds: Long,
      val ttlSeconds: Long,
  )

  suspend fun load(request: Request, fetch: suspend () -> CampgroundObservations): LoadedCells
  ```
  `batchFromLatest`, `sliceToTarget` and `metadataFromBatch` are deleted.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a window with one stale cell reports a miss, not a hit`()
@Test fun `the loader answers with cells, not a shaped response`()
```
The first is the Review Focus case: seed the window fresh, age one cell past the cutoff, assert `hit == false` and that the fetch ran once.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Change `loadOrFetch` to `load` returning `LoadedCells`**

Cell storage and the UNKNOWN back-fill are unchanged; only the return shape changes. The window, provider id and campsite identity leave the loader entirely.

- [ ] **Step 4: Run the loader tests to verify they pass**

- [ ] **Step 5: Commit**

---

### Task 5: the policy comes out of the service

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityReadPolicy.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityReadPolicyTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal data class AvailabilityRead(
      val provider: AvailabilityProvider,
      val windows: AvailabilityWindows,
      val freshAtOrAfter: Instant,
      val latestDate: LocalDate?,
  )

  internal class AvailabilityReadPolicy(
      private val providers: List<AvailabilityProvider>,
      private val dateResolver: AvailabilityDateResolver,
      private val bookingHorizons: BookingHorizonResolver,
      private val clock: Clock,
      private val snapshotFreshnessTtl: (AvailabilityProvider) -> Duration,
  ) {
      fun read(campground: Campground, dateContext: PoiDateContext, startDate: LocalDate?, endDate: LocalDate?, freshAtOrAfter: Instant?): AvailabilityRead
      fun supportingProviders(campground: Campground): List<AvailabilityProvider>
      fun pollingSupported(campground: Campground): Boolean
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the target window defaults to seven days from the earliest bookable date`()
@Test fun `the fetch window is the vendor's widest poll window`()
@Test fun `a window past the booking horizon is a bad date window`()
@Test fun `an unclaimed campground has no latest date and does not throw`()
```
The last is the Review Focus case. No DB: the policy is pure given its collaborators.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement `AvailabilityReadPolicy`, moving the window and provider resolution out of `CampsiteAvailabilityService` unchanged**

- [ ] **Step 4: Run the policy tests to verify they pass**

- [ ] **Step 5: Commit**

---

### Task 6: the reader and the read model

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampgroundWindow.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampgroundAvailabilityReader.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilitySlice.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityController.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampgroundCellCacheTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityControllerSliceTest.kt`

**Interfaces:**
- Consumes: `streamsFor` (1), `CampgroundObservations` (3), `LoadedCells` (4), `AvailabilityReadPolicy` (5).
- Produces: `CampgroundWindow` exactly as the spec's read model block defines it, and
  ```kotlin
  internal class CampgroundAvailabilityReader(
      private val policy: AvailabilityReadPolicy,
      private val loader: AvailabilityLoader,
      private val failoverFetcher: FailoverAvailabilityFetcher,
      private val availabilityRepo: AvailabilityRepo?,
  ) {
      suspend fun read(poiId: Long, campground: Campground, campgroundCampsites: List<Campsite>, campsites: List<Campsite>, dateContext: PoiDateContext, startDate: LocalDate?, endDate: LocalDate?, freshAtOrAfter: Instant?): CampgroundWindow
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a filter matching no campsite still carries the window and the horizon`()
@Test fun `the model's streams cover the campsites the read answers for, and no others`()
```
Plus the two existing payoff tests, re-pointed at the model: a filtered read then an unfiltered one is one provider call, and a closed window's reopen date is the same from the store as from the fetch.

- [ ] **Step 2: Run to verify they fail**

- [ ] **Step 3: Implement `CampgroundWindow` and `CampgroundAvailabilityReader`; move the reopen-date lookup into the reader**

The reader asks the policy, calls the loader with the fetcher as its fetch, builds streams once, looks up reopen dates for the campsites whose stream classifies `CLOSED_FOR_SEASON`, and returns the model. The controller resolves the POI, campsites and filter, then passes through. Wire both in `ServiceModule`.

- [ ] **Step 4: Run the availability service tests to verify they pass**

- [ ] **Step 5: Commit**

---

### Task 7: the season block and the envelopes become pure

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseMapper.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilityFusion.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampgroundWindowResponses.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampgroundWindowResponsesTest.kt`

**Interfaces:**
- Consumes: `CampgroundWindow` (6).
- Produces: `internal fun CampgroundWindow.seasonBlock(): AvailabilitySeasonBlock?`, `internal fun CampgroundWindow.envelopes(): List<CampsiteEnvelope>`, both pure.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `the season block ignores campsites the window does not answer for`()
```
The guard that exists today in `PoiAvailabilityFusionTest`, moved to the model's own function.

- [ ] **Step 2: Run to verify it fails**

- [ ] **Step 3: Implement both as extension functions over the model, delete the service-side derivation, and have the controller pass `window.seasonBlock()` as `fuseWindow`'s `season`**

- [ ] **Step 4: Run to verify it passes**

- [ ] **Step 5: Commit**

---

### Task 8: the renames

**Files:**
- Modify: every file carrying an old name — the spec's vocabulary table is the list.
- Test: no test assertion changes.

- [ ] **Step 1: Apply the renames from the spec's vocabulary table**

`poiAvailabilitySlice` → `campgroundWindow`; `parentRef`/`scope`/`claimedRef` → `campgroundRef`; `lastReservableCount` and `observationsForReservable` → campsite wording; drop "catalog" from `AspiraCatalogCampsite` and any `catalogSites` locals. `AvailabilityCell` and `AvailabilityRepo.CurrentCell` are PR 2's, not this one's.

- [ ] **Step 2: Run the full suite to verify nothing changed**

Run: `make test`
Expected: PASS, and `make api-types` produces no diff.

- [ ] **Step 3: Commit**

If any assertion had to change, revert that file from this commit and raise it as a behaviour change with its own reasoning.

---

## Done

- `make test` green, `:backend:contractLedgerCheck` green, `make api-types` no diff.
- `CampsiteAvailabilityService` and `PoiAvailabilitySlice` no longer exist.
- `grep -rn "AvailabilityObservationBatch" backend/src` returns nothing.
- One PR against `master` (or against #803's branch if that has not merged), with the costs and any behaviour change named in the body.
