// User-facing copy for the availability and watch surfaces, in one place.
//
// **What belongs here.** Words a user reads. Not aria-labels, which describe a
// control's state and belong next to the control that computes it, and not error
// text that is really a code (`settings-errors.ts` owns that mapping).
//
// **Why.** Two reasons, neither of them locale — there is no i18n and none
// planned. First, copy is reviewed as copy: a wording pass should be one diff on
// one file, not a hunt across nine components. Second, a set of messages has to
// read as a set. The capability hints below are four sentences that a user meets
// one at a time and must recognise as one voice, which is exactly the property
// that erodes when they live apart.
//
// **Migration is partial and deliberate.** This covers the availability grid, the
// watch surfaces, and every string that names the booking vendor. The account and
// POI surfaces still hold their own copy; move them in when you next touch them
// rather than in one sweep.
//
// Anything that varies is a function, so a caller cannot assemble a sentence out
// of fragments and get the word order wrong.

import type { StayAvailabilityStatus, StayFailureReason, StayOutcome } from '@/api/availability-api';
import { entryOf } from './entry-of';

/**
 * What we call recreation.gov to users — the one name, matching the vendor's own
 * branding and the `booking_system` the backend serves. Two names for one vendor
 * used to collide on a single screen.
 */
export const VENDOR = 'Recreation.gov';

/**
 * The neutral name for a booking site we cannot name. `heldTitle` and
 * `openCart` read better with the vendor simply dropped ("your cart"), so they
 * do that instead; every sentence that needs a noun there uses this one.
 */
const UNNAMED_BOOKING_SITE = 'the booking site';

/** The possessive form, for the gate sentences that ask for a login. */
const UNNAMED_BOOKING_ACCOUNT = 'your booking';

/** The bare noun, for the one sentence that already supplies its own "your". */
const UNNAMED_BOOKING_LABEL = 'booking';

/**
 * The one template for the booking verb plus a name.
 *
 * Both surfaces that offer a named booking link — the cell popover's escape
 * hatch and the site-detail anchor — go through this, so a wording pass is one
 * edit rather than two that can drift apart.
 */
const bookOn = (bookingSystem: string) => `Book on ${bookingSystem}`;

export const bookingCopy = {
  /** Named after whoever takes the booking at that link. Callers that have no
   *  name of their own fall back to [openProvider]'s neutral noun. */
  bookOn,
  /** The popover's escape hatch, named after whoever takes the booking at that link. */
  openProvider: (bookingSystem?: string) => bookOn(bookingSystem ?? UNNAMED_BOOKING_SITE),
  addToCart: 'Add to cart',
  /** The armed cell's label, before the second tap. */
  book: 'Book',
  /** The drawer's fallback when a pin carries a bare reserve URL and no label. */
  reserve: 'Reserve',
  holdRunning: 'Holding site… usually under a minute; can take a few',
  // The three lines of the hold toast. Each takes the name of the provider that
  // actually held the site, rather than naming one vendor for all of them.
  heldTitle: (bookingSystem?: string) => `Site held in your ${bookingSystem ? `${bookingSystem} ` : ''}cart`,
  openCart: (bookingSystem?: string) => `Open ${bookingSystem ?? 'your'} cart ↗`,
  checkOutSoon: (bookingSystem?: string) =>
    `Check out on ${bookingSystem ?? UNNAMED_BOOKING_SITE} within 15 minutes.`,
  holdBusyTitle: 'One hold at a time',
  holdBusyBody: 'A hold is already running — wait for it to finish before holding another site.',
} as const;

/**
 * The capability hints: what a control says when the caller cannot use it yet.
 *
 * Each names the one step that unlocks it and nothing else. They are together
 * because they are a set — a user who meets two of them should hear one voice.
 */
export const gateCopy = {
  cartSignedOut: 'Sign in to hold sites from here',
  /** Named after the adapter the backend says would hold it, never the POI's. */
  cartNoCredentials: (providerDisplay?: string) =>
    `Add ${providerDisplay ?? UNNAMED_BOOKING_ACCOUNT} login in Settings`,
  watchSignedOut: 'Sign in to get an alert when a site opens up that night.',
  /** The word inside the day panel's sentence, and the sentence around it. */
  signIn: 'Sign in',
  daySignedOutSuffix: ' to set availability alerts.',
  /** The add-to-cart row's help, as a link plus the prose that follows it. */
  editorNoCredentialsLink: (providerDisplay?: string) => `Add your ${providerDisplay ?? UNNAMED_BOOKING_LABEL} login`,
  editorNoCredentialsSuffix: ' in Settings to hold sites.',
  editorSignedOutSuffix: ' to enable add-to-cart.',
} as const;

