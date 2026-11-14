import type { AvailabilityEgressCellDto } from '../contract';
import type { BookableCampsite, StayWindow } from '../service/booking/model';
import type { Clock, StoredAvailability } from '../service/booking/ports';
import type { EgressFetch } from './availabilityEgress';

const BOOKABLE_STATUS = 'available';
const STORED_MODE = 'stored';

async function storedCells(egress: EgressFetch | null, campsite: BookableCampsite, window: StayWindow): Promise<AvailabilityEgressCellDto[] | null> {
  if (egress === null) return null;
  const result = await egress({
    mode: STORED_MODE,
    campground: { data_provider: campsite.campground.dataProvider, data_provider_ref: campsite.campground.dataProviderRef },
    start_date: window.startDate,
    end_date: window.endDate,
  }).catch(() => null);
  return result !== null && result.kind === 'cells' ? result.body.cells : null;
}

export function storedAvailability(egress: EgressFetch | null, clock: Clock, freshnessMaxAgeMs: number): StoredAvailability {
  return {
    async takenIn(campsite, window) {
      const cells = await storedCells(egress, campsite, window);
      if (cells === null) return false;
      const freshAfter = clock.now() - freshnessMaxAgeMs;
      return cells.some(
        (cell) =>
          cell.campsite.data_provider === campsite.dataProvider &&
          cell.campsite.data_provider_ref === campsite.dataProviderRef &&
          cell.date >= window.startDate &&
          cell.date < window.endDate &&
          cell.status !== BOOKABLE_STATUS &&
          Date.parse(cell.observed_at) > freshAfter,
      );
    },
  };
}
