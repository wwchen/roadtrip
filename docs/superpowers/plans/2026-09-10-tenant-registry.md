# Tenant Registry and Vendor Display Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `booking_providers` section in `poi-registry.yaml` becomes the single source for every booking vendor's tenants, hosts, display names, and CTA verbs, and every Kotlin literal, YAML arg, and frontend table that restated them is deleted.

**Architecture:** A new top-level YAML section deserializes into `BookingProviderEntry`/`TenantEntry`, is validated at boot by `PoiRegistry.validate`, and is projected into a `TenantRegistry` of `BookingVendorProfile`/`BookingTenant` domain rows. The Aspira and ReserveAmerica availability adapters take `List<BookingTenant>` instead of hand-built maps; `CampgroundCta`, `ExternalInfoLinkLabels`, `AtcTriggerActionHandler`, and `WatchCapabilityService` ask the registry for names and labels; the campground drawer and the campsite rows share one `BookingIdentityResolver`; and the frontend renders the `booking_system` / `provider_display` the backend serves instead of guessing from hosts and vendor slugs.

**Tech Stack:** Kotlin 2 / Ktor / kaml (`com.charleskorn.kaml`) / kotlinx.serialization / jOOQ + Postgres (Testcontainers); React + TypeScript + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-10-tenant-registry-design.md`. Audit finding 9 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issue #738. Builds on phase 4a, `docs/superpowers/plans/2026-09-10-booking-port.md`.

## Global Constraints

- **No migration in this phase.** `V61__booking_alias_indexes.sql` is the highest applied migration and stays the highest. Nothing here touches the database schema. `V12` and `V45` are applied migrations that keep their `aspira_*` strings; never edit an applied migration.
- Layering per `docs/backend-architecture.md`: SQL only in `repo/` and migrations; no Ktor in `service/`; models depend on stdlib + serialization only; ETLs never touch repos; adapters do not surface vendor types through the port; routes are the HTTP shell and never compute a display name.
- No inline magic constants: CTA verbs, host literals, and label prefixes are `const val`. Comments short and rare.
- **The registry section, copied verbatim from the spec.** This exact YAML is what Task 1 appends to `backend/src/main/resources/poi-registry.yaml`:

```yaml
booking_providers:
  - id: recgov
    display_name: Recreation.gov
    sells: true
    tenants:
      - host: www.recreation.gov
  - id: campflare
    display_name: Campflare
    sells: false
    tenants:
      - host: campflare.com
  - id: aspira
    display_name: Aspira NextGen
    sells: true
    tenants:
      - code: pc
        host: reservation.pc.gc.ca
        display_name: Parks Canada
      - code: bc
        host: camping.bcparks.ca
        display_name: BC Parks
      - code: wa
        host: washington.goingtocamp.com
        display_name: Washington State Parks
  - id: reserveamerica
    display_name: ReserveAmerica
    sells: true
    tenants:
      - code: ABPP
        host: shop.albertaparks.ca
        display_name: Alberta Parks
      - code: NY
        host: newyorkstateparks.reserveamerica.com
        display_name: New York State Parks
  - id: reservecalifornia
    display_name: ReserveCalifornia
    sells: true
    tenants:
      - host: www.reservecalifornia.com
```

- **The exact display names `booking_system` must read after this phase:** `Parks Canada`, `BC Parks`, `Washington State Parks`, `Alberta Parks`, `New York State Parks`, `Recreation.gov`, `Campflare`, `ReserveCalifornia`. A ref whose tenant the registry does not know reads the vendor name: `Aspira NextGen`, `ReserveAmerica`.
- **The CTA label templates:** `"Reserve on <name>"` when the provider's `sells` is true, `"View on <name>"` when it is false. The name is data; the verb is not. The six hand-written labels (`Book WA State Park`, `Book on BC Parks`, `Reserve on parks.canada.ca`, `Reserve on Recreation.gov`, `View on Campflare`, `Reserve on ReserveCalifornia`) are deleted.
- A tenant's `display_name`, when absent, is the vendor's `display_name`. `code` is the tenant key as stored in `booking_provider_ref` (`pc:`, `ABPP:`) and as ETL rows name it in `args.tenant` / `args.contract`. Single-tenant vendors have one row with no code.
- Host matching is case-insensitive and `www.`-insensitive (`UrlHosts.extract` already strips `www.`), so `www.recreation.gov` in YAML matches a stored `https://recreation.gov/...`.
- No `time_zone` and no `booking_horizon_days` on a registry row. The Aspira deeplink is dated from the POI's `PoiDateContext.earliestDate`; the horizon stays the adapter's `capabilities.bookingHorizonDays`.
- Out of scope: the agency info-link hosts in `ExternalInfoLinkLabels` (fs.usda.gov, nps.gov, blm.gov, fws.gov, usace.army.mil, usbr.gov, tva.gov, bcparks.ca, albertaparks.ca, pc.gc.ca, planetfitness.com, tesla.com) and `regionalParkSearch` in the frontend.
- Backend gate: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` (Docker running). Frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- One commit per task, conventional prefix, `Refs #738`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

---

### Task 1: The `booking_providers` section, `TenantRegistry`, and boot validation

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/BookingProviderEntry.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/provider/BookingTenant.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/TenantRegistry.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/TenantRegistryTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/fixtures/TenantRegistryFixture.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt` (new `bookingProviders` constructor property + `validateBookingProviders`), `backend/src/main/resources/poi-registry.yaml` (append the section from Global Constraints), `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt` (`single<TenantRegistry>`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistryValidatorTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/TenantRegistryTest.kt`

**Interfaces:**
- Consumes: `BookingProvider` (`model/domain/provider/BookingProvider.kt`, `entries`, `id`, `fromIdOrNull`), `BookingProviderRef` and its `Aspira.tenant` / `ReserveAmerica.contractCode` fields, `EtlEntry(slug, adapter, inputs, args)`.
- Produces:
  - `@Serializable data class BookingProviderEntry(val id: BookingProvider, @SerialName("display_name") val displayName: String, val sells: Boolean, val tenants: List<TenantEntry> = emptyList())`
  - `@Serializable data class TenantEntry(val code: String? = null, val host: String, @SerialName("display_name") val displayName: String? = null)`
  - `data class BookingTenant(val provider: BookingProvider, val code: String?, val host: String, val displayName: String)`
  - `data class BookingVendorProfile(val provider: BookingProvider, val displayName: String, val sells: Boolean, val tenants: List<BookingTenant>)`
  - `class TenantRegistry` with `fun profile(provider: BookingProvider): BookingVendorProfile`, `fun tenantsOf(provider: BookingProvider): List<BookingTenant>`, `fun tenant(provider: BookingProvider, code: String?): BookingTenant?`, `fun tenantByHost(host: String): BookingTenant?`, `fun displayName(provider: BookingProvider): String`, `fun displayName(ref: BookingProviderRef): String`, `fun sells(provider: BookingProvider): Boolean`, `fun ctaLabel(ref: BookingProviderRef): String`, `fun linkLabel(host: String): String?`, and `companion object { fun from(entries: List<BookingProviderEntry>): TenantRegistry; fun from(registry: PoiRegistry): TenantRegistry }`
  - `PoiRegistry.bookingProviders: List<BookingProviderEntry>` (third constructor property, defaulted to `emptyList()` so the tests that build a `PoiRegistry` directly keep compiling)
  - Test fixture `internal fun shippedTenantRegistry(): TenantRegistry`

- [ ] **Step 1: Write the failing `TenantRegistryTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/TenantRegistryTest.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantRegistryTest {
    private val registry = TenantRegistry.from(PoiRegistry.loadResource("poi-registry.yaml"))

    @Test
    fun `displayName names the tenant for a known aspira tenant`() {
        assertEquals("BC Parks", registry.displayName(aspiraRef("bc")))
        assertEquals("Parks Canada", registry.displayName(aspiraRef("pc")))
        assertEquals("Washington State Parks", registry.displayName(aspiraRef("wa")))
    }

    @Test
    fun `displayName falls back to the vendor for an unknown tenant`() {
        assertEquals("Aspira NextGen", registry.displayName(aspiraRef("zz")))
        assertEquals("Aspira NextGen", registry.displayName(aspiraRef(null)))
        assertEquals(
            "ReserveAmerica",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "ZZ", parkId = "1")),
        )
    }

    @Test
    fun `displayName on a single-tenant vendor is the vendor name`() {
        assertEquals("Recreation.gov", registry.displayName(BookingProviderRef.RecGov(facilityId = "232450")))
        assertEquals("Campflare", registry.displayName(BookingProviderRef.Campflare(campgroundId = "abc")))
        assertEquals(
            "ReserveCalifornia",
            registry.displayName(BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901))),
        )
    }

    @Test
    fun `displayName names the reserveamerica contract`() {
        assertEquals(
            "Alberta Parks",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "ABPP", parkId = "10")),
        )
        assertEquals(
            "New York State Parks",
            registry.displayName(BookingProviderRef.ReserveAmerica(contractCode = "NY", parkId = "10")),
        )
    }

    @Test
    fun `ctaLabel picks the verb from sells`() {
        assertEquals("Reserve on BC Parks", registry.ctaLabel(aspiraRef("bc")))
        assertEquals("Reserve on Recreation.gov", registry.ctaLabel(BookingProviderRef.RecGov(facilityId = "1")))
        assertEquals("View on Campflare", registry.ctaLabel(BookingProviderRef.Campflare(campgroundId = "1")))
    }

    @Test
    fun `linkLabel answers by host, ignoring www and case`() {
        assertEquals("Reserve on Recreation.gov", registry.linkLabel("WWW.Recreation.gov"))
        assertEquals("Reserve on Recreation.gov", registry.linkLabel("recreation.gov"))
        assertEquals("View on Campflare", registry.linkLabel("campflare.com"))
        assertEquals("Reserve on Washington State Parks", registry.linkLabel("washington.goingtocamp.com"))
        assertNull(registry.linkLabel("www.fs.usda.gov"))
        assertNull(registry.linkLabel("bcparks.ca"))
    }

    @Test
    fun `tenantsOf returns the vendor rows with resolved names`() {
        assertEquals(
            listOf("pc" to "Parks Canada", "bc" to "BC Parks", "wa" to "Washington State Parks"),
            registry.tenantsOf(BookingProvider.ASPIRA).map { it.code to it.displayName },
        )
        assertEquals(
            listOf(null to "Recreation.gov"),
            registry.tenantsOf(BookingProvider.RECGOV).map { it.code to it.displayName },
        )
    }

    @Test
    fun `sells is a registry fact`() {
        assertEquals(false, registry.sells(BookingProvider.CAMPFLARE))
        assertEquals(true, registry.sells(BookingProvider.RECGOV))
    }

    private fun aspiraRef(tenant: String?) =
        BookingProviderRef.Aspira(
            tenant = tenant,
            transactionLocationId = 4189,
            mapId = -2147483361,
            resourceLocationId = null,
        )
}
```

- [ ] **Step 2: Add the four failing validator cases to `PoiRegistryValidatorTest`**

Every fixture in that file goes through `PoiRegistry.loadString`, which now validates the new section, so first add a shared constant and prepend it to every existing fixture string in the file. At the top of `PoiRegistryValidatorTest.kt`, above the class:

```kotlin
/** The shipped booking_providers section. Every fixture needs it: validate()
 *  requires one row per BookingProvider member. */
private val BOOKING_PROVIDERS =
    """
    booking_providers:
      - id: recgov
        display_name: Recreation.gov
        sells: true
        tenants:
          - host: www.recreation.gov
      - id: campflare
        display_name: Campflare
        sells: false
        tenants:
          - host: campflare.com
      - id: aspira
        display_name: Aspira NextGen
        sells: true
        tenants:
          - code: pc
            host: reservation.pc.gc.ca
            display_name: Parks Canada
          - code: bc
            host: camping.bcparks.ca
            display_name: BC Parks
          - code: wa
            host: washington.goingtocamp.com
            display_name: Washington State Parks
      - id: reserveamerica
        display_name: ReserveAmerica
        sells: true
        tenants:
          - code: ABPP
            host: shop.albertaparks.ca
            display_name: Alberta Parks
          - code: NY
            host: newyorkstateparks.reserveamerica.com
            display_name: New York State Parks
      - id: reservecalifornia
        display_name: ReserveCalifornia
        sells: true
        tenants:
          - host: www.reservecalifornia.com
    """.trimIndent()
```

