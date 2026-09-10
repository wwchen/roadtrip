# Campground Bags and Vocabularies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Type the seven opaque campground JSONB bags and the campsite `kind` vocabulary, serve them as one shape with backend-owned labels, and delete the frontend's amenity, carrier, season, rating, and parent-park heuristics.

**Architecture:** The #724/#745 pattern once more: `@Serializable` domain types and enums in `model/domain`, `CatalogColumnJson` as the one codec, `CampgroundRepo`/`CampsiteRepo` as the only encoders/decoders, vendor ETLs mapping upstream keys into the enums, migrations that canonicalize stored shapes (no backfill; `make data-import` fills new facts), typed API DTOs, and a frontend that renders labels it is given.

**Tech Stack:** Kotlin 2 / Ktor / jOOQ raw SQL / kotlinx.serialization / Flyway + Postgres (Testcontainers) / detekt + ktlint; React + TypeScript + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-09-campground-bags-design.md`. Audit findings 3 (remainder) and 12 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issues: #732, #741.

## Global Constraints

- Layering per `docs/backend-architecture.md`: SQL only in `repo/` and migrations; no Ktor types in `service/`; models depend on stdlib + serialization only; ETLs never touch repos.
- No inline magic constants; comments short and rare; never edit an applied migration (`V56` is the latest; this plan adds `V57` and `V58`).
- Migrations canonicalize stored shapes and add columns; they backfill nothing. Every shape listed in the spec's data table must decode after V57/V58.
- Stored JSON keys, wire enum values, and labels exactly as the spec lists them; labels come from the enums, never from the frontend.
- Backend gate (Docker running): `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`; fast loop `--tests '<pattern>'`; ktlint fixes `./gradlew :backend:ktlintFormat --offline -q`.
- Frontend gate: `cd frontend && npm run typecheck && npm test && npm run lint`.
- One commit per task, conventional prefix, `Refs #732` or `Refs #741`, ending `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- The first Write/Edit to each file in a session is denied by the GateGuard hook; state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

---

### Task 1: Campground bag types, codec, V57, repo (#732)

