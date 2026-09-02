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
 * What we call recreation.gov to users.
 *
 * The product said "Recreation.gov" in error banners and credential labels and
 * "rec.gov" in the booking rows, which is one product with two names for the same
 * vendor. It is the short form everywhere now.
 */
export const VENDOR = 'rec.gov';

export const bookingCopy = {
  /** The popover's escape hatch to the provider's own page. */
  openProvider: `Book on ${VENDOR}`,
  addToCart: 'Add to cart',
  /** The armed cell's label, before the second tap. */
  book: 'Book',
  held: 'Cart',
  holdRunning: 'Holding site… usually under a minute; can take a few',
  heldTitle: `Site held in your ${VENDOR} cart`,
  openCart: `Open ${VENDOR} cart ↗`,
  checkOutSoon: `Check out on ${VENDOR} within 15 minutes.`,
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
  cartNoCredentials: `Add ${VENDOR} login in Settings`,
  watchSignedOut: 'Sign in to get an alert when a site opens up that night.',
  /** The word inside the day panel's sentence, and the sentence around it. */
  signIn: 'Sign in',
  daySignedOutSuffix: ' to set availability alerts.',
  /** The add-to-cart row's help, as a link plus the prose that follows it. */
  editorNoCredentialsLink: `Add your ${VENDOR} login`,
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
