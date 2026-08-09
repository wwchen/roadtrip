// Row content for the selected-day site list.
//
// The pure half of web/availability/site-list.js: which catalog rows a day's
// available ids resolve to, and the one-line summary under each name. Split out for
// the same reason as `matrix-rows.ts` — the fallbacks here are provider-shaped rules,
// not markup.
import type { Campsite } from '@/api/campsite-api';
import { siteName } from './matrix-rows';

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

/** "Sleeps up to 6 · Walk-in tent site by the water" — whatever of it exists. */
export function rowDetails(row: Partial<Campsite>): string[] {
  const raw = (row.source_payload && typeof row.source_payload === 'object'
    ? row.source_payload
    : {}) as Record<string, unknown>;
  return [capacityLabel(row, raw), descriptionSummary(raw.description)].filter(Boolean);
}

/**
 * Sleeping capacity, from whichever of six field spellings the provider used.
 *
 * The three forms are distinct claims and read differently: a known range, a known
 * ceiling, and a known floor. Collapsing them to one would overstate what we know.
 */
export function capacityLabel(row: Partial<Campsite>, raw: Record<string, unknown>): string {
  const min = numberValue(raw.min_capacity ?? raw.minCapacity ?? raw.min_num_people ?? raw.minNumPeople);
  const max = numberValue(
    row.max_people ?? raw.max_capacity ?? raw.maxCapacity ?? raw.max_num_people ?? raw.maxNumPeople,
  );
  if (min != null && max != null && min !== max) return `Sleeps ${min}-${max}`;
  if (max != null) return `Sleeps up to ${max}`;
  if (min != null) return `Sleeps ${min}+`;
  return '';
}

/**
 * A provider description as one clamped line of plain text.
 *
 * Tags are stripped rather than sanitised: this is a summary inside a dense list, so
 * provider markup has nothing to add here even when it is safe. The rich version
 * lives in the site-detail row.
 */
export function descriptionSummary(value: unknown): string {
  if (typeof value !== 'string') return '';
  const text = value
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
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
  if (loopA !== loopB) return String(loopA).localeCompare(String(loopB));
  return siteName(a).localeCompare(siteName(b), undefined, { numeric: true });
}

function numberValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}
