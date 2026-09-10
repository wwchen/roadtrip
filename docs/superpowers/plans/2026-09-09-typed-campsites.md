# Typed Campsites Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote every campsite fact the drawer renders into typed columns filled by the vendor ETLs, serve a closed `CampsiteDto` instead of the table row, and delete the frontend's raw-payload readers.

**Architecture:** Same pattern as #724: `@Serializable` domain types for the JSONB columns, one codec (`CatalogColumnJson`, renamed from `CampgroundColumnJson`), the entity repo as the only encoder/decoder, vendor ETLs normalizing upstream keys on write, and a Flyway migration (`V56`) that rewrites stored rows once so reads decode strictly. The API layer maps the row to `CampsiteDto`; `Campsite` stops being serializable. The frontend `Campsite` type becomes the closed mirror of the DTO.

**Tech Stack:** Kotlin 2 / Ktor / jOOQ raw SQL / kotlinx.serialization / Flyway + Postgres (Testcontainers in tests) / detekt + ktlint; React + TypeScript + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-09-etl-promotion-design.md`, section 2a. Audit: `docs/superpowers/specs/2026-09-09-architecture-audit.md` findings 2 and 12. Issues: #731 (finding 2), #741 (finding 12, campsite part).

## Global Constraints

- Layering per `docs/backend-architecture.md`: SQL only in `repo/`; no Ktor types in `service/`; models depend on stdlib + serialization only; vendor ETLs emit candidates and never touch repos.
- No inline magic constants; comments short and rare; never edit an applied migration (`V55` is the latest; this plan adds `V56`).
- Canonical JSON shapes written to `campsites`: `equipment` is a JSON array of strings; `photos` is `[{"url": "..."}]`; `attributes` is `[{"name": "...", "value": "..."}]` with `value` omitted when null. Absent lists are `[]`, never SQL NULL.
- `CampsiteDto` fields and `@SerialName`s exactly as listed in Task 4; nullable scalars omitted when absent (`explicitNulls = false`, as the routes already configure).
- Backend gate (Docker running): `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`. Fast loop: `./gradlew :backend:test --tests '<pattern>' --offline -q`. ktlint fixes: `./gradlew :backend:ktlintFormat --offline -q`.
- Frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- Commit per task with a conventional prefix and `Refs #731` (or `#741`), ending `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- The first Write/Edit to each file in a session is denied by the GateGuard hook; state the facts it asks for in one line and re-issue the identical call.

---

### Task 1: Domain types, shared codec, migration, repo (#731)

**Files:**
- Rename: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJson.kt` → `CatalogColumnJson.kt` (object `CatalogColumnJson`; update every import, including `CampgroundRepo.kt`, `CampgroundService.kt`, and `CampgroundColumnJsonTest.kt` → `CatalogColumnJsonTest.kt`)
- Rename: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundPhoto.kt` → `CatalogPhoto.kt` (class `CatalogPhoto`; update every use)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteAttribute.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampsiteUpsertCandidate.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campsite.kt` (typed fields; keep `@Serializable` for now — Task 4 removes it)
- Create: `backend/src/main/resources/db/migration/V56__typed_campsite_columns.sql`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampsiteRepo.kt` (insert placeholders/columns, `campsiteFromRecord`, `campsiteSelect`, delete `rawContainsJson`)
- Modify: the five campsite ETLs only as far as the compiler requires for the type change (`equipment`/`photos` now typed): `service/etl/vendors/recgov/RecGovCampsitesEtl.kt`, `campflare/CampflareCampsitesEtl.kt`, `aspira/AspiraCampsitesEtl.kt` (ReserveAmerica and ReserveCalifornia pass nothing for these fields and need no change)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt` (round-trip of the typed fields), `repo/CanonicalCatalogSchemaTest.kt` (column list gains `attributes`, `description`, `min_people`), `model/domain/CatalogColumnJsonTest.kt`, plus every test the compiler flags (`fixtures/CampsiteFixture.kt`, the ETL tests).

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class CampsiteAttribute(val name: String, val value: String? = null)
  @Serializable data class CatalogPhoto(val url: String)
  // CampsiteUpsertCandidate gains / changes:
  val equipment: List<String> = emptyList(),
  val photos: List<CatalogPhoto> = emptyList(),
  val attributes: List<CampsiteAttribute> = emptyList(),
  val description: String? = null,
  val minPeople: Int? = null,
  // Campsite row: same five, non-null lists; schedule/price/sourcePayload stay JsonElement
  ```
- `CampsiteRepo` encodes the three lists with `CatalogColumnJson.encodeArray` and decodes with `decodeArray`.