Then, in each existing test, change `PoiRegistry.loadString("""…""".trimIndent())` to `PoiRegistry.loadString(BOOKING_PROVIDERS + "\n" + """…""".trimIndent())`. Delete the test `reserveamerica provider tenants are read from terminal etl args` outright — `reserveAmericaSources()` is removed in Task 2, and the four new cases below replace what it covered.

Add these four tests to the class:

```kotlin
    @Test
    fun `a missing booking_providers row fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("  - id: campflare\n    display_name: Campflare\n    sells: false\n    tenants:\n      - host: campflare.com\n", "") +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers is missing a row for 'campflare'"), err.message)
    }

    @Test
    fun `a duplicate booking_providers host fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("host: camping.bcparks.ca", "host: www.recreation.gov") +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("duplicate booking_providers host 'recreation.gov'"), err.message)
    }

    @Test
    fun `an etl args tenant naming no tenant fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-zz
                            name: Aspira ZZ maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                        poi_data:
                          - name: Zed Parks
                            category: campground
                            agency: Zed Parks
                            etls:
                              - slug: aspira-zz-campgrounds
                                adapter: AspiraCampgroundsEtl
                                inputs: [aspira-maps-zz]
                                args:
                                  tenant: zz
                                  host: zz.goingtocamp.com
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains("poi_data 'Zed Parks' etl 'aspira-zz-campgrounds' args.tenant='zz' is not an aspira tenant"),
            err.message,
        )
    }

    @Test
    fun `an etl args host disagreeing with its tenant fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-bc
                            name: Aspira BC maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                        poi_data:
                          - name: BC Provincial Parks
                            category: campground
                            agency: BC Parks
                            etls:
                              - slug: aspira-bc-campgrounds
                                adapter: BcParksCampgroundsEtl
                                inputs: [aspira-maps-bc]
                                args:
                                  tenant: bc
                                  host: camping.example.test
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains(
                "poi_data 'BC Provincial Parks' etl 'aspira-bc-campgrounds' args.host='camping.example.test' " +
                    "does not match tenant 'bc' host 'camping.bcparks.ca'",
            ),
            err.message,
        )
    }
```

Add `import kotlin.test.assertFailsWith` and `import kotlin.test.assertTrue` if the file does not already have them.

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew :backend:test --offline -q --tests '*TenantRegistryTest' --tests '*PoiRegistryValidatorTest'`
Expected: FAIL — `TenantRegistry` and `BookingProviderEntry` are unresolved references.

- [ ] **Step 4: Write the registry entry rows**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/BookingProviderEntry.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row in the `booking_providers` section: a booking vendor, the name a
 * person calls it, whether a person books on its own site, and the hosts it
 * runs. One row per [BookingProvider] member — the boot validator enforces it.
 */
@Serializable
data class BookingProviderEntry(
    val id: BookingProvider,
    @SerialName("display_name") val displayName: String,
    val sells: Boolean,
    val tenants: List<TenantEntry> = emptyList(),
)

/**
 * One host a vendor runs. [code] is the tenant key as it is stored in
 * `booking_provider_ref` and as ETL rows name it in `args.tenant` /
 * `args.contract`; single-tenant vendors have one row with no code.
 * [displayName] absent means the vendor's own name.
 */
@Serializable
data class TenantEntry(
    val code: String? = null,
    val host: String,
    @SerialName("display_name") val displayName: String? = null,
)
```

- [ ] **Step 5: Write the domain rows**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/provider/BookingTenant.kt`:

```kotlin
package ca.floo.roadtrip.model.domain.provider

/** One host a booking vendor runs, with the name a person books under. */
data class BookingTenant(
    val provider: BookingProvider,
    val code: String?,
    val host: String,
    val displayName: String,
)

/** A booking vendor as the registry declares it. */
data class BookingVendorProfile(
    val provider: BookingProvider,
    val displayName: String,
    val sells: Boolean,
    val tenants: List<BookingTenant>,
)
```

- [ ] **Step 6: Write `TenantRegistry`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/TenantRegistry.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.BookingTenant
import ca.floo.roadtrip.model.domain.provider.BookingVendorProfile

/** The verb is ours; the name is data. */
private const val RESERVE_VERB = "Reserve on"
private const val VIEW_VERB = "View on"
private const val HOST_WWW_PREFIX = "www."

/**
 * The `booking_providers` section, projected for lookup.
 *
 * Every vendor fact a person reads — the name, the tenant's name, whether the
 * vendor sells — lives here rather than in an adapter, a `*BookingDisplay`
 * object, or a frontend table. Built from a validated [PoiRegistry], so
 * [profile] may fail loudly on a provider the section does not name.
 */
class TenantRegistry private constructor(
    private val profiles: Map<BookingProvider, BookingVendorProfile>,
) {
    private val byHost: Map<String, BookingTenant> =
        profiles.values
            .flatMap { it.tenants }
            .associateBy { normalizeHost(it.host) }

    fun profile(provider: BookingProvider): BookingVendorProfile =
        profiles[provider] ?: error("booking_providers has no row for '${provider.id}'")

    fun tenantsOf(provider: BookingProvider): List<BookingTenant> = profile(provider).tenants

    fun tenant(
        provider: BookingProvider,
        code: String?,
    ): BookingTenant? = tenantsOf(provider).firstOrNull { it.code == code }

    fun tenantByHost(host: String): BookingTenant? = byHost[normalizeHost(host)]

    fun displayName(provider: BookingProvider): String = profile(provider).displayName

    /** The ref's tenant name when the ref names a known tenant, else the vendor name. */
    fun displayName(ref: BookingProviderRef): String =
        tenant(ref.provider, tenantCodeOf(ref))?.displayName ?: displayName(ref.provider)

    fun sells(provider: BookingProvider): Boolean = profile(provider).sells

    fun ctaLabel(ref: BookingProviderRef): String = label(sells(ref.provider), displayName(ref))

    /** The same label by host; null for a host no vendor runs. */
    fun linkLabel(host: String): String? = tenantByHost(host)?.let { label(sells(it.provider), it.displayName) }

    private fun label(
        sells: Boolean,
        name: String,
    ): String = if (sells) "$RESERVE_VERB $name" else "$VIEW_VERB $name"

    companion object {
        fun from(registry: PoiRegistry): TenantRegistry = from(registry.bookingProviders)

        fun from(entries: List<BookingProviderEntry>): TenantRegistry {
            val profiles =
                entries.associate { entry ->
                    entry.id to
                        BookingVendorProfile(
                            provider = entry.id,
                            displayName = entry.displayName,
                            sells = entry.sells,
                            tenants =
                                entry.tenants.map { tenant ->
                                    BookingTenant(
                                        provider = entry.id,
                                        code = tenant.code,
                                        host = tenant.host,
                                        displayName = tenant.displayName ?: entry.displayName,
                                    )
                                },
                        )
                }
            val missing = BookingProvider.entries.filter { it !in profiles }
            require(missing.isEmpty()) {
                "booking_providers is missing a row for ${missing.joinToString { "'${it.id}'" }}"
            }
            return TenantRegistry(profiles)
        }

        /** Only Aspira and ReserveAmerica refs carry a tenant key. */
        internal fun tenantCodeOf(ref: BookingProviderRef): String? =
            when (ref) {
                is BookingProviderRef.Aspira -> ref.tenant
                is BookingProviderRef.ReserveAmerica -> ref.contractCode
                else -> null
            }

        internal fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix(HOST_WWW_PREFIX)
    }
}
```

- [ ] **Step 7: Add the section and its validation to `PoiRegistry`**

In `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt`, add the constructor property after `campsiteData`:

```kotlin
    @kotlinx.serialization.SerialName("booking_providers")
    val bookingProviders: List<BookingProviderEntry> = emptyList(),
```

Add the call inside `validate()`, immediately after the agency loop and before `validateEtlSection`:

```kotlin
        validateBookingProviders(errs)
```

Add these members to the class (`ARG_TENANT_PROVIDERS` above the class, beside `CAMPSITE_DATA_SECTION`):

```kotlin
private const val POI_DATA_SECTION = "poi_data"

/** ETL arg key → the vendor whose tenant it must name. */
private val TENANT_ARG_PROVIDERS =
    mapOf(
        "tenant" to BookingProvider.ASPIRA,
        "contract" to BookingProvider.RESERVEAMERICA,
    )

private const val ARG_HOST = "host"
```

```kotlin
    /**
     * The `booking_providers` section: one row per [BookingProvider] member,
     * tenant codes unique per vendor, hosts unique across the section, and
     * every ETL row that names a tenant naming a real one at the right vendor.
     */
    private fun validateBookingProviders(errs: MutableList<String>) {
        val byProvider = bookingProviders.groupBy { it.id }
        for (provider in BookingProvider.entries) {
            val rows = byProvider[provider].orEmpty()
            if (rows.isEmpty()) errs += "booking_providers is missing a row for '${provider.id}'"
            if (rows.size > 1) errs += "booking_providers has ${rows.size} rows for '${provider.id}'"
        }
        val hosts = mutableSetOf<String>()
        for (entry in bookingProviders) {
            val codes = mutableSetOf<String?>()
            for (tenant in entry.tenants) {
                if (!codes.add(tenant.code)) {
                    errs += "booking_providers '${entry.id.id}' has duplicate tenant code '${tenant.code}'"
                }
                val host = TenantRegistry.normalizeHost(tenant.host)
                if (!hosts.add(host)) errs += "duplicate booking_providers host '$host'"
            }
        }
        if (errs.isNotEmpty()) return
        val registry = TenantRegistry.from(bookingProviders)
        validateEtlTenantArgs(POI_DATA_SECTION, poiData.map { EtlRowRef(it.name, it.etls) }, registry, errs)
        validateEtlTenantArgs(CAMPSITE_DATA_SECTION, campsiteData.map { EtlRowRef(it.name, it.etls) }, registry, errs)
    }

    private fun validateEtlTenantArgs(
        label: String,
        rows: List<EtlRowRef>,
        registry: TenantRegistry,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            for (etl in row.etls) {
                for ((argKey, provider) in TENANT_ARG_PROVIDERS) {
                    val code = etl.args[argKey] ?: continue
                    val tenant = registry.tenant(provider, code)
                    if (tenant == null) {
                        errs += "$label '${row.name}' etl '${etl.slug}' args.$argKey='$code' is not a ${provider.id} tenant"
                        continue
                    }
                    val declaredHost = etl.args[ARG_HOST] ?: continue
                    if (TenantRegistry.normalizeHost(declaredHost) != TenantRegistry.normalizeHost(tenant.host)) {
                        errs += "$label '${row.name}' etl '${etl.slug}' args.host='$declaredHost' " +
                            "does not match tenant '$code' host '${tenant.host}'"
                    }
                }
            }
        }
    }
```

`EtlRowRef` is already a private nested data class in this file; move nothing.

- [ ] **Step 8: Append the section to the shipped YAML**

Append the exact `booking_providers` block from Global Constraints to the end of `backend/src/main/resources/poi-registry.yaml`, preceded by a banner comment matching the file's style:

```yaml

# ============================================================================
# booking_providers — one row per booking vendor: the name a person calls it,
# whether they book on its own site, and the hosts it runs. Display copy and
# CTA verbs come from here; adapters read tenants from here through
# TenantRegistry. See docs/reservation-providers.md.
# ============================================================================
```

Update the file's header comment: change "Four sections." to "Five sections." and add:

```yaml
# 4. booking_providers — booking vendors and their tenants. See the section banner.
```

Also delete the header's stale paragraph "Reservation provider info is NOT in this file. …" and replace it with:

```yaml
# Reservation *vendor* facts live in booking_providers. ETL rows still carry the
# per-tenant `args` (`host`, `tenant`, `contract`) they build refs from; the
# validator checks those against booking_providers at boot.
```

- [ ] **Step 9: Wire the registry into DI**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`, add the import `ca.floo.roadtrip.model.metadata.registry.TenantRegistry` and, immediately after the `single<PoiRegistry> { … }` block:

```kotlin
        single<TenantRegistry> { TenantRegistry.from(get<PoiRegistry>()) }
```

- [ ] **Step 10: Add the shipped-registry test fixture**

Create `backend/src/test/kotlin/ca/floo/roadtrip/fixtures/TenantRegistryFixture.kt`:

```kotlin
package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry

/**
 * The registry the app actually ships. Display tests pin the shipped names
 * rather than a hand-built copy, so a YAML edit that changes what a user reads
 * fails here instead of in production.
 */
internal fun shippedTenantRegistry(): TenantRegistry = TenantRegistry.from(PoiRegistry.loadResource("poi-registry.yaml"))
```

- [ ] **Step 11: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS (`production poi-registry resource validates` now exercises the new section).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model backend/src/main/resources/poi-registry.yaml backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt backend/src/test/kotlin/ca/floo/roadtrip/model backend/src/test/kotlin/ca/floo/roadtrip/fixtures/TenantRegistryFixture.kt
git commit -m "$(cat <<'EOF'
feat(registry): booking_providers section and the tenant registry

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Adapters and ETLs read the registry

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraAvailabilityProvider.kt`, `.../ReserveAmericaAvailabilityProvider.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/provider/DataProviderRef.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt` (delete `reserveAmericaSources`), `backend/src/main/resources/poi-registry.yaml` (`tenant: bc` on the BC row; delete both `booking_horizon_days` args)
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraTenant.kt`, `.../AspiraTenants.kt`, `.../ReserveAmericaTenant.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/ReserveAmericaSourceConfig.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraObservationsTest.kt`, `.../AspiraAvailabilityProviderTest.kt`, `.../AvailabilityProviderRegistryTest.kt`, `.../AvailabilityProviderContractTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/BookingAliasClaimTest.kt`, `.../ProviderUpstreamErrorMappingTest.kt`

**Interfaces:**
- Consumes: `TenantRegistry.tenantsOf(provider): List<BookingTenant>`, `BookingTenant(provider, code, host, displayName)` (Task 1).
- Produces:
  - `class AspiraAvailabilityProvider(tenants: List<BookingTenant>, availabilityClient: AspiraAvailabilityClient, enabled: Boolean, occupancyEnabled: Boolean = false)`
  - `class ReserveAmericaAvailabilityProvider(tenants: List<BookingTenant>, availabilityClient: ReserveAmericaAvailabilityClient, enabled: Boolean)` — no companion `tenants` map
  - `class BcParksCampgroundsEtl(etlSlug: String = "aspira-bc-campgrounds", aspiraTenant: String)`
  - `DataProviderRef.BcParksCampsite(tenant: String, resourceLocationId: Long)` — no default on `tenant`

- [ ] **Step 1: Write the failing adapter tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraObservationsTest.kt`, replace the `tenants` map with the registry's rows:

```kotlin
    private val tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA)
```

adding `import ca.floo.roadtrip.fixtures.shippedTenantRegistry` and `import ca.floo.roadtrip.model.domain.provider.BookingProvider`, and drop the now-unused `PC_HOST`/`WA_HOST` constants only if nothing else in the file reads them. Do the same swap in `AspiraAvailabilityProviderTest.kt`, `AvailabilityProviderRegistryTest.kt`, `AvailabilityProviderContractTest.kt`, `BookingAliasClaimTest.kt`, and `ProviderUpstreamErrorMappingTest.kt` wherever an `AspiraTenant(...)` / `ReserveAmericaTenant(...)` literal or `ReserveAmericaAvailabilityProvider.tenants` appears — ReserveAmerica becomes `shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA)`.

Then add this new test to `AspiraObservationsTest`, which pins the boolean that replaces `vendorCode`:

```kotlin
    @Test
    fun `campground-level availability prefers per-resource rows`() = runTest {
        val client =
            FakeAspiraAvailabilityClient(
                AspiraAvailability(
                    byResource = mapOf("-2147483572" to listOf(0, 0)),
                    byMapLink = emptyMap(),
                    parkRollup = listOf(2, 2),
                ),
            )
        val provider =
            AspiraAvailabilityProvider(
                tenants = tenants,
                availabilityClient = client,
                enabled = true,
            )
        val batch = provider.availability(bcCampground, START_DATE, START_DATE.plusDays(2))
        assertEquals(2, batch.observations.size)
        assertTrue(batch.observations.all { it.status == AvailabilityStatus.AVAILABLE })
    }
```

Reuse whatever fake client and `bcCampground` fixture the file already declares; if the file names them differently, use its own names rather than introducing new ones.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --offline -q --tests '*AspiraObservationsTest'`
Expected: FAIL — `AspiraAvailabilityProvider` still wants `Map<String, AspiraTenant>`.

- [ ] **Step 3: Move `AspiraAvailabilityProvider` onto `BookingTenant`**

In `AspiraAvailabilityProvider.kt`: change the imports (add `ca.floo.roadtrip.model.domain.provider.BookingTenant`), the constructor, and the four sites that used `vendorCode`.

```kotlin
class AspiraAvailabilityProvider(
    tenants: List<BookingTenant>,
    private val availabilityClient: AspiraAvailabilityClient,
    private val enabled: Boolean,
    private val occupancyEnabled: Boolean = false,
) : AvailabilityProvider {
    private val tenantsByCode: Map<String, BookingTenant> =
        tenants.mapNotNull { tenant -> tenant.code?.let { it to tenant } }.toMap()
```

`supportsCampground`:

```kotlin
        return isEnabled() && ref is BookingProviderRef.Aspira && ref.tenant in tenantsByCode
```

`availability`:

```kotlin
        return runWithErrorMapping {
            fetchAvailability(
                scope = aspiraRef,
                host = tenant.host,
                startDate = startDate,
                endDate = endDate,
                // A campground-level fetch under a known tenant classifies the
                // per-resource rows when the upstream returns any.
                preferResourceRows = true,
            )
        }
```

`reservationUrlTemplate` and `tenantForRef`:

```kotlin
        val tenant = tenantsByCode[aspiraRef.tenant] ?: return null
```

```kotlin
    private fun tenantForRef(ref: BookingProviderRef.Aspira): BookingTenant =
        tenantsByCode[ref.tenant]
            ?: throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "tenant '${ref.tenant}' is not configured",
                cause = IllegalArgumentException("aspira tenant '${ref.tenant}' is not configured"),
            )
```

`fetchAvailability` and the free function it calls:

```kotlin
    private suspend fun fetchAvailability(
        scope: BookingProviderRef.Aspira,
        host: String,
        startDate: LocalDate,
        endDate: LocalDate,
        preferResourceRows: Boolean = false,
    ): AvailabilityObservationBatch {
        val days = daysBetween(startDate, endDate)
        val observedAt = Instant.now()
        val data = availabilityClient.fetch(host, mapIdOrThrow(scope.mapId), startDate, endDate.minusDays(1))
        return AvailabilityObservationBatch(
            provider = "aspira",
            startDate = startDate,
            endDate = endDate,
            observations = observationsFromAvailability(data, startDate, days, observedAt, preferResourceRows),
            cacheBlock = directFetchCacheBlock(),
            scope = scope,
        )
    }
