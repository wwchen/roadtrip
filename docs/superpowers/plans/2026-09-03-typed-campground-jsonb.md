# Typed Campground JSONB Columns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `location`, `links`, `photos`, `management` and `contact` typed on both the campground upsert candidate and the campground row, with `CampgroundRepo` as the only JSON encoder and decoder, and delete the read path's per-vendor key preference lists.

**Architecture:** Vendor ETLs construct domain types; Campflare maps its upstream keys into them inside its ETL. `CampgroundRepo` encodes on write and decodes on read through one codec in `model/domain`. A Flyway migration rewrites stored rows into the canonical keys once, so the decoder is strict. The API DTO keeps `JsonElement` fields, so the frontend contract does not change.

**Tech Stack:** Kotlin/Ktor, kotlinx.serialization, jOOQ + Flyway + Postgres (Testcontainers in tests), detekt + ktlint.

**Spec:** `docs/superpowers/specs/2026-09-03-typed-campground-jsonb-design.md`

## Global Constraints

- Layering per `AGENTS.md`: SQL only in `repo`, no Ktor types in `service`, models depend on stdlib + serialization only.
- One meaningful top-level model per file; detekt forbids functions and `var` in data classes.
- Never edit an applied migration; the new migration is `V55`.
- Every JSON key name written to the five columns stays exactly as today: `latitude`, `longitude`, `region`, `country`, `elevation`, `address`, `street`, `city`, `state`, `postcode`, `url`, `title`, `agency`, `website`, `phone`, `email`.
- The API response for `/api/pois/{id}` keeps `address`, `links`, `management`, `contact` as JSON values; an absent object is sent as `{}` and an absent list as `[]`, exactly as today.
- Fast test loop: `./gradlew :backend:test --tests '<class>' --offline -q`. Full gate before the PR: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`.

---

## File map

**Created:**

- `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJson.kt` — the one codec for the five columns.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundFields.kt` — pure upstream-to-domain normalizers.
- `backend/src/main/resources/db/migration/V55__normalize_campground_jsonb.sql` — rewrites stored rows into canonical keys.
- `backend/src/test/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJsonTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundFieldsTest.kt`

**Modified:**

- `model/domain/Address.kt` — `@Serializable`.
- `model/domain/CampgroundLocation.kt`, `CampgroundLink.kt`, `CampgroundManagement.kt`, `CampgroundContact.kt` — extra optional fields.
- `model/domain/CampgroundUpsertCandidate.kt` — five fields become typed.
- `model/domain/Campground.kt` — five fields become typed.
- `repo/CampgroundRepo.kt` — encode in `bulkUpsertCampgroundRows`, decode in `fromRecord`.
- `service/etl/vendors/{aspira,bcparks,recgov,reserveamerica,reservecalifornia,campflare}/*CampgroundsEtl.kt` — construct domain types.
- `service/poi/CampgroundService.kt` — typed reads, preference lists deleted.
- Tests that build candidates or rows: `repo/CatalogEntityRepoTest.kt`, the six vendor ETL tests, `service/poi/PoiServiceTest.kt`, `service/availability/provider/TestCampground.kt`.
- `docs/backend-architecture.md` — one paragraph under Models.

**Deleted:**

- `service/etl/framework/CampgroundJsonb.kt` and `CampgroundJsonbTest.kt` (superseded by the codec; the ETL layer no longer encodes).

---