- [ ] **Step 1: Failing repo round-trip test**

In `CatalogEntityRepoTest.kt`, next to the existing campsite upsert test (around line 238), add:

```kotlin
@Test
fun `campsite typed columns round-trip through the repo`() {
    val campgroundId = seedCampgroundForCampsites()   // reuse whatever helper the neighbouring test uses to get a parent row
    val candidate =
        CampsiteUpsertCandidate(
            dataProviderRef = DataProviderRef.RecGov(id = "cs-typed-1"),
            parentDataProviderRef = DataProviderRef.RecGov(id = "cg-typed-1"),
            name = "Site 1",
            equipment = listOf("Tent", "RV"),
            photos = listOf(CatalogPhoto(url = "https://example.test/site1.jpg")),
            attributes = listOf(CampsiteAttribute(name = "Shade", value = "Partial"), CampsiteAttribute(name = "Pets allowed")),
            description = "Walk-in tent site by the water.",
            minPeople = 2,
            maxPeople = 6,
        )
    CampsiteRepo(ctx).upsertCampsiteBatch(listOf(candidate))
    val row = CampsiteRepo(ctx).findByPoi(poiIdFor(campgroundId)).single()   // or findById; use what the neighbouring test uses
    assertEquals(listOf("Tent", "RV"), row.equipment)
    assertEquals(listOf(CatalogPhoto("https://example.test/site1.jpg")), row.photos)
    assertEquals(listOf(CampsiteAttribute("Shade", "Partial"), CampsiteAttribute("Pets allowed")), row.attributes)
    assertEquals("Walk-in tent site by the water.", row.description)
    assertEquals(2, row.minPeople)
}
```

Read the neighbouring campsite test first and copy its parent-seeding and lookup calls exactly; the names above are placeholders for those two calls only.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --offline -q`
Expected: unresolved `CatalogPhoto` / `attributes` / `description` / `minPeople`.

- [ ] **Step 3: Renames and new types**

`git mv` the two files; `CatalogColumnJson` keeps the exact body of `CampgroundColumnJson` with the KDoc first sentence changed to "The one codec for the typed catalog JSONB columns (campgrounds and campsites)." `CatalogPhoto` keeps `CampgroundPhoto`'s body. Fix every import with the compiler (`grep -rn 'CampgroundColumnJson\|CampgroundPhoto' backend/src` must be empty afterwards).

`CampsiteAttribute.kt`:

```kotlin
package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campsites.attributes` JSONB array. */
@Serializable
data class CampsiteAttribute(
    val name: String,
    val value: String? = null,
)
```

`CampsiteUpsertCandidate`: replace `equipment: JsonElement?` with `equipment: List<String> = emptyList()`, `photos: JsonElement?` with `photos: List<CatalogPhoto> = emptyList()`, and add `attributes`, `description`, `minPeople` per Interfaces. `Campsite`: `equipment: List<String>`, `photos: List<CatalogPhoto>`, `attributes: List<CampsiteAttribute>`, `description: String?`, `minPeople: Int?` (`@SerialName("min_people")` for now, since the row is still serialized until Task 4).

- [ ] **Step 4: Migration V56**

