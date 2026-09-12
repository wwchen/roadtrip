# Geometry Policy: Declared per ETL, Recorded per Pin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Aspira-backed campground ETL row declares its geometry sources and match policy in `poi-registry.yaml`, typed and validated at boot; no code sniffs a slug to pick a parser; and every emitted candidate carries a typed `GeometryProvenance` persisted in its own `campgrounds` column.

**Architecture:** A new `GeometryPolicy` model hangs off `EtlEntry` (`geometry:` block: an ordered `sources` list of `{input, format, name_property?, state?}` plus a `match` policy), validated in `PoiRegistry.validate` and decoded with kaml `strictMode = true`. `GeometrySource` becomes a pure reader (`points(): Sequence<NamedPoint>`); `GeometryIndex` owns normalization and first-writer-wins merging; `GeometrySources.forSpec` is the only `when` over `GeometryFormat`. `AspiraLeafMatcher` takes the `MatchPolicy` and reports `matchedName` + `score`. Both ETLs stamp a `GeometryProvenance` onto the candidate, `CampgroundRepo` writes and reads it in a new `geometry_provenance` JSONB column added by `V62`, and `match_kind` leaves `source_payload`.

**Tech Stack:** Kotlin 2 / Ktor / Koin / kaml (`com.charleskorn.kaml`) / kotlinx.serialization / jOOQ + Postgres (Flyway migrations, Testcontainers via `SharedDbTest`) / JUnit 5 + kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-11-geometry-policy-design.md`. Audit finding 11 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issue #740. Follows phase 5a, `docs/superpowers/plans/2026-09-11-backend-seams.md`.

## Resolutions

Where the spec left a choice or its letter fought the repo's conventions, this plan decided. Each decision is load-bearing for the task that implements it.

1. **`BcParksStrapiRow` moves into `service/etl/vendors/aspira/`; `BcParksStrapiSource` stays there.** The spec offers either direction. `vendors/bcparks` already imports eight symbols from `vendors/aspira` (`AspiraLeaf`, `AspiraLeafMatcher`, `AspiraLeavesWalk`, `normalize`, …) and `vendors/aspira` imports nothing from `vendors/bcparks`. `GeometrySources.forSpec` lives in `aspira` and must construct the Strapi parser, so moving the parser to `bcparks` would add a second, opposing package edge. Moving the row instead keeps the dependency one-directional. `BcParksCampgroundsDto` and `BcParksCampgroundsEtl` import `BcParksStrapiRow` from `aspira`, exactly as they already import `AspiraLeaf`. Task 2.
2. **Removing `state_filter` from the YAML in Task 1 is covered by a compatibility read in the same task, replaced in Task 3.** `productionTerminalEtlDefinitions` is a `by lazy` over the real YAML that `UsCampgroundsCsvSourceTest` drives, so the moment `args.state_filter` disappears the WA terminal silently loses its state filter and that test fails. Task 1's factory therefore reads the state out of the new typed block (`entry.geometry?.sources?.firstOrNull { it.format == USCAMPGROUNDS_CSV }?.state`) and still passes it as the old `stateFilter` constructor argument. Task 3 deletes that read along with the constructor argument. Nothing is left half-finished across a task boundary: after Task 1 the build is green and behaviour is identical.
3. **The geometry/args validation runs *after* `validateBookingProviders`.** `validateBookingProviders` captures `before = errs.size` on entry and skips its own tenant cross-check when it added errors itself. Adding geometry errors before it would not break that (the guard compares against its own entry point), but adding them inside it would. A separate `validateAdapterPolicies` called after both `validateEtlSection` calls keeps `an unrelated registry error does not hide the tenant cross-check` honest. Task 1.
4. **`BcParksCampgroundsEtl.etlSlug` loses its `DEFAULT_ETL_SLUG` default and becomes the first required parameter.** The default existed only so `BcParksCampgroundsEtlTest` could construct the ETL without a slug; the spec's signature is `(etlSlug, aspiraTenant, geometry)`. The test passes the real slug, which it needs anyway for `TransformCtx` lookups. Task 3.
5. **`BcParksStrapiSource` memoizes its parse.** It serves both `rows()` (metadata) and `points()` (geometry) off the same envelopes, and the BC ETL uses both in one run. A `by lazy` list parses the Strapi pages once per instance. Task 2.
6. **No `.conf` sibling for `V62`.** `ALTER TABLE … ADD COLUMN` of a nullable column is metadata-only and a plain `CREATE INDEX` (no `CONCURRENTLY`) runs inside a transaction, so unlike `V61` this migration needs no `executeInTransaction=false`. Task 5.
7. **No manual jOOQ codegen step.** `CampgroundRepo` binds `campgrounds` through raw SQL strings, not generated table classes, so no generated type has to change. The generated classes are not committed (`build/generated/jooq/main`), `generateSchemaSourceOnCompilation` is `true`, and `generateJooq` declares the migration directory as an input — so adding `V62` regenerates them automatically on the next compile. Docker must be running. Task 5.

## Global Constraints

These are the spec's binding rules. Every task's requirements implicitly include this section.

- **No slug sniffing for geometry.** After Task 3 no code chooses a geometry parser from the shape of an input slug. `AspiraCampgroundsEtl.detectGeometrySource` is deleted, and `GeometrySources.forSpec` is the only `when` over `GeometryFormat`.
- **The `geometry:` block shape and field names**, verbatim:
  - `geometry.sources` — an ordered list. **Order is preference**: when two sources carry the same normalized name, the earlier source's point wins.
  - `geometry.sources[].input` — a data_source slug that must appear in the row's `inputs:`.
  - `geometry.sources[].format` — a closed enum. One format, one parser class.
  - `geometry.sources[].name_property` — optional, valid only on `geojson_points` (default: `name`, then `Name`).
  - `geometry.sources[].state` — optional, valid only on `uscampgrounds_csv` (the only nationwide feed). It replaces `args.state_filter`.
  - `geometry.match.fuzzy_threshold` — default `0.5`, valid range `(0, 1]`.
  - `geometry.match.parent_fallback` — default `false`; replaces the dead `parent_name_fallback` arg.
- **`format` enum values**, exactly these four: `uscampgrounds_csv`, `bcparks_strapi`, `arcgis_centroids`, `geojson_points`.
- **`parent_fallback` is set `true` on all three rows** (WA, BC, PC) to preserve today's output.
- **`ACCEPTED_ARG_KEYS` = `{host, tenant}`** for `AspiraCampgroundsEtl` and `BcParksCampgroundsEtl`. Any other `args` key on those two adapters is a boot error. `state_filter` and `parent_name_fallback` are removed from the registry.
- **Strict YAML decode.** `PoiRegistry`'s kaml `Yaml` is configured `strictMode = true`, so an unknown key anywhere in the file — including inside `geometry:` — fails boot. If the shipped registry has a key the model does not declare, the model or the registry is fixed so it decodes strictly; that is in scope, and reverting to `strictMode = false` is not.
- **`GeometryProvenance` field names**, verbatim: `match_kind` (`"exact" | "fuzzy" | "parent"`), `source` (the geometry input slug), `matched_name`, `score` (Jaccard for `fuzzy`, else null). `matchKind` stays the ETL-side enum; the provenance carries its `label`.
- **`match_kind` is removed from `sourcePayload`.** One truth. Both ETLs.
- **`V62__campground_geometry_provenance.sql` adds the column and a partial index; no backfill.** `make data-import` populates it (deploy step). `V61` is the highest applied migration today. Never edit an applied migration.
- **Behaviour on today's data must be unchanged:** the same 326 rows with the same coordinates and the same match-kind distribution (247 exact / 38 fuzzy / 41 parent) after a re-import.
- **Layering rules, verbatim from `AGENTS.md`:**
  - Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
  - Services take a `UnitOfWork` (when several writes must land together) or the repo handles they use — never a `DSLContext`. `org.jooq` appears only under `repo/`, `db/`, and `di/InfraModule.kt`; `LayeringGuardTest` fails the build otherwise.
  - SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
  - Layering is `routes -> service -> repo`: routes are the HTTP shell and do not add new route-to-repo paths.
  - Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.
  - **No inline magic constants.** Every numeric and string literal introduced here is a named `const val`: the CSV column indices, the `Point` geometry type, the default name properties, the input-role markers, the format wire names.
  - **No half-finished implementations.** If a method exists, it works.
- **Comments short and rare.** Keep existing KDoc where its claim stays true; delete the KDoc paragraphs this refactor falsifies (the `AspiraCampgroundsEtl` header's "dispatches on the slug shape" and "≥0.5 Jaccard"). Do not narrate the refactor in comments.
- **detekt/ktlint:** a non-`const` private top-level `val` needs `@Suppress("TopLevelPropertyNaming")` (the precedent is `PoiRegistry.kt`); `const val` uses `SCREAMING_SNAKE`. Lines stay under 140 characters. `ClassOrdering` puts `companion object` last.
- **Backend gate, run at the end of every task:** `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` from the worktree root. Docker must be running (Testcontainers for `SharedDbTest`, and `generateJooq` for the compile).
- One commit per task, conventional prefix, `Refs #740`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `model/metadata/registry/GeometryPolicy.kt` (new) | The typed `geometry:` block: `GeometryPolicy`, `GeometrySourceSpec`, `GeometryFormat`, `MatchPolicy` | 1 |
| `model/metadata/registry/EtlEntry.kt` | Gains `geometry: GeometryPolicy?` | 1 |
| `model/metadata/registry/PoiRegistry.kt` | Strict decode; `GEOMETRY_ADAPTERS`, `ACCEPTED_ARG_KEYS`, `validateAdapterPolicies` | 1 |
| `resources/poi-registry.yaml` | The three rows' `geometry:` blocks; `state_filter` / `parent_name_fallback` removed | 1 |
| `service/etl/vendors/aspira/NamedPoint.kt` (new) | A name and the point it names | 2 |
| `service/etl/vendors/aspira/GeometryPoint.kt` (new) | An indexed point plus the input slug that supplied it | 2 |
| `service/etl/vendors/aspira/GeometrySource.kt` | `sealed interface GeometrySource { fun points(): Sequence<NamedPoint> }` | 2 |
| `service/etl/vendors/aspira/GeometryIndex.kt` (new) | Normalization + first-writer-wins merge + the per-source contribution log | 2 |
| `service/etl/vendors/aspira/GeometrySources.kt` (new) | The one `when` over `GeometryFormat` | 2 |
| `service/etl/vendors/aspira/UsCampgroundsCsvSource.kt` | CSV reader; `state` filter | 2 |
| `service/etl/vendors/aspira/BcParksStrapiSource.kt` | The one Strapi parser: `rows()` + `points()` | 2 |
| `service/etl/vendors/aspira/BcParksStrapiRow.kt` (moved) | The parsed Strapi row | 2 |
| `service/etl/vendors/aspira/ArcGisCentroidSource.kt` (renamed) | ArcGIS centroid-mode reader | 2 |
| `service/etl/vendors/aspira/GeoJsonFeaturesSource.kt` | GeoJSON point reader with a configurable name property | 2 |
| `service/etl/vendors/aspira/ApcaAccommodationSource.kt` (deleted) | Absorbed by `GeoJsonFeaturesSource` + `name_property` | 2 |
| `service/etl/vendors/aspira/AspiraCampgroundsEtl.kt` | Reads the policy; parse-time input accounting; provenance | 3, 4, 6 |
| `service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt` | Reads the policy; `GeometryIndex`; provenance | 3, 4, 7 |
| `service/etl/vendors/aspira/AspiraLeafMatcher.kt` | Policy-driven ladder; `matchedName` + `score` | 4 |
| `model/domain/GeometryProvenance.kt` (new) | The persisted provenance shape | 5 |
| `resources/db/migration/V62__campground_geometry_provenance.sql` (new) | Column + partial index | 5 |
| `repo/CampgroundRepo.kt` | Writes and reads `geometry_provenance` | 5 |

---

### Task 1: The `geometry:` block — model, validation, strict decode, and the three rows

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/GeometryPolicy.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/EtlEntry.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt`, `backend/src/main/resources/poi-registry.yaml`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistryValidatorTest.kt`

**Interfaces:**
- Consumes: `com.charleskorn.kaml.Yaml` / `YamlConfiguration(strictMode: Boolean)`; `PoiRegistry.loadString(content: String, sourceName: String = "POI registry"): PoiRegistry`; `PoiRegistry.loadResource(resourceName: String, …): PoiRegistry`; `EtlEntry(slug, adapter, inputs, args)`; the existing private `PoiRegistry.EtlRowRef(name: String, etls: List<EtlEntry>)`.
- Produces (package `ca.floo.roadtrip.model.metadata.registry`):
  - `data class GeometryPolicy(val sources: List<GeometrySourceSpec>, val match: MatchPolicy = MatchPolicy())`
  - `data class GeometrySourceSpec(val input: String, val format: GeometryFormat, val nameProperty: String? = null, val state: String? = null)`
  - `enum class GeometryFormat(val wire: String) { USCAMPGROUNDS_CSV, BCPARKS_STRAPI, ARCGIS_CENTROIDS, GEOJSON_POINTS }`
  - `data class MatchPolicy(val fuzzyThreshold: Double = MatchPolicy.DEFAULT_FUZZY_THRESHOLD, val parentFallback: Boolean = false)` with `const val DEFAULT_FUZZY_THRESHOLD: Double = 0.5`
  - `EtlEntry.geometry: GeometryPolicy?` (defaults to `null`)

