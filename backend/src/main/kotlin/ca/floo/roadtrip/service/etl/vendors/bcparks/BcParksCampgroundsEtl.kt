package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
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
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraInventoryCategories
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatch
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatchKind
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeafMatcher
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeavesWalk
import ca.floo.roadtrip.service.etl.vendors.aspira.normalize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Merge ETL for BC Parks campgrounds: joins Aspira booking data with BC Parks
 * Strapi metadata to produce campgrounds enriched with Strapi description,
 * photos, contact, and URL. Campsites are handled separately by AspiraCampsitesEtl.
 */
class BcParksCampgroundsEtl(
    override val etlSlug: String = "aspira-bc-campgrounds",
) : CampgroundEtl<BcParksCampgroundsDto> {
    private val log = LoggerFactory.getLogger(javaClass)
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

        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
            )
        val bookableMapIds =
            AspiraBookingCtaRefs.bookableMapIdsByResourceLocationId(dto.inventoryEnvelopes, dto.dictionaryPayload)

        val matcher = AspiraLeafMatcher(indexStrapiRows(dto.strapiRows), nonBookableResLocs)
        val (matches, tally) = matcher.matchBookable(dto.leaves)
        val campgrounds = matches.map { campgroundCandidate(it, host, subcategory, agency, bookableMapIds) }

        log.info(
            "$etlSlug: {} leaves → {} campgrounds " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={})",
            dto.leaves.size,
            campgrounds.size,
            tally.exact,
            tally.fuzzy,
            tally.parent,
            tally.miss,
            tally.skippedContainer,
            tally.skippedNonBookable,
        )
        return campgrounds.asSequence().map { TransformResult.Ok(it) }
    }

    /** Normalized Strapi park name → first row carrying it. */
    private fun indexStrapiRows(rows: List<BcParksStrapiRow>): Map<String, BcParksStrapiRow> {
        val byName = LinkedHashMap<String, BcParksStrapiRow>()
        for (row in rows) {
            val key = normalize(row.name)
            if (key.isNotEmpty()) byName.putIfAbsent(key, row)
        }
        return byName
    }

    private fun campgroundCandidate(
        match: AspiraLeafMatch<BcParksStrapiRow>,
        host: String,
        subcategory: String?,
        agency: String,
        bookableMapIds: Map<Long, Set<Long>>,
    ): CampgroundUpsertCandidate {
        val leaf = match.leaf
        val strapiRow = match.value
        val bookingUrl = "https://$host/"
        val dataRef = DataProviderRef.BcParks(transactionLocationId = leaf.transactionLocationId, mapId = leaf.mapId)
        val bookingCtaRef = AspiraBookingCtaRefs.forLeaf(leaf, bookableMapIds)
        return CampgroundUpsertCandidate(
            dataProviderRef = dataRef,
            bookingProvider = BookingProvider.ASPIRA,
            bookingProviderRef = bookingCtaRef?.let { campgroundBookingRef(leaf, it) },
            name = leaf.name,
            latitude = strapiRow.lat,
            longitude = strapiRow.lon,
            kind = subcategory,
            mediumDescription = strapiRow.description,
            location = CampgroundLocation(strapiRow.lat, strapiRow.lon, region = REGION, country = COUNTRY),
            reservationUrl = bookingUrl,
            links = listOfNotNull(bookingUrl, strapiRow.url?.takeIf { it != bookingUrl }).map { CampgroundLink(it) },
            photos = listOfNotNull(strapiRow.photoUrl?.let(::CampgroundPhoto)),
            management = CampgroundManagement(agency),
            contact = strapiRow.phone?.let { CampgroundContact(phone = it) },
            metadata = metadataPayload(leaf, host, match.kind, bookingCtaRef, strapiRow),
            sourceUrl = bookingUrl,
            sourcePayload = sourcePayload(leaf, match.kind, bookingCtaRef, strapiRow),
        )
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

    private fun metadataPayload(
        leaf: AspiraLeaf,
        host: String,
        matchKind: AspiraLeafMatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("host", host)
            put("transaction_location_id", leaf.transactionLocationId)
            put("map_id", leaf.mapId)
            leaf.resourceLocationId?.let { put("resource_location_id", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind.label)
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
        matchKind: AspiraLeafMatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put("transactionLocationId", leaf.transactionLocationId)
            put("mapId", leaf.mapId)
            leaf.resourceLocationId?.let { put("resourceLocationId", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind.label)
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
