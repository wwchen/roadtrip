# Typed Provider Seam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the typed `booking_provider` / `booking_provider_ref` columns the only booking identity the serving path, the CTA logic, the availability batch, and the public API use, and collapse the per-vendor enumerations that sit outside the adapter registry.

**Architecture:** Delete the legacy vendor-keyed JSON ref codec and every reader of `source_payload` on the serving path; carry `BookingProviderRef` as a typed value from repo to service to DTO; derive the provider allowlist, cache entities, and ETL adapter table from `BookingProvider` / one registry map instead of hand-written `when` blocks; replace free-form status strings with enums. No schema migration is needed: the typed columns already exist and already carry the CTA-resolved ref.

**Tech Stack:** Kotlin 2 / Ktor / jOOQ (raw SQL via `DSLContext.fetch`) / kotlinx.serialization / Koin / JUnit 5 + kotlin.test / Testcontainers Postgres (`SharedDbTest`); React + TypeScript + Vitest on the frontend.

**Spec:** `docs/superpowers/specs/2026-09-09-architecture-audit.md` (findings 1, 4, 5, and the status-string parts of 12). GitHub issues: #730 (finding 1), #733 (finding 4), #734 (finding 5), #741 (finding 12).

## Global Constraints

- Layering per `docs/backend-architecture.md`: routes → service → repo/clients. No SQL outside `repo/`, no Ktor types in `service/`, no vendor branching in routes.
- No inline magic constants (`AGENTS.md`). Named `const val` or config.
- Never edit an applied migration under `backend/src/main/resources/db/migration`. This plan adds none.
- Comments: short and rare. Do not add explanatory comment blocks; the code and tests carry the meaning.
- Backend gate (Docker must be running for jOOQ codegen):
  `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
  Fast loop for one package: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.*' --offline -q`. ktlint fixes: `./gradlew :backend:ktlintFormat --offline -q`.
- Frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- Commit after each task with a conventional prefix (`refactor(...)`, `feat(...)`, `test(...)`), body naming the issue (`Refs #730`). End the message with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- The first Write/Edit to each file in a session is denied by the GateGuard hook. State the facts it asks for and re-issue the same call.

---

### Task 1: Retire the legacy JSON booking-ref codec on the serving path (#730)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campground.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/poi/CampgroundPoiDetail.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt:76-110,186-190`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/campground/CampgroundCta.kt:1-80`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundService.kt:60-105`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/PoiCategoryDetailSchema.kt:25`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BookingRefDto.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/provider/BookingProviderRefLegacyJson.kt`
- Delete: `backend/src/test/kotlin/ca/floo/roadtrip/model/domain/provider/BookingProviderRefLegacyJsonTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/campground/CampgroundCtaTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/PoiServiceTest.kt:33-70,120-135`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/FeatureCollectionContractTest.kt:225`

**Interfaces:**
- Consumes: `BookingProviderRef.parse(provider, ref)`, `BookingProvider.fromIdOrNull(id)` (existing).
- Produces:
  - `fun Campground.bookingRef(): BookingProviderRef?` (extension in `Campground.kt`).
  - `data class CampgroundPoiDetail(campground, source, sourceId, bookingRef: BookingProviderRef?, propertiesJson, memberSources)`.
  - `CampgroundCta.computeCtas(bookingRef: BookingProviderRef?, reserveUrl: String?, infoUrl: String?): List<PoiCtaSchema>`
  - `CampgroundCta.bookingSystem(bookingRef: BookingProviderRef?, reserveUrl: String?, infoUrl: String?): String?`
  - `@Serializable data class BookingRefDto(val provider: String, val ref: String)` and `PoiCategoryDetailSchema.bookingRef: BookingRefDto?` serialized as `booking_ref`.

Why the CTA override goes away entirely: `AspiraCampgroundsEtl.kt:239` and `BcParksCampgroundsEtl.kt:171` already write `booking_provider_ref` from the same `bookingCtaRef` (map id and resource location id) that the `booking_cta_provider_ref` payload key carries. The override is a duplicate of the primary. `PoiServiceTest` "detail row ignores old materialized Aspira CTA ref" already asserts the override is not read.

- [ ] **Step 1: Write the failing CTA test against typed refs**

Replace the private `CtaInput` data class and helpers at the bottom of `CampgroundCtaTest.kt` with typed inputs, and rewrite the existing tests to construct refs. The first two tests become:

```kotlin
@Test
fun `recgov reservable ref labels stored booking URL`() {
    val out =
        cta.computeCtas(
            bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
            reserveUrl = "https://www.recreation.gov/camping/campgrounds/232450",
            infoUrl = null,
        ).singleOrNull()
    assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out?.url)
    assertEquals("Reserve on recreation.gov", out?.label)
    assertEquals("reserve", out?.kind)
}

