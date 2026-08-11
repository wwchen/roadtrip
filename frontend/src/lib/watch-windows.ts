// Availability watches, as the grid understands them.
//
// The capability gates here are load-bearing rather than decorative. A watch that a
// provider cannot service is a promise we cannot keep, so "can this user set a
// watch on this cell" is answered from the backend's own capability block and not
// from whether a button would look good there.
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import {
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
} from '@/lib/watch-triggers';
import type { Watch } from '@/api/watches-api';
import type { WatchCapabilities as WireWatchCapabilities } from '@/api/availability-api';

/** How often a watch polls, when the grid creates one. */
export const DEFAULT_WATCH_CADENCE_SEC = 60;
/** New watches stop after they fire: an alert you have acted on is noise. */
export const DEFAULT_STOP_WHEN_FOUND = true;
/** The booking action a provider must support before "add to cart" is offered. */
const BOOKING_ACTION_ADD_TO_CART = 'add_to_cart';

/** What this provider can do, as sets rather than the wire arrays. */
export interface WatchCapabilities {
  triggerKinds: ReadonlySet<string>;
  bookingActions: ReadonlySet<string>;
}

export const NO_WATCH_CAPABILITIES: WatchCapabilities = {
  triggerKinds: new Set(),
  bookingActions: new Set(),
};

/**
 * The wire block as sets.
 *
 * Accepts an already-normalised value too, because the popover receives whichever
 * of the two shapes its caller happened to hold — the same tolerance the vanilla
 * `normalizeWatchCapabilities` had, for the same reason.
 */
export function normalizeWatchCapabilities(
  value: WireWatchCapabilities | WatchCapabilities | null | undefined,
): WatchCapabilities {
  const asWire = value as WireWatchCapabilities | null | undefined;
  const asSets = value as WatchCapabilities | null | undefined;
  return {
    triggerKinds:
      asSets?.triggerKinds instanceof Set
        ? asSets.triggerKinds
        : new Set(Array.isArray(asWire?.trigger_kinds) ? asWire.trigger_kinds : []),
    bookingActions:
      asSets?.bookingActions instanceof Set
        ? asSets.bookingActions
        : new Set(Array.isArray(asWire?.booking_actions) ? asWire.booking_actions : []),
  };
}

/**
 * Whether this campground can notify at all.
 *
 * Either channel counts: a provider with email but no Slack still supports alerts,
 * and gating on Slack alone would hide the feature from anyone whose provider
 * happens to be configured the other way.
 */
export function supportsWatchAlerts(capabilities: WatchCapabilities): boolean {
  return (
    capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY) ||
    capabilities.triggerKinds.has(TRIGGER_KIND_EMAIL_NOTIFY)
  );
}

/**
 * Whether "add to cart" can be offered.
 *
 * Needs *both* the booking action and the trigger: the action says the provider has
 * a cart, the trigger says our poller may drive it. One without the other is a
 * button that fails.
 */
export function supportsAddToCart(capabilities: WatchCapabilities): boolean {
  return (
    capabilities.bookingActions.has(BOOKING_ACTION_ADD_TO_CART) &&
    capabilities.triggerKinds.has(TRIGGER_KIND_ATC)
  );
}

/**
 * The stay a watch on `startDate` covers: that night only.
 *
 * End-exclusive, so a watch on the 4th ends on the 5th. Single-night by design —
 * the grid is a per-day surface, and a multi-night watch set by tapping one cell
 * would be a different feature wearing the same affordance.
 */
export function stayEndDate(startDate: string): string {
  return localYmd(addLocalDays(parseLocalYmd(startDate), 1));
}

/** The key a watch is stored under: its exact window. */
export function watchWindowKey(startDate: string, endDate: string): string {
  return `${startDate}|${endDate}`;
}

/**
 * The user's watches for this POI, keyed by window.
 *
 * Filters by POI id rather than trusting the query parameter, and drops `done`
 * watches: a fired watch is history, and showing its cell as still-watched would
 * invite someone to wait for an alert that has already been sent.
 */
export function indexWatchesByWindow(
  watches: readonly Watch[] | null | undefined,
  poiId: string | number,
): Map<string, Watch> {
  const out = new Map<string, Watch>();
  if (!Array.isArray(watches)) return out;
  const id = String(poiId);
  for (const watch of watches) {
    if (!watch || watch.status === 'done') continue;
    if (String(watch.poi_id ?? '') !== id) continue;
    const start = watch.start_date;
    const end = watch.end_date;
    if (start && end) out.set(watchWindowKey(start, end), watch);
  }
  return out;
}

/**
 * The dates in the visible week that already have a watch.
 *
 * Watches are single-day, so a watch's start date *is* the watched day and the
 * matrix marks that column.
 */
export function watchedDates(watchesByWindow: ReadonlyMap<string, Watch>): Set<string> {
  const out = new Set<string>();
  for (const watch of watchesByWindow.values()) {
    if (watch?.start_date) out.add(watch.start_date);
  }
  return out;
}