- [ ] **Step 1: Write the failing validator tests**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistryValidatorTest.kt`. Put the two new top-level constants next to the existing `BLANK_CODE`:

```kotlin
/** Six-space list indent plus two: the column an `etls:` entry's own keys sit at. */
private const val ETL_KEY_INDENT = "        "

/** Two deeper again: the column an `args:` key sits at. */
private const val ARG_KEY_INDENT = "          "

/** The default `args` for the WA fixture row: exactly what the adapter accepts. */
@Suppress("TopLevelPropertyNaming")
private val WA_ARGS = listOf("host: washington.goingtocamp.com", "tenant: wa")

/** The production WA geometry block, the shape every positive fixture reuses. */
private const val WA_GEOMETRY_BLOCK =
    """
    geometry:
      sources:
        - input: uscampgrounds
          format: uscampgrounds_csv
          state: WA
      match:
        parent_fallback: true
    """
```

Then add this helper and these test methods inside `class PoiRegistryValidatorTest` (place the helper just above the closing brace, beside the existing private helpers):

```kotlin
    /**
     * A minimal one-row WA registry. [etlBody] is spliced in at the `etls:`
     * entry's own key column, so each geometry test varies only the block it
     * is about.
     */
    private fun waRegistry(
        etlBody: String,
        adapter: String = "AspiraCampgroundsEtl",
        args: List<String> = WA_ARGS,
    ): String =
        BOOKING_PROVIDERS + "\n" +
            """
            data_sources:
              - slug: aspira-maps-wa
                name: Aspira WA maps
                fetcher:
                  executor: python3
                  filename: scripts/fetch_aspira.py
                  output_dir_prefix: data/raw/aspira-maps-wa
              - slug: uscampgrounds
                name: uscampgrounds.info
                fetcher:
                  executor: python3
                  filename: scripts/fetch_uscampgrounds.py
                  output_dir_prefix: data/raw/uscampgrounds
            poi_data:
              - name: Washington State Parks
                category: campground
                agency: WA State Parks
                etls:
                  - slug: aspira-wa-campgrounds
                    adapter: $adapter
                    inputs: [aspira-maps-wa, uscampgrounds]
                    args:
                      ${args.joinToString("\n" + ARG_KEY_INDENT)}
            """.trimIndent() +
            etlBody.trimIndent().let { if (it.isBlank()) "" else "\n" + it.prependIndent(ETL_KEY_INDENT) } + "\n"

    private fun waError(
        etlBody: String,
        adapter: String = "AspiraCampgroundsEtl",
        args: List<String> = WA_ARGS,
    ): String =
        assertFailsWith<IllegalArgumentException> {
            PoiRegistry.loadString(waRegistry(etlBody, adapter, args))
        }.message!!

    @Test
    fun `a declared geometry block decodes to typed sources and match policy`() {
        val registry = PoiRegistry.loadString(waRegistry(WA_GEOMETRY_BLOCK))

        val geometry = registry.poiData.single().etls.single().geometry!!
        assertEquals(
            listOf(
                GeometrySourceSpec(
                    input = "uscampgrounds",
                    format = GeometryFormat.USCAMPGROUNDS_CSV,
                    state = "WA",
                ),
            ),
            geometry.sources,
        )
        assertEquals(MatchPolicy(parentFallback = true), geometry.match)
    }

    @Test
    fun `a geometry adapter declaring no geometry block fails`() {
        assertTrue(
            waError("").contains(
                "poi_data 'Washington State Parks' etl 'aspira-wa-campgrounds' adapter 'AspiraCampgroundsEtl' " +
                    "must declare 'geometry' with at least one source",
            ),
        )
    }

    @Test
    fun `a geometry source naming an input the etl does not declare fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: apca-places
                      format: arcgis_centroids
                """,
            )
        assertTrue(
            message.contains(
                "poi_data 'Washington State Parks' etl 'aspira-wa-campgrounds' " +
                    "geometry source input 'apca-places' is not one of the etl's inputs",
            ),
            message,
        )
    }

    @Test
    fun `the same geometry input declared twice fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                    - input: uscampgrounds
                      format: geojson_points
                """,
            )
        assertTrue(message.contains("declares geometry source input 'uscampgrounds' twice"), message)
    }

    @Test
    fun `state on a format that cannot honour it fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: geojson_points
                      state: WA
                """,
            )
        assertTrue(
            message.contains(
                "geometry source 'uscampgrounds' declares 'state', which only 'uscampgrounds_csv' honours",
            ),
            message,
        )
    }

    @Test
    fun `name_property on a format that cannot honour it fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                      name_property: Name_e
                """,
            )
        assertTrue(
            message.contains(
                "geometry source 'uscampgrounds' declares 'name_property', which only 'geojson_points' honours",
            ),
            message,
        )
    }

    @Test
    fun `a fuzzy threshold outside the open-zero-to-one range fails`() {
        val tooLow =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                  match:
                    fuzzy_threshold: 0.0
                """,
            )
        assertTrue(tooLow.contains("match.fuzzy_threshold=0.0 is outside (0.0, 1.0]"), tooLow)

        val tooHigh =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                  match:
                    fuzzy_threshold: 1.5
                """,
            )
        assertTrue(tooHigh.contains("match.fuzzy_threshold=1.5 is outside (0.0, 1.0]"), tooHigh)
    }

    /** `CampflareCampgroundsEtl` has no `ACCEPTED_ARG_KEYS` entry, so the WA args pass through it unjudged. */
    @Test
    fun `an adapter that joins no geometry may not declare a geometry block`() {
        val message =
            waError(
                etlBody = WA_GEOMETRY_BLOCK,
                adapter = "CampflareCampgroundsEtl",
            )
        assertTrue(
            message.contains(
                "adapter 'CampflareCampgroundsEtl' does not join geometry, so it must not declare 'geometry'",
            ),
            message,
        )
    }

    @Test
    fun `BcParksCampgroundsEtl must declare exactly one bcparks_strapi source`() {
        val message =
            waError(
                etlBody =
                    """
                    geometry:
                      sources:
                        - input: uscampgrounds
                          format: uscampgrounds_csv
                    """,
                adapter = "BcParksCampgroundsEtl",
            )
        assertTrue(
            message.contains(
                "adapter 'BcParksCampgroundsEtl' must declare exactly one geometry source with format 'bcparks_strapi'",
            ),
            message,
        )
    }

    /** The dead `parent_name_fallback` arg's whole failure class, now a boot error. */
    @Test
    fun `an args key the adapter does not accept fails`() {
        val message =
            waError(
                etlBody = WA_GEOMETRY_BLOCK,
                args = WA_ARGS + "parent_name_fallback: true",
            )
        assertTrue(
            message.contains(
                "adapter 'AspiraCampgroundsEtl' does not accept arg 'parent_name_fallback' (accepted: host, tenant)",
            ),
            message,
        )
    }

    @Test
    fun `strict decoding rejects an unknown key inside the geometry block`() {
        val err =
            assertFailsWith<Exception> {
                PoiRegistry.loadString(
                    waRegistry(
                        """
                        geometry:
                          sources:
                            - input: uscampgrounds
                              format: uscampgrounds_csv
                          matcher:
                            parent_fallback: true
                        """,
                    ),
                )
            }
        assertTrue(err.message!!.contains("matcher"), err.message)
    }

    /** The shipped rows, decoded. This is the test the production YAML edit has to satisfy. */
    @Test
    fun `the three shipped geometry rows decode to their declared policies`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val bySlug = registry.poiData.flatMap { it.etls }.associateBy { it.slug }

        assertEquals(
            GeometryPolicy(
                sources = listOf(GeometrySourceSpec("uscampgrounds", GeometryFormat.USCAMPGROUNDS_CSV, state = "WA")),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-wa-campgrounds").geometry,
        )
        assertEquals(
            GeometryPolicy(
                sources = listOf(GeometrySourceSpec("bcparks-strapi", GeometryFormat.BCPARKS_STRAPI)),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-bc-campgrounds").geometry,
        )
        assertEquals(
            GeometryPolicy(
                sources =
                    listOf(
                        GeometrySourceSpec("apca-accommodation", GeometryFormat.GEOJSON_POINTS, nameProperty = "Name_e"),
                        GeometrySourceSpec("apca-places", GeometryFormat.ARCGIS_CENTROIDS),
                    ),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-pc-campgrounds").geometry,
        )
        assertEquals(mapOf("host" to "reservation.pc.gc.ca", "tenant" to "pc"), bySlug.getValue("aspira-pc-campgrounds").args)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.metadata.registry.PoiRegistryValidatorTest' --offline`
Expected: compilation failure — `Unresolved reference: GeometrySourceSpec`, `GeometryFormat`, `MatchPolicy`, `GeometryPolicy`.

- [ ] **Step 3: Create the model**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/GeometryPolicy.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val USCAMPGROUNDS_CSV_FORMAT = "uscampgrounds_csv"
private const val BCPARKS_STRAPI_FORMAT = "bcparks_strapi"
private const val ARCGIS_CENTROIDS_FORMAT = "arcgis_centroids"
private const val GEOJSON_POINTS_FORMAT = "geojson_points"

/**
 * A geometry-joined ETL row's declaration: which sibling feeds carry the
 * coordinates its own vendor payload lacks, in which preference order, and how
 * leaf names are resolved against them.
 */
@Serializable
data class GeometryPolicy(
    /** Preference order: the first source to claim a normalized name keeps it. */
    val sources: List<GeometrySourceSpec>,
    val match: MatchPolicy = MatchPolicy(),
)

/**
 * One geometry feed. [state] is honoured only by [GeometryFormat.USCAMPGROUNDS_CSV]
 * and [nameProperty] only by [GeometryFormat.GEOJSON_POINTS]; declaring either
 * elsewhere is a boot error rather than a silently dropped filter.
 */
@Serializable
data class GeometrySourceSpec(
    val input: String,
    val format: GeometryFormat,
    @SerialName("name_property") val nameProperty: String? = null,
    val state: String? = null,
)

/** One member, one parser class. [wire] is the spelling the YAML uses. */
@Serializable
enum class GeometryFormat(
    val wire: String,
) {
    @SerialName(USCAMPGROUNDS_CSV_FORMAT)
    USCAMPGROUNDS_CSV(USCAMPGROUNDS_CSV_FORMAT),

    @SerialName(BCPARKS_STRAPI_FORMAT)
    BCPARKS_STRAPI(BCPARKS_STRAPI_FORMAT),

    @SerialName(ARCGIS_CENTROIDS_FORMAT)
    ARCGIS_CENTROIDS(ARCGIS_CENTROIDS_FORMAT),

    @SerialName(GEOJSON_POINTS_FORMAT)
    GEOJSON_POINTS(GEOJSON_POINTS_FORMAT),
}

/** The match ladder's knobs: exact name, then Jaccard overlap, then the parent park's name. */
@Serializable
data class MatchPolicy(
    @SerialName("fuzzy_threshold") val fuzzyThreshold: Double = DEFAULT_FUZZY_THRESHOLD,
    @SerialName("parent_fallback") val parentFallback: Boolean = false,
) {
    companion object {
        /** Minimum Jaccard token overlap for a fuzzy name match. */
        const val DEFAULT_FUZZY_THRESHOLD: Double = 0.5
    }
}
```

- [ ] **Step 4: Hang the block off `EtlEntry`**

Replace the body of `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/EtlEntry.kt`:

```kotlin
package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.Serializable

@Serializable
data class EtlEntry(
    val slug: String,
    val adapter: String,
    val inputs: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
    /** Required for the adapters that join geometry by name; rejected for the rest. */
    val geometry: GeometryPolicy? = null,
)
```

- [ ] **Step 5: Add the validation and strict decode**

In `backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt`:

Replace the `ARG_HOST` constant and the two adapter maps around it with this block (the two existing maps gain the named adapter constants; their contents are otherwise unchanged):

```kotlin
private const val ARG_HOST = "host"
private const val ARG_TENANT = "tenant"

private const val ASPIRA_CAMPGROUNDS_ADAPTER = "AspiraCampgroundsEtl"
private const val BC_PARKS_CAMPGROUNDS_ADAPTER = "BcParksCampgroundsEtl"

private const val MIN_FUZZY_THRESHOLD_EXCLUSIVE = 0.0
private const val MAX_FUZZY_THRESHOLD_INCLUSIVE = 1.0

/** Tenant-scoped adapter name → the vendor whose tenant its args must name. */
@Suppress("TopLevelPropertyNaming")
private val TENANT_SCOPED_ADAPTER_PROVIDERS =
    mapOf(
        ASPIRA_CAMPGROUNDS_ADAPTER to BookingProvider.ASPIRA,
        "AspiraCampsitesEtl" to BookingProvider.ASPIRA,
        BC_PARKS_CAMPGROUNDS_ADAPTER to BookingProvider.ASPIRA,
        "ReserveAmericaCampgroundsEtl" to BookingProvider.RESERVEAMERICA,
        "ReserveAmericaSitesEtl" to BookingProvider.RESERVEAMERICA,
    )

/**
 * Adapters whose `transform` reads `args.host` and fails the run without it.
 * Boot is where that should be caught, not the first row-insert.
 */
@Suppress("TopLevelPropertyNaming")
private val HOST_REQUIRED_ADAPTERS =
    setOf(
        ASPIRA_CAMPGROUNDS_ADAPTER,
        BC_PARKS_CAMPGROUNDS_ADAPTER,
    )

/** Adapters that join their vendor's leaves to a sibling geometry feed by name. */
@Suppress("TopLevelPropertyNaming")
private val GEOMETRY_ADAPTERS =
    setOf(
        ASPIRA_CAMPGROUNDS_ADAPTER,
        BC_PARKS_CAMPGROUNDS_ADAPTER,
    )

