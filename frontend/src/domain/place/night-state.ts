import type { AvailabilityDay } from '@/api/availability-api';
import { normalizeAvailabilityStatus, type AvailabilityStatus } from '@/lib/availability-status';

export type NightState = 'open' | 'full' | 'first_come' | 'closed' | 'past' | 'unknown';

export interface Night {
  state: NightState;
  openIds: readonly number[];
}

const AVAILABLE: AvailabilityStatus = 'available';

const UNREAD: AvailabilityStatus = 'unknown';

const NIGHT_STATE = {
  available: 'open',
  reserved: 'full',
  first_come: 'first_come',
  closed: 'closed',
  past: 'past',
  unknown: 'unknown',
} satisfies Record<AvailabilityStatus, NightState>;

const CELL_PERMISSIVENESS = {
  available: 5,
  first_come: 4,
  reserved: 3,
  closed: 2,
  past: 1,
  unknown: 0,
} satisfies Record<AvailabilityStatus, number>;

const NEEDS_MOST_SITES_READ = {
  open: false,
  first_come: false,
  full: true,
  closed: true,
  past: true,
  unknown: false,
} satisfies Record<NightState, boolean>;

const OBSERVED_SHARE_TO_CALL_A_NIGHT_SHUT = 0.5;

const UNKNOWN_NIGHT: Night = { state: 'unknown', openIds: [] };

function cellStatuses(day: AvailabilityDay): AvailabilityStatus[] {
  return Object.values(day.cells).map((cell) => normalizeAvailabilityStatus(cell.status));
}

function openIdsOf(day: AvailabilityDay): number[] {
  return Object.entries(day.cells)
    .filter(([, cell]) => cell.status === AVAILABLE)
    .map(([id]) => Number(id))
    .filter(Number.isInteger)
    .sort((a, b) => a - b);
}

function mostPermissive(statuses: readonly AvailabilityStatus[]): AvailabilityStatus {
  return statuses.reduce(
    (best, status) => (CELL_PERMISSIVENESS[status] > CELL_PERMISSIVENESS[best] ? status : best),
    UNREAD,
  );
}

function mostSitesRead(statuses: readonly AvailabilityStatus[]): boolean {
  const read = statuses.filter((status) => status !== UNREAD).length;
  return read > statuses.length * OBSERVED_SHARE_TO_CALL_A_NIGHT_SHUT;
}

export function nightOf(day: AvailabilityDay | undefined): Night {
  if (day === undefined) return UNKNOWN_NIGHT;

  const statuses = cellStatuses(day);
  if (statuses.length === 0) return UNKNOWN_NIGHT;

  const state = NIGHT_STATE[mostPermissive(statuses)];
  if (state === 'unknown') return UNKNOWN_NIGHT;
  if (NEEDS_MOST_SITES_READ[state] && !mostSitesRead(statuses)) return UNKNOWN_NIGHT;

  return state === 'open' ? { state, openIds: openIdsOf(day) } : { state, openIds: [] };
}
