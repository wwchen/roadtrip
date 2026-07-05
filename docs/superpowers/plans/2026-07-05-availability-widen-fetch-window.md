# Widen Live Availability Fetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a live availability cache miss, fetch the widest window the vendor allows (anchored at the requested week), record all of it, and return only the requested slice — so paging to an adjacent week is served from the DB instead of re-fetching upstream.

**Architecture:** Unify the "wide window" computation the poller already uses (`resolvePollingWindow`) into an anchor-parameterized `AvailabilityDateResolver.wideWindow`. The composer computes two windows per vendor group — a validated **target** window (drives coverage + response) and a **fetch** window (`wideWindow(anchor = targetStart)`) — and passes them through the batcher as one `AvailabilityWindows` pair. `AvailabilityLoader` records whatever window the fetch returned (derived from the batch), so the wide data lands in the interval table; coverage and the response stay on the target window.

**Tech Stack:** Kotlin, Ktor, jOOQ/Postgres, JUnit5 + kotlin.test, Testcontainers (`SharedDbTest`), Gradle (toolchain 21).

## Global Constraints

- Branch: `feat/availability-widen-fetch-window` (stacked on `refactor/availability-service-loader-rename`). Use the renamed types **`AvailabilityService`** / **`AvailabilityServiceImpl`** / **`AvailabilityLoader`** (not `AvailabilityQueryService*` / `CachedAvailabilityService`).
- Scope is the **cataloged** path only. Do **not** touch `AvailabilityServiceImpl.cataloglessProviderAvailability` — catalogless unification is a separate follow-up spec.
- No inline magic constants: the request cap is the per-vendor `caps.maxPollWindowDays`, never a literal.
- Layering: window policy lives in `AvailabilityDateResolver`; the loader coordinates *when* to fetch/record and never computes windows.
- Build: run Gradle via `./gradlew` at repo root; do **not** export `JAVA_HOME`. CI runs ktlint separately — keep imports ordered and lines within the ktlint layout.
- Poller behavior must be **unchanged**: its fetch window stays `resolvePollingWindow` anchored at `earliestDate` (target == fetch).

---

### Task 1: `wideWindow` on `AvailabilityDateResolver`

Generalize the poller's window into an anchor-parameterized method; make `resolvePollingWindow` a thin delegate so the poller and its tests are untouched behaviorally.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolver.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolverTest.kt`

**Interfaces:**
- Produces:
  `fun wideWindow(anchor: LocalDate, context: PoiDateContext, maxPollWindowDays: Int, bookingHorizonDays: Int): ResolvedDateWindow?`
  Window is `[max(earliestDate, anchor), min(earliestDate + bookingHorizonDays, start + min(maxPollWindowDays, bookingHorizonDays)))`; `null` when the span is non-positive or the anchor sits at/after the horizon.
- Consumes: existing `PoiDateContext { earliestDate, timeZone }`, `ResolvedDateWindow { startDate, endDate }`.

- [ ] **Step 1: Write the failing tests**

Add to `AvailabilityDateResolverTest.kt` (imports already present: `Clock`, `Instant`, `ZoneOffset`, `ChronoUnit`, `assertEquals`, `assertNull`; add `import java.time.LocalDate`):

```kotlin
    @Test
    fun `wideWindow anchored at earliest equals the polling window`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val wide = resolver.wideWindow(context.earliestDate, context, maxPollWindowDays = 60, bookingHorizonDays = 180)!!
        val polling = resolver.resolvePollingWindow(context, maxPollWindowDays = 60, bookingHorizonDays = 180)!!

        assertEquals(polling.startDate, wide.startDate)
        assertEquals(polling.endDate, wide.endDate)
    }

    @Test
    fun `wideWindow anchors at a future target start and spans the vendor cap`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val anchor = context.earliestDate.plusDays(90)

        val wide = resolver.wideWindow(anchor, context, maxPollWindowDays = 30, bookingHorizonDays = 365)!!

        assertEquals(anchor, wide.startDate)
        assertEquals(30L, ChronoUnit.DAYS.between(wide.startDate, wide.endDate))
    }

    @Test
    fun `wideWindow clamps a past anchor up to the earliest bookable date`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val pastAnchor = context.earliestDate.minusDays(10)

        val wide = resolver.wideWindow(pastAnchor, context, maxPollWindowDays = 30, bookingHorizonDays = 365)!!

        assertEquals(context.earliestDate, wide.startDate)
    }

    @Test
    fun `wideWindow end never passes the booking horizon`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val horizonEnd = context.earliestDate.plusDays(30)
        val anchor = context.earliestDate.plusDays(20)

        val wide = resolver.wideWindow(anchor, context, maxPollWindowDays = 60, bookingHorizonDays = 30)!!

        assertEquals(horizonEnd, wide.endDate)
    }

    @Test
    fun `wideWindow yields no window for a zero cap or an anchor at the horizon`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        assertNull(resolver.wideWindow(context.earliestDate, context, maxPollWindowDays = 0, bookingHorizonDays = 180))
        assertNull(resolver.wideWindow(context.earliestDate.plusDays(180), context, maxPollWindowDays = 60, bookingHorizonDays = 180))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityDateResolverTest'`
