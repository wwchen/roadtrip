// Row content for the selected-day site list.
//
import type { Campsite } from '@/api/campsite-api';
import { siteName } from './matrix-rows';
import { capacityLabel, descriptionText, rawPayload } from './site-detail-facts';

/** Two lines of summary is the row's height budget; past that it is truncated. */
const MAX_DESCRIPTION_CHARS = 120;

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

/** "Up to 6 people · Walk-in tent site by the water" — whatever of it exists. */
export function rowDetails(row: Partial<Campsite>): string[] {
  const raw = rawPayload(row);
  return [capacityLabel(row, raw), descriptionText(raw.description, MAX_DESCRIPTION_CHARS)].filter(
    Boolean,
  );
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
  if (loopA !== loopB) return String(loopA).localeCompare(String(loopB));
  return siteName(a).localeCompare(siteName(b), undefined, { numeric: true });
}