```sql
-- Typed campsite columns. equipment becomes an array of strings, photos an
-- array of {url}, and three facts the drawer used to dig out of source_payload
-- get columns. Idempotent; canonical rows map to themselves.

ALTER TABLE campsites
  ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS description TEXT,
  ADD COLUMN IF NOT EXISTS min_people INT;

ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_attributes_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_attributes_check CHECK (jsonb_typeof(attributes) = 'array');

UPDATE campsites SET equipment = COALESCE((
  SELECT jsonb_agg(s.label ORDER BY s.ord)
  FROM (
    SELECT e.ord,
           NULLIF(btrim(CASE WHEN jsonb_typeof(e.v) = 'string' THEN e.v #>> '{}'
                             WHEN jsonb_typeof(e.v) = 'object' THEN COALESCE(e.v->>'name', e.v->>'label', e.v->>'equipment_name') END), '') AS label
    FROM jsonb_array_elements(equipment) WITH ORDINALITY AS e(v, ord)
  ) s WHERE s.label IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(equipment) = 'array';

UPDATE campsites SET equipment = '[]'::jsonb WHERE equipment IS NULL OR jsonb_typeof(equipment) <> 'array';
ALTER TABLE campsites ALTER COLUMN equipment SET DEFAULT '[]'::jsonb;
ALTER TABLE campsites ALTER COLUMN equipment SET NOT NULL;

UPDATE campsites SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord, NULLIF(btrim(COALESCE(p.v->>'url', p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url')), '') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array';

UPDATE campsites SET min_people = COALESCE(
    (source_payload->>'min_capacity')::int,
    (source_payload->'_roadtrip_tags'->'capacity'->>'min')::int)
WHERE min_people IS NULL
  AND (source_payload->>'min_capacity' ~ '^[0-9]+$' OR source_payload->'_roadtrip_tags'->'capacity'->>'min' ~ '^[0-9]+$');

UPDATE campsites SET description = NULLIF(btrim(regexp_replace(regexp_replace(source_payload->>'description', '<[^>]*>', ' ', 'g'), '\s+', ' ', 'g')), '')
WHERE description IS NULL AND source_payload ? 'description';

UPDATE campsites SET attributes = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('name', s.name, 'value', s.value)) ORDER BY s.ord)
  FROM (
    SELECT a.ord, NULLIF(btrim(a.v->>'name'), '') AS name,
           NULLIF(btrim(COALESCE(a.v->'value_labels'->>0, a.v->>'value')), '') AS value
    FROM jsonb_array_elements(source_payload->'defined_attributes') WITH ORDINALITY AS a(v, ord)
    WHERE jsonb_typeof(a.v) = 'object'
  ) s WHERE s.name IS NOT NULL), '[]'::jsonb)
WHERE attributes = '[]'::jsonb AND jsonb_typeof(source_payload->'defined_attributes') = 'array';

UPDATE campsites SET attributes = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('name', initcap(replace(t.key, '_', ' ')), 'value', NULLIF(btrim(t.value), ''))) ORDER BY t.key)
  FROM jsonb_each_text(source_payload->'_roadtrip_tags'->'attributes') AS t(key, value)), '[]'::jsonb)
WHERE attributes = '[]'::jsonb AND jsonb_typeof(source_payload->'_roadtrip_tags'->'attributes') = 'object';
```

Check `V38__canonical_catalog.sql` for the exact names of the existing `campsites_equipment_check` and `campsites_photos_check` constraints; `equipment` becoming NOT NULL must not conflict with `campsites_equipment_check` (array-or-null) — drop and recreate it as array-only if needed.

- [ ] **Step 5: Repo**

`bulkUpsertCampsiteRows`: add `attributes, description, min_people` to the column list, placeholders (`?::jsonb, ?, ?`), `DO UPDATE SET` (`attributes = EXCLUDED.attributes, description = EXCLUDED.description, min_people = EXCLUDED.min_people`), and params (`CatalogColumnJson.encodeArray(record.attributes)`, `record.description`, `record.minPeople`). Replace `jsonArrayOrNull(record.equipment)` with `CatalogColumnJson.encodeArray(record.equipment)` and `jsonArray(record.photos)` with `CatalogColumnJson.encodeArray(record.photos)`.

`campsiteSelect`: add `c.attributes::text AS attributes_text, c.description, c.min_people`. `campsiteFromRecord`: `equipment = CatalogColumnJson.decodeArray(record.get("equipment_text", String::class.java))`, `photos = CatalogColumnJson.decodeArray(...)`, `attributes = CatalogColumnJson.decodeArray(record.get("attributes_text", String::class.java))`, `description = record.get("description", String::class.java)`, `minPeople = record.get("min_people", Int::class.javaObjectType)`. Delete `parseNullableJsonElement`, `SearchFilters.rawContainsJson`, and the `source_payload @>` clause (grep confirms no caller).

- [ ] **Step 6: ETL compile fixes only**

- rec.gov: `equipment = (raw["equipment_types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()`.
- Campflare: `equipment = raw.arrayField("equipment")` → map each element: a string primitive is itself, an object takes `name`; `photos = raw.arrayField("photos")` → map objects through `CampflareCampgroundFields`' photo URL precedence (reuse the existing function that builds `CatalogPhoto` from a Campflare photo object; if it is private to campgrounds, make it `internal` in that file).
- Aspira: `equipment = inv.allowedEquipment?.let { enrichAllowedEquipment(it, dto.dictionaries) }` → the sub-category names only: `List<String>` of `label?.subCategoryName` values, keeping the enriched JSON in `sourcePayload` as today.

Leave `minPeople`, `description`, `attributes` for Task 2.

- [ ] **Step 7: Fix tests and run the gate**

Update `fixtures/CampsiteFixture.kt` (`equipment = emptyList()`, `photos = emptyList()`, `attributes = emptyList()`, `description = null`, `minPeople = null`), `CanonicalCatalogSchemaTest` column list, `CampflareCampsitesEtlTest` (`equipment[0].name` assertion becomes `equipment == listOf("Tent")` or whatever the fixture names), and anything else the compiler flags. Add to `CatalogEntityRepoTest` a V56 no-op assertion like the existing V55 one (around line 609): a row written by the typed repo re-reads byte-identical after re-running the V56 statements (copy the pattern of the V55 test).

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A backend
git commit -m "refactor(catalog): typed campsite equipment, photos, attributes, description, min_people