Expected: FAIL — `wideWindow` unresolved reference.

- [ ] **Step 3: Implement `wideWindow` and delegate `resolvePollingWindow`**

In `AvailabilityDateResolver.kt`, replace the existing `resolvePollingWindow` body with a delegate and add `wideWindow` next to it. The existing KDoc on `resolvePollingWindow` stays. New code:

```kotlin
    /**
     * The widest window the vendor exposes for a single call, anchored at
     * [anchor] (clamped forward to the earliest bookable date) and capped by
     * `min(maxPollWindowDays, bookingHorizonDays)`, never running past the
     * booking horizon. Shared by the poller (anchor = earliestDate) and the
     * live read path (anchor = the requested week's start) so the two never
     * drift on how wide a single fetch is. Returns null when the effective
     * span is non-positive or the anchor is already at/after the horizon, so
     * the batcher skips the group and makes no upstream call.
     */
    fun wideWindow(
        anchor: LocalDate,
        context: PoiDateContext,
        maxPollWindowDays: Int,
        bookingHorizonDays: Int,
    ): ResolvedDateWindow? {
        val span = minOf(maxPollWindowDays, bookingHorizonDays)
        if (span <= 0) return null
        val start = maxOf(context.earliestDate, anchor)
        val horizonEnd = context.earliestDate.plusDays(bookingHorizonDays.toLong())
        val end = minOf(horizonEnd, start.plusDays(span.toLong()))
        if (!end.isAfter(start)) return null
        return ResolvedDateWindow(startDate = start, endDate = end)
    }

    fun resolvePollingWindow(
        context: PoiDateContext,
        maxPollWindowDays: Int,
        bookingHorizonDays: Int,
    ): ResolvedDateWindow? = wideWindow(context.earliestDate, context, maxPollWindowDays, bookingHorizonDays)
```

Delete the old `resolvePollingWindow` implementation body (the `val days = minOf(...)` block) — it's now inside `wideWindow`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityDateResolverTest'`
Expected: PASS — new `wideWindow` tests and the pre-existing `resolvePollingWindow` tests all green (delegation preserves behavior).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolver.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolverTest.kt
git commit -m "feat(availability): anchor-parameterized wideWindow; resolvePollingWindow delegates"
```

---

### Task 2: `AvailabilityLoader` records the fetched window, always returns the target slice

