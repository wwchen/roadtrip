package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
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
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiSource
import ca.floo.roadtrip.service.etl.vendors.aspira.normalize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Merge ETL for BC Parks campgrounds: joins Aspira booking data with BC Parks
 * Strapi metadata to produce campgrounds enriched with Strapi description,
 * photos, contact, and URL. Campsites are handled separately by AspiraCampsitesEtl.
 */
class BcParksCampgroundsEtl(
    override val etlSlug: String,
    private val aspiraTenant: String,
    private val geometry: GeometryPolicy,
) : CampgroundEtl<BcParksCampgroundsDto> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<BcParksCampgroundsDto>> =
        sequence {
            val slugs = inputs.dataSourceSlugs()
            val mapsSlug = slugs.first { it.contains(MAPS_INPUT_MARKER) }
            val strapiSlug = geometry.sources.single().input
            val inventorySlug = slugs.first { it.contains(INVENTORY_INPUT_MARKER) }
            val dictionarySlug = slugs.firstOrNull { it.contains(DICTIONARIES_INPUT_MARKER) }

            val mapsArray = inputs.envelope(mapsSlug).payload.jsonArray
            val leaves = AspiraLeavesWalk.walk(mapsArray)
            val strapiEnvelopes = inputs.envelopes(strapiSlug)
            val inventoryEnvelopes = inputs.envelopes(inventorySlug)
            val dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject }

            val strapiRows = BcParksStrapiSource(strapiEnvelopes).rows()

            val dto =
                BcParksCampgroundsDto(
                    leaves = leaves,
                    strapiRows = strapiRows,
                    inventoryEnvelopes = inventoryEnvelopes,
                    dictionaryPayload = dictionaryPayload,
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
            parentName = leaf.parentName,
            latitude = strapiRow.lat,
            longitude = strapiRow.lon,
            kind = subcategory,
            mediumDescription = strapiRow.description,
            location = CampgroundLocation(strapiRow.lat, strapiRow.lon, region = REGION, country = COUNTRY),
            reservationUrl = bookingUrl,
            links = listOfNotNull(bookingUrl, strapiRow.url?.takeIf { it != bookingUrl }).map { CampgroundLink(it) },
            photos = listOfNotNull(strapiRow.photoUrl?.let(::CatalogPhoto)),
            management = CampgroundManagement(agency),
            contact = strapiRow.phone?.let { CampgroundContact(phone = it) },
            sourceUrl = bookingUrl,
            sourcePayload = sourcePayload(leaf, match.kind, strapiRow),
        )
    }

    // ---- Campground helpers ---------------------------------------------------

    private fun campgroundBookingRef(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): String =
        BookingProviderRef
            .Aspira(
                tenant = aspiraTenant,
                transactionLocationId = leaf.transactionLocationId,
                mapId = bookingCtaRef.mapId,
                resourceLocationId = bookingCtaRef.resourceLocationId,
            ).serialize()

    private fun sourcePayload(
        leaf: AspiraLeaf,
        matchKind: AspiraLeafMatchKind,
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
        }

    private companion object {
        const val REGION = "BC"
        const val COUNTRY = "CA"

        const val MAPS_INPUT_MARKER = "maps"
        const val INVENTORY_INPUT_MARKER = "inventory"
        const val DICTIONARIES_INPUT_MARKER = "dictionaries"
    }
}
