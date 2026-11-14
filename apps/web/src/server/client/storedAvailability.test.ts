import { expect, test, vi } from 'vitest';
import type { AvailabilityStatusDto } from '../contract';
import type { EgressFetch } from './availabilityEgress';
import { storedAvailability } from './storedAvailability';
import type { BookableCampsite } from '../service/booking/model';

const NOW = Date.parse('2026-12-01T12:00:00Z');
const FRESHNESS_MS = 300_000;
const WINDOW = { startDate: '2026-12-10', endDate: '2026-12-11' };
const CLOCK = { now: () => NOW };
const CAMPSITE: BookableCampsite = {
  id: 55n,
  dataProvider: 'campflare',
  dataProviderRef: 'site-55',
  bookingProvider: 'recgov',
  bookingProviderRef: '300',
  bookingAliases: [],
  campground: { dataProvider: 'campflare', dataProviderRef: 'cg-1', bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] },
};

function cell(status: AvailabilityStatusDto, ageMs: number, date = '2026-12-10', ref = 'site-55') {
  return { campsite: { data_provider: 'campflare', data_provider_ref: ref }, date, status, observed_at: new Date(NOW - ageMs).toISOString() };
}

const cellsOf = (cells: ReturnType<typeof cell>[]): EgressFetch =>
  vi.fn(async () => ({ kind: 'cells' as const, body: { start_date: WINDOW.startDate, end_date: WINDOW.endDate, campsites: [], cells, from_cache: false } }));

test('a night seen taken within the freshness window is evidence', async () => {
  const egress = cellsOf([cell('reserved', 4 * 60_000)]);
  expect(await storedAvailability(egress, CLOCK, FRESHNESS_MS).takenIn(CAMPSITE, WINDOW)).toBe(true);
  expect(egress).toHaveBeenCalledWith({
    mode: 'stored',
    campground: { data_provider: 'campflare', data_provider_ref: 'cg-1' },
    start_date: '2026-12-10',
    end_date: '2026-12-11',
  });
});

test('stale, available, other-site and checkout-night cells are not evidence', async () => {
  const egress = cellsOf([
    cell('reserved', 6 * 60_000),
    cell('available', 0),
    cell('reserved', 0, '2026-12-10', 'site-56'),
    cell('reserved', 0, '2026-12-11'),
  ]);
  expect(await storedAvailability(egress, CLOCK, FRESHNESS_MS).takenIn(CAMPSITE, WINDOW)).toBe(false);
});

test('a failed stored read is not evidence', async () => {
  const errored: EgressFetch = async () => ({ kind: 'error', status: 503, code: 'x', body: null });
  const rejected: EgressFetch = async () => {
    throw new Error('boom');
  };
  expect(await storedAvailability(errored, CLOCK, FRESHNESS_MS).takenIn(CAMPSITE, WINDOW)).toBe(false);
  expect(await storedAvailability(rejected, CLOCK, FRESHNESS_MS).takenIn(CAMPSITE, WINDOW)).toBe(false);
});

test('no egress configured is not evidence', async () => {
  expect(await storedAvailability(null, CLOCK, FRESHNESS_MS).takenIn(CAMPSITE, WINDOW)).toBe(false);
});