```

```kotlin
private fun observationsFromAvailability(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
    observedAt: Instant,
    preferResourceRows: Boolean = false,
): List<CampsiteDayObservation> {
    if (preferResourceRows && avail.byResource.isNotEmpty()) {
        return observationsFromResourceCatalog(avail.byResource, start, days, observedAt)
    }
```

The `campsiteVendor` parameter and every `aspira_*` string in this file are gone; `fetchCatalog`'s call to `fetchAvailability` keeps the default `false`.

- [ ] **Step 4: Move `ReserveAmericaAvailabilityProvider` onto `BookingTenant`**

In `ReserveAmericaAvailabilityProvider.kt`: add `import ca.floo.roadtrip.model.domain.provider.BookingTenant`, change the constructor, delete the whole `companion object`, and add the file-private accessor.

```kotlin
class ReserveAmericaAvailabilityProvider(
    tenants: List<BookingTenant>,
    private val availabilityClient: ReserveAmericaAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    private val tenantsByCode: Map<String, BookingTenant> =
        tenants.mapNotNull { tenant -> tenant.code?.let { it to tenant } }.toMap()
```

```kotlin
        return isEnabled() && ref is BookingProviderRef.ReserveAmerica && ref.contractCode in tenantsByCode
```

```kotlin
    private fun tenantForRef(ref: BookingProviderRef.ReserveAmerica): BookingTenant =
        ref.contractCode?.let { tenantsByCode[it] }
            ?: throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "contract '${ref.contractCode}' is not configured",
                cause = IllegalArgumentException("reserveamerica contract '${ref.contractCode}' is not configured"),
            )
```

At the bottom of the file, beside the other private extensions:

```kotlin
/** Registry rows for ReserveAmerica always carry a contract code — the map is keyed by it. */
private val BookingTenant.contractCode: String
    get() = requireNotNull(code) { "reserveamerica tenant '$host' has no contract code" }
```

`fetch(tenant, …)`, `availability`, and `catalogAvailability` keep reading `tenant.contractCode` and `tenant.host` unchanged.

- [ ] **Step 5: Delete the old tenant tables and the dead source config**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraTenant.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraTenants.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/ReserveAmericaTenant.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/ReserveAmericaSourceConfig.kt
```

Delete `fun reserveAmericaSources()` and its KDoc from `PoiRegistry.kt`, along with the now-unused `import ca.floo.roadtrip.model.domain.provider.BookingProvider` only if `validateBookingProviders` (Task 1) did not keep it — it did, so leave the import.

- [ ] **Step 6: Rewire DI**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, drop the `AspiraTenants` import, add `ca.floo.roadtrip.model.metadata.registry.TenantRegistry`, and replace the two provider constructions inside `single<List<AvailabilityProvider>>(named("availabilityProviders"))`:

```kotlin
                AspiraAvailabilityProvider(
                    tenants = get<TenantRegistry>().tenantsOf(BookingProvider.ASPIRA),
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.ASPIRA),
                ),
                ReserveAmericaAvailabilityProvider(
                    tenants = get<TenantRegistry>().tenantsOf(BookingProvider.RESERVEAMERICA),
                    availabilityClient = get(),
                    enabled = config.isProviderEnabled(BookingProvider.RESERVEAMERICA),
                ),
```

The `removePrefix("aspira_")` derivation is gone with the line it lived on: the registry code *is* the stored prefix.

- [ ] **Step 7: Give the BC Parks ETL its tenant from args**

In `BcParksCampgroundsEtl.kt`:

```kotlin
class BcParksCampgroundsEtl(
    override val etlSlug: String = "aspira-bc-campgrounds",
    private val aspiraTenant: String,
) : CampgroundEtl<BcParksCampgroundsDto> {
```

In `campgroundBookingRef`, `tenant = ASPIRA_TENANT` becomes `tenant = aspiraTenant`; delete `const val ASPIRA_TENANT = "bc"` from the private companion (leave `REGION`/`COUNTRY`).

In `ProductionTerminalEtlRegistry.kt`:

```kotlin
        "BcParksCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.STRAPI) { entry ->
                campgroundSink(BcParksCampgroundsEtl(etlSlug = entry.slug, aspiraTenant = entry.args.require("tenant")))
            },
```

In `backend/src/main/resources/poi-registry.yaml`, add `tenant: bc` to the BC Provincial Parks row's args, above `host`:

```yaml
        args:
          tenant: bc
          host: camping.bcparks.ca
```

- [ ] **Step 8: Drop the `booking_horizon_days` args and the `BcParksCampsite` tenant default**

In `poi-registry.yaml`, delete the `booking_horizon_days: "270"` line from both `reserveamerica-ab-campgrounds` and `reserveamerica-ny-campgrounds`. Nothing reads it: `ReserveAmericaAvailabilityProvider.capabilities.bookingHorizonDays` is the honoured value.

In `DataProviderRef.kt`:

```kotlin
    data class BcParksCampsite(
        val tenant: String,
        val resourceLocationId: Long,
    ) : DataProviderRef {
```

`parseBcParks` already passes `tenant` at both call sites (`parts[0]` and `""`), so nothing else changes.

- [ ] **Step 9: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. If a test still names `AspiraTenant`, swap it to the registry rows as in Step 1 — no `aspira_*` string may survive outside `db/migration/V12__*.sql` and `V45__*.sql`. Confirm with `grep -rn "aspira_" backend/src/main/kotlin backend/src/test/kotlin` returning nothing.

- [ ] **Step 10: Commit**

```bash
git add -A backend/src/main backend/src/test
git commit -m "$(cat <<'EOF'
refactor(providers): Aspira and ReserveAmerica tenants come from the registry

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: One booking-identity rule for the drawer and the campsite rows

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BookingIdentityResolver.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/CampsiteDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Delete: `backend/src/test/kotlin/ca/floo/roadtrip/di/ShippingProfileCompanionConfigTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/PoiServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/model/api/CampsiteDtoTest.kt`, and the campsites-route test that covers `/api/pois/{id}/campsites` (`CampsiteRoutesTest`)

**Interfaces:**
- Consumes: `TenantRegistry.sells(provider)` and `TenantRegistry.displayName(ref)` (Task 1); `Campground.bookingIdentities(): List<BookingProviderRef>`, `Campground.bookingRef(): BookingProviderRef?`, `Campground.bookingRefFor(provider): BookingProviderRef?` (`model/domain/Campground.kt`); `Campsite.bookingIdentities(): List<BookingAlias>` (`service/availability/AvailabilityBookingTargetResolver.kt`); `ResolvedAvailabilityTarget(campsite, provider, campground, parentPoiId, dateContext, candidates)` with `parentRef`; `AvailabilityProvider.claimedRef(campground)`.
- Produces:
  - `internal class BookingIdentityResolver(tenants: TenantRegistry)` with `fun forCampground(campground: Campground, servingProvider: AvailabilityProvider?, declaredPrimary: BookingProviderRef? = campground.bookingRef()): BookingProviderRef?` and `fun forCampsite(resolved: ResolvedAvailabilityTarget): BookingProviderRef?`
  - `CampsiteDto.bookingSystem: String?` serialized as `booking_system`, and `CampsiteDto.from(row: Campsite, bookingSystem: String? = null)`
  - `internal class CampgroundService(campgroundRepo, dateResolver, bookingHorizons, identities: BookingIdentityResolver, cta: CampgroundCta)` — `bookingAdapters` is gone
  - `internal class CampsiteCatalogService(refResolver, campsitesRepo, targets, identities: BookingIdentityResolver, tenants: TenantRegistry)`

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/PoiServiceTest.kt`, add (adapting the file's own campground/detail builders rather than inventing new ones):

```kotlin
    @Test
    fun `an aliased Campflare pin books through rec_gov with no booking adapters registered`() {
        val detail = poiDetail(campflareCampgroundAliasedToRecGov, bookingAdapters = BookingAdapterRegistry(emptyList()))
        assertEquals("recgov", detail.detail?.bookingRef?.provider)
        assertEquals("Recreation.gov", detail.detail?.bookingSystem)
    }

    @Test
    fun `a Campflare-only pin stays Campflare`() {
        val detail = poiDetail(campflareOnlyCampground, bookingAdapters = BookingAdapterRegistry(emptyList()))
        assertEquals("campflare", detail.detail?.bookingRef?.provider)
        assertEquals("Campflare", detail.detail?.bookingSystem)
    }

    @Test
    fun `booking_system names the aspira tenant`() {
        assertEquals("BC Parks", poiDetail(bcParksCampground).detail?.bookingSystem)
        assertEquals("New York State Parks", poiDetail(newYorkCampground).detail?.bookingSystem)
    }
```

In `backend/src/test/kotlin/ca/floo/roadtrip/model/api/CampsiteDtoTest.kt`:

```kotlin
    @Test
    fun `booking_system is omitted when unknown and present when served`() {
        assertNull(encoded()["booking_system"])
        val named = roadtripApiJson.encodeToJsonElement(CampsiteDto.from(row, bookingSystem = "BC Parks")).jsonObject
        assertEquals("BC Parks", named["booking_system"]?.jsonPrimitive?.content)
    }
```

In the campsites-route test, add the two rows the spec asks for plus the template/host agreement the spec leaves implicit:

```kotlin
    @Test
    fun `campsite rows carry the booking site name, aliased and plain`() {
        val body = client.get("/api/pois/$aliasedPoiId/campsites").bodyAsText()
        val rows = Json.parseToJsonElement(body).jsonObject["campsites"]!!.jsonArray
        assertEquals("Recreation.gov", rows.single().jsonObject["booking_system"]?.jsonPrimitive?.content)

        val plain = Json.parseToJsonElement(client.get("/api/pois/$bcPoiId/campsites").bodyAsText()).jsonObject
        assertEquals(
            "BC Parks",
            plain["campsites"]!!.jsonArray.single().jsonObject["booking_system"]?.jsonPrimitive?.content,
        )
    }

    /**
     * The reservation template comes from the *serving* availability provider
     * (CampsiteCatalogService → targets.resolve(campsite).provider), while
     * booking_system comes from the identity resolver. On an aliased Campflare
     * row served by Campflare those are different objects, so pin that they
     * still name the same vendor: CampflareAvailabilityProvider builds its
     * template with RecGovBookingUrl, and the row sells on rec.gov.
     */
    @Test
    fun `an aliased Campflare row's template host and booking_system agree`() {
        val body = Json.parseToJsonElement(client.get("/api/pois/$aliasedPoiId/campsites").bodyAsText()).jsonObject
        val row = body["campsites"]!!.jsonArray.single().jsonObject
        val template =
            body["reservation_url_templates"]!!
                .jsonObject
                .values
                .single()
                .jsonPrimitive
                .content
        val host = URI(template).host.lowercase().removePrefix("www.")
        assertEquals("recreation.gov", host)
        assertEquals("Recreation.gov", row["booking_system"]?.jsonPrimitive?.content)
        assertEquals(
            "Recreation.gov",
            shippedTenantRegistry().tenantByHost(host)?.displayName,
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --offline -q --tests '*PoiServiceTest' --tests '*CampsiteDtoTest' --tests '*CampsiteRoutesTest'`
Expected: FAIL — `booking_system` is not on the DTO and the drawer's ref still follows `BookingAdapterRegistry`.

- [ ] **Step 3: Write `BookingIdentityResolver`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BookingIdentityResolver.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.bookingIdentities
import ca.floo.roadtrip.model.domain.bookingRef
import ca.floo.roadtrip.model.domain.bookingRefFor
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * The identity a row books through, for the drawer and the campsite rows alike.
 *
 * The rule is the registry's `sells`, not this process's booking adapters: a
 * Campflare row that rec.gov also sells opens on rec.gov whether or not the
 * ATC companion happens to be wired here, so a pin renders the same in every
 * environment.
 */
internal class BookingIdentityResolver(
    private val tenants: TenantRegistry,
) {
    fun forCampground(
        campground: Campground,
        servingProvider: AvailabilityProvider?,
        declaredPrimary: BookingProviderRef? = campground.bookingRef(),
    ): BookingProviderRef? =
        campground.bookingIdentities().firstOrNull { tenants.sells(it.provider) }
            ?: servingProvider?.claimedRef(campground)
            ?: declaredPrimary

    /**
     * The campsite row's identity. The site names its own vendors; the tenant
     * that names the site a person books on lives on the campground's ref for
     * that same vendor, so the two are paired before falling back to the
     * campground's own rule.
     */
    fun forCampsite(resolved: ResolvedAvailabilityTarget): BookingProviderRef? =
        resolved.campsite
            .bookingIdentities()
            .firstOrNull { tenants.sells(it.provider) }
            ?.let { resolved.campground.bookingRefFor(it.provider) }
            ?: forCampground(resolved.campground, resolved.provider)
}
```

- [ ] **Step 4: Move `CampgroundService` onto the resolver**

In `CampgroundService.kt`: delete the `BookingAdapterRegistry` import and constructor parameter, add `import ca.floo.roadtrip.service.availability.BookingIdentityResolver`, and replace the private `bookingRef` function with the resolver call.

```kotlin
internal class CampgroundService(
    private val campgroundRepo: CampgroundRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val bookingHorizons: BookingHorizonResolver,
    private val identities: BookingIdentityResolver,
    private val cta: CampgroundCta,
) : PoiDetailService {
```

```kotlin
        val bookingRef = identities.forCampground(campground, servingProvider, detail.bookingRef)
```

Delete the whole `private fun bookingRef(...)` block and the now-unused `bookingIdentities` / `Campground` / `AvailabilityProvider` imports the compiler flags.

- [ ] **Step 5: Serve `booking_system` on the campsite rows**

In `CampsiteDto.kt`, add the field after `bookingProvider` and the parameter to `from`:

```kotlin
    @SerialName("booking_provider") val bookingProvider: String? = null,
    /** The site this row's booking identity opens, by the same resolver as the drawer. */
    @SerialName("booking_system") val bookingSystem: String? = null,
) {
    companion object {
        fun from(
            row: Campsite,
            bookingSystem: String? = null,
        ): CampsiteDto =
```

and, at the end of the constructor call inside `from`:

```kotlin
                bookingProvider = row.bookingProvider,
                bookingSystem = bookingSystem,
            )
```

In `CampsiteCatalogService.kt`, take the resolver and registry, resolve each campsite once, and use that one resolution for both the template and the name:

```kotlin
internal class CampsiteCatalogService(
    private val refResolver: RefResolver,
    private val campsitesRepo: CampsiteRepo,
    private val targets: AvailabilityTargetResolver,
    private val identities: BookingIdentityResolver,
    private val tenants: TenantRegistry,
) {
    fun campsitesForPoi(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
    ): PoiCampsitesResponseSchema {
        val campgrounds = refResolver.resolve<RefValue.CampgroundId>(RefValue.PoiId(poiId))
        if (campgrounds.isEmpty()) throw AvailabilityServiceError.NotFound
        val rows =
            campsitesRepo
                .findByPoi(poiId)
                .filterBySiteTypes(siteTypes)
                .map { campsite -> campsite to targets.resolve(campsite) }
        return PoiCampsitesResponseSchema(
            poiId = poiId,
            type = CAMPSITE_RESPONSE_TYPE,
            campsites =
                rows.map { (campsite, resolved) ->
                    CampsiteDto.from(campsite, bookingSystem = bookingSystem(resolved))
                },
            reservationUrlTemplates =
                rows
                    .mapNotNull { (campsite, resolved) ->
                        reservationUrlTemplate(campsite, resolved)?.let { campsite.id to it }
                    }.toMap(),
        )
    }

    private fun bookingSystem(resolved: ResolvedAvailabilityTarget?): String? =
        resolved?.let { identities.forCampsite(it) }?.let(tenants::displayName)

    private fun reservationUrlTemplate(
        campsite: Campsite,
        resolved: ResolvedAvailabilityTarget?,
    ): String? =
        resolved?.parentRef?.let { ref ->
            resolved.provider.reservationUrlTemplate(campsite, ref)
        }
}
```

Add `import ca.floo.roadtrip.model.metadata.registry.TenantRegistry`.

- [ ] **Step 6: Rewire DI**

In `ServiceModule.kt`, add `single { BookingIdentityResolver(get<TenantRegistry>()) }` beside the other availability singles, and change the `CampgroundService` construction:

```kotlin
                CampgroundService(
                    campgroundRepo = get<CampgroundRepo>(),
                    dateResolver = get<AvailabilityDateResolver>(),
                    bookingHorizons = get<BookingHorizonResolver>(),
                    identities = get<BookingIdentityResolver>(),
                    cta = get<CampgroundCta>(),
                ),
```

`CampgroundCta` is still `CampgroundCta.default` until Task 4; until then write `cta = CampgroundCta.default` here and switch it in Task 4 Step 7.

In `RouteModule.kt`, thread the two new arguments into the catalog service. `campsiteAvailabilityController` gains `identities: BookingIdentityResolver` and `tenants: TenantRegistry` parameters, supplied by its Koin caller with `get<BookingIdentityResolver>()` / `get<TenantRegistry>()`:

```kotlin
        catalogService = CampsiteCatalogService(DbRefResolver(RefLinkRepo(ctx)), campsitesRepo, targets, identities, tenants),
```

- [ ] **Step 7: Retire the companion-config test**

```bash
git rm backend/src/test/kotlin/ca/floo/roadtrip/di/ShippingProfileCompanionConfigTest.kt
```

Its premise is gone: the drawer's rec.gov identity no longer depends on `BookingAdapterRegistry` holding the rec.gov adapter, so a profile without `companion-base-url` can no longer drop a CTA.

- [ ] **Step 8: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A backend/src
git commit -m "$(cat <<'EOF'
refactor(booking): one booking-identity rule for the drawer and the campsite rows

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: CTA labels and info-link copy come from the registry

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/campground/CampgroundCta.kt`, `.../ExternalInfoLinkLabels.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovBookingDisplay.kt`, `.../CampflareBookingDisplay.kt`, `.../AspiraBookingDisplay.kt`, `.../ReserveAmericaBookingDisplay.kt`, `.../ReserveCaliforniaBookingDisplay.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/campground/CampgroundCtaTest.kt`

**Interfaces:**
- Consumes: `TenantRegistry.displayName(ref)`, `ctaLabel(ref)`, `linkLabel(host)`, `tenant(provider, code)`, `tenantByHost(host)` (Task 1); `PoiDateContext(timeZone, earliestDate)` (`model/availability/PoiDateContext.kt`); `RecGovBookingUrl.campground(facilityId)`, `AspiraBookingUrl.template(host, transactionLocationId, mapId, resourceLocationId)`, `ReserveCaliforniaBookingUrl.park(placeId)`, `ReservationUrlTemplate.fill(template, start, end)`.
- Produces:
  - `internal class CampgroundCta(tenants: TenantRegistry)` with `fun bookingSystem(bookingRef: BookingProviderRef?): String?` and `fun computeCtas(bookingRef: BookingProviderRef?, reserveUrl: String?, infoUrl: String?, dateContext: PoiDateContext): List<PoiCtaSchema>` — no `Clock`, no `companion object default`
  - `internal class ExternalInfoLinkLabels(tenants: TenantRegistry)` with `fun forUrl(url: String): String`

- [ ] **Step 1: Rewrite `CampgroundCtaTest` against the registry**

Replace the file's clock-based construction and its label assertions:

```kotlin
package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampgroundCtaTest {
    private val cta = CampgroundCta(shippedTenantRegistry())

    // A day where Pacific and Eastern disagree: 2026-06-17 in America/Vancouver
    // while America/New_York has already rolled to the 18th. The old EST anchor
    // dated every tenant's deeplink off the wrong day for exactly this case.
    private val pacificContext =
        PoiDateContext(timeZone = ZoneId.of("America/Vancouver"), earliestDate = LocalDate.parse("2026-06-17"))

    @Test
    fun `bookingSystem names the tenant, not the vendor`() {
        assertEquals("Parks Canada", cta.bookingSystem(parksCanadaRef))
        assertEquals("BC Parks", cta.bookingSystem(bcParksRef))
        assertEquals("Washington State Parks", cta.bookingSystem(washingtonRef))
        assertEquals("Recreation.gov", cta.bookingSystem(BookingProviderRef.RecGov(facilityId = "232450")))
        assertEquals("Campflare", cta.bookingSystem(BookingProviderRef.Campflare(campgroundId = "9")))
        assertEquals("Alberta Parks", cta.bookingSystem(BookingProviderRef.ReserveAmerica("ABPP", "10")))
        assertEquals("New York State Parks", cta.bookingSystem(BookingProviderRef.ReserveAmerica("NY", "10")))
        assertEquals(
            "ReserveCalifornia",
            cta.bookingSystem(BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901))),
        )
        assertEquals("Aspira NextGen", cta.bookingSystem(unknownTenantRef))
        assertNull(cta.bookingSystem(null))
    }

    @Test
    fun `recgov keeps a stored recreation_gov URL`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out.url)
        assertEquals("Reserve on Recreation.gov", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `recgov rebuilds the URL when the stored one is a foreign host`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = "https://campflare.com/campgrounds/232450",
                    infoUrl = null,
                    dateContext = pacificContext,
                ).first()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out.url)
        assertEquals("Reserve on Recreation.gov", out.label)
    }

    @Test
    fun `aspira deeplink is dated from the POI's own date context`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = bcParksRef,
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertNotNull(out.url)
        assertTrue(out.url.startsWith("https://camping.bcparks.ca/create-booking/results?"), out.url)
        assertTrue(out.url.contains("startDate=2026-06-17"), out.url)
        assertTrue(out.url.contains("endDate=2026-06-18"), out.url)
        assertEquals("Reserve on BC Parks", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `campflare ref gets the vendor's view link`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.Campflare(campgroundId = "9"),
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("View on Campflare", out.label)
        assertEquals("info", out.kind)
    }

    @Test
    fun `reservecalifornia ref produces the park deeplink`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901)),
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://reservecalifornia.com/park/660", out.url)
        assertEquals("Reserve on ReserveCalifornia", out.label)
    }

    @Test
    fun `an agency info URL keeps its agency label`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = null,
                    infoUrl = "https://www.fs.usda.gov/recarea/1",
                    dateContext = pacificContext,
                ).single()
        assertEquals("Park info on fs.usda.gov", out.label)
        assertEquals("info", out.kind)
    }

    private companion object {
        val parksCanadaRef =
            BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = 4189, mapId = -2147483361, resourceLocationId = null)
        val bcParksRef =
            BookingProviderRef.Aspira(tenant = "bc", transactionLocationId = 1, mapId = -2147483470, resourceLocationId = null)
        val washingtonRef =
            BookingProviderRef.Aspira(tenant = "wa", transactionLocationId = 2, mapId = -2147483600, resourceLocationId = null)
        val unknownTenantRef =
            BookingProviderRef.Aspira(tenant = "zz", transactionLocationId = 3, mapId = -2147483601, resourceLocationId = null)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:test --offline -q --tests '*CampgroundCtaTest'`
Expected: FAIL — `CampgroundCta` still takes a `Clock` and `computeCtas` has no `dateContext`.

- [ ] **Step 3: Put `ExternalInfoLinkLabels` on the registry**

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/campground/ExternalInfoLinkLabels.kt` with:

```kotlin
package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.metadata.registry.TenantRegistry

private const val UNNAMED_LINK_LABEL = "Visit website"

/**
 * The label for an external link on a pin.
 *
 * A host a booking vendor runs is named by the registry, so a stored
 * recreation.gov or campflare.com URL reads the vendor's registered name.
 * The rest are agencies — who manages the park, not where you book — and stay
 * a table here.
 */
internal class ExternalInfoLinkLabels(
    private val tenants: TenantRegistry,
) {
    fun forUrl(url: String): String {
        val host = UrlHosts.extract(url) ?: return UNNAMED_LINK_LABEL
        tenants.linkLabel(host)?.let { return it }
        return when {
            host.endsWith("fs.usda.gov") -> "Park info on fs.usda.gov"
            host.endsWith("nps.gov") -> "Park info on nps.gov"
            host.endsWith("blm.gov") -> "Park info on blm.gov"
            host.endsWith("fws.gov") -> "Park info on fws.gov"
            host.endsWith("usace.army.mil") -> "Park info on usace.army.mil"
            host.endsWith("usbr.gov") -> "Park info on usbr.gov"
            host.endsWith("tva.gov") -> "Park info on tva.gov"
            host.endsWith("bcparks.ca") -> "Park info on bcparks.ca"
            host.endsWith("albertaparks.ca") -> "Park info on albertaparks.ca"
            host.endsWith("pc.gc.ca") || host.endsWith("parks.canada.ca") -> "Park info on parks.canada.ca"
            host.endsWith("planetfitness.com") -> "Visit planetfitness.com"
            host.endsWith("tesla.com") -> "View on tesla.com"
            else -> "Visit $host"
        }
    }
}
```

The `recreation.gov` and `campflare.com` branches are gone: the registry answers those now.

- [ ] **Step 4: Rewrite `CampgroundCta`**

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/campground/CampgroundCta.kt` with:

```kotlin
package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.api.poi.PoiCtaSchema
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.service.availability.provider.AspiraBookingUrl
import ca.floo.roadtrip.service.availability.provider.RecGovBookingUrl
import ca.floo.roadtrip.service.availability.provider.ReservationUrlTemplate
import ca.floo.roadtrip.service.availability.provider.ReserveCaliforniaBookingUrl
import ca.floo.roadtrip.service.etl.vendors.campflare.CampflareUrls

// Backend-computed actions for a POI pin. The drawer reads {url, label, kind}
// verbatim — the FE owns no per-vendor precedence, URL construction, or copy.
private const val INFO_CTA_KIND = "info"
private const val RESERVE_CTA_KIND = "reserve"

/** A deeplink offers one night: arrival and the next morning's checkout. */
private const val DEEPLINK_NIGHTS = 1L

internal class CampgroundCta(
    private val tenants: TenantRegistry,
) {
    private val infoLinkLabels = ExternalInfoLinkLabels(tenants)

    private val providers: List<CampgroundCtaProvider> =
        listOf(
            RecGovCampgroundCtaProvider(tenants),
            AspiraCampgroundCtaProvider(tenants),
            ReserveCaliforniaCampgroundCtaProvider(tenants),
        )

    /** The booking site this pin's reservations flow through, as a person reads it. */
    fun bookingSystem(bookingRef: BookingProviderRef?): String? = bookingRef?.let(tenants::displayName)

    fun computeCtas(
        bookingRef: BookingProviderRef?,
        reserveUrl: String?,
        infoUrl: String?,
        dateContext: PoiDateContext,
    ): List<PoiCtaSchema> {
        val upstreamUrl = providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl)
        val primaryCta =
            providers.firstNotNullOfOrNull { it.reserveCta(bookingRef, upstreamUrl, dateContext) }
                ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
                    PoiCtaSchema(url = it, label = infoLinkLabels.forUrl(it), kind = INFO_CTA_KIND)
                }
        return listOfNotNull(primaryCta, campflareCta(bookingRef)).distinctBy { it.url }
    }

    /**
     * Campflare sells nothing itself, so its public page is appended for every
     * Campflare ref rather than offered as a reserve CTA. A row the drawer
     * hands over as Campflare arrives here; an aliased row sold on rec.gov
     * arrives as a rec.gov ref instead.
     */
    private fun campflareCta(providerRef: BookingProviderRef?): PoiCtaSchema? {
        val campflare = providerRef as? BookingProviderRef.Campflare ?: return null
        return PoiCtaSchema(
            url = CampflareUrls.campground(campflare.campgroundId),
            label = tenants.ctaLabel(campflare),
            kind = INFO_CTA_KIND,
        )
    }

    private fun providerUrl(
        reserveUrl: String?,
        infoUrl: String?,
    ): String? =
        reserveUrl
            ?.takeIf { it.isNotBlank() }
            ?: infoUrl?.takeIf { it.isNotBlank() }
}

private interface CampgroundCtaProvider {
    fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema?
}

/**
 * The stored URL is kept only when a rec.gov tenant runs its host; otherwise
 * the link is rebuilt from the facility id. An aliased Campflare row carries a
 * campflare.com `reservation_url`, and labelling that "Reserve on
 * Recreation.gov" sent people to the wrong site.
 */
