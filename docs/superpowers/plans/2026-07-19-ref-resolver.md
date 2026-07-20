# RefResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a generic `RefResolver` service that translates between the 7 identity spaces (poi_id, campground_id, campsite_id, campground DataProviderRef, campsite DataProviderRef, campground BookingProviderRef, campsite BookingProviderRef) via a single typed `resolve()` method. Eliminates `CampsiteProviderRepo`, `CampsiteProviderRefRow`, `CampgroundProviderRefRow`, `CampsiteVendorRefRow`, `CampsiteDateContextRow`, and the legacy `ProviderRefParser` / `source_payload` JSON fallback path.

**Architecture:** A sealed interface `RefValue` with 7 typed variants — entity type is encoded in the variant so `BookingProviderRef` on a campground (facility-level) is disambiguated from `BookingProviderRef` on a campsite (site-level). A single `RefResolver` interface with `resolve(from, toType)` returning `List<T>`. The DB implementation (`DbRefResolver`) uses a `when(from) → when(to)` dispatch matrix of SQL queries. Callers that previously built ad-hoc projection rows (`CampsiteProviderRefRow`, etc.) now call `refResolver.resolve<TargetType>(sourceRef)`.

**Tech Stack:** Kotlin, jOOQ (raw SQL), Koin DI, JUnit 5 + SharedDbTest

## Global Constraints

- Kotlin `sealed interface` for `RefValue` variants — exhaustive `when` at compile time.
- No legacy `source_payload` JSON parsing for ref resolution after this lands — `ProviderRefParser` is deleted.
- Batch overload (`resolve(List<RefValue>, KClass<T>)`) returns `Map<RefValue, List<T>>` for poller hot paths.
- `CatalogCampsiteRef` construction moves into `RefResolver` since it's a ref translation (campsite internal → provider-native composite).
- `AvailabilityProviderRegistry.forPoi(row: CampsiteProviderRefRow)` overloads get replaced with `forBooking(BookingProvider, BookingProviderRef)` — the only surviving lookup path.

---

### Task 1: Define `RefValue` Sealed Interface and `RefResolver` Interface

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/ref/RefValue.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/ref/RefResolver.kt`

**Interfaces:**
- Consumes: `DataProviderRef`, `BookingProviderRef` from `model.domain.provider`
- Produces: `RefValue` sealed interface, `RefResolver` interface — used by every downstream task

- [ ] **Step 1: Create `RefValue.kt`**

```kotlin
package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef

sealed interface RefValue {
    data class PoiId(val id: Long) : RefValue
    data class CampgroundId(val id: Long) : RefValue
    data class CampsiteId(val id: Long) : RefValue
    data class CampgroundDataRef(val ref: DataProviderRef) : RefValue
    data class CampsiteDataRef(val ref: DataProviderRef) : RefValue
    data class CampgroundBookingRef(val ref: BookingProviderRef) : RefValue
    data class CampsiteBookingRef(val ref: BookingProviderRef) : RefValue
}
```

- [ ] **Step 2: Create `RefResolver.kt`**

```kotlin
package ca.floo.roadtrip.service.ref

import kotlin.reflect.KClass

interface RefResolver {
    fun <T : RefValue> resolve(from: RefValue, to: KClass<T>): List<T>
    fun <T : RefValue> resolve(from: List<RefValue>, to: KClass<T>): Map<RefValue, List<T>>
}

inline fun <reified T : RefValue> RefResolver.resolve(from: RefValue): List<T> =
    resolve(from, T::class)

inline fun <reified T : RefValue> RefResolver.resolve(from: List<RefValue>): Map<RefValue, List<T>> =
    resolve(from, T::class)
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/ref/RefValue.kt backend/src/main/kotlin/ca/floo/roadtrip/service/ref/RefResolver.kt
git commit -m "feat: add RefValue sealed interface and RefResolver contract"
```

---

### Task 2: Implement `DbRefResolver` with Core Resolution Paths

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/ref/DbRefResolver.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/ref/DbRefResolverTest.kt`

**Interfaces:**
- Consumes: `RefValue`, `RefResolver` (Task 1), `DataProviderRef.parse()`, `BookingProviderRef.parse()`, `BookingProvider.fromIdOrNull()`
- Produces: `DbRefResolver` class — registered in Koin, injected into services

The resolution matrix (implemented paths):