### Task 1: Domain types and the column codec

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Address.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundLocation.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundLink.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundManagement.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundContact.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJson.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJsonTest.kt`

**Interfaces:**
- Produces: `CampgroundLocation(latitude, longitude, region?, country?, elevation?, address: Address?)`, `CampgroundLink(url, title?)`, `CampgroundPhoto(url)` (unchanged), `CampgroundManagement(agency, website?)`, `CampgroundContact(phone?, email?)`.
- Produces: `CampgroundColumnJson.encodeObject(value: T?): String`, `encodeArray(values: List<T>): String`, `decodeObject<T>(raw: String): T?`, `decodeArray<T>(raw: String): List<T>`, `element(value: T?): JsonElement`, `elements(values: List<T>): JsonElement`.

- [ ] **Step 1: Write the failing codec test**

```kotlin
package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampgroundColumnJsonTest {
    @Test
    fun `object round-trips and omits absent fields`() {
        val location = CampgroundLocation(51.18, -115.57, region = "AB", address = Address(city = "Banff"))
        val raw = CampgroundColumnJson.encodeObject(location)
        assertEquals("""{"latitude":51.18,"longitude":-115.57,"region":"AB","address":{"city":"Banff"}}""", raw)
        assertEquals(location, CampgroundColumnJson.decodeObject<CampgroundLocation>(raw))
    }

    @Test
    fun `absent object is the empty object on write and null on read`() {
        assertEquals("{}", CampgroundColumnJson.encodeObject<CampgroundManagement>(null))
        assertNull(CampgroundColumnJson.decodeObject<CampgroundManagement>("{}"))
        assertNull(CampgroundColumnJson.decodeObject<CampgroundManagement>("null"))
    }

    @Test
    fun `arrays round-trip and non-arrays read as empty`() {
        val links = listOf(CampgroundLink("https://a.test/", title = "A"), CampgroundLink("https://b.test/"))
        val raw = CampgroundColumnJson.encodeArray(links)
        assertEquals("""[{"url":"https://a.test/","title":"A"},{"url":"https://b.test/"}]""", raw)
        assertEquals(links, CampgroundColumnJson.decodeArray<CampgroundLink>(raw))
        assertEquals(emptyList(), CampgroundColumnJson.decodeArray<CampgroundLink>("null"))
    }

    @Test
    fun `unknown stored keys are ignored on read`() {
        val contact = CampgroundColumnJson.decodeObject<CampgroundContact>("""{"phone":"1","fax":"2"}""")
        assertEquals(CampgroundContact(phone = "1"), contact)
    }

    @Test
    fun `element helpers send the empty object and array for absent values`() {
        assertEquals(JsonObject(emptyMap()), CampgroundColumnJson.element<CampgroundContact>(null))
        assertEquals("[]", CampgroundColumnJson.elements(emptyList<CampgroundPhoto>()).toString())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.domain.CampgroundColumnJsonTest' --offline -q`
Expected: compilation failure, `CampgroundColumnJson` unresolved.

- [ ] **Step 3: Extend the domain types**

`Address.kt`: add `import kotlinx.serialization.Serializable` and `@Serializable` above the class. No field changes.

`CampgroundLocation.kt`:

```kotlin
@Serializable
data class CampgroundLocation(
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val country: String? = null,
    val elevation: Double? = null,
    val address: Address? = null,
)
```

Remove the `JsonObject` import. `CampgroundLink.kt` gains `val title: String? = null`. `CampgroundManagement.kt` gains `val website: String? = null`. `CampgroundContact.kt` becomes `val phone: String? = null, val email: String? = null`.

- [ ] **Step 4: Write the codec**

```kotlin
package ca.floo.roadtrip.model.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The one codec for the typed campground JSONB columns. Absent values are the
 * empty object / array on the wire and in the table; unknown stored keys are
 * ignored on read.
 */
object CampgroundColumnJson {
    const val EMPTY_OBJECT = "{}"

    @OptIn(ExperimentalSerializationApi::class)
    val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    inline fun <reified T : Any> encodeObject(value: T?): String = value?.let { json.encodeToString(it) } ?: EMPTY_OBJECT

    inline fun <reified T : Any> encodeArray(values: List<T>): String = json.encodeToString(values)

    inline fun <reified T : Any> decodeObject(raw: String): T? {
        val element = json.parseToJsonElement(raw)
        if (element !is JsonObject || element.isEmpty()) return null
        return json.decodeFromJsonElement(element)
    }

    inline fun <reified T : Any> decodeArray(raw: String): List<T> {
        val element = json.parseToJsonElement(raw)
        if (element !is JsonArray) return emptyList()
        return json.decodeFromJsonElement(element)
    }

    inline fun <reified T : Any> element(value: T?): JsonElement = value?.let { json.encodeToJsonElement(it) } ?: JsonObject(emptyMap())

    inline fun <reified T : Any> elements(values: List<T>): JsonElement = json.encodeToJsonElement(values)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.domain.CampgroundColumnJsonTest' --offline -q`
Expected: PASS. The existing `CampgroundJsonbTest` still passes because the added fields default to `null` and are omitted.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/domain backend/src/test/kotlin/ca/floo/roadtrip/model/domain/CampgroundColumnJsonTest.kt
git commit -m "feat(model): typed campground column shapes and one JSONB codec"
```

---

### Task 2: Campflare normalizers

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundFields.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundFieldsTest.kt`

**Interfaces:**
- Consumes: `JsonObject.stringField(name)`, `doubleField`, `objectField`, `arrayField` from `CampflareEtlSupport.kt` (internal, same package).
- Produces (internal, top-level): `campflareLocation(location: JsonObject, latitude: Double, longitude: Double): CampgroundLocation`, `campflareLinks(links: JsonElement?, sourceUrl: String): List<CampgroundLink>`, `campflarePhotos(photos: JsonElement?): List<CampgroundPhoto>`, `campflareManagement(management: JsonObject?): CampgroundManagement?`, `campflareContact(contact: JsonObject?): CampgroundContact?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampflareCampgroundFieldsTest {
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `location keeps elevation and normalizes the nested address keys`() {
        val raw = obj("""{"latitude":37.7,"longitude":-119.5,"elevation":4000,"address":{"street1":"1 Park Rd","state_code":"CA","zipcode":"95389","country_code":"US"}}""")
        assertEquals(
            CampgroundLocation(37.7, -119.5, elevation = 4000.0, address = Address(street = "1 Park Rd", state = "CA", postcode = "95389", country = "US")),
            campflareLocation(raw, latitude = 37.7, longitude = -119.5),
        )
    }

    @Test
    fun `photos prefer url then large medium small original and drop entries without one`() {
        val raw = obj("""{"photos":[{"original_url":"o","large_url":"l"},{"small_url":"s"},{"caption":"none"}]}""")
        assertEquals(listOf(CampgroundPhoto("l"), CampgroundPhoto("s")), campflarePhotos(raw["photos"]))
    }

    @Test
    fun `links keep title and append the Campflare source link once`() {
        val raw = obj("""{"links":[{"href":"https://nps.test","label":"NPS"},{"url":"https://campflare.test/c/1"}]}""")
        assertEquals(
            listOf(CampgroundLink("https://nps.test", title = "NPS"), CampgroundLink("https://campflare.test/c/1")),
            campflareLinks(raw["links"], sourceUrl = "https://campflare.test/c/1"),
        )
        assertEquals(
            listOf(CampgroundLink("https://campflare.test/c/2", title = "Campflare")),
            campflareLinks(null, sourceUrl = "https://campflare.test/c/2"),
        )
    }

    @Test
    fun `management needs an agency and carries the website`() {
        assertEquals(
            CampgroundManagement("National Park Service", website = "https://nps.test"),
            campflareManagement(obj("""{"agency_name":"National Park Service","agency_id":7,"agency_website":"https://nps.test"}""")),
        )
        assertNull(campflareManagement(obj("""{"agency_id":7}""")))
        assertNull(campflareManagement(null))
    }

    @Test
    fun `contact maps primary keys and is null when empty`() {
        assertEquals(CampgroundContact(phone = "555", email = "a@b.test"), campflareContact(obj("""{"primary_phone":"555","primary_email":"a@b.test"}""")))
        assertNull(campflareContact(obj("""{"fax":"1"}""")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampgroundFieldsTest' --offline -q`
Expected: compilation failure, functions unresolved.

- [ ] **Step 3: Write the normalizers**

```kotlin
package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

// Campflare's upstream key sets, in the precedence order the read path used
// before the columns were typed. First present, non-blank key wins.
private val photoUrlKeys = listOf("url", "large_url", "medium_url", "small_url", "original_url")
private val linkUrlKeys = listOf("url", "href")
private val linkTitleKeys = listOf("title", "label", "name")
private val agencyKeys = listOf("agency", "agency_name")
private val websiteKeys = listOf("agency_website", "website_url", "website", "url")
private val phoneKeys = listOf("phone", "primary_phone")
private val emailKeys = listOf("email", "primary_email")
private val streetKeys = listOf("street", "street1", "address_line")
private val stateKeys = listOf("state", "state_code")
private val postcodeKeys = listOf("postcode", "postal_code", "zipcode")
private val countryKeys = listOf("country", "country_code")

internal const val CAMPFLARE_SOURCE_LINK_TITLE = "Campflare"

internal fun campflareLocation(
    location: JsonObject,
    latitude: Double,
    longitude: Double,
): CampgroundLocation =
    CampgroundLocation(
        latitude = latitude,
        longitude = longitude,
        region = location.stringField("region"),
        country = location.stringField("country"),
        elevation = location.doubleField("elevation"),
        address = location.objectField("address")?.let(::campflareAddress),
    )

private fun campflareAddress(address: JsonObject): Address? {
    val parsed =
        Address(
            street = address.first(streetKeys),
            city = address.stringField("city"),
            state = address.first(stateKeys),
            postcode = address.first(postcodeKeys),
            country = address.first(countryKeys),
        )
    return parsed.takeIf { it != Address() }
}

internal fun campflarePhotos(photos: JsonElement?): List<CampgroundPhoto> =
    photos?.jsonArray.orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.first(photoUrlKeys)?.let(::CampgroundPhoto)
    }

internal fun campflareLinks(
    links: JsonElement?,
    sourceUrl: String,
): List<CampgroundLink> {
    val parsed =
        links?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val url = obj.first(linkUrlKeys) ?: return@mapNotNull null
            CampgroundLink(url = url, title = obj.first(linkTitleKeys))
        }
    if (parsed.any { it.url == sourceUrl }) return parsed
    return parsed + CampgroundLink(url = sourceUrl, title = CAMPFLARE_SOURCE_LINK_TITLE)
}

internal fun campflareManagement(management: JsonObject?): CampgroundManagement? {
    val agency = management?.first(agencyKeys) ?: return null
    return CampgroundManagement(agency = agency, website = management.first(websiteKeys))
}

internal fun campflareContact(contact: JsonObject?): CampgroundContact? {
    val phone = contact?.first(phoneKeys)
    val email = contact?.first(emailKeys)
    if (phone == null && email == null) return null
    return CampgroundContact(phone = phone, email = email)
}

private fun JsonObject.first(keys: List<String>): String? = keys.firstNotNullOfOrNull { stringField(it) }
```

Then delete `campgroundLinksWithCampflareSource`, `sourceLinkUrl`, `CAMPFLARE_SOURCE_LINK_TITLE`, `TITLE_FIELD`, `URL_FIELD`, `HREF_FIELD`, `LINKS_FIELD` from `CampflareEtlSupport.kt` (grep first; keep any constant another file still uses). Check the exact source-link title string in `CampflareEtlSupport.kt` before deleting and copy it verbatim into the constant above.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.campflare.CampflareCampgroundFieldsTest' --offline -q`
Expected: PASS. `CampflareCampgroundsEtl` still compiles because it does not use these yet.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare backend/src/test/kotlin/ca/floo/roadtrip/service/etl/vendors/campflare/CampflareCampgroundFieldsTest.kt
git commit -m "feat(etl): Campflare upstream-to-domain normalizers for the typed columns"
```

---

### Task 3: Typed candidate, repo encodes, ETLs construct domain types

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/CampgroundUpsertCandidate.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt` (`bulkUpsertCampgroundRows`, the `params +=` block)
- Modify: the six `*CampgroundsEtl.kt` files
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/CampgroundJsonb.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/CampgroundJsonbTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt`, the six vendor ETL tests

**Interfaces:**
- Consumes: the Task 1 types and codec, the Task 2 normalizers.
- Produces: `CampgroundUpsertCandidate.location: CampgroundLocation`, `links: List<CampgroundLink> = emptyList()`, `photos: List<CampgroundPhoto> = emptyList()`, `management: CampgroundManagement? = null`, `contact: CampgroundContact? = null`.

- [ ] **Step 1: Change the candidate**

Replace the five fields:

```kotlin
    val location: CampgroundLocation,
    ...
    val links: List<CampgroundLink> = emptyList(),
    val photos: List<CampgroundPhoto> = emptyList(),
    ...
    val management: CampgroundManagement? = null,
    val contact: CampgroundContact? = null,
```

`location` has no default: every vendor has a pin. `latitude` and `longitude` stay as separate fields for the geometry column; do not remove them in this change.

- [ ] **Step 2: Update the ETL tests first so they state the new shape**

In each vendor test, JSON-poking assertions become property reads. Exact replacements:

`ReserveAmericaCampgroundsEtlTest.kt` lines 39-44 and 87-92:
```kotlin
assertEquals("NY", campground.location.region)
assertEquals("US", campground.location.country)
assertEquals("New York State Parks", campground.management!!.agency)
```
and line 50: `assertEquals("https://newyorkstateparks.reserveamerica.com/photo.jpg", campground.photos.single().url)`. Delete the `jsonObjectArrayFirstUrl` helper if nothing else uses it.

`ReserveCaliforniaCampgroundsEtlTest.kt` lines 35-46: `campground.photos.single().url`, `campground.location.region`, `campground.location.country`, `campground.management!!.agency`.

`AspiraCampgroundsEtlTest.kt` lines 210-211: `assertEquals("Parks Canada", campground.management!!.agency)`.

`BcParksCampgroundsEtlTest.kt` lines 50-67: `assertEquals("<expected url>", cg.photos.single().url)`, `assertEquals("<expected phone>", cg.contact!!.phone)`, `assertEquals("<expected agency>", cg.management!!.agency)`; copy the expected literals from the current assertions. Also assert the two-link case: `assertEquals(listOf("https://camping.bcparks.ca/", "<strapi url>"), cg.links.map { it.url })`.

`RecGovCampgroundsEtlTest.kt` lines 62-67: `assertEquals("National Park Service", upperPines.management!!.agency)`, `upperPines.photos.single().url`. Line 128 (`assertNull(... .management)`) stays. Add: `assertEquals(Address(street = "<street>", city = "<city>", state = "CA", postcode = "<zip>", country = "US"), upperPines.location.address)` using the fixture's address values.

`CampflareCampgroundsEtlTest.kt`: rename `promotes management agency_name to the canonical agency key` to `management maps agency_name to agency`; assert `management!!.agency == "National Park Service"`. Add to the full-payload test: `assertEquals(4000.0, campground.location.elevation)`, `assertEquals(Address(state = "CA", country = "US"), campground.location.address)`, `assertEquals(listOf(CampgroundLink("https://www.nps.gov/yose", "NPS"), CampgroundLink(sourceUrl, "Campflare")), campground.links)`, `assertEquals(listOf(CampgroundPhoto("https://cdn.example/p.jpg")), campground.photos)`, `assertEquals(CampgroundContact(phone = "555-0100"), campground.contact)`.

`CatalogEntityRepoTest.kt`: the ten `CampgroundUpsertCandidate(` constructions. Line 32 becomes `location = CampgroundLocation(37.739, -119.565, address = Address(state = "CA", country = "US"))`; line 34 `management = CampgroundManagement("National Park Service")`; line 238 `location = CampgroundLocation(37.739, -119.565)`. Any assertion that reads the stored column back through SQL (`management->>'agency'`) is unchanged; assertions reading `json(...)` fixtures compare typed values instead.

- [ ] **Step 3: Run the ETL tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.vendors.*' --offline -q`
Expected: compilation failure across the vendor ETLs and tests.

- [ ] **Step 4: Repo encodes**

In `bulkUpsertCampgroundRows`, replace five lines of the `params +=` block:

```kotlin
params += CampgroundColumnJson.encodeObject(record.location)
...
params += CampgroundColumnJson.encodeArray(record.links)
params += CampgroundColumnJson.encodeArray(record.photos)
...
params += CampgroundColumnJson.encodeObject(record.management)
params += CampgroundColumnJson.encodeObject(record.contact)
```

The `?::jsonb` placeholders and the SQL are unchanged. Import `ca.floo.roadtrip.model.domain.CampgroundColumnJson`.

- [ ] **Step 5: Vendor ETLs construct domain types**

`AspiraCampgroundsEtl.kt` `campgroundCandidate`:
```kotlin
location = CampgroundLocation(latitude = lat, longitude = lon),
reservationUrl = "https://$host/",
links = listOf(CampgroundLink("https://$host/")),
management = CampgroundManagement(agency),
```

`BcParksCampgroundsEtl.kt` `campgroundCandidate`:
```kotlin
location = CampgroundLocation(strapiRow.lat, strapiRow.lon, region = REGION, country = COUNTRY),
reservationUrl = bookingUrl,
links = listOfNotNull(bookingUrl, strapiRow.url?.takeIf { it != bookingUrl }).map { CampgroundLink(it) },
photos = listOfNotNull(strapiRow.photoUrl?.let { CampgroundPhoto(it) }),
management = CampgroundManagement(agency),
contact = strapiRow.phone?.let { CampgroundContact(phone = it) },
```

`RecGovCampgroundsEtl.kt` `transformRow`: change `addressPayload` to return the domain type and rename it:
```kotlin
private fun address(address: FacilityAddress?): Address? {
    if (address == null) return null
    val parsed =
        Address(
            street = address.FacilityStreetAddress1?.takeIf { it.isNotBlank() },
            city = address.City?.takeIf { it.isNotBlank() },
            state = address.AddressStateCode?.takeIf { it.isNotBlank() },
            postcode = address.PostalCode?.takeIf { it.isNotBlank() },
            country = normalizeCountry(address.AddressCountryCode),
        )
    return parsed.takeIf { it != Address() }
}
```
and in the candidate:
```kotlin
location = CampgroundLocation(lat, lon, region = region, country = country, address = address(firstAddr)),
reservationUrl = infoUrl,
links = listOfNotNull(infoUrl?.let { CampgroundLink(it) }),
photos = listOfNotNull(photoUrl?.let { CampgroundPhoto(it) }),
cellService = cell?.let(::cellCoveragePayload),
management = agency?.let { CampgroundManagement(it) },
contact = row.FacilityPhone?.takeIf { it.isNotBlank() }?.let { CampgroundContact(phone = it) },
```

`ReserveAmericaCampgroundsEtl.kt`:
```kotlin
location = CampgroundLocation(park.lat, park.lon, region = settings.region, country = settings.country),
reservationUrl = park.infoUrl,
links = listOfNotNull(park.infoUrl?.let { CampgroundLink(it) }),
photos = listOfNotNull(park.photoUrl?.let { CampgroundPhoto(it) }),
management = CampgroundManagement(settings.agency),
```

`ReserveCaliforniaCampgroundsEtl.kt`:
```kotlin
location = CampgroundLocation(place.latitude, place.longitude, region = REGION, country = COUNTRY),
amenities = amenitiesPayload(place.amenities),
reservationUrl = parkUrl,
links = listOf(CampgroundLink(parkUrl)),
photos = listOfNotNull(place.imageUrl?.let { CampgroundPhoto(it) }),
management = CampgroundManagement(agency),
```

`CampflareCampgroundsEtl.kt`:
```kotlin
location = campflareLocation(location!!, latitude = latitude!!, longitude = longitude!!),
...
links = campflareLinks(raw.arrayField("links"), sourceUrl),
photos = campflarePhotos(raw.arrayField("photos")),
...
management = campflareManagement(raw.objectField("management")),
contact = campflareContact(raw.objectField("contact")),
```
Delete `normalizedManagement`, `AGENCY_KEY`, `AGENCY_NAME_KEY` from this file. `location` is already validated non-null above the candidate; keep the existing `!!` style used for `name`.

Delete `service/etl/framework/CampgroundJsonb.kt` and its test. Remove the now-unused `CampgroundJsonb` imports and add the `model.domain` imports each file needs. Run `./gradlew :backend:ktlintFormat --offline -q` to fix import order.

- [ ] **Step 6: Run the ETL and repo tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.etl.*' --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --offline -q`
Expected: PASS. `PoiServiceTest` is not run here; it still reads `JsonElement` rows and is untouched until Task 5.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "refactor(etl): typed campground columns on the upsert candidate; repo is the encoder"
```

---

### Task 4: Migration for stored rows

**Files:**
- Create: `backend/src/main/resources/db/migration/V55__normalize_campground_jsonb.sql`

**Interfaces:**
- Produces: every `campgrounds` row's five columns in canonical keys, so Task 5's strict decoder is safe.

- [ ] **Step 1: Write the dry-run count and run it against a production snapshot**

```sql
SELECT
  count(*) FILTER (WHERE jsonb_typeof(location) = 'object' AND NOT (location ? 'latitude')) AS location_without_latitude,
  count(*) FILTER (WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements(photos) p
    WHERE COALESCE(p->>'url', p->>'large_url', p->>'medium_url', p->>'small_url', p->>'original_url') IS NULL)) AS photos_dropped,
  count(*) FILTER (WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements(links) l WHERE COALESCE(l->>'url', l->>'href') IS NULL)) AS links_dropped,
  count(*) FILTER (WHERE management <> '{}'::jsonb AND COALESCE(management->>'agency', management->>'agency_name') IS NULL) AS management_dropped,
  count(*) FILTER (WHERE contact <> '{}'::jsonb
    AND COALESCE(contact->>'phone', contact->>'primary_phone') IS NULL
    AND COALESCE(contact->>'email', contact->>'primary_email') IS NULL) AS contact_dropped
FROM campgrounds WHERE deleted_at IS NULL;
```

Expected: all zero, or a count you can explain per vendor. Any `location_without_latitude` above zero needs a look before the migration ships; the decoder in Task 5 treats `{}` as absent but not a location missing only its coordinates.

- [ ] **Step 2: Write the migration**

```sql
-- Normalize the five typed campground JSONB columns into their canonical
-- keys. Vendors other than Campflare already wrote these keys; Campflare
-- rows carried upstream names (primary_phone, large_url, agency_name, ...).
-- The read path now decodes strictly, so this runs once over stored rows.
-- Idempotent: canonical rows map to themselves. source_payload is untouched.

UPDATE campgrounds SET location = jsonb_strip_nulls(jsonb_build_object(
  'latitude',  location->'latitude',
  'longitude', location->'longitude',
  'region',    location->>'region',
  'country',   location->>'country',
  'elevation', location->'elevation',
  'address',   CASE WHEN jsonb_typeof(location->'address') = 'object' THEN
    NULLIF(jsonb_strip_nulls(jsonb_build_object(
      'street',   COALESCE(location->'address'->>'street', location->'address'->>'street1', location->'address'->>'address_line'),
      'city',     location->'address'->>'city',
      'state',    COALESCE(location->'address'->>'state', location->'address'->>'state_code'),
      'postcode', COALESCE(location->'address'->>'postcode', location->'address'->>'postal_code', location->'address'->>'zipcode'),
      'country',  COALESCE(location->'address'->>'country', location->'address'->>'country_code'))), '{}'::jsonb)
  END))
WHERE jsonb_typeof(location) = 'object' AND location ? 'latitude';

UPDATE campgrounds SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord, COALESCE(p.v->>'url', p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array';

UPDATE campgrounds SET links = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('url', s.url, 'title', s.title)) ORDER BY s.ord)
  FROM (
    SELECT l.ord,
           COALESCE(l.v->>'url', l.v->>'href') AS url,
           COALESCE(l.v->>'title', l.v->>'label', l.v->>'name') AS title
    FROM jsonb_array_elements(links) WITH ORDINALITY AS l(v, ord)
    WHERE jsonb_typeof(l.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(links) = 'array';

UPDATE campgrounds SET management = CASE
  WHEN COALESCE(management->>'agency', management->>'agency_name') IS NULL THEN '{}'::jsonb
  ELSE jsonb_strip_nulls(jsonb_build_object(
    'agency',  COALESCE(management->>'agency', management->>'agency_name'),
    'website', COALESCE(management->>'agency_website', management->>'website_url', management->>'website', management->>'url')))
  END
WHERE jsonb_typeof(management) = 'object';

UPDATE campgrounds SET contact = jsonb_strip_nulls(jsonb_build_object(
  'phone', COALESCE(contact->>'phone', contact->>'primary_phone'),
  'email', COALESCE(contact->>'email', contact->>'primary_email')))
WHERE jsonb_typeof(contact) = 'object';
```

- [ ] **Step 3: Verify Flyway applies it**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CatalogEntityRepoTest' --offline -q`
Expected: PASS. The Testcontainers template database runs every migration, so a syntax error fails here.

- [ ] **Step 4: Verify idempotence on a seeded row**

Add to `CatalogEntityRepoTest.kt`:

```kotlin
@Test
fun `normalization migration is a no-op on canonical rows`() {
    val id = upsertOne(candidate(location = CampgroundLocation(1.0, 2.0, region = "CA", address = Address(city = "X")), links = listOf(CampgroundLink("https://a.test/", "A")), photos = listOf(CampgroundPhoto("https://p.test/1.jpg")), management = CampgroundManagement("NPS", website = "https://nps.test"), contact = CampgroundContact(phone = "1", email = "e@x.test")))
    val before = columnsAsText(id)
    ctx.execute(File("src/main/resources/db/migration/V55__normalize_campground_jsonb.sql").readText())
    assertEquals(before, columnsAsText(id))
}
```

Use the test's existing helpers for inserting one candidate and reading columns back as text (`candidate(...)`, `upsertOne(...)`, `columnsAsText(...)` are names to match to what the file already has; if it has no such helpers, add private ones that call `CampgroundRepo.upsert*` and `SELECT location::text, links::text, photos::text, management::text, contact::text FROM campgrounds WHERE id = ?`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V55__normalize_campground_jsonb.sql backend/src/test/kotlin/ca/floo/roadtrip/repo/CatalogEntityRepoTest.kt
git commit -m "feat(db): normalize stored campground JSONB columns into canonical keys"
```

---

### Task 5: Typed row, repo decodes, service reads properties

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/Campground.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/CampgroundRepo.kt` (`fromRecord`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/CampgroundService.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/TestCampground.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/poi/PoiServiceTest.kt`

**Interfaces:**
- Consumes: Task 1 codec, Task 4 guarantee that stored rows are canonical.
- Produces: `Campground.location: CampgroundLocation?`, `links: List<CampgroundLink>`, `photos: List<CampgroundPhoto>`, `management: CampgroundManagement?`, `contact: CampgroundContact?`.

- [ ] **Step 1: Rewrite the Campflare-keys service test to canonical keys**

In `PoiServiceTest.kt`, the test `campground detail reads photo, phone and email from Campflare's keys` (line 410) seeds `{"original_url": ...}` and `{"primary_phone": ..., "primary_email": ...}`. After Task 4 such rows cannot exist. Replace it with:

```kotlin
@Test
fun `campground detail reads photo, phone and email from the canonical columns`() {
    // seed as the existing test does, but with:
    // photos  = """[{"url":"https://cdn.example/p.jpg"}]"""
    // contact = """{"phone":"555-0100","email":"info@example.test"}"""
    assertEquals("https://cdn.example/p.jpg", detail.photoUrl)
    assertEquals("555-0100", detail.phone)
    assertEquals("info@example.test", detail.email)
}
```

Keep the other seeded-JSON tests (`reads description and photo from canonical columns`, `extracts email, elevation and last_verified from nested JSONB`, `serves its own columns as named schema fields`); change any seeded `{"agency_name":"NPS"}` (line 327) to `{"agency":"NPS"}` and the assertion at line 394 from `["agency_name"]` to `["agency"]`.

- [ ] **Step 2: Run PoiServiceTest to verify the changed tests fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.PoiServiceTest' --offline -q`
Expected: PASS. The service still serves the stored JSON verbatim, so canonical seeds already satisfy these assertions. This step proves the fixtures are valid before the read path changes; the failure this task guards against is the strict decoder in Step 3 meeting a non-canonical seed.

- [ ] **Step 3: Type the row and decode in the repo**

`Campground.kt`: replace the five `JsonElement` fields with

```kotlin
    val location: CampgroundLocation?,
    ...
    val links: List<CampgroundLink>,
    val photos: List<CampgroundPhoto>,
    ...
    val management: CampgroundManagement?,
    val contact: CampgroundContact?,
```

`CampgroundRepo.fromRecord`:

```kotlin
location = CampgroundColumnJson.decodeObject(record.get("location_text", String::class.java)),
...
links = CampgroundColumnJson.decodeArray(record.get("links_text", String::class.java)),
photos = CampgroundColumnJson.decodeArray(record.get("photos_text", String::class.java)),
...
management = CampgroundColumnJson.decodeObject(record.get("management_text", String::class.java)),
contact = CampgroundColumnJson.decodeObject(record.get("contact_text", String::class.java)),
```

`TestCampground.kt`: `location = null`, `links = emptyList()`, `photos = emptyList()`, `management = null`, `contact = null`.

- [ ] **Step 4: Service reads properties**

In `CampgroundService.poiDetailProperties`:

```kotlin
val infoUrl = campground.links.firstOrNull()?.url
val photoUrl = campground.photos.firstOrNull()?.url
val dateContext = dateResolver.context(lat = campground.location?.latitude, lng = campground.location?.longitude)
...
agency = campground.management?.agency,
region = campground.location?.region,
country = campground.location?.country,
...
phone = campground.contact?.phone,
address = CampgroundColumnJson.element(campground.location),
...
links = CampgroundColumnJson.elements(campground.links),
...
management = CampgroundColumnJson.element(campground.management),
contact = CampgroundColumnJson.element(campground.contact),
email = campground.contact?.email,
elevation = campground.location?.elevation,
```

`lastVerified` keeps reading `campground.metadata.stringProperty(LAST_UPDATED_KEY)`. Delete `REGION_KEY`, `COUNTRY_KEY`, `AGENCY_KEY`, `PHONE_KEY`, `URL_KEY`, `LATITUDE_KEY`, `LONGITUDE_KEY`, `EMAIL_KEY`, `ELEVATION_KEY`, the five Campflare key constants, `photoUrlKeys`, `phoneKeys`, `emailKeys`, and the comment block above them. Delete `firstObjectStringProperty` (both overloads) and `doubleProperty`; keep `stringProperty` for `metadata`. Remove the `JsonArray` import if it is now unused.

- [ ] **Step 5: Run the affected tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.poi.*' --tests 'ca.floo.roadtrip.route.PoiRoutesTest' --tests 'ca.floo.roadtrip.route.FeatureCollectionContractTest' --tests 'ca.floo.roadtrip.service.availability.*' --offline -q`
Expected: PASS.

- [ ] **Step 6: Full gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. Then `cd frontend && npm run test` to confirm the drawer tests that read `management.agency`, `links[].title` and `address` still pass against unchanged API shapes.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src
git commit -m "refactor(poi): typed campground row; service reads properties, no vendor key lists"
```

---

### Task 6: Architecture doc

**Files:**
- Modify: `docs/backend-architecture.md` (Models section, after the "ETL upsert candidates" bullet)

- [ ] **Step 1: Add the rule**

```markdown
- **Typed JSONB columns.** When a JSONB column has more than one writer or
  more than one reader, its shape is a `@Serializable` domain type
  (`CampgroundLocation`, `CampgroundLink`, ...). Candidates and rows carry
  the type; the entity repo is the only place that encodes or decodes it.
  Vendor ETLs map upstream keys into the type; the read path never carries
  per-vendor key fallbacks.
```

- [ ] **Step 2: Commit**

```bash
git add docs/backend-architecture.md
git commit -m "docs(architecture): typed JSONB column rule"
```

---

## Rollout

1. Run the Task 4 dry-run query against production before merging.
2. Deploy. Flyway applies `V55` before the app serves; `CampgroundService` never decodes a legacy row.
3. No re-import is needed. The next scheduled import writes canonical keys through the typed candidate.

## Self-review against the spec

- Domain types, codec, Campflare table, migration, out-of-scope items: Tasks 1, 2, 4, 3 and 5 cover each spec section.
- `location.region` for Campflare stays `null` (spec: out of scope). `campflareLocation` reads only `region` and `country` from the upstream object, which Campflare does not ship.
- API wire shape: `element`/`elements` send `{}` and `[]` for absent values (Global Constraints).
- Names match across tasks: `CampgroundColumnJson.{encodeObject,encodeArray,decodeObject,decodeArray,element,elements}`, `campflare{Location,Links,Photos,Management,Contact}`.