**Files:**
- Create under `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/`: `AmenityKey.kt`, `CampgroundAmenity.kt`, `Carrier.kt`, `CarrierSignal.kt`, `CampgroundRating.kt`, `CampgroundMetadata.kt`, `CampgroundPrice.kt`, `CampgroundSchedule.kt`, `CampgroundAlert.kt` (bodies per the spec's Domain types block; enums serialize by `wire` via `@SerialName` on each constant; `CampgroundMetadata.lastUpdated` is `@SerialName("last_updated")`, `CampgroundSchedule.checkIn/checkOut` are `check_in`/`check_out`, `CampgroundAlert.endsOn/sourceUrl` are `ends_on`/`source_url`)
- Modify: `model/domain/CampgroundUpsertCandidate.kt` and `model/domain/Campground.kt`: `amenities: List<CampgroundAmenity> = emptyList()`, `cellService: List<CarrierSignal> = emptyList()`, `metadata: CampgroundMetadata? = null`, `price: CampgroundPrice? = null`, `defaultCampsiteSchedule: CampgroundSchedule? = null`, `alerts: List<CampgroundAlert> = emptyList()`, new `parentName: String? = null`; `connections` stays `JsonElement`
- Create: `backend/src/main/resources/db/migration/V57__typed_campground_bags.sql`
- Modify: `repo/CampgroundRepo.kt` (insert/upsert columns and params for `parent_name`; encode the six bags with `CatalogColumnJson`; decode in the row mapper; NULL or `{}`/`[]` decode to empty list / null exactly like `CampsiteRepo.decodeListColumn`)
- Modify: every campground ETL only as far as the compiler requires (they build the typed values in Task 2; here rec.gov, Aspira, BC Parks, ReserveAmerica, ReserveCalifornia pass `emptyList()`/`null` for the bags they do not yet map, and Campflare does the same)
- Modify: `service/poi/CampgroundService.kt` only as far as the compiler requires: pass `CatalogColumnJson.elements(campground.amenities)` etc. into the still-`JsonElement` schema fields, and `lastVerified = campground.metadata?.lastUpdated` (Task 3 replaces the DTOs)
- Test: `model/domain/CatalogColumnJsonTest.kt` (enum wire round-trip for `AmenityKey`, `Carrier`), `repo/CatalogEntityRepoTest.kt` (round-trip of all six bags and `parent_name`; a V57 no-op test on canonical rows; a V57 legacy-shape test that seeds, through raw SQL, one Campflare amenities object with `toilet_kind`, a `true`, a `false`, and a JSON `null`, one ReserveCalifornia label object, one rec.gov `{avg,count}` cell object, one Campflare bare-number cell object, one Campflare `price`/schedule/alert in the old keys, and one Aspira metadata blob of provenance extras, then re-runs the migration SQL and asserts the decoded typed values), `repo/CanonicalCatalogSchemaTest.kt` (column list gains `parent_name`).

**Interfaces:** exactly the spec's Domain types block; `Campground`/`CampgroundUpsertCandidate` fields as above. Task 2 builds these values; Task 3 maps them to DTOs.

- [ ] Steps: failing repo round-trip test → V57 per the spec's Migration section, in the style of V56 (`jsonb_exists`, `jsonb_typeof` guards, `WHERE` guards that make every `UPDATE` a no-op on canonical rows; never `?` in SQL text, JDBC reads it as a bind marker) → types → repo → compiler-driven ETL/service fixes → gate → commit `refactor(catalog): typed campground amenities, carriers, metadata, price, schedule, alerts`.

---

### Task 2: ETLs map vendor bags into the enums; `parent_name` (#732)

**Files:** `service/etl/vendors/campflare/CampflareCampgroundsEtl.kt` plus a new `CampflareCampgroundBags.kt` with the pure mappers; `recgov/RecGovCampgroundsEtl.kt` (carrier enum via `Carrier.fromVendorName`, `RECAREA[0].RecAreaName` → `parentName`, activities and `rating_reviews` into `CampgroundMetadata`); `reservecalifornia/ReserveCaliforniaCampgroundsEtl.kt` (label → `AmenityKey` table from the spec's Amenity mapping section; activities into metadata; drop `facility_unit_types`); `aspira/AspiraCampgroundsEtl.kt` and `bcparks/BcParksCampgroundsEtl.kt` (`parentName = leaf.parentName`; stop writing provenance extras into metadata, `sourcePayload` keeps them); `reserveamerica/ReserveAmericaCampgroundsEtl.kt` (stop duplicating the extras into metadata). Tests: each vendor's campground ETL test asserts the typed values; a new `CampflareCampgroundBagsTest` covers `toilet_kind`, JSON null, unknown key → `OTHER` with the key as detail, bare-number carriers including `uscell`, old price/schedule/alert keys.

**Interfaces:** `object CampflareCampgroundBags { fun amenities(raw: JsonObject): List<CampgroundAmenity>; fun carriers(raw: JsonObject): List<CarrierSignal>; fun price(raw: JsonObject?): CampgroundPrice?; fun schedule(raw: JsonObject?): CampgroundSchedule?; fun alerts(raw: JsonArray?): List<CampgroundAlert>; fun metadata(raw: JsonObject?): CampgroundMetadata? }`; `Carrier.fromVendorName(name: String): Carrier?` on the enum companion (the rec.gov name map); `AmenityKey.fromWire(value: String): AmenityKey?`.

- [ ] Steps: failing ETL tests → mappers → gate → commit `feat(etl): campground bags as typed values with backend vocabularies`.

---

### Task 3: Typed POI detail DTOs (#732)

**Files:** `model/api/poi/PoiCategoryDetailSchema.kt` (replace the campground `JsonElement` fields `price`, `schedule`, `amenities`, `cell_coverage`, `alerts`, `metadata` per the spec's API section; add `activities`, `rating`, `parent_name`; delete `metadata`; charger fields untouched), new `model/api/poi/AmenityDto.kt`, `CarrierSignalDto.kt`, `RatingDto.kt`, `PriceDto.kt`, `ScheduleDto.kt`, `AlertDto.kt` (each with `from(domain)` in a companion; `AmenityDto.label` = `key.negativeLabel` when `present == false` and one exists, `detail` for `OTHER`, `"<Detail> toilets"` with the detail capitalized for `TOILETS` with a detail, otherwise `key.label`), `service/poi/CampgroundService.kt` (map typed values; `activities` and `rating` from `metadata`; `lastVerified` from `metadata?.lastUpdated`). Tests: `PoiServiceTest`, `route/FeatureCollectionContractTest.kt`, a new `PoiDetailDtoTest` asserting the JSON for one campground with every bag populated and one with all empty (lists `[]`, optional objects omitted).

- [ ] Steps: failing DTO test → DTOs → service → gate → commit `refactor(api): typed campground amenities, carriers, rating, price, schedule, alerts on POI detail`.

---

### Task 4: Frontend renders typed bags; dead heuristics deleted (#732)

**Files:** `frontend/src/api/poi-api.ts` (TypeScript mirrors `AmenityDto`, `CarrierSignalDto`, `RatingDto`, `PriceDto`, `ScheduleDto`, `AlertDto`, and a `CampgroundDetail` interface listing the typed fields the campground page reads: `amenities`, `cell_coverage`, `activities`, `rating`, `price`, `schedule`, `alerts`, `parent_name`, `last_verified`), `frontend/src/domain/poi/campground-detail.ts` (rewrite `amenityTags` to read `{label, present}`, `carrierSignals` to read the typed list, `rating` to read `p.rating`, `activityList` to read `p.activities`, price/schedule/alert readers to the typed keys; delete `AMENITY_LABELS`, `NEGATIVE_AMENITY_LABELS`, `CARRIER_LABELS`, `titleCase` if unused, `seasonVerdict`, `parseSeasonRange`, `parseDateBit`, `monthFrom`, `monthDay`, `MONTHS`, `FUZZY_DAY`, `parentParkName`, `normalizeTitle`, the three parent regexes, and all `rating_reviews` handling), `frontend/src/domain/poi/types/campground.tsx` (no `seasonVerdict`; `knownParent` from `p.parent_name` only), `frontend/src/features/trip/trip-cards.ts` and `TripResults.tsx` (delete `parseRating`, `compactSeasonLabel`, `MAX_SEASON_CHARS`, `TRUNCATED_SEASON_CHARS`, and the `season`/`reservable`/`rating`/`sites` card fields with their rendering), `frontend/src/lib/poi.ts` only if it references the deleted fields, and every test/fixture: `campground-detail.test.ts`, `trip-cards.test.ts`, `TopBar.test.tsx`, `CampgroundPanel.test.tsx`, `campground.test.tsx`, `lib/poi.test.ts`.

- [ ] Steps: rewrite tests → implement → `grep -rn 'AMENITY_LABELS\|CARRIER_LABELS\|seasonVerdict\|parseRating\|rating_reviews\|parentParkName\|reservable' frontend/src` returns nothing → gate → commit `refactor(frontend): campground facts from the typed detail DTO`.

---

### Task 5: `CampsiteKind` vocabulary, filters, V58 (#741)

**Files:**
- Create: `model/domain/CampsiteKind.kt` (the spec's enum, `@SerialName` per constant, `companion fun fromWire(value: String): CampsiteKind?`), `service/etl/framework/CampsiteKinds.kt` (`object CampsiteKinds { fun recgov(campsiteType: String?): RecGovKind; fun campflare(kind: String?): CampsiteKind; fun aspira(category: String?): CampsiteKind; fun reserveCalifornia(unitType: String?): CampsiteKind }` with `data class RecGovKind(val kind: CampsiteKind, val electric: Boolean?)`; every mapping row exactly as the spec's Campsite kind vocabulary section lists it), `V58__campsite_kind_wire.sql` (the same mapping as SQL `CASE` expressions per `data_provider` over `kind`, unclassifiable → `'other'`; then rewrite `availability_watch.campsite_filters -> 'site_type'` when present, string or array, through the same `CASE`; idempotent: a value already in the wire set is left alone).
- Modify: `model/domain/CampsiteUpsertCandidate.kt` and `Campsite.kt` (`kind: CampsiteKind`; delete `DEFAULT_CAMPSITE_KIND`, the default is `CampsiteKind.OTHER`), `RecGovCampsitesEtl.kt` (kind and `electricHookups` from `CampsiteKinds.recgov`; `kindListed` keeps the vendor string), `CampflareCampsitesEtl.kt` (`kindListed = raw kind_listed ?: raw kind`), `AspiraCampsitesEtl.kt`, `ReserveCaliforniaSitesEtl.kt`, `ReserveAmericaSitesEtl.kt`; `repo/CampsiteRepo.kt` (store and read `kind.wire`; delete `search()`, `SearchFilters`, `searchWhere`, and `addInClause`, which have no caller in main or test code); `service/availability/CampsiteCatalogService.kt` and `CampsiteAvailabilityController.kt` and `BulkAvailabilityController.kt` (`siteTypes: List<CampsiteKind>`); `WatchScopeResolver.kt` (parse `site_type` strings through `fromWire`, unknown → no match); `WatchAlertDispatcher.kt` (`siteType = r.kindListed ?: r.kind.label`); `route/api/pois/CampsiteRoutes.kt` and `BulkAvailabilityRoutes.kt`/`model/api/BulkAvailabilityRequestDto.kt` (unknown `site_type` → `bad_request` naming the value; describe the accepted values in the `describeApi` text as the wire list); `model/api/CampsiteDto.kt` (`kind` = wire, new `kind_label`); frontend `api/campsite-api.ts` (`kind_label: string`), `features/availability/matrix-rows.ts` (`filterOptions` for `'kind'` returns `{value, label}` pairs from `kind`/`kind_label`; loops unchanged), `SiteMatrix.tsx` Type dropdown and its filter component (options show labels, filter on wire value), `site-detail-facts.ts` (Type fact = `kind_listed ?? kind_label`), `SiteList.tsx` lines 180-182 (the day-list type badge prints `row.kind_label`), tests and fixtures for each (backend: `CampsiteAvailabilityControllerSliceTest` uses `tent`/`rv`/`cabin`, `CanonicalCatalogFixtures.seedCampsite` defaults to `site` → `other`; frontend fixtures in `site-detail-facts.test.ts`, `campsite-api.test.ts`, `site-list-rows.test.ts`, `WatchTable.test.tsx`, `matrix-rows.test.ts`, `AvailabilityWeek.test.tsx`).
- Test: `CampsiteKindsTest` feeding every vendor value in the spec paragraph and asserting the kind (and `electric` for rec.gov `STANDARD ELECTRIC` true, `STANDARD NONELECTRIC` false, `WALK TO` null); `CampsiteRepo`/`CatalogEntityRepoTest` round-trip and a V58 legacy test seeding one row per vendor family plus an `availability_watch` row with `campsite_filters = {"site_type": ["TENT ONLY NONELECTRIC", "tent"]}` and asserting `["tent","tent"]`; route tests for the `bad_request`; frontend tests for the dropdown and facts.

- [ ] Steps: failing `CampsiteKindsTest` → enum + mapping → ETLs → repo/filters/routes → V58 → DTO/frontend → both gates → commit `feat(catalog): CampsiteKind vocabulary with per-vendor mapping and typed filters`.

---

### Task 6: Docs (#732, #741)

**Files:** `docs/backend-architecture.md` (Models: the campground bags and `CampsiteKind` follow the typed-JSONB rule; vocabularies live in `model/domain` enums and vendor mappings in the ETLs), `docs/frontend-components.md` (labels for amenities, carriers, and site kinds come from the API), `docs/reservation-providers.md` where it describes `site_type` filters.

- [ ] Steps: edit → commit `docs: campground bag types and the campsite kind vocabulary`.
