# Availability Status Enum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace loose availability status strings with a canonical enum (`first_come`, `reserved`, `available`, `closed`, `unknown`) across the DB, Kotlin API model, provider classifiers, and frontend renderers.

**Architecture:** The Postgres enum is the durable schema boundary. Kotlin provider and API code use a domain enum in `service/api`; repository code converts that domain enum to the jOOQ-generated Postgres enum at the DB boundary. POI-scoped availability responses add `reservable_statuses` so each lowest-level reservable/day can show `FF`, `R`, `A`, `C`, or `?` independently.

**Tech Stack:** Kotlin 2.x, Ktor, kotlinx.serialization, jOOQ generated from Flyway migrations, Postgres, vanilla JS frontend.

---

## File Structure

- Create: `backend/src/main/resources/db/migration/V23__availability_status_enum.sql`
  - Defines `availability_status`, migrates `availability_snapshot.status`, and maps legacy `partial`.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityStatus.kt`
  - Domain enum, lowercase wire encoding, and parse helpers.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt`
  - Replace `String` status fields with `AvailabilityStatus`, add `reservable_statuses`, and centralize rollups.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityService.kt`
  - Map Rec.gov upstream statuses into the canonical enum, including `Not Reservable -> first_come`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatus.kt`
  - Return canonical enum values and map unknown Aspira integer codes to `unknown`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AspiraAvailabilityService.kt`
  - Use enum helpers and emit per-resource status maps for catalog responses.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`
  - Persist the DB enum and expose snapshots as `AvailabilityStatus`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt`
  - Parse latest snapshot status into `AvailabilityStatus`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt`
  - Type snapshot API status as `AvailabilityStatus`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`
  - Type watch heatmap status as `AvailabilityStatus?`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityRoutes.kt`
  - Use `AvailabilityStatus.UNKNOWN` for empty day placeholders.
- Modify tests under:
  - `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityServiceTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingProviderTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatusTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/AspiraBookingProviderTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStatsTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`
  - `backend/src/test/kotlin/ca/floo/roadtrip/SmokeTest.kt`
- Modify frontend renderers:
  - `web/availability/site-matrix.js`
  - `web/availability/week-grid.js`
  - `web/availability/day-detail.js`
  - `web/components/availability-panel.js`
  - `web/components/availability/watch-heatmap.js`
  - `web/components/availability/snapshots-tab.js`
- Modify frontend styles:
  - `index.html`
  - `web/components/catalog.css`
  - `watch-detail.html`
- Modify docs:
  - `docs/booking-providers.md`
  - `docs/booking-providers/aspira.md`
  - `docs/superpowers/specs/2026-06-15-availability-watches-design.md`

---

## Task 1: Database enum migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__availability_status_enum.sql`

- [ ] **Step 1: Add the migration**

Create `backend/src/main/resources/db/migration/V23__availability_status_enum.sql`:

```sql
CREATE TYPE availability_status AS ENUM (
  'first_come',
  'reserved',
  'available',
  'closed',
  'unknown'
);

ALTER TABLE availability_snapshot
  DROP CONSTRAINT IF EXISTS reservable_availability_log_status_check;

ALTER TABLE availability_snapshot
  ALTER COLUMN status TYPE availability_status
  USING (
    CASE
      WHEN status = 'available' THEN 'available'
      WHEN status = 'booked' THEN 'reserved'
      WHEN status = 'closed' THEN 'closed'
      WHEN status = 'partial' AND available THEN 'available'
      WHEN status = 'partial' AND NOT available THEN 'reserved'
      ELSE 'unknown'
    END
  )::availability_status;
```

- [ ] **Step 2: Regenerate jOOQ**

Run from `backend/`:

```bash
./gradlew generateJooq
```

Expected: `BUILD SUCCESSFUL`. Generated files stay under `backend/build/generated/jooq/main` and are not committed.

- [ ] **Step 3: Run migration/codegen drift check**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.repo.JooqCodegenDriftTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V23__availability_status_enum.sql
git commit -m "db: add availability status enum"
```

---

## Task 2: Domain enum and response DTO

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityStatus.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt`

- [ ] **Step 1: Write failing serialization tests**

In `AvailabilityResponseTest.kt`, update the existing `availability renderer serializes stable dto shape` test to use the enum and assert `reservable_statuses`:

```kotlin
DayClassification(
    date = "2026-06-10",
    status = AvailabilityStatus.AVAILABLE,
    availableCount = 3,
    total = 5,
    availableReservableIds = listOf("site:recgov:100", "site:recgov:200", "site:recgov:300"),
    reservableStatuses =
        mapOf(
            "site:recgov:100" to AvailabilityStatus.AVAILABLE,
            "site:recgov:200" to AvailabilityStatus.FIRST_COME,
            "site:recgov:300" to AvailabilityStatus.RESERVED,
        ),
)
```

Add these assertions near the existing day assertions:

```kotlin
assertEquals("available", availabilityDay["status"]!!.jsonPrimitive.content)
assertEquals(
    "first_come",
    availabilityDay["reservable_statuses"]!!
        .jsonObject["site:recgov:200"]!!
        .jsonPrimitive.content,
)
```

Add a new test in `AvailabilityResponseTest.kt`:

```kotlin
@Test
fun `status rollup keeps unknown distinct from closed`() {
    val unknown =
        dayClassificationFromReservableStatuses(
            date = "2026-06-10",
            statuses = mapOf("site:recgov:100" to AvailabilityStatus.UNKNOWN),
        )
    val closed =
        dayClassificationFromReservableStatuses(
            date = "2026-06-10",
            statuses = mapOf("site:recgov:100" to AvailabilityStatus.CLOSED),
        )

    assertEquals(AvailabilityStatus.UNKNOWN, unknown.status)
    assertEquals(AvailabilityStatus.CLOSED, closed.status)
    assertEquals("success", classifyWindowState(listOf(unknown)))
    assertEquals("closed_for_season", classifyWindowState(listOf(closed)))
}
```

