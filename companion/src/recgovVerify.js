// Dry-run session check for one profile.
//
// Loads a logged-in-only rec.gov page and reads GET /api/cart/shoppingcart
// from page context, which exercises the session, the fingerprint cookie and
// Akamai without needing a campsite target. It never clicks Reserve and never
// places a cart hold — a test must never cost a real reservation.

import {
  getCartItems,
  recgovAuthenticationFailure,
} from './cart.js'
import {
  getContext,
  injectBearerRoute,
  injectFingerprintCookie,
  injectRecaccount,
  injectStoredCookies,
  isSpaLoggedIn,
} from './browser.js'
import {
  resolveRecaccount,
  withRecgovProfileScope,
} from './recgovSession.js'

export const RECGOV_ACCOUNT_URL = 'https://www.recreation.gov/account/profile'
export const VERIFY_NAVIGATION_TIMEOUT_MS = 30_000
export const VERIFY_SETTLE_MS = 2_000
const CART_OK_STATUS = 200

export const VERIFY_ERROR_NOT_AUTHENTICATED = 'recgov_not_authenticated'
export const VERIFY_ERROR_CART_UNREACHABLE = 'recgov_cart_unreachable'

export function createRecgovVerifyDeps ({
  getContextFn = getContext,
  injectStoredCookiesFn = injectStoredCookies,
  resolveRecaccountFn = resolveRecaccount,
  injectRecaccountFn = injectRecaccount,
  injectBearerRouteFn = injectBearerRoute,
  injectFingerprintCookieFn = injectFingerprintCookie,
  isSpaLoggedInFn = isSpaLoggedIn,
  getCartItemsFn = getCartItems,
} = {}) {
  return {
    getContextFn,
    injectStoredCookiesFn,
    resolveRecaccountFn,
    injectRecaccountFn,
    injectBearerRouteFn,
    injectFingerprintCookieFn,
    isSpaLoggedInFn,
    getCartItemsFn,
  }
}

export async function verifyRecgovSession ({ profileId = null, ...overrides } = {}) {
  return withRecgovProfileScope(profileId, () => runVerify(profileId, overrides))
}

async function runVerify (profileId, overrides) {
  const deps = createRecgovVerifyDeps(overrides)
  const context = await deps.getContextFn()
  await deps.injectStoredCookiesFn(context, null, profileId)
  const page = await context.newPage()
  try {
    const recaccount = await deps.resolveRecaccountFn(page, { allowManualLogin: false })
    if (!recaccount?.access_token) return unauthenticatedResult()

    await deps.injectRecaccountFn(page, recaccount)
    await deps.injectBearerRouteFn(page, recaccount.access_token)
    await deps.injectFingerprintCookieFn(context, recaccount.access_token)
    await page.goto(RECGOV_ACCOUNT_URL, {
      waitUntil: 'domcontentloaded',
      timeout: VERIFY_NAVIGATION_TIMEOUT_MS,
    })
    await page.waitForTimeout(VERIFY_SETTLE_MS)

    const loggedIn = (await deps.isSpaLoggedInFn(page)) === true
    if (!loggedIn) return unauthenticatedResult()

    const cart = await deps.getCartItemsFn(page)
    const cartStatus = cart?.status ?? null
    if (cartStatus !== CART_OK_STATUS) {
      return {
        ...verifyBase(true, cartStatus, cart),
        ok: false,
        error: VERIFY_ERROR_CART_UNREACHABLE,
        detail: cart?.error || `cart API returned status ${cartStatus ?? '?'}`,
      }
    }
    return {
      ...verifyBase(true, cartStatus, cart),
      ok: true,
      token_expires_at: recaccount.expiration ?? null,
    }
  } finally {
    await page.close().catch(() => {})
  }
}

function unauthenticatedResult () {
  const failure = recgovAuthenticationFailure()
  return {
    ...verifyBase(false, null, null),
    ok: false,
    error: VERIFY_ERROR_NOT_AUTHENTICATED,
    detail: failure.detail,
    corrective_action: failure.corrective_action,
  }
}

function verifyBase (loggedIn, cartStatus, cart) {
  return {
    logged_in: loggedIn,
    account_url: RECGOV_ACCOUNT_URL,
    cart_status: cartStatus,
    cart_reservation_count: reservationCount(cart),
    checked_at: new Date().toISOString(),
  }
}

function reservationCount (cart) {
  const reservations = cart?.reservations
  return Array.isArray(reservations) ? reservations.length : null
}
