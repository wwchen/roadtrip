# Bulk POI Availability Endpoint (P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a synchronous `POST /api/pois/availability/bulk` that returns each requested POI's campsite availability, with campsites filtered to a minimum consecutive-night run and sorted by that run descending.

**Architecture:** The controller fans out over the *existing* per-POI availability use case under a bounded semaphore — no second read path. `CampsiteAvailabilityController.availabilityForPoi` is split so both the detail endpoint and bulk share one implementation. Freshness moves from a relative `ttl: Duration` to an absolute `freshAtOrAfter: Instant` so every POI in one fan-out is measured against the same cutoff.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, Koin DI, jOOQ, JUnit 5 + `kotlin.test` assertions, `kotlinx.coroutines` (`Semaphore`, `coroutineScope`, `withTimeout`).

**Spec:** `docs/superpowers/specs/2026-08-23-bulk-availability-design.md`

## Global Constraints

- Layering is `routes -> service -> repo`. Routes parse HTTP and return DTOs; they add no business orchestration and no repo access. (`AGENTS.md`)
- No inline magic constants. Every numeric, string, and duration literal at a call site is a named `const val` or env/YAML-driven config. (`AGENTS.md`)
- Prefer typed `@Serializable` DTOs over hand-built JSON in routes. (`AGENTS.md`)
- Comments are short and rare. Do not add explanatory comments except where the plan shows one verbatim.
- Never edit an already-applied Flyway migration. P1 adds no migration.
- A "night" is a date cell whose `AvailabilityStatus.isOnlineBookable` is true — `AVAILABLE` only. `FIRST_COME` does not count.
- Response POI entries are 1:1 with the request's `poi_ids`, preserving request order.
- Partial failure returns HTTP 200 with a per-POI `error`.
- Package root is `ca.floo.roadtrip`. Backend source root is `backend/src/main/kotlin/ca/floo/roadtrip`, tests `backend/src/test/kotlin/ca/floo/roadtrip`.
- Run tests with `./gradlew :backend:test --tests '<pattern>'` from the repo root.

---

## File Structure

**Created:**
- `service/availability/AvailabilityRunLengths.kt` — pure run-length computation.
- `service/availability/AvailabilityErrorCodes.kt` — `AvailabilityProviderError` → wire error code, shared by bulk and the existing route mapper.
- `service/availability/PoiAvailabilitySlice.kt` — the shared per-POI result both endpoints map from.
- `service/availability/BulkAvailabilityController.kt` — fan-out, ranking, per-POI error capture.
- `config/BulkAvailabilityConfig.kt` — caps and timings.
- `model/api/BulkAvailabilityRequestDto.kt`, `model/api/BulkAvailabilityResponseDto.kt` — wire shapes.
- `route/api/pois/BulkAvailabilityRoutes.kt` — HTTP shell.

**Modified:**
- `service/api/AvailabilityLoader.kt` — `Request.ttl` → `Request.freshAtOrAfter`.
- `service/availability/AvailabilityFreshness.kt` — add `isFreshAsOf`; `isFresh` delegates.
- `service/availability/CampsiteAvailabilityService.kt` — accept `freshAtOrAfter`, add a `Clock`.
- `service/availability/CampsiteAvailabilityController.kt` — extract `poiAvailabilitySlice`.
- `model/api/AvailabilityResponseDto.kt` — add optional `longest_run_nights`.
- `config/AvailabilityConfig.kt` — add `bulk` section.
- `backend/src/main/resources/application.yaml` — `availability.bulk` block.
- `route/api/pois/CampsiteRoutes.kt` — `mapProviderError` delegates to `AvailabilityErrorCodes`.
- `di/RouteModule.kt` — construct and register the new controller and route.

---

### Task 1: Consecutive-night run lengths

A pure function with no dependencies. Everything else builds on its signature.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengths.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengthsTest.kt`

**Interfaces:**
- Consumes: `CampsiteDayObservation` (`model/availability/CampsiteDayObservation.kt`), `AvailabilityStatus.isOnlineBookable`.
- Produces: `fun longestRunNights(observations: List<CampsiteDayObservation>): Int` in package `ca.floo.roadtrip.service.availability`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengthsTest.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

private val observedAt: Instant = Instant.parse("2026-08-23T12:00:00Z")
private val day0: LocalDate = LocalDate.of(2026, 9, 4)

private fun observations(vararg statuses: AvailabilityStatus): List<CampsiteDayObservation> =
    statuses.mapIndexed { index, status ->
        CampsiteDayObservation(
            campsiteId = 1L,
            date = day0.plusDays(index.toLong()),
            observedAt = observedAt,
            status = status,
        )
    }

class AvailabilityRunLengthsTest {
    @Test
    fun `empty window has no run`() {
        assertEquals(0, longestRunNights(emptyList()))
    }

    @Test
    fun `all available is one full run`() {
        val days = observations(*Array(5) { AvailabilityStatus.AVAILABLE })
        assertEquals(5, longestRunNights(days))
    }

    @Test
    fun `all reserved has no run`() {
        val days = observations(*Array(5) { AvailabilityStatus.RESERVED })
        assertEquals(0, longestRunNights(days))
    }

    @Test
    fun `run at the leading boundary counts`() {
        val days =
            observations(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `run at the trailing boundary counts`() {
        val days =
            observations(
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `longest of several runs wins`() {
        val days =
            observations(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(3, longestRunNights(days))
    }

    @Test
    fun `first come does not count toward a run`() {
        val days =
            observations(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.FIRST_COME,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(1, longestRunNights(days))
    }

    @Test
    fun `a missing date breaks a run`() {
        val days =
            listOf(
                CampsiteDayObservation(1L, day0, observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0.plusDays(2), observedAt, AvailabilityStatus.AVAILABLE),
            )
        assertEquals(1, longestRunNights(days))
    }

    @Test
    fun `unordered input is scanned in date order`() {
        val days =
            listOf(
                CampsiteDayObservation(1L, day0.plusDays(2), observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0, observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0.plusDays(1), observedAt, AvailabilityStatus.AVAILABLE),
            )
        assertEquals(3, longestRunNights(days))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityRunLengthsTest'`