| From | To | SQL join path |
|------|----|---------------|
| `PoiId` | `CampgroundId` | `poi_campgrounds` |
| `PoiId` | `CampsiteId` | `poi_campgrounds → campsites` |
| `PoiId` | `CampgroundBookingRef` | `poi_campgrounds → campgrounds.booking_provider/ref` |
| `PoiId` | `CampsiteBookingRef` | `poi_campgrounds → campsites.booking_provider/ref` |
| `CampgroundId` | `PoiId` | `poi_campgrounds` |
| `CampgroundId` | `CampsiteId` | `campsites.campground_id` |
| `CampgroundId` | `CampgroundBookingRef` | `campgrounds.booking_provider/ref` |
| `CampgroundId` | `CampgroundDataRef` | `campgrounds.data_provider/ref` |
| `CampsiteId` | `PoiId` | `campsites → poi_campgrounds` |
| `CampsiteId` | `CampgroundId` | `campsites.campground_id` |
| `CampsiteId` | `CampsiteBookingRef` | `campsites.booking_provider/ref` |
| `CampsiteId` | `CampgroundBookingRef` | `campsites → campgrounds.booking_provider/ref` (parent) |
| `CampsiteId` | `CampsiteDataRef` | `campsites.data_provider/ref` |
| `CampgroundDataRef` | `CampgroundId` | `campgrounds WHERE (data_provider, data_provider_ref)` |
| `CampsiteDataRef` | `CampsiteId` | `campsites WHERE (data_provider, data_provider_ref)` |
| `CampgroundBookingRef` | `CampgroundId` | `campgrounds WHERE (booking_provider, booking_provider_ref)` |
| `CampgroundBookingRef` | `CampsiteId` | `campgrounds → campsites WHERE cg.booking_provider/ref` |
| `CampsiteBookingRef` | `CampsiteId` | `campsites WHERE (booking_provider, booking_provider_ref)` |

- [ ] **Step 1: Write failing test — PoiId → CampgroundBookingRef resolution**

