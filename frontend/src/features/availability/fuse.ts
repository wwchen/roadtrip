// Fusing one campsite-availability response into the per-day rows the grid draws.
//
// The endpoint returns one envelope per campsite — a stream each — and deliberately leaves the combining to the client,
// so this is where "what is this campground's status on Thursday" is actually
// decided.
import { normalizeAvailabilityStatus, type AvailabilityStatus } from '@/lib/availability-status';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import type {
  AvailabilityCache,
  CampsiteAvailability,
  PoiCampsitesAvailabilityResponse,
} from '@/api/availability-api';

/** The provider's own "closed for the season" marker on a campsite envelope. */
const STATE_CLOSED_FOR_SEASON = 'closed_for_season';

/**
 * Which banner or grid the week shows.
 *
 * `empty` and `closed_for_season` are different facts and stay different states:
 * "this campground has no online-bookable sites" is permanent, "come back in May"
 * is not, and collapsing them was the copy bug this distinction exists to avoid.
 */
export type WeekState = 'success' | 'empty' | 'closed_for_season';

/** A day of the visible week, fused across every campsite. */
export interface FusedDay {
  date: string;
  /** The campground's rolled-up status for the day — see `rollupStatus`. */
  status: AvailabilityStatus;
  /** Campsite ids bookable that day, ascending. */
  available_campsite_ids: number[];
  /** `campsiteId → status`, in ascending id order, for the matrix cells. */
  campsite_statuses: Record<string, AvailabilityStatus>;
}

export interface FusedWeek {
  state: WeekState;
  days: FusedDay[];
  /** The provider's season block, only when every campsite is closed for it. */
  season: SeasonBlock | null;
  /** The stalest cache block across the streams — see `oldestCacheBlock`. */
  cacheBlock: AvailabilityCache | null;
}

/** The only field of a provider's season block anything reads. */
export interface SeasonBlock {
  reopens_on?: string | null;
  [key: string]: unknown;
}

/**
 * Rollup precedence: what one label for a whole campground should say.
 *
 * `available` first because one bookable site makes the day bookable, and
 * `unknown` **ahead of** `reserved` because a stream we could not read must not
 * render as a confident "full" — an honest "?" sends the user to the provider,
 * a wrong "reserved" sends them away. `closed` requires unanimity: any
 * non-closed site means the campground is open even if most of it is not.
 *
 * One deliberate deviation from the original: it compared the provider's strings
 * verbatim, so `"Available"` fell through every branch and rolled up as
 * `unknown`. Every *renderer* in both trees normalises before display, which made
 * the rollup the one place a casing difference changed the answer. Normalising
 * here removes that inconsistency; for the wire values the backend actually sends
 * (lower snake_case) it is a no-op.
 */
const ROLLUP_ORDER: readonly AvailabilityStatus[] = ['available', 'first_come', 'unknown', 'reserved'];

export function rollupStatus(values: readonly unknown[]): AvailabilityStatus {
  if (values.length === 0) return 'unknown';
  const statuses = values.map(normalizeAvailabilityStatus);
  for (const candidate of ROLLUP_ORDER) {
    if (statuses.includes(candidate)) return candidate;
  }
  if (statuses.every((value) => value === 'closed')) return 'closed';
  return 'unknown';
}

/**
 * Every date in `[startDate, endDate)`.
 *
 * End-exclusive, matching the endpoint's window and the booking convention: a
 * one-night stay on the 4th is `start=4th, end=5th`, and the 5th is not a day of
 * the stay.
 */
export function enumerateDates(startDate: string, endDate: string): string[] {
  const out: string[] = [];
  const end = parseLocalYmd(endDate);
  for (let cur = parseLocalYmd(startDate); cur < end; cur = addLocalDays(cur, 1)) {
    out.push(localYmd(cur));
  }
  return out;
}

/**
 * The stalest cache block across the streams.
 *
 * The grid shows one freshness pill, and the honest number when streams were
 * cached at different times is the oldest one — a pill reading "checked 1m ago"
 * over a day whose data is an hour old is worse than no pill.
 */
export function oldestCacheBlock(
  campsites: readonly CampsiteAvailability[],
): AvailabilityCache | null {
  let chosen: AvailabilityCache | null = null;
  for (const row of campsites) {
    const cache = row?.cache;
    if (!cache) continue;
    if (!chosen || (cache.age_seconds ?? 0) > (chosen.age_seconds ?? 0)) chosen = cache;
  }
  return chosen;
}

/** One date, across every campsite stream. */
export function fuseDay(date: string, campsites: readonly CampsiteAvailability[]): FusedDay {
  const statuses = new Map<string, AvailabilityStatus>();
  for (const row of campsites) {
    const campsiteId = row?.campsite_id;
    if (campsiteId == null) continue;
    const day = (Array.isArray(row.availability) ? row.availability : []).find(
      (entry) => entry?.date === date,
    );
    statuses.set(String(campsiteId), normalizeAvailabilityStatus(day?.status));
  }

  // Numeric id order, not insertion order: the matrix and the site list both
  // iterate this, and a stable order keeps rows from reshuffling between weeks.
  const idsSorted = [...statuses.keys()].sort((a, b) => Number(a) - Number(b));
  const ordered: Record<string, AvailabilityStatus> = {};
  for (const id of idsSorted) ordered[id] = statuses.get(id)!;

  return {
    date,
    status: rollupStatus(idsSorted.map((id) => statuses.get(id))),
    available_campsite_ids: idsSorted
      .filter((id) => statuses.get(id) === 'available')
      .map((id) => Number(id)),
    campsite_statuses: ordered,
  };
}

/**
 * The whole week.
 *
 * Two shortcuts before any per-day work, both from the original:
 *   - no campsites at all → `empty`; this POI has nothing online-bookable, and
 *     there is no grid to draw.
 *   - every campsite `closed_for_season` → `closed_for_season`, carrying a season
 *     block from the first campsite that has a reopen date, since providers only
 *     populate it on some streams.
 */
export function fusePoiCampsitesAvailability(
  json: Partial<PoiCampsitesAvailabilityResponse> | null | undefined,
  startDate: string,
  endDate: string,
): FusedWeek {
  const campsites = Array.isArray(json?.campsites) ? json.campsites : [];
  if (campsites.length === 0) {
    return { state: 'empty', days: [], season: null, cacheBlock: null };
  }

  if (campsites.every((row) => row?.state === STATE_CLOSED_FOR_SEASON)) {
    const withReopen = campsites.find((row) => {
      const season = row?.season as SeasonBlock | null | undefined;
      return season && season.reopens_on;
    });
    return {
      state: 'closed_for_season',
      days: [],
      season: ((withReopen?.season as SeasonBlock | undefined) ?? null),
      cacheBlock: oldestCacheBlock(campsites),
    };
  }

  return {
    state: 'success',
    days: enumerateDates(startDate, endDate).map((date) => fuseDay(date, campsites)),
    season: null,
    cacheBlock: oldestCacheBlock(campsites),
  };
}