Expected: FAIL — compilation error, `Unresolved reference: longestRunNights`.

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengths.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CampsiteDayObservation

/**
 * Longest run of consecutive dates that are online-bookable. Input is sorted
 * before scanning, and a gap in dates breaks a run even when both sides are
 * bookable.
 */
fun longestRunNights(observations: List<CampsiteDayObservation>): Int {
    val bookable =
        observations
            .filter { it.status.isOnlineBookable }
            .map { it.date }
            .distinct()
            .sorted()
    if (bookable.isEmpty()) return 0

    var longest = 1
    var current = 1
    for (index in 1 until bookable.size) {
        current = if (bookable[index] == bookable[index - 1].plusDays(1)) current + 1 else 1
        if (current > longest) longest = current
    }
    return longest
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityRunLengthsTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengths.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengthsTest.kt
git commit -m "feat(availability): add consecutive-night run length helper"
```

---

### Task 2: Absolute freshness (`freshAtOrAfter`)

Replace the relative TTL on the loader request with an absolute instant, so a fan-out measures every POI against one cutoff. `AvailabilityLoader.loadOrFetch` has exactly one production call site (`CampsiteAvailabilityService.kt:64`).

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt:42,57,87,143`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt:25-32,64-72`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshnessTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt:46-53`

**Interfaces:**
- Produces: `fun isFreshAsOf(observedAts: List<Instant>, freshAtOrAfter: Instant): Boolean`
- Produces: `AvailabilityLoader.Request(metadata, targets, startDate, endDate, freshAtOrAfter: Instant, runId: Long? = null)` — the `ttl: Duration` parameter is gone.
- Produces: `CampsiteAvailabilityService.fetchAvailability(campground, campsites, startDate, endDate, dateContext, freshAtOrAfter: Instant? = null): CampsiteAvailabilityResult` — null means "derive from this provider's default TTL".
- Produces: `CampsiteAvailabilityService(..., clock: Clock = Clock.systemUTC())`.

- [ ] **Step 1: Write the failing test**

Append to `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshnessTest.kt` (inside the existing test class):

```kotlin
    @Test
    fun `isFreshAsOf accepts observations at or after the cutoff`() {
        val cutoff = Instant.parse("2026-08-23T12:00:00Z")
        assertTrue(isFreshAsOf(listOf(cutoff, cutoff.plusSeconds(1)), cutoff))
    }

    @Test
    fun `isFreshAsOf rejects any observation before the cutoff`() {
        val cutoff = Instant.parse("2026-08-23T12:00:00Z")
        assertFalse(isFreshAsOf(listOf(cutoff, cutoff.minusSeconds(1)), cutoff))
    }

    @Test
    fun `isFreshAsOf treats an empty list as fresh`() {
        assertTrue(isFreshAsOf(emptyList(), Instant.parse("2026-08-23T12:00:00Z")))
    }
```

Ensure these imports exist at the top of that file: `java.time.Instant`, `kotlin.test.assertTrue`, `kotlin.test.assertFalse`, `org.junit.jupiter.api.Test`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityFreshnessTest'`
Expected: FAIL — `Unresolved reference: isFreshAsOf`.

- [ ] **Step 3: Add `isFreshAsOf` and delegate `isFresh`**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt`, replace the body of `isFresh` and add the new function:

```kotlin
/** Fresh when every observation was seen at or after [freshAtOrAfter]. Empty = vacuously fresh. */
fun isFreshAsOf(
    observedAts: List<Instant>,
    freshAtOrAfter: Instant,
): Boolean = observedAts.all { !it.isBefore(freshAtOrAfter) }

/** Fresh when every observation was seen within [ttl] of [now]. Empty = vacuously fresh. */
fun isFresh(
    observedAts: List<Instant>,
    now: Instant,
    ttl: Duration,
): Boolean = isFreshAsOf(observedAts, now.minus(ttl))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityFreshnessTest'`
Expected: PASS. The pre-existing `isFresh` tests still pass — that is the parity check.

- [ ] **Step 5: Change `AvailabilityLoader.Request`**

In `service/api/AvailabilityLoader.kt`:

Replace line 42 `val ttl: Duration,` with:

```kotlin
        val freshAtOrAfter: Instant,
```

Replace the freshness check on line 57:

```kotlin
            isFreshAsOf(cached.map { it.observedAt.toInstant() }, request.freshAtOrAfter)
```

Replace the cache block on line 87 (inside `sliceToTarget`):

```kotlin
    private fun sliceToTarget(
        fetched: AvailabilityObservationBatch,
        request: Request,
    ): AvailabilityObservationBatch {
        val targetDates = datesInWindow(request.startDate, request.endDate).toSet()
        return fetched.copy(
            startDate = request.startDate,
            endDate = request.endDate,
            observations = fetched.observations.filter { it.date in targetDates },
            cacheBlock =
                AvailabilityCacheBlock(
                    hit = false,
                    ageSeconds = 0,
                    ttlSeconds = effectiveTtlSeconds(request),
                ),
        )
    }
```

Replace the cache block on line 143 (inside `batchFromLatest`):

```kotlin
            cacheBlock =
                AvailabilityCacheBlock(
                    hit = hit,
                    ageSeconds = maxAgeSeconds(rows, now),
                    ttlSeconds = effectiveTtlSeconds(request),
                ),
```

Add this private helper beside `maxAgeSeconds`:

```kotlin
    private fun effectiveTtlSeconds(request: Request): Long =
        Duration.between(request.freshAtOrAfter, Instant.now(clock)).seconds.coerceAtLeast(0)
```

Update imports: `isFresh` → `isFreshAsOf` from `ca.floo.roadtrip.service.availability`. Keep the `java.time.Duration` import (still used by `effectiveTtlSeconds`).

- [ ] **Step 6: Change `CampsiteAvailabilityService`**

In `service/availability/CampsiteAvailabilityService.kt`, add a clock to the constructor:

```kotlin
internal class CampsiteAvailabilityService(
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val failoverFetcher: FailoverAvailabilityFetcher,
    availabilityRepo: AvailabilityRepo? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val snapshotFreshnessTtl: (provider: AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
) {
```

Add the parameter to `fetchAvailability`:

```kotlin
    suspend fun fetchAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        dateContext: PoiDateContext,
        freshAtOrAfter: Instant? = null,
    ): CampsiteAvailabilityResult {
```

Replace the `ttl` argument in the `AvailabilityLoader.Request` construction (line ~70):

```kotlin
                    freshAtOrAfter = freshAtOrAfter ?: Instant.now(clock).minus(snapshotFreshnessTtl(provider)),
```

Add imports `java.time.Clock` and `java.time.Instant`.

- [ ] **Step 7: Update the loader test fixture**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt`, change the `request()` helper (lines 46-53) so the cutoff is derived from the same fixed clock the loader uses:

```kotlin
    private fun request(targets: List<Long> = listOf(siteA, siteB)) =
        AvailabilityLoader.Request(
            metadata = AvailabilityLoader.Metadata(provider = PROVIDER),
            targets = targets.map { AvailabilityLoader.CampsiteTarget(dbId = it) },
            startDate = windowStart,
            endDate = windowEnd,
            freshAtOrAfter = fixedNow.minus(snapshotTtl),
        )
```

- [ ] **Step 8: Run the full availability suite**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.*'`
Expected: PASS. `AvailabilityLoaderTest` and `CampsiteAvailabilityServiceTest` must pass unchanged in behavior — this is the parity gate for the freshness migration.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshness.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityFreshnessTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt
git commit -m "refactor(availability): express read freshness as an absolute instant"
```

---

### Task 3: Extract `poiAvailabilitySlice`

`availabilityForPoi` currently also resolves `watchCapabilities`, which bulk does not need. Split the shared body out rather than adding a boolean flag.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilitySlice.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityController.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityControllerSliceTest.kt`

**Interfaces:**
- Consumes: `CampsiteAvailabilityService.fetchAvailability(..., freshAtOrAfter)` from Task 2.
- Produces: `internal data class PoiAvailabilitySlice(poiId: Long, startDate: LocalDate, endDate: LocalDate, allCampsites: List<Campsite>, campsites: List<Campsite>, batch: AvailabilityObservationBatch?)`
- Produces: `CampsiteAvailabilityController.poiAvailabilitySlice(poiId: Long, siteTypes: List<String>, startDate: LocalDate?, endDate: LocalDate?, freshAtOrAfter: Instant? = null): PoiAvailabilitySlice` — throws `AvailabilityServiceError` / `AvailabilityProviderError` exactly as `availabilityForPoi` does today.
- `availabilityForPoi` keeps its current signature and return type.

- [ ] **Step 1: Create the slice type**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilitySlice.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.domain.Campsite
import java.time.LocalDate

/**
 * One POI's resolved availability read, before it is shaped for a specific
 * endpoint. [batch] is null when the POI has no campsites matching the
 * requested site types, in which case only the window is meaningful.
 */
internal data class PoiAvailabilitySlice(
    val poiId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val batch: AvailabilityObservationBatch?,
)
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityControllerSliceTest.kt`. Mirror the construction style used by the existing `CampsiteAvailabilityServiceTest` in the same package for building fakes; the assertions are:

```kotlin
package ca.floo.roadtrip.service.availability

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CampsiteAvailabilityControllerSliceTest {
    @Test
    fun `slice carries the resolved window and the filtered campsites`() =
        runBlocking {
            val controller = sliceTestController(siteTypes = listOf("tent", "rv"))

            val slice =
                controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf("tent"),
                    startDate = LocalDate.of(2026, 9, 4),
                    endDate = LocalDate.of(2026, 9, 11),
                )

            assertEquals(LocalDate.of(2026, 9, 4), slice.startDate)
            assertEquals(LocalDate.of(2026, 9, 11), slice.endDate)
            assertEquals(2, slice.allCampsites.size)
            assertEquals(1, slice.campsites.size)
            assertNotNull(slice.batch)
        }

    @Test
    fun `slice has a null batch when no campsite matches the site type filter`() =
        runBlocking {
            val controller = sliceTestController(siteTypes = listOf("tent"))

            val slice =
                controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf("cabin"),
                    startDate = LocalDate.of(2026, 9, 4),
                    endDate = LocalDate.of(2026, 9, 11),
                )

            assertEquals(0, slice.campsites.size)
            assertNull(slice.batch)
        }
}
```

Write `sliceTestController(siteTypes: List<String>)` and `TEST_POI_ID` as private helpers at the bottom of the same file, constructing `CampsiteAvailabilityController` with the same fake repos/services `CampsiteAvailabilityServiceTest` already builds. Read that file first and reuse its fakes rather than inventing new ones.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.CampsiteAvailabilityControllerSliceTest'`
Expected: FAIL — `Unresolved reference: poiAvailabilitySlice`.