/**
 * Adapter name → the complete `args` key set it accepts. A key outside the set
 * is a boot error: a dead or misspelled arg used to boot cleanly and do nothing.
 */
@Suppress("TopLevelPropertyNaming")
private val ACCEPTED_ARG_KEYS =
    mapOf(
        ASPIRA_CAMPGROUNDS_ADAPTER to setOf(ARG_HOST, ARG_TENANT),
        BC_PARKS_CAMPGROUNDS_ADAPTER to setOf(ARG_HOST, ARG_TENANT),
    )
```

Also replace `TENANT_ARG_KEYS`'s `"tenant"` literal with `ARG_TENANT`:

```kotlin
@Suppress("TopLevelPropertyNaming")
private val TENANT_ARG_KEYS =
    mapOf(
        BookingProvider.ASPIRA to ARG_TENANT,
        BookingProvider.RESERVEAMERICA to "contract",
    )
```

In `validate()`, immediately after the second `validateEtlSection(...)` call and before the `// Global cycle detection` comment, insert:

```kotlin
        validateAdapterPolicies(POI_DATA_SECTION, poiData.map { EtlRowRef(it.name, it.etls) }, errs)
        validateAdapterPolicies(CAMPSITE_DATA_SECTION, campsiteData.map { EtlRowRef(it.name, it.etls) }, errs)
```

Add these two private methods just below `validateEtlTenantArgs`:

```kotlin
    /**
     * The `geometry:` block and the closed `args` key set for the adapters that
     * join geometry by name. Runs after [validateBookingProviders] so nothing
     * here can suppress that method's own tenant cross-check.
     */
    private fun validateAdapterPolicies(
        label: String,
        rows: List<EtlRowRef>,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            for (etl in row.etls) {
                val where = "$label '${row.name}' etl '${etl.slug}'"
                ACCEPTED_ARG_KEYS[etl.adapter]?.let { accepted ->
                    for (key in etl.args.keys - accepted) {
                        errs += "$where adapter '${etl.adapter}' does not accept arg '$key' " +
                            "(accepted: ${accepted.sorted().joinToString()})"
                    }
                }
                if (etl.adapter !in GEOMETRY_ADAPTERS) {
                    if (etl.geometry != null) {
                        errs += "$where adapter '${etl.adapter}' does not join geometry, so it must not declare 'geometry'"
                    }
                    continue
                }
                val geometry = etl.geometry
                if (geometry == null || geometry.sources.isEmpty()) {
                    errs += "$where adapter '${etl.adapter}' must declare 'geometry' with at least one source"
                    continue
                }
                validateGeometrySources(where, etl, geometry, errs)
                val threshold = geometry.match.fuzzyThreshold
                if (threshold <= MIN_FUZZY_THRESHOLD_EXCLUSIVE || threshold > MAX_FUZZY_THRESHOLD_INCLUSIVE) {
                    errs += "$where match.fuzzy_threshold=$threshold is outside " +
                        "($MIN_FUZZY_THRESHOLD_EXCLUSIVE, $MAX_FUZZY_THRESHOLD_INCLUSIVE]"
                }
            }
        }
    }

    private fun validateGeometrySources(
        where: String,
        etl: EtlEntry,
        geometry: GeometryPolicy,
        errs: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        for (source in geometry.sources) {
            if (source.input !in etl.inputs) {
                errs += "$where geometry source input '${source.input}' is not one of the etl's inputs"
            }
            if (!seen.add(source.input)) {
                errs += "$where declares geometry source input '${source.input}' twice"
            }
            if (source.state != null && source.format != GeometryFormat.USCAMPGROUNDS_CSV) {
                errs += "$where geometry source '${source.input}' declares 'state', " +
                    "which only '${GeometryFormat.USCAMPGROUNDS_CSV.wire}' honours"
            }
            if (source.nameProperty != null && source.format != GeometryFormat.GEOJSON_POINTS) {
                errs += "$where geometry source '${source.input}' declares 'name_property', " +
                    "which only '${GeometryFormat.GEOJSON_POINTS.wire}' honours"
            }
        }
        if (etl.adapter == BC_PARKS_CAMPGROUNDS_ADAPTER) {
            val only = geometry.sources.singleOrNull()
            if (only == null || only.format != GeometryFormat.BCPARKS_STRAPI) {
                errs += "$where adapter '$BC_PARKS_CAMPGROUNDS_ADAPTER' must declare exactly one geometry source " +
                    "with format '${GeometryFormat.BCPARKS_STRAPI.wire}'"
            }
        }
    }
```

Finally, flip the decoder to strict mode in the companion:

```kotlin
        private val yaml =
            Yaml(
                configuration =
                    com.charleskorn.kaml.YamlConfiguration(strictMode = true),
            )
```

- [ ] **Step 6: Edit the three registry rows**

In `backend/src/main/resources/poi-registry.yaml`, replace the three rows exactly as shown.

Washington State Parks:

```diff
   - name: Washington State Parks
     category: campground
     subcategory: state
     agency: WA State Parks
     etls:
       - slug: aspira-wa-campgrounds
         adapter: AspiraCampgroundsEtl
         inputs: [aspira-maps-wa, uscampgrounds, aspira-inventory-wa, aspira-dictionaries-wa]
         args:
           host: washington.goingtocamp.com
           tenant: wa
-          state_filter: WA
+        geometry:
+          sources:
+            - input: uscampgrounds
+              format: uscampgrounds_csv
+              state: WA
+          match:
+            parent_fallback: true
```

BC Provincial Parks:

```diff
   - name: BC Provincial Parks
     category: campground
     subcategory: provincial
     agency: BC Parks
     etls:
       - slug: aspira-bc-campgrounds
         adapter: BcParksCampgroundsEtl
         inputs: [aspira-maps-bc, bcparks-strapi, aspira-inventory-bc, aspira-dictionaries-bc]
         args:
           tenant: bc
           host: camping.bcparks.ca
+        geometry:
+          sources:
+            - input: bcparks-strapi
+              format: bcparks_strapi
+          match:
+            parent_fallback: true
```

Parks Canada:

```diff
   - name: Parks Canada
     category: campground
     subcategory: federal
     agency: Parks Canada
     etls:
       - slug: aspira-pc-campgrounds
         adapter: AspiraCampgroundsEtl
         inputs: [aspira-maps-pc, apca-accommodation, apca-places, aspira-inventory-pc, aspira-dictionaries-pc]
         args:
           host: reservation.pc.gc.ca
           tenant: pc
-          parent_name_fallback: true
+        geometry:
+          sources:
+            - input: apca-accommodation
+              format: geojson_points
+              name_property: Name_e
+            - input: apca-places
+              format: arcgis_centroids
+          match:
+            parent_fallback: true
```

- [ ] **Step 7: Keep the WA state filter wired through the typed block**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`, replace the `AspiraCampgroundsEtl` factory entry:

```kotlin
        "AspiraCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.ASPIRA) { entry ->
                campgroundSink(
                    AspiraCampgroundsEtl(
                        etlSlug = entry.slug,
                        dataProviderValue = DataProvider.ASPIRA,
                        aspiraTenant = entry.args.require("tenant"),
                        // Task 3 replaces this with the whole policy.
                        stateFilter =
                            entry.geometry
                                ?.sources
                                ?.firstOrNull { it.format == GeometryFormat.USCAMPGROUNDS_CSV }
                                ?.state,
                    ),
                )
            },
```

and add the import `import ca.floo.roadtrip.model.metadata.registry.GeometryFormat`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.metadata.registry.PoiRegistryValidatorTest' --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.UsCampgroundsCsvSourceTest' --tests 'ca.floo.roadtrip.service.etl.framework.ProductionTerminalEtlRegistryTest' --offline`
Expected: PASS.

If `the three shipped geometry rows decode` or `production poi-registry resource validates` fails with a kaml message naming an unknown property (`Unknown property 'x'. Known properties are: …`), the shipped registry has a key the model does not declare. Add that key to the model class the message names — do not restore `strictMode = false`.

- [ ] **Step 9: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/GeometryPolicy.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/EtlEntry.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistry.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt \
        backend/src/main/resources/poi-registry.yaml \
        backend/src/test/kotlin/ca/floo/roadtrip/model/metadata/registry/PoiRegistryValidatorTest.kt
git commit -F - <<'MSG'
feat(registry): geometry policy is declared per ETL row and validated at boot

The `geometry:` block types what `inputs:` order and a slug substring used to
imply: an ordered source list with a closed format enum, per-source filters
that are either honoured or rejected, and the match ladder's knobs. The
registry decodes strictly, so a dead arg no longer boots clean.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 2: Geometry sources become readers — `points()`, `GeometryIndex`, `GeometrySources`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/NamedPoint.kt`, `.../aspira/GeometryPoint.kt`, `.../aspira/GeometryIndex.kt`, `.../aspira/GeometrySources.kt`, `.../aspira/ArcGisCentroidSource.kt`, `.../aspira/BcParksStrapiRow.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ApcaAccommodationSource.kt`, `.../aspira/ApcaPlacesCentroidSource.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksStrapiRow.kt`
- Modify: `.../aspira/GeometrySource.kt`, `.../aspira/UsCampgroundsCsvSource.kt`, `.../aspira/BcParksStrapiSource.kt`, `.../aspira/GeoJsonFeaturesSource.kt`, `.../aspira/AspiraCampgroundsEtl.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, `.../bcparks/BcParksCampgroundsDto.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryIndexTest.kt` (new), `.../aspira/UsCampgroundsCsvSourceTest.kt`, `.../aspira/AspiraCampgroundsEtlTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`

**Interfaces:**
- Consumes: `GeometryFormat`, `GeometrySourceSpec` (Task 1); `ca.floo.roadtrip.model.metadata.Envelope`; the existing package-internal `normalize(name: String): String` and `csvSplit(line: String): List<String>` in `AspiraCampgroundsEtl.kt`.
- Produces (package `ca.floo.roadtrip.service.etl.vendors.aspira`):
  - `data class NamedPoint(val name: String, val latitude: Double, val longitude: Double)`
  - `data class GeometryPoint(val latitude: Double, val longitude: Double, val source: String)`
  - `sealed interface GeometrySource { fun points(): Sequence<NamedPoint> }`
  - `object GeometryIndex { fun build(sources: List<Pair<String, GeometrySource>>, log: Logger, etlSlug: String): Map<String, GeometryPoint> }`
  - `object GeometrySources { fun forSpec(spec: GeometrySourceSpec, envelopes: List<Envelope>): GeometrySource }`
  - `class UsCampgroundsCsvSource(envelopes: List<Envelope>, stateFilter: String? = null) : GeometrySource`
  - `class BcParksStrapiSource(envelopes: List<Envelope>) : GeometrySource` with `fun rows(): List<BcParksStrapiRow>`
  - `class ArcGisCentroidSource(envelopes: List<Envelope>) : GeometrySource`
  - `class GeoJsonFeaturesSource(envelopes: List<Envelope>, nameProperty: String? = null) : GeometrySource`
  - `data class BcParksStrapiRow(name, lat, lon, orcs, url, description, phone, photoUrl)` now in the `aspira` package
  - `AspiraCampgroundsEtl.geometrySourcesFor(inputs: InputBundle): List<Pair<String, GeometrySource>>` (unchanged signature)

- [ ] **Step 1: Write the failing `GeometryIndexTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryIndexTest.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

private const val ETL_SLUG = "geometry-index-test"

/**
 * The merge rule the YAML's `geometry.sources` order encodes: sources are
 * walked in declared order and the first point to claim a normalized name
 * keeps it, so campground-level feeds outrank park centroids. Each point
 * remembers the input slug that supplied it.
 */
class GeometryIndexTest {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun geoJson(
        name: String,
        lon: Double,
        lat: Double,
    ): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://geom", "method": "GET" },
              "response": { "status": 200 },
              "payload": {
                "type": "FeatureCollection",
                "features": [
                  { "type": "Feature",
                    "properties": { "name": ${Json.encodeToString(name)} },
                    "geometry": { "type": "Point", "coordinates": [$lon, $lat] } }
                ]
              }
            }
            """.trimIndent(),
        )

    @Test
    fun `the earlier source wins a name both sources carry`() {
        val index =
            GeometryIndex.build(
                listOf(
                    "apca-accommodation" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside", -115.49, 51.22))),
                    "apca-places" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside Campground", -115.00, 51.00))),
                ),
                log,
                ETL_SLUG,
            )

        assertEquals(GeometryPoint(51.22, -115.49, "apca-accommodation"), index[normalize("Two Jack Lakeside")])
    }

    @Test
    fun `a name only the later source carries is still indexed, tagged with that source`() {
        val index =
            GeometryIndex.build(
                listOf(
                    "apca-accommodation" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside", -115.49, 51.22))),
                    "apca-places" to GeoJsonFeaturesSource(listOf(geoJson("Banff National Park of Canada", -115.57, 51.18))),
                ),
                log,
                ETL_SLUG,
            )

        assertEquals(GeometryPoint(51.18, -115.57, "apca-places"), index[normalize("Banff National Park of Canada")])
    }

    @Test
    fun `a feature whose name normalizes to nothing is not indexed`() {
        val index = GeometryIndex.build(listOf("only" to GeoJsonFeaturesSource(listOf(geoJson("park", -1.0, 2.0)))), log, ETL_SLUG)

        assertEquals(emptyMap<String, GeometryPoint>(), index, "\"park\" is pure park-cruft; it must not index under the empty key")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.GeometryIndexTest' --offline`
