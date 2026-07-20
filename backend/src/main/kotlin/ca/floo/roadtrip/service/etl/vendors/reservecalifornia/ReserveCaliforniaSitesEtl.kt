package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx

class ReserveCaliforniaSitesEtl(
    override val etlSlug: String = "reservecalifornia-campsites",
) : CampsiteEtl<ReserveCaliforniaCatalog> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<ReserveCaliforniaCatalog>> =
        sequence {
            val catalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)
            if (catalog.grids.values.none { it.units.isNotEmpty() }) {
                yield(ParseResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia grid payloads with units parsed")))
            } else {
                yield(ParseResult.Ok(catalog))
            }
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampsiteUpsertCandidate>> =
        sequence {
            for (grid in dto.grids.values) {
                val facility = dto.facilities[grid.facilityId]
                if (facility?.isStandardBookable == false) {
                    yield(TransformResult.Bad(grid.facilityId.toString(), listOf("facility is not standard bookable")))
                    continue
                }
                val placeId = grid.placeId ?: facility?.placeId
                if (placeId == null) {
                    yield(TransformResult.Bad(grid.facilityId.toString(), listOf("missing parent place id")))
                    continue
                }
                val place = dto.places[placeId]
                if (place == null) {
                    yield(TransformResult.Bad(grid.facilityId.toString(), listOf("parent place $placeId not parsed")))
                    continue
                }
                if (grid.facilityId !in place.facilityIds) {
                    yield(TransformResult.Bad(grid.facilityId.toString(), listOf("facility not linked from parent place $placeId")))
                    continue
                }
                val kind = place.unitTypeByFacilityId[grid.facilityId] ?: DEFAULT_CAMPSITE_KIND
                for (unit in grid.units) {
                    yield(
                        TransformResult.Ok(
                            CampsiteUpsertCandidate(
                                dataProviderRef = DataProviderRef.ReserveCalifornia(id = unit.unitId.toString()),
                                bookingProvider = BookingProvider.RESERVECALIFORNIA,
                                bookingProviderRef = unit.unitId.toString(),
                                parentDataProviderRef = DataProviderRef.ReserveCalifornia(id = placeId.toString()),
                                name = unit.name?.takeIf { it.isNotBlank() } ?: unit.unitId.toString(),
                                kind = kind,
                                loopName = grid.facilityName ?: facility?.name,
                                reservationUrl = reserveCaliforniaParkUrl(placeId),
                                kindListed = kind,
                                sourcePayload = campsiteSourcePayload(unit, grid, placeId, facility),
                            ),
                        ),
                    )
                }
            }
        }
}
