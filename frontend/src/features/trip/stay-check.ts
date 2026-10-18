// The pure half of the availability check: how a campground's answer becomes
// its result, and how answered cards are ordered and counted. `useStayCheck`
// runs the check; the list and the head read the results.
import { isAnswered, type StayAnswer, type StayAvailabilityStatus } from '@/api/availability-api';
import { entryOf } from '@/lib/entry-of';
import type { StayResult } from '@/stores/stayCheckStore';

/**
 * The longest a check's stream may go without sending anything — an answer, or
 * the keep-alive the backend writes while it has none, at least every 20 s
 * (`BulkAvailabilityConfig.MAX_STREAM_HEARTBEAT_SECONDS`). A stream this quiet
 * has lost its connection; without a limit the check, and its button, would stay
 * running until the page was reloaded. How long the providers take is not this
 * limit's business: the backend bounds that itself (`availability.bulk.per-poi-timeout`
 * and `stream-timeout`) and answers `timed_out` for whoever ran out.
 */
export const CHECK_IDLE_TIMEOUT_MS = 60_000;

/**
 * The age a re-check asks for: the youngest the server will serve. It matches the
 * server's floor (`availability.bulk.fresh-tolerance`), short rather than zero so a
 * double-click does not ask every provider twice. The server clamps what it is sent,
 * so a floor configured higher answers at its own, and this need not track it. A first
 * check names no age and gets the endpoint's usual tolerance.
 */
export const RECHECK_MAX_AGE_SECONDS = 60;

/**
 * A campground's answer: its summary when it answered, else the outcome the server
 * classified it as. Anything that is not an answered stay lands on the error arm,
 * an outcome this build has never heard of included, so a newer server adding one
 * degrades to "couldn't check" rather than breaking the list.
 */
export function resultOf(answer: StayAnswer): StayResult {
  if (isAnswered(answer)) return { kind: 'summary', summary: answer.stay };
  return {
    kind: 'error',
    outcome: answer.outcome,
    reason: answer.reason ?? null,
    retryable: answer.retryable ?? null,
  };
}

/** How a result reads at a glance — the colour its line and its card's dot take. */
export type StayTone = 'open' | 'few' | 'full' | 'unknown';

/** Closed for the season reads as full: there is nowhere to stay either way, and the line says which. */
const STATUS_TONE: Record<StayAvailabilityStatus, StayTone> = {
  open: 'open',
  few_left: 'few',
  full: 'full',
  closed_for_season: 'full',
  no_data: 'unknown',
};

/** A summary's tone by its status; a failed answer, or a status it does not know, could not tell. */
export function stayToneOf(result: StayResult): StayTone {
  if (result.kind !== 'summary') return 'unknown';
  return entryOf(STATUS_TONE, result.summary.status) ?? 'unknown';
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
  if (result.kind !== 'summary') return ERROR_RANK;
  return entryOf(STATUS_RANK, result.summary.status) ?? ERROR_RANK;
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
  /** Booked up. */
  full: number;
  /** Shut for the season — a different message from booked up, so counted apart. */
  closed: number;
  /** Refused the dates themselves: past their booking horizon, or too soon. */
  refused: number;
  /** Ran out of time to answer — worth a Check again, unlike the unknowns. */
  timedOut: number;
  /** No data, or the check failed for it, or an outcome this build does not know. */
  unknown: number;
  /** How many of the asked ids have answered. */
  answered: number;
}

/** The head's counts, over the ids the check asked about. */
export function stayTallies(
  asked: readonly number[],
  results: Readonly<Record<number, StayResult>>,
): StayTallies {
  const tallies: StayTallies = { withSite: 0, full: 0, closed: 0, refused: 0, timedOut: 0, unknown: 0, answered: 0 };
  for (const id of asked) {
    const result = results[id];
    if (!result) continue;
    tallies.answered += 1;
    if (result.kind === 'error') {
      if (result.outcome === 'refused') tallies.refused += 1;
      else if (result.outcome === 'timed_out') tallies.timedOut += 1;
      else tallies.unknown += 1;
    } else if (result.summary.status === 'open' || result.summary.status === 'few_left') tallies.withSite += 1;
    else if (result.summary.status === 'full') tallies.full += 1;
    else if (result.summary.status === 'closed_for_season') tallies.closed += 1;
    else tallies.unknown += 1;
  }
  return tallies;
}