Expected: compilation failure — `Unresolved reference: GeometryIndex`, `GeometryPoint`, and `GeoJsonFeaturesSource` taking one argument.

- [ ] **Step 3: Create the reader types and the index**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/NamedPoint.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

/** A raw name from a geometry feed and the point it names, before normalization. */
data class NamedPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryPoint.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

/** An indexed point and the geometry input slug that supplied it. */
data class GeometryPoint(
    val latitude: Double,
    val longitude: Double,
    val source: String,
)
```

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometrySource.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

/**
 * A geometry feed, read as a flat stream of named points. Normalization and
 * first-writer-wins merging belong to [GeometryIndex]; a source only parses.
 */
sealed interface GeometrySource {
    fun points(): Sequence<NamedPoint>
}
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryIndex.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import org.slf4j.Logger

/**
 * Merges the declared geometry sources into one normalized-name index.
 *
 * Sources are walked in declared order and the first point to claim a
 * normalized name keeps it, so `geometry.sources` order is preference order:
 * campground-level feeds declared before park-polygon centroids win whenever
 * both carry the same name.
 */
object GeometryIndex {
    fun build(
        sources: List<Pair<String, GeometrySource>>,
        log: Logger,
        etlSlug: String,
    ): Map<String, GeometryPoint> {
        val byName = LinkedHashMap<String, GeometryPoint>()
        for ((slug, source) in sources) {
            val before = byName.size
            for (point in source.points()) {
                val key = normalize(point.name)
                if (key.isEmpty()) continue
                byName.putIfAbsent(key, GeometryPoint(point.latitude, point.longitude, slug))
            }
            log.info(
                "{}: geometry input slug={} contributed {} new keys (total={})",
                etlSlug,
                slug,
                byName.size - before,
                byName.size,
            )
        }
        return byName
    }
}
```

- [ ] **Step 4: Convert the four parsers**

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSource.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val LONGITUDE_COL = 0
private const val LATITUDE_COL = 1
private const val NAME_COL = 4
private const val STATE_COL = 12
private const val MIN_COLS = STATE_COL + 1

/**
 * uscampgrounds.info — the payload is a CSV string.
 *
 * The file is nationwide, so [stateFilter] is what keeps a single-state tenant
 * from matching a same-named campground elsewhere: names repeat across states,
 * and the index keeps the first row it sees, so an unfiltered Washington leaf
 * could take South Dakota's coordinates. Null means index every state, which is
 * what the non-US tenants want.
 */
class UsCampgroundsCsvSource(
    private val envelopes: List<Envelope>,
    private val stateFilter: String? = null,
) : GeometrySource {
    override fun points(): Sequence<NamedPoint> =
        sequence {
            val wantedState = stateFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            for (env in envelopes) {
                val text = env.payload.jsonPrimitive.contentOrNull ?: continue
                for (line in text.lineSequence()) {
                    if (line.isBlank()) continue
                    val cols = csvSplit(line)
                    if (cols.size < MIN_COLS) continue
                    if (wantedState != null && cols[STATE_COL].trim().uppercase() != wantedState) continue
                    val lon = cols[LONGITUDE_COL].toDoubleOrNull() ?: continue
                    val lat = cols[LATITUDE_COL].toDoubleOrNull() ?: continue
                    val name = cols.getOrNull(NAME_COL)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    yield(NamedPoint(name, lat, lon))
                }
            }
        }
}
```

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/BcParksStrapiSource.kt` (this is the one Strapi parser; the copy in `BcParksCampgroundsEtl` is deleted in Step 6):

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BC Parks Strapi — paginated JSON pages, rows under `payload.data[]`.
 *
 * The single parser for the feed: [rows] is the full protected-area record the
 * BC ETL enriches campgrounds with, and [points] is the geometry projection of
 * the same rows. Parsed once per instance.
 */
