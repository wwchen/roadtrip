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
  /** The one gated step. */
  checkTitle: 'Check availability',
  checkBody: 'Add dates to see which of these have a site.',
  addDates: 'Add dates',
  startDate: 'Start date',
  endDate: 'End date',
  saveDates: 'Save',
  cancelDates: 'Cancel',
  editDates: 'Edit',
  clearDates: 'Clear',
  endBeforeStart: 'The end date must be after the start date.',
  dateFormat: 'Enter dates as YYYY-MM-DD.',
  nights: (nights: number) => (nights === 1 ? '1 night' : `${nights} nights`),
  /** "Fri Sep 11 → Sun Sep 13 · 2 nights". */
  dateSummary: (start: string, end: string, nights: number) =>
    `${start} → ${end} · ${filterCopy.nights(nights)}`,
} as const;
