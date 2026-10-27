import type { AvailabilityDay, AvailabilityCell } from '@/api/availability-api';

export type NightState = 'open' | 'full' | 'unknown';

export interface Night {
  state: NightState;
  openIds: readonly number[];
}

const AVAILABLE = 'available';
const RESERVED = 'reserved';
const CLOSED = 'closed';

const UNKNOWN_NIGHT: Night = { state: 'unknown', openIds: [] };

export function nightOf(day: AvailabilityDay | undefined): Night {
  const entries: [string, AvailabilityCell][] = day ? Object.entries(day.cells) : [];
  if (entries.length === 0) return UNKNOWN_NIGHT;

  const openIds = entries
    .filter(([, cell]) => cell.status === AVAILABLE)
    .map(([id]) => Number(id))
    .filter(Number.isInteger)
    .sort((a, b) => a - b);
  if (openIds.length > 0) return { state: 'open', openIds };

  const everyCellTaken = entries.every(([, cell]) => cell.status === RESERVED || cell.status === CLOSED);
  const someReserved = entries.some(([, cell]) => cell.status === RESERVED);
  return everyCellTaken && someReserved ? { state: 'full', openIds } : UNKNOWN_NIGHT;
}
