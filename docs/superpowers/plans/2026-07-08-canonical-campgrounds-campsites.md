# Canonical Campgrounds and Campsites Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current polymorphic `pois` plus generic `reservables` catalog with canonical `campgrounds` and `campsites` tables, vendor reference lookup tables, and POI wrapper joins fed by Campflare plus Canada sources.

**Architecture:** `pois` becomes the spatial wrapper with lifecycle metadata only. Real domain data lives in typed tables (`campgrounds`, `campsites`, `tesla_superchargers`, `planet_fitness_locations`) and is connected to POIs through typed join tables. `vendor_refs` plus entity-ref join tables map upstream IDs from Campflare, Parks Canada, BC Parks, Alberta Parks, Aspira, ReserveAmerica, ReserveCalifornia, and future vendors to canonical internal IDs without doing cross-source merge or fuzzy matching in v1.

**Tech Stack:** Kotlin, Ktor, jOOQ, PostgreSQL/PostGIS, Flyway migrations, kotlinx.serialization, existing ETL registry and Python raw fetcher envelope helpers.

---

## Scope Decisions

- Build new canonical tables and route/repo code beside the current tables.
- Do not migrate old `pois` or `reservables` data. This is an intentional destructive catalog reset for local/dev databases and any rebuildable environment.
- Do not soft-delete old rows as part of this plan.
- Do not merge Campflare with old US raw RecGov/ReserveAmerica/ReserveCalifornia/Aspira data.
- Do not solve cross-vendor campsite identity resolution in v1.
- Use deterministic vendor refs only. If a source owns a row, that source writes the canonical row and its vendor refs.
- V1 owning campground/site sources are Campflare for US coverage and Canada ETLs for Canadian coverage.

## File Structure

- `backend/src/main/resources/db/migration/V38__canonical_catalog.sql`
  - Create canonical catalog schema: `pois`, `campgrounds`, `campsites`, typed POI join tables, `vendor_refs`, and entity-ref link tables.
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt`
  - Schema safety tests for required tables, FKs, unique constraints, and lookup indexes.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/catalog/CatalogModels.kt`
  - Kotlin domain DTOs used by repos and ETL outputs.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/VendorRefRepo.kt`
  - Upsert and lookup upstream vendor refs.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/CampgroundCatalogRepo.kt`
  - Upsert campgrounds, link vendor refs, and resolve campground by vendor ref.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/CampsiteCatalogRepo.kt`
  - Upsert campsites, link vendor refs, and resolve campsite by vendor ref.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/TeslaSuperchargerCatalogRepo.kt`
  - Upsert typed Tesla Supercharger rows, link Tesla vendor refs, and resolve superchargers by Tesla slug.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/PoiCatalogRepo.kt`
  - Upsert POI wrappers and typed POI join rows.
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/catalog/*Test.kt`
  - Repo tests for upsert idempotency, vendor ref lookup, and POI wrapper joins.
- `scripts/fetch_campflare_dump.py`
  - Fetch Campflare dump manifest and stream gzip JSONL into multipart envelopes.