class BcParksStrapiSource(
    private val envelopes: List<Envelope>,
) : GeometrySource {
    private val parsed: List<BcParksStrapiRow> by lazy { parseRows() }

    /** Every protected-area row that carries a name and a point. */
    fun rows(): List<BcParksStrapiRow> = parsed

    override fun points(): Sequence<NamedPoint> = parsed.asSequence().map { NamedPoint(it.name, it.lat, it.lon) }

    private fun parseRows(): List<BcParksStrapiRow> {
        val rows = mutableListOf<BcParksStrapiRow>()
        for (env in envelopes) {
            val data = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (row in data) {
                val o = row.jsonObject
                val name = o["protectedAreaName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val lat = o["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lon = o["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                rows +=
                    BcParksStrapiRow(
                        name = name,
                        lat = lat,
                        lon = lon,
                        orcs = o["orcs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        description = o.trimmedText("description"),
                        phone = o.trimmedText("parkContact"),
                        photoUrl = extractPhotoUrl(o["parkPhotos"] as? JsonArray),
                    )
            }
        }
        return rows
    }

    private fun JsonObject.trimmedText(key: String): String? =
        this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun extractPhotoUrl(photos: JsonArray?): String? {
        if (photos == null) return null
        val candidates =
            photos.mapNotNull { raw ->
                val p = raw as? JsonObject ?: return@mapNotNull null
                val url = p["imageUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val isActive = p["isActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val isFeatured = p["isFeatured"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val sortOrder = p["sortOrder"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE
                if (!isActive) return@mapNotNull null
                Triple(url, isFeatured, sortOrder)
            }
        return candidates
            .sortedWith(compareByDescending<Triple<String, Boolean, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()
            ?.first
    }
}
```

Move the row type: create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/BcParksStrapiRow.kt` and delete `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksStrapiRow.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

/** One BC Parks Strapi protected-area row, as [BcParksStrapiSource] parses it. */
data class BcParksStrapiRow(
    val name: String,
    val lat: Double,
    val lon: Double,
    val orcs: Long?,
    val url: String?,
    val description: String?,
    val phone: String?,
    val photoUrl: String?,
)
```

Rename `ApcaPlacesCentroidSource.kt` to `ArcGisCentroidSource.kt` (`git mv`, then replace the contents):

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ApcaPlacesCentroidSource.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ArcGisCentroidSource.kt
```

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CENTROID_NAME_ATTRIBUTE = "DESC_EN"

/**
 * ArcGIS centroid-mode JSON: features with `attributes.DESC_EN` and
 * `centroid: { x, y }`. Parks Canada's Places layer is what reads through it
 * today; names like "Banff National Park of Canada" collapse through
 * [normalize] to the bare name a leaf carries.
 */
class ArcGisCentroidSource(
    private val envelopes: List<Envelope>,
) : GeometrySource {
    override fun points(): Sequence<NamedPoint> =
        sequence {
            for (env in envelopes) {
                val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
                for (f in feats) {
                    val o = f.jsonObject
                    val attrs = o["attributes"]?.jsonObject ?: continue
                    val name = attrs[CENTROID_NAME_ATTRIBUTE]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val centroid = o["centroid"]?.jsonObject ?: continue
                    val lon = centroid["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = centroid["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                    yield(NamedPoint(name, lat, lon))
                }
            }
        }
}
```

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeoJsonFeaturesSource.kt` (it absorbs `ApcaAccommodationSource`):

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val POINT_GEOMETRY_TYPE = "Point"
private const val LONGITUDE_INDEX = 0
private const val LATITUDE_INDEX = 1
private const val MIN_COORDINATES = 2

/** Tried in order when a source spec declares no `name_property`. */
@Suppress("TopLevelPropertyNaming")
private val DEFAULT_NAME_PROPERTIES = listOf("name", "Name")

/**
 * A GeoJSON FeatureCollection whose features carry Point geometry.
 * [nameProperty] names the attribute holding the feature name — Parks Canada's
 * Accommodation layer uses `Name_e` — and null falls back to
 * [DEFAULT_NAME_PROPERTIES].
 */
class GeoJsonFeaturesSource(
    private val envelopes: List<Envelope>,
    private val nameProperty: String? = null,
) : GeometrySource {
    private val nameKeys: List<String> = nameProperty?.let(::listOf) ?: DEFAULT_NAME_PROPERTIES

    override fun points(): Sequence<NamedPoint> =
        sequence {
            for (env in envelopes) {
                val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
                for (f in feats) {
                    val o = f.jsonObject
                    val props = o["properties"]?.jsonObject ?: continue
                    val name =
                        nameKeys.firstNotNullOfOrNull { key ->
                            props[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        } ?: continue
                    val geom = o["geometry"]?.jsonObject ?: continue
                    if (geom["type"]?.jsonPrimitive?.contentOrNull != POINT_GEOMETRY_TYPE) continue
                    val coords = geom["coordinates"]?.jsonArray ?: continue
                    if (coords.size < MIN_COORDINATES) continue
                    val lon = coords[LONGITUDE_INDEX].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = coords[LATITUDE_INDEX].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                    yield(NamedPoint(name, lat, lon))
                }
            }
        }
}
```

Delete `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ApcaAccommodationSource.kt`.

- [ ] **Step 5: Add the format dispatch**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometrySources.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec

/** The one dispatch from a declared [GeometryFormat] to the parser that reads it. */
object GeometrySources {
    fun forSpec(
        spec: GeometrySourceSpec,
        envelopes: List<Envelope>,
    ): GeometrySource =
        when (spec.format) {
            GeometryFormat.USCAMPGROUNDS_CSV -> UsCampgroundsCsvSource(envelopes, spec.state)
            GeometryFormat.BCPARKS_STRAPI -> BcParksStrapiSource(envelopes)
            GeometryFormat.ARCGIS_CENTROIDS -> ArcGisCentroidSource(envelopes)
            GeometryFormat.GEOJSON_POINTS -> GeoJsonFeaturesSource(envelopes, spec.nameProperty)
        }
}
```

- [ ] **Step 6: Point the two ETLs at the new types**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`:

Replace `indexGeometry` and its KDoc with nothing — delete the whole private method — and change the matcher construction in `transform`:

```kotlin
        val matcher =
            AspiraLeafMatcher(
                GeometryIndex.build(dto.geomSources, log, etlSlug),
                nonBookableResLocs,
            )
```

Change `campgroundCandidate`'s signature and its two coordinate reads:

```kotlin
    private fun campgroundCandidate(
        match: AspiraLeafMatch<GeometryPoint>,
        host: String,
        subcategory: String?,
        agency: String,
        bookableMapIds: Map<Long, Set<Long>>,
    ): CampgroundUpsertCandidate {
        val leaf = match.leaf
        val point = match.value
        val dataRef = DataProviderRef.Aspira(transactionLocationId = leaf.transactionLocationId, mapId = leaf.mapId)
        val bookingCtaRef = AspiraBookingCtaRefs.forLeaf(leaf, bookableMapIds)
        return CampgroundUpsertCandidate(
            dataProviderRef = dataRef,
            bookingProvider = BookingProvider.ASPIRA,
            bookingProviderRef = bookingCtaRef?.let { campgroundBookingProviderRef(leaf, it) },
            name = leaf.name,
            parentName = leaf.parentName,
            latitude = point.latitude,
            longitude = point.longitude,
            kind = subcategory,
            location = CampgroundLocation(latitude = point.latitude, longitude = point.longitude),
            reservationUrl = "https://$host/",
            links = listOf(CampgroundLink("https://$host/")),
            management = CampgroundManagement(agency),
            sourceUrl = "https://$host/",
            sourcePayload = aspiraSourcePayload(leaf, match.kind),
        )
    }
```

Replace `detectGeometrySource` with the new class names (Task 3 deletes this method outright):

```kotlin
    private fun detectGeometrySource(
        slug: String,
        envelopes: List<Envelope>,
    ): GeometrySource =
        when {
            slug.contains("uscampgrounds") -> UsCampgroundsCsvSource(envelopes, stateFilter)
            slug.contains("bcparks") -> BcParksStrapiSource(envelopes)
            slug.contains("places") -> ArcGisCentroidSource(envelopes)
            slug.contains("accommodation") -> GeoJsonFeaturesSource(envelopes, APCA_ACCOMMODATION_NAME_PROPERTY)
            else -> GeoJsonFeaturesSource(envelopes)
        }
```

and add to the companion, alongside the existing keys:

```kotlin
        /** Parks Canada's Accommodation layer names features here, not in `name`. */
        private const val APCA_ACCOMMODATION_NAME_PROPERTY = "Name_e"
```

Add `import ca.floo.roadtrip.model.metadata.Envelope` (the method used the fully-qualified name).

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`:

Replace the `parseStrapiRows` call in `parse` with the shared parser:

```kotlin
            val strapiRows = BcParksStrapiSource(strapiEnvelopes).rows()
```

Delete the private `parseStrapiRows` and `extractPhotoUrl` methods together with the `// ---- Strapi parsing ----` banner. Replace the import block's `ca.floo.roadtrip.service.etl.vendors.aspira.*` entries so it reads (keeping the rest of the file's imports):

```kotlin
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraBookingCtaRef
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraBookingCtaRefs
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraInventoryCategories
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatch
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatchKind
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatcher
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeavesWalk
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiSource
import ca.floo.roadtrip.service.etl.vendors.aspira.normalize
```

and drop the now-unused `kotlinx.serialization.json.JsonArray`, `kotlinx.serialization.json.contentOrNull`, `kotlinx.serialization.json.jsonPrimitive` imports (`jsonArray`, `jsonObject`, `JsonObject`, `buildJsonObject`, `put`, `Envelope` are all still used).

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsDto.kt`, add `import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow`.

- [ ] **Step 7: Update the three touched tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSourceTest.kt`, replace the two `indexInto` call sites:

```kotlin
    private fun index(stateFilter: String?): Map<String, GeometryPoint> =
        GeometryIndex.build(
            listOf("uscampgrounds" to UsCampgroundsCsvSource(listOf(envelope()), stateFilter)),
            LoggerFactory.getLogger(javaClass),
            "uscampgrounds-csv-source-test",
        )
```

and the three assertions it feeds:

```kotlin
    @Test
    fun `a state filter keeps the matching state's coordinates`() {
        val byName = index(stateFilter = "WA")
        assertEquals(GeometryPoint(46.039, -120.667, "uscampgrounds"), byName[normalize("Brooks Memorial")])
    }

    @Test
    fun `a state filter drops other states entirely`() {
        // Not merely outranked — a name only present in another state must not
        // be indexed at all, or a fuzzy match can still reach it.
        val byName = index(stateFilter = "OR")
        assertNull(byName[normalize("Brooks Memorial")])
        assertNull(byName[normalize("Manchester")])
    }

    @Test
    fun `no state filter indexes every state, first row winning`() {
        // Tenants outside the US (Parks Canada, BC) declare no filter and must
        // keep the previous nationwide behaviour.
        val byName = index(stateFilter = null)
        assertEquals(GeometryPoint(43.177, -101.732, "uscampgrounds"), byName[normalize("Brooks Memorial")])
        assertEquals(GeometryPoint(47.548, -122.545, "uscampgrounds"), byName[normalize("Manchester")])
    }
```

and the registry-driven case's body:

```kotlin
        val byName =
            GeometryIndex.build(
                etl.geometrySourcesFor(inputs),
                LoggerFactory.getLogger(javaClass),
                "aspira-wa-campgrounds",
            )

        assertEquals(GeometryPoint(46.039, -120.667, "uscampgrounds"), byName[normalize("Brooks Memorial")])
```

Add `import org.slf4j.LoggerFactory` to that file.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt`, drop the dead second argument at all three `GeoJsonFeaturesSource` call sites:

```kotlin
                    "test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope())),
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`, the same at its one call site:

```kotlin
                            geomSources = listOf("fixture" to GeoJsonFeaturesSource(listOf(geoJsonEnvelope()))),
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.*' --tests 'ca.floo.roadtrip.service.etl.vendors.bcparks.*' --tests 'ca.floo.roadtrip.service.etl.framework.EtlExtrasDtoTest' --offline`
Expected: PASS.

- [ ] **Step 9: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/NamedPoint.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryPoint.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometrySource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryIndex.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometrySources.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/BcParksStrapiSource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/BcParksStrapiRow.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ArcGisCentroidSource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ApcaAccommodationSource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/ApcaPlacesCentroidSource.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksStrapiRow.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsDto.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/GeometryIndexTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSourceTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt
git commit -F - <<'MSG'
refactor(etl): geometry sources are readers; one index, one parser per format

GeometrySource.points() replaces indexInto, so normalization and the
first-writer-wins merge live once in GeometryIndex instead of five times.
GeometrySources.forSpec is the one dispatch over the format enum; the APCA
accommodation reader collapses into GeoJsonFeaturesSource with a name
property, and the BC ETL's private Strapi parse gives way to the source
that already performed it.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 3: Both ETLs read the declaration — no slug sniffing, every input accounted for

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt`, `.../aspira/UsCampgroundsCsvSourceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistryTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`

**Interfaces:**
- Consumes: `GeometryPolicy`, `GeometrySourceSpec`, `GeometryFormat` (Task 1); `GeometrySources.forSpec(spec, envelopes)`, `GeometryIndex.build(sources, log, etlSlug)` (Task 2); `InputBundle.dataSourceSlugs(): List<String>`, `InputBundle.envelopes(slug): List<Envelope>`, `InputBundle.envelope(slug): Envelope`; `ParseResult.Bad(sourceId: String?, errors: List<String>)`.
- Produces:
  - `class AspiraCampgroundsEtl(override val etlSlug: String, private val aspiraTenant: String, private val geometry: GeometryPolicy)` — `dataProviderValue` and `stateFilter` are gone
  - `class BcParksCampgroundsEtl(override val etlSlug: String, private val aspiraTenant: String, private val geometry: GeometryPolicy)` — `DEFAULT_ETL_SLUG` is gone
  - `internal fun EtlEntry.requireGeometry(): GeometryPolicy` in `ProductionTerminalEtlRegistry.kt`

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt`, add the policy fixture next to `private val slug`:

```kotlin
    // The PC row's shape, one source: the fixtures feed a GeoJSON envelope under
    // the slug `test-geom`, and the parent fallback is what PC declares today.
    private val geometryPolicy =
        GeometryPolicy(
            sources = listOf(GeometrySourceSpec(input = "test-geom", format = GeometryFormat.GEOJSON_POINTS)),
            match = MatchPolicy(parentFallback = true),
        )
```

change the constructor call in `campgrounds(...)`:

```kotlin
    private fun campgrounds(dto: AspiraJoinDto): List<CampgroundUpsertCandidate> =
        records(
            AspiraCampgroundsEtl(etlSlug = slug, aspiraTenant = "pc", geometry = geometryPolicy)
                .transform(dto, ctx),
        )
```

drop the now-unused `import ca.floo.roadtrip.model.domain.provider.DataProvider`, and add:

```kotlin
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec
import ca.floo.roadtrip.model.metadata.registry.MatchPolicy
import ca.floo.roadtrip.service.etl.framework.InputBundle
```

Then add these three parse tests:

```kotlin
    /**
     * The negative-selection hole: every input that was not maps, inventory or
     * dictionaries used to become a geometry source, so a typo'd slug quietly
     * indexed nothing. Now it fails the parse.
     */
    @Test
    fun `an input that is neither declared geometry nor a known feed fails parse`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(envelopeOf("[]")),
                    "test-geom" to listOf(geomEnvelope()),
                    "apca-plaecs" to listOf(geomEnvelope()),
                ),
            )

        val bad = etl.parse(inputs).single() as ParseResult.Bad
        assertTrue(
            bad.errors.any {
                it == "input 'apca-plaecs' is neither a declared geometry source nor the maps, inventory or dictionaries feed"
            },
            bad.errors.toString(),
        )
    }

    @Test
    fun `a declared geometry source with no envelopes fails parse`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(envelopeOf("[]")),
                    "test-geom" to emptyList<Envelope>(),
                ),
            )

        val bad = etl.parse(inputs).single() as ParseResult.Bad
        assertTrue(bad.errors.any { it == "declared geometry source 'test-geom' has no envelopes" }, bad.errors.toString())
    }

    @Test
    fun `parse builds one source per declared geometry input, in declared order`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry =
                    GeometryPolicy(
                        sources =
                            listOf(
                                GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS),
                                GeometrySourceSpec("test-centroids", GeometryFormat.ARCGIS_CENTROIDS),
                            ),
                    ),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(envelopeOf("[]")),
                    "test-centroids" to listOf(envelopeOf("""{"features":[]}""")),
                    "test-geom" to listOf(geomEnvelope()),
                ),
            )

        assertEquals(listOf("test-geom", "test-centroids"), etl.geometrySourcesFor(inputs).map { it.first })
    }
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSourceTest.kt`, rename the registry-driven case and repoint its claim:

```kotlin
    /**
     * The half that actually broke: `state_filter: WA` sat in the registry and
     * nothing read it. Driving the real YAML through the real registry is what
     * catches that — a test against the source alone passes either way. The
     * filter now lives at `geometry.sources[0].state`.
     */
    @Test
    fun `the WA terminal reads the geometry source state from the registry`() {
        val definition =
            productionTerminalEtlDefinitions["aspira-wa-campgrounds"]
                ?: error("aspira-wa-campgrounds is not a registered terminal")
        val etl = definition.etl as AspiraCampgroundsEtl

        val entry =
            PoiRegistry
                .loadResource("poi-registry.yaml")
                .poiData
                .flatMap { it.etls }
                .single { it.slug == "aspira-wa-campgrounds" }
        assertEquals("WA", entry.geometry!!.sources.single().state)

        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-wa" to listOf(jsonEnvelope("[]")),
                    "uscampgrounds" to listOf(envelope()),
                    "aspira-inventory-wa" to listOf(jsonEnvelope("[]")),
                    "aspira-dictionaries-wa" to listOf(jsonEnvelope("{}")),
                ),
            )

        val byName =
            GeometryIndex.build(
                etl.geometrySourcesFor(inputs),
                LoggerFactory.getLogger(javaClass),
                "aspira-wa-campgrounds",
            )

        assertEquals(GeometryPoint(46.039, -120.667, "uscampgrounds"), byName[normalize("Brooks Memorial")])
    }
```

and add `import ca.floo.roadtrip.model.metadata.registry.PoiRegistry`.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt`, build the ETL from the shipped policy:

```kotlin
    private val etl =
        BcParksCampgroundsEtl(
            etlSlug = "aspira-bc-campgrounds",
            aspiraTenant = "bc",
            geometry =
                PoiRegistry
                    .loadResource("poi-registry.yaml")
                    .poiData
                    .flatMap { it.etls }
                    .single { it.slug == "aspira-bc-campgrounds" }
                    .geometry!!,
        )
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistryTest.kt`, replace the last test and add its Aspira twin:

```kotlin
    @Test
    fun `the shipped bc parks terminal etl builds from the registry's tenant arg and geometry policy`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val entry =
            registry.poiData
                .flatMap { it.etls }
                .single { it.slug == "aspira-bc-campgrounds" }
        assertEquals("bc", entry.args["tenant"])
        assertEquals("bcparks-strapi", entry.geometry!!.sources.single().input)

        val definition = poiAdapters["BcParksCampgroundsEtl"]?.create?.invoke(entry)
        assertNotNull(definition)
    }

    @Test
    fun `both shipped aspira campground terminals build from their geometry policies`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val entries =
            registry.poiData
                .flatMap { it.etls }
                .filter { it.adapter == "AspiraCampgroundsEtl" }
        assertEquals(listOf("aspira-wa-campgrounds", "aspira-pc-campgrounds"), entries.map { it.slug })

        for (entry in entries) {
            assertNotNull(entry.geometry)
            assertNotNull(poiAdapters["AspiraCampgroundsEtl"]?.create?.invoke(entry), entry.slug)
        }
    }
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`, change the one construction:

```kotlin
                AspiraCampgroundsEtl(
                    etlSlug = "aspira-wa-campgrounds",
                    aspiraTenant = "wa",
                    geometry =
                        GeometryPolicy(
                            sources = listOf(GeometrySourceSpec(input = "fixture", format = GeometryFormat.GEOJSON_POINTS)),
                        ),
                )
```

and add:

```kotlin
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampgroundsEtlTest' --offline`
Expected: compilation failure — `No value passed for parameter 'dataProviderValue'` / `Cannot find a parameter with this name: geometry`.

- [ ] **Step 3: Make `AspiraCampgroundsEtl` read the policy**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`:

Replace the class header comment's last two paragraphs and the constructor:

```kotlin
// One ETL class. Each row's `geometry:` block declares which of its inputs
// carry coordinates, in which preference order, and how names are matched;
// this class reads only that declaration.
//
// Match strategy: aggressive name normalization (lowercase, drop park /
// campground / national-park-of-canada / etc. suffixes), then exact
// match against the union name → coords index; fallback to a Jaccard token
// overlap at `match.fuzzy_threshold`; final fallback, when
// `match.parent_fallback` is set, to the leaf's `parent_name`.
// Leaves that can't be matched are dropped — the booking ID alone
// doesn't earn a pin on the map.
class AspiraCampgroundsEtl(
    override val etlSlug: String,
    private val aspiraTenant: String,
    private val geometry: GeometryPolicy,
) : CampgroundEtl<AspiraJoinDto> {
```

Replace `geometrySourcesFor` and `parse`:

```kotlin
    /** The declared geometry inputs, paired with the parser each format names, in preference order. */
    internal fun geometrySourcesFor(inputs: InputBundle): List<Pair<String, GeometrySource>> =
        geometry.sources.map { spec -> spec.input to GeometrySources.forSpec(spec, inputs.envelopes(spec.input)) }

    override fun parse(inputs: InputBundle): Sequence<ParseResult<AspiraJoinDto>> =
        sequence {
            val slugs = inputs.dataSourceSlugs()
            val mapsSlug = slugs.firstOrNull { it.contains(MAPS_INPUT_MARKER) }
            val inventorySlug = slugs.firstOrNull { it.contains(INVENTORY_INPUT_MARKER) }
            val dictionarySlug = slugs.firstOrNull { it.contains(DICTIONARIES_INPUT_MARKER) }

            val errs = mutableListOf<String>()
            for (spec in geometry.sources) {
                when {
                    spec.input !in slugs -> errs += "declared geometry source '${spec.input}' is not among this run's inputs"
                    inputs.envelopes(spec.input).isEmpty() -> errs += "declared geometry source '${spec.input}' has no envelopes"
                }
            }
            val accounted = geometry.sources.map { it.input }.toSet() + setOfNotNull(mapsSlug, inventorySlug, dictionarySlug)
            for (slug in slugs - accounted) {
                errs += "input '$slug' is neither a declared geometry source nor the maps, inventory or dictionaries feed"
            }
            if (mapsSlug == null) errs += "no /api/maps input declared"

            if (errs.isNotEmpty()) {
                yield(ParseResult.Bad(null, errs))
                return@sequence
            }

            val leaves = AspiraLeavesWalk.walk(inputs.envelope(checkNotNull(mapsSlug)).payload.jsonArray)
            val dto =
                AspiraJoinDto(
                    leaves = leaves,
                    geomSources = geometrySourcesFor(inputs),
                    inventoryEnvelopes = inventorySlug?.let { inputs.envelopes(it) } ?: emptyList(),
                    dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject },
                    fetchedAt = Instant.now(),
                )
            if (dto.leaves.isEmpty()) {
                yield(ParseResult.Bad(null, listOf("no leaves from /api/maps")))
            } else {
                yield(ParseResult.Ok(dto))
            }
        }
```

Delete the private `detectGeometrySource` method and the `APCA_ACCOMMODATION_NAME_PROPERTY` constant Task 2 added. Add to the companion:

```kotlin
        private const val MAPS_INPUT_MARKER = "maps"
        private const val INVENTORY_INPUT_MARKER = "inventory"
        private const val DICTIONARIES_INPUT_MARKER = "dictionaries"
```

Replace the imports `ca.floo.roadtrip.model.domain.provider.DataProvider` and `ca.floo.roadtrip.model.metadata.Envelope` with `ca.floo.roadtrip.model.metadata.registry.GeometryPolicy`.

- [ ] **Step 4: Make `BcParksCampgroundsEtl` read the policy**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, delete the `DEFAULT_ETL_SLUG` constant and its KDoc, and replace the constructor:

```kotlin
class BcParksCampgroundsEtl(
    override val etlSlug: String,
    private val aspiraTenant: String,
    private val geometry: GeometryPolicy,
) : CampgroundEtl<BcParksCampgroundsDto> {
```

Replace the first five lines of `parse`'s body:

```kotlin
            val slugs = inputs.dataSourceSlugs()
            val mapsSlug = slugs.first { it.contains("maps") }
            val strapiSlug = geometry.sources.single().input
            val inventorySlug = slugs.first { it.contains("inventory") }
            val dictionarySlug = slugs.firstOrNull { it.contains("dictionaries") }
```

Add `import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy`.

- [ ] **Step 5: Hand the policy to both factories**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`, replace both entries:

```kotlin
        "AspiraCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.ASPIRA) { entry ->
                campgroundSink(
                    AspiraCampgroundsEtl(
                        etlSlug = entry.slug,
                        aspiraTenant = entry.args.require("tenant"),
                        geometry = entry.requireGeometry(),
                    ),
                )
            },
        "BcParksCampgroundsEtl" to
            PoiAdapterSpec(DataProvider.STRAPI) { entry ->
                campgroundSink(
                    BcParksCampgroundsEtl(
                        etlSlug = entry.slug,
                        aspiraTenant = entry.args.require("tenant"),
                        geometry = entry.requireGeometry(),
                    ),
                )
            },
```

and add, beside the existing private `require` helper:

```kotlin
/** Validation guarantees presence; this is the belt. */
private fun EtlEntry.requireGeometry(): GeometryPolicy =
    geometry ?: error("$slug: geometry policy is required for $adapter")
```

Replace the `GeometryFormat` import Task 1 added with `import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.*' --offline`
Expected: PASS.

- [ ] **Step 7: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/UsCampgroundsCsvSourceTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistryTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt
git commit -F - <<'MSG'
refactor(etl): the campground ETLs read their geometry declaration, not a slug

Both Aspira-backed campground ETLs take a GeometryPolicy and build their
sources from it; detectGeometrySource and the dead dataProviderValue and
stateFilter parameters are gone. Parse now accounts for every declared
input, so a typo'd slug fails loudly instead of becoming an empty GeoJSON
source.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 4: The match ladder takes its policy and reports what it matched

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcher.kt`, `.../aspira/AspiraLeafMatch.kt`, `.../aspira/AspiraCampgroundsEtl.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, `.../bcparks/BcParksCampgroundsDto.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcherTest.kt`

**Interfaces:**
- Consumes: `MatchPolicy(fuzzyThreshold, parentFallback)` (Task 1); `GeometryIndex.build`, `GeometryPoint`, `BcParksStrapiSource` (Task 2); `geometry: GeometryPolicy` on both ETLs (Task 3); `normalize`, `jaccard`.
- Produces:
  - `data class AspiraLeafMatch<T>(val leaf: AspiraLeaf, val value: T, val kind: AspiraLeafMatchKind, val matchedName: String, val score: Double? = null)`
  - `class AspiraLeafMatcher<T : Any>(byName: Map<String, T>, nonBookableResourceLocationIds: Set<Long>, policy: MatchPolicy)` with `fun match(leaf: AspiraLeaf): AspiraLeafMatch<T>?` and `fun matchBookable(leaves: List<AspiraLeaf>): Result<T>`; `Tally` and `Result` unchanged
  - `BcParksCampgroundsDto.geomSources: List<Pair<String, GeometrySource>>` replaces `strapiEnvelopes`

- [ ] **Step 1: Write the failing matcher tests**

Replace `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcherTest.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.registry.MatchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AspiraLeafMatcherTest {
    private val banff = 51.18 to -115.57
    private val twoJack = 51.22 to -115.49

    private fun matcher(
        policy: MatchPolicy = MatchPolicy(parentFallback = true),
        byName: Map<String, Pair<Double, Double>> = mapOf("banff" to banff, "two jack lakeside" to twoJack),
    ) = AspiraLeafMatcher(byName = byName, nonBookableResourceLocationIds = setOf(NON_BOOKABLE), policy = policy)

    @Test
    fun `exact normalized name wins and names the index key it hit`() {
        val match = matcher().match(leaf("Two Jack Lakeside Campground"))

        assertEquals(
            AspiraLeafMatch(
                leaf = leaf("Two Jack Lakeside Campground"),
                value = twoJack,
                kind = AspiraLeafMatchKind.EXACT,
                matchedName = "two jack lakeside",
            ),
            match,
        )
    }

    @Test
    fun `token overlap at the threshold is a fuzzy match carrying its score`() {
        // {two, jack} of {two, jack, lake, lakeside} — exactly the default threshold.
        val match = matcher().match(leaf("Two Jack Lake"))

        assertEquals(AspiraLeafMatchKind.FUZZY, match?.kind)
        assertEquals(twoJack, match?.value)
        assertEquals("two jack lakeside", match?.matchedName)
        assertEquals(MatchPolicy.DEFAULT_FUZZY_THRESHOLD, match?.score)
    }

    @Test
    fun `a threshold above the best overlap rejects the fuzzy match`() {
        assertNull(matcher(policy = MatchPolicy(fuzzyThreshold = STRICT_THRESHOLD, parentFallback = false)).match(leaf("Two Jack Lake")))
    }

    @Test
    fun `parent park name backstops a leaf with no geometry of its own`() {
        val match = matcher().match(leaf("Backcountry Site", parentName = "Banff National Park of Canada"))

        assertEquals(AspiraLeafMatchKind.PARENT, match?.kind)
        assertEquals(banff, match?.value)
        assertEquals("banff", match?.matchedName)
        assertNull(match?.score)
    }

    @Test
    fun `parent fallback off leaves that same leaf unmatched`() {
        val policy = MatchPolicy(parentFallback = false)

        assertNull(matcher(policy).match(leaf("Backcountry Site", parentName = "Banff National Park of Canada")))
    }

    /**
     * Ties resolve the same way exact collisions do: by index order, which is
     * source preference and then feed row order. The index is a LinkedHashMap,
     * so "alpha lake" is entered first and must win.
     */
    @Test
    fun `a tie keeps the first entry in index order`() {
        val byName =
            linkedMapOf(
                "alpha lake" to (1.0 to 2.0),
                "beta lake" to (3.0 to 4.0),
            )

        val match = matcher(byName = byName).match(leaf("Lake"))

        assertEquals("alpha lake", match?.matchedName)
        assertEquals(1.0 to 2.0, match?.value)
    }

    @Test
    fun `a leaf matching neither itself nor its parent is unmatched`() {
        assertNull(matcher().match(leaf("Nowhere Site", parentName = "Elsewhere")))
    }

    @Test
    fun `matchBookable skips containers and non-bookable leaves before lookup`() {
        val leaves =
            listOf(
                leaf("Banff", resourceLocationId = null),
                leaf("Two Jack Lakeside", resourceLocationId = NON_BOOKABLE),
                leaf("Two Jack Lakeside"),
                leaf("Two Jack Lake"),
                leaf("Backcountry Site", parentName = "Banff"),
            ) + (1..7).map { leaf("Miss $it") }
        val (matches, tally) = matcher().matchBookable(leaves)

        assertEquals(listOf("Two Jack Lakeside", "Two Jack Lake", "Backcountry Site"), matches.map { it.leaf.name })
        assertEquals(
            AspiraLeafMatcher.Tally(
                exact = 1,
                fuzzy = 1,
                parent = 1,
                miss = 7,
                skippedContainer = 1,
                skippedNonBookable = 1,
                missSamples = (1..5).map { "Miss $it" },
            ),
            tally,
        )
    }

    private fun leaf(
        name: String,
        resourceLocationId: Long? = BOOKABLE,
        parentName: String? = null,
    ) = AspiraLeaf(
        name = name,
        transactionLocationId = 1L,
        mapId = 2L,
        resourceLocationId = resourceLocationId,
        parentName = parentName,
    )

    private companion object {
        const val BOOKABLE = 9001L
        const val NON_BOOKABLE = 9002L

        /** Above every overlap these fixtures produce, so the fuzzy pass must reject. */
        const val STRICT_THRESHOLD = 0.9
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatcherTest' --offline`
Expected: compilation failure — `Cannot find a parameter with this name: policy` and `No value passed for parameter 'matchedName'`.

- [ ] **Step 3: Widen the match record**

Replace `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatch.kt`:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.aspira

/** A bookable leaf paired with the geometry value its name resolved to. */
data class AspiraLeafMatch<T>(
    val leaf: AspiraLeaf,
    val value: T,
    val kind: AspiraLeafMatchKind,
    /** The index key the leaf resolved to: the normalized name of the winning feed entry. */
    val matchedName: String,
    /** Jaccard token overlap for [AspiraLeafMatchKind.FUZZY]; null for the deterministic kinds. */
    val score: Double? = null,
)
```

- [ ] **Step 4: Drive the ladder from the policy**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcher.kt`, delete the `FUZZY_THRESHOLD` constant and its comment, add `import ca.floo.roadtrip.model.metadata.registry.MatchPolicy`, and replace the class header, the token index and `match`:

```kotlin
/**
 * Resolves Aspira `/api/maps` leaves to geometry through a normalized-name
 * index (see [normalize]): exact name first, then Jaccard token overlap at
 * [MatchPolicy.fuzzyThreshold], then — only when [MatchPolicy.parentFallback]
 * is set — the parent park's name.
 *
 * [matchBookable] also owns the two gates every Aspira tenant applies before
 * lookup. Leaves without a resourceLocationId are park containers (Banff,
 * Jasper, …), not bookable resources; emitting them layered a duplicate park
 * pin over the park's own campground pins, and the parent-name fallback keeps
 * every park represented through its campgrounds. Leaves whose
 * resourceLocationId holds only non-bookable inventory are activity mounts
 * (parking, shuttles), not campgrounds, even when their name matches.
 */
class AspiraLeafMatcher<T : Any>(
    private val byName: Map<String, T>,
    private val nonBookableResourceLocationIds: Set<Long>,
    private val policy: MatchPolicy,
) {
    private val tokenIndex: List<IndexEntry<T>> =
        byName.entries.map { (key, value) -> IndexEntry(key, key.split(' ').toSet(), value) }

    private data class IndexEntry<T>(
        val key: String,
        val tokens: Set<String>,
        val value: T,
    )
```

and replace `match`:

```kotlin
    fun match(leaf: AspiraLeaf): AspiraLeafMatch<T>? {
        val key = normalize(leaf.name)
        byName[key]?.let { return AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.EXACT, matchedName = key) }

        // One score per entry, first maximum kept: ties resolve by index order,
        // which is source preference and then feed row order — the same rule
        // that settles an exact-name collision.
        val tokens = key.split(' ').toSet()
        var best: IndexEntry<T>? = null
        var bestScore = 0.0
        for (entry in tokenIndex) {
            val score = jaccard(entry.tokens, tokens)
            if (best == null || score > bestScore) {
                best = entry
                bestScore = score
            }
        }
        if (best != null && bestScore >= policy.fuzzyThreshold) {
            return AspiraLeafMatch(leaf, best.value, AspiraLeafMatchKind.FUZZY, matchedName = best.key, score = bestScore)
        }

        if (!policy.parentFallback) return null
        val parentKey = leaf.parentName?.let(::normalize) ?: return null
        return byName[parentKey]?.let { AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.PARENT, matchedName = parentKey) }
    }
```

- [ ] **Step 5: Hand both ETLs their policy's match block**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`, replace the matcher construction in `transform`:

```kotlin
        val matcher =
            AspiraLeafMatcher(
                byName = GeometryIndex.build(dto.geomSources, log, etlSlug),
                nonBookableResourceLocationIds = nonBookableResLocs,
                policy = geometry.match,
            )
```

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsDto.kt`, replace `strapiEnvelopes` with the geometry sources:

```kotlin
package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow
import ca.floo.roadtrip.service.etl.vendors.aspira.GeometrySource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class BcParksCampgroundsDto(
    val leaves: List<AspiraLeaf>,
    val strapiRows: List<BcParksStrapiRow>,
    /** The single declared Strapi source, paired with its input slug, for [GeometrySource] indexing. */
    val geomSources: List<Pair<String, GeometrySource>>,
    val inventoryEnvelopes: List<Envelope>,
    val dictionaryPayload: JsonObject?,
    val mapsArray: JsonArray,
)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, build the source once in `parse` and carry it:

```kotlin
            val strapiSource = BcParksStrapiSource(inputs.envelopes(strapiSlug))
            val strapiRows = strapiSource.rows()

            val dto =
                BcParksCampgroundsDto(
                    leaves = leaves,
                    strapiRows = strapiRows,
                    geomSources = listOf(strapiSlug to strapiSource),
                    inventoryEnvelopes = inventoryEnvelopes,
                    dictionaryPayload = dictionaryPayload,
                    mapsArray = mapsArray,
                )
```

(delete the now-unused `val strapiEnvelopes = inputs.envelopes(strapiSlug)` line), and replace the matcher block in `transform` so the ladder runs over points and the Strapi row is fetched by the name it matched:

```kotlin
        val strapiByName = indexStrapiRows(dto.strapiRows)
        val matcher =
            AspiraLeafMatcher(
                byName = GeometryIndex.build(dto.geomSources, log, etlSlug),
                nonBookableResourceLocationIds = nonBookableResLocs,
                policy = geometry.match,
            )
        val (matches, tally) = matcher.matchBookable(dto.leaves)
        val campgrounds =
            matches.mapNotNull { match ->
                strapiByName[match.matchedName]?.let { row ->
                    campgroundCandidate(match, row, host, subcategory, agency, bookableMapIds)
                }
            }
```

and change `campgroundCandidate`'s first two parameters and its two uses of `match.value`:

```kotlin
    private fun campgroundCandidate(
        match: AspiraLeafMatch<GeometryPoint>,
        strapiRow: BcParksStrapiRow,
        host: String,
        subcategory: String?,
        agency: String,
        bookableMapIds: Map<Long, Set<Long>>,
    ): CampgroundUpsertCandidate {
        val leaf = match.leaf
```

(the body's remaining `val strapiRow = match.value` line is deleted; everything below it already reads `strapiRow`).

Add the imports `ca.floo.roadtrip.service.etl.vendors.aspira.GeometryIndex` and `ca.floo.roadtrip.service.etl.vendors.aspira.GeometryPoint`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.*' --offline`
Expected: PASS.

- [ ] **Step 7: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcher.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatch.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsDto.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraLeafMatcherTest.kt
git commit -F - <<'MSG'
refactor(etl): the match ladder takes its policy and reports what it matched

The fuzzy threshold and the parent fallback come from the row's
match policy instead of a private constant and an unconditional branch, and
every match now carries the index key it resolved to plus the Jaccard score
that earned it. Ties keep the first index entry, the same preference rule
that settles an exact-name collision.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 5: `GeometryProvenance` — the model, the column, and the repo round-trip

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/GeometryProvenance.kt`, `backend/src/main/resources/db/migration/V62__campground_geometry_provenance.sql`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundUpsertCandidate.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campground.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/SqlBindSupport.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt`

**Interfaces:**
- Consumes: `CatalogColumnJson.json`, `decodeObjectColumn<T>(raw: String?): T?`, `BULK_CHUNK_SIZE`, `CampgroundRepo.upsertCampgrounds(records, source)`, `CampgroundRepo.findById(id)`.
- Produces:
  - `data class GeometryProvenance(val matchKind: String, val source: String, val matchedName: String, val score: Double? = null)` in `ca.floo.roadtrip.model.domain`, serialized as `match_kind` / `source` / `matched_name` / `score`
  - `CampgroundUpsertCandidate.geometryProvenance: GeometryProvenance?` (default `null`)
  - `Campground.geometryProvenance: GeometryProvenance?` (default `null`)
  - `internal inline fun <reified T : Any> nullableJsonObject(value: T?): String?` in `ca.floo.roadtrip.repo`
  - the `campgrounds.geometry_provenance` JSONB column and `campgrounds_geometry_provenance_match_kind_idx`

- [ ] **Step 1: Write the failing repo test**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt`, beside `booking aliases round-trip through both catalog repos`:

```kotlin
    @Test
    fun `geometry provenance round-trips and is replaced on conflict`() {
        val fuzzy =
            GeometryProvenance(
                matchKind = "fuzzy",
                source = "uscampgrounds",
                matchedName = "brooks memorial",
                score = SAMPLE_FUZZY_SCORE,
            )
        val repo = CampgroundRepo(ctx)
        repo.upsertCampgrounds(
            listOf(
                campgroundWithProvenance("cg-prov-1", fuzzy),
                campgroundWithProvenance("cg-prov-none", null),
            ),
            source = "aspira-wa-campgrounds",
        )

        assertEquals(fuzzy, checkNotNull(repo.findById(campgroundId("cg-prov-1"))).geometryProvenance)
        assertNull(checkNotNull(repo.findById(campgroundId("cg-prov-none"))).geometryProvenance)
        assertNull(
            ctx
                .fetchOne("SELECT geometry_provenance::text AS p FROM campgrounds WHERE data_provider_ref = ?", "cg-prov-none")!!
                .get("p", String::class.java),
            "an absent provenance must be SQL NULL, not an empty object",
        )

        // The re-import path: the same vendor ref, a better match this time.
        val exact = GeometryProvenance(matchKind = "exact", source = "uscampgrounds", matchedName = "brooks memorial")
        repo.upsertCampgrounds(listOf(campgroundWithProvenance("cg-prov-1", exact)), source = "aspira-wa-campgrounds")

        assertEquals(exact, checkNotNull(repo.findById(campgroundId("cg-prov-1"))).geometryProvenance)
    }

    /** The partial index the non-exact review query plans against. */
    @Test
    fun `the geometry provenance index is built and valid`() {
        val indexes =
            ctx
                .fetch(
                    """
                    SELECT c.relname AS indexname
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND i.indisvalid AND c.relname = ?
                    """.trimIndent(),
                    "campgrounds_geometry_provenance_match_kind_idx",
                ).map { it.get("indexname", String::class.java) }

        assertEquals(listOf("campgrounds_geometry_provenance_match_kind_idx"), indexes)
    }

    private fun campgroundWithProvenance(
        ref: String,
        provenance: GeometryProvenance?,
    ): CampgroundUpsertCandidate =
        CampgroundUpsertCandidate(
            dataProviderRef = DataProviderRef.Campflare(id = ref),
            name = ref,
            latitude = 1.0,
            longitude = 2.0,
            location = CampgroundLocation(1.0, 2.0),
            geometryProvenance = provenance,
        )
```

The file has no companion object, so add the score constant as a top-level private constant beside the other declarations at the head of the file:

```kotlin
/** An arbitrary but exactly representable fuzzy score: what matters is that it survives the round-trip. */
private const val SAMPLE_FUZZY_SCORE = 0.75
```

plus the import `import ca.floo.roadtrip.model.domain.GeometryProvenance`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --offline`
Expected: compilation failure — `Unresolved reference: GeometryProvenance`.

- [ ] **Step 3: Add the model and the candidate/domain fields**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/GeometryProvenance.kt`:

```kotlin
package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a campground's pin was found. Aspira-backed rows carry no coordinates of
 * their own and join a sibling geometry feed by name, so the pin is only as
 * good as the match: this records which feed supplied it, which index name it
 * resolved to, and the fuzzy score when the match was not exact. Providers
 * whose payload carries its own coordinates leave it null.
 */
@Serializable
data class GeometryProvenance(
    /** "exact" | "fuzzy" | "parent" — the ETL-side match kind's label. */
    @SerialName("match_kind") val matchKind: String,
    /** The geometry input slug that supplied the point. */
    val source: String,
    @SerialName("matched_name") val matchedName: String,
    /** Jaccard token overlap for a fuzzy match; null otherwise. */
    val score: Double? = null,
)
```

Append to `CampgroundUpsertCandidate` (after `sourcePayload`):

```kotlin
    val geometryProvenance: GeometryProvenance? = null,
```

Append to `Campground` (after `bookingAliases`):

```kotlin
    val geometryProvenance: GeometryProvenance? = null,
```

- [ ] **Step 4: Add the migration**

Create `backend/src/main/resources/db/migration/V62__campground_geometry_provenance.sql`:

```sql
-- Geometry provenance: how each campground's pin was found — exact, fuzzy or
-- parent-park match, against which geometry feed, at which name and score.
-- Aspira-backed ETLs write it; every other provider leaves it NULL.
--
-- No backfill. `make data-import` fills the column on the next run of the three
-- Aspira rows, which is a deploy step rather than a migration concern.

ALTER TABLE campgrounds
  ADD COLUMN IF NOT EXISTS geometry_provenance JSONB;

-- The review query filters on the match kind and only a minority of rows carry
-- the column at all, so the index is partial.
CREATE INDEX IF NOT EXISTS campgrounds_geometry_provenance_match_kind_idx
  ON campgrounds ((geometry_provenance->>'match_kind'))
  WHERE geometry_provenance IS NOT NULL;
```

No `.conf` sibling: this migration runs inside a transaction.

- [ ] **Step 5: Write and read the column**

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/SqlBindSupport.kt`, add beside `jsonObject`:

```kotlin
/** A typed JSONB column that must be SQL NULL when absent, not the empty object. */
internal inline fun <reified T : Any> nullableJsonObject(value: T?): String? = value?.let { CatalogColumnJson.json.encodeToString(it) }
```

and the two imports `ca.floo.roadtrip.model.domain.CatalogColumnJson` and `kotlinx.serialization.encodeToString`.

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt`, in `bulkUpsertCampgroundRows`:

Extend the placeholder template. It currently ends with three concatenated pieces — the `reservation_url` group, the `management … source_payload` group, and `"now(), NULL)"`. Replace those three with four (the row goes from 31 bound parameters to 32):

```kotlin
                    "?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, " +
                        "?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, " +
                        "?::jsonb, " +
                        "now(), NULL)"
```

Add the column to the INSERT list (the `source_payload` line becomes):

```sql
                  management, contact, connections, metadata, source_payload,
                  geometry_provenance,
```

Add the conflict update after `source_payload = EXCLUDED.source_payload,`:

```sql
                  geometry_provenance = EXCLUDED.geometry_provenance,
```

Bind the parameter after `params += jsonObject(record.sourcePayload)`:

```kotlin
                params += nullableJsonObject(record.geometryProvenance)
```

In the companion's `baseSelectColumns`, after the `source_payload` line:

```sql
            cg.geometry_provenance::text AS geometry_provenance_text,
```

In `fromRecord`, after `bookingAliases = …`:

```kotlin
            geometryProvenance = decodeObjectColumn(record.get("geometry_provenance_text", String::class.java)),
```

and add `import ca.floo.roadtrip.model.domain.GeometryProvenance`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.*' --offline`
Expected: PASS. `generateJooq` reruns because the migration directory changed; Docker must be up.

- [ ] **Step 7: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/domain/GeometryProvenance.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundUpsertCandidate.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campground.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/repo/SqlBindSupport.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt \
        backend/src/main/resources/db/migration/V62__campground_geometry_provenance.sql \
        backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt
git commit -F - <<'MSG'
feat(catalog): campgrounds carry a typed geometry provenance column

V62 adds geometry_provenance JSONB plus a partial index on its match kind,
and CampgroundRepo writes it on insert and on conflict and reads it back.
No backfill: make data-import populates it. Nothing serves it yet.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 6: The Aspira ETL stamps provenance; `match_kind` leaves the payload

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`

**Interfaces:**
- Consumes: `GeometryProvenance(matchKind, source, matchedName, score)` and `CampgroundUpsertCandidate.geometryProvenance` (Task 5); `AspiraLeafMatch.matchedName` / `.score` (Task 4); `GeometryPoint.source` (Task 2); `AspiraLeafMatchKind.label`.
- Produces: `AspiraCampgroundsEtl.aspiraSourcePayload(leaf: AspiraLeaf): JsonObject` — the `matchKind` parameter is gone, and so is the `match_kind` key.

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt`, replace `source payload records match provenance` and `campground leaf that misses its own name falls back to the parent park centroid`:

```kotlin
    @Test
    fun `an exact match stamps provenance and leaves match_kind out of the payload`() {
        val campground = campgrounds(dtoOf(campground)).single()

        assertEquals(
            GeometryProvenance(matchKind = "exact", source = "test-geom", matchedName = "two jack lakeside"),
            campground.geometryProvenance,
        )
        val extras = campground.sourcePayload!!.jsonObject
        assertEquals("Two Jack Lakeside", extras["name"]!!.jsonPrimitive.content)
        assertNull(extras["match_kind"], "match_kind now lives in geometry_provenance, not the payload blob")
    }

    @Test
    fun `campground leaf that misses its own name falls back to the parent park centroid`() {
        val campground = campgrounds(dtoOf(campgroundMissingOwnName)).single()

        assertEquals("Backcountry Site With No Geometry", campground.name)
        assertEquals(
            GeometryProvenance(matchKind = "parent", source = "test-geom", matchedName = "banff"),
            campground.geometryProvenance,
        )
        // Located at Banff's seeded centroid (lon -115.57, lat 51.18), not its own.
        assertEquals(-115.57, campground.longitude)
        assertEquals(51.18, campground.latitude)
    }
```

and add `import ca.floo.roadtrip.model.domain.GeometryProvenance` plus `import kotlin.test.assertNull`.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt`, replace the last assertion of `aspira source payload omits an absent parent name`:

```kotlin
        assertNull(extras["parent_name"])
        assertNull(extras["match_kind"])
        assertEquals(
            GeometryProvenance(matchKind = "exact", source = "fixture", matchedName = "lakeside"),
            campground.geometryProvenance,
        )
```

and add `import ca.floo.roadtrip.model.domain.GeometryProvenance`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampgroundsEtlTest' --tests 'ca.floo.roadtrip.service.etl.framework.EtlExtrasDtoTest' --offline`
Expected: FAIL — `expected:<GeometryProvenance(...)> but was:<null>`.

- [ ] **Step 3: Stamp the provenance**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt`, change the tail of `campgroundCandidate`:

```kotlin
            sourceUrl = "https://$host/",
            sourcePayload = aspiraSourcePayload(leaf),
            geometryProvenance =
                GeometryProvenance(
                    matchKind = match.kind.label,
                    source = point.source,
                    matchedName = match.matchedName,
                    score = match.score,
                ),
        )
    }
```

and replace `aspiraSourcePayload`:

```kotlin
    private fun aspiraSourcePayload(leaf: AspiraLeaf): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, leaf.mapId)
            leaf.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it) }
            leaf.parentName?.let { put("parent_name", it) }
        }