private class RecGovCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val recGov = providerRef as? BookingProviderRef.RecGov ?: return null
        val stored = upstreamUrl?.takeIf { it.isNotBlank() && isRecGovTenantHost(it) }
        return reserveCta(
            url = stored ?: RecGovBookingUrl.campground(recGov.facilityId),
            label = tenants.ctaLabel(recGov),
        )
    }

    private fun isRecGovTenantHost(url: String): Boolean =
        UrlHosts.extract(url)?.let { tenants.tenantByHost(it)?.provider } == BookingProvider.RECGOV
}

private class AspiraCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val aspira = providerRef as? BookingProviderRef.Aspira ?: return null
        // The tenant's own host, so the link never depends on what a row
        // happened to store; an unregistered tenant falls back to that URL.
        val host =
            tenants.tenant(BookingProvider.ASPIRA, aspira.tenant)?.host
                ?: upstreamUrl?.let(UrlHosts::extract)
                ?: return null
        val arrival = dateContext.earliestDate
        val template =
            AspiraBookingUrl.template(host, aspira.transactionLocationId, aspira.mapId, aspira.resourceLocationId)
        return reserveCta(
            url = ReservationUrlTemplate.fill(template, arrival, arrival.plusDays(DEEPLINK_NIGHTS)),
            label = tenants.ctaLabel(aspira),
        )
    }
}

private class ReserveCaliforniaCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val reserveCalifornia = providerRef as? BookingProviderRef.ReserveCalifornia ?: return null
        return reserveCta(
            url = ReserveCaliforniaBookingUrl.park(reserveCalifornia.placeId),
            label = tenants.ctaLabel(reserveCalifornia),
        )
    }
}

private fun reserveCta(
    url: String,
    label: String,
): PoiCtaSchema =
    PoiCtaSchema(
        url = url,
        label = label,
        kind = RESERVE_CTA_KIND,
    )
```

The per-provider `bookingSystem` overrides are gone: every one of them answered `registry.displayName(ref)`, which is one line on `CampgroundCta`. The ReserveAmerica and Campflare CTA providers are gone with them — neither ever produced a reserve CTA, and ReserveAmerica's info link is now labelled by the registry through `ExternalInfoLinkLabels`.

- [ ] **Step 5: Delete the display objects**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovBookingDisplay.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/CampflareBookingDisplay.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraBookingDisplay.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/ReserveAmericaBookingDisplay.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/ReserveCaliforniaBookingDisplay.kt
```

`RecGovBookingAdapter.kt` still imports `RecGovBookingDisplay` for its `displayName`; that property and its import are removed in Task 5. To keep this task's gate green, replace that one line now with the literal it already resolved to and leave a note for Task 5:

```kotlin
    override val displayName: String = "Recreation.gov"
```

- [ ] **Step 6: Pass the date context through**

In `CampgroundService.kt`:

```kotlin
        val computedCtas =
            cta.computeCtas(
                bookingRef = bookingRef,
                reserveUrl = campground.reservationUrl,
                infoUrl = infoUrl,
                dateContext = dateContext,
            )
```

and the footer name:

```kotlin
                    bookingSystem = cta.bookingSystem(bookingRef),
```

In `WatchAlertDispatcher.kt`, the one call site narrows to the ref:

```kotlin
                        bookingSystem = campgroundCta.bookingSystem(target?.parentRef),
```

- [ ] **Step 7: Wire `CampgroundCta` through DI**

In `ServiceModule.kt`, add `single { CampgroundCta(get<TenantRegistry>()) }`, change the Task 3 placeholder to `cta = get<CampgroundCta>()`, and add `campgroundCta = get<CampgroundCta>()` to the `WatchAlertDispatcher` construction (its parameter no longer has a default).

In `WatchAlertDispatcher.kt`, change `private val campgroundCta: CampgroundCta = CampgroundCta.default,` to `private val campgroundCta: CampgroundCta,`.

- [ ] **Step 8: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. Any test that still constructs `CampgroundCta(clock = …)` or `CampgroundCta.default` takes `CampgroundCta(shippedTenantRegistry())`.

- [ ] **Step 9: Commit**

```bash
git add -A backend/src
git commit -m "$(cat <<'EOF'
refactor(display): CTA labels and booking-site names come from the registry

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: The port stops naming its vendor; the wire carries the name

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingAdapter.kt`, `.../RecGovBookingAdapter.kt`, `.../BookingActionService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AtcTriggerActionHandler.kt`, `.../WatchCapabilityService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BookingActionDto.kt`, `.../ApiErrorSchema.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteResponses.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/BookingRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/fixtures/FakeBookingAdapterFixture.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BookingRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingAdapterTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchCapabilityServiceTest.kt`, `.../TriggerActionHandlerTest.kt`

**Interfaces:**
- Consumes: `TenantRegistry.displayName(ref: BookingProviderRef): String` (Task 1); `BookingTarget(providerId, parentRef, campsiteId, vendorSiteId)`.
- Produces:
  - `internal interface BookingAdapter` with **no** `displayName`
  - `AddToCartOutcome.Held(cartUrl: String, provider: BookingProvider, providerDisplay: String)`, `AddToCartOutcome.Refused(code: String, provider: BookingProvider? = null, providerDisplay: String? = null)`, `AddToCartOutcome.Failed(code: String, detail: String?, category: BookingFailureCategory, provider: BookingProvider, providerDisplay: String)`
  - `internal class BookingActionService(campsites, availabilityTargets, bookingTargets, availability, bookings, tenants: TenantRegistry)`
  - `AddToCartResponseDto(status, cartUrl, provider, providerDisplay)` serialized as `provider_display`; `ApiErrorSchema(error, detail, provider, providerDisplay)` likewise
  - `suspend fun ApplicationCall.respondApiError(error: String, status: HttpStatusCode, detail: String? = null, provider: String? = null, providerDisplay: String? = null)`
  - `internal class AtcTriggerActionHandler(bookings, bookingTargets, notifications, targetResolver, tenants: TenantRegistry, metrics)`
  - `internal class WatchCapabilityService(availabilityTargets, bookingTargets, notificationTriggerKinds, bookings, tenants: TenantRegistry)`

- [ ] **Step 1: Write the failing wire tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BookingRoutesTest.kt`:

```kotlin
    @Test
    fun `a hold names the booking site beside the provider slug`() {
        val body = Json.parseToJsonElement(post(validRequest).bodyAsText()).jsonObject
        assertEquals("recgov", body["provider"]?.jsonPrimitive?.content)
        assertEquals("Recreation.gov", body["provider_display"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a refusal names the booking site beside the provider slug`() {
        val response = post(validRequest, adapter = fakeAdapterWithoutCredentials)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("recgov", body["provider"]?.jsonPrimitive?.content)
        assertEquals("Recreation.gov", body["provider_display"]?.jsonPrimitive?.content)
    }
```

Use the file's existing request/adapter helpers and its rec.gov-ref campsite fixture rather than new ones.

In `WatchCapabilityServiceTest.kt`, change the two `displayName = OTHER_PROVIDER_DISPLAY_NAME` / `FAKE_PROVIDER_DISPLAY_NAME` expectations to the registry names for the providers those fakes claim (`"Recreation.gov"` for `RECGOV`, `"Campflare"` for `CAMPFLARE`) and delete the `displayName = …` arguments to `FakeBookingAdapter`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --offline -q --tests '*BookingRoutesTest' --tests '*WatchCapabilityServiceTest'`
Expected: FAIL — `provider_display` is not on either DTO.

- [ ] **Step 3: Take `displayName` off the port**

In `BookingAdapter.kt`, delete:

```kotlin
    /** This vendor as a person reads it, for copy that has to name it. */
    val displayName: String
```

and update the interface KDoc's "and the name a person reads" clause to "and its codes" — the name is the registry's now.

In `RecGovBookingAdapter.kt`, delete the `displayName` override added in Task 4 Step 5. In `FakeBookingAdapterFixture.kt`, delete the `override val displayName` parameter and the `FAKE_PROVIDER_DISPLAY_NAME` constant, plus every `displayName = …` argument at its call sites (`WatchCapabilityServiceTest`, `TriggerActionHandlerTest`'s inline fake, `OTHER_PROVIDER_DISPLAY_NAME`). In `RecGovBookingAdapterTest.kt`, delete the `assertEquals("Recreation.gov", provider().displayName)` assertion — `TenantRegistryTest` pins that name now.

- [ ] **Step 4: Carry the display name on the outcome**

In `BookingActionService.kt`:

```kotlin
internal sealed interface AddToCartOutcome {
    /** The vendor slug and the name a person reads, both from the target's ref. */
    data class Held(
        val cartUrl: String,
        val provider: BookingProvider,
        val providerDisplay: String,
    ) : AddToCartOutcome

    data class Refused(
        val code: String,
        val provider: BookingProvider? = null,
        val providerDisplay: String? = null,
    ) : AddToCartOutcome

    data class Failed(
        val code: String,
        val detail: String?,
        val category: BookingFailureCategory,
        val provider: BookingProvider,
        val providerDisplay: String,
    ) : AddToCartOutcome
}
```

```kotlin
internal class BookingActionService(
    private val campsites: BookingCampsiteLookup,
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val availability: CurrentAvailabilityLookup,
    private val bookings: BookingAdapterRegistry,
    private val tenants: TenantRegistry,
) : BookingActionPort {
```

After the adapter gate, resolve the name once and pass it into every later outcome:

```kotlin
        val adapter = bookings.adapterFor(target) ?: return AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET)
        // The booking site's own name, from the ref the hold will use.
        val providerDisplay = tenants.displayName(target.parentRef)

        if (!adapter.canFulfil(caller)) {
            return AddToCartOutcome.Refused(BookingActionCodes.CREDENTIALS_REQUIRED, adapter.id, providerDisplay)
        }

        if (knownTaken(campsiteId, startDate, endDate)) {
            return AddToCartOutcome.Refused(BookingActionCodes.NOT_AVAILABLE, adapter.id, providerDisplay)
        }
```

and in the `when (val result = adapter.addToCart(request))`:

```kotlin
                AddToCartOutcome.Held(result.cartUrl, result.providerId, providerDisplay)
```

```kotlin
                AddToCartOutcome.Failed(result.error, result.detail, result.category, result.providerId, providerDisplay)
```

```kotlin
            AddToCartResult.Unsupported ->
                AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET, adapter.id, providerDisplay)
```

Add `import ca.floo.roadtrip.model.metadata.registry.TenantRegistry`. The three refusals before a target exists keep the single-argument `Refused(...)` — there is no vendor to name yet, and guessing one is what this phase removes.

- [ ] **Step 5: Put `provider_display` on the wire**

In `BookingActionDto.kt`:

```kotlin
    /** Whose cart it is: an aliased campground is served by one vendor and booked through another. */
    val provider: String,
    /** The same vendor as a person reads it, from the tenant registry. */
    @SerialName("provider_display") val providerDisplay: String,
)
```

In `ApiErrorSchema.kt`:

```kotlin
@Serializable
data class ApiErrorSchema(
    val error: String,
    val detail: String? = null,
    /** The booking adapter that refused, where one was reached. Absent otherwise. */
    val provider: String? = null,
    /** That adapter's booking site as a person reads it. Absent with [provider]. */
    @SerialName("provider_display") val providerDisplay: String? = null,
)
```

Add `import kotlinx.serialization.SerialName` to `ApiErrorSchema.kt`.

In `RouteResponses.kt`:

```kotlin
internal suspend fun ApplicationCall.respondApiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
    provider: String? = null,
    providerDisplay: String? = null,
) {
    respondEncodedJson(
        ApiErrorSchema(error = error, detail = detail, provider = provider, providerDisplay = providerDisplay),
        status,
    )
}
```

In `BookingRoutes.kt`, `respondOutcome` repeats what the service said:

```kotlin
private suspend fun ApplicationCall.respondOutcome(outcome: AddToCartOutcome) =
    when (outcome) {
        is AddToCartOutcome.Held ->
            respondEncodedJson(
                AddToCartResponseDto(
                    status = BookingActionStatus.COMPLETED,
                    cartUrl = outcome.cartUrl,
                    provider = outcome.provider.id,
                    providerDisplay = outcome.providerDisplay,
                ),
            )
        is AddToCartOutcome.Refused ->
            respondApiError(
                error = outcome.code,
                status = refusalStatus(outcome.code),
                provider = outcome.provider?.id,
                providerDisplay = outcome.providerDisplay,
            )
        is AddToCartOutcome.Failed ->
            respondApiError(
                error = outcome.code,
                status = outcome.category.status(),
                detail = outcome.detail,
                provider = outcome.provider.id,
                providerDisplay = outcome.providerDisplay,
            )
    }
