import { expect, test } from 'vitest';
import type { AvailabilityEgressCellDto, AvailabilityStatusDto } from '../../contract';
import { providerRefKey, type CampsiteKey } from '../../repo/availabilityReadRepo';
import { fittingGroup, staySummary } from './staySummary';

const PROVIDER = 'campflare';
const START = '2026-12-01';
const END = '2026-12-03';
const NIGHTS = [START, '2026-12-02'];
const AT = '2026-11-07T12:00:00Z';

const site = (ref: string, maxPeople: number | null = null): CampsiteKey => ({ id: BigInt(ref.length), key: providerRefKey(PROVIDER, ref), maxPeople });
const cell = (ref: string, date: string, status: AvailabilityStatusDto, observedAt = AT): AvailabilityEgressCellDto => ({
  campsite: { data_provider: PROVIDER, data_provider_ref: ref },
  date,
  status,
  observed_at: observedAt,
});
const every = (ref: string, status: AvailabilityStatusDto) => NIGHTS.map((night) => cell(ref, night, status));

test('a campsite counts as open only when every night is available', () => {
  const summary = staySummary([site('a'), site('bb')], [...every('a', 'available'), cell('bb', START, 'available'), cell('bb', NIGHTS[1]!, 'reserved')], START, END, true);
  expect(summary).toEqual({ status: 'few_left', open_sites: 1, checked_sites: 2, observed_at: AT, from_cache: true });
});

test('more than two open campsites is open', () => {
  const sites = ['a', 'bb', 'ccc'];
  expect(staySummary(sites.map((ref) => site(ref)), sites.flatMap((ref) => every(ref, 'available')), START, END, false).status).toBe('open');
});

test('nothing open: closed on some night everywhere is closed_for_season, else full', () => {
  expect(staySummary([site('a')], [cell('a', START, 'reserved'), cell('a', NIGHTS[1]!, 'closed')], START, END, false).status).toBe('closed_for_season');
  expect(staySummary([site('a')], [cell('a', START, 'first_come'), cell('a', NIGHTS[1]!, 'unknown')], START, END, false).status).toBe('full');
});

test('a campsite with unanswered nights and nothing against it leaves no data', () => {
  const summary = staySummary([site('a'), site('bb')], [...every('a', 'reserved'), cell('bb', START, 'available')], START, END, false);
  expect(summary.status).toBe('no_data');
  expect(staySummary([site('a')], [], START, END, false)).toEqual({ status: 'no_data', open_sites: 0, checked_sites: 1, from_cache: false });
});

test('the latest observation of a night wins and the oldest dates the answer', () => {
  const cells = [cell('a', START, 'reserved', '2026-11-07T10:00:00.5Z'), cell('a', START, 'available', '2026-11-07T11:00:00Z'), cell('a', NIGHTS[1]!, 'available', '2026-11-07T10:00:00Z')];
  expect(staySummary([site('a')], cells, START, END, false)).toMatchObject({ open_sites: 1, observed_at: '2026-11-07T10:00:00Z' });
});

test('no campsites is no data with nothing checked', () => {
  expect(staySummary([], [], START, END, true)).toEqual({ status: 'no_data', open_sites: 0, checked_sites: 0, from_cache: false });
});

test('fittingGroup keeps campsites that sleep the group, and those with no stated capacity', () => {
  const sites = [site('a', 4), site('bb', 8), site('ccc', null)];
  expect(fittingGroup(sites, 6).map((s) => s.key)).toEqual([sites[1]!.key, sites[2]!.key]);
  expect(fittingGroup(sites, null)).toHaveLength(3);
});
