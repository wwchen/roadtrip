# Geometry policy: declared per ETL, recorded per pin

**Phase:** 5b of the architecture audit (`2026-09-09-architecture-audit.md`, finding 11). Issue #740.
**Branch:** `refactor/geometry-policy`, base `2ec2218c` (master after #750).

## Problem

Aspira's `/api/maps` carries booking IDs but no coordinates, so every Aspira-backed campground ETL joins each leaf to a sibling geometry feed by name. Today that join is configured nowhere and recorded almost nowhere:

- `AspiraCampgroundsEtl.detectGeometrySource` picks the parser by substring on the input slug (`uscampgrounds`, `bcparks`, `places`, `accommodation`, else a generic GeoJSON reader). Every `inputs:` entry that is not maps, inventory or dictionaries is *assumed* to be geometry. A typo'd slug becomes a silent GeoJSON source that indexes nothing.
- The YAML `inputs:` list order doubles as the geometry preference order (first writer wins on a normalized name). Parks Canada relies on `apca-accommodation` (campground points) preceding `apca-places` (park polygon centroids). Nothing declares this.
- `state_filter` is an untyped `args` string read by the registry factory and threaded into exactly one of five sources. `parent_name_fallback: true` on the Parks Canada row is read by nothing; the parent fallback runs unconditionally for every tenant. `EtlEntry.args` is a `Map<String, String>` and the registry is decoded with kaml `strictMode = false`, so a dead or misspelled key boots cleanly.
- The match ladder (exact → Jaccard ≥ 0.5 → parent name) is a private constant. `AspiraLeafMatchKind` is written into the `source_payload` blob as `match_kind`, but nothing records which feed supplied the point, what index name it matched, or the score. An exact match against a park centroid is indistinguishable from an exact match against a campground point.
- `BcParksCampgroundsEtl` re-implements the Strapi parse that `BcParksStrapiSource` already performs; the aspira-package copy is unreachable.

Live data (326 Aspira/BC campgrounds): 247 exact, 38 fuzzy, 41 parent. Washington is 23% fuzzy against a nationwide CSV; Parks Canada is 38% pinned at park centroids. None of it is queryable except by digging into `source_payload`.

## Goal

Each Aspira-backed campground ETL row declares its geometry sources and match policy in `poi-registry.yaml`, typed and validated at boot. The code reads only the declaration; no slug sniffing. Every emitted candidate carries a typed `GeometryProvenance` (match kind, source input, matched name, score) that is persisted in its own column so fuzzy and centroid pins can be reviewed with one query.

Behaviour on today's data must be unchanged: the same 326 rows with the same coordinates and the same match-kind distribution after a re-import (verified live on a DB copy).

## Design

### 1. YAML: a `geometry:` block on the ETL row

```yaml
  - name: Washington State Parks
    ...
    etls:
      - slug: aspira-wa-campgrounds
        adapter: AspiraCampgroundsEtl
        inputs: [aspira-maps-wa, uscampgrounds, aspira-inventory-wa, aspira-dictionaries-wa]
        args:
          host: washington.goingtocamp.com
          tenant: wa
        geometry:
          sources:
            - input: uscampgrounds
              format: uscampgrounds_csv
              state: WA
          match:
            parent_fallback: true

      - slug: aspira-bc-campgrounds
        adapter: BcParksCampgroundsEtl
        inputs: [aspira-maps-bc, bcparks-strapi, aspira-inventory-bc, aspira-dictionaries-bc]
        args:
          tenant: bc
          host: camping.bcparks.ca
        geometry:
          sources:
            - input: bcparks-strapi
              format: bcparks_strapi
          match:
            parent_fallback: true

      - slug: aspira-pc-campgrounds
        adapter: AspiraCampgroundsEtl
        inputs: [aspira-maps-pc, apca-accommodation, apca-places, aspira-inventory-pc, aspira-dictionaries-pc]
        args:
          host: reservation.pc.gc.ca
          tenant: pc
        geometry:
          sources:
            - input: apca-accommodation
              format: geojson_points
              name_property: Name_e
            - input: apca-places
              format: arcgis_centroids
          match:
            parent_fallback: true
```

Semantics:

- `sources` is an ordered list. **Order is preference**: when two sources carry the same normalized name, the earlier source's point wins. This is the rule the code already applies; the block makes it declared instead of implied by `inputs:`.
- `format` is a closed enum: `uscampgrounds_csv`, `bcparks_strapi`, `arcgis_centroids`, `geojson_points`. Each format is one parser class. `apca-accommodation` is plain GeoJSON with a non-default name property, so the separate `ApcaAccommodationSource` collapses into `GeoJsonFeaturesSource` plus `name_property`.
- `state` is a per-source filter, valid only on `uscampgrounds_csv` (the only nationwide feed). It replaces `args.state_filter`. Declaring it on a format that cannot honour it is a boot error, which is the uniform treatment the audit asks for: a filter is either applied or rejected, never dropped.
- `name_property` is valid only on `geojson_points` (default: `name`, then `Name`).
- `match.fuzzy_threshold` defaults to `0.5` (the current constant, now a named default in the model). Valid range `(0, 1]`. `match.parent_fallback` defaults to `false` and replaces the dead `parent_name_fallback` arg; all three rows set it `true` to preserve today's output.
- `args` for these two adapters is exactly `{host, tenant}`; `state_filter` and `parent_name_fallback` are removed from the registry.

### 2. Model and validation (`model/metadata/registry`)

New file `GeometryPolicy.kt`:

```kotlin
@Serializable
data class GeometryPolicy(
    val sources: List<GeometrySourceSpec>,
    val match: MatchPolicy = MatchPolicy(),
)

@Serializable
data class GeometrySourceSpec(
    val input: String,
    val format: GeometryFormat,
    @SerialName("name_property") val nameProperty: String? = null,
    val state: String? = null,
)

@Serializable
enum class GeometryFormat {
    @SerialName("uscampgrounds_csv") USCAMPGROUNDS_CSV,
    @SerialName("bcparks_strapi") BCPARKS_STRAPI,
    @SerialName("arcgis_centroids") ARCGIS_CENTROIDS,
    @SerialName("geojson_points") GEOJSON_POINTS,
}

@Serializable
data class MatchPolicy(
    @SerialName("fuzzy_threshold") val fuzzyThreshold: Double = DEFAULT_FUZZY_THRESHOLD,
    @SerialName("parent_fallback") val parentFallback: Boolean = false,
) { companion object { const val DEFAULT_FUZZY_THRESHOLD = 0.5 } }
```

`EtlEntry` gains `val geometry: GeometryPolicy? = null`.

`PoiRegistry.validate` gains, for every ETL row (both sections):

- Adapters in `GEOMETRY_ADAPTERS` (`AspiraCampgroundsEtl`, `BcParksCampgroundsEtl`) must declare `geometry` with at least one source; any other adapter declaring `geometry` is an error.
- Every `sources[].input` must appear in the row's `inputs:`; no input may be declared twice.
- `state` only with `uscampgrounds_csv`; `name_property` only with `geojson_points`; `fuzzy_threshold` in `(0, 1]`.
- `BcParksCampgroundsEtl` must declare exactly one source and it must be `bcparks_strapi` (the ETL joins Strapi metadata through the same rows).
- Adapters with an entry in `ACCEPTED_ARG_KEYS` (the same two adapters: `{host, tenant}`) reject any other `args` key. This is what turns the `parent_name_fallback` failure class into a boot error.
- The registry is decoded with kaml `strictMode = true`, so an unknown key anywhere in the file (including inside `geometry:`) fails boot. If the current registry has keys the model does not declare, the model or the registry is fixed so it decodes strictly; that is in scope.

Each rule has a test in `PoiRegistryValidatorTest` using a minimal inline registry, and the production registry must decode strictly (existing load test covers it).

### 3. Sources (`service/etl/vendors/aspira`)

`GeometrySource` becomes a pure reader:

```kotlin
sealed interface GeometrySource {
    fun points(): Sequence<NamedPoint>
}

data class NamedPoint(val name: String, val latitude: Double, val longitude: Double)
```

`normalize` + `putIfAbsent` leave the five parsers and live in one place, `GeometryIndex`:

```kotlin
data class GeometryPoint(val latitude: Double, val longitude: Double, val source: String)

object GeometryIndex {
    /** Normalized name → first point seen, walking sources in declared (preference) order. */
    fun build(sources: List<Pair<String, GeometrySource>>, log: Logger, etlSlug: String): Map<String, GeometryPoint>
}
```

(The per-source contribution log line moves here from `AspiraCampgroundsEtl.indexGeometry`.)

`GeometrySources.forSpec(spec: GeometrySourceSpec, envelopes: List<Envelope>): GeometrySource` is the only `when` over `GeometryFormat`; it replaces `detectGeometrySource`. Classes:

| format | class | note |
|---|---|---|
| `uscampgrounds_csv` | `UsCampgroundsCsvSource(envelopes, state)` | unchanged filter semantics |
| `bcparks_strapi` | `BcParksStrapiSource(envelopes)` | now parses full `BcParksStrapiRow`s; `points()` derives from `rows()` |
| `arcgis_centroids` | `ArcGisCentroidSource(envelopes)` | renamed from `ApcaPlacesCentroidSource` (a format, not a feed) |
| `geojson_points` | `GeoJsonFeaturesSource(envelopes, nameProperty)` | absorbs `ApcaAccommodationSource`, which is deleted |

`BcParksStrapiRow` moves to the aspira package next to its parser (or `BcParksStrapiSource` moves to `vendors/bcparks`; the plan picks one and the BC ETL's private `parseStrapiRows` is deleted either way). One Strapi parser.

### 4. Matching

`AspiraLeafMatcher<T>(byName, nonBookableResourceLocationIds, policy: MatchPolicy)`:

- Threshold from `policy.fuzzyThreshold`; parent fallback only when `policy.parentFallback`.
- The fuzzy pass computes each score once and keeps the first maximum in index order (deterministic: source preference, then feed row order). Ties are therefore resolved by the same preference rule as exact collisions; document it.
- `AspiraLeafMatch<T>` gains `matchedName: String` (the index key the leaf resolved to) and `score: Double?` (Jaccard for `FUZZY`, else null).
- `Tally` unchanged.

### 5. Provenance on the candidate, persisted in its own column

`model/domain/GeometryProvenance.kt`:

```kotlin
@Serializable
data class GeometryProvenance(
    @SerialName("match_kind") val matchKind: String,   // "exact" | "fuzzy" | "parent"
    val source: String,                                 // the geometry input slug
    @SerialName("matched_name") val matchedName: String,
    val score: Double? = null,
)
```

`CampgroundUpsertCandidate.geometryProvenance: GeometryProvenance? = null`. Both Aspira ETLs populate it; other providers leave it null. `match_kind` is removed from `sourcePayload` (one truth). `matchKind` stays the ETL-side enum; the provenance carries its `label`.

Migration `V62__campground_geometry_provenance.sql`: `ALTER TABLE campgrounds ADD COLUMN geometry_provenance jsonb;` plus a partial index `ON campgrounds ((geometry_provenance->>'match_kind')) WHERE geometry_provenance IS NOT NULL`. No backfill: `make data-import` populates it (deploy step). `CampgroundRepo` writes it on insert and on conflict update, and reads it into `Campground.geometryProvenance: GeometryProvenance?`. A repo test round-trips it. Nothing in the API serves it yet.

Review query (documented):

```sql
SELECT name, geometry_provenance->>'match_kind' AS kind, geometry_provenance->>'source' AS source,
       geometry_provenance->>'matched_name' AS matched, geometry_provenance->>'score' AS score
FROM campgrounds WHERE geometry_provenance->>'match_kind' IN ('fuzzy', 'parent')
ORDER BY (geometry_provenance->>'score')::double precision NULLS LAST;
```

### 6. ETL wiring

- `AspiraCampgroundsEtl(etlSlug, aspiraTenant, geometry: GeometryPolicy)`. The dead `dataProviderValue` parameter is removed. `geometrySourcesFor(inputs)` maps `geometry.sources` to `(input, GeometrySources.forSpec(...))`; `parse` fails with `ParseResult.Bad` if any declared source input has no envelopes, or if an `inputs:` slug is neither a declared geometry source nor the maps/inventory/dictionaries feed (closes the negative-selection hole).
- `BcParksCampgroundsEtl(etlSlug, aspiraTenant, geometry: GeometryPolicy)`: builds `BcParksStrapiSource` from its single declared source, indexes points through `GeometryIndex`, matches with the policy, and looks the Strapi row up by `match.matchedName` for description/photos/contact. Records provenance identically.
- `ProductionTerminalEtlRegistry` passes `entry.geometry ?: error("<slug>: geometry policy is required for <adapter>")` (validation guarantees presence; the error is the belt).
- Maps/inventory/dictionaries are still resolved by the existing substring rule. Out of scope here (backlog note), but the new "every input must be accounted for" parse check means an unrecognised input fails loudly instead of becoming a geometry source.

### 7. Tests

- `PoiRegistryValidatorTest`: one case per rule in §2, plus strict-mode rejection of an unknown key inside `geometry:`.
- `GeometryPolicy` decoding test: the three production rows decode to the expected typed values (drive `PoiRegistry.loadResource("poi-registry.yaml")`).
- `UsCampgroundsCsvSourceTest`: the "WA terminal reads state from the registry" case now reads `geometry.sources[0].state` through `productionTerminalEtlDefinitions` (same pattern, new path).
- `AspiraLeafMatcherTest`: parent fallback off → miss; threshold override honoured; `matchedName`/`score` populated; tie keeps the first index entry.
- `GeometryIndexTest`: preference order, first writer wins, `source` recorded per point.
- `AspiraCampgroundsEtlTest`: provenance on the candidate (`exact` with source slug; `parent` with source and matched park name); `match_kind` absent from `sourcePayload`; an undeclared input fails parse.
- `BcParksCampgroundsEtlTest`: provenance pinned (kind, source `bcparks-strapi`, matched name); `strapi_orcs`/`strapi_url` in payload pinned (currently unpinned).
- `CampgroundRepoTest` (or existing repo test): `geometry_provenance` round-trip and update-on-conflict.
- `ProductionTerminalEtlRegistryTest`: both Aspira terminals build with their policies.

### 8. Docs

- `docs/adding-a-data-source.md`: replace the `args: {}` one-liner's `state_filter` example; add a "Geometry-joined adapters" section documenting the `geometry:` block, formats, preference order, filters, and the boot errors.
- `docs/reservation-providers/aspira.md`: fix the three stale `AspiraJoinByNameEtl` references; document the match ladder, the policy knobs, and the review query.
- `docs/backend-architecture.md`: one line under the ETL section: geometry policy is registry-declared; provenance column.

## Out of scope (backlog, note on #740)

- Resolving maps/inventory/dictionaries inputs by role rather than substring.
- Using `/api/resourceLocation` `gpsCoordinates` as an in-tenant geometry source (would remove the name join for park-level pins).
- `CampgroundLocation.region/country` set only by the BC ETL.
- Serving provenance on the API or an admin review page.
- Emitting misses (today dropped; the candidate's coordinates are non-null).

## Verification

1. `make qa` green.
2. Live, on a `pg_dump` copy (`roadtrip_p7`): apply V62 by booting the branch backend against the copy; run `make data-import` for the three Aspira rows against the copy; compare `(name, location, match kind)` for the 326 rows before/after: zero coordinate diffs, identical kind distribution (247/38/41), `geometry_provenance` populated on every Aspira/BC row and null elsewhere; the review query returns the 79 non-exact rows with a source on each.
3. Boot with a deliberately bad registry (a `state` on the PC row) fails with the expected message.
