// Row content for the selected-day site list.
//
import type { Campsite } from '@/api/campsite-api';
import { siteName } from './matrix-rows';
import { capacityLabel } from './site-detail-facts';

/** Two lines of summary is the row's height budget; past that it is truncated. */
const MAX_DESCRIPTION_CHARS = 120;

/** The drawer says "2-6 people"; a dense row says "Sleeps 2-6". */
const PEOPLE_SUFFIX = ' people';

/**
 * The catalog rows for a day's available ids, in the id list's order.
 *
 * A missing catalog row becomes a stub rather than being dropped: the count in the
 * header comes from the availability response, and silently rendering fewer rows than
 * the count promises reads as a bug in the count.
 */
export function campsitesForIds(
  campsites: readonly Campsite[] | null | undefined,
  ids: readonly string[],
): Partial<Campsite>[] {
  const byId = new Map(
    (Array.isArray(campsites) ? campsites : []).map((row) => [String(row.id), row]),
  );
  return ids.map(
    (id) => byId.get(String(id)) ?? ({ id: id as unknown as number, data_provider_ref: id }),
  );
}

/** "Sleeps up to 6 · Walk-in tent site by the water" — whatever of it exists. */
export function rowDetails(row: Partial<Campsite>): string[] {
  return [sleepsLabel(row), descriptionSummary(row.description)].filter(Boolean);
}

/**
 * Sleeping capacity, in the list's wording.
 *
 * The drawer's three phrasings are distinct claims — a known range, a known
 * ceiling, and a known floor — so this re-words that one decision rather than
 * branching over the same two columns a second time.
 */
function sleepsLabel(row: Partial<Campsite>): string {
  const capacity = capacityLabel(row).replace(PEOPLE_SUFFIX, '');
  if (!capacity) return '';
  return `Sleeps ${capacity.charAt(0).toLowerCase()}${capacity.slice(1)}`;
}

/** A provider description as one clamped line, for a dense list row. */
export function descriptionSummary(value: string | null | undefined): string {
  const text = value ? value.replace(/\s+/g, ' ').trim() : '';
  if (!text) return '';
  return text.length > MAX_DESCRIPTION_CHARS
    ? `${text.slice(0, MAX_DESCRIPTION_CHARS - 3).trim()}...`
    : text;
}

/** The header's label, which says how much of the campground is open. */
export function siteListLabel(count: number | null, total: number | null): string {
  if (count == null) return 'Available sites';
  if (total != null) return `Available sites (${count} of ${total} sites)`;
  return `Available sites (${count})`;
}

/** Loop, then site name, numerically. Loop-less rows last. */
export function compareListRows(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const loopA = a.loop_name || '￿';
  const loopB = b.loop_name || '￿';
  if (loopA !== loopB) return loopA.localeCompare(loopB);
  return siteName(a).localeCompare(siteName(b), undefined, { numeric: true });
}