export const dayCopy = {
  setWatch: 'Set watch',
  watching: 'Watching - tap to remove',
  working: 'Working…',
  checking: 'Checking your availability alerts…',
  checkFailed: "Couldn't check your availability alerts.",
  retry: 'Retry',
  unsupported: 'Watches are not available for this campground.',
  nothingToWatch: 'No online openings to watch for this day.',
} as const;

export const watchCopy = {
  /** The panel titles. Both surfaces use the same one so signing in swaps only the body. */
  title: (poiName: string) => `Watch ${poiName}`,
  slack: 'Slack',
  slackHelp: 'Post when a matching site opens.',
  email: 'Email',
  emailHelp: 'Send to the email address saved in your account settings.',
  addToCart: 'Add to cart',
  addToCartHelp: 'Try to hold a matching site.',
  addToCartUnavailable: 'Unavailable for this watch scope.',
  stopWhenTriggered: 'Stop when triggered',
  stopWhenTriggeredHelp: 'Mark done after a successful trigger.',
  close: 'Close',
} as const;

/** The banners a failing upstream produces. Each names the vendor, not "the provider". */
export const upstreamCopy = {
  rateLimitedTitle: `${VENDOR} is limiting our checks`,
  erroredTitle: `${VENDOR} returned an error`,
  unreachableTitle: `We can't reach ${VENDOR}`,
  /** Copied to the clipboard for a bug report, so it carries the ids. */
  reportDetail: (poiName: string, poiId: string | number, message: string) =>
    `${VENDOR} error for ${poiName} (POI ${poiId}): ${message}`,
} as const;

/** The account surface's vendor-bearing strings. The rest of its copy is still inline. */
export const accountCopy = {
  emailLabel: `${VENDOR} email`,
  passwordLabel: `${VENDOR} password`,
  removeLabel: `Remove ${VENDOR} credentials`,
  credentialsRejected: `${VENDOR} rejected these credentials.`,
  mfaSent: `${VENDOR} sent a verification code. Enter it below.`,
  removed: `${VENDOR} credentials and saved browser session removed.`,
  removedNoSession: `${VENDOR} credentials removed. There was no saved browser session to erase.`,
} as const;

/**
 * The topbar's list of campgrounds in view, before any dates are chosen.
 * "Checkable online" is the one availability word here: the campground has a
 * booking provider we can ask (`availability_supported`).
 */
export const inViewCopy = {
  heading: 'Campgrounds in view',
  /** The card's count line: the whole catalog, until a site type narrows it (M2). */
  sites: (total: number) => (total === 1 ? '1 site' : `${total} sites`),
  checkableCount: (checkable: number) => `${checkable} checkable online`,
  notCheckable: 'Not checkable online',
  zoomIn: 'Zoom in to load campgrounds.',
  loading: 'Finding campgrounds in view…',
  none: 'No campgrounds in view — pan or zoom out to find some.',
  failed: 'Could not load the campgrounds in view. Try panning the map.',
} as const;

/**
 * The topbar's corridor list, when a trip is routed.
 *
 * Separate from [inViewCopy] because the two lists answer different questions:
 * this one is about a corridor the user can widen, that one about a viewport
 * they can pan.
 */
export const routeListCopy = {
  heading: 'Campgrounds along route',
  loading: 'Looking for campgrounds along the route…',
  none: 'Pan the map or widen the corridor to find campgrounds.',
} as const;

/** What both lists say when the legend, rather than the catalog, is what emptied them. */
export const listCopy = {
  allAgenciesHidden: 'All campgrounds hidden — re-enable a category in the legend.',
  layerOffTitle: 'Campgrounds are switched off',
  layerOffBody: (inView: number) =>
    `There are ${inView} in view — the Campgrounds layer is turned off, so none are drawn.`,
  layerOffAction: 'Turn campgrounds back on',
} as const;

/**
 * What a card says when its campground refused the stay itself, by the reason it
 * refused with. A refusal for a reason not listed reads as `stayCopy.refusedLine`.
 */
const STAY_REFUSAL_COPY: Readonly<Partial<Record<StayFailureReason, string>>> = {
  beyond_booking_horizon: 'Not bookable this far ahead yet',
  start_before_earliest: 'Too soon to book online',
  window_too_long: 'Too long a stay to book online',
};

