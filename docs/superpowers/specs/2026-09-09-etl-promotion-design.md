# ETL promotion: typed campsite facts, a closed campsite DTO, typed campground bags

**Date:** 2026-09-09
**Status:** Design for audit findings 2, 3 (remainder), and 12 (`docs/superpowers/specs/2026-09-09-architecture-audit.md`; issues #731, #732, #741)
**Follows:** `2026-09-03-typed-campground-jsonb-design.md` (#724) and the typed provider seam (#744)

## Problem

The `/api/pois/{id}/campsites` endpoint serializes the `campsites` table row,
`source_payload` and timestamps included, and the frontend's `Campsite` type is
an open index signature. Only the Campflare ETL fills the typed campsite
columns; rec.gov parks its attributes in a `_roadtrip_tags` bag inside the
payload and Aspira leaves capacity, description, and defined attributes there
too. So the browser is the second half of the ETL:
`features/availability/site-detail-facts.ts` reconciles four capacity spellings
and eight attribute-name spellings, strips HTML, and walks the payload six
levels deep to find a photo; `site-list-rows.ts` keeps a second, divergent copy
of the capacity logic. A vendor renaming a key changes what renders and no test
fails.

On the campground side #724 typed five JSONB columns. Seven remain opaque
(`amenities`, `price`, `default_campsite_schedule`, `cell_service`, `alerts`,
`connections`, `metadata`), each written in a different shape per vendor and
read by the frontend through label maps and coercions it keeps itself
(`AMENITY_LABELS`, `CARRIER_LABELS`, the cell-signal pair-or-number rule).
Three frontend season parsers and two rating readers consume `season`,
`reservable`, and `rating_reviews`, fields no endpoint emits; that is dead
code, not a leak.

## Design

Two deliverables, one pattern: **normalize on write, one shape on read, the
repo is the only codec, a migration rewrites stored rows once so decode is
strict.**

### 2a. Campsites (this plan)

**Domain types** (`model/domain/`, one per file, `@Serializable`):

```kotlin
data class CatalogPhoto(val url: String)                       // renamed from CampgroundPhoto; shared by both tables
data class CampsiteAttribute(val name: String, val value: String? = null)
```

`CampgroundColumnJson` is renamed `CatalogColumnJson`; it is the codec for
every typed JSONB column on both catalog tables.

**Candidate and row.** `CampsiteUpsertCandidate` and `Campsite` carry:

| Field | Type | Column |
| --- | --- | --- |
| `equipment` | `List<String>` | `equipment` JSONB, canonical `["Tent", "RV"]` |
| `photos` | `List<CatalogPhoto>` | `photos` JSONB, canonical `[{"url": ...}]` |
| `attributes` | `List<CampsiteAttribute>` | new `attributes` JSONB NOT NULL DEFAULT `[]` |
| `description` | `String?` | new `description` TEXT, plain text (HTML stripped in the ETL) |
| `minPeople` | `Int?` | new `min_people` INT |

`schedule`, `price`, and `source_payload` stay `JsonElement` on the row; none of
them reaches the API any more. `Campsite` stops being `@Serializable`: it is a
row model, and the wire shape is the DTO below.

**ETL promotion.**

| Vendor | Promoted in 2a |
| --- | --- |
| rec.gov | `min_num_people` → `minPeople`; `attributes[]{attribute_name, attribute_value}` → `attributes`, with `Fire Pit`, `Picnic Table`, `Max Num of Vehicles`, `Driveway Length`, `Max Vehicle Length`, `Accessible`/`ADA` promoted to the existing typed columns when the value parses; `campsite_reserve_type` and `type_of_use` become attributes named `Reserve type` and `Type of use`; `equipment_types` → `equipment` strings. `_roadtrip_tags` and `CampsiteTags.kt` are deleted. |
| Aspira | `minCapacity` → `minPeople`; `localizedValues[0].description` → `description` (tags stripped); `definedAttributes` → `attributes` (dictionary name, first value label or scalar value); `allowedEquipment` → `equipment` names. |
| Campflare | `description` → `description`; `equipment[].name` → `equipment`; `photos[]` through the same URL precedence `CampflareCampgroundFields` uses. |
| ReserveAmerica, ReserveCalifornia | nothing new upstream; `equipment`/`photos` empty lists. |

A shared `service/etl/framework/HtmlText.stripTags(s)` does the one
tag-stripping the frontend did (`<[^>]*>` → space, collapse whitespace, trim);
the ReserveCalifornia highlight splitter uses it too.

**Migration `V56__typed_campsite_columns.sql`.** Adds the three columns;
rewrites `equipment` (string or `{name}` object → string) and `photos`
(`url`/`large_url`/`medium_url`/`small_url`/`original_url` → `{url}`) in
place; backfills `min_people` from `source_payload->'min_capacity'` (Aspira)
and `source_payload->'_roadtrip_tags'->'capacity'->>'min'` (rec.gov),
`description` from `source_payload->>'description'` with tags stripped by
`regexp_replace`, and `attributes` from Aspira's `defined_attributes[]` and
rec.gov's `_roadtrip_tags->'attributes'` (slug keys humanized: `_` → space,
initcap). Idempotent. Rows the ETLs write after this change already carry the
canonical shape.

**Closed DTO** (`model/api/CampsiteDto.kt`), the only campsite wire shape,
used by `PoiCampsitesResponseSchema.campsites` and
`AvailabilityWatchSchema.campsite`:

```
id, campground_id, name, kind, kind_listed, loop_name, description,
min_people, max_people, max_cars, driveway_length, max_rv_length, max_trailer_length,
firepit, picnic_table, ada_accessible, water_hookups, electric_hookups, sewer_hookups, pull_through,
equipment: [String], attributes: [{name, value}], photo_url,
data_provider, data_provider_ref, booking_provider
```

Nullable scalars are omitted when absent (`explicitNulls = false`, as today).
`source_payload`, `schedule`, `price`, `created_at`, `updated_at`,
`deleted_at`, `booking_provider_ref`, `latitude`, `longitude` are not on the
wire; the drawer never read them, and the raw payload is served nowhere.
`CampsiteRepo.SearchFilters.rawContainsJson` (a `source_payload @>` filter with
no caller) is deleted.

**Frontend.** `api/campsite-api.ts` `Campsite` becomes the closed mirror of
`CampsiteDto` with no index signature. `site-detail-facts.ts` reads typed
fields only: Loop = `loop_name`; Type = `kind_listed ?? kind`; Capacity from
`min_people`/`max_people`; Equipment = `equipment.join(', ')` (first four);
feature chips = the boolean columns, the measurements, then `attributes` as
`Name: value` or `Name`; description = `description` clamped; photo =
`photo_url`. `rawPayload`, `findImageUrl`, `attributeLabels`, `firstString`,
`stripHtml`, and the six-spelling capacity readers are deleted;
`site-list-rows.ts` reuses `capacityLabel` from one place. `matrix-rows.ts`,
`SiteList.tsx`, `SiteDetail.tsx`, and `lib/watch-format.ts` drop their
`typeof`/`String()` guards. Tests are rewritten against DTO-shaped fixtures.

**Deferred to 2b:** a `CampsiteKind` enum. Existing watches persist raw kind
strings in `campsite_filters`, so the vocabulary change needs its own filter
migration; the DTO keeps `kind` and `kind_listed` as strings for now.

### 2b. Campground bags (next plan)

- `amenities` → `List<CampgroundAmenity(key: AmenityKey, present: Boolean, detail: String?)>` with `AmenityKey` an enum carrying the wire key and the user-facing label and negative label (`camp_store`, `dump_station`, `electric_hookups`, `fires_allowed`, `pets_allowed`, `sewer_hookups`, `showers`, `toilets`, `trash`, `water`, `water_hookups`, `wifi`, plus `OTHER` with the vendor label). Campflare keys and ReserveCalifornia highlight labels map in their ETLs. The API emits `{key, label, present, detail}`; the frontend deletes `AMENITY_LABELS`, `NEGATIVE_AMENITY_LABELS`, and `titleCase`.
- `cell_service` → `List<CarrierSignal(carrier: Carrier, average: Double, count: Int?)>`; Campflare's bare scalars normalize in its ETL; the carrier slug map moves from the rec.gov ETL into the enum. The frontend deletes `CARRIER_LABELS` and the pair-or-number rule.
- `metadata` → `CampgroundMetadata(activities: List<String>, rating: CampgroundRating(average, count)?, lastUpdated: String?)`; Aspira, BC Parks, and ReserveAmerica stop duplicating their provenance extras into it (they are already in `source_payload`). The API gains `activities` and `rating`; `last_verified` stays.
- `price` → `CampgroundPrice(minimum, maximum, currency)`, `default_campsite_schedule` → `CampgroundSchedule(checkIn, checkOut)`, `alerts` → `List<CampgroundAlert(title, body)>`, each with the Campflare precedence lists the frontend keeps today.
- `connections` stays `JsonElement` (Campflare-only vendor slugs rendered verbatim) with a note.
- `parent_name` becomes a campground column (Aspira/BC Parks leaf parent, rec.gov `RECAREA[0].RecAreaName`) so the frontend deletes its three parent-park regexes.
- Dead frontend code deleted outright: the three season parsers, `reservable`, `rating_reviews` array/JSON-string readers, and their tests and fixtures.
- Migration `V57` rewrites the seven columns; `CampsiteKind` enum plus the `campsite_filters` migration.

## Risks

- **Strict decode on read.** A campsite row whose `equipment` is a legacy shape the migration did not anticipate throws on read. The migration is written against every shape the five ETLs and the fixtures produce, and the plan includes a dry-run count query for production.
- **Attributes from rec.gov re-import.** The V56 backfill humanizes slug keys (`fire_pit` → `Fire Pit`); the next import overwrites them with the vendor's own names. Values are unchanged.
- **Wire break.** `campsites[]` loses every field the drawer did not read, and the watch `campsite` embed shrinks the same way. The only consumers are `frontend/src` (updated in the same change) and the `campsite-stats` Grafana dashboard, which reads columns via SQL, not the API.
- **Frontend fixture debt.** Nine test files seed `source_payload`; they are rewritten to the DTO shape rather than tolerated.