- [ ] **Step 4: Extract the method**

In `service/availability/CampsiteAvailabilityController.kt`, replace `availabilityForPoi` with these two methods. Keep the existing KDoc on `availabilityForPoi`.

```kotlin
    /**
     * One POI's resolved availability, shared by the detail endpoint and the
     * bulk endpoint.
     *
     * @throws AvailabilityServiceError on unknown POI/campground or a bad date window.
     * @throws ca.floo.roadtrip.model.availability.AvailabilityProviderError on upstream failure.
     */
    suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<String>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant? = null,
    ): PoiAvailabilitySlice {
        val campground = campgroundRepo.findByPoi(poiId) ?: throw AvailabilityServiceError.NotFound
        val allCampsites = campsitesRepo.findByCampground(campground.id)
        val campsites = allCampsites.filterBySiteTypes(siteTypes)
        val dateContext = dateResolver.contextForPoi(poiId)

        if (campsites.isEmpty()) {
            val window =
                dateResolver.resolveWindow(
                    startDate = startDate,
                    endDate = endDate,
                    context = dateContext,
                    bookingHorizonDays = EMPTY_WINDOW_HORIZON_DAYS,
                    maxDays = EMPTY_WINDOW_MAX_DAYS,
                    defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
                )
            return PoiAvailabilitySlice(
                poiId = poiId,
                startDate = window.startDate,
                endDate = window.endDate,
                allCampsites = allCampsites,
                campsites = emptyList(),
                batch = null,
            )
        }

        val result =
            availabilityService.fetchAvailability(
                campground = campground,
                campsites = campsites,
                startDate = startDate,
                endDate = endDate,
                dateContext = dateContext,
                freshAtOrAfter = freshAtOrAfter,
            )

        return PoiAvailabilitySlice(
            poiId = poiId,
            startDate = result.startDate,
            endDate = result.endDate,
            allCampsites = allCampsites,
            campsites = campsites,
            batch = result.batch,
        )
    }

    suspend fun availabilityForPoi(
        poiId: Long,
        siteTypes: List<String>,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): PoiCampsitesAvailabilityResponseDto {
        val slice = poiAvailabilitySlice(poiId, siteTypes, startDate, endDate)
        val watchCaps = watchCapabilityService.capabilitiesFor(slice.allCampsites)
        val batch = slice.batch

        val perCampsite =
            if (batch == null) {
                emptyList()
            } else {
                slice.campsites.map { campsite ->
                    availabilityResponseFromObservations(
                        batch.copy(
                            observations = batch.observations.filter { it.campsiteId == campsite.id },
                            campsiteId = campsite.id,
                            startDate = slice.startDate,
                            endDate = slice.endDate,
                        ),
                    )
                }
            }

        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            watchCapabilities = watchCaps,
            campsites = perCampsite,
        )
    }
```

