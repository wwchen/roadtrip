import type { AvailabilityDay } from '@/api/availability-api';
import { normalizeAvailabilityStatus, type AvailabilityStatus } from '@/lib/availability-status';

export type NightState = 'open' | 'full' | 'first_come' | 'closed' | 'past' | 'unknown';

export interface Night {
  state: NightState;
  openIds: readonly number[];
}

const AVAILABLE: AvailabilityStatus = 'available';

const NIGHT_STATE = {
  available: 'open',
  reserved: 'full',
  first_come: 'first_come',
  closed: 'closed',
  past: 'past',
  unknown: 'unknown',
} satisfies Record<AvailabilityStatus, NightState>;

const UNKNOWN_NIGHT: Night = { state: 'unknown', openIds: [] };

function openIdsOf(day: AvailabilityDay): number[] {
  return Object.entries(day.cells)
    .filter(([, cell]) => cell.status === AVAILABLE)
    .map(([id]) => Number(id))
    .filter(Number.isInteger)
    .sort((a, b) => a - b);
}

export function nightOf(day: AvailabilityDay | undefined): Night {
  if (day === undefined || Object.keys(day.cells).length === 0) return UNKNOWN_NIGHT;

  const state = NIGHT_STATE[normalizeAvailabilityStatus(day.status)];
  if (state !== 'open') return { state, openIds: [] };

  const openIds = openIdsOf(day);
  return openIds.length > 0 ? { state, openIds } : UNKNOWN_NIGHT;
}