@Test
fun `reservecalifornia ref produces park deeplink`() {
    val out =
        cta.computeCtas(
            bookingRef = BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901)),
            reserveUrl = null,
            infoUrl = null,
        ).singleOrNull()
    assertEquals("https://reservecalifornia.com/park/660", out?.url)
}
```

Convert every remaining test in the file the same way: a JSON string like `{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}` becomes `BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = 4189, mapId = -2147483361, resourceLocationId = -2147483408)`; `{"campflare_id":"x"}` becomes `BookingProviderRef.Campflare(campgroundId = "x")`; `{"contract_code":"NY","park_id":"117"}` becomes `BookingProviderRef.ReserveAmerica(contractCode = "NY", parkId = "117")`. Tests that passed a `ctaProviderRefJson` distinct from `providerRefJson` are testing the override path: delete them, and add one test that proves the primary ref alone drives the Aspira deeplink:

```kotlin
@Test
fun `aspira deeplink uses the map id carried by the booking ref`() {
    val out =
        cta.computeCtas(
            bookingRef = BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = 4189, mapId = -2147483645, resourceLocationId = 9002),
            reserveUrl = "https://reservation.pc.gc.ca/",
            infoUrl = null,
        ).singleOrNull()
    assertTrue(out!!.url.contains("mapId=-2147483645"), out.url)
}
```

The `bookingSystem labels` test calls `cta.bookingSystem(bookingRef = ..., reserveUrl = ..., infoUrl = ...)` directly.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.campground.CampgroundCtaTest' --offline -q`
Expected: compilation error, `computeCtas` has no parameter `bookingRef`.

- [ ] **Step 3: Add `Campground.bookingRef()` and retype `CampgroundPoiDetail`**

In `Campground.kt`, below the data class:

```kotlin
fun Campground.bookingRef(): BookingProviderRef? {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
    return bookingProviderRef?.let { BookingProviderRef.parse(provider, it) }
}
```

(add imports for `BookingProvider` and `BookingProviderRef`.)

`CampgroundPoiDetail.kt`:

```kotlin
data class CampgroundPoiDetail(
    val campground: Campground,
    val source: String,
    val sourceId: String,
    val bookingRef: BookingProviderRef?,
    val propertiesJson: String,
    val memberSources: List<String>,
)
```

- [ ] **Step 4: Repo stops reading the payload and the legacy encoder**

In `CampgroundRepo.findPoiDetailByPoi`, delete the `cta_provider_ref_text` select expression and set `bookingRef = campground.bookingRef()`:

```kotlin
val campground = fromRecord(record)
return CampgroundPoiDetail(
    campground = campground,
    source = record.get("detail_source", String::class.java),
    sourceId = record.get("detail_source_id", String::class.java),
    bookingRef = campground.bookingRef(),
    propertiesJson = record.get("properties_text", String::class.java),
    memberSources = memberSourcesOf(record.get("member_sources")),
)
```

Delete `private fun bookingProviderRefJson(...)` and the `BookingProviderRefLegacyJson` import.

- [ ] **Step 5: CTA service takes typed refs**

In `CampgroundCta.kt`, replace both public methods:

```kotlin
fun bookingSystem(
    bookingRef: BookingProviderRef?,
    reserveUrl: String?,
    infoUrl: String?,
): String? {
    val upstreamUrl = providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl)
    return providers.firstNotNullOfOrNull { it.bookingSystem(bookingRef, upstreamUrl) }
}

fun computeCtas(
    bookingRef: BookingProviderRef?,
    reserveUrl: String?,
    infoUrl: String?,
): List<PoiCtaSchema> {
    val primaryCta =
        primaryReserveCta(providerRef = bookingRef, reserveUrl = reserveUrl, infoUrl = infoUrl)
            ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
                PoiCtaSchema(url = it, label = ExternalInfoLinkLabels.forUrl(it), kind = INFO_CTA_KIND)
            }
    return listOfNotNull(primaryCta, campflareCta(bookingRef)).distinctBy { it.url }
}
```

Remove the `BookingProviderRefLegacyJson` import.

- [ ] **Step 6: DTO carries one ref shape**

Create `model/api/BookingRefDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.serialization.Serializable

@Serializable
data class BookingRefDto(
    val provider: String,
    val ref: String,
) {
    companion object {
        fun from(ref: BookingProviderRef): BookingRefDto = BookingRefDto(provider = ref.provider.id, ref = ref.serialize())
    }
}
```

In `PoiCategoryDetailSchema.kt` replace `@SerialName("provider_ref") val providerRef: JsonElement? = null` with `@SerialName("booking_ref") val bookingRef: BookingRefDto? = null`, and delete the two comment lines above `availability_supported` that talk about provider_ref shapes.

In `CampgroundService.poiDetailProperties`:

```kotlin
val computedCtas = cta.computeCtas(bookingRef = detail.bookingRef, reserveUrl = campground.reservationUrl, infoUrl = infoUrl)
...
bookingRef = detail.bookingRef?.let(BookingRefDto::from),
...
bookingSystem = cta.bookingSystem(bookingRef = detail.bookingRef, reserveUrl = campground.reservationUrl, infoUrl = infoUrl),
```

Remove the now-unused `Json.parseToJsonElement(it)` for provider ref (keep the `raw` parse of `propertiesJson`).

- [ ] **Step 7: Delete the codec and fix the remaining tests**

`git rm` `BookingProviderRefLegacyJson.kt` and `BookingProviderRefLegacyJsonTest.kt`. Then:

