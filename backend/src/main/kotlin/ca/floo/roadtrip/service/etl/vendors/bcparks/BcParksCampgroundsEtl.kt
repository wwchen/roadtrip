package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraBookingCtaRef
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraBookingCtaRefs
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraGeometryMatcher
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraInventoryCategories
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeavesWalk
import ca.floo.roadtrip.service.etl.vendors.aspira.MatchKind
import ca.floo.roadtrip.service.etl.vendors.aspira.normalize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Merge ETL for BC Parks campgrounds: joins Aspira booking data with BC Parks
 * Strapi metadata to produce campgrounds enriched with Strapi description,
 * photos, contact, and URL. Campsites are handled separately by AspiraCampsitesEtl.
 */
class BcParksCampgroundsEtl(
    override val etlSlug: String = "aspira-bc-campgrounds",
) : CampgroundEtl<BcParksCampgroundsDto> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<BcParksCampgroundsDto>> =
        sequence {
            val slugs = inputs.dataSourceSlugs()
            val mapsSlug = slugs.first { it.contains("maps") }
            val strapiSlug = slugs.first { it.contains("bcparks") || it.contains("strapi") }
            val inventorySlug = slugs.first { it.contains("inventory") }
            val dictionarySlug = slugs.firstOrNull { it.contains("dictionaries") }

            val mapsArray = inputs.envelope(mapsSlug).payload.jsonArray
            val leaves = AspiraLeavesWalk.walk(mapsArray)
            val strapiEnvelopes = inputs.envelopes(strapiSlug)
            val inventoryEnvelopes = inputs.envelopes(inventorySlug)
            val dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject }

            val strapiRows = parseStrapiRows(strapiEnvelopes)

            val dto =
                BcParksCampgroundsDto(
                    leaves = leaves,
                    strapiRows = strapiRows,
                    strapiEnvelopes = strapiEnvelopes,
                    inventoryEnvelopes = inventoryEnvelopes,
                    dictionaryPayload = dictionaryPayload,
                    mapsArray = mapsArray,
                )
            val errs = mutableListOf<String>()
            if (dto.leaves.isEmpty()) errs += "no Aspira leaves from /api/maps"
            if (dto.strapiRows.isEmpty()) errs += "no BC Parks Strapi rows parsed"
            if (dto.inventoryEnvelopes.isEmpty()) errs += "no inventory envelopes"
            if (errs.isEmpty()) {
                yield(ParseResult.Ok(dto))
            } else {
                yield(ParseResult.Bad(null, errs))
            }
        }

    override fun transform(
        dto: BcParksCampgroundsDto,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampgroundUpsertCandidate>> {
        val host = ctx.argFor(etlSlug, "host") ?: error("$etlSlug: missing args.host")
        val subcategory = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)

        return transformCampgrounds(dto, host, subcategory, agency)
            .asSequence()
            .map { TransformResult.Ok(it) }
    }

    private fun transformCampgrounds(
        dto: BcParksCampgroundsDto,
        host: String,
        subcategory: String?,
        agency: String,
    ): List<CampgroundUpsertCandidate> {
        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
            )
        val bookableMapIds =
            AspiraBookingCtaRefs.bookableMapIdsByResourceLocationId(dto.inventoryEnvelopes, dto.dictionaryPayload)

        val byName = LinkedHashMap<String, BcParksStrapiRow>()
        for (row in dto.strapiRows) {
            val key = normalize(row.name)
            if (key.isNotEmpty()) byName.putIfAbsent(key, row)
        }

        // The parent-name fallback is always on here, unlike AspiraCampgroundsEtl
        // where the registry's `parent_name_fallback` gates it: 4 real BC pins
        // (all under Wells Gray) depend on it and the BC row declares no flag.
        val matcher =
            AspiraGeometryMatcher(
                etlSlug = etlSlug,
                byName = byName,
                nonBookableResourceLocationIds = nonBookableResLocs,
                parentNameFallback = true,
            )
        return matcher.matchAll(dto.leaves).matches.map { (leaf, strapiRow, matchKind) ->
            val dataRef = DataProviderRef.BcParks(transactionLocationId = leaf.transactionLocationId, mapId = leaf.mapId)
            val bookingCtaRef = AspiraBookingCtaRefs.forLeaf(leaf, bookableMapIds)
            CampgroundUpsertCandidate(
                dataProviderRef = dataRef,
                bookingProvider = BookingProvider.ASPIRA,
                bookingProviderRef = bookingCtaRef?.let { campgroundBookingRef(leaf, it) },
                name = leaf.name,
                latitude = strapiRow.lat,
                longitude = strapiRow.lon,
                kind = subcategory,
                mediumDescription = strapiRow.description,
                location = locationPayload(strapiRow.lat, strapiRow.lon),
                reservationUrl = "https://$host/",
                links = linksPayload("https://$host/", strapiRow.url),
                photos = strapiRow.photoUrl?.let(::photoPayload),
                management = managementPayload(agency),
                contact = strapiRow.phone?.let(::contactPayload),
                metadata = metadataPayload(leaf, host, matchKind, bookingCtaRef, strapiRow),
                sourceUrl = "https://$host/",
                sourcePayload = sourcePayload(leaf, matchKind, bookingCtaRef, strapiRow),
            )
        }
    }

    // ---- Campground helpers ---------------------------------------------------

    private fun campgroundBookingRef(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): String =
        BookingProviderRef
            .Aspira(
                tenant = ASPIRA_TENANT,
                transactionLocationId = leaf.transactionLocationId,
                mapId = bookingCtaRef.mapId,
                resourceLocationId = bookingCtaRef.resourceLocationId,
            ).serialize()

    private fun locationPayload(
        latitude: Double,
        longitude: Double,
    ): JsonObject =
        buildJsonObject {
            put("latitude", latitude)
            put("longitude", longitude)
            put("region", REGION)
            put("country", COUNTRY)
        }

    private fun linksPayload(
        bookingUrl: String,
        infoUrl: String?,
    ): JsonArray =
        buildJsonArray {
            add(buildJsonObject { put("url", bookingUrl) })
            if (infoUrl != null && infoUrl != bookingUrl) {
                add(buildJsonObject { put("url", infoUrl) })
            }
        }

    private fun photoPayload(url: String): JsonArray =
        buildJsonArray {
            add(buildJsonObject { put("url", url) })
        }

    private fun managementPayload(agency: String): JsonObject = buildJsonObject { put("agency", agency) }

    private fun contactPayload(phone: String): JsonObject = buildJsonObject { put("phone", phone) }

    private fun metadataPayload(
        leaf: AspiraLeaf,
        host: String,
        matchKind: MatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("host", host)
            put("transaction_location_id", leaf.transactionLocationId)
            put("map_id", leaf.mapId)
            leaf.resourceLocationId?.let { put("resource_location_id", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind.wireValue)
            strapiRow.orcs?.let { put("strapi_orcs", it) }
            bookingCtaRef?.let {
                put(
                    "booking_cta_provider_ref",
                    buildJsonObject {
                        put("transactionLocationId", leaf.transactionLocationId)
                        put("mapId", it.mapId)
                        put("resourceLocationId", it.resourceLocationId)
                    },
                )
            }
        }

    private fun sourcePayload(
        leaf: AspiraLeaf,
        matchKind: MatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put("transactionLocationId", leaf.transactionLocationId)
            put("mapId", leaf.mapId)
            leaf.resourceLocationId?.let { put("resourceLocationId", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind.wireValue)
            strapiRow.orcs?.let { put("strapi_orcs", it) }
            strapiRow.url?.let { put("strapi_url", it) }
            bookingCtaRef?.let {
                put(
                    "booking_cta_provider_ref",
                    buildJsonObject {
                        put("transactionLocationId", leaf.transactionLocationId)
                        put("mapId", it.mapId)
                        put("resourceLocationId", it.resourceLocationId)
                    },
                )
            }
        }

    // ---- Strapi parsing -------------------------------------------------------

    private fun parseStrapiRows(envelopes: List<Envelope>): List<BcParksStrapiRow> {
        val rows = mutableListOf<BcParksStrapiRow>()
        for (env in envelopes) {
            val data = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (row in data) {
                val o = row.jsonObject
                val name = o["protectedAreaName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val lat = o["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lon = o["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val orcs = o["orcs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val description =
                    o["description"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                val phone =
                    o["parkContact"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                val photoUrl = extractPhotoUrl(o["parkPhotos"] as? JsonArray)
                rows +=
                    BcParksStrapiRow(
                        name = name,
                        lat = lat,
                        lon = lon,
                        orcs = orcs,
                        url = url,
                        description = description,
                        phone = phone,
                        photoUrl = photoUrl,
                    )
            }
        }
        return rows
    }

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

    private companion object {
        const val ASPIRA_TENANT = "bc"
        const val REGION = "BC"
        const val COUNTRY = "CA"
    }
}
