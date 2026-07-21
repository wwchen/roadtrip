package ca.floo.roadtrip.repo

import ca.floo.roadtrip.fixtures.CatalogPoiFixture
import ca.floo.roadtrip.model.domain.provider.DataProvider
import org.jooq.DSLContext

private val defaultTestDataProvider = DataProvider.RECGOV.id

@Suppress("UnusedReceiverParameter")
fun DSLContext.refreshCanonicalCatalogViews() {
    // No-op: materialized views removed in V44.
}

fun DSLContext.cleanCanonicalCatalogFixtures() {
    execute(
        """
        TRUNCATE TABLE
          availability_fetch_call,
          availability_run,
          availability_watch_poller,
          availability_watch_target,
          availability_watch,
          availability_poller,
          availability,
          poi_campgrounds,
          poi_tesla_superchargers,
          poi_planet_fitness_locations,
          campsites,
          campgrounds,
          pois,
          tesla_superchargers,
          planet_fitness_locations
        RESTART IDENTITY CASCADE
        """.trimIndent(),
    )
}

fun DSLContext.seedCatalogPoi(
    sourceId: String,
    name: String,
    lon: Double,
    lat: Double,
    poiType: String = "campground",
    source: String = defaultTestDataProvider,
    subcategory: String? = null,
    agency: String? = null,
    region: String? = "BC",
    country: String? = "CA",
    providerRefJson: String? = null,
    propertiesJson: String = """{"test":true}""",
    cadenceOverrideSec: Int? = null,
    geomGeoJson: String = """{"type":"Point","coordinates":[$lon,$lat]}""",
    bookingProvider: String? = null,
    bookingProviderRef: String? = null,
    refresh: Boolean = true,
): CatalogPoiFixture {
    val canonicalType = canonicalPoiType(poiType)
    val poiId =
        fetchOne(
            """
            INSERT INTO pois (poi_type, geom, cadence_override_sec)
            VALUES (?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?)
            RETURNING id
            """.trimIndent(),
            canonicalType,
            geomGeoJson,
            cadenceOverrideSec,
        )!!
            .get("id", Long::class.java)

    val catalogId =
        when (canonicalType) {
            "campground" -> {
                // Inner refresh suppressed: this function refreshes once at
                // the end, and we don't want the inner seedCampground call to
                // do a redundant one on top.
                val campgroundId =
                    seedCampground(
                        name = name,
                        source = source,
                        sourceId = sourceId,
                        kind = subcategory ?: "campground",
                        agency = agency,
                        region = region,
                        country = country,
                        providerRefJson = providerRefJson,
                        sourcePayloadJson = propertiesJson,
                        bookingProvider = bookingProvider,
                        bookingProviderRef = bookingProviderRef,
                        refresh = false,
                    )
                execute("INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)", poiId, campgroundId)
                campgroundId
            }

            "tesla_supercharger" -> {
                val superchargerId =
                    fetchOne(
                        """
                        INSERT INTO tesla_superchargers (
                          location_slug, common_site_name, site_status, address,
                          region, country, index_payload, detail_payload
                        ) VALUES (
                          ?, ?, 'open', '{}'::jsonb, ?, ?, ?::jsonb, ?::jsonb
                        )
                        RETURNING id
                        """.trimIndent(),
                        sourceId,
                        name,
                        region,
                        country,
                        propertiesJson,
                        providerRefJson ?: propertiesJson,
                    )!!
                        .get("id", Long::class.java)
                execute(
                    "INSERT INTO poi_tesla_superchargers (poi_id, tesla_supercharger_id) VALUES (?, ?)",
                    poiId,
                    superchargerId,
                )
                superchargerId
            }

            "planet_fitness_location" -> {
                val locationId =
                    fetchOne(
                        """
                        INSERT INTO planet_fitness_locations (
                          location_id, name, address, region, country, payload
                        ) VALUES (
                          ?, ?, '{}'::jsonb, ?, ?, ?::jsonb
                        )
                        RETURNING id
                        """.trimIndent(),
                        sourceId,
                        name,
                        region,
                        country,
                        providerRefJson ?: propertiesJson,
                    )!!
                        .get("id", Long::class.java)
                execute(
                    "INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (?, ?)",
                    poiId,
                    locationId,
                )
                locationId
            }

            else -> error("unsupported canonical poi type: $canonicalType")
        }

    return CatalogPoiFixture(poiId = poiId, catalogId = catalogId, poiType = canonicalType)
}

fun DSLContext.seedCampground(
    name: String = "Upper Pines",
    source: String = defaultTestDataProvider,
    sourceId: String = "campground-$name",
    kind: String = "campground",
    agency: String? = null,
    region: String? = "CA",
    country: String? = "US",
    providerRefJson: String? = null,
    sourcePayloadJson: String = "{}",
    bookingProvider: String? = null,
    bookingProviderRef: String? = null,
    refresh: Boolean = true,
): Long =
    fetchOne(
        """
        INSERT INTO campgrounds (
          name, kind, data_provider, data_provider_ref, booking_provider, booking_provider_ref,
          location, management, source_payload
        ) VALUES (
          ?, ?, ?, ?, ?, ?,
          jsonb_strip_nulls(jsonb_build_object('region', ?::text, 'country', ?::text)),
          jsonb_strip_nulls(jsonb_build_object('agency', ?::text)),
          ?::jsonb
        )
        RETURNING id
        """.trimIndent(),
        name,
        kind,
        source,
        sourceId,
        bookingProvider,
        bookingProviderRef,
        region,
        country,
        agency,
        providerRefJson ?: sourcePayloadJson,
    )!!
        .get("id", Long::class.java)

fun DSLContext.seedCampsite(
    campgroundId: Long,
    vendor: String = "recgov",
    vendorId: String,
    name: String = "Site $vendorId",
    kind: String = "site",
    loopName: String? = null,
    reservationUrl: String? = null,
    providerRefJson: String? = null,
    sourcePayloadJson: String = "{}",
    bookingProvider: String? = null,
    bookingProviderRef: String? = null,
    refresh: Boolean = true,
): Long =
    fetchOne(
        """
        INSERT INTO campsites (
          campground_id, name, kind, data_provider, data_provider_ref,
          booking_provider, booking_provider_ref, loop_name, reservation_url, source_payload
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb
        )
        RETURNING id
        """.trimIndent(),
        campgroundId,
        name,
        kind,
        vendor,
        vendorId,
        bookingProvider,
        bookingProviderRef,
        loopName,
        reservationUrl,
        providerRefJson ?: sourcePayloadJson,
    )!!
        .get("id", Long::class.java)

private fun canonicalPoiType(value: String): String =
    when (value) {
        "planet-fitness" -> "planet_fitness_location"
        "supercharger" -> "tesla_supercharger"
        "state-park" -> "campground"
        else -> value
    }
