# ReserveAmerica Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Catalog the 266 ReserveAmerica POIs (Alberta `alberta-provincial` + NY `new-york-state-parks`) by deriving a site roster from the `campsiteCalendar.do` HTML we already scrape, so they leave the catalogless path and serve real per-site availability.

**Architecture:** A new Python fetcher captures `campsiteCalendar.do` per park into `data/raw/`. A Kotlin catalog parser (sharing row-splitting with the existing availability parser) feeds a per-tenant `ReserveAmericaSitesEtl` that emits `reservables`; a `ReserveAmericaPoiReservableJoiner` links them to POIs by `(contract_code, park_id)`. Site identity is `site:reserveamerica_{contract}:{siteId}` — the same `siteId` the availability adapter emits, so catalog rows bind to availability by construction.

**Tech Stack:** Kotlin (Ktor/jOOQ backend, `SourceEtl` framework), Python 3 fetchers (`_envelope` helpers), Postgres, YAML registry (`config/poi-registry.yaml`).

## Global Constraints

- Branch: `feat/reserveamerica-catalog` (already created off `origin/master`). Never commit to master.
- Backend build uses `jvmToolchain(21)` — **do NOT export `JAVA_HOME`**; Gradle provisions its own JDK. `gradlew` is at repo root.
- Run backend tests: `./gradlew :backend:test --tests "<FQN>" -x :backend:generateJooq`.
- ktlint is a **separate** CI gate: `./gradlew :backend:ktlintCheck -x :backend:generateJooq` (autofix: `:backend:ktlintFormat`). Run it before every commit.
- Reservable identity is dictated by the availability adapter's `rid()` = `"site:reserveamerica_${contractCode.lowercase()}:$siteId"` ([ReserveAmericaReservationProvider.kt:213](../../../backend/src/main/kotlin/ca/floo/roadtrip/service/reservation/adapters/reserveamerica/ReserveAmericaReservationProvider.kt)). The SitesEtl MUST emit `vendor = "reserveamerica_${contract.lowercase()}"` and `vendor_id = siteId` verbatim, or `catalogAvailability` (which binds via `statuses[reservable.vendorId]`) will not match.
- `loop` and `site_type` ship **null** (the calendar `loopName` is a pagination bucket, not a real loop). Do NOT build a brittle attribute scraper. The dead Active developer API is out of scope.
- New DB tables would need adding to `database.includes` in `build.gradle.kts`; this plan adds **none** (`reservables` / `reservable_pois` already exist), so no jOOQ codegen change.

---

### Task 1: Shared row-splitter + `ReserveAmericaCatalogParser`

Extract the per-site row-splitting from `ReserveAmericaAvailabilityParser` into a shared helper, then add a catalog parser that pulls `siteId`, site name, and `parkId` from each row. Pure functions, no DB.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/clients/reserveamerica/ReserveAmericaAvailabilityClient.kt` (the `ReserveAmericaAvailabilityParser` object lives here, ~line 157)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/clients/reserveamerica/ReserveAmericaCatalogParser.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/clients/reserveamerica/ReserveAmericaCatalogParserTest.kt`
- Test (regression): `backend/src/test/kotlin/ca/floo/roadtrip/clients/reserveamerica/ReserveAmericaAvailabilityParserTest.kt` (existing — must stay green)

**Interfaces:**
- Produces: `ReserveAmericaAvailabilityParser.siteRows(html: String): List<String>` — one HTML slice per `siteListLabel` row.
- Produces: `object ReserveAmericaCatalogParser { fun parse(html: String): List<CatalogSite> }` and `data class CatalogSite(val parkId: String, val siteId: String, val name: String)`.

- [ ] **Step 1: Write the failing catalog-parser test**

Real fixture shape (from a live NY park-489 pull): each row is
`<div class='siteListLabel'><a href='/camping/…/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489' … aria-label='Site: 039 (253478)' …>039</a></div>`.

Create `ReserveAmericaCatalogParserTest.kt`:

```kotlin
package ca.floo.roadtrip.clients.reserveamerica

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReserveAmericaCatalogParserTest {
    @Test
    fun `parses site roster from campsite calendar rows`() {
        val html =
            """
            <span id='resulttotal_dr_top'>67</span>
            <div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489" aria-label='Site: 039 (253478)'>039</a></div>
            <div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253497&amp;parkId=489" aria-label='Site: 056 (253497)'>056</a></div>
            """.trimIndent()

        val sites = ReserveAmericaCatalogParser.parse(html)

        assertEquals(
            listOf(
                ReserveAmericaCatalogParser.CatalogSite(parkId = "489", siteId = "253478", name = "039"),
                ReserveAmericaCatalogParser.CatalogSite(parkId = "489", siteId = "253497", name = "056"),
            ),
            sites,
        )
    }

    @Test
    fun `skips rows without a siteId`() {
        val html = "<div class='siteListLabel'><a href='/camping/x'>bogus</a></div>"
        assertEquals(emptyList(), ReserveAmericaCatalogParser.parse(html))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaCatalogParserTest" -x :backend:generateJooq`
