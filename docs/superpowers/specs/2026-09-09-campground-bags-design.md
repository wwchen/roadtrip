# Campground bags and vocabularies: typed columns, enums, one shape on the wire

**Date:** 2026-09-09
**Status:** Design for phase 2b of the audit (`2026-09-09-etl-promotion-design.md` section 2b; issues #732, #741)
**Follows:** #724 (typed campground JSONB), #744 (typed provider seam), #745 (typed campsites)

## What the data actually looks like (local catalog, 2026-09-09)

| Column | Writers and shapes |
| --- | --- |
| `amenities` | Campflare: object of 12 canonical keys with `true`/`false`/JSON `null` plus `toilet_kind: string`. ReserveCalifornia: object of free-text highlight labels → `true` (`Restrooms`, `Rinse Showers`, `Dump Station`, `Store-convenience`, `Fire Rings`, `Picnic Tables`, `Surfing`, `Museum`, …). rec.gov, Aspira, BC Parks, ReserveAmerica: `{}`. |
| `cell_service` | rec.gov: `{verizon|att|tmobile|sprint: {avg, count}}`. Campflare: `{verizon|att|tmobile|uscell: number}`. Others `{}`. |
| `metadata` | rec.gov: `activities: [String]`, `rating_reviews: {avg, count}`. ReserveCalifornia: `activities`, `facility_unit_types` (dead; the sites ETL reads the DTO, not the row). Campflare: `last_updated`, `has_availability_alerts`, `has_availability_data`, `has_campsite_level_data` (no reader). Aspira, BC Parks, ReserveAmerica: provenance extras duplicated from `source_payload`. |
| `price` | Campflare only: `{currency, currency_code, minimum, maximum}`. |
| `default_campsite_schedule` | Campflare only: `{check_in_time, check_out_time, uniform}`. |
| `alerts` | Campflare only (815 rows): `[{title, content, end_date, source_url}]`. |
| `connections` | Campflare only: vendor slugs (`ridb_facility_id`, …), rendered verbatim as chips. |

The API emits none of `season`, `reservable`, `sites`, `rating_reviews`, `activities`, or `parent_name`, so the frontend's season parsers (three copies), `parseRating`, the `reservable` hint, and the parent-park link-title regexes are dead or guessing.

Campsite `kind` is the vendor's string: rec.gov `STANDARD NONELECTRIC` / `STANDARD ELECTRIC` / `TENT ONLY NONELECTRIC` / `RV NONELECTRIC` / `RV ELECTRIC` / `WALK TO` / `GROUP STANDARD NONELECTRIC` / `GROUP TENT ONLY AREA NONELECTRIC` / `EQUESTRIAN NONELECTRIC` / `CABIN NONELECTRIC` / `CABIN ELECTRIC` / `BOAT IN` / `HIKE TO` / `GROUP SHELTER NONELECTRIC` / `GROUP PICNIC AREA` / `PICNIC` / `MOORING` / `PARKING` / `SHELTER NONELECTRIC` / `OVERNIGHT SHELTER NONELECTRIC` / `LOOKOUT` / `YURT` / `ANCHORAGE` / `ZONE`; Campflare `standard` / `rv` / `tent-only` / `cabin` / `group` / `walk-to` / `equestrian` / `water-access` / `management`; Aspira dictionary names (`Campsite`, `Campsite/Seasonal`, `Overflow`, `Backcountry Site`, `Backcountry Zone`, `oTENTik`, `Cabin`, `Rustic Cabin`, `Yurt`, `Group Campground`, `Group Camp`, `Marina`, `Mooring Buoy`, `Day Use Facility`, `Equestrian`, `Vacation House`, and ~30 more); ReserveCalifornia unit-type names (49 in the local catalog: `Campsite` 4184, `Tent Campsite`, `Premium Campsite`, `Hook Up (E/W/S) Campsite`, `Primitive Campsite`, `Hike/Bike Campsite`, `Hike In Primitive Campsite`, `Group Campsite`, `Premium Cabin (8 People)`, `Bike In Campsite`, `Boat In Campsite`, `Mini-Group Campsite`, `Group Day Use`, `Tent Only - Walk-In`, `Yurt (6 ppl)`, `Premium Cottage_3`, `Floating Camp Campsite`, `Equestrian Campsite`, `Equestrain Group Tent Primitive Campsite`, `Group Dailyuse (B)`, `Environmental Campsite`, `ADA Campsite`, …); ReserveAmerica `site` (the default). Filters (`site_type` query parameter on the campsites and bulk-availability routes; watch `campsite_filters.site_type` as a string or array, read by `WatchScopeResolver`) compare these strings exactly, so a filter for `tent` can never match a rec.gov row. The frontend never writes `site_type` into a watch (watch creation sends `campsite_filters: {}`); its only site-type UI is the drawer's Type dropdown over `kind`.

## Design

Same pattern as #724 and #745: normalize on write, one shape on read, the repo is the only codec, a migration canonicalizes stored shapes so decode is strict, and re-import fills anything new.

### Domain types (`model/domain/`, one per file, `@Serializable`)

```kotlin
enum class AmenityKey(val wire: String, val label: String, val negativeLabel: String? = null) {
    CAMP_STORE("camp_store", "Camp store"), DUMP_STATION("dump_station", "Dump station"),
    ELECTRIC_HOOKUPS("electric_hookups", "Electric hookups", "No electric hookups"),
    FIRES_ALLOWED("fires_allowed", "Fires allowed"), PETS_ALLOWED("pets_allowed", "Pets allowed"),
    SEWER_HOOKUPS("sewer_hookups", "Sewer hookups", "No sewer hookups"),
    SHOWERS("showers", "Showers", "No showers"), TOILETS("toilets", "Toilets", "No toilets"), TRASH("trash", "Trash"),
    WATER("water", "Water", "No water"), WATER_HOOKUPS("water_hookups", "Water hookups", "No water hookups"),
    WIFI("wifi", "Wi-Fi"), OTHER("other", "Other");
}
data class CampgroundAmenity(val key: AmenityKey, val present: Boolean = true, val detail: String? = null)
// detail: TOILETS carries Campflare's toilet_kind ("vault"); OTHER carries the vendor label verbatim.

enum class Carrier(val wire: String, val label: String) {
    VERIZON("verizon", "Verizon"), ATT("att", "AT&T"), TMOBILE("tmobile", "T-Mobile"),
    SPRINT("sprint", "Sprint"), US_CELLULAR("uscell", "US Cellular");
}
data class CarrierSignal(val carrier: Carrier, val average: Double, val count: Int? = null)
data class CampgroundRating(val average: Double, val count: Int)
data class CampgroundMetadata(val activities: List<String> = emptyList(), val rating: CampgroundRating? = null, val lastUpdated: String? = null)
data class CampgroundPrice(val minimum: Double? = null, val maximum: Double? = null, val currency: String? = null)
data class CampgroundSchedule(val checkIn: String? = null, val checkOut: String? = null)
data class CampgroundAlert(val title: String? = null, val body: String, val endsOn: String? = null, val sourceUrl: String? = null)
```

Enums serialize as their `wire` value (`@SerialName` per constant). Stored JSON keys: `amenities` becomes `[{"key","present","detail"}]`; `cell_service` becomes `[{"carrier","average","count"}]`; `metadata` keeps `activities`, gains `rating: {average, count}` and `last_updated`, drops everything else; `price` keys `minimum`/`maximum`/`currency`; `default_campsite_schedule` keys `check_in`/`check_out`; `alerts` keys `title`/`body`/`ends_on`/`source_url`. `connections` stays `JsonElement` (Campflare vendor slugs rendered verbatim). `campgrounds.parent_name TEXT` is a new column (Aspira and BC Parks leaf parent, rec.gov `RECAREA[0].RecAreaName`); the ETLs fill it on import.

### Amenity mapping in the ETLs

Campflare: key → `AmenityKey` by wire value; `true`/`false` → `present`; JSON `null` → omitted; `toilet_kind` → `detail` on `TOILETS` (and `TOILETS` present). ReserveCalifornia labels, case-insensitive after trimming: `Restrooms`, `Toilet, Accessible`, `Comfort Station` → `TOILETS`; `Showers`, `Rinse Showers` → `SHOWERS`; `Dump Station` → `DUMP_STATION`; `Store-convenience`, `Store - Convenience` → `CAMP_STORE`; `Fire Rings` → `FIRES_ALLOWED`; anything else → `OTHER` with the label as `detail`. The activity split (`activityHints`) stays where it is; activities go to `metadata.activities`.

### Carrier mapping

rec.gov's carrier name map moves onto `Carrier` (`"Verizon"`, `"AT&T"`, `"T-Mobile"`, `"Sprint"`, `"US Cellular"` → wire); unknown carriers are dropped as today. Campflare's bare number per key → `CarrierSignal(average = n, count = null)`.

### Migration `V57__typed_campground_bags.sql`

Schema: `ADD COLUMN parent_name TEXT`. Canonicalization only, so strict decode never meets a legacy shape between migrate and import: `amenities` object → array (Campflare keys by wire; `toilet_kind` folded into `TOILETS.detail`; unknown keys → `OTHER` with the key as detail; JSON `null` dropped); `cell_service` object → array (`{avg,count}` and bare-number forms); `metadata` reduced to `activities`/`rating`/`last_updated`; `price` `currency_code` → `currency`; schedule `check_in_time`/`check_out_time` → `check_in`/`check_out`, `uniform` dropped; alerts `content` → `body`, `end_date` → `ends_on`. Idempotent; no backfill of `parent_name` (re-import). `make data-import` after deploy.

### API (`PoiCategoryDetailSchema`)

`amenities: List<AmenityDto(key, label, present, detail)>`, `cell_coverage: List<CarrierSignalDto(carrier, label, average, count)>`, `activities: List<String>`, `rating: RatingDto(average, count)?`, `price: PriceDto?`, `schedule: ScheduleDto?`, `alerts: List<AlertDto>`, `parent_name: String?`. `metadata` leaves the wire (`last_verified` stays). `connections`, `upstream`, `links`, `management`, `contact`, `address` unchanged. Labels ride on the wire because the audit's rule is that vendor vocabularies live in one backend registry; the frontend renders `label`, `negativeLabel` is applied server-side (`label` is already "No showers" when `present` is false). An absent amenity whose key has no negative label states no fact, so it is not served at all.

### Frontend

`domain/poi/campground-detail.ts`: `amenityTags` reads `{label, present}`; `carrierSignals` reads the typed list (bucketing stays, it is presentation); `rating` reads `rating`; `activityList` reads `activities`; price and schedule formatting stay but read the typed keys; alerts read `title`/`body`. Deleted: `AMENITY_LABELS`, `NEGATIVE_AMENITY_LABELS`, `CARRIER_LABELS`, `titleCase` if no other caller, `seasonVerdict` and its parser, `parentParkName` and its three regexes (the page uses `parent_name`), `rating_reviews` handling. `features/trip/trip-cards.ts`: `parseRating`, `compactSeasonLabel`, and the `season`/`reservable`/`rating`/`sites` card fields are deleted along with their `TripResults.tsx` rendering. Tests and `CampgroundPanel.test.tsx` fixtures are rewritten to the typed shapes.

### Campsite kind vocabulary

```kotlin
enum class CampsiteKind(val wire: String, val label: String) {
    STANDARD("standard", "Standard"), TENT("tent", "Tent"), RV("rv", "RV"), CABIN("cabin", "Cabin"),
    GROUP("group", "Group"), WALK_IN("walk_in", "Walk-in"), BOAT_IN("boat_in", "Boat-in"),
    EQUESTRIAN("equestrian", "Equestrian"), BACKCOUNTRY("backcountry", "Backcountry"),
    DAY_USE("day_use", "Day use"), OTHER("other", "Other");
}
```

`campsites.kind` stores the wire value; `kind_listed` keeps the vendor string (already does; ReserveCalifornia and Campflare copy the vendor value into it, rec.gov and Aspira already do). Per-vendor mapping in one `CampsiteKinds` object under `service/etl/framework`: rec.gov by leading token (`GROUP` → GROUP, `TENT ONLY` → TENT, `RV` → RV, `CABIN`/`YURT`/`LOOKOUT`/`SHELTER`/`OVERNIGHT SHELTER` → CABIN, `WALK TO`/`HIKE TO` → WALK_IN, `BOAT IN`/`MOORING`/`ANCHORAGE` → BOAT_IN, `EQUESTRIAN` → EQUESTRIAN, `STANDARD` → STANDARD, `ZONE` → BACKCOUNTRY, `PICNIC`/`PARKING`/`DAY USE` → DAY_USE, else OTHER) and a trailing `ELECTRIC` token that is not `NONELECTRIC` sets `electricHookups = true` on the candidate; Campflare by exact value (`standard` → STANDARD, `tent-only` → TENT, `rv` → RV, `cabin` → CABIN, `group` → GROUP, `walk-to` → WALK_IN, `water-access` → BOAT_IN, `equestrian` → EQUESTRIAN, `management` → OTHER); Aspira by dictionary name (`Campsite`/`Campsite/Seasonal`/`Overflow` → STANDARD, `Cabin`/`Rustic Cabin`/`Deluxe Cabin`/`Backcountry Cabin`/`Yurt`/`oTENTik`/`Ôasis`/`MicrOcube`/`Teepee`/`Prospector Tent`/`Platform Tent`/`Adirondack`/`Equipped Camping`/`Vacation House` → CABIN, names starting `Backcountry` or `Wilderness` → BACKCOUNTRY, names starting `Group` → GROUP, `Equestrian` → EQUESTRIAN, `Marina`/`Mooring Buoy`/`Marine Trail`/`Annual Marina` → BOAT_IN, names starting `Day Use`/`Conference`/`Retreat`, and `Daily Fishing`/`Guided Event`/`Hiking Trip`/`Ferry` → DAY_USE, else OTHER); ReserveCalifornia by substring of the trimmed name, case-insensitive, first rule wins: `Day Use`/`Dailyuse` → DAY_USE; `Group` (includes `Mini-Group`) → GROUP; `Equestrian`/`Equestrain` → EQUESTRIAN; `Cabin`/`Cottage`/`Yurt` → CABIN; `Boat In`/`Floating Camp` → BOAT_IN; `Hike`/`Bike`/`Walk-In` → WALK_IN; `Hook Up` → RV, and a `(E` in the name sets `electricHookups = true`; `Tent` → TENT; `Campsite` → STANDARD (covers `Premium`, `Primitive`, `Environmental`, `ADA`); else OTHER. So `Equestrian Group Primitive Campsite` → GROUP (group first, as for rec.gov), `Tent Only - Walk-In` → WALK_IN, `Premium Hook Up (E/W) Campsite` → RV with electric; ReserveAmerica → OTHER (no upstream type).

Filters: `site_type` (campsites route, bulk availability) and watch `campsite_filters.site_type` are parsed against `CampsiteKind` wire values; an unknown value on a request is a `bad_request`, an unknown value in a stored watch filter matches nothing (as today). Migration `V58__campsite_kind_wire.sql` rewrites `campsites.kind` from the vendor strings to wire values with the same mapping in SQL (a `CASE` per `data_provider` over `kind`; rows the SQL cannot classify become `other` and the next import reclassifies them), and rewrites `availability_watch.campsite_filters -> 'site_type'` (string or array) through the same `CASE`. `CampsiteDto.kind` carries the wire value and gains `kind_label`; the drawer's Type dropdown lists labels and filters on wire values; watch notifications keep showing `kind_listed` (the vendor's words) and fall back to the label.

## Risks

- **Strict decode on read.** Every legacy shape observed in the local catalog is covered by V57's canonicalization; the migration is rehearsed on a `pg_dump` copy of `campgrounds` and `campsites` before merge.
- **Watch filters.** A stored watch whose `site_type` maps to `other` keeps matching only `other` rows after V58; the PR lists how many watches carry a `site_type` at all (local rehearsal counts it).
- **Kind mapping is a judgment table.** Aspira's dictionary has about 47 names; unmapped ones become `OTHER` and still show their `kind_listed` in the drawer. The table lives in one object with a test that asserts every vendor value listed in this spec maps as written.
- **Wire break.** POI detail `amenities`, `cell_coverage`, `price`, `schedule`, `alerts` change shape and `metadata` disappears; `campsites[].kind` values change. The frontend ships in the same PR; no other consumer.