- `PoiServiceTest.kt:44-47`: replace the `publicRef` JSON assertions with
  ```kotlin
  val ref = feature.campgroundDetail().bookingRef!!
  assertEquals("aspira", ref.provider)
  assertEquals("pc:-2147483647:-2147483026:-2147483640", ref.ref)
  assertEquals(BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = -2147483647, mapId = -2147483026, resourceLocationId = -2147483640), row.bookingRef)
  ```
  and delete `assertNull(row.ctaProviderRefJson)`.
- `PoiServiceTest.kt` "detail row ignores old materialized Aspira CTA ref from source payload": keep the seed, assert `row.bookingRef` equals the same typed Aspira ref as above (the payload key is not read).
- `PoiServiceTest.kt:131-132`: `assertEquals(BookingRefDto("recgov", "232869"), detail.bookingRef)`.
- `FeatureCollectionContractTest.kt:225`: `bookingRef = BookingRefDto(provider = "reserveamerica", ref = "NY:117")`.
- Grep `backend/src/test` for `providerRefJson`, `ctaProviderRefJson`, `providerRef =` and fix every remaining compile error the same way.

- [ ] **Step 8: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. Also `grep -rn 'LegacyJson\|providerRefJson\|cta_provider_ref' backend/src` returns nothing.

- [ ] **Step 9: Commit**

```bash
git add -A backend
git commit -m "refactor(backend): serve booking refs from the typed columns only

Delete BookingProviderRefLegacyJson and the source_payload CTA override read.
CampgroundPoiDetail carries a BookingProviderRef; the POI detail API emits
booking_ref {provider, ref} instead of a vendor-keyed JSON blob.

Refs #730

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `PoiServingRepo` dedupe key from typed columns (#730)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt:60-100`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/PoiServingRepoTest.kt`

**Interfaces:**
- Consumes: `PoiServingRepo(ctx, enabledDataProviders).fetchPoisWithinPolygon(polygonGeoJson, categories): List<PoiRow>` (existing), `ctx.seedCatalogPoi(...)` from `repo/CanonicalCatalogFixtures.kt` (read that file for the exact parameter list; `PoiServiceTest.seedPoi` at line 629 shows a working call).
- Produces: nothing new. The SQL `poi_key` expression becomes `COALESCE(booking_provider || ':' || booking_provider_ref, source || ':' || source_id)`.

- [ ] **Step 1: Write the failing repo test**

```kotlin
package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PoiServingRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `campgrounds sharing a booking ref collapse to one pin inside a corridor`() {
        val shared = seed(source = "campflare", sourceId = "cf-1", bookingProvider = "recgov", bookingProviderRef = "232447")
        seed(source = "recgov", sourceId = "232447", bookingProvider = "recgov", bookingProviderRef = "232447")
        val other = seed(source = "recgov", sourceId = "232448", bookingProvider = "recgov", bookingProviderRef = "232448")

        val rows = repo().fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(shared, other), rows.map { it.id }.toSet())
    }

    @Test
    fun `campgrounds without a booking ref dedupe on data provider identity`() {
        val a = seed(source = "recgov", sourceId = "1", bookingProvider = null, bookingProviderRef = null)
        val b = seed(source = "recgov", sourceId = "2", bookingProvider = null, bookingProviderRef = null)

        val rows = repo().fetchPoisWithinPolygon(WORLD, listOf("campground"))

        assertEquals(setOf(a, b), rows.map { it.id }.toSet())
    }

    private fun repo() = PoiServingRepo(ctx, enabledDataProviders = setOf("recgov", "campflare"))

    private fun seed(source: String, sourceId: String, bookingProvider: String?, bookingProviderRef: String?): Long =
        ctx.seedCatalogPoi(
            sourceId = sourceId,
            name = "Camp $sourceId",
            lon = -120.0,
            lat = 45.0,
            source = source,
            subcategory = "federal",
            agency = "USFS",
            region = "OR",
            country = "US",
            providerRefJson = "{}",
            propertiesJson = "{}",
            bookingProvider = bookingProvider,
            bookingProviderRef = bookingProviderRef,
        ).poiId

    private companion object {
        const val WORLD = """{"type":"Polygon","coordinates":[[[-180,-89],[180,-89],[180,89],[-180,89],[-180,-89]]]}"""
    }
}
```

Adjust the `seedCatalogPoi` call to the fixture's real signature if a parameter name differs; the first test must express "same booking ref → one row" and "different booking ref → two rows". The first test is expected to pass on the old SQL for rec.gov (the `recgov_id` CASE arm) only if the seed writes `recgov_id` into the payload, which it does not, so it fails before the change.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.PoiServingRepoTest' --offline -q`
Expected: FAIL, three ids returned in the first test (the dedupe never fires because `source_payload` has no `recgov_id`).

- [ ] **Step 3: Replace the CASE with the typed columns**

In `fetchPoisWithinPolygon`, replace the whole `CASE ... END AS poi_key` block with:

```sql
COALESCE(booking_provider || ':' || booking_provider_ref, source || ':' || source_id) AS poi_key
```

and in the inner `SELECT`, replace `COALESCE(cg.source_payload, '{}'::jsonb) AS provider_ref` with:

```sql
cg.booking_provider,
cg.booking_provider_ref,
```