- [ ] **Step 2: Run tests to verify failure**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.api.AvailabilityResponseTest
```

Expected: compile failure because `AvailabilityStatus` and `reservableStatuses` do not exist yet.

- [ ] **Step 3: Add the enum**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityStatus.kt`:

```kotlin
package ca.floo.roadtrip.service.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AvailabilityStatus(
    val wireValue: String,
) {
    @SerialName("first_come")
    FIRST_COME("first_come"),

    @SerialName("reserved")
    RESERVED("reserved"),

    @SerialName("available")
    AVAILABLE("available"),

    @SerialName("closed")
    CLOSED("closed"),

    @SerialName("unknown")
    UNKNOWN("unknown"),
    ;

    val isOnlineBookable: Boolean
        get() = this == AVAILABLE

    companion object {
        fun parse(raw: String?): AvailabilityStatus =
            entries.firstOrNull { it.wireValue == raw?.lowercase() } ?: UNKNOWN
    }
}
```

- [ ] **Step 4: Update `AvailabilityResponse.kt`**

In `AvailabilityResponse.kt`, replace `DayClassification` with:

```kotlin
data class DayClassification(
    val date: String,
    val status: AvailabilityStatus,
    val availableCount: Int,
    val total: Int,
    val availableReservableIds: List<String>? = null,
    val reservableStatuses: Map<String, AvailabilityStatus>? = null,
)
```

Replace `classifyWindowState` with:

```kotlin
fun classifyWindowState(days: List<DayClassification>): String {
    val total = days.sumOf { it.total }
    if (total == 0) return "empty"
    val allClosed = days.all { it.status == AvailabilityStatus.CLOSED || it.total == 0 }
    val allReserved = days.all { it.status == AvailabilityStatus.RESERVED }
    val anyVisible =
        days.any {
            it.status == AvailabilityStatus.AVAILABLE ||
                it.status == AvailabilityStatus.FIRST_COME ||
                it.status == AvailabilityStatus.UNKNOWN
        }
    return when {
        allClosed -> "closed_for_season"
        anyVisible -> "success"
        allReserved -> "zero_available"
        else -> "success"
    }
}
```

Replace the status checks in `summarizeWindow`:

```kotlin
if (state == "zero_available") return "Reserved next $days days"
val availableDates = perDay.count { it.status == AvailabilityStatus.AVAILABLE }
val firstComeDates = perDay.count { it.status == AvailabilityStatus.FIRST_COME }
val unknownDates = perDay.count { it.status == AvailabilityStatus.UNKNOWN }
val weekendsUnavailable =
    perDay.any { d ->
        val dow = LocalDate.parse(d.date).dayOfWeek
        (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY) &&
            (d.status == AvailabilityStatus.RESERVED || d.status == AvailabilityStatus.CLOSED)
    }
val tail = if (weekendsUnavailable) " · weekends unavailable" else ""
return when {
    availableDates > 0 -> {
        val noun = if (availableDates == 1) "date" else "dates"
        "$availableDates $noun available$tail"
    }
    firstComeDates > 0 -> {
        val noun = if (firstComeDates == 1) "date" else "dates"
        "$firstComeDates $noun first-come$tail"
    }
    unknownDates > 0 -> "Availability unknown"
    else -> "No availability data"
}
```

Add these helpers below `summarizeWindow`:

```kotlin
fun dayClassificationFromReservableStatuses(
    date: String,
    statuses: Map<String, AvailabilityStatus>,
): DayClassification {
    val sorted = statuses.toSortedMap()
    val availableIds =
        sorted
            .filterValues { it == AvailabilityStatus.AVAILABLE }
            .keys
            .toList()
    return DayClassification(
        date = date,
        status = rollupStatus(sorted.values),
        availableCount = availableIds.size,
        total = sorted.size,
        availableReservableIds = availableIds,
        reservableStatuses = sorted,
    )
}

fun dayClassificationFromStatuses(
    date: String,
    statuses: List<AvailabilityStatus>,
): DayClassification =
    DayClassification(
        date = date,
        status = rollupStatus(statuses),
        availableCount = statuses.count { it == AvailabilityStatus.AVAILABLE },
        total = statuses.size,
    )

fun rollupStatus(statuses: Iterable<AvailabilityStatus>): AvailabilityStatus {
    val values = statuses.toList()
    if (values.isEmpty()) return AvailabilityStatus.UNKNOWN
    return when {
        values.any { it == AvailabilityStatus.AVAILABLE } -> AvailabilityStatus.AVAILABLE
        values.any { it == AvailabilityStatus.FIRST_COME } -> AvailabilityStatus.FIRST_COME
        values.any { it == AvailabilityStatus.RESERVED } -> AvailabilityStatus.RESERVED
        values.all { it == AvailabilityStatus.CLOSED } -> AvailabilityStatus.CLOSED
        else -> AvailabilityStatus.UNKNOWN
    }
}
```

In `availabilityResponseDto`, pass the new field:

```kotlin
reservableStatuses = day.reservableStatuses,
```

Replace `AvailabilityDayDto.status` and add `reservableStatuses`:

```kotlin
val status: AvailabilityStatus,
@SerialName("available_count") val availableCount: Int,
val total: Int,
@SerialName("available_reservable_ids") val availableReservableIds: List<String>? = null,
@SerialName("reservable_statuses") val reservableStatuses: Map<String, AvailabilityStatus>? = null,
```

- [ ] **Step 5: Update empty availability route**

In `AvailabilityRoutes.kt`, import `AvailabilityStatus` and change the empty response day creation:

```kotlin
status = AvailabilityStatus.UNKNOWN,
```

- [ ] **Step 6: Run tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.api.AvailabilityResponseTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityStatus.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponse.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityRoutes.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt
git commit -m "feat: add availability status domain enum"
```

---

## Task 3: Rec.gov enum classifier

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityService.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityServiceTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingProviderTest.kt`

- [ ] **Step 1: Add failing Rec.gov tests**

In `RecGovAvailabilityServiceTest.kt`, add:

```kotlin
@Test
fun `not reservable maps to first come first served`() {
    val map =
        mapOf(
            "100" to campsiteWith(mapOf(futureKey(0) to "Not Reservable")),
            "200" to campsiteWith(mapOf(futureKey(0) to "Reserved")),
        )
    val body = classify(cacheReturning(map), days = 1)
    val day = body["availability"]!!.jsonArray[0].jsonObject

    assertEquals("first_come", day["status"]!!.jsonPrimitive.content)
    assertEquals(0, day["available_count"]!!.jsonPrimitive.content.toInt())
    assertEquals(
        "first_come",
        day["reservable_statuses"]!!
            .jsonObject["site:recgov:100"]!!
            .jsonPrimitive.content,
    )
    assertEquals(
        "reserved",
        day["reservable_statuses"]!!
            .jsonObject["site:recgov:200"]!!
            .jsonPrimitive.content,
    )
}

@Test
fun `missing recgov date row maps to unknown`() {
    val map = mapOf("100" to campsiteWith(emptyMap()))
    val body = classify(cacheReturning(map), days = 1)
    val day = body["availability"]!!.jsonArray[0].jsonObject

    assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
    assertEquals(
        "unknown",
        day["reservable_statuses"]!!
            .jsonObject["site:recgov:100"]!!
            .jsonPrimitive.content,
    )
}
```

In `RecGovBookingProviderTest.kt`, update string status assertions from `"available"` to enum assertions after Task 2:

```kotlin
assertEquals(AvailabilityStatus.AVAILABLE, dto.availability.single().status)
```

Add the import:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
```

- [ ] **Step 2: Run tests to verify failure**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.api.recgov.RecGovAvailabilityServiceTest --tests ca.floo.roadtrip.service.booking.RecGovBookingProviderTest
```

Expected: tests fail because Rec.gov still collapses `Not Reservable` and missing rows.

- [ ] **Step 3: Update Rec.gov classifier**

In `RecGovAvailabilityService.kt`, add imports:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.service.api.dayClassificationFromReservableStatuses
```

Replace `classifyDay` with:

```kotlin
private fun classifyDay(
    merged: Map<String, Map<String, String>>,
    date: String,
): DayClassification {
    val statuses =
        merged
            .mapKeys { (siteId, _) -> "site:recgov:$siteId" }
            .mapValues { (_, byDate) -> recgovStatus(byDate[date]) }
    return dayClassificationFromReservableStatuses(date, statuses)
}
```

Replace `isOpen` with:

```kotlin
private fun recgovStatus(raw: String?): AvailabilityStatus =
    when {
        raw == null -> AvailabilityStatus.UNKNOWN
        raw.equals("Available", true) || raw.equals("Open", true) -> AvailabilityStatus.AVAILABLE
        raw.equals("Not Reservable", true) -> AvailabilityStatus.FIRST_COME
        raw.equals("Closed", true) -> AvailabilityStatus.CLOSED
        raw.equals("Reserved", true) -> AvailabilityStatus.RESERVED
        else -> AvailabilityStatus.RESERVED
    }

private fun isOnlineBookable(s: String?): Boolean = recgovStatus(s) == AvailabilityStatus.AVAILABLE
```

Update `inferReopenDate` to call `isOnlineBookable(status)` instead of checking `Available`/`Open` directly.

Leave `availableDatesRecgov` unchanged; it filters `cls.availableCount > 0`, which remains correct because only `available` is online bookable.

- [ ] **Step 4: Run tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.service.api.recgov.RecGovAvailabilityServiceTest --tests ca.floo.roadtrip.service.booking.RecGovBookingProviderTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityService.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/service/api/recgov/RecGovAvailabilityServiceTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingProviderTest.kt
git commit -m "feat: classify recgov first-come availability"
```

---

