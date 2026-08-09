// What the alerts panel says about a watch.
//
// The pure half of web/topbar/alerts.js: the ordering, the bar label, the done-state
// inference, and the Slack deep-link contract. Everything here is testable without a
// watch list, a network call or a DOM.
import type { Watch, WatchStatus } from '@/api/watches-api';
import { watchFallbackName } from '@/lib/watch-format';

/** The panel asks for every status; 200 is far past what a person accumulates. */
export const WATCH_LIST_LIMIT = 200;

/** The statuses the panel lists, in the order it fetches them. */
export const ALERT_STATUSES: readonly WatchStatus[] = ['active', 'paused', 'done'];

/**
 * The Slack deep-link parameters.
 *
 * Kept in sync with `WatchAlertDispatcher` on the backend, which builds
 * `<appRoot>/?alert=<id>&alert_action=<action>`.
 */
export const ALERT_PARAM = 'alert';
export const ALERT_ACTION_PARAM = 'alert_action';

/** How long the focused row and its armed control stay highlighted. */
export const FOCUS_HIGHLIGHT_MS = 6_000;

export type AlertAction = 'pause' | 'resume' | 'delete';
const ALERT_ACTIONS = new Set<AlertAction>(['pause', 'resume', 'delete']);

/**
 * Ascending by start date, undated last.
 *
 * Lexicographic compare is chronological for `YYYY-MM-DD`, so this needs no parsing.
 * The nearest window belongs at the top: that is the one the user is about to act on.
 */
export function byStartDate(a: Watch, b: Watch): number {
  const da = a.start_date ?? '';
  const db = b.start_date ?? '';
  if (da === db) return 0;
  if (!da) return 1;
  if (!db) return -1;
  return da < db ? -1 : 1;
}

/**
 * The one flat, sorted list the panel renders.
 *
 * One list across all three statuses rather than three sections, because the question
 * a user has is "what am I waiting on, soonest first" — not "what is paused".
 */
export function alertRows(lists: readonly (readonly Watch[] | undefined)[]): Watch[] {
  return lists.flatMap((list) => list ?? []).sort(byStartDate);
}

export interface AlertCounts {
  active: number;
  paused: number;
  done: number;
  total: number;
}

export function countByStatus(watches: readonly Watch[]): AlertCounts {
  const active = watches.filter((w) => w.status === 'active').length;
  const paused = watches.filter((w) => w.status === 'paused').length;
  const done = watches.filter((w) => w.status === 'done').length;
  return { active, paused, done, total: active + paused + done };
}

/**
 * The collapsed bar's label: "3 availability alerts · 1 paused · 2 done".
 *
 * The extras are only named when they exist, so the common case reads as one clause.
 */
export function barLabel({ paused, done, total }: AlertCounts): string {
  const base = `${total} availability alert${total === 1 ? '' : 's'}`;
  const extra: string[] = [];
  if (paused > 0) extra.push(`${paused} paused`);
  if (done > 0) extra.push(`${done} done`);
  return extra.length ? `${base} · ${extra.join(' · ')}` : base;
}

/** The name a row shows: the POI's, or the watch's own fallback. */
export function alertName(watch: Watch, poiNames: ReadonlyMap<number, string>): string {
  if (watch.poi_id != null) return poiNames.get(watch.poi_id) || `POI ${watch.poi_id}`;
  return watchFallbackName(watch);
}

/**
 * Why a watch is done: availability was found, or its window elapsed.
 *
 * Inferred rather than read, because the list payload does not carry the trigger flag
 * (see `WatchAlertDispatcher` / `AvailabilityPollerRepo.retire`): a watch whose end
 * date has passed expired, and anything else that is done was triggered. `today` is a
 * parameter so a test does not have to mock the clock.
 */
export function doneKind(watch: Watch, today: string = new Date().toISOString().slice(0, 10)) {
  const end = watch.end_date ?? '';
  return end && end < today ? 'expired' : ('found' as const);
}

export interface AlertDeepLink {
  watchId: string;
  /** The control to pulse, when the link named one we recognise. */
  action: AlertAction | null;
}

/**
 * Read `?alert=` (and its optional action) from the current URL.
 *
 * The panel never auto-mutates on one of these: it expands, scrolls the row into view
 * and pulses the named control, so the user completes the action with the in-app
 * button. That is deliberate — a stale or forwarded Slack card must not be able to
 * change a watch by itself, and the app stays the only writer.
 */
export function readAlertDeepLink(search: string = window.location.search): AlertDeepLink | null {
  const params = new URLSearchParams(search);
  const watchId = params.get(ALERT_PARAM);
  if (!watchId) return null;
  const action = params.get(ALERT_ACTION_PARAM) as AlertAction | null;
  return { watchId, action: action && ALERT_ACTIONS.has(action) ? action : null };
}

/** Drop both parameters, so a refresh or a back-nav does not re-focus the row. */
export function clearAlertDeepLink(): void {
  const url = new URL(window.location.href);
  if (!url.searchParams.has(ALERT_PARAM) && !url.searchParams.has(ALERT_ACTION_PARAM)) return;
  url.searchParams.delete(ALERT_PARAM);
  url.searchParams.delete(ALERT_ACTION_PARAM);
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
}
