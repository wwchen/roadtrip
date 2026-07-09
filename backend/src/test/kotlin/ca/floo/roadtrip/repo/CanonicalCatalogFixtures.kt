package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class CatalogPoiFixture(
    val poiId: Long,
    val catalogId: Long,
    val poiType: String,
)

private const val FIXTURE_FETCHED_AT = "2026-06-01 00:00:00+00"

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
          campsite_vendor_refs,
          campground_vendor_refs,
          campsites,
          campgrounds,
          vendor_refs,
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
    source: String = "test",
    subcategory: String? = null,
    agency: String? = null,
    region: String? = "BC",
    country: String? = "CA",
    providerRefJson: String? = null,
    propertiesJson: String = """{"test":true}""",
    cadenceOverrideSec: Int? = null,
    geomGeoJson: String = """{"type":"Point","coordinates":[$lon,$lat]}""",
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
    source: String = "test",
    sourceId: String = "campground-$name",
    kind: String = "campground",
    agency: String? = null,
    region: String? = "CA",
    country: String? = "US",
    providerRefJson: String? = null,
    sourcePayloadJson: String = "{}",
): Long {
    val campgroundId =
        fetchOne(
            """
            INSERT INTO campgrounds (
              name, kind, data_source, location, management, source_payload
            ) VALUES (
              ?, ?, ?, jsonb_strip_nulls(jsonb_build_object('region', ?::text, 'country', ?::text)),
              jsonb_strip_nulls(jsonb_build_object('agency', ?::text)),
              ?::jsonb
            )
            RETURNING id
            """.trimIndent(),
            name,
            kind,
            source,
            region,
            country,
            agency,
            sourcePayloadJson,
        )!!
            .get("id", Long::class.java)

    val vendorRefId =
        seedVendorRef(
            vendor = source,
            entityType = "campground",
            externalId = sourceId,
            externalName = name,
            payloadJson = providerRefJson ?: "{}",
        )
    execute(
        "INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id) VALUES (?, ?)",
        campgroundId,
        vendorRefId,
    )
    return campgroundId
}

fun DSLContext.seedCampsite(
    campgroundId: Long,
    vendor: String = "recgov",
    vendorId: String,
    name: String = "Site $vendorId",
    kind: String = "site",
    loopName: String? = null,
    providerRefJson: String? = null,
    sourcePayloadJson: String = "{}",
): Long {
    val campsiteId =
        fetchOne(
            """
            INSERT INTO campsites (
              campground_id, name, kind, data_source, loop_name, source_payload
            ) VALUES (
              ?, ?, ?, ?, ?, ?::jsonb
            )
            RETURNING id
            """.trimIndent(),
            campgroundId,
            name,
            kind,
            vendor,
            loopName,
            sourcePayloadJson,
        )!!
            .get("id", Long::class.java)
    val vendorRefId =
        seedVendorRef(
            vendor = vendor,
            entityType = "campsite",
            externalId = vendorId,
            externalName = name,
            payloadJson = providerRefJson ?: "{}",
        )
    execute(
        "INSERT INTO campsite_vendor_refs (campsite_id, vendor_ref_id) VALUES (?, ?)",
        campsiteId,
        vendorRefId,
    )
    return campsiteId
}

private fun DSLContext.seedVendorRef(
    vendor: String,
    entityType: String,
    externalId: String,
    externalName: String?,
    payloadJson: String,
): Long =
    fetchOne(
        """
        INSERT INTO vendor_refs (
          vendor, entity_type, external_id, external_name, payload, created_at, updated_at
        ) VALUES (
          ?, ?, ?, ?, ?::jsonb, '$FIXTURE_FETCHED_AT'::timestamptz, '$FIXTURE_FETCHED_AT'::timestamptz
        )
        RETURNING id
        """.trimIndent(),
        vendor,
        entityType,
        externalId,
        externalName,
        payloadJson,
    )!!
        .get("id", Long::class.java)

private fun canonicalPoiType(value: String): String =
    when (value) {
        "planet-fitness" -> "planet_fitness_location"
        "supercharger" -> "tesla_supercharger"
        "state-park" -> "campground"
        else -> value
    }