Add the `java.time.Instant` import.

Note the one behavior difference to verify: the original computed `watchCaps` before filtering and before resolving the window. It still uses `allCampsites`, so the value is identical; only the ordering of the calls changed.

- [ ] **Step 5: Run the slice test and the regression suite**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.*' --tests 'ca.floo.roadtrip.route.*'`
Expected: PASS. Every pre-existing single-POI availability test passes unchanged — that is the gate on this extraction.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PoiAvailabilitySlice.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityController.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityControllerSliceTest.kt
git commit -m "refactor(availability): share one per-POI slice between read endpoints"
```

---

### Task 4: Bulk configuration

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfig.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AvailabilityConfig.kt`
- Modify: `backend/src/main/resources/application.yaml`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfigTest.kt`

**Interfaces:**
- Produces: `BulkAvailabilityConfig(maxPois: Int, fanOutConcurrency: Int, perPoiTimeout: Duration, tolerance: Duration, ipRateLimitPerMinute: Int)` with `companion object { val default: BulkAvailabilityConfig; fun fromConfig(config: ConfigSection): BulkAvailabilityConfig }`.
- Produces: `AvailabilityConfig.bulk: BulkAvailabilityConfig`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfigTest.kt`:

```kotlin
package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BulkAvailabilityConfigTest {
    @Test
    fun `defaults are used when the section is absent`() {
        val config = BulkAvailabilityConfig.default
        assertEquals(50, config.maxPois)
        assertEquals(8, config.fanOutConcurrency)
        assertEquals(Duration.ofSeconds(20), config.perPoiTimeout)
        assertEquals(Duration.ofHours(2), config.tolerance)
        assertEquals(10, config.ipRateLimitPerMinute)
    }

    @Test
    fun `max pois must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 0,
                fanOutConcurrency = 8,
                perPoiTimeout = Duration.ofSeconds(20),
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }

    @Test
    fun `fan out concurrency must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 50,
                fanOutConcurrency = 0,
                perPoiTimeout = Duration.ofSeconds(20),
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }

    @Test
    fun `per poi timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 50,
                fanOutConcurrency = 8,
                perPoiTimeout = Duration.ZERO,
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.BulkAvailabilityConfigTest'`
Expected: FAIL — `Unresolved reference: BulkAvailabilityConfig`.

- [ ] **Step 3: Write the config class**

Create `backend/src/main/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfig.kt`:

```kotlin
package ca.floo.roadtrip.config

import java.time.Duration

/**
 * Caps and timings for the bulk availability read. A bulk scan is the one read
 * that can turn a single request into many upstream calls, so every bound here
 * is operationally tunable.
 */
data class BulkAvailabilityConfig(
    val maxPois: Int,
    val fanOutConcurrency: Int,
    val perPoiTimeout: Duration,
    val tolerance: Duration,
    val ipRateLimitPerMinute: Int,
) {
    init {
        require(maxPois >= 1) { "bulk max-pois must be >= 1 (got $maxPois)" }
        require(fanOutConcurrency >= 1) { "bulk fan-out-concurrency must be >= 1 (got $fanOutConcurrency)" }
        require(!perPoiTimeout.isZero && !perPoiTimeout.isNegative) {
            "bulk per-poi-timeout must be positive (got $perPoiTimeout)"
        }
        require(!tolerance.isNegative) { "bulk tolerance must not be negative (got $tolerance)" }
        require(ipRateLimitPerMinute >= 1) {
            "bulk ip-rate-limit-per-minute must be >= 1 (got $ipRateLimitPerMinute)"
        }
    }

    companion object {
        private const val DEFAULT_MAX_POIS = 50
        private const val DEFAULT_FAN_OUT_CONCURRENCY = 8
        private const val DEFAULT_PER_POI_TIMEOUT_SEC = 20L
        private const val DEFAULT_TOLERANCE_HOURS = 2L
        private const val DEFAULT_IP_RATE_LIMIT_PER_MINUTE = 10

        val default =
            BulkAvailabilityConfig(
                maxPois = DEFAULT_MAX_POIS,
                fanOutConcurrency = DEFAULT_FAN_OUT_CONCURRENCY,
                perPoiTimeout = Duration.ofSeconds(DEFAULT_PER_POI_TIMEOUT_SEC),
                tolerance = Duration.ofHours(DEFAULT_TOLERANCE_HOURS),
                ipRateLimitPerMinute = DEFAULT_IP_RATE_LIMIT_PER_MINUTE,
            )

        fun fromConfig(config: ConfigSection): BulkAvailabilityConfig =
            BulkAvailabilityConfig(
                maxPois = config.value("max-pois")?.toInt() ?: default.maxPois,
                fanOutConcurrency = config.value("fan-out-concurrency")?.toInt() ?: default.fanOutConcurrency,
                perPoiTimeout = config.duration("per-poi-timeout", default.perPoiTimeout),
                tolerance = config.duration("tolerance", default.tolerance),
                ipRateLimitPerMinute =
                    config.value("ip-rate-limit-per-minute")?.toInt()
                        ?: default.ipRateLimitPerMinute,
            )
    }
}
```

Before writing this, open `config/ConfigSection.kt` and confirm `value(...)`, `duration(name, default)`, and `section(...)` have these exact signatures — `AvailabilityPollerConfig` and `RouteConfig` both use them. Match whatever is actually there.

- [ ] **Step 4: Wire it into `AvailabilityConfig`**

In `config/AvailabilityConfig.kt`:

```kotlin
data class AvailabilityConfig(
    val forcePullCooldown: Duration,
    val providerCooldown: Duration,
    val poller: AvailabilityPollerConfig,
    val bulk: BulkAvailabilityConfig,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AvailabilityConfig =
            AvailabilityConfig(
                forcePullCooldown = config.requiredDuration("force-pull-cooldown"),
                providerCooldown = config.requiredDuration("provider-cooldown"),
                poller = AvailabilityPollerConfig.fromConfig(config.section("poller")),
                bulk = BulkAvailabilityConfig.fromConfig(config.section("bulk")),
            )
    }
}
```

- [ ] **Step 5: Add the YAML block**

In `backend/src/main/resources/application.yaml`, under `availability:` and after the `poller:` block (matching its indentation):

```yaml
    bulk:
      # A bulk scan is the one read that can turn one request into many
      # upstream calls, so each bound is stated here rather than compiled in.
      max-pois: 50
      fan-out-concurrency: 8
      per-poi-timeout: 20s
      tolerance: 2h
      ip-rate-limit-per-minute: 10
```

- [ ] **Step 6: Run the config tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.*' --tests 'ca.floo.roadtrip.RoadtripRuntimeConfigTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfig.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/AvailabilityConfig.kt \
        backend/src/main/resources/application.yaml \
        backend/src/test/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfigTest.kt
