# Aspira Campsite Photos Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `AspiraCampsitesEtl` promotes each inventory resource's `photos[].photoUrlResult.url` onto the campsite `photos` column.

**Architecture:** One private reader in the ETL turns the vendor's `photos` array into `List<CatalogPhoto>`; `ResourceInventory` carries it; the candidate sets it. No schema, wire, repo, or frontend change.

**Tech Stack:** Kotlin, kotlinx.serialization JSON tree, JUnit 5, ktlint, detekt.

**Spec:** `docs/superpowers/specs/2026-09-13-aspira-campsite-photos-design.md`

## Global Constraints

- No inline magic constants: the JSON keys `photos`, `photoUrlResult`, `url` are named `private const val`s, placed where this file already keeps its constants.
- Comments short and rare. No KDoc that restates the code.
- `org.jooq` stays out of `service/`. No Ktor names under `model/`.
- Do not touch `CatalogPhoto`, `CampsiteUpsertCandidate`, `CampsiteRepo`, the frontend, or any migration.
- Commit message ends with `Refs #758` and `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: Promote inventory photos in `AspiraCampsitesEtl`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampsitesEtl.kt` (`parseResourceInventory` ~line 276, the candidate construction ~line 195, `ResourceInventory` ~line 643)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampsitesEtlTest.kt`
- Modify: `docs/reservation-providers/aspira.md` (the `GET /api/resourcelocation/resources` section, ~lines 314-380)

**Interfaces:**
- Consumes: `ca.floo.roadtrip.model.domain.CatalogPhoto(url: String)`; `CampsiteUpsertCandidate.photos: List<CatalogPhoto> = emptyList()`.
- Produces: nothing new outside the file. `ResourceInventory` (private) gains `val photos: List<CatalogPhoto>`.

- [ ] **Step 1: Write the failing tests**

Add to `AspiraCampsitesEtlTest`, next to `inventoryPayload`, a second fixture. Same resource, plus a `photos` array with three entries: one complete, one with no `photoUrlResult`, one whose `url` is blank.

```kotlin
    private val inventoryWithPhotosPayload =
        """
        {
          "-2147481558": {
            "resourceId": -2147481558,
            "resourceLocationId": -2147483624,
            "resourceCategoryId": -2147483648,
            "localizedValues": [
              { "cultureName": "en-US", "name": "31", "description": "Lakeside" }
            ],
            "mapIds": [-2147483615],
            "allowedEquipment": [],
            "definedAttributes": [],
            "photos": [
              {
                "photoUrlResult": {
                  "url": "https://washington.goingtocamp.com/images/07008492-6f89-47e1-acaf-d986cb314dbc.jpg",
                  "avifUrl": "https://washington.goingtocamp.com/images/07008492-6f89-47e1-acaf-d986cb314dbc.avif"
                },
                "aspectType": 0
              },
              { "aspectType": 0 },
              { "photoUrlResult": { "url": "" }, "aspectType": 0 }
            ]
          }
        }
        """.trimIndent()
```

Two tests, modelled on `promotes inventory capacity description and named attributes onto typed columns` (same `etl` and `dto` construction, `dictionaries = AspiraCampsitesEtl.AspiraDictionaries.empty`):

```kotlin
    @Test
    fun `promotes inventory photo urls in source order and skips entries without a url`() {
        // etl and dto as in the capacity test, with inventory = listOf(envelopeOf(inventoryWithPhotosPayload))
        val campsite = records(etl.transform(dto, ctx)).single()

        assertEquals(
            listOf(CatalogPhoto("https://washington.goingtocamp.com/images/07008492-6f89-47e1-acaf-d986cb314dbc.jpg")),
            campsite.photos,
        )
    }

    @Test
    fun `a resource without photos yields no photos`() {
        // etl and dto as in the capacity test, with inventory = listOf(envelopeOf(inventoryPayload))
        val campsite = records(etl.transform(dto, ctx)).single()

        assertEquals(emptyList(), campsite.photos)
    }
```

Import `ca.floo.roadtrip.model.domain.CatalogPhoto` in the test.

- [ ] **Step 2: Run the tests to verify the first one fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampsitesEtlTest' --offline -q`
Expected: the photos test FAILS on the assertion (expected one photo, got an empty list). The no-photos test passes already.

- [ ] **Step 3: Implement the reader**

In `AspiraCampsitesEtl.kt`:

1. Import `ca.floo.roadtrip.model.domain.CatalogPhoto`.
2. Add `val photos: List<CatalogPhoto>,` to `ResourceInventory` after `mapIds`.
3. Add the three key constants where the file keeps its other constants (a `companion object` or top-level `private const val`s; match what is already there):

```kotlin
private const val PHOTOS_FIELD = "photos"
private const val PHOTO_URL_RESULT_FIELD = "photoUrlResult"
private const val PHOTO_URL_FIELD = "url"
```

4. Add a private reader beside `parseResourceInventory`:

```kotlin
    private fun resourcePhotos(obj: JsonObject): List<CatalogPhoto> =
        (obj[PHOTOS_FIELD] as? JsonArray).orEmpty().mapNotNull { entry ->
            val result = (entry as? JsonObject)?.get(PHOTO_URL_RESULT_FIELD) as? JsonObject
            result
                ?.get(PHOTO_URL_FIELD)
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let(::CatalogPhoto)
        }
```

5. In `parseResourceInventory`, pass `photos = resourcePhotos(obj)` to the `ResourceInventory` constructor.
6. In the candidate construction, add `photos = inv.photos,` after `minPeople = inv.minCapacity,`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampsitesEtlTest' --offline -q`
Expected: PASS.

Then: `./gradlew :backend:ktlintCheck :backend:detekt --offline -q`
Expected: clean. If ktlint wants the chain wrapped differently, follow it.

- [ ] **Step 5: Update the doc**

In `docs/reservation-providers/aspira.md`, section `GET /api/resourcelocation/resources`:

1. Replace the example's `"photos": [],` line with the real shape:

```
    "photos": [                              // site photos; url is the JPEG
      {"photoUrlResult": {"url": "https://{host}/images/{uuid}.jpg", "avifUrl": "https://{host}/images/{uuid}.avif"}, "aspectType": 0}
    ],
```

2. Add one row to the field-mapping table, after the `definedAttributes[]` row:

```
| `photos[]` | `campsites.photos` | `photoUrlResult.url` per entry, in order; entries without a URL are skipped. First one is `CampsiteDto.photo_url` |
```

Leave the rest of the table as it is.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampsitesEtl.kt backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/aspira/AspiraCampsitesEtlTest.kt docs/reservation-providers/aspira.md
git commit -F <message file>
```

Message:

```
feat(etl): Aspira campsites carry their inventory photos

The resources inventory lists photos per site as photoUrlResult.url;
the ETL now reads them in order onto the photos column, skipping
entries without a URL. No schema or wire change; a re-import fills it.

Refs #758

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```
