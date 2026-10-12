// The pure half of the availability check: how the asked ids are split into
// requests, how a response becomes answers, and how answered cards are ordered
// and counted. `useStayCheck` runs it; the list and the head read the results.
import type { BulkAvailabilityResponseDto } from '@/api/generated/api-types';
import type { StayAvailabilityStatus } from '@/api/availability-api';
import type { StayResult } from '@/stores/stayCheckStore';

const MS_PER_SECOND = 1000;

/**
 * The code an entry carrying neither a summary nor an error is recorded under:
 * the backend's own for a failure it could not classify (`AvailabilityProviderError.Unknown`).
 */
const UNCLASSIFIED_ERROR = 'unknown';

/**
 * At most this many requests per check. Each request answers a batch at once, so
 * more requests fill the cards in sooner, one batch at a time; fewer keep a check
 * well inside the endpoint's per-IP limit, which a second check soon after has to
 * fit in too.
 */
export const MAX_REQUESTS_PER_CHECK = 4;

/** The ids split into at most [maxBatches] runs of near-equal size, order kept. */
export function batchesOf(ids: readonly number[], maxBatches: number): number[][] {
  if (ids.length === 0) return [];
  const size = Math.ceil(ids.length / maxBatches);
  const batches: number[][] = [];
  for (let start = 0; start < ids.length; start += size) batches.push(ids.slice(start, start + size));
  return batches;
}

/** One answer per entry: its summary, else its error code. */
export function resultsOf(response: BulkAvailabilityResponseDto): Record<number, StayResult> {
  const results: Record<number, StayResult> = {};
  for (const entry of response.pois) {
    results[entry.poi_id] = entry.summary
      ? { kind: 'summary', summary: entry.summary }
      : { kind: 'error', code: entry.error ?? UNCLASSIFIED_ERROR };
  }
  return results;
}

/**
 * When the response's oldest answer was observed: [now] less the stalest cache
 * age it reports. The backend serves recent observations without asking the
 * provider again, so "checked just now" would be wrong for an answer read from
 * an hour-old poll.
 */
export function observedAtOf(response: BulkAvailabilityResponseDto, now: number): string {
  const stalest = Math.max(0, ...response.pois.map((entry) => entry.summary?.cache?.age_seconds ?? 0));
  return new Date(now - stalest * MS_PER_SECOND).toISOString();
}

/** Where a status sorts once a check is back: bookable first, then the ones that say why not. */
const STATUS_RANK: Record<StayAvailabilityStatus, number> = {
  open: 0,
  few_left: 1,
  full: 2,
  closed_for_season: 3,
  no_data: 4,
};
const ERROR_RANK = Object.keys(STATUS_RANK).length;
const UNCHECKED_RANK = ERROR_RANK + 1;

function rankOf(result: StayResult | undefined): number {
  if (!result) return UNCHECKED_RANK;
  return result.kind === 'summary' ? STATUS_RANK[result.summary.status] : ERROR_RANK;
}

function openSitesOf(result: StayResult | undefined): number {
  return result?.kind === 'summary' ? result.summary.open_sites : 0;
}

/**
 * Cards by how the stay looks: status first, then most open sites, and the
 * order they arrived in otherwise — so a card that was nearest stays ahead of an
 * equal one. Cards nobody asked about (panned in since, or not checkable) go last.
 */
export function rankByStay<C extends { summary: { id: number } }>(
  cards: readonly C[],
  results: Readonly<Record<number, StayResult>>,
): C[] {
  return cards
    .map((card, index) => ({ card, index, result: results[card.summary.id] }))
    .sort(
      (a, b) =>
        rankOf(a.result) - rankOf(b.result) ||
        openSitesOf(b.result) - openSitesOf(a.result) ||
        a.index - b.index,
    )
    .map(({ card }) => card);
}

export interface StayTallies {
  /** Open or few left: somewhere to stay. */
  withSite: number;
  /** Full or closed for the season. */
  full: number;
  /** No data, or the check failed for it. */
  unknown: number;
  /** How many of the asked ids have answered. */
  answered: number;
}

/** The head's counts, over the ids the check asked about. */
export function stayTallies(
  asked: readonly number[],
  results: Readonly<Record<number, StayResult>>,
): StayTallies {
  const tallies: StayTallies = { withSite: 0, full: 0, unknown: 0, answered: 0 };
  for (const id of asked) {
    const result = results[id];
    if (!result) continue;
    tallies.answered += 1;
    if (result.kind === 'error') tallies.unknown += 1;
    else if (result.summary.status === 'open' || result.summary.status === 'few_left') tallies.withSite += 1;
    else if (result.summary.status === 'full' || result.summary.status === 'closed_for_season') tallies.full += 1;
    else tallies.unknown += 1;
  }
  return tallies;
}