git commit -m "feat(config): add bulk availability caps and timings"
```

---

### Task 5: Bulk controller and DTOs

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityErrorCodes.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityRequestDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityResponseDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt` (`mapProviderError`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityControllerTest.kt`

**Interfaces:**
- Consumes: `longestRunNights` (Task 1), `PoiAvailabilitySlice` + `poiAvailabilitySlice` (Task 3), `BulkAvailabilityConfig` (Task 4).
- Produces: `fun availabilityErrorCode(e: AvailabilityProviderError): String`
- Produces: `BulkAvailabilityRequest(poiIds: List<Long>, startDate: LocalDate?, endDate: LocalDate?, minNights: Int, siteTypes: List<String>)`
- Produces: `BulkAvailabilityController.availabilityForPois(request: BulkAvailabilityRequest): BulkAvailabilityResponseDto`
- Produces: `AvailabilityResponseDto.longestRunNights: Int?` (new optional field, `@SerialName("longest_run_nights")`).

- [ ] **Step 1: Add the shared error-code function**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityErrorCodes.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityProviderError

/** Wire error code for a provider failure. Shared by the detail and bulk reads. */
fun availabilityErrorCode(e: AvailabilityProviderError): String =
    when (e) {
        is AvailabilityProviderError.RateLimited -> "rate_limited"
        is AvailabilityProviderError.UpstreamBlocked -> "upstream_blocked"
        is AvailabilityProviderError.UpstreamUnavailable -> "upstream_5xx"
        is AvailabilityProviderError.UpstreamUnreachable -> "upstream_unreachable"
        is AvailabilityProviderError.Misconfigured -> "provider_misconfigured"
        is AvailabilityProviderError.Unsupported -> "unsupported"
        is AvailabilityProviderError.WrongRefType -> "provider_misconfigured"
    }
```

Then rewrite `mapProviderError` in `route/api/pois/CampsiteRoutes.kt` to use it, so the two cannot drift:

```kotlin
internal fun mapProviderError(e: AvailabilityProviderError): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val upstream = upstreamHttpStatus(e)
    val status =
        when (e) {
            is AvailabilityProviderError.RateLimited,
            is AvailabilityProviderError.UpstreamBlocked,
            is AvailabilityProviderError.UpstreamUnavailable,
            is AvailabilityProviderError.UpstreamUnreachable,
            -> HttpStatusCode.ServiceUnavailable
            is AvailabilityProviderError.Unsupported -> HttpStatusCode.NotImplemented
            is AvailabilityProviderError.Misconfigured,
            is AvailabilityProviderError.WrongRefType,
            -> HttpStatusCode.InternalServerError
        }
    val upstreamStatus = if (status == HttpStatusCode.ServiceUnavailable) upstream else null
    return status to availabilityErrorDto(availabilityErrorCode(e), upstreamStatus = upstreamStatus)
}
```

Verify against the original `when` block that each variant keeps the same status code and the same `upstreamStatus` presence. `Misconfigured`, `Unsupported`, and `WrongRefType` did not pass `upstreamStatus` before; the guard above preserves that.

- [ ] **Step 2: Add the DTOs**

Add one optional field to `model/api/AvailabilityResponseDto.kt`, after `campsiteId`:

```kotlin
    @SerialName("longest_run_nights") val longestRunNights: Int? = null,
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityRequestDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityRequestDto(
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("min_nights") val minNights: Int = 1,
    @SerialName("site_type") val siteTypes: List<String> = emptyList(),
)
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityResponseDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityResponseDto(
    val pois: List<BulkPoiAvailabilityDto>,
)

/**
 * One requested POI. Exactly one of [campsites] and [error] is set: an empty
 * [campsites] list means the POI resolved but no site met `min_nights`.
 */
@Serializable
data class BulkPoiAvailabilityDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val campsites: List<AvailabilityResponseDto>? = null,
    val error: String? = null,
)
```

- [ ] **Step 3: Write the failing controller test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityControllerTest.kt`. Build a fake standing in for `CampsiteAvailabilityController.poiAvailabilitySlice` — extract an interface or use a test subclass, whichever the existing fakes in `CampsiteAvailabilityServiceTest` make natural. Assertions:

```kotlin
package ca.floo.roadtrip.service.availability

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BulkAvailabilityControllerTest {
    @Test
    fun `response entries mirror the requested poi ids in order including duplicates`() =
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(3L, 1L, 3L)))
            assertEquals(listOf(3L, 1L, 3L), response.pois.map { it.poiId })
        }

    @Test
    fun `campsites below min nights are dropped and the rest sort by run descending`() =
        runBlocking {
            // POI 1 has three sites with longest runs of 1, 5 and 3 nights.
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L), minNights = 3))
            val campsites = assertNotNull(response.pois.single().campsites)
            assertEquals(listOf(5, 3), campsites.map { it.longestRunNights })
        }

    @Test
    fun `a poi whose provider fails reports an error and does not fail its neighbours`() =
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L, RATE_LIMITED_POI_ID)))
            assertNotNull(response.pois[0].campsites)
            assertEquals("rate_limited", response.pois[1].error)
            assertNull(response.pois[1].campsites)
        }

    @Test
    fun `an unknown poi reports not_found`() =
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(UNKNOWN_POI_ID)))
            assertEquals("not_found", response.pois.single().error)
        }

    @Test
    fun `a poi that exceeds the per poi timeout reports timeout`() =
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(SLOW_POI_ID)))
            assertEquals("timeout", response.pois.single().error)
        }

    @Test
    fun `a poi with no matching campsites resolves with an empty list not an error`() =
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(NO_SITES_POI_ID)))
            assertEquals(emptyList(), response.pois.single().campsites)
            assertNull(response.pois.single().error)
        }

    @Test
    fun `every poi in one fan out is measured against the same freshness cutoff`() =
        runBlocking {
            val recorder = CutoffRecorder()
            bulkController(recorder = recorder)
                .availabilityForPois(request(poiIds = listOf(1L, 2L, 3L)))
            assertEquals(1, recorder.cutoffs.distinct().size)
        }

    @Test
    fun `concurrent poi resolution never exceeds the configured fan out`() =
        runBlocking {
            val meter = ConcurrencyMeter()
            bulkController(fanOutConcurrency = 2, meter = meter)
                .availabilityForPois(request(poiIds = (1L..8L).toList()))
            assertEquals(2, meter.peak)
        }
}
```

Write `bulkController(...)`, `request(...)`, `CutoffRecorder`, `ConcurrencyMeter`, and the `*_POI_ID` constants as private helpers in the same file. `ConcurrencyMeter` increments on entry, records the max, and decrements on exit, with a small `delay(...)` in the fake so overlap is observable. `CutoffRecorder` captures each `freshAtOrAfter` the fake receives.

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.BulkAvailabilityControllerTest'`
Expected: FAIL — `Unresolved reference: BulkAvailabilityController`.