```

Add `import ca.floo.roadtrip.model.domain.GeometryProvenance`. Nothing becomes unused: `AspiraLeafMatchKind` is same-package here and is still read through `match.kind.label`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampgroundsEtlTest' --tests 'ca.floo.roadtrip.service.etl.framework.EtlExtrasDtoTest' --offline`
Expected: PASS.

- [ ] **Step 5: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtl.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampgroundsEtlTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlExtrasDtoTest.kt
git commit -F - <<'MSG'
feat(etl): the Aspira campground ETL records how each pin was found

Every emitted candidate carries a typed GeometryProvenance — match kind,
the geometry input slug that supplied the point, the index name it resolved
to, and the fuzzy score. match_kind leaves source_payload: one truth.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 7: The BC Parks ETL stamps provenance; its Strapi payload keys get pinned

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt`

**Interfaces:**
- Consumes: `GeometryProvenance` and `CampgroundUpsertCandidate.geometryProvenance` (Task 5); `AspiraLeafMatch.matchedName` / `.score` (Task 4); `GeometryPoint.source` (Task 2).
- Produces: `BcParksCampgroundsEtl.sourcePayload(leaf: AspiraLeaf, strapiRow: BcParksStrapiRow): JsonObject` — the `matchKind` parameter and the `match_kind` key are gone.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt`:

