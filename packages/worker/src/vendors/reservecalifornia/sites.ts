import { BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { campsiteKindReserveCalifornia } from '../../etl/campsite-kinds.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import type { ReserveCaliforniaCatalog } from './catalog.js';
import { campsiteSourcePayload, parseCatalog, reserveCaliforniaParkUrl } from './campgrounds.js';
import { isStandardBookable } from './facility.js';

const NO_UNITS_SUFFIX = 'no ReserveCalifornia grid payloads with units parsed';
const NOT_STANDARD_BOOKABLE = 'facility is not standard bookable';
const MISSING_PARENT_PLACE_ID = 'missing parent place id';

export class ReserveCaliforniaSitesEtl implements SourceEtl<ReserveCaliforniaCatalog, CampsiteUpsertCandidate> {
  constructor(readonly etlSlug: string) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<ReserveCaliforniaCatalog>> {
    const catalog = parseCatalog(inputs.soleEnvelopes(), this.etlSlug);
    const hasUnits = [...catalog.grids.values()].some((grid) => grid.units.length > 0);
    yield hasUnits
      ? { ok: true, dto: catalog }
      : { ok: false, errors: [`${this.etlSlug}: ${NO_UNITS_SUFFIX}`] };
  }

  *transform(dto: ReserveCaliforniaCatalog): Iterable<TransformResult<CampsiteUpsertCandidate>> {
    for (const grid of dto.grids.values()) {
      const facility = dto.facilities.get(grid.facilityId);
      const sourceId = grid.facilityId.toString();
      if (facility !== undefined && !isStandardBookable(facility)) {
        yield { ok: false, sourceId, errors: [NOT_STANDARD_BOOKABLE] };
        continue;
      }
      const placeId = grid.placeId ?? facility?.placeId ?? null;
      if (placeId === null) {
        yield { ok: false, sourceId, errors: [MISSING_PARENT_PLACE_ID] };
        continue;
      }
      const place = dto.places.get(placeId);
      if (place === undefined) {
        yield { ok: false, sourceId, errors: [`parent place ${placeId} not parsed`] };
        continue;
      }
      if (!place.facilityIds.includes(grid.facilityId)) {
        yield { ok: false, sourceId, errors: [`facility not linked from parent place ${placeId}`] };
        continue;
      }
      const unitType = place.unitTypeByFacilityId.get(grid.facilityId) ?? null;
      const siteKind = campsiteKindReserveCalifornia(unitType);
      for (const unit of grid.units) {
        yield {
          ok: true,
          record: {
            dataProviderRef: DataProviderRef.reserveCalifornia(unit.unitId.toString()),
            bookingProvider: BookingProvider.RESERVECALIFORNIA,
            bookingProviderRef: unit.unitId.toString(),
            parentDataProviderRef: DataProviderRef.reserveCalifornia(placeId.toString()),
            name: nonBlank(unit.name) ?? unit.unitId.toString(),
            kind: siteKind.kind,
            loopName: grid.facilityName ?? facility?.name ?? null,
            reservationUrl: reserveCaliforniaParkUrl(placeId),
            kindListed: unitType,
            electricHookups: siteKind.electric,
            sourcePayload: campsiteSourcePayload(unit, grid, placeId, facility),
          },
        };
      }
    }
  }
}