- `scripts/test_fetch_campflare_dump.py`
  - Unit tests for manifest selection, gzip splitting, and envelope shape.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CatalogEtlOutput.kt`
  - New terminal ETL output for typed catalog rows.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt`
  - Dispatch terminal `CatalogEtlOutput` rows to catalog repos.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistry.kt`
  - Add `data_type` to ETL rows so the registry declares whether a terminal emits campgrounds, campsites, Tesla, Planet Fitness, or legacy rows.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtl.kt`
  - Parse Campflare campground dump records into canonical campground rows.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampsitesEtl.kt`
  - Parse Campflare campsite dump records into canonical campsite rows.
- `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/*Test.kt`
  - Fixture tests for Campflare mapping.
- `config/poi-registry.yaml`
  - Add Campflare dump sources and mark US legacy ETL rows disabled.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/PoiSchemas.kt`
  - Add typed POI wrapper response DTOs with joined `data`.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`
  - Read POIs through typed joins and return wrapper rows.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/PoiRoutes.kt`
  - Return new wrapper response shape.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/OnRoutePoiRepo.kt`
  - Read on-route POIs through typed joins.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt`
  - Deprecate old reservable routes or leave them disabled behind no-op responses after the frontend stops calling them.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/reservation/*`
  - Resolve availability targets through `vendor_refs` instead of `ReservableId`.
- `web/availability/*`
  - Replace `rid` usage with canonical numeric `campsite_id`.

---

### Task 1: Add Canonical Catalog Schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V38__canonical_catalog.sql`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt`

- [ ] **Step 1: Write the failing schema test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalCatalogSchemaTest {
    @Test
    fun `canonical catalog tables exist`() {
        SharedTestDb.withDb { ctx ->
            val tables =
                ctx.fetch(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'pois',
                        'campgrounds',
                        'campsites',
                        'vendor_refs',
                        'campground_vendor_refs',
                        'campsite_vendor_refs',
                        'poi_campgrounds',
                        'tesla_superchargers',
                        'poi_tesla_superchargers',
                        'planet_fitness_locations',
                        'poi_planet_fitness_locations'
                      )
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("table_name", String::class.java) }

            assertEquals(
                listOf(
                    "campground_vendor_refs",
                    "campgrounds",
                    "campsite_vendor_refs",
                    "campsites",
                    "planet_fitness_locations",
                    "poi_campgrounds",
                    "poi_planet_fitness_locations",
                    "poi_tesla_superchargers",
                    "pois",
                    "tesla_superchargers",
                    "vendor_refs",
                ),
                tables,
            )
        }
    }

    @Test
    fun `tesla superchargers table has typed operational columns`() {
        SharedTestDb.withDb { ctx ->
            val columns =
                ctx.fetch(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'tesla_superchargers'
                      AND column_name IN (
                        'location_slug',
                        'location_guid',
                        'common_site_name',
                        'site_status',
                        'access_type',
                        'open_to_public',
                        'open_to_non_teslas',
                        'trailer_friendly',
                        'twenty_four_seven',
                        'stall_count',
                        'max_power_kw',
                        'address',
                        'region',
                        'country',
                        'time_zone',
                        'amenities',
                        'hardware_counts',
                        'pricebooks',
                        'availability_profile',
                        'info_url',
                        'index_payload',
                        'detail_payload'
                      )
                    ORDER BY column_name
                    """.trimIndent(),
                ).map { it.get("column_name", String::class.java) }

            assertEquals(
                listOf(
                    "access_type",
                    "address",
                    "amenities",
                    "availability_profile",
                    "common_site_name",
                    "country",
                    "detail_payload",
                    "hardware_counts",
                    "index_payload",
                    "info_url",
                    "location_guid",
                    "location_slug",
                    "max_power_kw",
                    "open_to_non_teslas",
                    "open_to_public",
                    "pricebooks",
                    "region",
                    "site_status",
                    "stall_count",
                    "time_zone",
                    "trailer_friendly",
                    "twenty_four_seven",
                ),
                columns,
            )
        }
    }

    @Test
    fun `vendor refs are unique per vendor entity and external id`() {
        SharedTestDb.withDb { ctx ->
            val constraintCount =
                ctx.fetchOne(
                    """
                    SELECT COUNT(*) AS n
                    FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND tablename = 'vendor_refs'
                      AND indexname = 'vendor_refs_vendor_entity_external_uidx'
                    """.trimIndent(),
                )!!.get("n", Number::class.java).toInt()

            assertEquals(1, constraintCount)
        }
    }
}
```

- [ ] **Step 2: Run the schema test and verify it fails**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.repo.CanonicalCatalogSchemaTest
```

Expected: FAIL because the new tables do not exist.

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V38__canonical_catalog.sql`:

```sql
-- Intentional catalog reset: the old polymorphic POI/reservable model is being replaced.
-- Do not run this against a production database unless the release plan explicitly allows
-- dropping and rebuilding catalog data from source ETLs.
DROP TABLE IF EXISTS reservable_pois CASCADE;
DROP TABLE IF EXISTS reservables CASCADE;
DROP TABLE IF EXISTS pois CASCADE;

CREATE TABLE vendor_refs (
  id            BIGSERIAL PRIMARY KEY,
  vendor        TEXT NOT NULL,
  entity_type   TEXT NOT NULL,
  external_id   TEXT NOT NULL,
  payload       JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (vendor ~ '^[a-z0-9_:-]+$'),
  CHECK (entity_type IN ('campground', 'campsite', 'tesla_supercharger', 'planet_fitness_location')),
  CHECK (external_id <> '')
);

CREATE UNIQUE INDEX vendor_refs_vendor_entity_external_uidx
  ON vendor_refs (vendor, entity_type, external_id);

CREATE TABLE campgrounds (
  id              BIGSERIAL PRIMARY KEY,
  name            TEXT NOT NULL,
  status          TEXT,
  kind            TEXT,
  agency          TEXT,
  management      JSONB NOT NULL DEFAULT '{}'::jsonb,
  address         JSONB NOT NULL DEFAULT '{}'::jsonb,
  region          TEXT,
  country         CHAR(2),
  phone           TEXT,
  email           TEXT,
  info_url        TEXT,
  reservation_url TEXT,
  amenities       JSONB NOT NULL DEFAULT '[]'::jsonb,
  connections     JSONB NOT NULL DEFAULT '{}'::jsonb,
  photos          JSONB NOT NULL DEFAULT '[]'::jsonb,
  price           JSONB NOT NULL DEFAULT '{}'::jsonb,
  schedule        JSONB NOT NULL DEFAULT '{}'::jsonb,
  source_payload  JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);

CREATE TABLE campsites (
  id                    BIGSERIAL PRIMARY KEY,
  campground_id         BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  name                  TEXT,
  kind                  TEXT,
  kind_listed           TEXT,
  loop_name             TEXT,
  latitude              DOUBLE PRECISION,
  longitude             DOUBLE PRECISION,
  max_people            INTEGER,
  max_cars              INTEGER,
  driveway_length       DOUBLE PRECISION,
  max_rv_length         DOUBLE PRECISION,
  max_trailer_length    DOUBLE PRECISION,
  electric_hookups      BOOLEAN,
  water_hookups         BOOLEAN,
  sewer_hookups         BOOLEAN,
  ada_accessible        BOOLEAN,
  pull_through          BOOLEAN,
  firepit               BOOLEAN,
  picnic_table          BOOLEAN,
  equipment             JSONB NOT NULL DEFAULT '[]'::jsonb,
  price                 JSONB NOT NULL DEFAULT '{}'::jsonb,
  photos                JSONB NOT NULL DEFAULT '[]'::jsonb,
  schedule              JSONB NOT NULL DEFAULT '{}'::jsonb,
  reservation_url       TEXT,
  source_payload        JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at            TIMESTAMPTZ
);

CREATE TABLE campground_vendor_refs (
  campground_id BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  vendor_ref_id BIGINT NOT NULL REFERENCES vendor_refs(id) ON DELETE CASCADE,
  is_primary    BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campground_id, vendor_ref_id)
);

CREATE UNIQUE INDEX campground_vendor_refs_primary_uidx
  ON campground_vendor_refs (campground_id)
  WHERE is_primary;

CREATE TABLE campsite_vendor_refs (
  campsite_id    BIGINT NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  vendor_ref_id  BIGINT NOT NULL REFERENCES vendor_refs(id) ON DELETE CASCADE,
  is_primary     BOOLEAN NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campsite_id, vendor_ref_id)
);

CREATE UNIQUE INDEX campsite_vendor_refs_primary_uidx
  ON campsite_vendor_refs (campsite_id)
  WHERE is_primary;

CREATE TABLE pois (
  id          BIGSERIAL PRIMARY KEY,
  poi_type    TEXT NOT NULL,
  geom        geometry(Geometry, 4326) NOT NULL,
  metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at  TIMESTAMPTZ,
  CHECK (poi_type IN ('campground', 'tesla_supercharger', 'planet_fitness_location'))
);

CREATE INDEX pois_active_type_idx ON pois (poi_type) WHERE deleted_at IS NULL;
CREATE INDEX pois_geom_gix ON pois USING GIST (geom);

CREATE TABLE poi_campgrounds (
  poi_id        BIGINT PRIMARY KEY REFERENCES pois(id) ON DELETE CASCADE,
  campground_id BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (campground_id)
);

CREATE TABLE tesla_superchargers (
  id                   BIGSERIAL PRIMARY KEY,
  name                 TEXT NOT NULL,
  location_slug        TEXT NOT NULL,
  location_guid        TEXT,
  common_site_name     TEXT,
  site_status          TEXT,
  access_type          TEXT,
  open_to_public       BOOLEAN,
  open_to_non_teslas   BOOLEAN,
  trailer_friendly     BOOLEAN,
  twenty_four_seven    BOOLEAN,
  stall_count          INTEGER NOT NULL DEFAULT 0,
  max_power_kw         INTEGER NOT NULL DEFAULT 0,
  address              JSONB NOT NULL DEFAULT '{}'::jsonb,
  region               TEXT,
  country              CHAR(2),
  time_zone            TEXT,
  amenities            JSONB NOT NULL DEFAULT '[]'::jsonb,
  hardware_counts      JSONB NOT NULL DEFAULT '{}'::jsonb,
  pricebooks           JSONB NOT NULL DEFAULT '[]'::jsonb,
  availability_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
  info_url             TEXT,
  index_payload        JSONB NOT NULL DEFAULT '{}'::jsonb,
  detail_payload       JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at           TIMESTAMPTZ,
  CHECK (location_slug <> ''),
  CHECK (stall_count >= 0),
  CHECK (max_power_kw >= 0)
);

CREATE UNIQUE INDEX tesla_superchargers_location_slug_uidx
  ON tesla_superchargers (location_slug);

CREATE INDEX tesla_superchargers_active_country_idx
  ON tesla_superchargers (country, region)
  WHERE deleted_at IS NULL;

CREATE TABLE poi_tesla_superchargers (
  poi_id          BIGINT PRIMARY KEY REFERENCES pois(id) ON DELETE CASCADE,
  supercharger_id BIGINT NOT NULL REFERENCES tesla_superchargers(id) ON DELETE CASCADE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (supercharger_id)
);

CREATE TABLE planet_fitness_locations (
  id             BIGSERIAL PRIMARY KEY,
  name           TEXT NOT NULL,
  source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at     TIMESTAMPTZ
);

CREATE TABLE poi_planet_fitness_locations (
  poi_id      BIGINT PRIMARY KEY REFERENCES pois(id) ON DELETE CASCADE,
  location_id BIGINT NOT NULL REFERENCES planet_fitness_locations(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (location_id)
);
```

- [ ] **Step 4: Run the schema test and compile generation**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.repo.CanonicalCatalogSchemaTest
```

Expected: PASS.

Run:

```bash
./gradlew --no-daemon compileKotlin
```

Expected: PASS and jOOQ generated classes include the new tables.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V38__canonical_catalog.sql backend/src/test/kotlin/ca/floo/roadtrip/repo/CanonicalCatalogSchemaTest.kt
git commit -m "feat: add canonical catalog schema"
```

---

### Task 2: Add Catalog Domain Models

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/models/catalog/CatalogModels.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/models/catalog/CatalogModelsTest.kt`

- [ ] **Step 1: Write the model test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/models/catalog/CatalogModelsTest.kt`:

```kotlin
package ca.floo.roadtrip.models.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogModelsTest {
    @Test
    fun `vendor ref input carries source identity`() {
        val ref =
            VendorRefInput(
                vendor = CatalogVendor.CAMPFLARE,
                entityType = CatalogEntityType.CAMPSITE,
                externalId = "5f65dc5c-30e2-4531-8fba-82bdcaa17e1a",
                payload = buildJsonObject { put("campground_id", "calvert-cliffs-youth-mysp") },
            )

        assertEquals("campflare", ref.vendor.wireValue)
        assertEquals("campsite", ref.entityType.wireValue)
        assertEquals("5f65dc5c-30e2-4531-8fba-82bdcaa17e1a", ref.externalId)
        assertEquals(
            """{"campground_id":"calvert-cliffs-youth-mysp"}""",
            Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ref.payload),
        )
    }
}
```

- [ ] **Step 2: Run the model test and verify it fails**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.models.catalog.CatalogModelsTest
```

Expected: FAIL because `CatalogModels.kt` does not exist.

- [ ] **Step 3: Create the model file**

Create `backend/src/main/kotlin/ca/floo/roadtrip/models/catalog/CatalogModels.kt`:

```kotlin
package ca.floo.roadtrip.models.catalog

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

enum class CatalogVendor(
    val wireValue: String,
) {
    CAMPFLARE("campflare"),
    PARKS_CANADA("parks_canada"),
    BC_PARKS("bc_parks"),
    ALBERTA_PARKS("alberta_parks"),
    ASPIRA_PC("aspira_pc"),
    ASPIRA_BC("aspira_bc"),
    ASPIRA_WA("aspira_wa"),
    RESERVEAMERICA("reserveamerica"),
    RESERVECALIFORNIA("reservecalifornia"),
    RECGOV("recgov"),
    TESLA("tesla"),
    PLANET_FITNESS("planet_fitness"),
}

enum class CatalogEntityType(
    val wireValue: String,
) {
    CAMPGROUND("campground"),
    CAMPSITE("campsite"),
    TESLA_SUPERCHARGER("tesla_supercharger"),
    PLANET_FITNESS_LOCATION("planet_fitness_location"),
}

data class VendorRefInput(
    val vendor: CatalogVendor,
    val entityType: CatalogEntityType,
    val externalId: String,
    val payload: JsonElement = buildJsonObject { },
)

data class CampgroundInput(
    val primaryRef: VendorRefInput,
    val name: String,
    val status: String?,
    val kind: String?,
    val agency: String?,
    val management: JsonElement,
    val address: JsonElement,
    val region: String?,
    val country: String?,
    val phone: String?,
    val email: String?,
    val infoUrl: String?,
    val reservationUrl: String?,
    val amenities: JsonElement,
    val connections: JsonElement,
    val photos: JsonElement,
    val price: JsonElement,
    val schedule: JsonElement,
    val sourcePayload: JsonObject,
    val latitude: Double,
    val longitude: Double,
)

data class CampsiteInput(
    val primaryRef: VendorRefInput,
    val campgroundRef: VendorRefInput,
    val name: String?,
    val kind: String?,
    val kindListed: String?,
    val loopName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val maxPeople: Int?,
    val maxCars: Int?,
    val drivewayLength: Double?,
    val maxRvLength: Double?,
    val maxTrailerLength: Double?,
    val electricHookups: Boolean?,
    val waterHookups: Boolean?,
    val sewerHookups: Boolean?,
    val adaAccessible: Boolean?,
    val pullThrough: Boolean?,
    val firepit: Boolean?,
    val picnicTable: Boolean?,
    val equipment: JsonElement,
    val price: JsonElement,
    val photos: JsonElement,
    val schedule: JsonElement,
    val reservationUrl: String?,
    val sourcePayload: JsonObject,
)

data class TeslaSuperchargerInput(
    val primaryRef: VendorRefInput,
    val name: String,
    val locationSlug: String,
    val locationGuid: String?,
    val commonSiteName: String?,
    val siteStatus: String?,
    val accessType: String?,
    val openToPublic: Boolean?,
    val openToNonTeslas: Boolean?,
    val trailerFriendly: Boolean?,
    val twentyFourSeven: Boolean?,
    val stallCount: Int,
    val maxPowerKw: Int,
    val address: JsonElement,
    val region: String?,
    val country: String?,
    val timeZone: String?,
    val amenities: JsonElement,
    val hardwareCounts: JsonElement,
    val pricebooks: JsonElement,
    val availabilityProfile: JsonElement,
    val infoUrl: String?,
    val indexPayload: JsonObject,
    val detailPayload: JsonObject,
)

data class PoiWrapperInput(
    val poiType: CatalogEntityType,
    val latitude: Double,
    val longitude: Double,
    val metadata: JsonElement,
)
```

- [ ] **Step 4: Run the model test**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.models.catalog.CatalogModelsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/catalog/CatalogModels.kt backend/src/test/kotlin/ca/floo/roadtrip/models/catalog/CatalogModelsTest.kt
git commit -m "feat: add canonical catalog models"
```

---

### Task 3: Add Vendor Ref and Catalog Repositories

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/VendorRefRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/CampgroundCatalogRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/CampsiteCatalogRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/TeslaSuperchargerCatalogRepo.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/PoiCatalogRepo.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/repo/catalog/CatalogRepoTest.kt`

- [ ] **Step 1: Write repo tests**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/catalog/CatalogRepoTest.kt`:

```kotlin
package ca.floo.roadtrip.repo.catalog

import ca.floo.roadtrip.models.catalog.CampgroundInput
import ca.floo.roadtrip.models.catalog.CampsiteInput
import ca.floo.roadtrip.models.catalog.CatalogEntityType
import ca.floo.roadtrip.models.catalog.CatalogVendor
import ca.floo.roadtrip.models.catalog.PoiWrapperInput
import ca.floo.roadtrip.models.catalog.TeslaSuperchargerInput
import ca.floo.roadtrip.models.catalog.VendorRefInput
import ca.floo.roadtrip.repo.SharedTestDb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CatalogRepoTest {
    @Test
    fun `campground and campsite resolve by vendor refs`() {
        SharedTestDb.withDb { ctx ->
            val vendorRefs = VendorRefRepo(ctx)
            val campgrounds = CampgroundCatalogRepo(ctx, vendorRefs)
            val campsites = CampsiteCatalogRepo(ctx, vendorRefs, campgrounds)
            val pois = PoiCatalogRepo(ctx)

            val campgroundRef =
                VendorRefInput(
                    vendor = CatalogVendor.CAMPFLARE,
                    entityType = CatalogEntityType.CAMPGROUND,
                    externalId = "calvert-cliffs-youth-mysp",
                )
            val campgroundId =
                campgrounds.upsert(
                    CampgroundInput(
                        primaryRef = campgroundRef,
                        name = "Calvert Cliffs Youth Campground",
                        status = "closed",
                        kind = null,
                        agency = "Maryland State Parks",
                        management = buildJsonObject {},
                        address = buildJsonObject {},
                        region = "MD",
                        country = "US",
                        phone = null,
                        email = null,
                        infoUrl = null,
                        reservationUrl = "https://parkreservations.maryland.gov/create-booking/results",
                        amenities = buildJsonArray {},
                        connections = buildJsonObject {},
                        photos = buildJsonArray {},
                        price = buildJsonObject {},
                        schedule = buildJsonObject {},
                        sourcePayload = JsonObject(emptyMap()),
                        latitude = 38.40831195138031,
                        longitude = -76.41588657431205,
                    ),
                )

            val campsiteRef =
                VendorRefInput(
                    vendor = CatalogVendor.CAMPFLARE,
                    entityType = CatalogEntityType.CAMPSITE,
                    externalId = "5f65dc5c-30e2-4531-8fba-82bdcaa17e1a",
                )
            val campsiteId =
                campsites.upsert(
                    CampsiteInput(
                        primaryRef = campsiteRef,
                        campgroundRef = campgroundRef,
                        name = "6",
                        kind = "standard",
                        kindListed = null,
                        loopName = null,
                        latitude = null,
                        longitude = null,
                        maxPeople = null,
                        maxCars = null,
                        drivewayLength = null,
                        maxRvLength = null,
                        maxTrailerLength = null,
                        electricHookups = null,
                        waterHookups = null,
                        sewerHookups = null,
                        adaAccessible = null,
                        pullThrough = null,
                        firepit = null,
                        picnicTable = null,
                        equipment = buildJsonArray {},
                        price = buildJsonObject {},
                        photos = buildJsonArray {},
                        schedule = buildJsonObject {},
                        reservationUrl = null,
                        sourcePayload = JsonObject(emptyMap()),
                    ),
                )

            val poiId =
                pois.upsertCampgroundPoi(
                    campgroundId = campgroundId,
                    input =
                        PoiWrapperInput(
                            poiType = CatalogEntityType.CAMPGROUND,
                            latitude = 38.40831195138031,
                            longitude = -76.41588657431205,
                            metadata = buildJsonObject {},
                        ),
                )

            assertEquals(campgroundId, campgrounds.findIdByVendorRef(CatalogVendor.CAMPFLARE, "calvert-cliffs-youth-mysp"))
            assertEquals(campsiteId, campsites.findIdByVendorRef(CatalogVendor.CAMPFLARE, "5f65dc5c-30e2-4531-8fba-82bdcaa17e1a"))
            assertNotNull(poiId)
        }
    }

    @Test
    fun `tesla supercharger resolves by vendor ref and joins to poi wrapper`() {
        SharedTestDb.withDb { ctx ->
            val vendorRefs = VendorRefRepo(ctx)
            val superchargers = TeslaSuperchargerCatalogRepo(ctx, vendorRefs)
            val pois = PoiCatalogRepo(ctx)

            val ref =
                VendorRefInput(
                    vendor = CatalogVendor.TESLA,
                    entityType = CatalogEntityType.TESLA_SUPERCHARGER,
                    externalId = "westhartfordsupercharger",
                )
            val superchargerId =
                superchargers.upsert(
                    TeslaSuperchargerInput(
                        primaryRef = ref,
                        name = "West Hartford, CT",
                        locationSlug = "westhartfordsupercharger",
                        locationGuid = null,
                        commonSiteName = "Corbins Corner Shopping Center",
                        siteStatus = "open",
                        accessType = "Public",
                        openToPublic = true,
                        openToNonTeslas = false,
                        trailerFriendly = false,
                        twentyFourSeven = true,
                        stallCount = 8,
                        maxPowerKw = 150,
                        address =
                            buildJsonObject {
                                put("city", JsonPrimitive("West Hartford"))
                                put("state", JsonPrimitive("CT"))
                                put("countryCode", JsonPrimitive("US"))
                            },
                        region = "CT",
                        country = "US",
                        timeZone = "America/New_York",
                        amenities =
                            buildJsonArray {
                                add(JsonPrimitive("AMENITIES_RESTROOMS"))
                            },
                        hardwareCounts = buildJsonObject {},
                        pricebooks = buildJsonArray {},
                        availabilityProfile = buildJsonObject {},
                        infoUrl = "https://www.tesla.com/findus?location=westhartfordsupercharger",
                        indexPayload = JsonObject(emptyMap()),
                        detailPayload = JsonObject(emptyMap()),
                    ),
                )

            val poiId =
                pois.upsertTeslaSuperchargerPoi(
                    superchargerId = superchargerId,
                    input =
                        PoiWrapperInput(
                            poiType = CatalogEntityType.TESLA_SUPERCHARGER,
                            latitude = 41.72603,
                            longitude = -72.76248,
                            metadata = buildJsonObject {},
                        ),
                )

            assertEquals(superchargerId, superchargers.findIdByVendorRef(CatalogVendor.TESLA, "westhartfordsupercharger"))
            assertNotNull(poiId)
        }
    }
}
```

- [ ] **Step 2: Run repo tests and verify they fail**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.repo.catalog.CatalogRepoTest
```

Expected: FAIL because repo classes do not exist.

- [ ] **Step 3: Implement `VendorRefRepo`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/VendorRefRepo.kt`:

```kotlin
package ca.floo.roadtrip.repo.catalog

import ca.floo.roadtrip.db.generated.tables.VendorRefs.Companion.VENDOR_REFS
import ca.floo.roadtrip.models.catalog.CatalogEntityType
import ca.floo.roadtrip.models.catalog.CatalogVendor
import ca.floo.roadtrip.models.catalog.VendorRefInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL

class VendorRefRepo(
    private val ctx: DSLContext,
) {
    fun upsert(input: VendorRefInput): Long {
        val payload = JSONB.valueOf(json.encodeToString(JsonElement.serializer(), input.payload))
        return ctx
            .insertInto(VENDOR_REFS)
            .set(VENDOR_REFS.VENDOR, input.vendor.wireValue)
            .set(VENDOR_REFS.ENTITY_TYPE, input.entityType.wireValue)
            .set(VENDOR_REFS.EXTERNAL_ID, input.externalId)
            .set(VENDOR_REFS.PAYLOAD, payload)
            .onConflict(VENDOR_REFS.VENDOR, VENDOR_REFS.ENTITY_TYPE, VENDOR_REFS.EXTERNAL_ID)
            .doUpdate()
            .set(VENDOR_REFS.PAYLOAD, DSL.excluded(VENDOR_REFS.PAYLOAD))
            .set(VENDOR_REFS.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning(VENDOR_REFS.ID)
            .fetchOne()!!
            .id!!
    }

    fun findId(
        vendor: CatalogVendor,
        entityType: CatalogEntityType,
        externalId: String,
    ): Long? =
        ctx
            .select(VENDOR_REFS.ID)
            .from(VENDOR_REFS)
            .where(VENDOR_REFS.VENDOR.eq(vendor.wireValue))
            .and(VENDOR_REFS.ENTITY_TYPE.eq(entityType.wireValue))
            .and(VENDOR_REFS.EXTERNAL_ID.eq(externalId))
            .fetchOne(VENDOR_REFS.ID)

    private companion object {
        val json = Json { explicitNulls = false }
    }
}
```

- [ ] **Step 4: Implement catalog repos**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog/CampgroundCatalogRepo.kt`, `CampsiteCatalogRepo.kt`, `TeslaSuperchargerCatalogRepo.kt`, and `PoiCatalogRepo.kt` with upsert methods matching the tests. Use jOOQ generated tables, `onConflict(...).doUpdate()`, and return canonical IDs. All SQL and jOOQ table references stay in these repo classes.

Required method signatures:

```kotlin
class CampgroundCatalogRepo(
    private val ctx: DSLContext,
    private val vendorRefs: VendorRefRepo,
) {
    fun upsert(input: CampgroundInput): Long
    fun findIdByVendorRef(vendor: CatalogVendor, externalId: String): Long?
}

class CampsiteCatalogRepo(
    private val ctx: DSLContext,
    private val vendorRefs: VendorRefRepo,
    private val campgrounds: CampgroundCatalogRepo,
) {
    fun upsert(input: CampsiteInput): Long
    fun findIdByVendorRef(vendor: CatalogVendor, externalId: String): Long?
}

class TeslaSuperchargerCatalogRepo(
    private val ctx: DSLContext,
    private val vendorRefs: VendorRefRepo,
) {
    fun upsert(input: TeslaSuperchargerInput): Long
    fun findIdByVendorRef(vendor: CatalogVendor, externalId: String): Long?
}

class PoiCatalogRepo(
    private val ctx: DSLContext,
) {
    fun upsertCampgroundPoi(campgroundId: Long, input: PoiWrapperInput): Long
    fun upsertTeslaSuperchargerPoi(superchargerId: Long, input: PoiWrapperInput): Long
}
```

Implementation details:

```kotlin
private fun pointGeometry(
    longitude: Double,
    latitude: Double,
): org.jooq.Field<org.jooq.Geometry> =
    org.jooq.impl.DSL.field(
        "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)",
        org.jooq.impl.SQLDataType.GEOMETRY,
        org.jooq.impl.DSL.value(longitude),
        org.jooq.impl.DSL.value(latitude),
    )
```

- [ ] **Step 5: Run repo tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.repo.catalog.CatalogRepoTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/catalog backend/src/test/kotlin/ca/floo/roadtrip/repo/catalog
git commit -m "feat: add canonical catalog repositories"
```

---

### Task 4: Add Campflare Dump Fetcher

**Files:**
- Create: `scripts/fetch_campflare_dump.py`
- Create: `scripts/test_fetch_campflare_dump.py`
- Modify: `config/poi-registry.yaml`

- [ ] **Step 1: Write fetcher tests**

Create `scripts/test_fetch_campflare_dump.py`:

```python
import gzip
import json
import tempfile
import unittest
from pathlib import Path

from fetch_campflare_dump import iter_jsonl_gzip, part_name


class CampflareDumpFetcherTest(unittest.TestCase):
    def test_iter_jsonl_gzip_reads_objects(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "sample.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as f:
                f.write(json.dumps({"id": "a"}) + "\n")
                f.write(json.dumps({"id": "b"}) + "\n")

            rows = list(iter_jsonl_gzip(path))

        self.assertEqual([{"id": "a"}, {"id": "b"}], rows)

    def test_part_name_is_stable_and_padded(self):
        self.assertEqual("part-000001", part_name(1))
        self.assertEqual("part-001234", part_name(1234))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run fetcher tests and verify they fail**

Run:

```bash
python3 -m unittest scripts/test_fetch_campflare_dump.py
```

Expected: FAIL because `fetch_campflare_dump.py` does not exist.

- [ ] **Step 3: Implement fetcher helpers and CLI**

Create `scripts/fetch_campflare_dump.py`:

```python
#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
import os
import tempfile
import urllib.request
from pathlib import Path

from _envelope import load_source, utc_ts, write_envelope

API_BASE = "https://api.campflare.com/v2"
FETCHER = "fetch_campflare_dump.py"
FETCHER_VERSION = "1"
DEFAULT_PAGE_SIZE = 5000
ROOT = Path(__file__).resolve().parent.parent
ENV_PATH = ROOT / ".env"


def load_env() -> None:
    if not ENV_PATH.exists():
        return
    for line in ENV_PATH.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def part_name(index: int) -> str:
    return f"part-{index:06d}"


def iter_jsonl_gzip(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield json.loads(line)


def http_get_json(url: str, api_key: str) -> dict:
    req = urllib.request.Request(url, headers={"Authorization": api_key})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=300) as resp:
        with dest.open("wb") as out:
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)


def run(source: str, kind: str, page_size: int) -> None:
    load_env()
    api_key = os.environ.get("CAMPFLARE_API_KEY")
    if not api_key:
        raise SystemExit("CAMPFLARE_API_KEY is required; set it in the environment or repo-root .env")
    src = load_source(source)
    manifest = http_get_json(f"{API_BASE}/dumps/latest", api_key)
    entry = manifest[kind]
    ts = utc_ts()

    with tempfile.TemporaryDirectory() as td:
        gz_path = Path(td) / f"{kind}.jsonl.gz"
        download(entry["url"], gz_path)
        batch = []
        part = 1
        for row in iter_jsonl_gzip(gz_path):
            batch.append(row)
            if len(batch) >= page_size:
                write_envelope(
                    source_obj=src,
                    fetcher=FETCHER,
                    fetcher_version=FETCHER_VERSION,
                    request_url=f"{API_BASE}/dumps/latest#{kind}",
                    request_method="GET",
                    request_headers={"authorization": "<redacted>"},
                    response_status=200,
                    response_headers={"content-type": "application/x-ndjson+gzip"},
                    payload=batch,
                    part=part_name(part),
                    ts=ts,
                )
                batch = []
                part += 1
        if batch:
            write_envelope(
                source_obj=src,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=f"{API_BASE}/dumps/latest#{kind}",
                request_method="GET",
                request_headers={"authorization": "<redacted>"},
                response_status=200,
                response_headers={"content-type": "application/x-ndjson+gzip"},
                payload=batch,
                part=part_name(part),
                ts=ts,
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--kind", choices=["campgrounds", "campsites"], required=True)
    parser.add_argument("--page-size", type=int, default=DEFAULT_PAGE_SIZE)
    args = parser.parse_args()
    run(source=args.source, kind=args.kind, page_size=args.page_size)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Add Campflare data sources to registry**

Modify `config/poi-registry.yaml` in `data_sources:`:

```yaml
  - slug: campflare-campgrounds
    name: Campflare campground dump
    fetcher:
      executor: python3
      filename: scripts/fetch_campflare_dump.py
      args: { source: campflare-campgrounds, kind: campgrounds }
      timeout_sec: 1800
      output_dir_prefix: data/raw/campflare-campgrounds

  - slug: campflare-campsites
    name: Campflare campsite dump
    fetcher:
      executor: python3
      filename: scripts/fetch_campflare_dump.py
      args: { source: campflare-campsites, kind: campsites }
      timeout_sec: 1800
      output_dir_prefix: data/raw/campflare-campsites
```

- [ ] **Step 5: Run fetcher tests**

Run:

```bash
python3 -m unittest scripts/test_fetch_campflare_dump.py
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/fetch_campflare_dump.py scripts/test_fetch_campflare_dump.py config/poi-registry.yaml
git commit -m "feat: add campflare dump fetcher"
```

---

### Task 5: Add Catalog ETL Output and Registry Data Type

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CatalogEtlOutput.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistry.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistryTest.kt`

- [ ] **Step 1: Add registry test for `data_type`**

Find the existing registry tests or create `backend/src/test/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistryTest.kt`. Add:

```kotlin
@Test
fun `etl entry accepts catalog data type`() {
    val entry =
        EtlEntry(
            slug = "campflare-campgrounds",
            adapter = "CampflareCampgroundsEtl",
            inputs = listOf("campflare-campgrounds"),
            args = emptyMap(),
            dataType = EtlDataType.CAMPGROUND,
        )

    assertEquals(EtlDataType.CAMPGROUND, entry.dataType)
}
```

- [ ] **Step 2: Run registry test and verify it fails**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.models.metadata.registry.PoiRegistryTest
```

Expected: FAIL because `dataType` and `EtlDataType` do not exist.

- [ ] **Step 3: Add data type enum and field**

Modify `backend/src/main/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistry.kt`:

```kotlin
@Serializable
enum class EtlDataType(
    val wireValue: String,
) {
    @SerialName("legacy_poi")
    LEGACY_POI("legacy_poi"),

    @SerialName("legacy_reservable")
    LEGACY_RESERVABLE("legacy_reservable"),

    @SerialName("campground")
    CAMPGROUND("campground"),

    @SerialName("campsite")
    CAMPSITE("campsite"),

    @SerialName("tesla_supercharger")
    TESLA_SUPERCHARGER("tesla_supercharger"),

    @SerialName("planet_fitness_location")
    PLANET_FITNESS_LOCATION("planet_fitness_location"),
}

@Serializable
data class EtlEntry(
    val slug: String,
    val adapter: String,
    val inputs: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
    @SerialName("data_type")
    val dataType: EtlDataType = EtlDataType.LEGACY_POI,
)
```

- [ ] **Step 4: Create catalog output**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CatalogEtlOutput.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.catalog.CampgroundInput
import ca.floo.roadtrip.models.catalog.CampsiteInput

sealed interface CatalogEtlOutput {
    data class Campgrounds(
        val rows: List<CampgroundInput>,
    ) : CatalogEtlOutput

    data class Campsites(
        val rows: List<CampsiteInput>,
    ) : CatalogEtlOutput
}
```

- [ ] **Step 5: Dispatch catalog outputs in orchestrator**

Modify `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt` so terminal outputs of type `CatalogEtlOutput.Campgrounds` call `CampgroundCatalogRepo.runImport(...)` and `CatalogEtlOutput.Campsites` call `CampsiteCatalogRepo.runImport(...)`. Reuse existing import run semantics and tripwire behavior; do not write SQL in the orchestrator.

Required repo methods to add in Task 3 repos:

```kotlin
fun runImport(
    source: String,
    inputs: List<CampgroundInput>,
): ImportResult

fun runImport(
    source: String,
    inputs: List<CampsiteInput>,
): ImportResult
```

- [ ] **Step 6: Run registry and orchestrator tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.models.metadata.registry.PoiRegistryTest
./gradlew --no-daemon compileKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CatalogEtlOutput.kt backend/src/main/kotlin/ca/floo/roadtrip/models/metadata/registry/PoiRegistry.kt backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt
git commit -m "feat: add catalog etl output dispatch"
```

---

### Task 6: Add Campflare Campground and Campsite ETLs

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtl.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampsitesEtl.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundsEtlTest.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampsitesEtlTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt`
- Modify: `config/poi-registry.yaml`

- [ ] **Step 1: Write Campflare ETL fixture tests**

Create `CampflareCampgroundsEtlTest.kt` with a fixture record matching the live dump:

```kotlin
@Test
fun `campflare campground maps to canonical campground input`() {
    val raw =
        """
        {
          "id": "calvert-cliffs-youth-mysp",
          "name": "Calvert Cliffs Youth Campground",
          "status": "closed",
          "location": {
            "latitude": 38.40831195138031,
            "longitude": -76.41588657431205,
            "address": {
              "state_code": "MD",
              "country_code": "US"
            }
          },
          "management": {
            "agency_name": "Maryland State Parks",
            "agency_id": "maryland-state-parks"
          },
          "metadata": {
            "has_availability_data": true,
            "has_campsite_level_data": true
          },
          "amenities": [],
          "connections": {},
          "photos": [],
          "price": {},
          "contact": {
            "primary_phone": "+1 (443) 975-4360",
            "primary_email": null
          },
          "reservation_url": "https://parkreservations.maryland.gov/create-booking/results"
        }
        """.trimIndent()

    val rows = CampflareCampgroundsEtl("campflare-campgrounds").transformRecords(listOf(raw))

    assertEquals("Calvert Cliffs Youth Campground", rows.single().name)
    assertEquals("campflare", rows.single().primaryRef.vendor.wireValue)
    assertEquals("calvert-cliffs-youth-mysp", rows.single().primaryRef.externalId)
    assertEquals(38.40831195138031, rows.single().latitude)
    assertEquals(-76.41588657431205, rows.single().longitude)
}
```

Create `CampflareCampsitesEtlTest.kt`:

```kotlin
@Test
fun `campflare campsite maps to canonical campsite input`() {
    val raw =
        """
        {
          "id": "5f65dc5c-30e2-4531-8fba-82bdcaa17e1a",
          "campground_id": "calvert-cliffs-youth-mysp",
          "name": "6",
          "kind": "standard",
          "kind_listed": null,
          "loop_name": null,
          "max_people": 8,
          "equipment": [{"kind":"tent","name":"Tent"}],
          "electric_hookups": false,
          "water_hookups": false,
          "sewer_hookups": false,
          "ada_accessible": false,
          "pull_through": null,
          "price": {"per_night": 26.0, "currency_code": "USD"},
          "photos": [],
          "schedule": null
        }
        """.trimIndent()

    val rows = CampflareCampsitesEtl("campflare-campsites").transformRecords(listOf(raw))

    assertEquals("5f65dc5c-30e2-4531-8fba-82bdcaa17e1a", rows.single().primaryRef.externalId)
    assertEquals("calvert-cliffs-youth-mysp", rows.single().campgroundRef.externalId)
    assertEquals("standard", rows.single().kind)
    assertEquals(8, rows.single().maxPeople)
}
```

- [ ] **Step 2: Run ETL tests and verify they fail**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampgroundsEtlTest --tests ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampsitesEtlTest
```

Expected: FAIL because the ETL classes do not exist.

- [ ] **Step 3: Implement Campflare ETLs**

Implement both classes as `SourceEtl<List<Envelope>, CatalogEtlOutput.*>` with `multiPart = true`. Parse every envelope payload as a JSON array of records. Preserve the full upstream record as `sourcePayload`.

Required helper signatures:

```kotlin
class CampflareCampgroundsEtl(
    override val etlSlug: String,
) : SourceEtl<List<Envelope>, CatalogEtlOutput.Campgrounds> {
    override val multiPart: Boolean = true
    fun transformRecords(rawRecords: List<String>): List<CampgroundInput>
}

class CampflareCampsitesEtl(
    override val etlSlug: String,
) : SourceEtl<List<Envelope>, CatalogEtlOutput.Campsites> {
    override val multiPart: Boolean = true
    fun transformRecords(rawRecords: List<String>): List<CampsiteInput>
}
```

Mapping rules:

```text
Campflare campground id        -> VendorRefInput(CAMPFLARE, CAMPGROUND, id)
Campflare campground name      -> CampgroundInput.name
location.latitude/longitude    -> CampgroundInput latitude/longitude
location.address.state_code    -> region
location.address.country_code  -> country uppercase
management.agency_name         -> agency
contact.primary_phone          -> phone
contact.primary_email          -> email
reservation_url                -> reservationUrl
full record                    -> sourcePayload

Campflare campsite id          -> VendorRefInput(CAMPFLARE, CAMPSITE, id)
campground_id                  -> campgroundRef externalId
kind                           -> kind
kind_listed                    -> kindListed
loop_name                      -> loopName
equipment                      -> equipment JSON
full record                    -> sourcePayload
```

- [ ] **Step 4: Register Campflare ETLs**

Modify `EtlOrchestrator.etlRegistry`:

```kotlin
"campflare-campgrounds" to
    ca.floo.roadtrip.service.etl.vendors.campflare
        .CampflareCampgroundsEtl("campflare-campgrounds"),
"campflare-campsites" to
    ca.floo.roadtrip.service.etl.vendors.campflare
        .CampflareCampsitesEtl("campflare-campsites"),
```

Modify `config/poi-registry.yaml` to add enabled catalog rows:

```yaml
catalog_data:
  - name: Campflare Campgrounds
    enabled: true
    etls:
      - slug: campflare-campgrounds
        adapter: CampflareCampgroundsEtl
        inputs: [campflare-campgrounds]
        data_type: campground

  - name: Campflare Campsites
    enabled: true
    etls:
      - slug: campflare-campsites
        adapter: CampflareCampsitesEtl
        inputs: [campflare-campsites]
        data_type: campsite
```

- [ ] **Step 5: Run ETL tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampgroundsEtlTest --tests ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampsitesEtlTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt config/poi-registry.yaml
git commit -m "feat: load campflare campgrounds and campsites"
```

---

### Task 7: Wire Canada Sources Into Canonical Campgrounds and Campsites

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraJoinByNameEtl.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraResourcesEtl.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksStrapiEtl.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaEtl.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica/ReserveAmericaSitesEtl.kt`
- Modify: Canada-related ETL tests under `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/...`
- Modify: `config/poi-registry.yaml`

- [ ] **Step 1: Write failing Canada canonical output tests**

For BC Parks, Parks Canada, and Alberta tests, add assertions that terminal ETLs return `CatalogEtlOutput.Campgrounds` or `CatalogEtlOutput.Campsites` and that vendor refs use the owning Canada vendor:

```kotlin
assertEquals("bc_parks", output.rows.single().primaryRef.vendor.wireValue)
assertEquals("campground", output.rows.single().primaryRef.entityType.wireValue)
```

For Parks Canada Aspira rows:

```kotlin
assertEquals("aspira_pc", output.rows.single().primaryRef.vendor.wireValue)
```

For Alberta ReserveAmerica rows:

```kotlin
assertEquals("alberta_parks", output.rows.single().primaryRef.vendor.wireValue)
```

- [ ] **Step 2: Run Canada ETL tests and verify they fail**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.etl.vendors.bcparks.BcParksStrapiEtlTest --tests ca.floo.roadtrip.service.etl.vendors.aspira.AspiraJoinByNameEtlTest --tests ca.floo.roadtrip.service.etl.vendors.aspira.AspiraResourcesEtlTest --tests ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaEtlTest --tests ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaSitesEtlTest
```

Expected: FAIL while outputs still use legacy `Poi` or `ReservableEtlOutput`.

- [ ] **Step 3: Convert Canada campground ETLs to canonical outputs**

Map current Canada campground output fields to `CampgroundInput`. Use source-owned vendor refs:

```text
BC Parks campground         -> vendor bc_parks, entity campground
Parks Canada campground     -> vendor aspira_pc, entity campground
Alberta campground          -> vendor alberta_parks, entity campground
```

Keep old raw source payload in `sourcePayload`.

- [ ] **Step 4: Convert Canada site ETLs to canonical outputs**

Map existing site/resource outputs to `CampsiteInput`. Use source-owned vendor refs:

```text
BC Aspira resource          -> vendor aspira_bc, entity campsite
Parks Canada Aspira resource -> vendor aspira_pc, entity campsite
Alberta ReserveAmerica site -> vendor alberta_parks, entity campsite
```

For Canada sources with fields that do not fit canonical columns, preserve the full upstream row in `sourcePayload`.

- [ ] **Step 5: Update registry rows**

Set US legacy rows disabled. Keep Canada rows enabled and label their terminal data types:

```yaml
  - name: BC Provincial Parks
    enabled: true
    ...
        data_type: campground

  - name: Parks Canada
    enabled: true
    ...
        data_type: campground

  - name: Alberta Provincial Parks
    enabled: true
    ...
        data_type: campground

  - name: BC Aspira Resources
    enabled: true
    ...
        data_type: campsite

  - name: Parks Canada Aspira Resources
    enabled: true
    ...
        data_type: campsite

  - name: Alberta Provincial Park Sites
    enabled: true
    ...
        data_type: campsite
```

- [ ] **Step 6: Run Canada tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.etl.vendors.bcparks.BcParksStrapiEtlTest --tests ca.floo.roadtrip.service.etl.vendors.aspira.AspiraJoinByNameEtlTest --tests ca.floo.roadtrip.service.etl.vendors.aspira.AspiraResourcesEtlTest --tests ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaEtlTest --tests ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaSitesEtlTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/reserveamerica backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors config/poi-registry.yaml
git commit -m "feat: load canada sources into canonical catalog"
```

---

### Task 8: Switch POI Serving to Wrapper Joins

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/PoiSchemas.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/OnRoutePoiRepo.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/PoiRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/PoisOnRouteRoutes.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/PoiRoutesTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/PoisOnRouteRoutesTest.kt`

- [ ] **Step 1: Update route tests for wrapper data**

In `PoiRoutesTest`, seed:

```sql
INSERT INTO campgrounds (id, name, status, region, country) VALUES (100, 'Upper Pines', 'open', 'CA', 'US');
INSERT INTO pois (id, poi_type, geom, metadata)
VALUES (200, 'campground', ST_SetSRID(ST_MakePoint(-119.565, 37.742), 4326), '{"source":"campflare"}');
INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (200, 100);
```

Assert response has:

```json
{
  "properties": {
    "poi_id": 200,
    "poi_type": "campground",
    "data": {
      "id": 100,
      "name": "Upper Pines",
      "status": "open"
    }
  }
}
```

- [ ] **Step 2: Run POI route tests and verify they fail**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.routes.PoiRoutesTest --tests ca.floo.roadtrip.routes.PoisOnRouteRoutesTest
```

Expected: FAIL because serving still reads old `pois` columns and does not join `campgrounds`.

- [ ] **Step 3: Add new API schemas**

Modify `PoiSchemas.kt` with wrapper DTOs:

```kotlin
@Serializable
data class PoiWrapperPropertiesSchema(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("poi_type") val poiType: String,
    val metadata: JsonElement,
    val data: JsonElement,
)
```

Keep old DTOs only where routes still need compatibility during the frontend transition.

- [ ] **Step 4: Rewrite serving repo queries**

In `PoiServingRepo`, replace old source/category projections with `pois.poi_type`, typed joins, and JSON data projection:

```sql
SELECT p.id,
       p.poi_type,
       p.metadata::text AS metadata_text,
       ST_X(ST_PointOnSurface(p.geom)) AS lng,
       ST_Y(ST_PointOnSurface(p.geom)) AS lat,
       jsonb_build_object(
         'id', c.id,
         'name', c.name,
         'status', c.status,
         'region', c.region,
         'country', c.country,
         'agency', c.agency
       )::text AS data_text
FROM pois p
JOIN poi_campgrounds pc ON pc.poi_id = p.id
JOIN campgrounds c ON c.id = pc.campground_id
WHERE p.deleted_at IS NULL
  AND c.deleted_at IS NULL
  AND p.poi_type = 'campground'
  AND p.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
```

- [ ] **Step 5: Update routes to emit wrapper response**

In `PoiRoutes.kt`, map repo rows to `PoiWrapperPropertiesSchema`. Preserve GeoJSON FeatureCollection shape so the map integration changes minimally.

- [ ] **Step 6: Run route tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.routes.PoiRoutesTest --tests ca.floo.roadtrip.routes.PoisOnRouteRoutesTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/PoiSchemas.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/OnRoutePoiRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/PoiRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/PoisOnRouteRoutes.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/PoiRoutesTest.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/PoisOnRouteRoutesTest.kt
git commit -m "feat: serve pois from canonical wrapper joins"
```

---

### Task 9: Replace RID/Reservable Site API with Campsite IDs

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteRoutes.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteSchemas.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/routes/CampsiteRoutesTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/RoadtripRuntime.kt`

- [ ] **Step 1: Write campsite route test**

Create `CampsiteRoutesTest.kt`:

```kotlin
@Test
fun `GET campground campsites returns numeric campsite ids`() = testApplication {
    // Seed campground id=100 and two campsites id=201, id=202.
    // Call /api/campgrounds/100/campsites.
    // Assert response uses campsite_id and does not include rid.
}
```

Expected response:

```json
{
  "campground_id": 100,
  "campsites": [
    {
      "campsite_id": 201,
      "name": "001",
      "kind": "standard",
      "loop_name": "A"
    }
  ]
}
```

- [ ] **Step 2: Run campsite route test and verify it fails**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.routes.CampsiteRoutesTest
```

Expected: FAIL because the route does not exist.

- [ ] **Step 3: Add campsite API schemas**

Create `CampsiteSchemas.kt` with:

```kotlin
@Serializable
data class CampsiteSchema(
    @SerialName("campsite_id") val campsiteId: Long,
    val name: String? = null,
    val kind: String? = null,
    @SerialName("kind_listed") val kindListed: String? = null,
    @SerialName("loop_name") val loopName: String? = null,
    @SerialName("max_people") val maxPeople: Int? = null,
    val equipment: JsonElement? = null,
    val price: JsonElement? = null,
    @SerialName("reservation_url") val reservationUrl: String? = null,
)

@Serializable
data class CampgroundCampsitesResponse(
    @SerialName("campground_id") val campgroundId: Long,
    val campsites: List<CampsiteSchema>,
)
```

- [ ] **Step 4: Add campsite routes**

Create `CampsiteRoutes.kt`:

```kotlin
fun Route.campsiteRoutes(ctx: DSLContext) {
    val repo = CampsiteCatalogRepo(ctx, VendorRefRepo(ctx), CampgroundCatalogRepo(ctx, VendorRefRepo(ctx)))
    get("/api/campgrounds/{campground_id}/campsites") {
        val campgroundId = call.parameters["campground_id"]?.toLongOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val rows = repo.findByCampground(campgroundId)
        call.respondText(
            campsiteJson.encodeToString(
                CampgroundCampsitesResponse(
                    campgroundId = campgroundId,
                    campsites = rows.map { it.toSchema() },
                ),
            ),
            ContentType.Application.Json,
        )
    }
}
```

- [ ] **Step 5: Wire route in runtime**

In `RoadtripRuntime.kt`, call:

```kotlin
campsiteRoutes(ctx)
```

next to current API route registration.

- [ ] **Step 6: Keep old reservable route compatibility explicit**

In `ReservableRoutes.kt`, return `410 Gone` for endpoints that the frontend no longer calls:

```kotlin
call.respondReservableError(
    error = "reservables_deprecated",
    status = HttpStatusCode.Gone,
    detail = "Use /api/campgrounds/{campground_id}/campsites.",
)
```

- [ ] **Step 7: Run route tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.routes.CampsiteRoutesTest --tests ca.floo.roadtrip.routes.ReservableRoutesTest
```

Expected: PASS after updating legacy expectations.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/CampsiteRoutes.kt backend/src/main/kotlin/ca/floo/roadtrip/models/api/CampsiteSchemas.kt backend/src/main/kotlin/ca/floo/roadtrip/routes/ReservableRoutes.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/CampsiteRoutesTest.kt backend/src/test/kotlin/ca/floo/roadtrip/routes/ReservableRoutesTest.kt backend/src/main/kotlin/ca/floo/roadtrip/RoadtripRuntime.kt
git commit -m "feat: expose campsites by canonical ids"
```

---

### Task 10: Update Availability to Resolve Through Vendor Refs

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ResolvedAvailabilityTarget.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/reservation/ReservationProvider.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/reservation/ReservationProviderRegistry.kt`
- Modify: provider adapters under `backend/src/main/kotlin/ca/floo/roadtrip/service/reservation/adapters`
- Modify: availability tests under `backend/src/test/kotlin/ca/floo/roadtrip/service`

- [ ] **Step 1: Write resolver test**

Add a test that seeds:

```sql
INSERT INTO vendor_refs (id, vendor, entity_type, external_id)
VALUES (1, 'campflare', 'campground', 'calvert-cliffs-youth-mysp');
INSERT INTO campgrounds (id, name) VALUES (100, 'Calvert Cliffs Youth Campground');
INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id, is_primary) VALUES (100, 1, true);
INSERT INTO pois (id, poi_type, geom) VALUES (200, 'campground', ST_SetSRID(ST_MakePoint(-76.4, 38.4), 4326));
INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (200, 100);
```

Assert target resolver returns `campgroundId = 100`, `vendor = campflare`, and `externalId = calvert-cliffs-youth-mysp`.

- [ ] **Step 2: Run availability tests and verify failure**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.availability.AvailabilityTargetResolverTest
```

Expected: FAIL because resolver still expects `provider_ref` on old `pois`.

- [ ] **Step 3: Change target model**

Change `ResolvedAvailabilityTarget` to carry canonical IDs and vendor refs:

```kotlin
data class ResolvedAvailabilityTarget(
    val poiId: Long,
    val campgroundId: Long,
    val campsiteIds: List<Long>,
    val primaryVendor: CatalogVendor,
    val primaryExternalId: String,
)
```

- [ ] **Step 4: Update provider port**

Change `ReservationProvider` methods to accept canonical target data and vendor refs rather than `ProviderRef` sealed variants:

```kotlin
suspend fun campgroundAvailability(
    target: ReservationTarget,
    startDate: LocalDate,
    endDate: LocalDate,
): PoiReservablesAvailabilityResponseDto
```

Define:

```kotlin
data class ReservationTarget(
    val campgroundId: Long,
    val campgroundVendorRef: VendorRefInput,
    val campsiteVendorRefs: Map<Long, VendorRefInput>,
)
```

- [ ] **Step 5: Implement Campflare adapter first**

Add a Campflare adapter that calls:

```text
GET https://api.campflare.com/v2/campground/{campflare_campground_id}/availability
```

Map Campflare campsite IDs to canonical `campsites.id` through `campsite_vendor_refs`.

- [ ] **Step 6: Run availability tests**

Run:

```bash
./gradlew --no-daemon test --tests ca.floo.roadtrip.service.availability.AvailabilityTargetResolverTest --tests ca.floo.roadtrip.routes.AvailabilityRoutesTest
```

Expected: PASS after updating expected response IDs from RID strings to numeric campsite IDs.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability backend/src/main/kotlin/ca/floo/roadtrip/service/reservation backend/src/test/kotlin/ca/floo/roadtrip/service backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityRoutesTest.kt
git commit -m "feat: resolve availability through vendor refs"
```

---

### Task 11: Update Frontend Away From RID

**Files:**
- Modify: `web/availability/site-list.js`
- Modify: `web/availability/site-matrix.js`
- Modify: `web/availability/availability-week.js`
- Modify: `web/availability/day-fields.js`
- Modify: `web/api/reservable-api.js`
- Modify: frontend smoke tests under `backend/src/smokeTest/kotlin/ca/floo/roadtrip/SmokeTest.kt`

- [ ] **Step 1: Update frontend test fixtures**

Replace fixture keys:

```json
"available_reservable_ids": ["site:campflare:5f65dc5c-30e2-4531-8fba-82bdcaa17e1a"]
```

with:

```json
"available_campsite_ids": [201]
```

Replace row shape:

```json
{ "rid": "site:campflare:5f65dc5c-30e2-4531-8fba-82bdcaa17e1a", "name": "6" }
```

with:

```json
{ "campsite_id": 201, "name": "6" }
```

- [ ] **Step 2: Run smoke test and verify failure**

Run:

```bash
./gradlew --no-daemon smokeTest
```

Expected: FAIL until frontend reads numeric campsite IDs.

- [ ] **Step 3: Update day field helpers**

In `web/availability/day-fields.js`, export:

```javascript
export function availableCampsiteIds(day) {
  const ids = day?.available_campsite_ids ?? day?.availableCampsiteIds;
  if (Array.isArray(ids)) return ids.map((id) => Number(id));
  return [];
}
```

- [ ] **Step 4: Update site list and matrix row identity**

Replace `row.rid` and `data-rid` with `row.campsite_id` and `data-campsite-id`. The identity function becomes:

```javascript
function campsiteId(row) {
  return Number(row?.campsite_id ?? row?.campsiteId);
}
```

- [ ] **Step 5: Remove RID fallback display**

Replace labels that fall back to `rid` with `Site #${campsite_id}` or `site.name`.

- [ ] **Step 6: Run frontend smoke tests**

Run:

```bash
./gradlew --no-daemon smokeTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add web/availability web/api/reservable-api.js backend/src/smokeTest/kotlin/ca/floo/roadtrip/SmokeTest.kt
git commit -m "feat: use canonical campsite ids in frontend"
```

---

### Task 12: End-to-End Campflare Load Verification

**Files:**
- Modify: `docs/reservation-providers.md`
- Modify: `docs/backend-architecture.md`
- Create: `docs/canonical-catalog.md`

- [ ] **Step 1: Run unit tests**

Run:

```bash
./gradlew --no-daemon test
```

Expected: PASS.

- [ ] **Step 2: Run Kotlin compile and style checks**

Run:

```bash
./gradlew --no-daemon compileKotlin ktlintCheck
```

Expected: PASS.

- [ ] **Step 3: Fetch Campflare dump using `CAMPFLARE_API_KEY` from env or `.env`**

Run:

```bash
# Assumes CAMPFLARE_API_KEY is exported or present in repo-root .env.
python3 scripts/fetch_campflare_dump.py --source campflare-campgrounds --kind campgrounds
python3 scripts/fetch_campflare_dump.py --source campflare-campsites --kind campsites
```

Expected:

```text
data/raw/campflare-campgrounds/<timestamp>/part-000001.json
data/raw/campflare-campsites/<timestamp>/part-000001.json
```

- [ ] **Step 4: Run catalog imports**

Run the admin ingest endpoint or local ingest command for:

```text
Campflare Campgrounds
Campflare Campsites
BC Provincial Parks
Parks Canada
Alberta Provincial Parks
BC Aspira Resources
Parks Canada Aspira Resources
Alberta Provincial Park Sites
```

Expected import counts:

```text
campflare campgrounds: about 10,963 rows
campflare campsites: about 299,904 rows
zero orphan campsites for campflare source
```

- [ ] **Step 5: Validate DB counts**

Run:

```bash
psql postgresql://roadtrip:roadtrip@127.0.0.1:5432/roadtrip -c "
SELECT
  (SELECT count(*) FROM campgrounds WHERE deleted_at IS NULL) AS campgrounds,
  (SELECT count(*) FROM campsites WHERE deleted_at IS NULL) AS campsites,
  (SELECT count(*) FROM pois WHERE deleted_at IS NULL AND poi_type = 'campground') AS campground_pois;
"
```

Expected:

```text
campgrounds > 10000
campsites > 250000
campground_pois > 10000
```

- [ ] **Step 6: Write docs**

Create `docs/canonical-catalog.md` with:

```markdown
# Canonical Catalog

Roadtrip stores spatial wrappers in `pois` and domain data in typed catalog tables.

V1 campground/site owners:
- Campflare owns US campgrounds and campsites.
- Canada ETLs own Canadian campgrounds and campsites.

Vendor refs map upstream IDs to canonical rows:
- `vendor_refs`
- `campground_vendor_refs`
- `campsite_vendor_refs`

V1 does not merge or enrich rows across vendors. A source owns the canonical rows it writes.
```

Update `docs/backend-architecture.md` ETL flow:

```text
admin route -> ingest controller -> ETL orchestrator -> catalog ETL -> catalog repo upsert
```

Update `docs/reservation-providers.md`:

```text
Availability providers resolve upstream IDs through vendor_refs instead of parsing old provider_ref JSON from pois.
```

- [ ] **Step 7: Run final verification**

Run:

```bash
./gradlew --no-daemon test compileKotlin ktlintCheck
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add docs/canonical-catalog.md docs/backend-architecture.md docs/reservation-providers.md
git commit -m "docs: document canonical catalog architecture"
```

---

## Self-Review

**Spec coverage:**
- Canonical `campgrounds` and `campsites`: Task 1, Task 3, Task 6, Task 7.
- POI wrapper table with typed joins: Task 1, Task 3, Task 8.
- `vendor_refs` and entity-ref join tables: Task 1, Task 3, Task 10.
- Campflare for US and Canada sources for Canada: Task 6 and Task 7.
- No legacy data migration: Scope Decisions and Task 12 verification.
- No cross-vendor association or enrichment merge: Scope Decisions.
- `/api/pois` wrapper joined with data: Task 8.
- Drop RID as primary API identity: Task 9, Task 10, Task 11.

**Placeholder scan:**
- The plan does not use `TBD`, `TODO`, or "implement later".
- Steps that modify code include target paths, expected signatures, and concrete mapping rules.

**Type consistency:**
- `CatalogVendor`, `CatalogEntityType`, `VendorRefInput`, `CampgroundInput`, and `CampsiteInput` are introduced in Task 2 and reused consistently in later tasks.
- Canonical site identity is `campsites.id` exposed as `campsite_id`.
- Upstream identity is always `vendor_refs(vendor, entity_type, external_id)`.