## Task 4: Aspira enum classifier

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatus.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AspiraAvailabilityService.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatusTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/AspiraBookingProviderTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt`

- [ ] **Step 1: Add failing Aspira tests**

In `AspiraStatusTest.kt`, import `AvailabilityStatus` and update assertions to enum values. Replace the unknown test with:

```kotlin
@Test
fun `unknown code maps to unknown`() {
    assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(99))
    assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(-1))
}
```

In `AspiraBookingProviderTest.kt`, update string status assertions:

```kotlin
assertEquals(AvailabilityStatus.AVAILABLE, dto.availability.single().status)
```

Add a test:

```kotlin
@Test
fun `aspira catalog availability marks missing resource days unknown`() =
    runBlocking {
        val mapCache =
            CachedAspiraAvailability(
                fetcher = { _, mapId, _, _ ->
                    AspiraAvailability(
                        mapId = mapId,
                        parkRollup = emptyList(),
                        byMapLink = emptyMap(),
                        byResource = mapOf("100" to emptyList()),
                    )
                },
            )
        val adapter =
            AspiraBookingProvider(
                tenant =
                    AspiraTenant(
                        host = "reservation.pc.gc.ca",
                        vendorCode = "aspira_pc",
                        bookingHorizonDays = 365,
                    ),
                cache = mapCache,
            )

        val dto =
            adapter.catalogAvailability(
                CatalogAvailabilityRequest(
                    ref =
                        ProviderRef.Aspira(
                            transactionLocationId = -2147483630,
                            mapId = -2147483388,
                            resourceLocationId = null,
                        ),
                    reservables =
                        listOf(
                            CatalogReservableRef(
                                rid = "site:aspira_pc:100",
                                vendorId = "100",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                        ),
                    startDate = LocalDate.parse("2026-06-17"),
                    endDate = LocalDate.parse("2026-06-18"),
                ),
            )

        assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
        assertEquals(
            mapOf("site:aspira_pc:100" to AvailabilityStatus.UNKNOWN),
            dto.availability.single().reservableStatuses,
        )
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.models.aspira.AspiraStatusTest --tests ca.floo.roadtrip.service.booking.AspiraBookingProviderTest --tests ca.floo.roadtrip.service.api.AvailabilityResponseTest
```

Expected: compile/test failure because Aspira still returns strings and unknown codes map to partial.

- [ ] **Step 3: Update `AspiraStatus.kt`**

Replace the `classify` function:

```kotlin
fun classify(code: Int): AvailabilityStatus =
    when (code) {
        AVAILABLE, LIMITED, PARTIAL, MIXED, MOSTLY_BOOKED -> AvailabilityStatus.AVAILABLE
        UNAVAILABLE -> AvailabilityStatus.CLOSED
        NO_DATA -> AvailabilityStatus.UNKNOWN
        else -> AvailabilityStatus.UNKNOWN
    }
```

Add import:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
```

Update the file comment so it says `NO_DATA` and unfamiliar codes are `UNKNOWN`, not `partial` or closed.

- [ ] **Step 4: Update `AspiraAvailabilityService.kt`**

Add imports:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.service.api.dayClassificationFromReservableStatuses
import ca.floo.roadtrip.service.api.dayClassificationFromStatuses
```

Replace status comparisons like:

```kotlin
when (arrivalCls) {
    "closed" -> closed++
    "available", "partial" -> {
        available++
        availableReservableIds += "site:$reservableVendor:$resourceId"
    }
    else -> booked++
}
```

with status-map builders. For `classifyResourceCatalogArrivalDay`, use:

```kotlin
private fun classifyResourceCatalogArrivalDay(
    byResource: Map<String, List<Int>>,
    d: Int,
    date: String,
    reservableVendor: String,
): DayClassification {
    val statuses =
        byResource
            .mapKeys { (resourceId, _) -> "site:$reservableVendor:$resourceId" }
            .mapValues { (_, resourceDays) ->
                if (d >= resourceDays.size) AvailabilityStatus.UNKNOWN else AspiraStatus.classify(resourceDays[d])
            }
    return dayClassificationFromReservableStatuses(date, statuses)
}
```

For `classifyLinkedResourceCatalogArrivalDay`, use:

```kotlin
private fun classifyLinkedResourceCatalogArrivalDay(
    resources: List<CatalogResourceDays>,
    d: Int,
    date: String,
): DayClassification {
    val statuses =
        resources.associate { resource ->
            val days = resource.days
            val status =
                if (days == null || d >= days.size) {
                    AvailabilityStatus.UNKNOWN
                } else {
                    AspiraStatus.classify(days[d])
                }
            resource.rid to status
        }
    return dayClassificationFromReservableStatuses(date, statuses)
}
```

For `classifyResourceArrivalDay`, use:

```kotlin
private fun classifyResourceArrivalDay(
    resourceDays: List<Int>,
    d: Int,
    date: String,
    reservableId: String? = null,
): DayClassification {
    val status = AspiraStatus.classify(resourceDays[d])
    return if (reservableId == null) {
        dayClassificationFromStatuses(date, listOf(status))
    } else {
        dayClassificationFromReservableStatuses(date, mapOf(reservableId to status))
    }
}
```

For `d >= resourceDays.size` in `classifyResourceDays`, set `AvailabilityStatus.UNKNOWN`.

For `classifyArrivalDay`, collect a list of `AvailabilityStatus` and return `dayClassificationFromStatuses(date, statuses)`.

For occupancy, keep `AvailabilityStatus.AVAILABLE` when occupancy is available and `AvailabilityStatus.RESERVED` when no occupancy row is available for a known resource:

```kotlin
val statuses =
    resources.associate { resource ->
        val occupancy = occupancyByResourceId[resource.resourceId]
        val status =
            if (
                occupancy != null &&
                occupancy.availability == ASPIRA_OCCUPANCY_AVAILABLE &&
                !occupancy.filtered
            ) {
                AvailabilityStatus.AVAILABLE
            } else if (occupancy == null) {
                AvailabilityStatus.UNKNOWN
            } else {
                AvailabilityStatus.RESERVED
            }
        resource.rid to status
    }
return dayClassificationFromReservableStatuses(arrival.toString(), statuses)
```

- [ ] **Step 5: Run tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.models.aspira.AspiraStatusTest --tests ca.floo.roadtrip.service.booking.AspiraBookingProviderTest --tests ca.floo.roadtrip.service.api.AvailabilityResponseTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatus.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/service/api/AspiraAvailabilityService.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/models/aspira/AspiraStatusTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/service/booking/AspiraBookingProviderTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt
git commit -m "feat: classify aspira availability with enum statuses"
```

---

## Task 5: Snapshot and heatmap persistence

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt`
- Modify tests that insert snapshots.

- [ ] **Step 1: Update failing repo/API tests**

In snapshot insert helpers across repo/route tests, change default status values:

```kotlin
status: String = if (available) "available" else "reserved",
```

Change raw SQL snapshot inserts to cast the parameter:

```sql
INSERT INTO availability_snapshot (
    reservable_id, observed_at, target_date, status, available, day_payload
) VALUES (?::bigint, ?::timestamptz, ?::date, ?::availability_status, ?::boolean, '{}'::jsonb)
```

Update expectations from `"booked"` to `"reserved"`.

Add one heatmap test in `AvailabilityHeatmapRepoTest.kt`:

```kotlin
@Test
fun `latest cell parses first come and unknown enum statuses`() {
    val rid = seedReservable("100")
    val firstComeDate = LocalDate.parse("2026-07-04")
    val unknownDate = LocalDate.parse("2026-07-05")
    insertSnapshot(rid, firstComeDate, now().minusMinutes(1), available = false, status = "first_come")
    insertSnapshot(rid, unknownDate, now().minusMinutes(1), available = false, status = "unknown")

    val cells = AvailabilityHeatmapRepo(ctx).loadHeatmap(listOf(rid), listOf(firstComeDate, unknownDate))
    val byDate = cells.associateBy { it.targetDate }

    assertEquals(AvailabilityStatus.FIRST_COME, byDate[firstComeDate]!!.status)
    assertEquals(AvailabilityStatus.UNKNOWN, byDate[unknownDate]!!.status)
}
```

Add import:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
```

- [ ] **Step 2: Run tests to verify failure**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.repo.AvailabilityHeatmapRepoTest --tests ca.floo.roadtrip.repo.AvailabilitySnapshotStatsTest --tests ca.floo.roadtrip.routes.AvailabilityDashboardRoutesTest --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest
```

Expected: compile failures while repos/schemas still expose `String` status or raw SQL inserts lack enum casts.

- [ ] **Step 3: Update `AvailabilitySnapshotRepo.kt`**

Add imports:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus
```

In `appendBatch`, change:

```kotlin
.set(AVAILABILITY_SNAPSHOT.STATUS, day.status.toDb())
.set(AVAILABILITY_SNAPSHOT.AVAILABLE, day.status.isOnlineBookable)
```

Change `Snapshot.status`:

```kotlin
val status: AvailabilityStatus,
```

Change `fromRecord`:

```kotlin
status = AvailabilityStatus.parse(r.get(AVAILABILITY_SNAPSHOT.STATUS)?.literal),
```

Add this helper at file bottom:

```kotlin
private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.lookupLiteral(wireValue)
        ?: error("availability status has no DB enum literal: $wireValue")
```

- [ ] **Step 4: Update `AvailabilityHeatmapRepo.kt`**

Import:

```kotlin
import ca.floo.roadtrip.service.api.AvailabilityStatus
```

Change `LatestCell.status`:

```kotlin
val status: AvailabilityStatus,
```

Change the raw SQL fetch mapping:

```kotlin
status = AvailabilityStatus.parse(r.get("status", String::class.java)),
```

- [ ] **Step 5: Update API schemas and routes**

In `AvailabilityDashboardSchemas.kt`, import `AvailabilityStatus` and change:

```kotlin
val status: AvailabilityStatus,
```

for `AvailabilitySnapshotSchema.status`.

In `AvailabilityWatchSchemas.kt`, import `AvailabilityStatus` and change:

```kotlin
val status: AvailabilityStatus? = null,
```

for `AvailabilityWatchHeatmapCell.status`.

No route conversion is needed after repo types change; `status = status` and `status = cell?.status` remain correct.

- [ ] **Step 6: Run tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.repo.AvailabilityHeatmapRepoTest --tests ca.floo.roadtrip.repo.AvailabilitySnapshotStatsTest --tests ca.floo.roadtrip.routes.AvailabilityDashboardRoutesTest --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepo.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityWatchSchemas.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt \
  backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutes.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityHeatmapRepoTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotStatsTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt
git commit -m "feat: persist availability status enum"
```

---

## Task 6: Matrix renderer uses per-reservable statuses

**Files:**
- Modify: `web/availability/site-matrix.js`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/SmokeTest.kt`

- [ ] **Step 1: Update smoke fixture expectations**

In the matrix smoke route for `/api/poi/31337/availability`, update one fixture day to include all statuses:

```json
{
  "date": "2026-06-19",
  "status": "first_come",
  "available_count": 0,
  "total": 6,
  "available_reservable_ids": [],
  "reservable_statuses": {
    "site:matrix:001": "first_come",
    "site:matrix:002": "reserved",
    "site:matrix:003": "closed",
    "site:matrix:004": "unknown",
    "site:matrix:005": "reserved",
    "site:matrix:006": "first_come"
  }
}
```

Add assertions after the matrix is visible:

```kotlin
assertThat(page.locator(".cg-site-matrix-legend")).containsText("A")
assertThat(page.locator(".cg-site-matrix-legend")).containsText("FF")
assertThat(page.locator(".cg-site-matrix-legend")).containsText("R")
assertThat(page.locator(".cg-site-matrix-legend")).containsText("C")
assertThat(page.locator(".cg-site-matrix-legend")).containsText("?")
assertThat(page.locator(".cg-site-matrix-cell-first-come .cg-site-matrix-cell-button")).containsText("FF")
assertThat(page.locator(".cg-site-matrix-cell-reserved .cg-site-matrix-cell-button")).containsText("R")
assertThat(page.locator(".cg-site-matrix-cell-closed .cg-site-matrix-cell-button")).containsText("C")
assertThat(page.locator(".cg-site-matrix-cell-unknown .cg-site-matrix-cell-button")).containsText("?")
```

Update existing `.cg-site-matrix-cell-booked` expectations to `.cg-site-matrix-cell-reserved`.

- [ ] **Step 2: Run smoke test to verify failure**

Run from `backend/` with a dev server running:

```bash
QA_BASE_URL=http://127.0.0.1:8765 ./gradlew test --tests "ca.floo.roadtrip.SmokeTest.horizontal matrix swipe does not drag mobile drawer" --rerun
```

Expected: failure because the renderer still shows `Open/Full/Closed`.

- [ ] **Step 3: Update `site-matrix.js`**

Add near the top:

```js
const STATUS_META = {
  available: { kind: 'available', label: 'A', aria: 'available' },
  first_come: { kind: 'first-come', label: 'FF', aria: 'first come first served' },
  reserved: { kind: 'reserved', label: 'R', aria: 'reserved' },
  closed: { kind: 'closed', label: 'C', aria: 'closed' },
  unknown: { kind: 'unknown', label: '?', aria: 'unknown' },
};

const LEGACY_STATUS = {
  booked: 'reserved',
  full: 'reserved',
  partial: 'available',
  open: 'available',
};
```

Change the legend in `renderSection`:

```html
<span class="cg-site-matrix-key cg-site-matrix-key-available" title="Available">A</span>
<span class="cg-site-matrix-key cg-site-matrix-key-first-come" title="First come first served">FF</span>
<span class="cg-site-matrix-key cg-site-matrix-key-reserved" title="Reserved">R</span>
<span class="cg-site-matrix-key cg-site-matrix-key-closed" title="Closed">C</span>
<span class="cg-site-matrix-key cg-site-matrix-key-unknown" title="Unknown">?</span>
```

Replace `cellState` with:

```js
function cellState(row, day, availableIds) {
  const rid = rowRid(row);
  const directStatus = reservableStatus(day, rid);
  if (directStatus) return STATUS_META[directStatus] || STATUS_META.unknown;
  if (availableIds?.has(rid)) return STATUS_META.available;

  const total = numeric(day.total);
  const status = normalizeStatus(day.status);
  if (status === 'closed' || total === 0) return STATUS_META.closed;
  if (status === 'first_come') return STATUS_META.first_come;
  if (status === 'unknown') return STATUS_META.unknown;
  return STATUS_META.reserved;
}
```

Add helpers:

```js
function reservableStatus(day, rid) {
  const statuses = day?.reservable_statuses ?? day?.reservableStatuses;
  if (!statuses || typeof statuses !== 'object') return null;
  return normalizeStatus(statuses[rid]);
}

function normalizeStatus(raw) {
  const value = String(raw || '').toLowerCase();
  return STATUS_META[value] ? value : (LEGACY_STATUS[value] || null);
}
```

Change sort label `Open first` to `A first` or `Available first`:

```js
['open', 'Available first'],
```

Update the `sortReservables` call to include `visibleDays`:

```js
const rows = sortReservables(filterReservables(allRows, activeFilters), activeFilters.sort, {
  availabilityByDate,
  selectedDate,
  visibleDays,
});
```

Replace `openDateCount` so it counts direct per-reservable `available` status first, then falls back to legacy `available_reservable_ids`:

```js
function openDateCount(row, availabilityByDate, days = []) {
  const rid = rowRid(row);
  let count = 0;
  for (const day of days) {
    const status = reservableStatus(day, rid);
    if (status === 'available') count += 1;
    else if (!status && availabilityByDate.get(day.date)?.has(rid)) count += 1;
  }
  return count;
}
```

Update `sortReservables` to pass `visibleDays`:

```js
const ao = openDateCount(a, context.availabilityByDate, context.visibleDays);
const bo = openDateCount(b, context.availabilityByDate, context.visibleDays);
```

and pass `visibleDays` into `sortReservables` context where it is called.

- [ ] **Step 4: Run smoke test**

Run from `backend/`:

```bash
QA_BASE_URL=http://127.0.0.1:8765 ./gradlew test --tests "ca.floo.roadtrip.SmokeTest.horizontal matrix swipe does not drag mobile drawer" --rerun
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add web/availability/site-matrix.js backend/src/test/kotlin/ca/floo/roadtrip/SmokeTest.kt
git commit -m "feat: render per-reservable availability statuses"
```

---

## Task 7: Frontend status labels and styles

**Files:**
- Modify: `web/availability/week-grid.js`
- Modify: `web/availability/day-detail.js`
- Modify: `web/components/availability-panel.js`
- Modify: `web/components/availability/watch-heatmap.js`
- Modify: `web/components/availability/snapshots-tab.js`
- Modify: `index.html`
- Modify: `web/components/catalog.css`
- Modify: `watch-detail.html`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/SmokeTest.kt`

- [ ] **Step 1: Add/adjust smoke assertions**

Update SmokeTest assertions so no visible matrix text expects `Open` or `Full`. Keep non-matrix copy such as "open NOW" unchanged.

Add:

```kotlin
assertThat(page.locator(".cg-site-matrix")).not().containsText("Full")
assertThat(page.locator(".cg-site-matrix")).not().containsText("Open")
```

- [ ] **Step 2: Update week grid**

In `week-grid.js`, update `renderAvailLabel`:

```js
function renderAvailLabel(day) {
  const status = normalizeStatus(day.status);
  const count = availableCount(day);
  if (status === 'available' && count != null) return `${count} ${count === 1 ? 'site' : 'sites'}`;
  if (status === 'available') return 'A';
  if (status === 'first_come') return 'FF';
  if (status === 'reserved') return 'R';
  if (status === 'closed') return 'C';
  return '?';
}
```

Update `renderStatus`:

```js
function renderStatus(day) {
  const status = normalizeStatus(day.status);
  const count = availableCount(day);
  if (count != null && count > 0) return 'available';
  return status;
}
```

Add:

```js
function normalizeStatus(raw) {
  const value = String(raw || '').toLowerCase();
  if (value === 'booked' || value === 'full') return 'reserved';
  if (value === 'partial' || value === 'open') return 'available';
  if (['available', 'first_come', 'reserved', 'closed', 'unknown'].includes(value)) return value;
  return 'unknown';
}
```

- [ ] **Step 3: Update day detail**

In `day-detail.js`, update `renderStatusLine` cases:

```js
case 'available':
  return `<span class="cg-status-ok">Available</span> · ${count} of ${total} sites`;
case 'first_come':
  return '<span class="cg-status-first-come">First come first served</span>';
case 'reserved':
  return '<span class="cg-status-full">Reserved</span>';
case 'closed':
  return '<span class="cg-status-full">Closed</span>';
case 'unknown':
  return '<span class="cg-status-unknown">Unknown</span>';
```

Update `renderStatus` and add `normalizeStatus` using the same mapping as `week-grid.js`.

Update `renderActions`:

```js
const canAlert = day.status !== 'closed' && day.status !== 'unknown' && Boolean(canWatch);
```

Change the closed-day message:

```js
parts.push(`<span class="cg-day-detail-meta">No online openings to watch for this day.</span>`);
```

- [ ] **Step 4: Update availability panel**

In `availability-panel.js`, change `reservableDayOpen`:

```js
function reservableDayOpen(day) {
  return normalizeStatus(day.status) === 'available' || Number(day.available_count ?? day.availableCount ?? 0) > 0;
}
```

Update `dayPillHtml`:

```js
const status = normalizeStatus(day.status);
const cls = status;
```

Add:

```js
function normalizeStatus(raw) {
  const value = String(raw || '').toLowerCase();
  if (value === 'booked' || value === 'full') return 'reserved';
  if (value === 'partial' || value === 'open') return 'available';
  if (['available', 'first_come', 'reserved', 'closed', 'unknown'].includes(value)) return value;
  return 'unknown';
}
```

- [ ] **Step 5: Update heatmap JS**

In `watch-heatmap.js`, replace `STATUS_CLASS`:

```js
const STATUS_CLASS = {
  available: 'cell-available',
  first_come: 'cell-first-come',
  reserved: 'cell-reserved',
  closed: 'cell-closed',
  unknown: 'cell-unknown',
  booked: 'cell-reserved',
  partial: 'cell-available',
};
```

Update legend:

```html
<span class="legend-swatch cell-available"></span> A
<span class="legend-swatch cell-first-come"></span> FF
<span class="legend-swatch cell-reserved"></span> R
<span class="legend-swatch cell-closed"></span> C
<span class="legend-swatch cell-unknown"></span> ?
<span class="legend-swatch cell-empty"></span> no snapshot
```

- [ ] **Step 6: Update snapshot tab labels**

In `snapshots-tab.js`, add:

```js
function statusLabel(status) {
  const value = String(status || '').toLowerCase();
  if (value === 'available') return 'A';
  if (value === 'first_come') return 'FF';
  if (value === 'reserved' || value === 'booked') return 'R';
  if (value === 'closed') return 'C';
  return '?';
}
```

Change status cell:

```js
<td title="${escapeHtml(s.status)}">${escapeHtml(statusLabel(s.status))}</td>
```

- [ ] **Step 7: Update drawer styles in `index.html`**

Replace `.cg-day-partial` with `.cg-day-first_come` and `.cg-day-unknown`, and replace `.cg-day-booked` with `.cg-day-reserved`. Use:

```css
.cg-day.cg-day-first_come {
  background: rgba(241,160,74,0.14);
  border-color: rgba(241,160,74,0.35);
}
.cg-day.cg-day-first_come .cg-day-avail { color: var(--cg-warn); font-weight: 600; }
.cg-day.cg-day-reserved { background: rgba(255,255,255,0.03); }
.cg-day.cg-day-reserved .cg-day-num { color: var(--cg-faint); }
.cg-day.cg-day-reserved .cg-day-avail { color: var(--cg-faint); }
.cg-day.cg-day-unknown { background: rgba(255,255,255,0.025); }
.cg-day.cg-day-unknown .cg-day-num,
.cg-day.cg-day-unknown .cg-day-avail { color: var(--cg-faint); }
.cg-day-detail-meta .cg-status-first-come { color: var(--cg-warn); font-weight: 600; }
.cg-day-detail-meta .cg-status-unknown { color: var(--cg-faint); font-weight: 600; }
```

Add matrix key styles:

```css
.cg-site-matrix-key-first-come::before { background: var(--cg-warn); }
.cg-site-matrix-key-reserved::before { background: var(--cg-faint); }
.cg-site-matrix-key-unknown::before { background: var(--cg-muted); }
```

Replace matrix cell styles:

```css
.cg-site-matrix-cell-first-come {
  background: rgba(241,160,74,0.14);
}
.cg-site-matrix-cell-first-come .cg-site-matrix-cell-button {
  background: transparent;
  color: var(--cg-warn);
}
.cg-site-matrix-cell-first-come:hover {
  background: rgba(241,160,74,0.22);
}
.cg-site-matrix-cell-reserved .cg-site-matrix-cell-button {
  color: var(--cg-faint);
}
.cg-site-matrix-cell-unknown .cg-site-matrix-cell-button {
  color: var(--cg-faint);
}
```

- [ ] **Step 8: Mirror catalog styles**

Apply the same matrix and day-pill style changes in `web/components/catalog.css`. Add day-pill classes:

```css
.day-pill.first_come {
  border-color: rgba(241,194,125,0.42);
  background: rgba(241,194,125,0.10);
  color: #f1c27d;
}
.day-pill.reserved,
.day-pill.closed,
.day-pill.unknown {
  color: var(--muted);
}
```

- [ ] **Step 9: Update watch detail styles**

In `watch-detail.html`, replace status variables/classes:

```css
--status-first-come: rgba(241,160,74,0.42);
--status-reserved: rgba(245,101,101,0.45);
--status-unknown: rgba(255,255,255,0.08);
```

Replace classes:

```css
.cell-first-come { background: var(--status-first-come); }
.cell-reserved { background: var(--status-reserved); }
.cell-unknown { background: var(--status-unknown); }
```

Keep `.cell-booked { background: var(--status-reserved); }` as a legacy alias during rollout.

- [ ] **Step 10: Run smoke test**

Run from `backend/`:

```bash
QA_BASE_URL=http://127.0.0.1:8765 ./gradlew test --tests "ca.floo.roadtrip.SmokeTest.horizontal matrix swipe does not drag mobile drawer" --rerun
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add web/availability/week-grid.js web/availability/day-detail.js web/components/availability-panel.js \
  web/components/availability/watch-heatmap.js web/components/availability/snapshots-tab.js \
  index.html web/components/catalog.css watch-detail.html backend/src/test/kotlin/ca/floo/roadtrip/SmokeTest.kt
git commit -m "feat: update availability status UI labels"
```

---

## Task 8: Route/API tests and snapshot-writing routes

**Files:**
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityRoutesParseStartTest.kt`
- Modify any remaining test fixtures with old statuses.

- [ ] **Step 1: Sweep tests for old status vocabulary**

Run:

```bash
rg -n '"booked"|"partial"|status = "booked"|status = if .*"booked"|cell-booked|cg-site-matrix-cell-booked' backend/src/test web
```

Expected remaining allowed results:

- Legacy compatibility maps in frontend JS.
- Legacy aliases in CSS.
- Test comments that explicitly mention legacy migration behavior.

Update all active API expectations to `reserved` or enum `AvailabilityStatus.RESERVED`.

- [ ] **Step 2: Run route tests**

Run from `backend/`:

```bash
./gradlew test --tests ca.floo.roadtrip.routes.ReservableRoutesTest --tests ca.floo.roadtrip.routes.AvailabilityRoutesParseStartTest --tests ca.floo.roadtrip.routes.AvailabilityDashboardRoutesTest --tests ca.floo.roadtrip.routes.AvailabilityWatchRoutesTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityRoutesParseStartTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt \
  backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt
git commit -m "test: update availability status route fixtures"
```

---

## Task 9: Documentation updates

**Files:**
- Modify: `docs/booking-providers.md`
- Modify: `docs/booking-providers/aspira.md`
- Modify: `docs/superpowers/specs/2026-06-15-availability-watches-design.md`

- [ ] **Step 1: Update booking provider docs**

In `docs/booking-providers.md`, replace the status sentence around the snapshot lingua franca with:

```markdown
The shared status enum is `first_come | reserved | available | closed | unknown`.
`available_count` counts only online-bookable `available` reservables. `first_come`
is visible to users but does not count as online availability; missing provider
data is `unknown`, not `closed`.
```

In `docs/booking-providers/aspira.md`, update the observed code table:

```markdown
| 0 | unknown / no data |
| 1 | available |
| 2 | available |
| 3 | available |
| 5 | closed |
| 6 | available |
| 7 | available |
| unknown | unknown |
```

In `docs/superpowers/specs/2026-06-15-availability-watches-design.md`, update the schema line:

```markdown
status          availability_status -- 'first_come' | 'reserved' | 'available' | 'closed' | 'unknown'
```

- [ ] **Step 2: Check docs for old canonical status language**

Run:

```bash
rg -n "available \\| partial \\| booked \\| closed|booked|partial" docs/booking-providers.md docs/booking-providers/aspira.md docs/superpowers/specs/2026-06-15-availability-watches-design.md
```

Expected: no references that present `partial` or `booked` as current canonical statuses.

- [ ] **Step 3: Commit**

```bash
git add docs/booking-providers.md docs/booking-providers/aspira.md docs/superpowers/specs/2026-06-15-availability-watches-design.md
git commit -m "docs: update availability status vocabulary"
```

---

## Task 10: Full verification

**Files:**
- No source changes unless verification exposes a defect.

- [ ] **Step 1: Full backend verification**

Run from `backend/`:

```bash
./gradlew ktlintCheck test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Focused smoke verification**

With the local app running at `http://127.0.0.1:8765`, run from `backend/`:

```bash
QA_BASE_URL=http://127.0.0.1:8765 ./gradlew test --tests ca.floo.roadtrip.SmokeTest --rerun
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Live API spot check for POI 4611**

Run:

```bash
curl -sS 'http://127.0.0.1:8765/api/poi/4611/availability?start_date=2026-06-17&end_date=2026-06-24&force=1'
```

Expected shape includes:

```json
{
  "availability": [
    {
      "reservable_statuses": {
        "site:recgov:25144": "first_come"
      }
    }
  ]
}
```

Exact site/date values may differ if upstream data changed; the required check is that Rec.gov `Not Reservable` rows become `first_come`, not `reserved`.

- [ ] **Step 4: Frontend visual spot check**

Open `http://127.0.0.1:8765/?poi=4611` and verify:

- Matrix legend shows `A`, `FF`, `R`, `C`, and `?`.
- `Not Reservable` Lower Penstemon dates render as `FF`.
- Reserved dates render as `R`.
- Missing provider data renders as `?`, not `C`.
- Explicit provider-closed data renders as `C`.

- [ ] **Step 5: Final sweep**

Run:

```bash
rg -n '"partial"|"booked"|cg-site-matrix-cell-booked|cell-partial|status-booked' backend/src/main web docs/booking-providers.md docs/booking-providers/aspira.md
```

Expected allowed results only:

- Explicit legacy compatibility mapping from `booked -> reserved`.
- Explicit legacy compatibility mapping from `partial -> available`.
- CSS aliases retained for rollout compatibility.

- [ ] **Step 6: Confirm clean worktree**

Run:

```bash
git status --short
```

Expected: no output. If verification exposed a defect, return to the relevant task above, make the fix there, rerun that task's checks, and use that task's commit command with the concrete changed files.
