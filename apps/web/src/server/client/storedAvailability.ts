import type { AvailabilityEgressCellDto } from '../contract';
import type { BookableCampsite, StayWindow } from '../service/booking/model';
import type { Clock, StoredAvailability } from '../service/booking/ports';
import { isCampgroundUnknown, type EgressFetch } from './availabilityEgress';

const BOOKABLE_STATUS = 'available';
const STORED_MODE = 'stored';

export class StoredAvailabilityError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly detail: string | undefined,
  ) {
    super(`stored availability read failed: ${JSON.stringify({ status, code, detail })}`);
    this.name = 'StoredAvailabilityError';
  }
}

async function storedCells(egress: EgressFetch | null, campsite: BookableCampsite, window: StayWindow): Promise<AvailabilityEgressCellDto[] | null> {
  if (egress === null) return null;
  const result = await egress({
    mode: STORED_MODE,
    campground: { data_provider: campsite.campground.dataProvider, data_provider_ref: campsite.campground.dataProviderRef },
    start_date: window.startDate,
    end_date: window.endDate,
  });
  if (isCampgroundUnknown(result)) return null;
  if (result.kind === 'error') throw new StoredAvailabilityError(result.status, result.code, result.body?.detail);
  return result.body.cells;
}

const UNREAD_LOG = '[stored-availability] read failed; the hold proceeds without stored evidence';

async function cellsOrUnread(egress: EgressFetch | null, campsite: BookableCampsite, window: StayWindow): Promise<AvailabilityEgressCellDto[] | null> {
  try {
    return await storedCells(egress, campsite, window);
  } catch (error) {
    console.error(UNREAD_LOG, { campsite: campsite.dataProviderRef, window }, error);
    return null;
  }
}

export function storedAvailability(egress: EgressFetch | null, clock: Clock, freshnessMaxAgeMs: number): StoredAvailability {
  return {
    async takenIn(campsite, window) {
      const cells = await cellsOrUnread(egress, campsite, window);
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