```kotlin
package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DbRefResolverTest : SharedDbTest() {
    private lateinit var resolver: DbRefResolver

    @BeforeEach
    fun setup() {
        ctx.cleanCanonicalCatalogFixtures()
        resolver = DbRefResolver(ctx)
    }

    @Test
    fun `poiId resolves to campground booking ref`() {
        val poiId = ctx.seedCatalogPoi(
            sourceId = "facility-232447",
            name = "Upper Pines",
            lon = -119.56, lat = 37.74,
            source = "recgov",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        ).poiId

        val result = resolver.resolve<RefValue.CampgroundBookingRef>(RefValue.PoiId(poiId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "232447"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to its own campsite booking ref`() {
        val poi = ctx.seedCatalogPoi(
            sourceId = "facility-232447",
            name = "Upper Pines",
            lon = -119.56, lat = 37.74,
            source = "recgov",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )
        val campgroundId = ctx.fetchOne(
            "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poi.poiId
        )!!.get("campground_id", Long::class.java)
        val campsiteId = ctx.seedCampsite(
            campgroundId = campgroundId,
            vendor = "recgov",
            vendorId = "site-1",
            bookingProvider = "recgov",
            bookingProviderRef = "site-1",
        )

        val result = resolver.resolve<RefValue.CampsiteBookingRef>(RefValue.CampsiteId(campsiteId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "site-1"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to parent campground booking ref`() {
        val poi = ctx.seedCatalogPoi(
            sourceId = "facility-232447",
            name = "Upper Pines",
            lon = -119.56, lat = 37.74,
            source = "recgov",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )
        val campgroundId = ctx.fetchOne(
            "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poi.poiId
        )!!.get("campground_id", Long::class.java)
        val campsiteId = ctx.seedCampsite(
            campgroundId = campgroundId,
            vendor = "recgov",
            vendorId = "site-1",
            bookingProvider = "recgov",
            bookingProviderRef = "site-1",
        )

        val result = resolver.resolve<RefValue.CampgroundBookingRef>(RefValue.CampsiteId(campsiteId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "232447"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to poiId`() {
        val poi = ctx.seedCatalogPoi(
            sourceId = "facility-232447",
            name = "Upper Pines",
            lon = -119.56, lat = 37.74,
            source = "recgov",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )
        val campgroundId = ctx.fetchOne(
            "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poi.poiId
        )!!.get("campground_id", Long::class.java)
        val campsiteId = ctx.seedCampsite(
            campgroundId = campgroundId,
            vendor = "recgov",
            vendorId = "site-1",
            bookingProvider = "recgov",
            bookingProviderRef = "site-1",
        )

        val result = resolver.resolve<RefValue.PoiId>(RefValue.CampsiteId(campsiteId))

        assertEquals(listOf(RefValue.PoiId(poi.poiId)), result)
    }

    @Test
    fun `campgroundBookingRef resolves to campgroundId`() {
        val campgroundId = ctx.seedCampground(
            source = "recgov",
            sourceId = "232447",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )

        val result = resolver.resolve<RefValue.CampgroundId>(
            RefValue.CampgroundBookingRef(BookingProviderRef.RecGov(facilityId = "232447"))
        )

        assertEquals(listOf(RefValue.CampgroundId(campgroundId)), result)
    }

    @Test
    fun `campgroundBookingRef resolves to child campsiteIds`() {
        val campgroundId = ctx.seedCampground(
            source = "recgov",
            sourceId = "232447",
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )
        val cs1 = ctx.seedCampsite(campgroundId = campgroundId, vendor = "recgov", vendorId = "s1")
        val cs2 = ctx.seedCampsite(campgroundId = campgroundId, vendor = "recgov", vendorId = "s2")

        val result = resolver.resolve<RefValue.CampsiteId>(
            RefValue.CampgroundBookingRef(BookingProviderRef.RecGov(facilityId = "232447"))
        )

        assertEquals(setOf(cs1, cs2), result.map { it.id }.toSet())
    }

    @Test
    fun `batch resolve returns grouped results`() {
        val poi1 = ctx.seedCatalogPoi(
            sourceId = "fac-1", name = "A", lon = -119.0, lat = 37.0,
            source = "recgov", bookingProvider = "recgov", bookingProviderRef = "1",
        )
        val poi2 = ctx.seedCatalogPoi(
            sourceId = "fac-2", name = "B", lon = -120.0, lat = 38.0,
            source = "recgov", bookingProvider = "recgov", bookingProviderRef = "2",
        )

        val inputs = listOf(RefValue.PoiId(poi1.poiId), RefValue.PoiId(poi2.poiId))
        val result = resolver.resolve<RefValue.CampgroundBookingRef>(inputs)

        assertEquals(2, result.size)
        assertEquals(
            BookingProviderRef.RecGov("1"),
            result[RefValue.PoiId(poi1.poiId)]!!.first().ref,
        )
        assertEquals(
            BookingProviderRef.RecGov("2"),
            result[RefValue.PoiId(poi2.poiId)]!!.first().ref,
        )
    }

    @Test
    fun `unsupported resolution path returns empty list`() {
        val result = resolver.resolve<RefValue.CampsiteDataRef>(
            RefValue.CampgroundBookingRef(BookingProviderRef.RecGov("232447"))
        )
        assertEquals(emptyList(), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "ca.floo.roadtrip.service.ref.DbRefResolverTest" --info 2>&1 | tail -20`
Expected: compilation error — `DbRefResolver` does not exist.

- [ ] **Step 3: Implement `DbRefResolver`**

```kotlin
package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import org.jooq.DSLContext
import kotlin.reflect.KClass

class DbRefResolver(
    private val ctx: DSLContext,
) : RefResolver {
    @Suppress("UNCHECKED_CAST")
    override fun <T : RefValue> resolve(from: RefValue, to: KClass<T>): List<T> =
        when (from) {
            is RefValue.PoiId -> resolveFromPoi(from.id, to)
            is RefValue.CampgroundId -> resolveFromCampground(from.id, to)
            is RefValue.CampsiteId -> resolveFromCampsite(from.id, to)
            is RefValue.CampgroundDataRef -> resolveFromCampgroundDataRef(from.ref, to)
            is RefValue.CampsiteDataRef -> resolveFromCampsiteDataRef(from.ref, to)
            is RefValue.CampgroundBookingRef -> resolveFromCampgroundBookingRef(from.ref, to)
            is RefValue.CampsiteBookingRef -> resolveFromCampsiteBookingRef(from.ref, to)
        } as List<T>

    override fun <T : RefValue> resolve(from: List<RefValue>, to: KClass<T>): Map<RefValue, List<T>> =
        from.associateWith { resolve(it, to) }

    private fun resolveFromPoi(poiId: Long, to: KClass<*>): List<RefValue> =
        when (to) {
            RefValue.CampgroundId::class -> ctx.fetch(
                """
                SELECT pc.campground_id
                FROM poi_campgrounds pc
                JOIN campgrounds cg ON cg.id = pc.campground_id
                WHERE pc.poi_id = ? AND cg.deleted_at IS NULL
                """.trimIndent(), poiId
            ).map { RefValue.CampgroundId(it.get("campground_id", Long::class.java)) }

            RefValue.CampsiteId::class -> ctx.fetch(
                """
                SELECT c.id
                FROM campsites c
                JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                WHERE pc.poi_id = ? AND c.deleted_at IS NULL
                """.trimIndent(), poiId
            ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            RefValue.CampgroundBookingRef::class -> ctx.fetch(
                """
                SELECT cg.booking_provider, cg.booking_provider_ref
                FROM campgrounds cg
                JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                WHERE pc.poi_id = ?
                  AND cg.deleted_at IS NULL
                  AND cg.booking_provider IS NOT NULL
                """.trimIndent(), poiId
            ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampsiteBookingRef::class -> ctx.fetch(
                """
                SELECT c.booking_provider, c.booking_provider_ref
                FROM campsites c
                JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                WHERE pc.poi_id = ?
                  AND c.deleted_at IS NULL
                  AND c.booking_provider IS NOT NULL
                """.trimIndent(), poiId
            ).mapNotNull(::parseCampsiteBookingRef)

            else -> emptyList()
        }

    private fun resolveFromCampground(campgroundId: Long, to: KClass<*>): List<RefValue> =
        when (to) {
            RefValue.PoiId::class -> ctx.fetch(
                """
                SELECT pc.poi_id
                FROM poi_campgrounds pc
                JOIN pois p ON p.id = pc.poi_id
                WHERE pc.campground_id = ? AND p.deleted_at IS NULL
                """.trimIndent(), campgroundId
            ).map { RefValue.PoiId(it.get("poi_id", Long::class.java)) }

            RefValue.CampsiteId::class -> ctx.fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.campground_id = ? AND c.deleted_at IS NULL
                """.trimIndent(), campgroundId
            ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            RefValue.CampgroundBookingRef::class -> ctx.fetch(
                """
                SELECT cg.booking_provider, cg.booking_provider_ref
                FROM campgrounds cg
                WHERE cg.id = ? AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                """.trimIndent(), campgroundId
            ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampgroundDataRef::class -> ctx.fetch(
                """
                SELECT cg.data_provider, cg.data_provider_ref
                FROM campgrounds cg
                WHERE cg.id = ? AND cg.deleted_at IS NULL
                """.trimIndent(), campgroundId
            ).mapNotNull(::parseCampgroundDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampsite(campsiteId: Long, to: KClass<*>): List<RefValue> =
        when (to) {
            RefValue.PoiId::class -> ctx.fetch(
                """
                SELECT pc.poi_id
                FROM campsites c
                JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                JOIN pois p ON p.id = pc.poi_id
                WHERE c.id = ? AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                """.trimIndent(), campsiteId
            ).map { RefValue.PoiId(it.get("poi_id", Long::class.java)) }

            RefValue.CampgroundId::class -> ctx.fetch(
                """
                SELECT c.campground_id FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL
                """.trimIndent(), campsiteId
            ).map { RefValue.CampgroundId(it.get("campground_id", Long::class.java)) }

            RefValue.CampsiteBookingRef::class -> ctx.fetch(
                """
                SELECT c.booking_provider, c.booking_provider_ref
                FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL AND c.booking_provider IS NOT NULL
                """.trimIndent(), campsiteId
            ).mapNotNull(::parseCampsiteBookingRef)

            RefValue.CampgroundBookingRef::class -> ctx.fetch(
                """
                SELECT cg.booking_provider, cg.booking_provider_ref
                FROM campsites c
                JOIN campgrounds cg ON cg.id = c.campground_id
                WHERE c.id = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                """.trimIndent(), campsiteId
            ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampsiteDataRef::class -> ctx.fetch(
                """
                SELECT c.data_provider, c.data_provider_ref
                FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL
                """.trimIndent(), campsiteId
            ).mapNotNull(::parseCampsiteDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampgroundDataRef(ref: DataProviderRef, to: KClass<*>): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampgroundId::class -> ctx.fetch(
                """
                SELECT cg.id FROM campgrounds cg
                WHERE cg.data_provider = ? AND cg.data_provider_ref = ? AND cg.deleted_at IS NULL
                """.trimIndent(), provider, serialized
            ).map { RefValue.CampgroundId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampsiteDataRef(ref: DataProviderRef, to: KClass<*>): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampsiteId::class -> ctx.fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.data_provider = ? AND c.data_provider_ref = ? AND c.deleted_at IS NULL
                """.trimIndent(), provider, serialized
            ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampgroundBookingRef(ref: BookingProviderRef, to: KClass<*>): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampgroundId::class -> ctx.fetch(
                """
                SELECT cg.id FROM campgrounds cg
                WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND cg.deleted_at IS NULL
                """.trimIndent(), provider, serialized
            ).map { RefValue.CampgroundId(it.get("id", Long::class.java)) }

            RefValue.CampsiteId::class -> ctx.fetch(
                """
                SELECT c.id FROM campsites c
                JOIN campgrounds cg ON cg.id = c.campground_id
                WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL
                """.trimIndent(), provider, serialized
            ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampsiteBookingRef(ref: BookingProviderRef, to: KClass<*>): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampsiteId::class -> ctx.fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.booking_provider = ? AND c.booking_provider_ref = ? AND c.deleted_at IS NULL
                """.trimIndent(), provider, serialized
            ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun parseCampgroundBookingRef(r: org.jooq.Record): RefValue.CampgroundBookingRef? {
        val bp = BookingProvider.fromIdOrNull(r.get("booking_provider", String::class.java)) ?: return null
        val bpRef = r.get("booking_provider_ref", String::class.java) ?: return null
        val parsed = BookingProviderRef.parse(bp, bpRef) ?: return null
        return RefValue.CampgroundBookingRef(parsed)
    }

    private fun parseCampsiteBookingRef(r: org.jooq.Record): RefValue.CampsiteBookingRef? {
        val bp = BookingProvider.fromIdOrNull(r.get("booking_provider", String::class.java)) ?: return null
        val bpRef = r.get("booking_provider_ref", String::class.java) ?: return null
        val parsed = BookingProviderRef.parse(bp, bpRef) ?: return null
        return RefValue.CampsiteBookingRef(parsed)
    }

    private fun parseCampgroundDataRef(r: org.jooq.Record): RefValue.CampgroundDataRef? {
        val dp = DataProvider.fromIdOrNull(r.get("data_provider", String::class.java)) ?: return null
        val dpRef = r.get("data_provider_ref", String::class.java) ?: return null
        val parsed = DataProviderRef.parse(dp, dpRef) ?: return null
        return RefValue.CampgroundDataRef(parsed)
    }

    private fun parseCampsiteDataRef(r: org.jooq.Record): RefValue.CampsiteDataRef? {
        val dp = DataProvider.fromIdOrNull(r.get("data_provider", String::class.java)) ?: return null
        val dpRef = r.get("data_provider_ref", String::class.java) ?: return null
        val parsed = DataProviderRef.parse(dp, dpRef) ?: return null
        return RefValue.CampsiteDataRef(parsed)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "ca.floo.roadtrip.service.ref.DbRefResolverTest" --info 2>&1 | tail -20`
Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/ref/DbRefResolver.kt backend/src/test/kotlin/ca/floo/roadtrip/service/ref/DbRefResolverTest.kt
git commit -m "feat: implement DbRefResolver with full resolution matrix"
```

---

### Task 3: Register `DbRefResolver` in Koin and Wire into `DbAvailabilityTargetResolver`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolver.kt`

**Interfaces:**
- Consumes: `RefResolver` (Task 1), `DbRefResolver` (Task 2)
- Produces: `DbAvailabilityTargetResolver` now takes `RefResolver` instead of `CampsiteProviderRepo`. Legacy `resolveFromLegacyJson` and `catalogRefFor` logic eliminated.

- [ ] **Step 1: Add `DbRefResolver` to Koin registration in `RepoModule.kt`**

Add after the `CampsiteProviderRepo` singleton (we keep `CampsiteProviderRepo` temporarily until all callers are migrated in later tasks):

```kotlin
single<RefResolver> { DbRefResolver(get()) }
```

Add imports:
```kotlin
import ca.floo.roadtrip.service.ref.DbRefResolver
import ca.floo.roadtrip.service.ref.RefResolver
```

- [ ] **Step 2: Update `DbAvailabilityTargetResolver` to use `RefResolver`**

Replace the constructor parameter `campsiteProviderRepo: CampsiteProviderRepo` with `refResolver: RefResolver`. Remove the `resolveFromLegacyJson` method entirely. Rewrite `buildCandidate` to use `refResolver.resolve<RefValue.CampgroundBookingRef>(...)`. Remove `catalogRefFor` — build `CatalogCampsiteRef` directly from `CampsiteAvailabilityTarget` fields and the resolved `BookingProviderRef`.

The new `DbAvailabilityTargetResolver`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.ref.RefResolver
import ca.floo.roadtrip.service.ref.RefValue
import ca.floo.roadtrip.service.ref.resolve

internal class DbAvailabilityTargetResolver(
    private val refResolver: RefResolver,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
    private val pollerRepo: AvailabilityPollerRepo,
) : AvailabilityTargetResolver {
    override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val candidates = poiIds.flatMap { poiId ->
            val bookingRefs = refResolver.resolve<RefValue.CampgroundBookingRef>(RefValue.PoiId(poiId))
            bookingRefs.mapNotNull { bookingRefValue ->
                val provider = availabilityProviders.forBooking(bookingRefValue.ref.provider, bookingRefValue.ref)
                    ?: return@mapNotNull null
                Triple(poiId, bookingRefValue.ref, provider)
            }
        }

        val (poiId, parentRef, provider) = candidates.firstOrNull() ?: return null
        val catalogRef = buildCatalogRef(campsite, parentRef)

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = provider,
            parentRef = parentRef,
            catalogRef = catalogRef,
            parentPoiId = poiId,
            dateContext = dateResolver.contextForPoi(poiId),
            candidates = candidates.map { (_, ref, prov) ->
                ProviderCandidate(
                    provider = prov,
                    parentRef = ref,
                    catalogRef = buildCatalogRef(campsite, ref),
                )
            },
        )
    }

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
        val liveWatches = pollerRepo.liveWatchesForPoller(poller.id)
        if (liveWatches.isEmpty()) return null

        val poiCadenceOverrideSec = pollerRepo.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        val targets = campsitesRepo
            .findAvailabilityTargetsByPoi(poller.poiId)
            .mapNotNull { resolve(it) }
            .filter {
                parentRefKey(it.parentRef) == poller.parentRef &&
                    it.provider.id.name.lowercase() == poller.provider
            }.distinctBy { it.campsite.id }

        val windowFor = { context: ca.floo.roadtrip.model.availability.PoiDateContext,
                          caps: ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities ->
            val resolvedWindow = dateResolver.resolvePollingWindow(
                context = context,
                maxPollWindowDays = caps.maxPollWindowDays,
                bookingHorizonDays = caps.bookingHorizonDays,
            )
            resolvedWindow?.let { AvailabilityWindows(target = it, fetch = it) }
        }

        return PollerFetchPlan(
            targets = targets,
            windowFor = windowFor,
            cadenceSec = cadenceSec,
            liveWatches = liveWatches,
        )
    }

    private fun buildCatalogRef(
        campsite: CampsiteAvailabilityTarget,
        parentRef: BookingProviderRef,
    ): CatalogCampsiteRef =
        when (parentRef) {
            is BookingProviderRef.RecGov -> CatalogCampsiteRef(
                campsiteId = campsite.id,
                vendorId = campsite.vendorId,
            )
            is BookingProviderRef.Campflare -> CatalogCampsiteRef(
                campsiteId = campsite.id,
                vendorId = campsite.vendorId,
            )
            is BookingProviderRef.Aspira -> CatalogCampsiteRef(
                campsiteId = campsite.id,
                vendorId = campsite.vendorId,
                mapId = parentRef.mapId,
                resourceLocationId = parentRef.resourceLocationId,
            )
            is BookingProviderRef.ReserveAmerica -> CatalogCampsiteRef(
                campsiteId = campsite.id,
                vendorId = campsite.vendorId,
            )
            is BookingProviderRef.ReserveCalifornia -> CatalogCampsiteRef(
                campsiteId = campsite.id,
                vendorId = campsite.vendorId,
            )
        }
}
```

Note: `dateResolver.contextForPoi(poiId)` is a new method we add to `AvailabilityDateResolver` — it does the poi → lat/lng lookup that `CampsiteProviderRepo.findDateContext` used to do. See Task 4.

- [ ] **Step 3: Update `ServiceModule.kt` DI wiring**

Change the `DbAvailabilityTargetResolver` factory:

```kotlin
single {
    DbAvailabilityTargetResolver(
        refResolver = get<RefResolver>(),
        campsitesRepo = get<CampsiteRepo>(),
        availabilityProviders = get<AvailabilityProviderRegistry>(),
        dateResolver = get<AvailabilityDateResolver>(),
        pollerRepo = get<AvailabilityPollerRepo>(),
    )
}
```

- [ ] **Step 4: Run existing `DbAvailabilityTargetResolverTest` to verify no regressions**

Run: `cd backend && ./gradlew test --tests "ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolverTest" --info 2>&1 | tail -20`
Expected: PASS (all existing tests pass with RefResolver wiring).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolver.kt
git commit -m "refactor: wire DbAvailabilityTargetResolver to RefResolver, remove legacy JSON path"
```

---

### Task 4: Migrate `CampsiteAvailabilityService` and `CampgroundAvailabilitySupport` off `CampsiteProviderRepo`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampgroundAvailabilitySupport.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolver.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`

**Interfaces:**
- Consumes: `RefResolver` (Task 1), `RefValue.CampgroundId` → `RefValue.CampgroundBookingRef`
- Produces: `CampsiteAvailabilityService` and `CampgroundAvailabilitySupport` no longer depend on `CampsiteProviderRepo`

- [ ] **Step 1: Add `contextForPoi(poiId)` to `AvailabilityDateResolver`**

This consolidates the `findDateContext` SQL from `CampsiteProviderRepo`. Add method:

```kotlin
fun contextForPoi(poiId: Long): PoiDateContext {
    val r = ctx.fetchOne(
        """
        SELECT ST_X(ST_PointOnSurface(p.geom)) AS lng,
               ST_Y(ST_PointOnSurface(p.geom)) AS lat
        FROM pois p
        WHERE p.id = ? AND p.deleted_at IS NULL
        """.trimIndent(), poiId
    ) ?: throw AvailabilityServiceError.NotFound
    return context(
        lat = (r.get("lat") as Number?)?.toDouble(),
        lng = (r.get("lng") as Number?)?.toDouble(),
    )
}
```

This means `AvailabilityDateResolver` needs a `DSLContext` constructor param. If it currently doesn't have one, add it and update Koin wiring.

- [ ] **Step 2: Rewrite `displayWindow` in `CampsiteAvailabilityService`**

Replace the `campsiteProviderRepo: CampsiteProviderRepo` parameter in the private `displayWindow` function. Instead accept `dateResolver: AvailabilityDateResolver` only (which already has `contextForPoi`):

```kotlin
private fun displayWindow(
    poiId: Long,
    startDate: LocalDate?,
    endDate: LocalDate?,
    dateResolver: AvailabilityDateResolver,
): Pair<LocalDate, LocalDate> {
    val dateContext = dateResolver.contextForPoi(poiId)
    val window = dateResolver.resolveWindow(
        startDate = startDate,
        endDate = endDate,
        context = dateContext,
        bookingHorizonDays = EMPTY_WINDOW_HORIZON_DAYS,
        maxDays = EMPTY_WINDOW_MAX_DAYS,
        defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
    )
    return window.startDate to window.endDate
}
```

Remove `campsiteProviderRepo` from `CampsiteAvailabilityService` constructor.

- [ ] **Step 3: Rewrite `CampgroundAvailabilitySupport`**

Replace `campsiteProviderRepo` with `refResolver: RefResolver`. The method `preferredAvailabilityProvider` becomes:

```kotlin
internal class CampgroundAvailabilitySupport(
    private val refResolver: RefResolver,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun preferredAvailabilityProvider(campgroundId: Long): String? {
        val bookingRefs = refResolver.resolve<RefValue.CampgroundBookingRef>(RefValue.CampgroundId(campgroundId))
        return bookingRefs.firstNotNullOfOrNull { refValue ->
            availabilityProviders.forBooking(refValue.ref.provider, refValue.ref)
                ?.id?.name?.lowercase()
        }
    }
}
```

- [ ] **Step 4: Update DI wiring in `ServiceModule.kt`**

```kotlin
single {
    CampgroundAvailabilitySupport(
        refResolver = get<RefResolver>(),
        availabilityProviders = get(),
    )
}
```

Remove `campsiteProviderRepo` from `CampsiteAvailabilityService` factory too.

- [ ] **Step 5: Run all availability service tests**

Run: `cd backend && ./gradlew test --tests "ca.floo.roadtrip.service.availability.*" --info 2>&1 | tail -30`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampgroundAvailabilitySupport.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityDateResolver.kt backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git commit -m "refactor: migrate availability services from CampsiteProviderRepo to RefResolver"
```

---

### Task 5: Migrate `CampsiteCatalogService` and `CampsiteRoutes` off `CampsiteProviderRepo`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`

**Interfaces:**
- Consumes: `RefResolver` (Task 1), `RefValue.PoiId` → `RefValue.CampgroundId`
- Produces: `CampsiteCatalogService` no longer depends on `CampsiteProviderRepo`

- [ ] **Step 1: Replace `campgroundExists` check in `CampsiteCatalogService`**

The existence check `campsiteProviderRepo.campgroundExists(poiId)` becomes a simple ref resolution:

```kotlin
internal class CampsiteCatalogService(
    private val refResolver: RefResolver,
    private val campsitesRepo: CampsiteRepo,
    private val targets: AvailabilityTargetResolver,
) {
    fun campsitesForPoi(poiId: Long, siteTypes: List<String>): PoiCampsitesResponseSchema {
        val campgrounds = refResolver.resolve<RefValue.CampgroundId>(RefValue.PoiId(poiId))
        if (campgrounds.isEmpty()) throw AvailabilityServiceError.NotFound
        // ... rest unchanged
    }
}
```

- [ ] **Step 2: Update `CampsiteRoutes.kt`**

Replace `CampsiteProviderRepo(ctx)` instantiation with injection of `RefResolver`. The route file creates services inline — replace all `campsiteProviderRepo` usages with `refResolver`:

```kotlin
val refResolver: RefResolver by inject()
```

Update `CampsiteCatalogService` and `DbAvailabilityTargetResolver` construction to pass `refResolver`.

- [ ] **Step 3: Run route + catalog tests**

Run: `cd backend && ./gradlew test --tests "ca.floo.roadtrip.service.availability.CampsiteCatalogServiceTest" --tests "ca.floo.roadtrip.route.*" --info 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogService.kt backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git commit -m "refactor: migrate CampsiteCatalogService and routes to RefResolver"
```

---

### Task 6: Migrate `CampgroundRepo` off `CampsiteProviderRepo` and Remove `AvailabilityProviderRegistry.forPoi` Overloads

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProviderRegistry.kt`

**Interfaces:**
- Consumes: `RefResolver` (Task 1)
- Produces: `CampgroundRepo` no longer instantiates `CampsiteProviderRepo` internally; `AvailabilityProviderRegistry` drops `forPoi(CampsiteProviderRefRow)` overloads

- [ ] **Step 1: Remove `CampsiteProviderRepo` from `CampgroundRepo`**

`CampgroundRepo` line 107 uses `campsiteProviderRepo.findProviderRef(poiId)?.providerRefJson` to populate `CampgroundPoiDetail.providerRefJson`. Since we're killing the legacy JSON path, this field should come from the typed `BookingProviderRef` instead. Replace with:

```kotlin
providerRefJson = null, // Legacy field — booking ref now resolved via RefResolver
```

Or remove the `providerRefJson` field from `CampgroundPoiDetail` entirely if no downstream consumer needs it. Check callers of `CampgroundPoiDetail`.

- [ ] **Step 2: Remove `forPoi(row: CampsiteProviderRefRow)` overloads from `AvailabilityProviderRegistry`**

Delete the two `forPoi` overloads (lines 39-48) that accept `CampsiteProviderRefRow`. Keep only `forBooking(BookingProvider, BookingProviderRef)` and `forSource(String)`.

Remove the `CampsiteProviderRefRow` import.

- [ ] **Step 3: Run full test suite**

Run: `cd backend && ./gradlew test --info 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProviderRegistry.kt
git commit -m "refactor: remove CampsiteProviderRepo from CampgroundRepo, drop forPoi overloads"
```

---

### Task 7: Delete Dead Code — `CampsiteProviderRepo`, Row Types, and `ProviderRefParser`

**Files:**
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteProviderRepo.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteProviderRefRow.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundProviderRefRow.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteVendorRefRow.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteDateContextRow.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/ProviderRefParser.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt` (remove `CampsiteProviderRepo` singleton)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt` (remove `aspiraProviderRefLong` / `toCatalogCampsiteRef` that reads legacy JSON)

**Interfaces:**
- Consumes: Nothing — this is pure deletion
- Produces: Clean compile with no references to deleted types

- [ ] **Step 1: Delete the files**

```bash
rm backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteProviderRepo.kt
rm backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteProviderRefRow.kt
rm backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundProviderRefRow.kt
rm backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteVendorRefRow.kt
rm backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteDateContextRow.kt
rm backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/ProviderRefParser.kt
```

- [ ] **Step 2: Remove `CampsiteProviderRepo` from `RepoModule.kt`**

Delete:
```kotlin
single { CampsiteProviderRepo(get()) }
```

And its import.

- [ ] **Step 3: Remove `toCatalogCampsiteRef()` and `aspiraProviderRefLong` from `CatalogAvailabilityBatcher.kt`**

Delete the extension function `CampsiteAvailabilityTarget.toCatalogCampsiteRef()` (lines 16-22) and the helper `aspiraProviderRefLong` (lines 24-28). The `CatalogCampsiteRef` is now built in `DbAvailabilityTargetResolver.buildCatalogRef()`.

- [ ] **Step 4: Remove `bookingProvider()` extension from `ProviderRefParser.kt` callers**

Since `ProviderRefParser.kt` is deleted, any remaining import of `bookingProvider` extension should be replaced with `BookingProviderRef.provider` property (which already exists on the sealed interface variants).

- [ ] **Step 5: Compile check**

Run: `cd backend && ./gradlew compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run full test suite**

Run: `cd backend && ./gradlew test --info 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: delete CampsiteProviderRepo, legacy row types, and ProviderRefParser"
```

---

### Task 8: Update Tests That Directly Instantiate `CampsiteProviderRepo`

**Files:**
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfillTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampgroundAvailabilitySupportTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogServiceTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolverTest.kt`

**Interfaces:**
- Consumes: `DbRefResolver(ctx)` replaces `CampsiteProviderRepo(ctx)` at every test instantiation site
- Produces: All tests compile and pass with the new wiring

- [ ] **Step 1: Find-and-replace across test files**

In every test file that imports `CampsiteProviderRepo`:
1. Replace `import ca.floo.roadtrip.repo.CampsiteProviderRepo` with `import ca.floo.roadtrip.service.ref.DbRefResolver`
2. Replace `campsiteProviderRepo = CampsiteProviderRepo(ctx)` with `refResolver = DbRefResolver(ctx)`
3. Update constructor calls that pass `campsiteProviderRepo` to pass `refResolver` instead

- [ ] **Step 2: Remove `providerRefJson` from test seed helpers if no longer needed**

The `seedCatalogPoi` and `seedCampground` helpers still write `source_payload` (used for other purposes like `raw` field in API responses). But the `providerRefJson` parameter name is misleading now. If `source_payload` is still needed for the `raw` JSON in API responses, keep it but rename the parameter to `sourcePayloadJson` for clarity (or verify it's already named that in `seedCampground`).

- [ ] **Step 3: Run full test suite**

Run: `cd backend && ./gradlew test --info 2>&1 | tail -30`
Expected: PASS — all tests green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: update all tests to use DbRefResolver instead of CampsiteProviderRepo"
```

---

### Task 9: Remove `providerRef` Legacy Field from `CampsiteAvailabilityTarget` and API Response

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteAvailabilityTarget.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/CampsiteSummarySchema.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteRepo.kt` (remove `provider_ref_text` from query)

**Interfaces:**
- Consumes: Nothing new
- Produces: `CampsiteAvailabilityTarget.providerRef` field removed; API no longer returns legacy `providerRef` JSON blob

- [ ] **Step 1: Remove `providerRef` from `CampsiteAvailabilityTarget`**

```kotlin
data class CampsiteAvailabilityTarget(
    val id: Long,
    val vendor: String,
    val vendorId: String,
    val name: String?,
    val loop: String?,
    val siteType: String?,
    val raw: JsonElement?,
    val tags: JsonElement? = null,
)
```

- [ ] **Step 2: Remove `providerRef` from `CampsiteSummarySchema`**

Delete the `providerRef: JsonElement? = null` field. If the frontend still reads this, add a TODO to verify — but since booking is now done via typed refs, this legacy blob is unused.

- [ ] **Step 3: Remove `cg.source_payload::text AS provider_ref_text` from the availability target SQL in `CampsiteRepo`**

In `CampsiteRepo`, the `availabilityTargetSelect` companion val includes the join to `campgrounds` and selects `cg.source_payload::text AS provider_ref_text`. Remove that column from the select and the `providerRef` assignment in `availabilityTargetFromRecord`.

- [ ] **Step 4: Run full test suite**

Run: `cd backend && ./gradlew test --info 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove legacy providerRef JSON blob from availability target and API schema"
```

---

### Task 10: Final Verification and Cleanup

**Files:**
- Verify: no remaining imports of deleted types
- Verify: `source_payload` column reads in `CampsiteProviderRepo` queries are gone (the column itself stays — it's used by `CampsiteRepo` for the `raw` field in API responses, and by `CampgroundRepo` for other metadata)

- [ ] **Step 1: Grep for dead references**

```bash
grep -rn "CampsiteProviderRepo\|CampsiteProviderRefRow\|CampgroundProviderRefRow\|CampsiteVendorRefRow\|CampsiteDateContextRow\|ProviderRefParser\|providerRefJson\|resolveFromLegacyJson" backend/src/main/kotlin --include="*.kt"
```

Expected: zero hits.

- [ ] **Step 2: Run full build + test**

Run: `cd backend && ./gradlew build --info 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify no leftover test references**

```bash
grep -rn "CampsiteProviderRepo\|ProviderRefParser\|CampsiteProviderRefRow" backend/src/test --include="*.kt"
```

Expected: zero hits.

- [ ] **Step 4: Final commit (if any stragglers)**

```bash
git add -A
git status
# Only commit if there are changes
git commit -m "chore: final cleanup of dead ref resolution imports"
```