```

- [ ] **Step 6: Name the vendor from the registry in the two capability paths**

In `AtcTriggerActionHandler.kt`, add the constructor parameter and replace the adapter lookup:

```kotlin
internal class AtcTriggerActionHandler(
    private val bookings: BookingAdapterRegistry,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notifications: NotificationSender,
    private val targetResolver: WatchNotificationTargetResolver,
    private val tenants: TenantRegistry,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
) : TriggerActionHandler {
```

```kotlin
        // What the delivered report calls this booking site. The ref names the
        // tenant, so the report reads the same name the drawer and the alert do.
        val bookingSystem = tenants.displayName(nextTarget.parentRef)
```

In `WatchCapabilityService.kt`, keep the target beside the adapter so the name can come from its ref:

```kotlin
    internal fun canFulfilAddToCart(
        requester: UserId?,
        scope: ResolvedWatchScope,
    ): Boolean {
        val user = requester ?: return false
        val claims = addToCartClaims(scope)
        return claims.isNotEmpty() && claims.all { (_, adapter) -> adapter.canFulfil(user) }
    }
```

```kotlin
    internal fun addToCartProviderName(
        owner: UserId,
        scope: ResolvedWatchScope,
    ): String? = addToCartClaim(owner, scope)?.let { (target, _) -> tenants.displayName(target.parentRef) }

    private fun addToCartClaim(
        requester: UserId?,
        scope: ResolvedWatchScope,
    ): Pair<BookingTarget, BookingAdapter>? {
        val claims = addToCartClaims(scope)
        val user = requester ?: return claims.firstOrNull()
        return claims.firstOrNull { (_, adapter) -> !adapter.canFulfil(user) } ?: claims.firstOrNull()
    }

    /** The targets that would hold this scope's sites, one per claiming adapter. */
    private fun addToCartClaims(scope: ResolvedWatchScope): List<Pair<BookingTarget, BookingAdapter>> =
        scope.targets
            .mapNotNull { resolved -> resolved?.let { bookingTargets.targetFor(BookingAction.ADD_TO_CART, it) } }
            .mapNotNull { target -> bookings.adapterFor(target)?.let { target to it } }
            .distinctBy { (_, adapter) -> adapter.id }
```

and in `capabilitiesFor`:

```kotlin
        val claim = if (state == AddToCartState.UNSUPPORTED) null else addToCartClaim(requester, scope)
        return AvailabilityWatchCapabilitiesDto(
            triggerKinds = supportedTriggerKinds(scope, bookingActions, requester),
            addToCart =
                AddToCartCapabilityDto(
                    state = state,
                    provider = claim?.second?.id?.id,
                    providerDisplay = claim?.let { (target, _) -> tenants.displayName(target.parentRef) },
                ),
        )
```

Add `tenants: TenantRegistry` to the constructor and the imports `ca.floo.roadtrip.model.booking.BookingTarget` and `ca.floo.roadtrip.model.metadata.registry.TenantRegistry`.

- [ ] **Step 7: Rewire DI**

In `ServiceModule.kt`, add `tenants = get<TenantRegistry>()` to the `WatchCapabilityService`, `AtcTriggerActionHandler`, and `BookingActionService` constructions.

- [ ] **Step 8: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. Confirm the port is clean with `grep -rn "displayName" backend/src/main/kotlin/ca/floo/roadtrip/service/booking` returning nothing.

- [ ] **Step 9: Commit**

```bash
git add -A backend/src
git commit -m "$(cat <<'EOF'
refactor(booking): the port stops naming its vendor; the wire carries the name

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: The frontend renders what it is served

**Files:**
- Modify: `frontend/src/features/availability/booking-links.ts`, `.../AvailabilityWeek.tsx`, `.../SiteMatrix.tsx`, `.../SiteDetail.tsx`, `.../site-detail-facts.ts`, `frontend/src/domain/poi/campground-detail.ts`, `frontend/src/api/campsite-api.ts`, `frontend/src/api/booking-api.ts`, `frontend/src/api/http.ts`, `frontend/src/lib/strings.ts`
- Test: `frontend/src/features/availability/booking-links.test.ts`, `.../AvailabilityWeek.test.tsx`, `.../site-detail-facts.test.ts`, `frontend/src/domain/poi/campground-detail.test.ts`, `frontend/src/features/poi/CampgroundPanel.test.tsx`

**Interfaces:**
- Consumes: `CampsiteDto.booking_system` (Task 3), `AddToCartResponseDto.provider_display` and `ApiErrorSchema.provider_display` (Task 5).
- Produces:
  - `Campsite.booking_system?: string | null` in `frontend/src/api/campsite-api.ts`
  - `AddToCartResponse.provider_display: string` and `AddToCartFailure.provider_display?: string` in `frontend/src/api/booking-api.ts`; `HttpError.providerDisplay?: string`
  - `bookingLabel(row: Partial<Campsite> | null | undefined): string` — one argument, no templates
  - `bookingCopy.reserve: 'Reserve'` in `frontend/src/lib/strings.ts`
  - `booking-links.ts` exports exactly `reservationUrlFromTemplate`, `hasReservationUrlTemplate`, `bookingLabel`, `type ReservationUrlTemplates`, `type StayWindow`

- [ ] **Step 1: Write the failing tests**

In `frontend/src/features/availability/booking-links.test.ts`, delete every `agencyLabel` / `providerLabel` / `knownProviderLabel` case and keep only the template cases, plus:

```ts
describe('bookingLabel', () => {
  it('names the site the backend served', () => {
    expect(bookingLabel({ id: 1, booking_system: 'BC Parks' })).toBe('Book on BC Parks');
  });

  it('falls back to the neutral verb when nothing was served', () => {
    expect(bookingLabel({ id: 1 })).toBe('Book');
    expect(bookingLabel({ id: 1, booking_system: '' })).toBe('Book');
    expect(bookingLabel(null)).toBe('Book');
  });
});
```

In `frontend/src/features/availability/AvailabilityWeek.test.tsx`, change the two toast assertions to read the envelope:

```tsx
  it('names the holding site from provider_display', async () => {
    mockAddToCart.mockResolvedValue({
      status: 'completed',
      cart_url: 'https://cart.example.test/1',
      provider: 'recgov',
      provider_display: 'Recreation.gov',
    });
    // …arm and fire the cell as the surrounding tests do…
    expect(await screen.findByText('Site held in your Recreation.gov cart')).toBeInTheDocument();
  });

  it('names the refusing site from the envelope', async () => {
    const err = Object.assign(new Error('boom'), {
      code: 'credentials_required',
      provider: 'recgov',
      provider_display: 'Recreation.gov',
    });
    mockAddToCart.mockRejectedValue(err);
    // …arm and fire…
    expect(await screen.findByText(/Recreation\.gov/)).toBeInTheDocument();
  });

  it('reads neutral when the envelope names no site', async () => {
    mockAddToCart.mockResolvedValue({
      status: 'completed',
      cart_url: 'https://cart.example.test/1',
      provider: 'recgov',
      provider_display: '',
    });
    // …arm and fire…
    expect(await screen.findByText('Site held in your cart')).toBeInTheDocument();
  });
```

In `frontend/src/features/availability/site-detail-facts.test.ts`, replace the `Provider` expectation:

```ts
  it('labels the booking site from what was served', () => {
    const facts = detailFacts({ data_provider: 'aspira', data_provider_ref: 'bc:42', booking_system: 'BC Parks' });
    expect(facts).toContainEqual({ label: 'Booking site', value: 'BC Parks' });
    expect(facts).toContainEqual({ label: 'Provider ID', value: 'bc:42' });
    expect(facts.find((f) => f.label === 'Provider')).toBeUndefined();
  });
```

In `frontend/src/domain/poi/campground-detail.test.ts`, change the bare-`reserve_url` fallback case:

```ts
  it('labels a bare reserve_url neutrally; the backend labels the real ones', () => {
    const [cta] = campgroundCtas({ reserve_url: 'https://www.recreation.gov/camping/campgrounds/1' });
    expect(cta.label).toBe('Reserve');
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npm test -- booking-links site-detail-facts campground-detail AvailabilityWeek`
Expected: FAIL — `bookingLabel` still takes two arguments and `reserveLabel` still names hosts.

- [ ] **Step 3: Shrink `booking-links.ts` to templates plus one label**

Delete `AGENCY_BY_HOST`, `AGENCY_BY_VENDOR`, `agencyLabel`, `providerLabel`, `knownProviderLabel`, `labelFromHost`, `humanizeAgency`, `hostFromUrl`, and the `VENDOR` import. Replace `bookingLabel` with:

```ts
/**
 * "Book on <whoever takes the booking>", or plain "Book".
 *
 * The name is the backend's `booking_system` for this row — the same resolver
 * the drawer uses. The frontend no longer guesses one from a URL host or a
 * vendor slug, which is how `aspira` used to read "Aspira" here and
 * "Aspira NextGen" one screen over.
 */
export function bookingLabel(row: Partial<Campsite> | null | undefined): string {
  const site = String(row?.booking_system || '').trim();
  return site ? `Book on ${site}` : bookingCopy.book;
}
```

Keep `reservationUrlFromTemplate`, `hasReservationUrlTemplate`, the private `reservationUrlTemplate`/`templateForRow`/`hasTemplatePlaceholders`/`nightsBetween` helpers, `TEMPLATE_PLACEHOLDERS`, `MS_PER_DAY`, and the two exported types unchanged.

- [ ] **Step 4: Update the callers**

`frontend/src/api/campsite-api.ts` — add the field after `booking_provider`:

```ts
  booking_provider?: string | null;
  /** The booking site this row opens, as the backend names it. */
  booking_system?: string | null;
```

`frontend/src/features/availability/SiteDetail.tsx` — `{bookingLabel(site)}`.

`frontend/src/features/availability/SiteMatrix.tsx` — replace the `agencyLabel` import with nothing (keep `hasReservationUrlTemplate` and the type) and pass the served name:

```tsx
          bookingAgency={row.booking_system || undefined}
```

`frontend/src/features/availability/site-detail-facts.ts` — drop the `providerLabel` import and swap the fact:

```ts
  add('Booking site', site.booking_system);
  add('Provider ID', site.data_provider_ref);
```

`frontend/src/api/http.ts` — carry the new envelope field:

```ts
  /** The booking adapter that refused, when the envelope named one. */
  provider?: string;
  /** That adapter's booking site as a person reads it. */
  providerDisplay?: string;
```

```ts
    if (body && typeof body.provider === 'string') err.provider = body.provider;
    if (body && typeof body.provider_display === 'string') err.providerDisplay = body.provider_display;
```

`frontend/src/api/booking-api.ts`:

```ts
export interface AddToCartResponse {
  status: 'completed';
  cart_url: string;
  provider: string;
  /** The booking site as a person reads it — what the toasts name. */
  provider_display: string;
}

export interface AddToCartFailure {
  code?: string;
  provider?: string;
  provider_display?: string;
}

export function addToCartFailure(err: unknown): AddToCartFailure {
  const carried = err as { code?: string; provider?: string; providerDisplay?: string; provider_display?: string } | null | undefined;
  return {
    code: carried?.code,
    provider: carried?.provider,
    provider_display: carried?.providerDisplay ?? carried?.provider_display,
  };
}
```

`frontend/src/features/availability/AvailabilityWeek.tsx` — delete `holdProviderName`, `refusingProviderName`, `sameVendor` and the `knownProviderLabel`/`providerLabel` import (keep `reservationUrlFromTemplate`), then read the envelope directly:

```tsx
          const holder = answer.provider_display || undefined;
```

```tsx
          const { code, provider_display: providerDisplay } = addToCartFailure(err);
          actions.cartActionChanged({ type: 'failed', cell, code: code ?? '' });
          toast({
            status: 'warning',
            title: 'Could not hold the site',
            children: settingsErrorMessage(code, providerDisplay || undefined),
          });
```

`bookingSystem` is now unused inside `holdSite`; drop it from the callback's dependency array if nothing else in the callback reads it, and delete the `const bookingSystem = …` line at ~96 if no other call site uses it.

`frontend/src/domain/poi/campground-detail.ts` — delete `reserveLabel` and the `VENDOR` import, and use the neutral verb:

```ts
  if (reserveUrl) return [{ url: reserveUrl, label: bookingCopy.reserve, variant: 'primary' }];
```

importing `bookingCopy` from `@/lib/strings`. The backend always labels a reservation URL (registry name when the host is a tenant, else `ExternalInfoLinkLabels`), so this branch only runs for pins the backend could not resolve at all.

`frontend/src/lib/strings.ts` — add to `bookingCopy`, beside `book`:

```ts
  /** The drawer's fallback when a pin carries a bare reserve URL and no label. */
  reserve: 'Reserve',
```

`VENDOR` stays exactly as it is: the rec.gov account panel and `settings-errors.ts` are rec.gov's own surface.

- [ ] **Step 5: Run the frontend gate**

Run: `cd frontend && npm run typecheck && npm test && npm run lint`
Expected: PASS. `CampgroundPanel.test.tsx` may need its fixture's `reserve_url` case updated to the neutral label.

- [ ] **Step 6: Run the backend gate too**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS (unchanged; run it so the commit is known-green on both sides).

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src
git commit -m "$(cat <<'EOF'
refactor(frontend): render the booking-site name the backend serves

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Docs

**Files:**
- Modify: `docs/reservation-providers.md`, `docs/reservation-providers/aspira.md`, `docs/reservation-providers/reserveamerica.md`, `docs/backend-architecture.md`, `DATA_SOURCES.md`, `docs/adding-a-reservation-provider.md`, `docs/adding-a-data-source.md`, `docs/glossary.md`

- [ ] **Step 1: `docs/reservation-providers.md`**

Three edits.

Fix the adapter matrix row that names a tenant we do not run — line ~603 reads "Aspira NextGen (BC Parks, Washington, Pennsylvania)". Replace `Pennsylvania` with `Parks Canada`, matching the three registry tenants (`bc`, `wa`, `pc`).

In the booking-seam section (~273-340), add after the `BookingAdapter` paragraph:

> **Vendor names are registry data, not adapter code.** `booking_providers` in
> `backend/src/main/resources/poi-registry.yaml` declares each vendor's display
> name, whether it `sells`, and its tenants (`code`, `host`, `display_name`).
> `TenantRegistry` projects that section and answers `displayName(ref)`,
> `ctaLabel(ref)` (`"Reserve on <name>"` when the vendor sells, `"View on
> <name>"` when it does not), `linkLabel(host)`, and `tenantsOf(provider)`.
> `BookingAdapter` has no `displayName`: `AtcTriggerActionHandler`,
> `WatchCapabilityService`, `BookingActionService`, and `CampgroundCta` all ask
> the registry, keyed by the target's `parentRef`, so a tenant is named the same
> everywhere. `booking_system` reads the tenant (`BC Parks`), not the platform
> (`Aspira NextGen`); a ref whose tenant the registry does not know reads the
> vendor name.
>
> **Which identity a row books through** is `BookingIdentityResolver`: the first
> of (primary, aliases in order) whose vendor `sells`, else the serving
> availability provider's claim, else the declared primary. It does not consult
> `BookingAdapterRegistry`, so a pin renders identically in a process without
> the ATC companion.

In "adding a provider" (~782-824), change step ordering so a registry row comes first:

> 1. Add a `booking_providers` row: `id` (the `BookingProvider` member), a
>    `display_name`, `sells`, and one `tenants` entry per host. The boot
>    validator requires exactly one row per member, unique tenant codes per
>    vendor, unique hosts across the section, and that every `poi_data` /
>    `campsite_data` ETL row carrying `args.tenant` (Aspira) or `args.contract`
>    (ReserveAmerica) names a real tenant whose host matches `args.host`.
> 2. Add the adapter in `service/availability/provider/`, taking
>    `List<BookingTenant>` from `TenantRegistry.tenantsOf` when it is
>    multi-tenant. No `*BookingDisplay` object, no per-vendor label constant.

Delete the per-vendor host list that section carried; point at the registry section instead.

- [ ] **Step 2: `docs/reservation-providers/aspira.md` and `reserveamerica.md`**

In `aspira.md` (~7-15, ~246), replace the host/tenant table with:

> Tenants are `booking_providers.aspira.tenants` in
> `backend/src/main/resources/poi-registry.yaml` — `pc` / Parks Canada, `bc` /
> BC Parks, `wa` / Washington State Parks. Adding one is a row there plus the
> `poi_data` / `campsite_data` rows whose `args.tenant` names it; there is no
> Kotlin tenant table any more. The booking horizon stays
> `AspiraAvailabilityProvider.capabilities.bookingHorizonDays` — the registry
> does not restate what the adapter declares. The reserve deeplink is dated from
> the POI's own `PoiDateContext.earliestDate`, not a fixed Eastern anchor.

In `reserveamerica.md` (~13-14, ~26-29), the same shape for `ABPP` / Alberta Parks and `NY` / New York State Parks, and delete any mention of `booking_horizon_days` on an ETL row — that arg is gone from the YAML.

- [ ] **Step 3: `docs/backend-architecture.md`**

In the paragraph at ~270-277, delete "and its display name" from the list of what a booking adapter owns, and add after it:

> `TenantRegistry` sits beside `BookingAdapterRegistry`: it is loaded from the
> `booking_providers` section at boot and is the only place a vendor's or
> tenant's display name and CTA verb live. Services ask it; routes never do —
> the outcome or DTO a service returns already carries the name.

- [ ] **Step 4: `DATA_SOURCES.md`, `docs/adding-a-data-source.md`, `docs/adding-a-reservation-provider.md`, `docs/glossary.md`**

`DATA_SOURCES.md` (~6, ~16): note the fifth registry section and that `args.tenant` / `args.contract` / `args.host` on an ETL row are validated against it at boot; the BC Parks row now carries `tenant: bc` like every other Aspira row, and `booking_horizon_days` is gone.

`docs/adding-a-data-source.md`: in the ETL-args checklist, add "if the source is a tenant of a booking vendor, its `args.tenant` / `args.contract` must name a `booking_providers` tenant and its `args.host` must match that tenant's host".

`docs/adding-a-reservation-provider.md` (steps 4, 5, 7): step 4 becomes the `booking_providers` row; step 5 drops "add a `*BookingDisplay` object"; step 7's CTA-wiring step becomes "nothing — `CampgroundCta` labels from `TenantRegistry.ctaLabel`; add a `CampgroundCtaProvider` only if the vendor needs a constructed deeplink".

`docs/glossary.md`: add

> **Tenant** — one host a booking vendor runs, with its own `code` (as stored in
> `booking_provider_ref` and named in ETL `args`) and the name a person books
> under. Declared in `booking_providers` in `poi-registry.yaml`. Parks Canada,
> BC Parks, and Washington State Parks are three tenants of one vendor, Aspira
> NextGen.

- [ ] **Step 5: Commit**

```bash
git add -A docs DATA_SOURCES.md
git commit -m "$(cat <<'EOF'
docs: the tenant registry and per-tenant booking names

Refs #738

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Manual verification (after Task 7)

The spec's live check, on the local stack with `make data-import` already run:

- [ ] An Aspira BC pin: drawer `booking_system` reads `BC Parks`, the primary CTA reads `Reserve on BC Parks` and opens `camping.bcparks.ca` dated from the pin's own `earliest_date`, and the campsite rows' Book buttons read `Book on BC Parks`.
- [ ] A ReserveAmerica NY pin: `booking_system` reads `New York State Parks`.
- [ ] An aliased Campflare pin: `booking_system` reads `Recreation.gov`, the CTA reads `Reserve on Recreation.gov` and points at `www.recreation.gov`, and a second CTA reads `View on Campflare`.
- [ ] A rec.gov pin: unchanged copy end to end.
- [ ] `make qa`.

---

## Self-review

**Spec coverage.** One registry section → Task 1 (entry classes, `TenantRegistry`, validation, YAML, the deletion of `reserveAmericaSources`/`ReserveAmericaSourceConfig` in Task 2 where the adapters that made them dead are removed). Adapters read the registry → Task 2 (both providers on `List<BookingTenant>`, the `vendorCode` flag becomes `preferResourceRows`, DI's `removePrefix("aspira_")` gone, BC Parks tenant from `args`, `BcParksCampsite.tenant` default gone, `booking_horizon_days` args gone). Display copy from the registry → Task 4 (`CampgroundCta`, the five `*BookingDisplay` deletions, `ExternalInfoLinkLabels`, the rec.gov CTA-URL rule) and Task 5 (`BookingAdapter.displayName` removed, both consumers on the registry). Booking identity follows the registry → Task 3 (`BookingIdentityResolver`, `CampgroundService`, `ShippingProfileCompanionConfigTest` retired). Dates from the POI → Task 4 (`PoiDateContext` threaded, the Eastern anchor, `Clock`, and the TODO deleted). The wire carries names → Task 3 (`CampsiteDto.booking_system`) and Task 5 (`provider_display` on both envelopes). The frontend renders what it is served → Task 6. Docs → Task 7. Every testing bullet in the spec has a step: `PoiRegistryValidatorTest` (1), `TenantRegistryTest` (1), `CampgroundCtaTest` (4), `PoiServiceTest` (3), campsites response (3), `BookingRoutesTest` (5), the four frontend suites (6), the live check above.

**Two facts the spec left implicit, resolved here.** (a) A campsite's reservation URL template comes from the *serving* availability provider (`CampsiteCatalogService.reservationUrlTemplate` → `targets.resolve(campsite).provider`), while `booking_system` comes from the identity resolver — two different objects. Task 3 Step 1 adds a test that an aliased Campflare campsite's template host and its `booking_system` name the same vendor; they do because `CampflareAvailabilityProvider.reservationUrlTemplate` builds its template with `RecGovBookingUrl.templateFromUrl`. (b) `AspiraTenant.vendorCode` was read exactly once, as `campsiteVendor != null` inside `observationsFromAvailability`, and `vendorCode` was non-null for every tenant — so the flag was always true on the campground-level path and absent on the catalog path. Task 2 Step 3 replaces it with `preferResourceRows: Boolean = false`, passed `true` from `availability()`, and Task 2 Step 1 pins that behaviour.

**Type consistency.** `TenantRegistry` method names are used identically in Tasks 2-6: `tenantsOf`, `tenant`, `tenantByHost`, `displayName`, `sells`, `ctaLabel`, `linkLabel`. `BookingTenant(provider, code, host, displayName)` is constructed only in `TenantRegistry.from` and consumed by field name elsewhere. `CampgroundCta.bookingSystem` takes one argument everywhere after Task 4 (`CampgroundService`, `WatchAlertDispatcher`, `CampgroundCtaTest`). `BookingIdentityResolver.forCampground` / `forCampsite` are named the same in Task 3's implementation and in `CampsiteCatalogService`. `providerDisplay` is the Kotlin name and `provider_display` the wire name in every DTO, route, test, and frontend type.

**No migration.** Nothing in these seven tasks adds, edits, or replays a Flyway migration. `V61__booking_alias_indexes.sql` remains the highest.