Expected: FAIL — `ReserveAmericaCatalogParser` unresolved.

- [ ] **Step 3: Extract the shared `siteRows` helper**

In `ReserveAmericaAvailabilityClient.kt`, inside `object ReserveAmericaAvailabilityParser`, add a public helper and refactor `parse` to use it. Replace the `rowStarts`/`for` slicing at the top of `parse` with a call to `siteRows`:

```kotlin
// inside object ReserveAmericaAvailabilityParser

/** One HTML slice per site row, split on the siteListLabel marker. Shared
 *  by the availability parser (status cells) and the catalog parser (roster). */
fun siteRows(html: String): List<String> {
    val starts = SITE_LABEL.findAll(html).map { it.range.first }.toList()
    return starts.mapIndexed { i, start ->
        html.substring(start, starts.getOrNull(i + 1) ?: html.length)
    }
}
```

Then in `parse`, replace:

```kotlin
        val rowStarts = SITE_LABEL.findAll(html).map { it.range.first }.toList()
        for ((index, rowStart) in rowStarts.withIndex()) {
            val rowEnd = rowStarts.getOrNull(index + 1) ?: html.length
            val row = html.substring(rowStart, rowEnd)
            val siteId = SITE_ID.find(row)?.groupValues?.get(1) ?: continue
```

with:

```kotlin
        for (row in siteRows(html)) {
            val siteId = SITE_ID.find(row)?.groupValues?.get(1) ?: continue
```

(Leave the rest of `parse` — status-cell extraction, `statuses[siteId] = byDate` — unchanged.)

- [ ] **Step 4: Create the catalog parser**

Create `ReserveAmericaCatalogParser.kt`:

```kotlin
package ca.floo.roadtrip.clients.reserveamerica

/**
 * Derives the site roster (catalog) from the same `campsiteCalendar.do` HTML
 * the availability adapter scrapes. Shares row-splitting with
 * [ReserveAmericaAvailabilityParser] via [ReserveAmericaAvailabilityParser.siteRows],
 * so the emitted [CatalogSite.siteId] is byte-for-byte the id the availability
 * path keys on — catalog rows bind to availability by construction.
 *
 * `loop`/`site_type` are intentionally not extracted: the calendar's loopName
 * is a pagination bucket ("Sites 036-049"), not a real loop, and site type is
 * only present as brittle per-cell attribute markup.
 */
object ReserveAmericaCatalogParser {
    data class CatalogSite(
        val parkId: String,
        val siteId: String,
        val name: String,
    )

    fun parse(html: String): List<CatalogSite> =
        ReserveAmericaAvailabilityParser.siteRows(html).mapNotNull { row ->
            val siteId = SITE_ID.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val parkId = PARK_ID.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = LABEL_TEXT.find(row)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { siteId }
            CatalogSite(parkId = parkId, siteId = siteId, name = name)
        }

    private val SITE_ID = Regex("""siteId=(\d+)""")
    private val PARK_ID = Regex("""parkId=(\d+)""")
    private val LABEL_TEXT = Regex(""">([^<]+)</a>""")
}
```

- [ ] **Step 5: Run both parser test classes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaCatalogParserTest" --tests "ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityParserTest" -x :backend:generateJooq`
Expected: PASS (catalog parser green; availability parser regression green).

- [ ] **Step 6: ktlint + commit**

```bash
./gradlew :backend:ktlintCheck -x :backend:generateJooq
git add backend/src/main/kotlin/ca/floo/roadtrip/clients/reserveamerica/ backend/src/test/kotlin/ca/floo/roadtrip/clients/reserveamerica/ReserveAmericaCatalogParserTest.kt
git commit -m "feat(reserveamerica): catalog parser + shared siteRows helper"
```

---

### Task 2: `ReserveAmericaSitesEtl`