- [ ] **Step 5: Write the controller**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.model.api.BulkPoiAvailabilityDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

internal data class BulkAvailabilityRequest(
    val poiIds: List<Long>,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minNights: Int,
    val siteTypes: List<String>,
)

/**
 * Bulk read across many POIs. Fans out over the same per-POI slice the detail
 * endpoint uses, so the two cannot drift, and captures each POI's failure at
 * the fan-out boundary so one bad vendor never blanks the scan.
 */
internal class BulkAvailabilityController(
    private val campsiteController: CampsiteAvailabilityController,
    private val config: BulkAvailabilityConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun availabilityForPois(request: BulkAvailabilityRequest): BulkAvailabilityResponseDto {
        val freshAtOrAfter = Instant.now(clock).minus(config.tolerance)
        val gate = Semaphore(config.fanOutConcurrency)

        val entries =
            coroutineScope {
                request.poiIds
                    .map { poiId -> async { gate.withPermit { entryFor(poiId, request, freshAtOrAfter) } } }
                    .awaitAll()
            }
        return BulkAvailabilityResponseDto(pois = entries)
    }

    private suspend fun entryFor(
        poiId: Long,
        request: BulkAvailabilityRequest,
        freshAtOrAfter: Instant,
    ): BulkPoiAvailabilityDto =
        try {
            withTimeout(config.perPoiTimeout.toMillis()) {
                rank(
                    campsiteController.poiAvailabilitySlice(
                        poiId = poiId,
                        siteTypes = request.siteTypes,
                        startDate = request.startDate,
                        endDate = request.endDate,
                        freshAtOrAfter = freshAtOrAfter,
                    ),
                    request.minNights,
                )
            }
        } catch (e: TimeoutCancellationException) {
            BulkPoiAvailabilityDto(poiId = poiId, error = "timeout")
        } catch (e: AvailabilityServiceError) {
            BulkPoiAvailabilityDto(poiId = poiId, error = e.error)
        } catch (e: AvailabilityProviderError) {
            BulkPoiAvailabilityDto(poiId = poiId, error = availabilityErrorCode(e))
        }

    private fun rank(
        slice: PoiAvailabilitySlice,
        minNights: Int,
    ): BulkPoiAvailabilityDto {
        val batch = slice.batch
        val campsites =
            if (batch == null) {
                emptyList()
            } else {
                slice.campsites
                    .map { campsite ->
                        val forCampsite = batch.observations.filter { it.campsiteId == campsite.id }
                        val response =
                            availabilityResponseFromObservations(
                                batch.copy(
                                    observations = forCampsite,
                                    campsiteId = campsite.id,
                                    startDate = slice.startDate,
                                    endDate = slice.endDate,
                                ),
                            )
                        response.copy(longestRunNights = longestRunNights(forCampsite))
                    }.filter { (it.longestRunNights ?: 0) >= minNights }
                    .sortedByDescending { it.longestRunNights ?: 0 }
            }

        return BulkPoiAvailabilityDto(
            poiId = slice.poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            campsites = campsites,
        )
    }
}
```

Two things to get right:
- Catch `TimeoutCancellationException` specifically, never bare `CancellationException`. `withTimeout` cancels only its own child scope, so this does not swallow the caller's cancellation.
- `AvailabilityServiceError` is a sealed type with an `error` property; confirm the property name in `service/availability/AvailabilityServiceError.kt` and use whatever is actually there.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.BulkAvailabilityControllerTest'`
Expected: PASS, 8 tests.

- [ ] **Step 7: Run the availability and route regression suite**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.*' --tests 'ca.floo.roadtrip.route.*'`
Expected: PASS. The `mapProviderError` rewrite is covered here.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityErrorCodes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityRequestDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityResponseDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityResponseDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityControllerTest.kt
git commit -m "feat(availability): add bulk POI availability controller"
```

---

### Task 6: Route and wiring

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutesTest.kt`