Make the loader record whatever window the fetch actually returned (from the batch), and slice the degraded fallback to the target window. Coverage check and normal response stay on the request (target) window. This is behavior-preserving today (the composer still fetches the request window, so `batch` window == request window) and is what makes wide-fetch caching work once Task 4 lands.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt`

**Interfaces:**
- Consumes: `AvailabilityLoader.Request { startDate, endDate = target window }`, `AvailabilityObservationBatch { startDate, endDate = fetched window }`.
- Produces: no signature change. Behavior: `recordFetched` covers `[batch.startDate, batch.endDate)`; the miss-fallback response is filtered to `[request.startDate, request.endDate)`.

- [ ] **Step 1: Write the failing tests**

Add to `AvailabilityLoaderTest.kt`:

```kotlin
    @Test
    fun `records the full fetched window even when it is wider than the requested window`() =
        runBlocking {
            // Requested (target) window is 1 day; the fetch returns 3 days.
            val requestStart = LocalDate.parse("2026-07-01")
            val requestEnd = LocalDate.parse("2026-07-02")
            val fetchStart = LocalDate.parse("2026-07-01")
            val fetchEnd = LocalDate.parse("2026-07-04")
            val observedAt = Instant.parse("2026-06-18T10:00:00Z")
            val seen = seedReservable("100")
            val repo = AvailabilityRepo(ctx)
            val service =
                AvailabilityLoader(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )

            service.loadOrFetch(
                AvailabilityLoader.Request(
                    metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
                    targets = listOf(AvailabilityLoader.TargetReservable(seen, "site:recgov:100")),
                    startDate = requestStart,
                    endDate = requestEnd,
                    ttl = Duration.ofMinutes(10),
                ),
            ) {
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = fetchStart,
                    endDate = fetchEnd,
                    observations =
                        (0L until 3L).map {
                            ReservableDayObservation(
                                "site:recgov:100",
                                fetchStart.plusDays(it),
                                observedAt,
                                AvailabilityStatus.AVAILABLE,
                            )
                        },
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                    campgroundId = "232447",
                )
            }

            // All 3 fetched days are persisted, not just the requested 1.
            val persisted =
                repo.readCurrent(listOf(seen), (0L until 3L).map { fetchStart.plusDays(it) })
            assertEquals(3, persisted.size)
        }

    @Test
    fun `a later request inside the recorded wide window is served from the DB without fetching`() =
        runBlocking {
            val week1Start = LocalDate.parse("2026-07-01")
            val week1End = LocalDate.parse("2026-07-08")
            val wideEnd = LocalDate.parse("2026-07-15")
            val week2Start = LocalDate.parse("2026-07-08")
            val week2End = LocalDate.parse("2026-07-15")
            val observedAt = Instant.parse("2026-06-18T10:00:00Z")
            val seen = seedReservable("100")
            val repo = AvailabilityRepo(ctx)
            val service =
                AvailabilityLoader(
                    availability = repo,
                    clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC),
                )
            fun req(start: LocalDate, end: LocalDate) =
                AvailabilityLoader.Request(
                    metadata = AvailabilityLoader.Metadata(provider = "recgov", campgroundId = "232447"),
                    targets = listOf(AvailabilityLoader.TargetReservable(seen, "site:recgov:100")),
                    startDate = start,
                    endDate = end,
                    ttl = Duration.ofHours(2),
                )

            // Week-1 request, but the fetch returns the wide [07-01, 07-15) window.
            service.loadOrFetch(req(week1Start, week1End)) {
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = week1Start,
                    endDate = wideEnd,
                    observations =
                        (0L until 14L).map {
                            ReservableDayObservation("site:recgov:100", week1Start.plusDays(it), observedAt, AvailabilityStatus.AVAILABLE)
                        },
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 7200),
                    campgroundId = "232447",
                )
            }

            // Week-2 request is fully inside the recorded window → must NOT fetch.
            var week2Fetched = false
            val batch =
                service.loadOrFetch(req(week2Start, week2End)) {
                    week2Fetched = true
                    error("week 2 must be served from the DB, not fetched")
                }

            assertEquals(false, week2Fetched)
            assertEquals(true, batch.cacheBlock!!.hit)
            // Response is only the week-2 slice (7 days), not the whole wide window.
            assertEquals(7, batch.observations.size)
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.api.AvailabilityLoaderTest'`
Expected: FAIL — the "records full fetched window" test finds only 1 persisted cell (record range is the request window); the "served from DB" test fetches on week-2 because week-2 dates were never recorded.

- [ ] **Step 3: Change the record range to the fetched window and slice the fallback**

In `AvailabilityLoader.kt`, in `recordFetched`, change the dates source from the request window to the fetched batch window:

```kotlin
        val dates = datesInWindow(batch.startDate, batch.endDate)
```

(Replace the existing `val dates = datesInWindow(request.startDate, request.endDate)` line inside `recordFetched` only. The `loadOrFetch` coverage read at the top and the post-fetch re-read stay on `request.startDate/endDate`.)

Then make the miss-fallback slice to the target window. Replace the `else` branch of `loadOrFetch`:

```kotlin
        } else {
            val targetDates = datesInWindow(request.startDate, request.endDate).toSet()
            fetched.copy(
                startDate = request.startDate,
                endDate = request.endDate,
                observations = fetched.observations.filter { it.date in targetDates },
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = request.ttl.seconds),
            )
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.api.AvailabilityLoaderTest'`
Expected: PASS — new tests green; the two pre-existing tests (`vendor omissions…`, `an unchanged refetch…`) still pass because there the batch window equals the request window.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoaderTest.kt
git commit -m "feat(availability): loader records the fetched window, slices fallback to target"
```

---

### Task 3: `AvailabilityWindows` pair through the batcher seam (behavior-preserving)

Split the batcher's single `windowFor` window into a `{ target, fetch }` pair without changing behavior yet: both callers set `target == fetch`, so the poller and live read produce identical output. This isolates the API/plumbing change from the behavior change in Task 4.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityWindows.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposer.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcherTest.kt`

**Interfaces:**
- Produces:
  `internal data class AvailabilityWindows(val target: ResolvedDateWindow, val fetch: ResolvedDateWindow)`
  `CatalogAvailabilityBatcher.fetchByGroup(targets, windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?, fetch: suspend (ProviderRef, ReservationProvider, List<Reservable>, AvailabilityWindows) -> AvailabilityObservationBatch): List<GroupFetchResult>`
  `CatalogAvailabilityBatcher.countFetchGroups(targets, windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?): Int`
- `GroupFetchResult.window: ResolvedDateWindow?` is unchanged in type; the batcher sets it to `windows.fetch` (what was actually fetched — the value `AvailabilityPollExecutor:269` records for the fetch-call trace).

- [ ] **Step 1: Write the failing test**

Update `CatalogAvailabilityBatcherTest.kt`. Add `import ca.floo.roadtrip.models.availability.AvailabilityWindows`. Change the `window` fixture helper and the four `fetchByGroup` call sites to use the pair, and add a test asserting the fetch lambda receives the fetch window while the group records it:

Replace the top field:
```kotlin
    private val window = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-31"))
    private val windows = AvailabilityWindows(target = window, fetch = window)
```

Then in each `fetchByGroup` call, replace `windowFor = { _, _ -> window }` with `windowFor = { _, _ -> windows }` (and the positional `{ _, _ -> window }` forms with `{ _, _ -> windows }`), and change each `fetch` lambda's last parameter usage from `w` to `ws.fetch` — e.g.:
```kotlin
                    fetch = { _, _, reservables, ws ->
                        calls++
                        assertEquals(2, reservables.size)
                        emptyBatch(ws.fetch)
                    },
```
In the "null window skips" test, keep `{ _, _ -> null }`.

Add a new test:
```kotlin
    @Test
    fun `records the fetch window from the windows pair`() =
        runBlocking {
            val provider = fakeProvider()
            val target = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-24"))
            val fetch = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-16"))
            val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> AvailabilityWindows(target, fetch) },
                    { _, _, _, ws -> emptyBatch(ws.fetch) },
                )
            assertEquals(fetch, results[0].window)
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcherTest'`
Expected: FAIL to compile — `AvailabilityWindows` unresolved, `windowFor`/`fetch` lambda types mismatch.

- [ ] **Step 3: Create the model**

Create `backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityWindows.kt`:

```kotlin
package ca.floo.roadtrip.models.availability

/**
 * The two windows a single availability fetch works with. [fetch] is the
 * widest window the vendor allows for one call (what we ask upstream and
 * record); [target] is the caller's requested window (what drives the cache
 * coverage check and the returned slice). The poller sets them equal; the
 * live read path sets [fetch] wider than [target] so paging is served from
 * the DB.
 */
internal data class AvailabilityWindows(
    val target: ResolvedDateWindow,
    val fetch: ResolvedDateWindow,
)
```

- [ ] **Step 4: Change the batcher seam**

In `CatalogAvailabilityBatcher.kt`, add `import ca.floo.roadtrip.models.availability.AvailabilityWindows`.

Change `countFetchGroups`'s `windowFor` type to return `AvailabilityWindows?` (body unchanged — it only null-checks):
```kotlin
    fun countFetchGroups(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?,
    ): Int =
        targets
            .map { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .distinct()
            .count { windowFor(it.dateContext, it.provider.capabilities) != null }
```