```kotlin
    @Test
    fun `the merged campground records its geometry provenance and Strapi payload keys`() {
        val cg = terminalRecords(etl, bundle(), ctx).single()

        assertEquals(
            GeometryProvenance(
                matchKind = "exact",
                source = "bcparks-strapi",
                matchedName = "rathtrevor beach",
            ),
            cg.geometryProvenance,
        )
        val payload = cg.sourcePayload!!.jsonObject
        assertNull(payload["match_kind"], "match_kind now lives in geometry_provenance, not the payload blob")
        assertEquals(1234, payload["strapi_orcs"]!!.jsonPrimitive.int)
        assertEquals("https://bcparks.ca/rathtrevor-beach/", payload["strapi_url"]!!.jsonPrimitive.content)
        assertEquals("Rathtrevor Beach", payload["name"]!!.jsonPrimitive.content)
    }
```

and add:

```kotlin
import ca.floo.roadtrip.model.domain.GeometryProvenance
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

(The leaf name "Rathtrevor Beach" and the Strapi name "Rathtrevor Beach Provincial Park" both normalize to `rathtrevor beach`, so this is the exact branch.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.bcparks.BcParksCampgroundsEtlTest' --offline`
Expected: FAIL — `expected:<GeometryProvenance(...)> but was:<null>`.