**Interfaces:**
- Consumes: `BulkAvailabilityController.availabilityForPois` and `BulkAvailabilityRequest` (Task 5), `BulkAvailabilityConfig` (Task 4).
- Produces: `internal fun Route.bulkAvailabilityRoutes(controller: BulkAvailabilityController, config: BulkAvailabilityConfig, rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute))`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutesTest.kt`. Follow the Ktor `testApplication` setup used by `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutesTest.kt` — read it first and mirror its structure. Assertions:

```kotlin
    @Test
    fun `rejects a request with more poi ids than the configured cap`() { /* expect 400, body error == "too_many_pois" */ }

    @Test
    fun `rejects an unparseable date`() { /* expect 400, body error == "bad_date_window" */ }

    @Test
    fun `rejects a non-positive min_nights`() { /* expect 400, body error == "bad_min_nights" */ }

    @Test
    fun `rejects an empty poi_ids list`() { /* expect 400, body error == "bad_request" */ }

    @Test
    fun `returns 200 with per-poi entries when one poi fails`() { /* expect 200, pois[1].error == "rate_limited" */ }

    @Test
    fun `throttles by ip`() { /* exceed ip-rate-limit-per-minute, expect 503, error == "ip_throttled" */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.pois.BulkAvailabilityRoutesTest'`
Expected: FAIL — `Unresolved reference: bulkAvailabilityRoutes`.

- [ ] **Step 3: Write the route**

Create `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt`:

```kotlin
package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityRequestDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.mapCatching
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.BulkAvailabilityRequest
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

internal fun Route.bulkAvailabilityRoutes(
    controller: BulkAvailabilityController,
    config: BulkAvailabilityConfig,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute),
) {
    route("/api") {
        route("/pois") {
            post("/availability/bulk") {
                if (!rateLimit.allow(call.request.origin.remoteHost)) {
                    call.respondBulkError("ip_throttled", HttpStatusCode.ServiceUnavailable)
                    return@post
                }

                val request =
                    when (
                        val body =
                            call
                                .receiveJsonBody<BulkAvailabilityRequestDto>()
                                .mapCatching { it.validated(config) }
                    ) {
                        is RouteBodyResult.Invalid -> {
                            call.respondBulkError(body.detail ?: "bad_request", HttpStatusCode.BadRequest)
                            return@post
                        }
                        is RouteBodyResult.Valid -> body.value
                    }

                call.respondText(
                    encodeAvailabilityJson(controller.availabilityForPois(request)),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }.describeApi(
                tag = "availability",
                summary = "Per-campsite availability across many campground POIs",
                description =
                    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
                        "min_nights?, site_type? }. Returns one entry per requested POI, in request " +
                        "order. Each entry carries either its campsites — filtered to " +
                        "`longest_run_nights >= min_nights` and sorted descending — or an error code. " +
                        "A POI failing never fails the request.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private fun BulkAvailabilityRequestDto.validated(config: BulkAvailabilityConfig): BulkAvailabilityRequest {
    require(poiIds.isNotEmpty()) { "bad_request" }
    require(poiIds.size <= config.maxPois) { "too_many_pois" }
    require(minNights >= 1) { "bad_min_nights" }
    return BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = parseDate(startDate),
        endDate = parseDate(endDate),
        minNights = minNights,
        siteTypes = siteTypes,
    )
}

private fun parseDate(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let {
        try {
            LocalDate.parse(it)
        } catch (e: Exception) {
            error("bad_date_window")
        }
    }

private suspend fun ApplicationCall.respondBulkError(
    error: String,
    status: HttpStatusCode,
) {
    respondText(encodeAvailabilityJson(availabilityErrorDto(error)), ContentType.Application.Json, status)
}
```

`mapCatching` turns a thrown `IllegalArgumentException`/`IllegalStateException` into `RouteBodyResult.Invalid(e.message)`, which is why the `require`/`error` messages are the wire error codes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.pois.BulkAvailabilityRoutesTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Wire into DI**

In `di/RouteModule.kt`, add the import `ca.floo.roadtrip.route.api.pois.bulkAvailabilityRoutes`. In the `routing { }` block, hoist the `CampsiteAvailabilityController` into a local so both routes share one instance, replacing the existing `campsiteRoutes(campsiteAvailabilityController(...))` call:

```kotlin
        val campsiteController =
            campsiteAvailabilityController(
                ctx = ctx,
                availabilityProviders = availabilityProviders,
                dateResolver = dateResolver,
                failoverFetcher = failoverFetcher,
                watchCapabilities = watchCapabilities,
            )
        campsiteRoutes(campsiteController)
        bulkAvailabilityRoutes(
            BulkAvailabilityController(campsiteController, config.availability.bulk),
            config.availability.bulk,
        )
```

- [ ] **Step 6: Run the whole backend suite**

Run: `./gradlew :backend:test`
Expected: PASS. `OpenApiSmokeTest` and `RouteAccessCoverage` both enumerate registered routes — if either fails, the new route needs its `describeApi`/`access` metadata corrected rather than the test relaxed.

- [ ] **Step 7: Verify against a running server**

```bash
curl -s -X POST localhost:8080/api/pois/availability/bulk \
  -H 'content-type: application/json' \
  -d '{"poi_ids":[1,2],"start_date":"2026-09-04","end_date":"2026-09-11","min_nights":2}' | jq .
```

Expected: HTTP 200, two entries in `pois`, in request order, each with either `campsites` or `error`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutesTest.kt
git commit -m "feat(api): add POST /api/pois/availability/bulk"
```

---

## Self-Review Notes

**Spec coverage.** Every P1 item maps to a task: runs → 1, `freshAtOrAfter` → 2, slice extraction → 3, config → 4, controller + DTOs → 5, route + wiring → 6. The spec's P2 (`maxBulkCampgrounds`, `catalogAvailabilityBulk`, Campflare override) is deliberately not in this plan.

**Deviations from the spec, called out.**
- The spec's response sketch nests `longest_run_nights` alongside an `AvailabilityResponseDto`. This plan adds it as an optional field *on* that DTO instead, so the wire shape stays flat and the detail endpoint simply omits it. Verify `encodeAvailabilityJson` is configured with `explicitNulls = false` so the field is absent rather than `null` on the detail endpoint; if it is not, that is a one-line Json config change, not a DTO change.
- The spec lists a `timeout` error code; the plan makes it concrete as the `TimeoutCancellationException` branch.
- `mapProviderError` is refactored to delegate to the new shared `availabilityErrorCode`. The spec implied bulk would reuse it; calling the route-layer function from a service-layer controller would invert the layering, so the shared half moved down.

**Assumptions the implementer must verify before writing code** — each is flagged inline at its step:
- `ConfigSection` exposes `value(name)`, `duration(name, default)`, and `section(name)` with those signatures (Task 4, Step 3).
- `AvailabilityServiceError` exposes an `error: String` property on every variant (Task 5, Step 5).
- `IpRateLimiter(perMinute = …)` and `allow(host)` match the usage in `CampsiteRoutes.kt` (Task 6).
- `RouteBodyResult.Invalid` carries a `detail` property (Task 6) — `PoisOnRouteRoutes.kt` reads `body.detail`.