One codec (CatalogColumnJson) for both catalog tables; V56 rewrites stored
campsite rows into the canonical shapes and backfills the three new columns
from source_payload.

Refs #731

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: ETLs promote the facts the drawer renders (#731, #741)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/HtmlText.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampsiteTags.kt`
- Modify: `service/etl/vendors/recgov/RecGovCampsitesEtl.kt`, `service/etl/vendors/aspira/AspiraCampsitesEtl.kt`, `service/etl/vendors/campflare/CampflareCampsitesEtl.kt`, `service/etl/vendors/reservecalifornia/ReserveCaliforniaCampgroundsEtl.kt` (use `HtmlText.stripTags` where it strips `<br>`-split highlight tags today)
- Test: `service/etl/framework/HtmlTextTest.kt` (new), `service/etl/vendors/recgov/RecGovCampsitesEtlTest.kt`, `aspira/AspiraCampsitesEtlTest.kt`, `campflare/CampflareCampsitesEtlTest.kt`

**Interfaces:**
- Produces: `object HtmlText { fun stripTags(value: String): String }` — replaces `<[^>]*>` with a space, collapses whitespace, trims.
- rec.gov promotion table (attribute names compared case-insensitively after trimming):

  | `attribute_name` | Typed field | Value rule |
  | --- | --- | --- |
  | `Fire Pit` | `firepit` | `Yes`/`Y`/`true` → true, `No`/`N`/`false` → false |
  | `Picnic Table` | `picnicTable` | same |
  | `Accessible`, `ADA Accessible` | `adaAccessible` | same |
  | `Max Num of Vehicles`, `Max Vehicles` | `maxCars` | leading integer |
  | `Driveway Length` | `drivewayLength` | leading integer |
  | `Max Vehicle Length` | `maxRvLength` | leading integer |
  | anything else | `attributes += CampsiteAttribute(name, value)` | value as given, blank → null |

  Promoted names are not repeated in `attributes`. `campsite_reserve_type` → `CampsiteAttribute("Reserve type", v)`, `type_of_use` → `CampsiteAttribute("Type of use", v)`, `min_num_people` → `minPeople`. `sourcePayload` is the raw campsite object plus `_parent_facility_id` only; `_roadtrip_tags` is gone.
- Aspira: `minPeople = inv.minCapacity`; `description = inv.description?.let(HtmlText::stripTags)?.takeIf { it.isNotBlank() }`; `attributes` = for each defined attribute with a dictionary name: `CampsiteAttribute(name, value_labels.firstOrNull() ?: scalar value?.toString())`.
- Campflare: `description = raw.stringField("description")?.let(HtmlText::stripTags)`.

- [ ] **Step 1: Failing ETL tests**

`HtmlTextTest`: `assertEquals("Walk-in tent site by the water.", HtmlText.stripTags("<p>Walk-in <b>tent</b> site\n by the water.</p>"))` and a no-op case.

`RecGovCampsitesEtlTest`: build a campsite object with `min_num_people: 2`, `max_num_people: 6`, `campsite_reserve_type: "Site-Specific"`, `type_of_use: "Overnight"`, `attributes: [{attribute_name: "Fire Pit", attribute_value: "Yes"}, {attribute_name: "Driveway Length", attribute_value: "40"}, {attribute_name: "Shade", attribute_value: "Partial"}]` and assert `minPeople == 2`, `firepit == true`, `drivewayLength == 40`, `attributes == listOf(CampsiteAttribute("Shade", "Partial"), CampsiteAttribute("Reserve type", "Site-Specific"), CampsiteAttribute("Type of use", "Overnight"))`, and that `sourcePayload` has no `_roadtrip_tags` key. Look at how the existing test feeds a facility JSON to the ETL and reuse that.

`AspiraCampsitesEtlTest`: extend the existing inventory fixture with `minCapacity: 2`, a `localizedValues[0].description` of `"<p>Lakeside &amp; shaded</p>"` (assert the stripped text, keeping entities as the stripper leaves them), and one `definedAttributes` entry whose dictionary name is `Max Vehicle Length` with a value label; assert `attributes == listOf(CampsiteAttribute("Max Vehicle Length", "<label>"))`.

`CampflareCampsitesEtlTest`: `description: "<b>Quiet</b> site"` → `"Quiet site"`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.*' --offline -q`
Expected: FAIL (unresolved `HtmlText`, then assertion failures).

- [ ] **Step 3: Implement**

`HtmlText.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.framework

object HtmlText {
    private val tag = Regex("<[^>]*>")
    private val whitespace = Regex("\\s+")

    fun stripTags(value: String): String = value.replace(tag, " ").replace(whitespace, " ").trim()
}
```

rec.gov: replace `buildCampsiteTags`/`recgovAttributeTags` with a `promoteAttributes(raw): PromotedAttributes` returning the typed values plus the residual list per the table (a small private data class; constants for the attribute names as `private const val`s; `YES_VALUES = setOf("yes", "y", "true")`, `NO_VALUES = setOf("no", "n", "false")`; leading integer via `Regex("^\\d+")`). Delete `CampsiteTags.kt` and its only use.

Aspira and Campflare per Interfaces. ReserveCalifornia: replace its inline tag-strip with `HtmlText.stripTags`.

- [ ] **Step 4: Gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS; `grep -rn '_roadtrip_tags\|campsiteTagKey' backend/src` empty.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(etl): promote campsite capacity, description, and attributes on write

rec.gov attributes become typed columns or CampsiteAttribute rows instead of
the _roadtrip_tags payload bag; Aspira and Campflare fill description and
minPeople; HTML is stripped once, in the ETL.

Refs #731 #741

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: V56 dry-run and a data check script (no issue; docs)

**Files:**
- Modify: `docs/backend-architecture.md` (Models: campsite typed columns sentence under the "Typed JSONB columns" bullet)
- Create: `scripts/sql/v56_dry_run.sql`

- [ ] **Step 1: Dry-run query**

```sql
-- Counts the campsite rows V56 will rewrite, per shape, so the deploy can be sized.
SELECT
  count(*) FILTER (WHERE jsonb_typeof(equipment) = 'array' AND EXISTS (SELECT 1 FROM jsonb_array_elements(equipment) e WHERE jsonb_typeof(e) = 'object')) AS equipment_objects,
  count(*) FILTER (WHERE jsonb_typeof(photos) = 'array' AND EXISTS (SELECT 1 FROM jsonb_array_elements(photos) p WHERE p ? 'large_url' OR p ? 'medium_url' OR p ? 'small_url' OR p ? 'original_url')) AS photos_vendor_keys,
  count(*) FILTER (WHERE source_payload ? 'min_capacity' OR source_payload->'_roadtrip_tags' ? 'capacity') AS min_people_backfill,
  count(*) FILTER (WHERE source_payload ? 'description') AS description_backfill,
  count(*) FILTER (WHERE jsonb_typeof(source_payload->'defined_attributes') = 'array' OR jsonb_typeof(source_payload->'_roadtrip_tags'->'attributes') = 'object') AS attributes_backfill
FROM campsites WHERE deleted_at IS NULL;
```

- [ ] **Step 2: Docs sentence**

Under the "Typed JSONB columns" bullet in `docs/backend-architecture.md`, append: "`campsites.equipment`, `photos`, and `attributes` follow the same rule through `CatalogColumnJson`; `CampsiteRepo` is their only codec, and the API serves `CampsiteDto`, never the row."

- [ ] **Step 3: Commit**

```bash
git add docs/backend-architecture.md scripts/sql/v56_dry_run.sql
git commit -m "docs: campsite typed columns and the V56 dry-run query

Refs #731

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Closed `CampsiteDto` on the wire (#731)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/CampsiteDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/PoiCampsitesResponseSchema.kt` (`campsites: List<CampsiteDto>`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt` (`campsite: CampsiteDto?`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CampsiteCatalogService.kt` (map rows), `service/availability/AvailabilityWatchApiMapper.kt` (map `singleCampsite`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campsite.kt` (remove `@Serializable`, `@SerialName`, `@Transient`, the private `InstantIsoStringSerializer`; `dataProviderRef` becomes a plain `val` computed the same way)
- Test: `route/CampsiteRoutesTest.kt` (the `unwrittenCampsiteFields` list gains `min_people`, `description`; assert `source_payload`, `created_at`, `updated_at`, `deleted_at`, `booking_provider_ref`, `schedule`, `price`, `latitude`, `longitude` are absent from every row), `service/availability/CampsiteCatalogServiceTest.kt`, `route/AvailabilityWatchRoutesTest.kt` (any assertion on `watch.campsite.*`), `route/FeatureCollectionContractTest.kt` if it touches campsites, and a new `model/api/CampsiteDtoTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable
  data class CampsiteDto(
      val id: Long,
      @SerialName("campground_id") val campgroundId: Long,
      val name: String,
      val kind: String,
      @SerialName("kind_listed") val kindListed: String? = null,
      @SerialName("loop_name") val loopName: String? = null,
      val description: String? = null,
      @SerialName("min_people") val minPeople: Int? = null,
      @SerialName("max_people") val maxPeople: Int? = null,
      @SerialName("max_cars") val maxCars: Int? = null,
      @SerialName("driveway_length") val drivewayLength: Int? = null,
      @SerialName("max_rv_length") val maxRvLength: Int? = null,
      @SerialName("max_trailer_length") val maxTrailerLength: Double? = null,
      val firepit: Boolean? = null,
      @SerialName("picnic_table") val picnicTable: Boolean? = null,
      @SerialName("ada_accessible") val adaAccessible: Boolean? = null,
      @SerialName("water_hookups") val waterHookups: Boolean? = null,
      @SerialName("electric_hookups") val electricHookups: Boolean? = null,
      @SerialName("sewer_hookups") val sewerHookups: Boolean? = null,
      @SerialName("pull_through") val pullThrough: Boolean? = null,
      val equipment: List<String> = emptyList(),
      val attributes: List<CampsiteAttribute> = emptyList(),
      @SerialName("photo_url") val photoUrl: String? = null,
      @SerialName("data_provider") val dataProvider: String,
      @SerialName("data_provider_ref") val dataProviderRef: String,
      @SerialName("booking_provider") val bookingProvider: String? = null,
  ) { companion object { fun from(row: Campsite): CampsiteDto } }
  ```
  `photoUrl = row.photos.firstOrNull()?.url`; `dataProviderRef = row.dataProviderRefValue`.

- [ ] **Step 1: Failing DTO test**

`model/api/CampsiteDtoTest.kt`: build a `Campsite` row (use `campsiteFixture` from `fixtures/CampsiteFixture.kt` with `photos = listOf(CatalogPhoto("https://x/1.jpg"))`, `attributes = listOf(CampsiteAttribute("Shade", "Partial"))`, `minPeople = 2`), encode `CampsiteDto.from(row)` with the routes' Json (`explicitNulls = false`; find the shared instance in `route/common/RouteResponses.kt`), and assert: `photo_url == "https://x/1.jpg"`, `attributes[0].name == "Shade"`, `min_people == 2`, and the keys `source_payload`, `created_at`, `schedule`, `price`, `booking_provider_ref`, `latitude` are absent.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.api.CampsiteDtoTest' --offline -q`

- [ ] **Step 3: Implement**

`CampsiteDto.kt` per Interfaces with `from(row)`. Swap the two schemas' element types; in `CampsiteCatalogService.campsitesForPoi` keep the `List<Campsite>` for filtering and URL templates and map to `campsites.map(CampsiteDto::from)` at the response; in `AvailabilityWatchApiMapper` map `singleCampsite?.let(CampsiteDto::from)`. Strip serialization from `Campsite` (delete the `kotlinx.serialization` imports it no longer needs and the private serializer object).

- [ ] **Step 4: Fix tests and gate**

Update the tests listed under Files. `CampsiteRoutesTest`'s absent-field assertion:

```kotlin
private val neverOnTheWire = listOf("source_payload", "schedule", "price", "created_at", "updated_at", "deleted_at", "booking_provider_ref", "latitude", "longitude")
```

asserted absent for every campsite object in the response.

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "refactor(api): closed CampsiteDto replaces the serialized campsite row

The campsites list and the watch embed carry the facts the drawer renders and
nothing else; source_payload, timestamps, schedule, and price leave the wire.

Refs #731

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Frontend reads the DTO and deletes its raw readers (#731)

**Files:**
- Modify: `frontend/src/api/campsite-api.ts` (closed `Campsite`)
- Modify: `frontend/src/features/availability/site-detail-facts.ts` (rewrite)
- Modify: `frontend/src/features/availability/site-list-rows.ts` (typed; import `capacityLabel` from site-detail-facts)
- Modify: `frontend/src/features/availability/SiteDetail.tsx`, `SiteList.tsx`, `matrix-rows.ts`, `frontend/src/lib/watch-format.ts` (drop guards; `filterOptions` keyed on `'loop_name' | 'kind'`)
- Test: `site-detail-facts.test.ts`, `site-list-rows.test.ts`, `matrix-rows.test.ts`, `api/campsite-api.test.ts`, `AvailabilityWeek.test.tsx` (the `source_payload` fixture at ~line 815 becomes `description`, `attributes: [{name: 'Type of use', value: 'Overnight'}]`), `features/alerts/alert-rows.test.ts` and `features/watches/WatchTable.test.tsx` only if their `campsite` fixtures need the new required fields.

**Interfaces:**
- Produces, in `campsite-api.ts`:
  ```ts
  export interface CampsiteAttribute { name: string; value?: string | null }
  /** Mirrors CampsiteDto. Closed: every fact the drawer renders is a typed field. */
  export interface Campsite {
    id: number;
    campground_id: number;
    name: string;
    kind: string;
    kind_listed?: string | null;
    loop_name?: string | null;
    description?: string | null;
    min_people?: number | null;
    max_people?: number | null;
    max_cars?: number | null;
    driveway_length?: number | null;
    max_rv_length?: number | null;
    max_trailer_length?: number | null;
    firepit?: boolean | null;
    picnic_table?: boolean | null;
    ada_accessible?: boolean | null;
    water_hookups?: boolean | null;
    electric_hookups?: boolean | null;
    sewer_hookups?: boolean | null;
    pull_through?: boolean | null;
    equipment: string[];
    attributes: CampsiteAttribute[];
    photo_url?: string | null;
    data_provider: string;
    data_provider_ref: string;
    booking_provider?: string | null;
  }
  ```
- `site-detail-facts.ts` exports: `detailFacts(site: Partial<Campsite>): SiteFact[]`, `capacityLabel(site: Partial<Campsite>): string`, `featureLabels(site: Partial<Campsite>): string[]`, `descriptionText(value: string | null | undefined): string`. Everything else in the file is deleted.

- [ ] **Step 1: Rewrite the tests first**

`site-detail-facts.test.ts` becomes tests against DTO rows:

```ts
const site: Partial<Campsite> = {
  id: 1, name: 'Site 12', loop_name: 'Loop A', kind: 'STANDARD NONELECTRIC', kind_listed: 'Standard Nonelectric',
  min_people: 2, max_people: 6, equipment: ['Tent', 'RV', 'Trailer', 'Van', 'Boat'],
  attributes: [{ name: 'Reserve type', value: 'Site-Specific' }, { name: 'Pets allowed' }, { name: 'Shade', value: 'Partial' }],
  firepit: true, picnic_table: false, max_rv_length: 32, description: 'Walk-in tent site by the water.',
  photo_url: 'https://x/1.jpg', data_provider: 'recgov', data_provider_ref: '100',
};
test('detail facts in reading order', () => {
  expect(detailFacts(site)).toEqual([
    { label: 'Loop', value: 'Loop A' },
    { label: 'Type', value: 'Standard Nonelectric' },
    { label: 'Capacity', value: '2-6 people' },
    { label: 'Equipment', value: 'Tent, RV, Trailer, Van' },
    { label: 'Provider', value: 'recgov' },
    { label: 'Provider ID', value: '100' },
  ]);
});
test('capacity phrasing', () => {
  expect(capacityLabel({ max_people: 6 })).toBe('Up to 6 people');
  expect(capacityLabel({ min_people: 2 })).toBe('2+ people');
  expect(capacityLabel({ min_people: 4, max_people: 4 })).toBe('Up to 4 people');
  expect(capacityLabel({})).toBe('');
});
test('feature chips: columns, measurements, then attributes; false is not a chip', () => {
  expect(featureLabels(site)).toEqual(['Firepit', 'Max RV length: 32 ft', 'Reserve type: Site-Specific', 'Pets allowed', 'Shade: Partial']);
});
test('description is clamped to 260 chars', () => { /* keep the existing clamp case, minus the HTML input */ });
```

Delete the image-search, attribute-bag, `formatValue`/`firstString`, and HTML-stripping tests. `site-list-rows.test.ts`: `rowDetails({ min_people: 2, max_people: 6, description: 'By the water' })` → `['Sleeps 2-6', 'By the water']`; the non-object-payload and `<p>` cases are deleted; the 120-char clamp stays (plain text input). `matrix-rows.test.ts`: fixtures gain the required fields; assertions unchanged. `campsite-api.test.ts`: the fixture becomes a full DTO row.

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm test -- site-detail-facts site-list-rows`
Expected: type errors / failing assertions.

- [ ] **Step 3: Implement**

`site-detail-facts.ts` (whole file):

```ts
// Facts a camper cares about, read from the typed catalog row.
import type { Campsite } from '@/api/campsite-api';

const MAX_FEATURES = 12;
const MAX_DESCRIPTION_CHARS = 260;
const MAX_EQUIPMENT_ITEMS = 4;

export interface SiteFact { label: string; value: string }

export function detailFacts(site: Partial<Campsite>): SiteFact[] {
  const facts: SiteFact[] = [];
  const add = (label: string, value: string | null | undefined): void => {
    const text = compactText(value);
    if (text) facts.push({ label, value: text });
  };
  add('Loop', site.loop_name);
  add('Type', site.kind_listed ?? site.kind);
  add('Capacity', capacityLabel(site));
  add('Equipment', (site.equipment ?? []).slice(0, MAX_EQUIPMENT_ITEMS).join(', '));
  add('Provider', site.data_provider);
  add('Provider ID', site.data_provider_ref);
  return facts;
}

/** "4-6 people" / "Up to 6 people" / "2+ people" — three different claims. */
export function capacityLabel(site: Partial<Campsite>): string {
  const min = site.min_people ?? null;
  const max = site.max_people ?? null;
  if (min != null && max != null && min !== max) return `${min}-${max} people`;
  if (max != null) return `Up to ${max} people`;
  if (min != null) return `${min}+ people`;
  return '';
}

/** Boolean columns, then measurements, then the provider's named attributes. Only `true` is a chip. */
export function featureLabels(site: Partial<Campsite>): string[] {
  const labels: string[] = [];
  const flag = (label: string, value: boolean | null | undefined): void => { if (value === true) labels.push(label); };
  const measure = (label: string, value: string): void => { if (value) labels.push(`${label}: ${value}`); };
  flag('Firepit', site.firepit);
  flag('Picnic table', site.picnic_table);
  flag('ADA accessible', site.ada_accessible);
  flag('Water hookups', site.water_hookups);
  flag('Electric hookups', site.electric_hookups);
  flag('Sewer hookups', site.sewer_hookups);
  flag('Pull-through', site.pull_through);
  measure('Max cars', site.max_cars != null ? String(site.max_cars) : '');
  measure('Driveway length', lengthLabel(site.driveway_length));
  measure('Max RV length', lengthLabel(site.max_rv_length));
  measure('Max trailer length', lengthLabel(site.max_trailer_length));
  for (const attribute of site.attributes ?? []) {
    const value = compactText(attribute.value);
    labels.push(value ? `${attribute.name}: ${value}` : attribute.name);
  }
  return unique(labels).slice(0, MAX_FEATURES);
}

export function descriptionText(value: string | null | undefined): string {
  const text = compactText(value);
  if (!text) return '';
  return text.length > MAX_DESCRIPTION_CHARS ? `${text.slice(0, MAX_DESCRIPTION_CHARS - 3).trim()}...` : text;
}

function lengthLabel(feet: number | null | undefined): string { return feet == null ? '' : `${feet} ft`; }
function compactText(value: string | null | undefined): string { return value ? value.replace(/\s+/g, ' ').trim() : ''; }
function unique(values: readonly string[]): string[] { /* same case-insensitive dedupe as today */ }
```

`SiteDetail.tsx`: `imageUrl = site.photo_url ?? ''`, `description = descriptionText(site.description)`, `facts = detailFacts(site)`, `features = featureLabels(site)`; delete the `rawPayload` import. `site-list-rows.ts`: `rowDetails(row)` = `[sleepsLabel(row), descriptionSummary(row.description)]` where `sleepsLabel` derives from the same `min_people`/`max_people` with the `Sleeps` wording; delete its `raw` parameter, the six-spelling reader, and the tag strip. `SiteList.tsx`: `row.loop_name` and `row.kind` render without `String()`. `matrix-rows.ts`: `siteTitleText` uses `row.loop_name?.trim()`; `filterOptions(rows, key: 'loop_name' | 'kind')`; the haystack drops the `filter(Boolean)` cast noise where types now allow. `watch-format.ts`: `site?.name` / `site?.loop_name` without `typeof` guards.

- [ ] **Step 4: Gate**

Run: `cd frontend && npm run typecheck && npm test && npm run lint`
Expected: PASS; `grep -rn 'source_payload' frontend/src` returns nothing outside `api/*.test.ts` fixtures that are gone too (target: zero hits).

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "refactor(frontend): campsite facts from the closed DTO

The Campsite type mirrors CampsiteDto with no index signature; the drawer's
raw-payload readers (six capacity spellings, attribute bags, the photo search,
HTML stripping) are deleted.

Refs #731

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Frontend docs

**Files:**
- Modify: `docs/frontend-components.md` (one paragraph after "Domain components": the `Campsite` type is the closed mirror of `CampsiteDto`; a fact the drawer needs is promoted by the ETL and added to the DTO, never read from a payload)

- [ ] **Step 1: Add the paragraph and commit**

```bash
git add docs/frontend-components.md
git commit -m "docs: the campsite type is closed; facts are promoted, not scraped

Refs #731

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