Change `fetchByGroup`'s signature and the two spots that use the window. The `fetch` lambda now takes `AvailabilityWindows`; the skip-null and result rows use `windows?.fetch`:
```kotlin
    suspend fun fetchByGroup(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?,
        fetch: suspend (
            parentRef: ProviderRef,
            provider: ReservationProvider,
            reservables: List<Reservable>,
            windows: AvailabilityWindows,
        ) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult> =
        targets
            .groupBy { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .map { (key, groupTargets) ->
                val reservables = groupTargets.map { it.reservable }
                val windows = windowFor(key.dateContext, key.provider.capabilities)
                if (windows == null) {
                    return@map GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        reservables = reservables,
                        window = null,
                        batch = null,
                        outcome = FetchOutcome.OK,
                        durationMs = 0,
                        error = null,
                    )
                }
                val startedNanos = System.nanoTime()
                try {
                    val batch = fetch(key.parentRef, key.provider, reservables, windows)
                    GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        reservables = reservables,
                        window = windows.fetch,
```

Continue the existing `GroupFetchResult` construction (outcome/duration/etc.) unchanged, and in the `catch` blocks that build a `GroupFetchResult`, set `window = windows.fetch` wherever `window = window` appeared. (Read the rest of the method below line 120 and replace each `window = window` with `window = windows.fetch`.)

- [ ] **Step 5: Adapt the poller (behavior-preserving)**

In `AvailabilityPollExecutor.kt`, change the `windowFor` lambda to return a pair with `target == fetch`, and update the fetch lambda's window parameter. The lambda type annotation changes to `AvailabilityWindows?`:

```kotlin
        val windowFor: (
            ca.floo.roadtrip.models.availability.PoiDateContext,
            ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities,
        ) -> ca.floo.roadtrip.models.availability.AvailabilityWindows? = { context, caps ->
            dateResolver.resolvePollingWindow(
                context = context,
                maxPollWindowDays = caps.maxPollWindowDays,
                bookingHorizonDays = caps.bookingHorizonDays,
            )?.let { ca.floo.roadtrip.models.availability.AvailabilityWindows(target = it, fetch = it) }
        }
```

In the `batcher.fetchByGroup(...)` call below (around line 171), change the fetch lambda's window parameter from `window` to `windows` and use `windows.fetch` where the poller passes the window to `provider.catalogAvailability` and to its `AvailabilityLoader.Request` (both use `windows.fetch`, since target == fetch for the poller). Read lines 171–230 and substitute `window.startDate`/`window.endDate` → `windows.fetch.startDate`/`windows.fetch.endDate`, and the lambda header `{ parentRef, provider, rows, window ->` → `{ parentRef, provider, rows, windows ->`.

- [ ] **Step 6: Adapt the composer (behavior-preserving)**

In `ReservableAvailabilityComposer.kt`, add `import ca.floo.roadtrip.models.availability.AvailabilityWindows`. Wrap the existing single window in a pair and read `windows.fetch` in the fetch lambda. Do **not** change the window math yet (still `resolveWindow` with `MAX_AVAILABILITY_DAYS`):

```kotlin
                windowFor = { context, caps ->
                    val w =
                        dateResolver.resolveWindow(
                            startDate = startDate,
                            endDate = endDate,
                            context = context,
                            bookingHorizonDays = caps.bookingHorizonDays,
                            maxDays = MAX_AVAILABILITY_DAYS,
                            defaultDays = DEFAULT_AVAILABILITY_DAYS,
                        )
                    AvailabilityWindows(target = w, fetch = w)
                },
                fetch = { parentRef, provider, rows, windows ->
                    availabilityLoader.loadOrFetch(
                        AvailabilityLoader.Request(
                            metadata = availabilityMetadata(provider.id, parentRef),
                            targets = rows.map { it.toAvailabilityTarget() },
                            startDate = windows.target.startDate,
                            endDate = windows.target.endDate,
                            ttl = snapshotFreshnessTtl(provider.id),
                        ),
                    ) {
                        provider.catalogAvailability(
                            CatalogAvailabilityRequest(
                                ref = parentRef,
                                reservables = rows.map { it.toCatalogReservableRef() },
                                startDate = windows.fetch.startDate,
                                endDate = windows.fetch.endDate,
                            ),
                        )
                    }
                },
```

- [ ] **Step 7: Run the affected tests + full compile**