- [ ] **Step 4: Run the test and the repo package**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.*' --tests 'ca.floo.roadtrip.service.poi.*' --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/PoiServingRepoTest.kt
git commit -m "refactor(repo): dedupe corridor pins on the typed booking ref

Refs #730

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Provider allowlist from the enum; Campflare readiness lives on the adapter (#733)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/ReadPathProviderConfig.kt:14-21`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/CampflareAvailabilityProvider.kt:20-34`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt:169-172,441-443`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/AppConfigTest.kt:253-265` (message unchanged, keep as the regression test)
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/CampflareAvailabilityProviderTest.kt`

**Interfaces:**
- Produces: `CampflareAvailabilityProvider(availabilityClient, enabled: Boolean, configured: Boolean)` with `isEnabled() = enabled && configured`.
- `AppConfig.isProviderEnabled(id)` in `ServiceModule.kt` becomes a single `readPathProviders.isAvailabilityProviderEnabled(id.id)`.

- [ ] **Step 1: Write the failing adapter test**

Find how existing tests fake `CampflareAvailabilityClient` (`grep -rn 'CampflareAvailabilityClient' backend/src/test`) and reuse that fake or write a one-line `object : CampflareAvailabilityClient { ... }` implementing its members with `error("unused")` bodies.

```kotlin
package ca.floo.roadtrip.service.availability.provider

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampflareAvailabilityProviderTest {
    @Test
    fun `provider is enabled only when allowed by config and an api key is configured`() {
        assertTrue(CampflareAvailabilityProvider(unusedClient, enabled = true, configured = true).isEnabled())
        assertFalse(CampflareAvailabilityProvider(unusedClient, enabled = true, configured = false).isEnabled())
        assertFalse(CampflareAvailabilityProvider(unusedClient, enabled = false, configured = true).isEnabled())
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.provider.CampflareAvailabilityProviderTest' --offline -q`
Expected: compile error, no parameter `configured`.

- [ ] **Step 3: Implement**

`CampflareAvailabilityProvider`:

```kotlin
class CampflareAvailabilityProvider(
    private val availabilityClient: CampflareAvailabilityClient,
    private val enabled: Boolean,
    private val configured: Boolean,
) : AvailabilityProvider {
    override fun isEnabled(): Boolean = enabled && configured
```

`ServiceModule.kt` construction site:

```kotlin
CampflareAvailabilityProvider(
    availabilityClient = get(),
    enabled = config.isProviderEnabled(BookingProvider.CAMPFLARE),
    configured = !config.campflare.apiKey.isNullOrBlank(),
),
```

`ServiceModule.kt:441`:

```kotlin
private fun AppConfig.isProviderEnabled(id: BookingProvider): Boolean = readPathProviders.isAvailabilityProviderEnabled(id.id)
```

(import `BookingProvider` at the top instead of the fully-qualified name.)

`ReadPathProviderConfig.kt`:

```kotlin
private val availabilityProviderIds: Set<String> = BookingProvider.entries.map { it.id }.toSet()
```

- [ ] **Step 4: Run the gate**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.*' --tests 'ca.floo.roadtrip.service.availability.provider.*' --tests 'ca.floo.roadtrip.di.*' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. The `AppConfigTest` unknown-provider message still lists exactly `[aspira, campflare, recgov, reserveamerica, reservecalifornia]` because the enum has the same five ids.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "refactor(config): derive the availability allowlist from BookingProvider

Campflare's api-key readiness moves onto its adapter; the generic enable
predicate no longer special-cases a vendor.

Refs #733

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: ETL adapter table replaces the class-name `when` and the registry's per-vendor accessors (#733)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt:30-95`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt:236-260,294-299` (delete `recgovSources`, `campflareSources`, `bcParksSources`, `reserveCaliforniaSources`; keep `reserveAmericaSources` and `hostBySource`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt:459-480`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistryTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/RoadtripRuntimeConfigTest.kt:46-66` (existing, must keep passing)

**Interfaces:**
- Produces, in `ProductionTerminalEtlRegistry.kt`:
  ```kotlin
  internal data class PoiAdapterSpec(val dataProvider: DataProvider, val create: (EtlEntry) -> TerminalEtlDefinition<*, *>)
  internal val poiAdapters: Map<String, PoiAdapterSpec>
  internal val campsiteAdapters: Map<String, (EtlEntry) -> TerminalEtlDefinition<*, *>>
  internal fun dataProviderForAdapter(adapter: String): DataProvider? = poiAdapters[adapter]?.dataProvider
  ```

- [ ] **Step 1: Write the failing registry test**

```kotlin
package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionTerminalEtlRegistryTest {
    @Test
    fun `adapter names resolve to the data provider they populate`() {
        assertEquals(DataProvider.RECGOV, dataProviderForAdapter("RecGovCampgroundsEtl"))
        assertEquals(DataProvider.STRAPI, dataProviderForAdapter("BcParksCampgroundsEtl"))
        assertEquals(DataProvider.TESLA_SUPERCHARGER, dataProviderForAdapter("TeslaIndexEtl"))
        assertNull(dataProviderForAdapter("NoSuchEtl"))
    }

    @Test
    fun `every terminal adapter named in the registry yaml has a factory`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val poi = registry.poiData.mapNotNull { it.etls.lastOrNull()?.adapter }.toSet()
        val campsite = registry.campsiteData.mapNotNull { it.etls.lastOrNull()?.adapter }.toSet()
        assertTrue((poi - poiAdapters.keys).isEmpty(), "missing poi adapters: ${poi - poiAdapters.keys}")
        assertTrue((campsite - campsiteAdapters.keys).isEmpty(), "missing campsite adapters: ${campsite - campsiteAdapters.keys}")
    }
}
```

(If `PoiRegistry` names the campsite list differently from `campsiteData`, use the property `enabledCampsiteData()` iterates over.)

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.framework.ProductionTerminalEtlRegistryTest' --offline -q`
Expected: unresolved reference `dataProviderForAdapter`.

- [ ] **Step 3: Replace the two `when` blocks with tables**

```kotlin
internal data class PoiAdapterSpec(
    val dataProvider: DataProvider,
    val create: (EtlEntry) -> TerminalEtlDefinition<*, *>,
)

internal val poiAdapters: Map<String, PoiAdapterSpec> =
    mapOf(
        "CampflareCampgroundsEtl" to PoiAdapterSpec(DataProvider.CAMPFLARE) { campgroundSink(CampflareCampgroundsEtl()) },
        "RecGovCampgroundsEtl" to PoiAdapterSpec(DataProvider.RECGOV) { campgroundSink(RecGovCampgroundsEtl(it.slug)) },
        "AspiraCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.ASPIRA) { entry ->
                campgroundSink(
                    AspiraCampgroundsEtl(
                        etlSlug = entry.slug,
                        dataProviderValue = DataProvider.ASPIRA,
                        aspiraTenant = entry.args.require("tenant"),
                        stateFilter = entry.args["state_filter"],
                    ),
                )
            },
        "BcParksCampgroundsEtl" to PoiAdapterSpec(DataProvider.STRAPI) { campgroundSink(BcParksCampgroundsEtl(etlSlug = it.slug)) },
        "ReserveAmericaCampgroundsEtl" to PoiAdapterSpec(DataProvider.RESERVEAMERICA) { campgroundSink(ReserveAmericaCampgroundsEtl(it.slug)) },
        "ReserveCaliforniaCampgroundsEtl" to PoiAdapterSpec(DataProvider.RESERVECALIFORNIA) { campgroundSink(ReserveCaliforniaCampgroundsEtl(it.slug)) },
        "PlanetFitnessEtl" to PoiAdapterSpec(DataProvider.PLANET_FITNESS_LOCATION) { planetFitnessSink(PlanetFitnessEtl()) },
        "TeslaIndexEtl" to PoiAdapterSpec(DataProvider.TESLA_SUPERCHARGER) { teslaSuperchargerSink(TeslaIndexEtl()) },
    )

internal val campsiteAdapters: Map<String, (EtlEntry) -> TerminalEtlDefinition<*, *>> =
    mapOf(
        "CampflareCampsitesEtl" to { _ -> campsiteSink(CampflareCampsitesEtl()) },
        "RecGovCampsitesEtl" to { entry -> campsiteSink(RecGovCampsitesEtl(entry.slug)) },
        "AspiraCampsitesEtl" to { entry -> campsiteSink(AspiraCampsitesEtl(/* same args as today */)) },
        "ReserveAmericaSitesEtl" to { entry -> campsiteSink(ReserveAmericaSitesEtl(etlSlug = entry.slug, contractCode = entry.args.require("contract"))) },
        "ReserveCaliforniaSitesEtl" to { entry -> campsiteSink(ReserveCaliforniaSitesEtl(entry.slug)) },
    )

internal fun dataProviderForAdapter(adapter: String): DataProvider? = poiAdapters[adapter]?.dataProvider

private fun createPoiTerminal(entry: EtlEntry): TerminalEtlDefinition<*, *> =
    poiAdapters[entry.adapter]?.create?.invoke(entry) ?: error("Unknown poi_data adapter: ${entry.adapter} (slug=${entry.slug})")

private fun createCampsiteTerminal(entry: EtlEntry): TerminalEtlDefinition<*, *> =
    campsiteAdapters[entry.adapter]?.invoke(entry) ?: error("Unknown campsite_data adapter: ${entry.adapter} (slug=${entry.slug})")
```

Copy the `AspiraCampsitesEtl(...)` argument list verbatim from the existing `when` arm. Note the `@Suppress("UNCHECKED_CAST")` annotations on the old functions: keep them only if the compiler still asks.

- [ ] **Step 4: DI derives supported data providers from the table**

In `ServiceModule.kt`, replace `canonicalCampgroundSourceKeys` with:

```kotlin
private fun catalogDataProviderKeys(registry: PoiRegistry): Set<String> =
    registry.poiData
        .mapNotNull { row -> row.etls.lastOrNull()?.adapter }
        .mapNotNull(::dataProviderForAdapter)
        .map { it.id }
        .toSet()
```

and use it in `supportedReadPathDataProviders` in place of `canonicalCampgroundSourceKeys(registry)`. Delete the `AspiraTenants` import from `ServiceModule.kt` if nothing else there uses it (line 178 still does; leave it). Then delete `recgovSources`, `campflareSources`, `bcParksSources`, `reserveCaliforniaSources` from `PoiRegistry.kt` and confirm with `grep -rn 'recgovSources\|campflareSources\|bcParksSources\|reserveCaliforniaSources' backend/src` that nothing references them.

- [ ] **Step 5: Run the gate**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.framework.*' --tests 'ca.floo.roadtrip.RoadtripRuntimeConfigTest' --tests 'ca.floo.roadtrip.model.metadata.*' --tests 'ca.floo.roadtrip.di.*' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS, including the existing `read path data source validation rejects unknown keys` message `[planet_fitness_location, recgov, recgov-campgrounds, tesla_supercharger]`.

- [ ] **Step 6: Commit**

```bash
git add -A backend
git commit -m "refactor(etl): table-driven terminal adapter registry

Adapter name -> (data provider, factory) is one map; boot validation derives
the supported data providers from it instead of six per-vendor accessors.

Refs #733

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Availability cache entities keyed by provider; snapshot TTL honours config (#733)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/ApiCacheEntity.kt` (whole file)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/ApiCacheConfig.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityService.kt:33,99-106`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt:210-215`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/AppConfigTest.kt:168-198`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CampsiteAvailabilityServiceTest.kt:207-221`

**Interfaces:**
- Produces:
  ```kotlin
  data class ApiCacheEntity(val namespace: String, val configKey: String, val defaultTtl: Duration) {
      companion object {
          val ROUTE: ApiCacheEntity
          fun availability(provider: BookingProvider): ApiCacheEntity
          val entries: List<ApiCacheEntity>
      }
  }
  fun ApiCacheConfig.availabilityTtl(provider: BookingProvider): Duration
  internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration   // no when
  ```

- [ ] **Step 1: Rewrite the two tests**

`AppConfigTest` defaults test:

```kotlin
assertEquals(Duration.ofMinutes(10), config.cache.ttlFor(ApiCacheEntity.ROUTE))
for (provider in BookingProvider.entries) {
    assertEquals(Duration.ofHours(2), config.cache.availabilityTtl(provider), provider.id)
}
```

`AppConfigTest` parsing test keeps the same property map and asserts:

```kotlin
assertEquals(Duration.ofHours(4), config.cache.availabilityTtl(BookingProvider.RECGOV))
assertEquals(Duration.ofMinutes(20), config.cache.availabilityTtl(BookingProvider.CAMPFLARE))
assertEquals(Duration.ofMinutes(15), config.cache.availabilityTtl(BookingProvider.ASPIRA))
assertEquals(Duration.ofMinutes(30), config.cache.availabilityTtl(BookingProvider.RESERVEAMERICA))
assertEquals(Duration.ofMinutes(45), config.cache.availabilityTtl(BookingProvider.RESERVECALIFORNIA))
```

`CampsiteAvailabilityServiceTest` TTL test:

```kotlin
@Test
fun `default snapshot freshness TTL is the provider's cache entity default`() {
    for (provider in BookingProvider.entries) {
        assertEquals(ApiCacheEntity.availability(provider).defaultTtl, defaultSnapshotFreshnessTtl(provider), provider.id)
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.AppConfigTest' --offline -q`
Expected: unresolved `availabilityTtl` / `ApiCacheEntity.availability`.

- [ ] **Step 3: Implement**

`ApiCacheEntity.kt`:

```kotlin
package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.Duration

data class ApiCacheEntity(
    val namespace: String,
    val configKey: String,
    val defaultTtl: Duration,
) {
    companion object {
        private val DEFAULT_ROUTE_TTL: Duration = Duration.ofMinutes(10)
        private val DEFAULT_AVAILABILITY_TTL: Duration = Duration.ofHours(2)

        val ROUTE: ApiCacheEntity = ApiCacheEntity(namespace = "route", configKey = "route.ttl", defaultTtl = DEFAULT_ROUTE_TTL)

        fun availability(provider: BookingProvider): ApiCacheEntity =
            ApiCacheEntity(
                namespace = "${provider.id}_availability",
                configKey = "${provider.id}-availability.ttl",
                defaultTtl = DEFAULT_AVAILABILITY_TTL,
            )

        val entries: List<ApiCacheEntity> = listOf(ROUTE) + BookingProvider.entries.map(::availability)
    }
}
```

`ApiCacheConfig.kt`: add `fun availabilityTtl(provider: BookingProvider): Duration = ttlFor(ApiCacheEntity.availability(provider))`. `fromConfig` already iterates `ApiCacheEntity.entries`.

`CampsiteAvailabilityService.kt`:

```kotlin
internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration = ApiCacheEntity.availability(providerId).defaultTtl
```

`RouteModule.kt:210`: pass `snapshotFreshnessTtl = { provider -> config.cache.availabilityTtl(provider.id) }` where `config` is the `AppConfig` already resolvable in that module (`get<AppConfig>()`; look at how neighbouring bindings fetch it). This is a behaviour fix: the configured `roadtrip.cache.<vendor>-availability.ttl` was previously ignored by the snapshot freshness check.

Grep `ApiCacheEntity\.` across `backend/src` for any remaining enum-constant references (`RECGOV_AVAILABILITY` etc.) and replace them with `ApiCacheEntity.availability(BookingProvider.X)`.

- [ ] **Step 4: Run the gate**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.*' --tests 'ca.floo.roadtrip.service.availability.*' --tests 'ca.floo.roadtrip.service.routing.*' --tests 'ca.floo.roadtrip.repo.ApiCacheRepoTest' --tests 'ca.floo.roadtrip.di.*' :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "refactor(config): availability cache entities keyed by BookingProvider

Drops the five-arm TTL when; the snapshot freshness check now reads the
configured per-vendor TTL instead of the compiled default.

Refs #733

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Typed scope on the observation batch and the availability API (#734)

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/AvailabilityObservationBatch.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseMapper.kt:68-83,150-200`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/AvailabilityLoader.kt:29-35,128-172`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/{RecGov,Campflare,Aspira,ReserveAmerica,ReserveCalifornia}AvailabilityProvider.kt`
- Modify: `frontend/src/api/availability-api.ts:27-42`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/api/AvailabilityResponseTest.kt:49-56,119-124,151-152`
- Test: any other test under `backend/src/test` that passes `campgroundId =`, `host =`, or `mapId =` to `AvailabilityObservationBatch(` (the compiler lists them).

**Interfaces:**
- Produces:
  ```kotlin
  data class AvailabilityObservationBatch(
      val provider: String, val startDate: LocalDate, val endDate: LocalDate,
      val observations: List<CampsiteDayObservation>, val cacheBlock: AvailabilityCacheBlock,
      val seasonBlock: AvailabilitySeasonBlock? = null,
      val scope: BookingProviderRef? = null,
      val campsiteId: Long? = null,
  )
  AvailabilityResponseDto: @SerialName("scope_ref") val scopeRef: String? = null   // replaces campground_id, host, map_id
  AvailabilityLoader.Metadata(provider: String, scope: BookingProviderRef? = null, campsiteId: Long? = null)
  fun availabilityResponseDto(provider, startDate, endDate, perDay, state, seasonBlock, cacheBlock, scopeRef: String? = null, campsiteId: Long? = null)
  ```
  Frontend `CampsiteAvailability` gains `scope_ref?: string | null` and loses the three vendor fields.

- [ ] **Step 1: Rewrite the response tests**

Line 49: `scope = BookingProviderRef.RecGov(facilityId = "232447"),` and line 56: `assertEquals("232447", json["scope_ref"]!!.jsonPrimitive.content)`.
Line 119: same `scope`, line 124: `assertEquals("232447", dto.scopeRef)`.
Lines 151-152: `scope = BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = 4189, mapId = -2147483388, resourceLocationId = null),` and add `assertEquals("pc:4189:-2147483388:null", dto.scopeRef)`.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.api.AvailabilityResponseTest' --offline -q`
Expected: no parameter named `scope`.

- [ ] **Step 3: Model and DTO**

`AvailabilityObservationBatch.kt`: delete `campgroundId`, `host`, `mapId`; add `val scope: BookingProviderRef? = null` (import `ca.floo.roadtrip.model.domain.provider.BookingProviderRef`).

`AvailabilityResponseDto.kt`: delete the `campgroundId`, `host`, `mapId` fields; add `@SerialName("scope_ref") val scopeRef: String? = null` after `provider`.

`AvailabilityResponseMapper.kt`: `availabilityResponseDto(...)` takes `scopeRef: String? = null` instead of the three; `availabilityResponseFromObservations` passes `scopeRef = batch.scope?.serialize()`. Replace the KDoc at line 150 with one sentence: "`scope_ref` is the serialized `BookingProviderRef` the observations were fetched under; it is opaque to clients."

`AvailabilityLoader.kt`: `Metadata(provider, scope: BookingProviderRef? = null, campsiteId: Long? = null)`; `metadataFromBatch` copies `scope = batch.scope ?: fallback.scope`; `batchFromLatest` sets `scope = request.metadata.scope`.

- [ ] **Step 4: Adapters fill their own variant**

- `RecGovAvailabilityProvider.kt:103,133`: `scope = BookingProviderRef.RecGov(facilityId = recgovId)` replaces `campgroundId = recgovId`.
- `CampflareAvailabilityProvider.kt:139-152`: `batch(campgroundId, ...)` sets `scope = BookingProviderRef.Campflare(campgroundId = campgroundId)`.
- `ReserveCaliforniaAvailabilityProvider.kt:131-140`: `scope = ref`; delete the `campgroundId`, `host`, `mapId` lines.
- `ReserveAmericaAvailabilityProvider.kt:141-160`: change the helper to `batch(scope: BookingProviderRef.ReserveAmerica, startDate, endDate, observations, campsiteId = null)` and at each call site build `BookingProviderRef.ReserveAmerica(contractCode = tenant.contractCode, parkId = reserveAmericaRef.parkId)`.
- `AspiraAvailabilityProvider.kt`: every `AvailabilityObservationBatch(...)` currently passing `host = host, mapId = ...` (lines ~180, 198, 225, 248, 266) takes a `scope: BookingProviderRef.Aspira` parameter instead. In `availability(...)` pass `aspiraRef.copy(mapId = mapId.toLong())`; in the catalog paths pass `aspiraRef`. Thread the parameter through `fetchAvailability`, `fetchCatalog`, and `fetchCatalogOccupancy` signatures; `host` stays a parameter where the client call needs it.

Fix every test the compiler flags by replacing `campgroundId = "x"` with `scope = BookingProviderRef.RecGov("x")` (or the vendor that test is about) or by deleting the argument when the test never asserts on it.

- [ ] **Step 5: Frontend type**

`frontend/src/api/availability-api.ts`:

```ts
export interface CampsiteAvailability {
  provider: string;
  /** Serialized BookingProviderRef the window was fetched under; opaque. */
  scope_ref?: string | null;
  campsite_id?: number | null;
  ...
```

Remove `campground_id`, `host`, `map_id`. Run `grep -rn 'campground_id\|map_id' frontend/src` and confirm only unrelated hits remain.

- [ ] **Step 6: Run both gates**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q && (cd frontend && npm run typecheck && npm test && npm run lint)`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A backend frontend/src/api/availability-api.ts
git commit -m "refactor(availability): typed scope ref on the observation batch

Adapters fill their own BookingProviderRef variant; the API emits scope_ref
instead of the Aspira-shaped campground_id/host/map_id triple.

Refs #734

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Status strings become enums (#741)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/ingest/RunStatus.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/ingest/RunOutcome.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/IngestController.kt:139,151,155`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/admin/AdminIngestRoutes.kt:115-119,154,162,226-233`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovAvailabilityProvider.kt:235-247`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaCampgroundsEtl.kt:77-90`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt:271`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovObservationsTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt` (existing `noop` assertion must keep passing)

**Interfaces:**
- Produces: `enum class RunStatus(val wire: String) { COMPLETED("completed"), FAILED("failed"), NOOP("noop") }`; `RunOutcome.status: RunStatus`.

Note: `AvailabilityPollExecutor.kt` and `AvailabilityRunService.kt` also mention a `RunOutcome`; check whether that is this class or a same-named availability type before editing.

- [ ] **Step 1: Write the failing rec.gov status test**

Add to `RecGovObservationsTest.kt`, next to `not reservable maps to first come`:

```kotlin
@Test
fun `unrecognised upstream status maps to unknown, never reserved`() {
    val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Walk-Up Only")))
    val body = classify(clientReturning(map), days = 1)
    val day = body["availability"]!!.jsonArray.single().jsonObject

    assertEquals("unknown", day["campsite_statuses"]!!.jsonObject["100"]!!.jsonPrimitive.content)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.provider.RecGovObservationsTest' --offline -q`
Expected: FAIL, actual `reserved`.

- [ ] **Step 3: Implement**

`RecGovAvailabilityProvider.classifyRecgovStatus`: change `else -> AvailabilityStatus.RESERVED` to `else -> AvailabilityStatus.UNKNOWN`.

`RunStatus.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.ingest

enum class RunStatus(val wire: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    NOOP("noop"),
}
```

`RunOutcome.status: RunStatus`; `IngestController` passes `RunStatus.NOOP`, `RunStatus.FAILED`, `RunStatus.COMPLETED`. `AdminIngestRoutes.runOne`:

```kotlin
val status = if (outcome.status == RunStatus.FAILED) HttpStatusCode.InternalServerError else HttpStatusCode.OK
```

and `toSchema()` sets `status = status.wire`. The batch runner in the same file compares and forwards the status too: line 154 becomes `if (outcome.status == RunStatus.FAILED) anyFailed = true` and line 162 passes `outcome.status.wire`.

`ReserveAmericaCampgroundsEtl.kt:77-90`:

```kotlin
val bookingProvider = BookingProvider.fromIdOrNull(settings.provider.trim().lowercase())?.takeIf { it == BookingProvider.RESERVEAMERICA }
...
bookingProvider = bookingProvider,
bookingProviderRef = bookingProvider?.let { "${settings.contract}:${park.parkId}" },
```

(`val bookingProvider` goes just above the `yield`.) `PoiRegistry.kt:271`: replace the `"reserveamerica"` literal with `BookingProvider.RESERVEAMERICA.id`.

- [ ] **Step 4: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "refactor(backend): typed run status; unknown rec.gov statuses are unknown

Refs #741

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Docs

**Files:**
- Modify: `docs/reservation-providers.md` (the "Provider-ref resolution" section and the "Preference wiring" paragraph)
- Modify: `docs/backend-architecture.md` ("Availability Flow" section)

- [ ] **Step 1: Document the single ref encoding**

In `docs/reservation-providers.md`, after the layout tree, add:

```markdown
## One ref encoding

`booking_provider` + `booking_provider_ref` (the colon-delimited
`BookingProviderRef.serialize()` form) is the only booking identity. The POI
detail API emits it as `booking_ref: {provider, ref}` and the availability
API as `scope_ref`. Nothing on the serving path reads `source_payload`; a key
an ETL writes there is provenance, not behaviour.
```

In `docs/backend-architecture.md` "Availability Flow", append one line: "`AvailabilityObservationBatch.scope` is the typed `BookingProviderRef` the adapter fetched under; adapters never put vendor ids in generic fields."

- [ ] **Step 2: Commit**

```bash
git add docs/reservation-providers.md docs/backend-architecture.md
git commit -m "docs: one booking ref encoding on the serving path

Refs #730 #734

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
