package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.models.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.models.etl.CampsiteEtlOutput
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx

class ReserveCaliforniaSitesEtl(
    override val etlSlug: String = "california-state-park-sites",
) : SourceEtl<ReserveCaliforniaCatalog, CampsiteEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): ReserveCaliforniaCatalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)

    override fun validate(dto: ReserveCaliforniaCatalog): ValidationResult<ReserveCaliforniaCatalog> =
        if (dto.grids.values.none { it.units.isNotEmpty() }) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia grid payloads with units parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): CampsiteEtlOutput =
        CampsiteEtlOutput(
            campsites =
                dto.grids.values.flatMap { grid ->
                    val facility = dto.facilities[grid.facilityId]
                    if (facility?.isStandardBookable == false) return@flatMap emptyList()
                    val placeId = grid.placeId ?: facility?.placeId ?: return@flatMap emptyList()
                    val place = dto.places[placeId] ?: return@flatMap emptyList()
                    if (grid.facilityId !in place.facilityIds) return@flatMap emptyList()
                    val kind = place.unitTypeByFacilityId[grid.facilityId] ?: DEFAULT_CAMPSITE_KIND
                    grid.units.map { unit ->
                        CampsiteUpsertCandidate(
                            vendor = RESERVECALIFORNIA_VENDOR,
                            vendorRefId = unit.unitId.toString(),
                            parentVendor = PARENT_CAMPGROUND_VENDOR,
                            parentVendorRefId = "$CAMPGROUND_REF_PREFIX$placeId",
                            name = unit.name?.takeIf { it.isNotBlank() } ?: unit.unitId.toString(),
                            kind = kind,
                            loopName = grid.facilityName ?: facility?.name,
                            reservationUrl = reserveCaliforniaParkUrl(placeId),
                            kindListed = kind,
                            sourcePayload = campsiteSourcePayload(unit, grid, placeId, facility),
                            vendorRefPayload = campsiteProviderRefPayload(unit, grid, placeId),
                        )
                    }
                },
        )
}