Run: `./gradlew :backend:compileKotlin :backend:compileTestKotlin`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcherTest' --tests 'ca.floo.roadtrip.service.scheduler.jobs.*'`
Expected: PASS — batcher tests updated for the pair; poller tests unchanged (target == fetch).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/availability/AvailabilityWindows.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposer.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcherTest.kt
git commit -m "refactor(availability): carry {target,fetch} windows through the batcher seam"
```

---

### Task 4: Composer splits target vs fetch + vendor-driven request cap

Flip the composer to the real behavior: validate the request against the vendor's `maxPollWindowDays` (target window) and fetch the wide `wideWindow(anchor = target.start)` window. This is the behavior change that makes paging DB-served.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposer.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposerTest.kt` (create)

**Interfaces:**
- Consumes: `AvailabilityDateResolver.wideWindow` (Task 1), `AvailabilityWindows` (Task 3), `caps.maxPollWindowDays`.
- Produces: composer now sets `target = resolveWindow(maxDays = caps.maxPollWindowDays)` and `fetch = wideWindow(anchor = target.start, …)`. `MAX_AVAILABILITY_DAYS` removed.

- [ ] **Step 1: Write the failing tests**

Create `ReservableAvailabilityComposerTest.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReservableAvailabilityComposerTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC)
    private val resolver = AvailabilityDateResolver(clock = clock)

    @Test
    fun `fetches the wide vendor window while requesting a single week`() =
        runBlocking {
            var fetchedStart: LocalDate? = null
            var fetchedEnd: LocalDate? = null
            val provider = fakeProvider(maxPollWindowDays = 30, bookingHorizonDays = 365) { req ->
                fetchedStart = req.startDate
                fetchedEnd = req.endDate
            }
            val composer = composerFor(provider)

            composer.availabilityFor(
                reservables = listOf(reservable("site:recgov:100")),
                startDate = LocalDate.parse("2026-07-12"),
                endDate = LocalDate.parse("2026-07-19"),
            )

            // The upstream call spans the vendor's 30-day window, not the 7-day request.
            assertEquals(LocalDate.parse("2026-07-12"), fetchedStart)
            assertEquals(30L, ChronoUnit.DAYS.between(fetchedStart, fetchedEnd))
        }

    @Test
    fun `rejects a request wider than the vendor poll window`() {
        val provider = fakeProvider(maxPollWindowDays = 30, bookingHorizonDays = 365) {}
        val composer = composerFor(provider)

        assertFailsWith<AvailabilityServiceError.BadDateWindow.WindowTooLong> {
            runBlocking {
                composer.availabilityFor(
                    reservables = listOf(reservable("site:recgov:100")),
                    startDate = LocalDate.parse("2026-07-12"),
                    endDate = LocalDate.parse("2026-09-01"), // 51 days > 30
                )
            }
        }
    }

    // --- fixtures ---

    private fun composerFor(provider: ReservationProvider): ReservableAvailabilityComposer {
        val targetResolver =
            object : AvailabilityTargetResolver {
                override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget =
                    ResolvedAvailabilityTarget(
                        reservable = reservable,
                        provider = provider,
                        parentRef = ProviderRef.RecGov("232447"),
                        parentPoiId = 1L,
                        dateContext = PoiDateContext(timeZone = ZoneOffset.UTC, earliestDate = LocalDate.parse("2026-07-04")),
                    )
            }
        return ReservableAvailabilityComposer(
            targets = targetResolver,
            dateResolver = resolver,
            availability = null, // loader has no repo → loadOrFetch calls fetch() directly
            snapshotFreshnessTtl = { Duration.ofHours(2) },
        )
    }

    private fun reservable(rid: String): Reservable =
        Reservable(id = 1L, rid = ReservableId.parse(rid)!!, name = null, loop = null, siteType = null, raw = null)

    private fun fakeProvider(
        maxPollWindowDays: Int,
        bookingHorizonDays: Int,
        onCatalog: (CatalogAvailabilityRequest) -> Unit,
    ): ReservationProvider =
        object : ReservationProvider {
            override val id = ReservationProviderId.RECGOV
            override val capabilities =
                ReservationProviderCapabilities(
                    supportsAvailability = true,
                    supportsAlerts = true,
                    bookingHorizonDays = bookingHorizonDays,
                    maxPollWindowDays = maxPollWindowDays,
                )

            override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
                throw UnsupportedOperationException("not used")

            override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
                onCatalog(req)
                return AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = req.startDate,
                    endDate = req.endDate,
                    observations = emptyList(),
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
                )
            }

            override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
                throw UnsupportedOperationException("not used")
        }
}
```