Per-tenant terminal ETL for the `reservable_data` section. Reads the campsite-calendar envelopes and emits one reservable per site.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaSitesEtl.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaSitesEtlTest.kt`

**Interfaces:**
- Consumes: `ReserveAmericaCatalogParser.parse` (Task 1); `Envelope` (`payload` is the HTML as a JSON string primitive; `part` like `campsite-489-0`).
- Produces: `class ReserveAmericaSitesEtl(override val etlSlug: String, private val contractCode: String) : SourceEtl<ReserveAmericaSitesEtl.Parsed, ReservableEtlOutput>`; emits `ReservableId(SITE, "reserveamerica_${contractCode.lowercase()}", siteId)`; writes `raw` keys `site_id`, `name`, `_parent_contract_code`, `_parent_park_id`.

- [ ] **Step 1: Write the failing ETL test (incl. identity invariant)**

```kotlin
package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityParser
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.RequestMeta
import ca.floo.roadtrip.models.metadata.ResponseMeta
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReserveAmericaSitesEtlTest {
    private val html =
        """
        <div class='siteListLabel'><a href="/x/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489" aria-label='Site: 039 (253478)'>039</a></div>
        <div class='td status a'>A</div>
        <div class='siteListLabel'><a href="/x/campsiteDetails.do?contractCode=NY&amp;siteId=253497&amp;parkId=489" aria-label='Site: 056 (253497)'>056</a></div>
        <div class='td status r'>R</div>
        """.trimIndent()

    private fun bundle(): InputBundle =
        InputBundle(
            linkedMapOf(
                "reserveamerica-campsites-ny" to
                    listOf(
                        Envelope(
                            fetcher = "fetch_reserveamerica_campsites",
                            fetcherVersion = "1",
                            fetchedAt = "2026-07-05T00:00:00Z",
                            request = RequestMeta(url = "https://x/campsiteCalendar.do", method = "GET"),
                            response = ResponseMeta(status = 200),
                            payload = JsonPrimitive(html),
                            part = "campsite-489-0",
                        ),
                    ),
            ),
            linkedMapOf(),
        )

    @Test
    fun `emits one site reservable per row with per-tenant vendor`() {
        val etl = ReserveAmericaSitesEtl(etlSlug = "new-york-state-park-sites", contractCode = "NY")
        val out = etl.transform(etl.parse(bundle()), TransformCtx.empty())

        assertEquals(2, out.reservables.size)
        val first = out.reservables.first()
        assertEquals("site:reserveamerica_ny:253478", first.rid.encode())
        assertEquals("039", first.name)
        assertEquals(null, first.loop)
        assertEquals(null, first.siteType)
        val raw = first.raw!!.jsonObject
        assertEquals("NY", raw["_parent_contract_code"]!!.jsonPrimitive.content)
        assertEquals("489", raw["_parent_park_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vendorId equals the availability parser siteId (binds by construction)`() {
        val etl = ReserveAmericaSitesEtl(etlSlug = "new-york-state-park-sites", contractCode = "NY")
        val catalogIds = etl.transform(etl.parse(bundle()), TransformCtx.empty()).reservables.map { it.rid.vendorId }.toSet()
        val availabilityIds =
            ReserveAmericaAvailabilityParser.siteRows(html)
                .mapNotNull { Regex("""siteId=(\d+)""").find(it)?.groupValues?.get(1) }
                .toSet()
        assertEquals(availabilityIds, catalogIds)
        assertTrue(catalogIds.isNotEmpty())
    }
}
```

Note: if `TransformCtx.empty()` does not exist, check `TransformCtx`'s constructor in `service/etl/framework/TransformCtx.kt` and build a no-arg instance the same way other ETL tests do (e.g. `RecGovCampsitesEtlTest`) — `parse`/`transform` here use no ctx lookups, so any valid empty ctx works.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaSitesEtlTest" -x :backend:generateJooq`
Expected: FAIL — `ReserveAmericaSitesEtl` unresolved.

- [ ] **Step 3: Implement the ETL**

```kotlin
package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaCatalogParser
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.ReservableEtlOutput
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reservable catalog terminal (RFC 0008) for one ReserveAmerica tenant.
 * Reads the `campsite-<parkId>-<startIdx>` HTML envelopes captured by
 * `scripts/fetch_reserveamerica_campsites.py` and emits one reservable per
 * site. The [ReserveAmericaPoiReservableJoiner] links them to POIs.
 *
 * `vendor` is per-tenant (`reserveamerica_ny`) — mandated by the availability
 * adapter's `rid()`; `vendor_id` is the scraped `siteId`, so catalog rows bind
 * to availability by construction.
 */
class ReserveAmericaSitesEtl(
    override val etlSlug: String,
    private val contractCode: String,
) : SourceEtl<ReserveAmericaSitesEtl.Parsed, ReservableEtlOutput> {
    override val multiPart: Boolean = true

    data class Parsed(val sites: List<ReserveAmericaCatalogParser.CatalogSite>)

    override fun parse(inputs: InputBundle): Parsed =
        Parsed(
            inputs
                .soleEnvelopes()
                .flatMap { ReserveAmericaCatalogParser.parse(it.payload.jsonPrimitive.content) },
        )

    override fun validate(dto: Parsed): ValidationResult<Parsed> =
        if (dto.sites.isEmpty()) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveAmerica campsite rows parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): ReservableEtlOutput {
        val vendor = "reserveamerica_${contractCode.lowercase()}"
        val reservables =
            dto.sites
                .distinctBy { it.siteId }
                .map { site ->
                    ReservableRepo.Input(
                        rid = ReservableId(ReservableType.SITE, vendor, site.siteId),
                        name = site.name,
                        loop = null,
                        siteType = null,
                        raw =
                            buildJsonObject {
                                put("site_id", site.siteId)
                                put("name", site.name)
                                put(PARENT_CONTRACT_KEY, contractCode)
                                put(PARENT_PARK_KEY, site.parkId)
                            },
                    )
                }
        return ReservableEtlOutput(reservables = reservables)
    }

    companion object {
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaSitesEtlTest" -x :backend:generateJooq`
Expected: PASS (both tests, incl. the identity invariant).

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew :backend:ktlintCheck -x :backend:generateJooq
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/
git commit -m "feat(reserveamerica): sites ETL emits per-tenant reservables from calendar roster"
```

---

### Task 3: `ReserveAmericaPoiReservableJoiner`

Links RA reservables to their parent POI by `(contract_code, park_id)`. Mirrors `ReserveCaliforniaPoiReservableJoiner` but joins on two keys and spans both tenants.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoiner.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoinerTest.kt`

**Interfaces:**
- Consumes: `PoiReservableJoiner` / `JoinerCtx` (framework); reservables written by Task 2 (raw keys `_parent_contract_code`, `_parent_park_id`); POIs with `provider_ref` `{contract_code, park_id}` and `source IN ('alberta-provincial','new-york-state-parks')`.
- Produces: `class ReserveAmericaPoiReservableJoiner : PoiReservableJoiner`, `adapter = "ReserveAmericaPoiReservableJoiner"`.

- [ ] **Step 1: Write the failing joiner test**

Model on `ReserveCaliforniaEtlTest`/`RecgovPoiReservableJoinerTest` — both extend `ca.floo.roadtrip.repo.SharedDbTest`. Insert a POI + a reservable, run `discoverLinks`, assert the pair. Use the same `ReservableRepo`/jOOQ insert helpers those tests use (read `RecgovPoiReservableJoinerTest.kt` for the exact `SharedDbTest` insert idiom in this codebase).

```kotlin
package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReserveAmericaPoiReservableJoinerTest : SharedDbTest() {
    @Test
    fun `links RA reservable to its park POI by contract and parkId`() {
        val reservablesRepo = ReservableRepo(dsl)

        // POI: alberta-provincial, provider_ref {contract_code: ABPP, park_id: 330101}
        dsl.execute(
            """
            INSERT INTO pois (source, source_id, name, category, geom, provider_ref)
            VALUES ('alberta-provincial', 'ra-330101', 'Aspen Beach', 'campground',
                    ST_SetSRID(ST_MakePoint(-114.0, 52.4), 4326),
                    '{"contract_code":"ABPP","park_id":"330101"}'::jsonb)
            """.trimIndent(),
        )
        val poiId = dsl.fetchOne("SELECT id FROM pois WHERE source_id = 'ra-330101'")!!.get(0, Long::class.java)

        reservablesRepo.upsert(
            ReservableRepo.Input(
                rid = ReservableId(ReservableType.SITE, "reserveamerica_abpp", "9001"),
                name = "1",
                loop = null,
                siteType = null,
                raw =
                    buildJsonObject {
                        put("_parent_contract_code", "ABPP")
                        put("_parent_park_id", "330101")
                    },
            ),
        )

        val links = ReserveAmericaPoiReservableJoiner().discoverLinks(JoinerCtx(ctx = dsl, reservablesRepo = reservablesRepo))

        assertEquals(1, links.size)
        assertEquals(poiId, links.first().poiId)
    }
}
```

Note: match the exact `SharedDbTest` accessor (`dsl` vs `ctx`) and `ReservableRepo.upsert` signature used by `RecgovPoiReservableJoinerTest.kt` in this repo; adjust the two calls above if they differ.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaPoiReservableJoinerTest" -x :backend:generateJooq`
Expected: FAIL — `ReserveAmericaPoiReservableJoiner` unresolved.

- [ ] **Step 3: Implement the joiner**

```kotlin
package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.framework.PoiReservableJoiner
import org.jooq.impl.DSL

/**
 * Links ReserveAmerica reservables to their parent campground POI by the
 * (contract_code, park_id) pair — the reservable carries them in
 * `raw->>'_parent_contract_code'` / `_parent_park_id`; the POI carries them in
 * `provider_ref->>'contract_code'` / `park_id`. Spans both tenants
 * (alberta-provincial, new-york-state-parks); vendor is per-tenant
 * (`reserveamerica_%`).
 */
class ReserveAmericaPoiReservableJoiner : PoiReservableJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<PoiReservableJoiner.Link> {
        fun res(key: String) =
            DSL.field("jsonb_extract_path_text(({0})::jsonb, {1})", String::class.java, RESERVABLES.RAW, DSL.inline(key))
        fun poi(key: String) =
            DSL.field("jsonb_extract_path_text(({0})::jsonb, {1})", String::class.java, POIS.PROVIDER_REF, DSL.inline(key))

        return ctx.ctx
            .select(RESERVABLES.ID, POIS.ID)
            .from(RESERVABLES)
            .join(POIS)
            .on(
                poi(POI_CONTRACT_KEY).eq(res(PARENT_CONTRACT_KEY))
                    .and(poi(POI_PARK_KEY).eq(res(PARENT_PARK_KEY))),
            )
            .where(POIS.SOURCE.`in`(POI_SOURCES))
            .and(RESERVABLES.VENDOR.like(VENDOR_PREFIX))
            .and(DSL.condition("reservables.deleted_at IS NULL"))
            .and(POIS.DELETED_AT.isNull)
            .fetch { record -> PoiReservableJoiner.Link(reservableId = record.value1()!!, poiId = record.value2()!!) }
    }

    override fun sweepStaleLinks(ctx: JoinerCtx): Int =
        ctx.ctx.execute(
            """
            DELETE FROM reservable_pois rp
            USING reservables r, pois p
            WHERE rp.reservable_id = r.id
              AND rp.poi_id = p.id
              AND r.vendor LIKE ?
              AND p.source IN ('alberta-provincial','new-york-state-parks')
              AND (
                r.deleted_at IS NOT NULL
                OR p.deleted_at IS NOT NULL
                OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM jsonb_extract_path_text(r.raw::jsonb, ?)
                OR jsonb_extract_path_text(p.provider_ref::jsonb, ?) IS DISTINCT FROM jsonb_extract_path_text(r.raw::jsonb, ?)
              )
            """.trimIndent(),
            VENDOR_PREFIX,
            POI_CONTRACT_KEY, PARENT_CONTRACT_KEY,
            POI_PARK_KEY, PARENT_PARK_KEY,
        )

    private companion object {
        const val ADAPTER_NAME = "ReserveAmericaPoiReservableJoiner"
        const val VENDOR_PREFIX = "reserveamerica_%"
        val POI_SOURCES = listOf("alberta-provincial", "new-york-state-parks")
        const val POI_CONTRACT_KEY = "contract_code"
        const val POI_PARK_KEY = "park_id"
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaPoiReservableJoinerTest" -x :backend:generateJooq`
Expected: PASS.

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew :backend:ktlintCheck -x :backend:generateJooq
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoiner.kt backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaPoiReservableJoinerTest.kt
git commit -m "feat(reserveamerica): joiner links reservables to POIs by (contract, parkId)"
```

---

### Task 4: Registry wiring

Register the two data sources, the two SitesEtl instances, and the joiner. This is what actually activates the pipeline.

**Files:**
- Modify: `config/poi-registry.yaml`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt` (`etlRegistry` ~line 460, `joinerRegistry` ~line 540)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistryTest.kt` (existing registry validation — must stay green; if absent, the orchestrator/registry test that loads `poi-registry.yaml`)

**Interfaces:**
- Consumes: `ReserveAmericaSitesEtl` (Task 2), `ReserveAmericaPoiReservableJoiner` (Task 3).
- Produces: etl slugs `alberta-provincial-park-sites`, `new-york-state-park-sites`; data_source slugs `reserveamerica-campsites-abpp`, `reserveamerica-campsites-ny`; joiner adapter `ReserveAmericaPoiReservableJoiner`.

- [ ] **Step 1: Add the two data sources to `config/poi-registry.yaml`**

Under `data_sources:`, after the `reserveamerica-ny` block:

```yaml
  - slug: reserveamerica-campsites-abpp
    name: ReserveAmerica Alberta campsite calendar rosters
    fetcher:
      executor: python3
      filename: scripts/fetch_reserveamerica_campsites.py
      args: { tenant: ABPP }
      output_dir_prefix: data/raw/reserveamerica-campsites-abpp

  - slug: reserveamerica-campsites-ny
    name: ReserveAmerica New York campsite calendar rosters
    fetcher:
      executor: python3
      filename: scripts/fetch_reserveamerica_campsites.py
      args: { tenant: NY }
      output_dir_prefix: data/raw/reserveamerica-campsites-ny
```

- [ ] **Step 2: Add the reservable_data ETL rows**

Under `reservable_data:`, after the `california-state-park-sites` row:

```yaml
  - etls:
      - slug: alberta-provincial-park-sites
        adapter: ReserveAmericaSitesEtl
        inputs: [reserveamerica-campsites-abpp]
  - etls:
      - slug: new-york-state-park-sites
        adapter: ReserveAmericaSitesEtl
        inputs: [reserveamerica-campsites-ny]
```

(Match the exact indentation/grouping of the existing `reservable_data` rows — copy the shape of the `california-state-park-sites` row directly above.)

- [ ] **Step 3: Add the joiner row**

Under `poi_reservable_joiner:`, after the ReserveCalifornia entry:

```yaml
  - name: ReserveAmerica Sites → Alberta + NY Parks
    adapter: ReserveAmericaPoiReservableJoiner
```

- [ ] **Step 4: Register the ETL instances + joiner in `EtlOrchestrator.kt`**

In `etlRegistry` (after the `california-state-park-sites` entry, ~line 531):

```kotlin
                "alberta-provincial-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl(etlSlug = "alberta-provincial-park-sites", contractCode = "ABPP"),
                "new-york-state-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl(etlSlug = "new-york-state-park-sites", contractCode = "NY"),
```

In `joinerRegistry` (after the ReserveCalifornia entry, ~line 550):

```kotlin
                "ReserveAmericaPoiReservableJoiner" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaPoiReservableJoiner(),
```

- [ ] **Step 5: Run the registry/orchestrator validation test**

Run: `./gradlew :backend:test --tests "*PoiRegistry*" --tests "*EtlOrchestrator*" -x :backend:generateJooq`
Expected: PASS — the YAML slugs resolve to registered adapters, no "adapter not found" / "unknown slug" errors. (If a test asserts a fixed count of etls/joiners, update that count.)

- [ ] **Step 6: ktlint + commit**

```bash
./gradlew :backend:ktlintCheck -x :backend:generateJooq
git add config/poi-registry.yaml backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt
git commit -m "feat(reserveamerica): wire campsite data sources, sites ETLs, and joiner"
```

---

### Task 5: `fetch_reserveamerica_campsites.py`

Capture `campsiteCalendar.do` per park into `data/raw/`. Reuses `fetch_reserveamerica.py`'s WAF session + directory walk to enumerate parkIds.

**Files:**
- Create: `scripts/fetch_reserveamerica_campsites.py`

**Interfaces:**
- Consumes: `_envelope` helpers (`load_source`, `parse_payload`, `write_envelope`, `utc_ts`, `err`); the tenant table + session/directory-walk idiom from `scripts/fetch_reserveamerica.py`.
- Produces: envelopes under `data/raw/reserveamerica-campsites-<contract>/<ts>/`, one per page, `part = "campsite-<parkId>-<startIdx>"`, payload = calendar HTML string.

- [ ] **Step 1: Write the fetcher**

```python
#!/usr/bin/env python3
"""Capture ReserveAmerica campsite-calendar rosters per park.

For each park in a tenant, fetch campsiteCalendar.do (paginating startIdx by
PAGE_STEP until resulttotal is covered) and write one envelope per page. The
site roster — siteId + label — is embedded in these pages; ReserveAmericaSitesEtl
parses it. We reuse fetch_reserveamerica's WAF session (welcome.do primes the
JSESSIONID cookie) and directory walk to enumerate parkIds.

Usage:
  python3 scripts/fetch_reserveamerica_campsites.py            # all tenants
  python3 scripts/fetch_reserveamerica_campsites.py --tenant ABPP
"""
from __future__ import annotations

import argparse
import datetime as dt
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import err, load_source, parse_payload, utc_ts, write_envelope  # noqa: E402
from fetch_reserveamerica import (  # noqa: E402
    COMMON_HEADERS,
    DELAY_S,
    LETTERS,
    MAX_PAGES_PER_LETTER,
    PAGE_STEP,
    TENANTS,
    Tenant,
    _request,
    directory_url,
    make_session,
    park_ids_in_html,
)

FETCHER = "fetch_reserveamerica_campsites"
FETCHER_VERSION = "1"

# campsiteCalendar.do paginates the site list 25 at a time (mirrors the
# ReserveAmericaAvailabilityClient PAGE_SIZE). Cap pages defensively.
SITE_PAGE_SIZE = 25
MAX_SITE_PAGES = 40  # 1000 sites/park ceiling


def calendar_url(host: str, contract: str, park_id: str, arvdate: str, start_idx: int) -> str:
    return (
        f"https://{host}/campsiteCalendar.do?page=calendar"
        f"&contractCode={contract}&parkId={park_id}"
        f"&calarvdate={arvdate}&sitepage=true&startIdx={start_idx}"
    )


def enumerate_park_ids(tenant: Tenant, opener, welcome_url: str) -> list[str]:
    ids: set[str] = set()
    for letter in LETTERS:
        for page in range(MAX_PAGES_PER_LETTER):
            url = directory_url(tenant.host, tenant.contract, letter, page * PAGE_STEP)
            status, _, _, body = _request(opener, url, referer=welcome_url, timeout=60)
            on_page = park_ids_in_html(tenant.contract, body)
            if not on_page:
                break
            ids.update(on_page)
            time.sleep(DELAY_S)
            if len(on_page) < PAGE_STEP:
                break
    return sorted(ids, key=int)


def fetch_tenant(tenant: Tenant, ts: str) -> int:
    slug = f"reserveamerica-campsites-{tenant.contract.lower()}"
    source_obj = load_source(slug)
    welcome_url = f"https://{tenant.host}/welcome.do"
    opener = make_session(tenant.host)
    _request(opener, welcome_url, referer=f"https://{tenant.host}/", timeout=30)

    park_ids = enumerate_park_ids(tenant, opener, welcome_url)
    err(f"  [{tenant.contract}] {len(park_ids)} parks")
    # A near-term arrival date makes the calendar list the full site roster.
    arvdate = (dt.date.today() + dt.timedelta(days=14)).strftime("%m/%d/%Y")

    for i, park_id in enumerate(park_ids, start=1):
        start_idx = 0
        for _ in range(MAX_SITE_PAGES):
            url = calendar_url(tenant.host, tenant.contract, park_id, arvdate, start_idx)
            try:
                status, req_h, resp_h, body = _request(opener, url, referer=welcome_url, timeout=60)
            except Exception as e:  # noqa: BLE001
                err(f"  [{tenant.contract}] park {park_id}@{start_idx} failed: {e}")
                break
            payload = parse_payload(resp_h.get("content-type", ""), body)
            row_count = body.count("siteListLabel")
            if row_count == 0:
                break
            write_envelope(
                source_obj=source_obj,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=url,
                request_method="GET",
                request_headers=req_h,
                response_status=status,
                response_headers=resp_h,
                payload=payload,
                ts=ts,
                part=f"campsite-{park_id}-{start_idx}",
            )
            if row_count < SITE_PAGE_SIZE:
                break
            start_idx += SITE_PAGE_SIZE
            time.sleep(DELAY_S)
        if i % 20 == 0:
            err(f"  [{tenant.contract}] {i}/{len(park_ids)} parks…")
        time.sleep(DELAY_S)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tenant", help="ABPP or NY; omit for all")
    args = ap.parse_args()
    ts = utc_ts()
    tenants = [t for t in TENANTS if not args.tenant or t.contract == args.tenant]
    if not tenants:
        err(f"no tenant matching {args.tenant}")
        return 1
    rc = 0
    for t in tenants:
        rc |= fetch_tenant(t, ts)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Smoke-run one tenant against the live site**

Run: `python3 scripts/fetch_reserveamerica_campsites.py --tenant NY`
Expected: writes envelopes under `data/raw/reserveamerica-campsites-ny/<ts>/campsite-*.json`. Spot-check one contains `siteListLabel` rows:

```bash
ls data/raw/reserveamerica-campsites-ny/*/ | head
python3 -c "import json,glob; f=sorted(glob.glob('data/raw/reserveamerica-campsites-ny/*/campsite-489-0.json'))[-1]; print('siteListLabel' in json.load(open(f))['payload'])"
```
Expected: `True`.

- [ ] **Step 3: Confirm the ETL parses the real capture**

Run the SitesEtl against the freshly captured park-489 envelope in a scratch check (or re-run the Task 2 test — it already covers parsing). Verify at least one reservable is produced from real HTML:

```bash
python3 -c "import json,glob,re; f=sorted(glob.glob('data/raw/reserveamerica-campsites-ny/*/campsite-489-0.json'))[-1]; h=json.load(open(f))['payload']; print('sites:', len(re.findall(r'siteId=(\\\\d+)', h)))"
```
Expected: a non-zero site count matching the park's roster.

- [ ] **Step 4: Commit**

```bash
git add scripts/fetch_reserveamerica_campsites.py
git commit -m "feat(reserveamerica): fetcher captures campsiteCalendar.do site rosters"
```

(Do NOT commit `data/raw/` captures unless the repo convention is to check fixtures in — confirm against how `reserveamerica-ny` captures are tracked before adding them.)

---

### Task 6: Sunset-demarcate the catalogless path

Document `cataloglessProviderAvailability` as the scoped, deprecated handler for upstream-bad-data residue. No behavior change.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt` (the `cataloglessProviderAvailability` function, ~line 75)

- [ ] **Step 1: Add the sunset KDoc**

Directly above `private suspend fun cataloglessProviderAvailability(`:

```kotlin
/**
 * SUNSET — render-only fallback for POIs with no obtainable catalog.
 *
 * This path serves POIs that have a `provider_ref` but no linked `reservables`.
 * With ReserveAmerica now cataloged, the remaining population is upstream bad
 * data, NOT missing ingestion:
 *   - Aspira POIs with `provider_ref.resourceLocationId == null` (Parks Canada
 *     join-by-name entries that never got a join key);
 *   - RecGov non-campsite facilities (day-use areas, cabins, lookouts, group
 *     sites, boat ramps, visitor centers) that have no standard campsite roster;
 *   - ReserveCalifornia open-camping / SVRA grids the sites ETL intentionally skips.
 *
 * Any vendor with an obtainable catalog MUST be cataloged (SitesEtl + joiner)
 * rather than rely on this path. It is earmarked for removal once the
 * catalogless population reaches zero. Do not add new vendors here.
 */
```

- [ ] **Step 2: Confirm it compiles (no behavior change)**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.availability.*" -x :backend:generateJooq`
Expected: PASS (unchanged behavior; existing availability tests green).

- [ ] **Step 3: ktlint + commit**

```bash
./gradlew :backend:ktlintCheck -x :backend:generateJooq
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt
git commit -m "docs(availability): sunset-demarcate the catalogless residue handler"
```

---

### Task 7: Documentation

New per-vendor wire doc + posture correction in the architecture-contract doc.

**Files:**
- Create: `docs/reservation-providers/reserveamerica.md`
- Modify: `docs/reservation-providers.md` (matrix note ~line 92; closing pointer ~line 273)

- [ ] **Step 1: Write `docs/reservation-providers/reserveamerica.md`**

Mirror the structure of `docs/reservation-providers/reservecalifornia.md`. Required sections and content:

- **Summary** — Two Active Network systems: the **consumer site** (`shop.albertaparks.ca`, `newyorkstateparks.reserveamerica.com`) scraped via `campsiteCalendar.do` (availability + the site roster; no key) and the **developer API** (`api.amp.active.com`) which is **decommissioned** — see below.
- **Tenants** — `ABPP` → `shop.albertaparks.ca` (Alberta provincial); `NY` → `newyorkstateparks.reserveamerica.com` (New York state).
- **ID model** — `provider_ref = {contract_code, park_id}` (both strings). Reservable identity `site:reserveamerica_{contract}:{siteId}` where `siteId` is the numeric id from the calendar's `campsiteDetails.do` href; `vendor` is per-tenant. `facilityID` is unique only within a `contractCode`.
- **Endpoint catalog** —
  - `GET campsiteCalendar.do?page=calendar&contractCode=&parkId=&calarvdate=&sitepage=true&startIdx=` → HTML; per-site `<div class='siteListLabel'><a href='…siteId=…&parkId=…'>NNN</a>`; paginates `startIdx` by 25; `resulttotal` gives the count. **This is the catalog source.**
  - **DEAD — do not re-investigate:** `api.amp.active.com/camping/{campgrounds,campsites}` (Campground/Campsite Search) documents rich `SiteType`/`Loop`/capacity/hookups keyed by `(contractCode, parkId)`, but the endpoint returns `awselb/2.0` 403 to **every** caller — no key, a valid provisioned key, our egress, Anthropic's WebFetch egress, and **Active's own I/O Docs console** (verified 2026-07-05). Docs footer © 2017. The API is decommissioned; rich `site_type`/`loop` is not obtainable from it by anyone.
- **Catalog status** — Cataloged via the `campsiteCalendar.do` roster (`ReserveAmericaSitesEtl` + `ReserveAmericaPoiReservableJoiner`). `name` = site number; `loop`/`site_type` are **null** (calendar `loopName` is a pagination bucket). Only future path to rich `loop`/`site_type` is a `campsiteDetails.do` per-site scrape — NOT the dead API.
- **Adapter design notes** — Availability reads the live matrix (`ReserveAmericaAvailabilityParser`); the catalog parser (`ReserveAmericaCatalogParser`) shares row-splitting, so catalog `vendor_id` equals the availability `siteId` by construction. Watches stay off (`supportsAlerts=false`) pending cadence/load validation.

- [ ] **Step 2: Correct the matrix note in `docs/reservation-providers.md`**

Replace the ReserveAmerica row's Notes cell (line ~92). The doc forbids inlining wire shapes here — architecture-level only:

> `Availability reads the live campsite-calendar matrix; sites are cataloged from that same calendar roster (see reserveamerica.md). Alerts stay off until upstream cadence/load limits are validated.`

- [ ] **Step 3: Promote the closing pointer (line ~273)**

Replace `_recgov.md, reserveamerica.md — to be written._` with:

```markdown
- [reserveamerica.md](reservation-providers/reserveamerica.md) — ReserveAmerica /
  Active Network (`shop.albertaparks.ca`, `newyorkstateparks.reserveamerica.com`).
- _recgov.md — to be written._
```

- [ ] **Step 4: Commit**

```bash
git add docs/reservation-providers/reserveamerica.md docs/reservation-providers.md
git commit -m "docs(reserveamerica): vendor wire doc + posture correction"
```

---

## Self-Review

**Spec coverage:**
- Fetcher (spec §Components 1) → Task 5. ✔
- Shared `siteRows` + catalog parser (§Components 2) → Task 1. ✔
- `ReserveAmericaSitesEtl` with mandated per-tenant vendor (§Components 3, §Global) → Task 2. ✔
- `ReserveAmericaPoiReservableJoiner` on (contract, park) (§Components 4) → Task 3. ✔
- Registry wiring, no jOOQ codegen (§Components 5) → Task 4. ✔
- Sunset demarcation (§Sunset) → Task 6. ✔
- Docs, both files, dead-API recorded (§Docs) → Task 7. ✔
- Identity invariant test (§Testing) → Task 2 Step 1 (second test). ✔
- Data-flow end-to-end (§Data flow) → validated by Task 5 smoke-run + the joiner test.

**Placeholder scan:** No TBD/TODO. Two explicit "match the existing idiom" notes (Task 2 `TransformCtx.empty()`, Task 3 `SharedDbTest` accessor + `ReservableRepo.upsert` signature) point the implementer at a named reference file to copy verbatim — resolve them by reading that file, not by inventing.

**Type consistency:** `siteRows` (Task 1) consumed by Task 2's invariant test; `CatalogSite(parkId, siteId, name)` consistent across Tasks 1–2; raw keys `_parent_contract_code`/`_parent_park_id` consistent Task 2 → Task 3; vendor `reserveamerica_{contract}` consistent with the adapter `rid()` (Global Constraints) and the joiner `reserveamerica_%` prefix.

**Pre-implementation check (spec):** Task 5 Step 2/3 is the calibration the spec called for — confirms a near-term window lists the roster and the ETL parses real HTML. If a real park lists **zero** rows for a near-term window, revisit `calarvdate` (try in-season) before proceeding.