- [ ] **Step 3: Stamp the provenance**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt`, change the tail of `campgroundCandidate`:

```kotlin
            sourceUrl = bookingUrl,
            sourcePayload = sourcePayload(leaf, strapiRow),
            geometryProvenance =
                GeometryProvenance(
                    matchKind = match.kind.label,
                    source = match.value.source,
                    matchedName = match.matchedName,
                    score = match.score,
                ),
        )
    }
```

and replace `sourcePayload`:

```kotlin
    private fun sourcePayload(
        leaf: AspiraLeaf,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put("transactionLocationId", leaf.transactionLocationId)
            put("mapId", leaf.mapId)
            leaf.resourceLocationId?.let { put("resourceLocationId", it) }
            leaf.parentName?.let { put("parent_name", it) }
            strapiRow.orcs?.let { put("strapi_orcs", it) }
            strapiRow.url?.let { put("strapi_url", it) }
        }
```

Add `import ca.floo.roadtrip.model.domain.GeometryProvenance` and drop the now-unused `import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatchKind`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.bcparks.BcParksCampgroundsEtlTest' --offline`
Expected: PASS.

- [ ] **Step 5: Run the backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtl.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/bcparks/BcParksCampgroundsEtlTest.kt
git commit -F - <<'MSG'
feat(etl): the BC Parks campground ETL records how each pin was found

Same provenance as the Aspira ETL, sourced from the declared Strapi input,
and match_kind leaves source_payload here too. The strapi_orcs and
strapi_url payload keys were unpinned; now a test holds them.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 8: Docs — the `geometry:` block, the match ladder, and the review query

**Files:**
- Modify: `docs/adding-a-data-source.md`, `docs/reservation-providers/aspira.md`, `docs/backend-architecture.md`

**Interfaces:**
- Consumes: the shipped `geometry:` blocks, `GeometryFormat`'s four wire values, the `ACCEPTED_ARG_KEYS` boot errors, `campgrounds.geometry_provenance`.
- Produces: no code.

- [ ] **Step 1: Fix the `args: {}` example and document the block**

In `docs/adding-a-data-source.md`, replace the `args: {}` line in the `poi_data:` example:

```yaml
        args: {}                    # optional; transformer-specific (e.g. host, tenant)
```

and insert this section immediately after the paragraph beginning "The other steps just create the things these rows reference":

````markdown
### Geometry-joined adapters

Some vendors carry booking IDs but no coordinates. `AspiraCampgroundsEtl` and
`BcParksCampgroundsEtl` therefore join each leaf to a sibling feed *by name*,
and the row declares that join in a `geometry:` block on the ETL entry:

```yaml
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

- `sources` is **ordered, and the order is preference**: when two sources carry
  the same normalized name, the earlier one's point wins. Parks Canada declares
  campground points before park-polygon centroids for exactly that reason.
- `format` is a closed enum — one value, one parser class:

  | `format` | reads |
  |---|---|
  | `uscampgrounds_csv` | the nationwide uscampgrounds.info CSV |
  | `bcparks_strapi` | BC Parks Strapi protected-area pages |
  | `arcgis_centroids` | ArcGIS centroid-mode JSON (`attributes.DESC_EN` + `centroid`) |
  | `geojson_points` | a GeoJSON FeatureCollection of Points |

- `state` filters a source to one two-letter state. Valid **only** on
  `uscampgrounds_csv`, the only nationwide feed.
- `name_property` names the feature attribute holding the name. Valid **only**
  on `geojson_points`; the default is `name`, then `Name`.
- `match.fuzzy_threshold` is the minimum Jaccard token overlap for a fuzzy name
  match. Default `0.5`, valid range `(0, 1]`.
- `match.parent_fallback` lets a leaf that matches nothing itself take its
  parent park's point. Default `false`.

The registry is decoded strictly and validated at boot, so each of these is a
startup failure rather than a silently dropped setting:

- a geometry adapter with no `geometry:` block, or with an empty `sources` list;
- a `geometry.sources[].input` that is not in the row's `inputs:`, or declared twice;
- `state` on a format other than `uscampgrounds_csv`, or `name_property` on a
  format other than `geojson_points`;
- a `fuzzy_threshold` outside `(0, 1]`;
- `BcParksCampgroundsEtl` declaring anything but exactly one `bcparks_strapi` source;
- a `geometry:` block on an adapter that joins no geometry;
- any `args` key on these two adapters other than `host` and `tenant`;
- any key anywhere in the file that no model declares.
````

- [ ] **Step 2: Fix the stale class name and document the policy in the Aspira doc**

In `docs/reservation-providers/aspira.md`, replace `AspiraJoinByNameEtl` with `AspiraCampgroundsEtl` at all three sites (lines ~58, ~121, ~428):

```bash
sed -i '' 's/AspiraJoinByNameEtl/AspiraCampgroundsEtl/g' docs/reservation-providers/aspira.md
```

Then append this section to the end of the file:

````markdown
## Geometry: the name join, its policy, and its provenance

`/api/maps` carries booking IDs but no lat/lng, so each Aspira-backed
campground ETL joins its leaves to a sibling geometry feed by name. Which feed,
in which order, and how names are matched is declared on the ETL row in
`poi-registry.yaml` under `geometry:` — see the "Geometry-joined adapters"
section of `docs/adding-a-data-source.md` for the block's full shape and its
boot errors. Nothing in the code sniffs an input slug to pick a parser.

The match ladder, per leaf:

1. **Exact** — the leaf name, aggressively normalized (lowercase, punctuation
   dropped, park-y suffixes like `state park`, `provincial park`,
   `national park of canada`, `campground` stripped), against the merged
   name → point index.
2. **Fuzzy** — the highest Jaccard token overlap at or above
   `match.fuzzy_threshold` (default `0.5`). Each candidate is scored once and
   the first maximum in index order wins, so a tie resolves by source
   preference and then feed row order — the same rule that settles an exact
   name collision.
3. **Parent** — only when `match.parent_fallback` is set: the leaf's
   `parent_name`, normalized the same way. This is what keeps a park
   represented through its campgrounds after park-container leaves are dropped.

A leaf that clears none of the three is dropped: a booking ID alone does not
earn a pin.

Every emitted campground records the outcome in the `campgrounds`
`geometry_provenance` JSONB column (`GeometryProvenance`):

| key | meaning |
|---|---|
| `match_kind` | `exact`, `fuzzy` or `parent` |
| `source` | the geometry input slug that supplied the point |
| `matched_name` | the normalized index key the leaf resolved to |
| `score` | the Jaccard overlap for a `fuzzy` match; absent otherwise |

Reviewing the pins that are not exact:

```sql
SELECT name, geometry_provenance->>'match_kind' AS kind, geometry_provenance->>'source' AS source,
       geometry_provenance->>'matched_name' AS matched, geometry_provenance->>'score' AS score
FROM campgrounds WHERE geometry_provenance->>'match_kind' <> 'exact' ORDER BY score NULLS LAST;
```

The column is populated by `make data-import`, not by a migration, so it is
null on any row that has not been re-imported since `V62`.
````

- [ ] **Step 3: Note it in the architecture doc**

In `docs/backend-architecture.md`, insert after the paragraph ending "Persistence stays in repos." in the ETL Flow section:

```markdown
The two Aspira-backed campground ETLs join their leaves to a sibling geometry
feed by name; that join is registry-declared per row (`geometry:` in
`poi-registry.yaml`, typed and validated at boot), not inferred from input
slugs, and each emitted candidate records how its pin was found in the
`campgrounds.geometry_provenance` column.
```

- [ ] **Step 4: Verify the docs**

Run: `grep -rn "AspiraJoinByNameEtl\|state_filter\|parent_name_fallback" docs/adding-a-data-source.md docs/reservation-providers/aspira.md docs/backend-architecture.md backend/src/main/resources/poi-registry.yaml`
Expected: no output.

- [ ] **Step 5: Run the full gate**

Run: `make test`
Expected: PASS (backend tests + kover + ktlint + detekt, frontend, companion).

Then, with the stack up (`make run`), run the smoke suite the spec's verification names:

Run: `make qa`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/adding-a-data-source.md docs/reservation-providers/aspira.md docs/backend-architecture.md
git commit -F - <<'MSG'
docs: the geometry block, the match ladder, and the provenance review query

adding-a-data-source gains a "Geometry-joined adapters" section covering the
block, the four formats, preference order, the two per-source filters and
every boot error. The Aspira doc loses three references to a class deleted
two refactors ago and gains the ladder, the policy knobs and the review SQL.

Refs #740

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

## Deploy and live verification

Run after the branch merges, per the spec's Verification section. Not a task — no code changes.

1. `make test` green (the full local gate) and `make qa` green against a running stack.
2. On a `pg_dump` copy (`roadtrip_p7`): boot the branch backend against the copy so Flyway applies `V62`, then run `make data-import` for the three Aspira rows against the copy. Compare `(name, location, match kind)` for the 326 rows before and after:
   - zero coordinate diffs;
   - identical kind distribution (247 exact / 38 fuzzy / 41 parent);
   - `geometry_provenance` populated on every Aspira/BC row and null everywhere else;
   - the review query returns the 79 non-exact rows, each with a `source`.
3. Boot with a deliberately bad registry — add `state: WA` to the Parks Canada row's `apca-places` source — and confirm boot fails with:
   `poi_data 'Parks Canada' etl 'aspira-pc-campgrounds' geometry source 'apca-places' declares 'state', which only 'uscampgrounds_csv' honours`

## Out of scope (backlog, note on #740)

- Resolving maps/inventory/dictionaries inputs by role rather than substring. The new parse-time accounting check means an unrecognised input now fails loudly, which is the half that mattered.
- Using `/api/resourceLocation` `gpsCoordinates` as an in-tenant geometry source.
- `CampgroundLocation.region`/`country` set only by the BC ETL.
- Serving provenance on the API or an admin review page.
- Emitting misses (today dropped; the candidate's coordinates are non-null).
- `service/etl/vendors/bcparks/BcParksPhoto.kt` is referenced by nothing — `BcParksStrapiSource.extractPhotoUrl` reads the raw JSON. Dead since the Strapi parse was hand-rolled; deleting it is a separate change.
