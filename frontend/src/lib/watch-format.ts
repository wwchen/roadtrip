// How a watch reads: its date, its age, and what to call it.
//
// Extracted from `features/watches/WatchTable.tsx`, which ported them in Phase 1,
// because the alerts panel (4e) is the second surface that shows a table of watches
// and re-implementing three formatters would guarantee the two drift. `WatchTable`
// re-exports them so its own suite and call sites are unchanged.
import type { Watch } from '@/api/watches-api';

const RELATIVE_MINUTE_S = 60;
const RELATIVE_HOUR_MIN = 60;
const RELATIVE_DAY_H = 24;

/**
 * Wall-clock age of an ISO instant.
 *
 * Reads the clock at render time, so a row's "5m ago" only refreshes when the table
 * re-renders — same as the original, which re-rendered on every reload. `now` is a
 * parameter so a test does not have to mock the clock.
 */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const secs = Math.max(0, Math.round((now - then) / 1000));
  if (secs < RELATIVE_MINUTE_S) return 'just now';
  const mins = Math.round(secs / RELATIVE_MINUTE_S);
  if (mins < RELATIVE_HOUR_MIN) return `${mins}m ago`;
  const hrs = Math.round(mins / RELATIVE_HOUR_MIN);
  if (hrs < RELATIVE_DAY_H) return `${hrs}h ago`;
  return `${Math.round(hrs / RELATIVE_DAY_H)}d ago`;
}

/**
 * A watch's start date as `Mon D`.
 *
 * Formatted in UTC on purpose: the value is a calendar date, so rendering it in the
 * viewer's zone would shift it a day west of UTC.
 */
export function formatWatchDate(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/** The label a watch shows when it has no POI target. Ported from the original. */
export function watchFallbackName(watch: Watch): string {
  const site = watch.campsite;
  const name = typeof site?.name === 'string' ? site.name : '';
  if (name) {
    const loop = typeof site?.loop_name === 'string' ? site.loop_name : '';
    return loop ? `${loop} / ${name}` : name;
  }
  return `Watch #${watch.id}`;
}