/** The availability check (M3): its button, its progress, and each card's answer. */
export const stayCopy = {
  check: 'Check availability',
  checkAgain: 'Check again',
  checking: 'Checking…',
  /** The list head while a check runs: "Checking 5 of 13…". */
  progress: (answered: number, asked: number) => `Checking ${answered} of ${asked}…`,
  /** "Mon Oct 12 → Wed Oct 14", from the two dates already formatted. */
  range: (start: string, end: string) => `${start} → ${end}`,
  /** The list head once it is back: "Mon Oct 12 → Wed Oct 14 · 8 with a site · 5 full", then any closed and unknown. */
  summary: (range: string, withSite: number, full: number) => `${range} · ${withSite} with a site · ${full} full`,
  closed: (count: number) => `${count} closed for the season`,
  refused: (count: number) => `${count} not bookable for these dates`,
  timedOutCount: (count: number) => `${count} too slow to answer`,
  unknown: (count: number) => `${count} unknown`,
  checkedAgo: (ago: string) => `Checked ${ago}`,
  throttled: 'Too many checks in a row — try again in a minute.',
  noneCheckable: 'None of the campgrounds in view can be checked online.',
  failed: 'The check stopped part way. Try again.',
  timedOut: 'The booking sites took too long to answer. Try again.',
  /** A card still waiting for its answer. */
  pending: 'Checking…',
  /** A card whose campground could not be asked. */
  error: "Couldn't check",
  /** A card whose campground ran out of time: unlike `error`, asking again may well answer. */
  timedOutLine: 'Too slow to answer — check again',
  /** A card whose campground refused the stay for a reason with no copy of its own. */
  refusedLine: 'Not bookable for these dates',
  /**
   * A card's line for an answer that is not a stay, by the outcome the server gave
   * it: a refusal says why, a timeout says to ask again, and anything else — a
   * failure, or an outcome this build has never heard of — is `error`.
   */
  errorFor: (outcome: StayOutcome, reason: StayFailureReason | null) => {
    if (outcome === 'timed_out') return stayCopy.timedOutLine;
    if (outcome !== 'refused') return stayCopy.error;
    return (reason === null ? undefined : entryOf(STAY_REFUSAL_COPY, reason)) ?? stayCopy.refusedLine;
  },
  reopens: (date: string) => `Reopens ${date}`,
  /** A card's answer, by status; `open` is the count of sites open every night. */
  result: {
    open: (open: number) => `${open} sites open`,
    few_left: (open: number) => (open === 1 ? '1 site left' : `${open} sites left`),
    full: () => 'Full',
    closed_for_season: () => 'Closed for the season',
    no_data: () => 'No data for these dates',
  } satisfies Record<StayAvailabilityStatus, (open: number) => string>,
} as const;

export const placeCopy = {
  driveFrom: (duration: string, origin: string) => `${duration} from ${origin}`,
  factsSeparator: ' · ',
} as const;

/** The campground filter block under the search row, and the cards' facet row. */
export const filterCopy = {
  pill: 'Filter campgrounds',
  pillHint: 'Then check dates',
  heading: 'Campgrounds',
  /** The filter block's count: everything in view, or how many of it pass the filter. */
  inView: (total: number) => `${total} in view`,
  matching: (matching: number, total: number) => `${matching} of ${total} in view`,
  siteType: 'Site type',
  anySiteType: 'Any',
  groupSize: 'Group size',
  fewerPeople: 'Fewer people',
  morePeople: 'More people',
  /** The card's count line with a type selected: the kind's count, then the total when they differ. */
  kindSites: (count: number, kindLabel: string, total: number) => {
    const noun = kindLabel === kindLabel.toUpperCase() ? kindLabel : kindLabel.toLowerCase();
    const kind = `${count} ${noun} ${count === 1 ? 'site' : 'sites'}`;
    return count === total ? kind : `${kind} · ${total} total`;
  },
  /** Facet labels. */
  facetGroup: (maxPeople: number) => `Up to ${maxPeople}`,
  facetGroupNoData: 'Group size',
  noData: 'No data',
  /** The state word in a facet's aria-label, e.g. "Tent, matches". */
  facetMatches: 'matches',
  facetMisses: 'does not match',
  /** The date window. */
  startDate: 'Start date',
  endDate: 'End date',
  /** What an empty date field shows. */
  noDate: 'Add date',
  /** A date field's accessible name: "Start date, Fri Sep 11", or "Start date, not set". */
  dateField: (label: string, date: string | null) => `${label}, ${date ?? 'not set'}`,
  /** The hint under the fields while nothing is picked. */
  datesPurpose: 'Add dates to see which of these have a site.',
  clearDates: 'Clear',
  pickStart: 'Pick a start date',
  pickEnd: 'Pick an end date',
  nights: (nights: number) => (nights === 1 ? '1 night' : `${nights} nights`),
} as const;
