# Date-Window Availability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace campsite minimum-night query contracts with explicit `start_date` / exclusive `end_date` windows, while keeping stay-window awareness only in watch/alert execution.

**Architecture:** Routes parse date windows and pass typed `startDate` / `endDate` values into booking-provider requests. Providers return per-day availability for the requested window with single-day semantics; frontend code owns stay visualization. Watch-like persistence (`availability_watch` and deprecated rec.gov `alerts`) stores date windows because polling needs an executable monitored range.

**Tech Stack:** Kotlin 2.1, Ktor, kotlinx.serialization, jOOQ generated from Flyway migrations, PostgreSQL/Flyway, vanilla ES modules in `web/`.

---

## File Structure

- `backend/src/main/resources/db/migration/V19__date_window_availability.sql` changes `availability_watch` and deprecated `alerts`.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteAvailabilitySchemas.kt` changes bulk request/response schemas.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt` changes `window` from `{start, days}` to `{start_date, end_date}` and removes stay-mismatch summary text.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt` changes provider request DTOs from `start`/`days`/`minNights` to `startDate`/`endDate`.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt` parses `start_date` / `end_date`, rejects removed params, and updates POI/reservable/bulk routes.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt` uses exact start/end dates for generated booking links.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`, `repo/AvailabilityWatchRepo.kt`, `routes/AvailabilityWatchRoutes.kt`, `service/availability/AvailabilityWatchService.kt`, `service/availability/AvailabilityJobIntent.kt`, and `service/availability/AvailabilityPollExecutor.kt` convert watches from `target_dates` + `min_nights` to `start_date` + `end_date`.
- `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/**` removes `minNights` from deprecated rec.gov alert models/routes/repos and derives match length from `startDate` / `endDate`.
- `web/api/*.js`, `web/availability/*.js`, `web/components/availability-panel.js`, `web/watches.js`, `web/watch-detail.js`, `watches.html`, and `backend/src/main/resources/static/campsite/*` remove min-night controls/params and use date windows.
- Backend route/unit tests update in `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`, `AvailabilityWatchRoutesTest.kt`, service booking tests, and legacy campsite tests.

## Task 1: Backend API Contract Tests