Note: the composer maps observations back per reservable and throws `AvailabilityServiceError.NotFound` if a requested rid has no entry. With an empty observation batch the `byRid` map is empty, so the first test's `availabilityFor` would throw `NotFound` before the assert. To keep the test focused on the fetch window, capture the window inside `onCatalog` (already done) and wrap the `availabilityFor` call in a `runCatching { }` — the window is recorded before mapping. Adjust the first test's call:

```kotlin
            runCatching {
                composer.availabilityFor(
                    reservables = listOf(reservable("site:recgov:100")),
                    startDate = LocalDate.parse("2026-07-12"),
                    endDate = LocalDate.parse("2026-07-19"),
                )
            }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.ReservableAvailabilityComposerTest'`
Expected: FAIL — the fetch window currently equals the 7-day request (30-day assert fails); the "rejects wider than 30" test fails because `MAX_AVAILABILITY_DAYS = 60` still permits 51 days.

- [ ] **Step 3: Split the windows in the composer**

In `ReservableAvailabilityComposer.kt`, delete the `MAX_AVAILABILITY_DAYS` constant (line 18) and change the `windowFor` lambda to compute target + wide fetch:

```kotlin
                windowFor = { context, caps ->
                    val target =
                        dateResolver.resolveWindow(
                            startDate = startDate,
                            endDate = endDate,
                            context = context,
                            bookingHorizonDays = caps.bookingHorizonDays,
                            maxDays = caps.maxPollWindowDays,
                            defaultDays = DEFAULT_AVAILABILITY_DAYS,
                        )
                    val fetch =
                        dateResolver.wideWindow(
                            anchor = target.startDate,
                            context = context,
                            maxPollWindowDays = caps.maxPollWindowDays,
                            bookingHorizonDays = caps.bookingHorizonDays,
                        ) ?: target
                    AvailabilityWindows(target = target, fetch = fetch)
                },
```

(The `?: target` guard is defensive: `wideWindow` only returns null for a zero/negative span, which can't happen once `resolveWindow` has validated a positive target window against the same caps. Falling back to `target` preserves the pre-widen behavior rather than crashing.)

The `fetch` lambda from Task 3 already reads `windows.target` for the loader Request and `windows.fetch` for `catalogAvailability` — no change needed there.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.ReservableAvailabilityComposerTest'`
Expected: PASS — fetch spans 30 days for a 7-day request; a 51-day request throws `WindowTooLong`.

- [ ] **Step 5: Full backend test run**

Run: `./gradlew :backend:test`
Expected: BUILD SUCCESSFUL — no regressions across resolver, loader, batcher, poller, composer, and route tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposer.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/ReservableAvailabilityComposerTest.kt
git commit -m "feat(availability): live read fetches vendor-max window, caps request at maxPollWindowDays"
```

---

## Notes for the implementer

- **Why Task 2 before Task 4:** the loader change is behavior-preserving while the composer still fetches the narrow window (batch window == request window), and it is the piece that makes the wide fetch actually cache. Landing it first keeps every commit green and lets the loader's regression test (`a later request inside the recorded wide window…`) prove the fix at the unit level, independent of the composer wiring.
- **The `WindowTooLong` cap is load-bearing.** It guarantees `targetEnd ≤ targetStart + maxPollWindowDays`, so `wideWindow(anchor = targetStart)` always contains the target window — that is why there is no month-straddle handling and no `max(…, targetEnd)` patch. Do not raise `maxDays` above `caps.maxPollWindowDays`.
- **Out of scope:** the catalogless path (`AvailabilityServiceImpl.cataloglessProviderAvailability`) still fetches the bare requested window and bypasses the loader. Leave it. It is the subject of the follow-up unification spec.
- **FE impact:** the request cap drops from 60 to the vendor max (30 for Aspira). The drawer only requests 7-day weeks, so no real client is affected; a `WindowTooLong` now carries the vendor `maxDays`.