**Files:**
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteAvailabilitySchemas.kt` only after RED

- [ ] **Step 1: Write failing POI/reservable date-window route tests**

Add these tests to `ReservableRoutesTest` near the current availability route tests:

```kotlin
@Test
fun `poi availability uses exclusive start and end date window`() =
    testApplication {
        val poiId =
            seedPoi(
                sourceId = "upper-pines-window",
                name = "Upper Pines Campground",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        application {
            routing {
                campsiteAvailabilityRoutes(
                    CampsiteProviderRepo(ctx),
                    fakeBookingProviders(),
                    ReservableRepo(ctx),
                )
            }
        }

        val resp = client.get("/api/poi/$poiId/availability?start_date=2026-07-01&end_date=2026-07-04")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("2026-07-01", body["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-07-04", body["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
        assertEquals(3, body["availability"]!!.jsonArray.size)
    }

@Test
fun `availability routes reject removed days and min nights params`() =
    testApplication {
        val poiId =
            seedPoi(
                sourceId = "upper-pines-removed-params",
                name = "Upper Pines Campground",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        application {
            routing {
                campsiteAvailabilityRoutes(
                    CampsiteProviderRepo(ctx),
                    fakeBookingProviders(),
                    ReservableRepo(ctx),
                )
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/$poiId/availability?days=7").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/$poiId/availability?min_nights=2").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/reservable/site:recgov:330257/availability?min_nights=2").status)
    }

@Test
fun `reservable availability uses exclusive start and end date window`() =
    testApplication {
        val poiId =
            seedPoi(
                sourceId = "upper-pines-reservable-window",
                name = "Upper Pines Campground",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        val reservableId = seedReservable(vendorId = "330257", name = "A12")
        link(reservableId, poiId)
        application {
            routing {
                campsiteAvailabilityRoutes(
                    CampsiteProviderRepo(ctx),
                    fakeBookingProviders(),
                    ReservableRepo(ctx),
                    AvailabilitySnapshotRepo(ctx),
                )
            }
        }

        val resp = client.get("/api/reservable/site:recgov:330257/availability?start_date=2026-07-01&end_date=2026-07-04")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("2026-07-01", body["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-07-04", body["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
        assertEquals(3, body["availability"]!!.jsonArray.size)
        assertEquals(3L, ctx.fetchOne("SELECT count(*) FROM availability_snapshot")!!.get(0, Long::class.java))
    }
```

- [ ] **Step 2: Run route tests to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.ReservableRoutesTest --rerun
```

Expected: FAIL because the routes ignore `start_date` / `end_date`, return `window.start` / `window.days`, or still accept removed params.

- [ ] **Step 3: Write failing bulk schema test**

Add a test to `ReservableRoutesTest` using the existing `campsiteAvailabilityRoutes` setup:

```kotlin
@Test
fun `bulk availability accepts start and end date window`() =
    testApplication {
        val poiId =
            seedPoi(
                sourceId = "bulk-window",
                name = "Bulk Window Campground",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        application {
            routing {
                campsiteAvailabilityRoutes(
                    CampsiteProviderRepo(ctx),
                    fakeBookingProviders(),
                    ReservableRepo(ctx),
                )
            }
        }

        val resp =
            client.post("/api/campsite/availability/bulk") {
                contentType(ContentType.Application.Json)
                setBody("""{"ids":[$poiId],"start_date":"2026-07-01","end_date":"2026-07-04"}""")
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("2026-07-01", body["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-07-04", body["end_date"]!!.jsonPrimitive.content)
    }
```

Add imports if missing:

```kotlin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
```

- [ ] **Step 4: Run bulk test to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests "ca.floo.roadtrip.routes.ReservableRoutesTest.bulk availability accepts start and end date window" --rerun
```

Expected: FAIL because `BulkAvailRequestSchema` still requires `start` and `nights`.

- [ ] **Step 5: Commit RED tests**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt
git commit -m "test: cover date-window availability API"
```

## Task 2: Shared Availability Date Window Contract

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteAvailabilitySchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`

- [ ] **Step 1: Update bulk schemas**

Replace the bulk request/response data classes with:

```kotlin
@Serializable
data class BulkAvailRequestSchema(
    val ids: List<Long>,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class BulkAvailResponseSchema(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val results: List<BulkAvailEntrySchema>,
)
```

Add this import:

```kotlin
import kotlinx.serialization.SerialName
```

- [ ] **Step 2: Update availability response window DTO**

In `AvailabilityResponse.kt`, change `availabilityResponseDto` to accept start/end:

```kotlin
fun availabilityResponseDto(
    provider: String,
    startDate: LocalDate,
    endDate: LocalDate,
    perDay: List<DayClassification>,
    state: String,
    summary: String,
    seasonBlock: AvailabilitySeasonBlock?,
    cacheBlock: AvailabilityCacheBlock,
    campgroundId: String? = null,
    host: String? = null,
    mapId: String? = null,
    reservableId: String? = null,
): AvailabilityResponseDto =
    AvailabilityResponseDto(
        provider = provider,
        campgroundId = campgroundId,
        host = host,
        mapId = mapId,
        reservableId = reservableId,
        checkedAt = Instant.now().toString(),
        window = AvailabilityWindowDto(startDate = startDate.toString(), endDate = endDate.toString()),
        summary = summary,
        state = state,
        season = seasonBlock?.let { availabilityResponseJson.encodeToJsonElement(it) } ?: JsonNull,
        availability =
            perDay.map { day ->
                AvailabilityDayDto(
                    date = day.date,
                    status = day.status,
                    availableCount = day.availableCount,
                    total = day.total,
                    availableReservableIds = day.availableReservableIds,
                )
            },
        cache = cacheBlock,
    )
```

Replace `AvailabilityWindowDto` with:

```kotlin
@Serializable
data class AvailabilityWindowDto(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)
```

- [ ] **Step 3: Remove stay-mismatch summary language**

Replace `summarizeWindow` with:

```kotlin
fun summarizeWindow(
    days: Int,
    perDay: List<DayClassification>,
    state: String,
): String {
    if (state == "empty") return "No availability data"
    if (state == "closed_for_season") return "Closed for season"
    if (state == "zero_available") return "Fully booked next $days days"
    val availableDates = perDay.count { it.availableCount > 0 }
    val weekendsBooked =
        perDay.any { d ->
            val dow = LocalDate.parse(d.date).dayOfWeek
            (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY) &&
                (d.status == "booked" || d.status == "closed")
        }
    val tail = if (weekendsBooked) " · weekends full" else ""
    val noun = if (availableDates == 1) "date" else "dates"
    return "$availableDates $noun available$tail"
}
```

- [ ] **Step 4: Update provider request DTOs**

In `BookingProvider.kt`, replace request shapes:

```kotlin
data class AvailabilityRequest(
    val ref: ProviderRef,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)

data class CatalogAvailabilityRequest(
    val ref: ProviderRef,
    val reservables: List<CatalogReservableRef>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)

data class ReservableAvailabilityRequest(
    val ref: ProviderRef,
    val vendorId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)

data class AvailableDatesRequest(
    val ref: ProviderRef,
    val startDate: LocalDate,
    val endDate: LocalDate,
)
```

Update the default `catalogAvailability` delegate:

```kotlin
AvailabilityRequest(
    ref = req.ref,
    startDate = req.startDate,
    endDate = req.endDate,
    force = req.force,
)
```

- [ ] **Step 5: Update fake providers in route tests**

Replace fake response helpers in `ReservableRoutesTest` with this shape:

```kotlin
private fun fakeResponse(
    startDate: java.time.LocalDate,
    endDate: java.time.LocalDate,
    campgroundId: String?,
    reservableId: String?,
    availableIds: List<String>? = null,
): AvailabilityResponseDto {
    val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt()
    val perDay =
        (0 until days).map { offset ->
            val availableCount = availableIds?.size ?: 1
            DayClassification(
                date = startDate.plusDays(offset.toLong()).toString(),
                status = if (availableIds?.isEmpty() == true) "booked" else "available",
                availableCount = availableCount,
                total = availableIds?.size ?: 1,
                availableReservableIds = availableIds,
            )
        }
    return availabilityResponseDto(
        provider = "fake",
        startDate = startDate,
        endDate = endDate,
        perDay = perDay,
        state = "success",
        summary = "$days dates available",
        seasonBlock = null,
        cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 60),
        campgroundId = campgroundId,
        reservableId = reservableId,
    )
}
```

Apply the same parameter names to `FakeAspiraBookingProvider.fakeResponse`.

- [ ] **Step 6: Run route tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.ReservableRoutesTest --rerun
```

Expected: compile advances past DTO request/response shapes. Remaining failures point at route/provider implementations still using `start`, `days`, and `minNights`.

- [ ] **Step 7: Commit shared contract**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteAvailabilitySchemas.kt backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt
git commit -m "refactor: introduce date-window availability contract"
```

## Task 3: Provider Adapters Return Per-Day Facts

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/api/AvailabilityPublicRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AspiraAvailabilityService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/adapters/recgov/RecGovBookingProvider.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/adapters/aspira/AspiraBookingProvider.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt`
- Modify tests in `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/*BookingProviderTest.kt`

- [ ] **Step 1: Write RED provider tests for single-day semantics**

In `RecGovBookingProviderTest`, replace the multi-night reservable test body with:

```kotlin
val dto =
    adapter.reservableAvailability(
        ReservableAvailabilityRequest(
            ref = ProviderRef.RecGov("232447"),
            vendorId = "330257",
            startDate = LocalDate.parse("2026-07-01"),
            endDate = LocalDate.parse("2026-07-02"),
        ),
    )

assertEquals("site:recgov:330257", dto.reservableId)
assertEquals("available", dto.availability.single().status)
assertEquals(1, dto.availability.single().availableCount)
```

In `AspiraBookingProviderTest`, replace `start = ... days = ... minNights = ...` request args with:

```kotlin
startDate = LocalDate.parse("2026-06-17"),
endDate = LocalDate.parse("2026-06-18"),
```

- [ ] **Step 2: Run provider tests to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.booking.RecGovBookingProviderTest --tests ca.floo.roadtrip.service.booking.AspiraBookingProviderTest --rerun
```

Expected: FAIL until adapters and helpers stop requiring `days` / `minNights`.

- [ ] **Step 3: Update rec.gov helper signatures**

In `AvailabilityPublicRoutes.kt`, add:

```kotlin
private fun daysBetween(
    startDate: LocalDate,
    endDate: LocalDate,
): Int = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt()
```

Change `fetchAndClassifyRecgov`, `fetchAndClassifyRecgovCatalog`, and `fetchAndClassifyRecgovReservable` signatures to `startDate: LocalDate, endDate: LocalDate`. Inside each:

```kotlin
val days = daysBetween(startDate, endDate)
val months = monthsCovering(startDate, endDate.minusDays(1))
val dates = (0 until days).map { startDate.plusDays(it.toLong()).toString() }
val perDay = dates.map { date -> classifyDay(merged, date) }
```

Update `availabilityResponseDto` calls to pass `startDate = startDate` and `endDate = endDate`.

- [ ] **Step 4: Collapse rec.gov day classification**

Replace `classifyDay` with a single-day version:

```kotlin
private fun classifyDay(
    merged: Map<String, Map<String, String>>,
    date: String,
): DayClassification {
    var available = 0
    var booked = 0
    var closed = 0
    val availableReservableIds = mutableListOf<String>()
    for ((siteId, byDate) in merged) {
        val statusForDate = byDate[date] ?: continue
        when {
            statusForDate.equals("Closed", true) -> closed++
            isOpen(statusForDate) -> {
                available++
                availableReservableIds += "site:recgov:$siteId"
            }
            else -> booked++
        }
    }
    val total = available + booked + closed
    val status =
        when {
            total == 0 -> "closed"
            closed == total -> "closed"
            available > 0 -> "available"
            else -> "booked"
        }
    return DayClassification(date, status, available, total, availableReservableIds.sorted())
}
```

- [ ] **Step 5: Update rec.gov available dates**

Change `availableDatesRecgov` to:

```kotlin
suspend fun availableDatesRecgov(
    cache: CachedAvailability,
    recgovId: String,
    startDate: LocalDate,
    endDate: LocalDate,
): List<String> =
    coroutineScope {
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force = false) } }
                .awaitAll()
        val merged = mergeCampsites(results.map { it.data })
        val days = daysBetween(startDate, endDate)
        (0 until days)
            .map { startDate.plusDays(it.toLong()).toString() }
            .filter { date -> classifyDay(merged, date).availableCount > 0 }
    }
```

- [ ] **Step 6: Update RecGovBookingProvider**

Use request windows directly:

```kotlin
override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
    val recgovId = recgovIdOrThrow(req.ref)
    return runWithErrorMapping {
        fetchAndClassifyRecgov(
            cache = cache,
            recgovId = recgovId,
            startDate = req.startDate,
            endDate = req.endDate,
            force = req.force,
        )
    }
}
```

Apply the same pattern to `catalogAvailability`, `reservableAvailability`, and `availableDates`.

- [ ] **Step 7: Update Aspira service signatures**

For `fetchAndClassifyAspira`, `fetchAndClassifyAspiraCatalog`, and `fetchAndClassifyAspiraResource`, replace `today`, `days`, `minNights` with `startDate`, `endDate`; derive:

```kotlin
val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt()
val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
val perDay = classifyDays(cached.data, startDate, days, 1, reservableVendor)
```

Leave deeply internal helper parameters named `nights` only until the same edit removes the rolling-window calls. Calls into `classify*` should pass `1`.

- [ ] **Step 8: Update Aspira available dates and adapter**

Change adapter methods to pass `req.startDate` and `req.endDate`. Change `availableDatesAspira` to accept `(cache, host, mapId, startDate, endDate)` and return any date in the window with `availableCount > 0`.

- [ ] **Step 9: Simplify snapshot fetch service**

In `ReservableAvailabilityFetchService.Request`, replace:

```kotlin
val start: LocalDate,
val days: Int,
val minNights: Int,
```

with:

```kotlin
val startDate: LocalDate,
val endDate: LocalDate,
```

In `fetch`, call:

```kotlin
ReservableAvailabilityRequest(
    ref = request.ref,
    vendorId = request.vendorId,
    startDate = request.startDate,
    endDate = request.endDate,
    force = request.force,
)
```

Delete the conditional refetch in `appendBaseAvailabilitySnapshot`; append `response` directly.

- [ ] **Step 10: Run provider tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.booking.RecGovBookingProviderTest --tests ca.floo.roadtrip.service.booking.AspiraBookingProviderTest --rerun
```

Expected: PASS.

- [ ] **Step 11: Commit provider changes**

```bash
git add backend/src/main/kotlin/ca/floo/campsite/recgov/booker/api/AvailabilityPublicRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/service/api/AspiraAvailabilityService.kt backend/src/main/kotlin/ca/floo/roadtrip/service/booking/adapters/recgov/RecGovBookingProvider.kt backend/src/main/kotlin/ca/floo/roadtrip/service/booking/adapters/aspira/AspiraBookingProvider.kt backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingProviderTest.kt backend/src/test/kotlin/ca/floo/roadtrip/service/booking/AspiraBookingProviderTest.kt
git commit -m "refactor: return per-day provider availability windows"
```

## Task 4: Route Parsing and Reservable Booking Links

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`

- [ ] **Step 1: Update reservable link tests to RED**

In `ReservableRoutesTest`, change the rec.gov and Aspira catalog URL tests to call:

```kotlin
client.get("/api/poi/$poiId/reservables?type=site&start_date=2026-07-01&end_date=2026-07-03")
```

and:

```kotlin
client.get("/api/poi/$poiId/reservables?start_date=2026-07-01&end_date=2026-07-03")
```

Assert URLs contain exact dates and no `min_nights` request support:

```kotlin
assertEquals(
    "https://www.recreation.gov/camping/campsites/330257?startDate=2026-07-01&endDate=2026-07-03",
    urls["site:recgov:330257"],
)
assertTrue(url.contains("startDate=2026-07-01"), url)
assertTrue(url.contains("endDate=2026-07-03"), url)
assertTrue(url.contains("nights=2"), url)
```

The Aspira `nights=2` assertion stays because upstream deeplinks require a derived duration even though the app contract uses dates.

- [ ] **Step 2: Run link tests to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests "ca.floo.roadtrip.routes.ReservableRoutesTest.poi reservables lists linked site reservables and total count" --tests "ca.floo.roadtrip.routes.ReservableRoutesTest.poi reservables returns aspira booking links from parent provider ref" --rerun
```

Expected: FAIL because `ReservableRoutes` still reads `start` and `min_nights`.

- [ ] **Step 3: Implement route date-window parser**

In `CampsiteAvailabilityRoutes.kt`, replace `AvailabilityQuery` with:

```kotlin
private data class AvailabilityWindowQuery(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean,
) {
    val days: Int = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt()
}
```

Replace `parseAvailabilityQuery` with:

```kotlin
private fun ApplicationCall.parseAvailabilityWindow(
    bookingHorizonDays: Int,
    defaultDays: Int = 7,
): AvailabilityWindowQuery? {
    if (request.queryParameters["days"] != null || request.queryParameters["min_nights"] != null || request.queryParameters["minNights"] != null) {
        return null
    }
    val today = LocalDate.now(java.time.ZoneOffset.UTC)
    val start =
        when (val parsed = parseStartParam(request.queryParameters["start_date"], today, bookingHorizonDays)) {
            is StartParam.Ok -> parsed.value
            StartParam.Invalid -> return null
        }
    val endRaw = request.queryParameters["end_date"]
    val end =
        if (endRaw == null) {
            start.plusDays(defaultDays.toLong())
        } else {
            runCatching { LocalDate.parse(endRaw) }.getOrNull() ?: return null
        }
    if (!end.isAfter(start)) return null
    if (end.isAfter(today.plusDays(bookingHorizonDays.toLong()))) return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()
    if (days !in 1..MAX_AVAILABILITY_DAYS) return null
    val force = request.queryParameters["force"] == "1"
    return AvailabilityWindowQuery(startDate = start, endDate = end, force = force)
}
```

Use `bad_date_window` for null parse results where route code currently returns `bad_start`.

- [ ] **Step 4: Wire POI/reservable availability routes**

Change provider calls in `CampsiteAvailabilityRoutes.kt`:

```kotlin
CatalogAvailabilityRequest(
    ref = ref,
    reservables = catalogRefs,
    startDate = query.startDate,
    endDate = query.endDate,
    force = query.force,
)
```

and:

```kotlin
ReservableAvailabilityFetchService.Request(
    reservableId = row.id,
    reservableRid = rid.encode(),
    provider = provider,
    ref = ref,
    vendorId = rid.vendorId,
    startDate = query.startDate,
    endDate = query.endDate,
    force = query.force,
)
```

Update `emptyPoiAvailability` to take `startDate`/`endDate` and pass both into `availabilityResponseDto`.

- [ ] **Step 5: Wire bulk route**

In the bulk handler, replace `req.start` and `req.nights` parsing with:

```kotlin
val startDate =
    try {
        LocalDate.parse(req.startDate)
    } catch (e: Exception) {
        call.respondApiError("bad_start_date", HttpStatusCode.BadRequest, detail = "start_date must be YYYY-MM-DD")
        return@post
    }
val endDate =
    try {
        LocalDate.parse(req.endDate)
    } catch (e: Exception) {
        call.respondApiError("bad_end_date", HttpStatusCode.BadRequest, detail = "end_date must be YYYY-MM-DD")
        return@post
    }
if (!endDate.isAfter(startDate)) {
    call.respondApiError("bad_date_window", HttpStatusCode.BadRequest, detail = "end_date must be after start_date")
    return@post
}
val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt()
if (days !in 1..MAX_NIGHTS) {
    call.respondApiError("bad_date_window", HttpStatusCode.BadRequest, detail = "date window must be 1..$MAX_NIGHTS days")
    return@post
}
```

Change `fetchOneBulk` to accept `startDate` and `endDate`, and call:

```kotlin
AvailableDatesRequest(ref = ref, startDate = startDate, endDate = endDate)
```

Respond with:

```kotlin
BulkAvailResponseSchema(
    startDate = req.startDate,
    endDate = req.endDate,
    results = results,
)
```

- [ ] **Step 6: Implement reservable exact URL options**

In `ReservableRoutes.kt`, replace `ReservationUrlOptions`:

```kotlin
internal data class ReservationUrlOptions(
    val startDate: LocalDate?,
    val endDate: LocalDate?,
)
```

Replace `reservationUrlOptions`:

```kotlin
private fun ApplicationCall.reservationUrlOptions(): ReservationUrlOptions {
    if (request.queryParameters["min_nights"] != null || request.queryParameters["minNights"] != null || request.queryParameters["start"] != null) {
        throw BadReservableQuery("bad_date_window", "use start_date and end_date")
    }
    val startDate =
        request.queryParameters["start_date"]
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { LocalDate.parse(raw) }
                    .getOrElse { throw BadReservableQuery("bad_start_date", "start_date must be YYYY-MM-DD") }
            }
    val endDate =
        request.queryParameters["end_date"]
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { LocalDate.parse(raw) }
                    .getOrElse { throw BadReservableQuery("bad_end_date", "end_date must be YYYY-MM-DD") }
            }
    if ((startDate == null) != (endDate == null)) {
        throw BadReservableQuery("bad_date_window", "start_date and end_date must be provided together")
    }
    if (startDate != null && !endDate!!.isAfter(startDate)) {
        throw BadReservableQuery("bad_date_window", "end_date must be after start_date")
    }
    return ReservationUrlOptions(startDate = startDate, endDate = endDate)
}
```

Update rec.gov URL:

```kotlin
private fun Reservable.recgovReservationUrl(options: ReservationUrlOptions): String {
    val base = "https://www.recreation.gov/camping/campsites/${urlEncode(rid.vendorId)}"
    val start = options.startDate ?: return base
    val end = options.endDate ?: return base
    return "$base?${queryString("startDate" to start.toString(), "endDate" to end.toString())}"
}
```

Update Aspira URL to pass `startDate` and `endDate`; derive `nights` only inside `aspiraReservationUrl`:

```kotlin
val nights = ChronoUnit.DAYS.between(start, end).toInt()
```

- [ ] **Step 7: Run route tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.ReservableRoutesTest --rerun
```

Expected: PASS.

- [ ] **Step 8: Commit route changes**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteAvailabilityRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt
git commit -m "feat: use date windows in availability routes"
```

## Task 5: Watch Schema, Migration, Repo, and Poller

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__date_window_availability.sql`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityJobIntent.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`

- [ ] **Step 1: Write RED watch route tests**

Update create bodies in `AvailabilityWatchRoutesTest` from:

```json
{"poi_id": 1, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
```

to:

```json
{"poi_id": 1, "start_date": "2026-07-04", "end_date": "2026-07-06", "cadence_sec": 60, "trigger_kinds": ["atc"]}
```

In `POST creates a poi-scoped watch with filters`, assert:

```kotlin
assertEquals("2026-07-04", obj["start_date"]!!.jsonPrimitive.content)
assertEquals("2026-07-06", obj["end_date"]!!.jsonPrimitive.content)
assertTrue("target_dates" !in obj)
assertTrue("min_nights" !in obj)
```

Add invalid range test:

```kotlin
@Test
fun `POST rejects invalid watch date window`() =
    testApplication {
        application {
            routing {
                availabilityWatchRoutes(
                    ctx,
                    ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                        ctx,
                        ca.floo.roadtrip.repo.ReservableRepo(ctx),
                    ),
                )
            }
        }
        val poiId = seedPoi(sourceId = "bad-window", name = "Bad Window")
        val resp =
            client.post("/api/availability/watches") {
                contentType(ContentType.Application.Json)
                setBody("""{"poi_id":$poiId,"start_date":"2026-07-06","end_date":"2026-07-04","cadence_sec":60,"trigger_kinds":["atc"]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("invalid_date_window", obj["error"]!!.jsonPrimitive.content)
    }
```

- [ ] **Step 2: Run watch tests to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest --rerun
```

Expected: FAIL because watch schemas and repo still use `target_dates` and `min_nights`.

- [ ] **Step 3: Add Flyway migration**

Create `V19__date_window_availability.sql`:

```sql
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM availability_watch
    WHERE target_dates IS NULL OR cardinality(target_dates) = 0
  ) THEN
    RAISE EXCEPTION 'availability_watch target_dates must be non-empty before V19 migration';
  END IF;
END $$;

ALTER TABLE availability_watch
  ADD COLUMN start_date DATE,
  ADD COLUMN end_date DATE;

UPDATE availability_watch
SET
  start_date = (
    SELECT min(value)
    FROM unnest(target_dates) AS dates(value)
  ),
  end_date = (
    SELECT max(value)
    FROM unnest(target_dates) AS dates(value)
  ) + min_nights;

ALTER TABLE availability_watch
  ALTER COLUMN start_date SET NOT NULL,
  ALTER COLUMN end_date SET NOT NULL,
  ADD CONSTRAINT availability_watch_date_window_check CHECK (end_date > start_date),
  DROP COLUMN target_dates,
  DROP COLUMN min_nights;

ALTER TABLE alerts
  DROP COLUMN min_nights;
```

- [ ] **Step 4: Regenerate jOOQ**

Run from `backend/`:

```bash
./gradlew generateJooq
```

Expected: SUCCESS and generated bindings under `backend/build/generated/jooq/main` no longer expose `MIN_NIGHTS` or `TARGET_DATES` on `AVAILABILITY_WATCH` or `ALERTS`.

- [ ] **Step 5: Update watch API schemas**

Replace request/response date fields:

```kotlin
@Serializable
data class AvailabilityWatchCreateRequest(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("reservable_rid") val reservableRid: String? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)
```

Use nullable fields on update:

```kotlin
@SerialName("start_date") val startDate: String? = null,
@SerialName("end_date") val endDate: String? = null,
```

In `AvailabilityWatchSchema`, use:

```kotlin
@SerialName("start_date") val startDate: String,
@SerialName("end_date") val endDate: String,
```

- [ ] **Step 6: Update repo models and SQL mapping**

In `AvailabilityWatchRepo`, replace `targetDates` and `minNights` in `CreateInput`, `UpdateInput`, and `Watch` with:

```kotlin
val startDate: LocalDate,
val endDate: LocalDate,
```

and nullable update fields:

```kotlin
val startDate: LocalDate? = null,
val endDate: LocalDate? = null,
```

Use generated columns:

```kotlin
.set(AVAILABILITY_WATCH.START_DATE, input.startDate)
.set(AVAILABILITY_WATCH.END_DATE, input.endDate)
```

and in `fromRecord`:

```kotlin
startDate = r.get(AVAILABILITY_WATCH.START_DATE)!!,
endDate = r.get(AVAILABILITY_WATCH.END_DATE)!!,
```

- [ ] **Step 7: Update route validation and schema conversion**

In `AvailabilityWatchRoutes.kt`, parse create:

```kotlin
val startDate = runCatching { LocalDate.parse(req.startDate) }.getOrElse {
    return@post call.respondError("invalid_date_window", HttpStatusCode.BadRequest, it.message)
}
val endDate = runCatching { LocalDate.parse(req.endDate) }.getOrElse {
    return@post call.respondError("invalid_date_window", HttpStatusCode.BadRequest, it.message)
}
if (!endDate.isAfter(startDate)) {
    return@post call.respondError("invalid_date_window", HttpStatusCode.BadRequest, "end_date must be after start_date")
}
```

Pass `startDate` and `endDate` into `AvailabilityWatchRepo.CreateInput`.

In update, parse both when either is present; reject if only one is set:

```kotlin
if ((req.startDate == null) != (req.endDate == null)) {
    return@patch call.respondError("invalid_date_window", HttpStatusCode.BadRequest, "start_date and end_date must be updated together")
}
```

In `Watch.toSchema`, emit:

```kotlin
startDate = startDate.toString(),
endDate = endDate.toString(),
```

- [ ] **Step 8: Update job intent and watch service**

In `AvailabilityJobIntent`, replace `targetDates` and `minNights` with:

```kotlin
abstract val startDate: String
abstract val endDate: String
```

Each variant should serialize:

```kotlin
@SerialName("start_date") override val startDate: String,
@SerialName("end_date") override val endDate: String,
```

In `AvailabilityWatchService.buildIntent`, pass watch date strings:

```kotlin
startDate = watch.startDate.toString(),
endDate = watch.endDate.toString(),
```

- [ ] **Step 9: Update poller and heatmap date derivation**

In `AvailabilityPollExecutor.runReservable`, replace target-date range computation with:

```kotlin
val startDate = LocalDate.parse(intent.startDate)
val endDate = LocalDate.parse(intent.endDate)
```

and pass both into `ReservableAvailabilityFetchService.Request`.

In `AvailabilityWatchRoutes.resolveChildren` heatmap handler, derive row dates:

```kotlin
val targetDates =
    generateSequence(watch.startDate) { d ->
        d.plusDays(1).takeIf { it.isBefore(watch.endDate) }
    }.toList()
```

Use `targetDates` for `heatmaps.loadHeatmap` and response cells.

- [ ] **Step 10: Run watch tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest --rerun
```

Expected: PASS.

- [ ] **Step 11: Commit watch changes**

```bash
git add backend/src/main/resources/db/migration/V19__date_window_availability.sql backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityJobIntent.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollExecutor.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "feat: store availability watches as date windows"
```

## Task 6: Deprecated rec.gov Alerts Hard Removal

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/domain/Models.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/api/CampsiteApiJson.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/api/AlertRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/db/AlertRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/matching/Matcher.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/poller/Poller.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/availability/AvailabilityManager.kt`
- Modify: `backend/src/main/kotlin/ca/floo/campsite/recgov/booker/notifier/SlackNotifier.kt`
- Modify: legacy campsite tests under `backend/src/test/kotlin/ca/floo/campsite/recgov/booker/**`

- [ ] **Step 1: Write RED legacy alert API assertions**

In `CampsiteApiRoutesIT`, update create-alert JSON to omit `min_nights` and assert the alert response has no field:

```kotlin
assertTrue("min_nights" !in item)
```

Where tests assert `min_nights`, replace them with start/end assertions:

```kotlin
assertEquals("2026-07-04", item["start_date"]!!.jsonPrimitive.content)
assertEquals("2026-07-06", item["end_date"]!!.jsonPrimitive.content)
```

- [ ] **Step 2: Run legacy alert tests to verify RED**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.campsite.recgov.booker.api.CampsiteApiRoutesIT --tests ca.floo.campsite.recgov.booker.matching.MatcherTest --tests ca.floo.campsite.recgov.booker.notifier.SlackNotifierTest --rerun
```

Expected: FAIL because alert DTO/domain/repo still expose `minNights`.

- [ ] **Step 3: Remove alert minNights from DTO/domain/repo**

Remove `minNights` from:

```kotlin
AlertCreateRequestDto
AlertPatchRequestDto
AlertDto
Alert
AlertRepo.CreateInput
```

Delete these assignments:

```kotlin
minNights = body.minNights
body.minNights?.let { updates["min_nights"] = it }
rec.minNights = input.minNights
"min_nights" -> rec.minNights = (v as Number).toInt()
minNights = r.minNights ?: 1
```

- [ ] **Step 4: Derive alert stay length in pollers**

Add helper near poller usage sites:

```kotlin
private fun stayLength(alert: Alert): Int =
    java.time.temporal.ChronoUnit.DAYS
        .between(LocalDate.parse(alert.startDate), LocalDate.parse(alert.endDate))
        .toInt()
        .coerceAtLeast(1)
```

Replace `alert.minNights` with `stayLength(alert)` in `Poller` and `AvailabilityManager`.

- [ ] **Step 5: Remove Slack min-night block**

In `SlackNotifier`, delete the field block:

```kotlin
mrkdwn("*Min nights*\n${alert.minNights}")
```

Keep start/end date information in the notification.

- [ ] **Step 6: Run legacy alert tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.campsite.recgov.booker.api.CampsiteApiRoutesIT --tests ca.floo.campsite.recgov.booker.matching.MatcherTest --tests ca.floo.campsite.recgov.booker.notifier.SlackNotifierTest --rerun
```

Expected: PASS.

- [ ] **Step 7: Commit legacy alert removal**

```bash
git add backend/src/main/kotlin/ca/floo/campsite/recgov/booker backend/src/test/kotlin/ca/floo/campsite/recgov/booker
git commit -m "refactor: remove min nights from deprecated alerts"
```

## Task 7: Frontend Date Windows

**Files:**
- Modify: `web/api/availability-api.js`
- Modify: `web/api/reservable-api.js`
- Modify: `web/api/campsite-alert-api.js`
- Modify: `web/availability/availability-week.js`
- Modify: `web/availability/day-detail.js`
- Modify: `web/availability/site-detail.js`
- Modify: `web/availability/site-matrix.js`
- Modify: `web/components/availability-panel.js`
- Modify: `web/components/reservable-table.js`
- Modify: `web/watches.js`
- Modify: `web/watch-detail.js`
- Modify: `watches.html`
- Modify: `backend/src/main/resources/static/campsite/index.html`
- Modify: `backend/src/main/resources/static/campsite/campsite-alert-forms.js`
- Modify: `backend/src/main/resources/static/campsite/campsite-data.js`

- [ ] **Step 1: Write simple frontend grep checks**

Before editing, run from repo root:

```bash
rg -n "Min nights|min_nights|minNights|cg\\.minNights|data-nights" web watches.html watch-detail.html backend/src/main/resources/static/campsite
```

Expected: matches exist.

- [ ] **Step 2: Update JS API clients**

In `web/api/availability-api.js`, change:

```js
export function requestCampsiteAvailability(id, { startDate, endDate, siteType, force, signal } = {}) {
  const params = new URLSearchParams();
  if (startDate) params.set('start_date', startDate);
  if (endDate) params.set('end_date', endDate);
  if (siteType) params.set('site_type', siteType);
  if (force) params.set('force', '1');
  return fetch(`/api/poi/${encodeURIComponent(id)}/availability?${params}`, { signal });
}

export async function fetchBulkAvailability({ ids, startDate, endDate, signal }) {
  return jsonPostOk('/api/campsite/availability/bulk', { ids, start_date: startDate, end_date: endDate }, { signal });
}
```

In `web/api/reservable-api.js`, use `startDate` and `endDate` in `fetchPoiReservables`, `fetchReservableAvailability`, and `reservableAvailabilityUrl`.

- [ ] **Step 3: Update drawer week defaults**

In `availability-week.js`, remove:

```js
const STORAGE_KEY_MIN_NIGHTS = 'cg.minNights';
const DEFAULT_MIN_NIGHTS = 1;
const MIN_NIGHTS_CHIPS = [1, 2, 3, 7];
```

Remove `minNights` from context and delete `renderNightsRow`, `loadMinNights`, and `saveMinNights`.

Add:

```js
function currentWeekEnd(ctx) {
  return addDays(ctx.weekStart, WEEK_DAYS);
}
```

Change fetches:

```js
const resp = await requestCampsiteAvailability(ctx.poiId, {
  startDate: isoDate(ctx.weekStart),
  endDate: isoDate(currentWeekEnd(ctx)),
  force,
  signal: ctx.signal,
});
```

For matrix pagination:

```js
const nextEnd = addDays(nextStart, WEEK_DAYS);
```

- [ ] **Step 4: Remove frontend stay-length params**

In `availability-week.js`, update site fetch:

```js
const end = start ? isoDate(addDays(parseIsoDate(start), 1)) : null;
const json = await fetchPoiReservables(ctx.poiId, {
  startDate: start,
  endDate: end,
  signal: ctx.signal,
});
```

Update alert payload:

```js
return {
  campground_id: String(ctx.recgovId),
  campground_name: ctx.feature.properties?.name || `Campground ${ctx.recgovId}`,
  parent_name: ctx.feature.properties?.parent_name || null,
  parent_id: ctx.feature.properties?.parent_id || null,
  start_date: date,
  end_date: isoDate(addDays(parseIsoDate(date), 1)),
  campsite_types: [],
  equipment_types: [],
  notify_slack: false,
  auto_cart: false,
  stop_after_match: true,
};
```

In `campsite-alert-api.js`, change `findMatchingAlert` signature to `{ campgroundId, startDate, endDate }` and compare both dates.

- [ ] **Step 5: Update availability panel controls**

In `web/components/availability-panel.js`, replace the Min nights label with End:

```html
<label>
  End
  <input name="end_date" type="date" value="${escapeHtml(query.endDate)}">
</label>
```

Change query object:

```js
return {
  startDate: String(data.get('start_date') || '').trim(),
  endDate: String(data.get('end_date') || '').trim(),
  force: data.get('force') === 'on',
};
```

Change URL builder:

```js
const params = new URLSearchParams({
  start_date: query.startDate || utcYmd(new Date()),
  end_date: query.endDate || utcYmd(addUtcDays(new Date(), 7)),
});
```

Define `addUtcDays` in the same file:

```js
function addUtcDays(date, days) {
  const copy = new Date(date);
  copy.setUTCDate(copy.getUTCDate() + days);
  return copy;
}
```

- [ ] **Step 6: Update watch admin UI**

In `watches.html`, replace target dates and min nights inputs with:

```html
<label class="narrow">Start date <input name="start_date" type="date" required></label>
<label class="narrow">End date <input name="end_date" type="date" required></label>
```

In `web/watches.js`, build create/update payloads:

```js
start_date: String(fd.get('start_date') || '').trim(),
end_date: String(fd.get('end_date') || '').trim(),
```

Update `prefillCreateFormFromUrl` fields:

```js
const fields = ['poi_id', 'reservable_rid', 'start_date', 'end_date', 'cadence_sec', 'trigger_kinds'];
```

Update row display from target count to range:

```js
const dates = `${w.start_date} → ${w.end_date}`;
```

In `web/watch-detail.js`, remove min nights and render:

```js
<dt>date window</dt><dd>${escapeHtml(w.start_date)} → ${escapeHtml(w.end_date)}</dd>
```

- [ ] **Step 7: Update legacy static campsite UI**

In `backend/src/main/resources/static/campsite/index.html`, remove `id="min-nights"` and `id="edit-min-nights"` controls.

In `campsite-alert-forms.js`, remove `min_nights` from create/edit payloads and from edit form fill.

In `campsite-data.js`, replace:

```js
<span>&#x1f319; ${a.min_nights}+ nights</span>
```

with:

```js
<span>${a.start_date} → ${a.end_date}</span>
```

- [ ] **Step 8: Run frontend grep check**

Run from repo root:

```bash
rg -n "Min nights|min_nights|minNights|cg\\.minNights|data-nights" web watches.html watch-detail.html backend/src/main/resources/static/campsite
```

Expected: no matches.

- [ ] **Step 9: Commit frontend changes**

```bash
git add web watches.html watch-detail.html backend/src/main/resources/static/campsite
git commit -m "feat: use date windows in availability UI"
```

## Task 8: Full Hard-Removal Sweep

**Files:**
- Search and modify all live files under `backend/src/main`, `backend/src/test`, `web`, root HTML pages, and `backend/src/main/resources/static/campsite`.

- [ ] **Step 1: Search live code for removed identifiers**

Run from repo root:

```bash
rg -n "min_nights|minNights|Min nights|MIN_NIGHTS|target_dates|targetDates|TARGET_DATES" backend/src/main backend/src/test web *.html
```

Expected: matches only in old RFCs/docs are excluded by this command. Live-code matches must be removed or justified by legacy generated files that are regenerated by Gradle.

- [ ] **Step 2: Remove remaining live-code matches**

For each match:

```text
backend/src/main/...: remove field/param/control and replace with start_date/end_date if the code still serves a live route or UI.
backend/src/test/...: update fixture/request/assertion to date-window fields.
web/...: remove UI state or request params.
*.html: remove visible min-night controls.
```

- [ ] **Step 3: Run focused backend tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.ReservableRoutesTest --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest --tests ca.floo.roadtrip.service.booking.RecGovBookingProviderTest --tests ca.floo.roadtrip.service.booking.AspiraBookingProviderTest --tests ca.floo.campsite.recgov.booker.api.CampsiteApiRoutesIT --tests ca.floo.campsite.recgov.booker.matching.MatcherTest --tests ca.floo.campsite.recgov.booker.notifier.SlackNotifierTest --rerun
```

Expected: PASS.

- [ ] **Step 4: Commit sweep**

```bash
git add backend/src/main backend/src/test web '*.html'
git commit -m "chore: remove remaining min-night live code"
```

## Task 9: Final Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run Kotlin compile**

Run from `backend/`:

```bash
./gradlew compileKotlin
```

Expected: SUCCESS.

- [ ] **Step 2: Run backend tests**

Run from `backend/`:

```bash
./gradlew test
```

Expected: SUCCESS.

- [ ] **Step 3: Run ktlint**

Run from `backend/`:

```bash
./gradlew ktlintCheck
```

Expected: SUCCESS.

- [ ] **Step 4: Run hard-removal search**

Run from repo root:

```bash
rg -n "min_nights|minNights|Min nights|MIN_NIGHTS|target_dates|targetDates|TARGET_DATES" backend/src/main backend/src/test web *.html
```

Expected: no matches.

- [ ] **Step 5: Inspect git diff**

Run from repo root:

```bash
git diff --stat HEAD~9..HEAD
git status --short
```

Expected: committed implementation changes are present; no accidental generated jOOQ files are staged; working tree is clean except for files intentionally left by the user.
