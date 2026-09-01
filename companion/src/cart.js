// Add-to-cart orchestration. Owns Playwright-driven rec.gov interaction.
// Returns the browser result to the CLI/HTTP caller; the companion does not persist.

import fs from 'node:fs/promises'
import path from 'node:path'
import {
  IS_HEADLESS,
  getContext,
  injectStoredCookies,
  persistProfileCookies,
  injectFingerprintCookie,
  injectBearerRoute,
  injectRecaccount,
  isSpaLoggedIn,
  reservationUrl,
  campsiteUrl,
  toCheckoutDate,
} from './browser.js'
import {
  RECGOV_DIAGNOSTIC_DIR,
  RECGOV_HOME_URL,
  RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS,
  RECGOV_LOGIN_STATE_SETTLE_MS,
  activeProfileId,
  clearBrowserRecaccount,
  resolveRecaccount,
  withRecgovProfileScope,
} from './recgovSession.js'
import { captureRecgovPageImage } from './recgovScreenshotCapture.js'
import { SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX } from './recgovScreenshotRoutes.js'
import { diagnosticArtifactName } from './tracing.js'

let lastLoginState = null
export function getLastLoginState () { return lastLoginState }

export async function getCartItems (page) {
  return page.evaluate(async () => {
    try {
      const resp = await fetch('https://www.recreation.gov/api/cart/shoppingcart', {
        credentials: 'include',
      })
      const body = await resp.json().catch(() => ({}))
      return { status: resp.status, reservations: body?.reservations ?? null, body }
    } catch (e) { return { error: e.message } }
  })
}

async function clickCalendarDate (page, dateStr) {
  const d = new Date(dateStr + 'T12:00:00Z')
  const monthDay = d.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
  const weekdayFull = d.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
  const dayNum = String(d.getUTCDate())
  const selectors = [
    `[aria-label*="${monthDay}"]:not([aria-disabled="true"])`,
    `[aria-label*="${weekdayFull}"]:not([aria-disabled="true"])`,
    `[data-date="${dateStr}"]`,
    `[data-day="${dateStr}"]`,
    `[aria-label*="Available"][aria-label*="${monthDay}"]`,
    `td:not([aria-disabled="true"]) button:has-text("${dayNum}")`,
    `[role="gridcell"]:not([aria-disabled="true"]) button:has-text("${dayNum}")`,
  ]
  for (const sel of selectors) {
    try {
      const el = page.locator(sel).first()
      await el.waitFor({ state: 'visible', timeout: 1500 })
      await el.click()
      await page.waitForTimeout(200)
      return true
    } catch {}
  }

  const fallbacks = [
    `[aria-label*="${monthDay}"]`,
    `[aria-label*="${weekdayFull}"]`,
    `td button:has-text("${dayNum}")`,
    `[role="gridcell"] button:has-text("${dayNum}")`,
  ]
  for (const sel of fallbacks) {
    try {
      const el = page.locator(sel).first()
      await el.waitFor({ state: 'visible', timeout: 1000 })
      await el.click({ force: true })
      await page.waitForTimeout(200)
      console.log(`Cart: clicked date ${dateStr} via force-click fallback`)
      return true
    } catch {}
  }

  console.log(`Cart: could not click date ${dateStr}`)
  return false
}

export async function enterDates (page, firstDate, checkoutDate) {
  const ENTER_DATES = 'button:has-text("Enter Dates"), button:has-text("Change Dates")'
  const GRID_SEL = '[role="gridcell"], [role="grid"], td[aria-label]'

  const calendarOpen = await page.locator(GRID_SEL).first().isVisible().catch(() => false)
  if (calendarOpen) {
    const d = new Date(firstDate + 'T12:00:00Z')
    const monthDay = d.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
    const alreadySelected = await page.locator(
      `[aria-label*="${monthDay}"].is-selected, [aria-label*="${monthDay}"].is-range-start`
    ).first().isVisible().catch(() => false)

    if (alreadySelected) {
      console.log(`Cart: dates already pre-selected (${firstDate} → ${checkoutDate}) — closing picker via Escape`)
      await page.keyboard.press('Escape')
      await page.waitForTimeout(500)
      return DATES_ACCEPTED
    }
  }

  await page.locator(ENTER_DATES).first().click()
  const d0 = new Date(firstDate + 'T12:00:00Z')
  const firstMonthDay = d0.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
  await page.waitForSelector(`[aria-label*="${firstMonthDay}"]`, { timeout: 4000 }).catch(() => {
    return page.waitForSelector(GRID_SEL, { timeout: 2000 }).catch(() => {})
  })
  await page.waitForTimeout(400)

  const arrivalSel = `[aria-label*="${firstMonthDay}"]`
  const isDisabled = await page.locator(arrivalSel).first().getAttribute('aria-disabled').catch(() => null)
  if (isDisabled === 'true') {
    console.log(`Cart: arrival date ${firstDate} is aria-disabled — trying React fiber click`)
    const fiberClicked = await page.evaluate((monthDay) => {
      const cells = [...document.querySelectorAll(`[aria-label*="${monthDay}"]`)]
      for (const cell of cells) {
        const fk = Object.keys(cell).find(k => k.startsWith('__reactFiber') || k.startsWith('__reactInternalInstance'))
        if (!fk) continue
        let fiber = cell[fk]
        while (fiber) {
          const onClick = fiber.memoizedProps?.onClick
          if (onClick) {
            try {
              onClick({ type: 'click', target: cell, currentTarget: cell, preventDefault: () => {}, stopPropagation: () => {}, nativeEvent: {} })
              return true
            } catch { return false }
          }
          fiber = fiber.return
        }
      }
      return false
    }, firstMonthDay)

    if (fiberClicked) {
      console.log(`Cart: React fiber click succeeded for ${firstDate}`)
      await page.waitForTimeout(500)
      await clickCalendarDate(page, checkoutDate)
      await page.locator('button:has-text("Done"), button:has-text("Apply"), button:has-text("Search"), button:has-text("Check Availability")')
        .first().click({ timeout: 2000 }).catch(() => {})
      // A fiber click fires the handler directly, so "it did not throw" says
      // nothing about whether the calendar accepted the date. Only the
      // rendered selection does.
      if (await dateSelectionAccepted(page, firstMonthDay)) return DATES_ACCEPTED
      console.log(`Cart: fiber click for ${firstDate} did not take — the date is genuinely not offered`)
      return DATES_REFUSED
    }

    // aria-disabled and no handler to call: rec.gov means it.
    console.log(`Cart: arrival date ${firstDate} is not selectable and the fiber fallback found no handler`)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    return DATES_REFUSED
  }

  await clickCalendarDate(page, firstDate)
  await clickCalendarDate(page, checkoutDate)
  await page.locator('button:has-text("Done"), button:has-text("Apply"), button:has-text("Search"), button:has-text("Check Availability")')
    .first().click({ timeout: 2000 }).catch(() => {})
  return DATES_ACCEPTED
}

/** Whether the calendar is now rendering that date as the chosen arrival. */
export async function dateSelectionAccepted (page, monthDay) {
  return page
    .locator(`[aria-label*="${monthDay}"].is-selected, [aria-label*="${monthDay}"].is-range-start`)
    .first()
    .isVisible()
    .catch(() => false)
}

/** What the date picker did with the requested range. */
export const DATES_ACCEPTED = 'dates_accepted'
export const DATES_REFUSED = 'dates_refused'

const RESERVE_SELECTORS = [
  'button:has-text("Add to Cart")',
  'button:has-text("Reserve")',
  'button:has-text("Reserve Now")',
  'button:has-text("Book Now")',
  '[data-testid="add-to-cart-button"]',
  '.rec-button-primary:has-text("Add to Cart")',
  '.rec-button-primary:has-text("Reserve")',
]
const RESERVE_COMBINED = RESERVE_SELECTORS.join(', ')
const CONFIRMATION_BUTTON_LABELS = ['Continue', 'Confirm', 'Book Now', 'Next']
const CONFIRMATION_SELECTORS = CONFIRMATION_BUTTON_LABELS.map(label => `button:has-text("${label}")`)
const CONFIRMATION_ENABLED_SELECTORS = CONFIRMATION_BUTTON_LABELS.map(label => `button:enabled:has-text("${label}")`)
const CONFIRMATION_COMBINED = CONFIRMATION_SELECTORS.join(', ')
const CONFIRMATION_ENABLED_COMBINED = CONFIRMATION_ENABLED_SELECTORS.join(', ')
const ENTER_DATES_SEL = 'button:has-text("Enter Dates"), button:has-text("Change Dates")'
const CART_VERIFY_WAIT_MS = 10_000
const CART_VERIFY_POLL_MS = 1_000
const CONFIRMATION_WAIT_MS = 3_000
const CONFIRMATION_ENABLED_WAIT_MS = 5_000
const CONFIRMATION_CLICK_TIMEOUT_MS = 3_000
const POST_CONFIRMATION_CLICK_SETTLE_MS = 2_000
const CONFIRMATION_DIAGNOSTIC_LIMIT = 8
const CONFIRMATION_TEXT_LOG_LIMIT = 80
const ATC_SCREENSHOT_OPERATION = 'atc'
const ATC_SCREENSHOT_LABEL_MAX_CHARS = 60
const CART_URL_CAMPSITE_ID_RE = /\/camping\/campsites\/([^/?#]+)/
const CART_VERIFY_MATCHED = 'matched'
const CART_VERIFY_FETCH_FAILED = 'cart_fetch_failed'
const CART_VERIFY_HTTP_ERROR = 'cart_http_error'
const CART_VERIFY_EMPTY = 'cart_empty'
const CART_VERIFY_MISSING_ITEM = 'missing_expected_item'
const ERROR_RECGOV_NOT_AUTHENTICATED = 'recgov_not_authenticated'
const ERROR_RECGOV_LOGIN_FAILED = 'recgov_login_failed'
const ERROR_RECGOV_REFRESH_FAILED = 'recgov_refresh_failed'
const ERROR_RECGOV_SPA_LOGGED_OUT = 'recgov_spa_logged_out'
const ERROR_RECGOV_CONFIRMATION_DISABLED = 'recgov_confirmation_disabled'
// Two ways an add-to-cart can miss that `cart_not_added` used to blur together.
// The user can act on the difference: one means the site is not offered for
// those dates at all, the other means we tried to hold it and could not.
const ERROR_RECGOV_DATES_NOT_OFFERED = 'recgov_dates_not_offered'
const ERROR_RECGOV_NO_RESERVE_BUTTON = 'recgov_no_reserve_button'
const DATES_NOT_OFFERED_DETAIL =
  'Recreation.gov did not offer the requested arrival date for this site — its calendar refused the selection.'
const NO_RESERVE_BUTTON_DETAIL =
  'Recreation.gov showed the site but offered no Reserve or Add to Cart control for those dates.'
const HEADLESS_NO_SESSION_DETAIL =
  'This profile has no Recreation.gov session — test login in Settings, or run the companion headed to log in once.'
const HEADED_NO_SESSION_DETAIL =
  'No Recreation.gov browser session is available in the companion profile.'
const LOGIN_FAILED_DETAIL =
  'Recreation.gov credential login did not produce a browser session.'
/**
 * What to say about the specific thing that blocked the login.
 *
 * The single detail line used to end "...submit a current MFA code on the
 * companion root page" for EVERY reason, so a captcha — which no code can
 * clear — told the operator to go type a code.
 */
const LOGIN_BLOCKER_DETAIL = {
  captcha_required:
    'Recreation.gov presented a challenge the companion cannot solve. Retrying often passes; otherwise log in headed once.',
  mfa_required: 'Recreation.gov asked for a verification code. Submit a current one on the companion root page.',
  mfa_invalid: 'Recreation.gov rejected the verification code. Start the login again to get a new one.',
  login_link_not_found: 'The Recreation.gov login control was not found on the page — the site layout may have changed.',
}
const REFRESH_FAILED_DETAIL =
  'Recreation.gov browser session refresh failed. The stored session may be expired or rejected.'
const SPA_LOGGED_OUT_DETAIL =
  'Recreation.gov rejected the companion browser session after page load; the SPA still shows a logged-out state.'
const CONFIRMATION_DISABLED_DETAIL =
  'Recreation.gov showed an add-to-cart confirmation step, but no confirmation button became enabled and the requested campsite/date was not found in the cart.'
const LOGIN_ON_HOST_ACTION =
  'Run make recgov-login on the host profile mounted by the companion, or start the companion headed and log in once.'
const LOGIN_ON_COMPANION_ACTION =
  'Open the companion root page and submit the Recreation.gov username, password, and MFA code when required.'
const INSPECT_COMPANION_ACTION =
  'Open the booking URL in a headed companion session to inspect the Recreation.gov confirmation step; the site may require extra input, reject the date/site, or present a challenge.'

export function cartHoldCompletionObserved (responses) {
  return responses.some(e => e.status >= 200 && e.status < 300 &&
    (/\/api\/camps\/reservations\?id=/.test(e.path) || /\/api\/cart\/buy-now/.test(e.path)))
}

export function cartContainsMatch (cart, match) {
  return verifyCartContainsMatch(cart, match).ok
}

export function verifyCartContainsMatch (cart, match) {
  const reservations = Array.isArray(cart?.reservations)
    ? cart.reservations
    : Array.isArray(cart?.body?.reservations) ? cart.body.reservations : []

  const base = {
    status: cart?.status ?? null,
    reservation_count: reservations.length,
    expected: expectedCartMatch(match),
  }

  if (cart?.error) {
    return { ok: false, reason: CART_VERIFY_FETCH_FAILED, detail: cart.error, ...base }
  }
  if (Number.isFinite(cart?.status) && (cart.status < 200 || cart.status >= 300)) {
    return { ok: false, reason: CART_VERIFY_HTTP_ERROR, ...base }
  }
  if (!reservations.length) {
    return { ok: false, reason: CART_VERIFY_EMPTY, ...base }
  }

  let bestMatch = null
  for (let index = 0; index < reservations.length; index++) {
    const result = cartReservationMatchResult(reservations[index], base.expected)
    if (result.ok) {
      return {
        ok: true,
        reason: CART_VERIFY_MATCHED,
        reservation_index: index,
        matched: result.matched,
        ...base,
      }
    }
    if (!bestMatch || result.score > bestMatch.score) {
      bestMatch = { reservation_index: index, score: result.score, matched: result.matched }
    }
  }

  return { ok: false, reason: CART_VERIFY_MISSING_ITEM, best_match: bestMatch, ...base }
}

export function recgovAuthenticationFailure ({
  headless = IS_HEADLESS,
  attemptedLogin = false,
  attemptedRefresh = false,
  reason = null,
} = {}) {
  if (attemptedLogin) {
    const blocker = LOGIN_BLOCKER_DETAIL[reason]
    return {
      error: ERROR_RECGOV_LOGIN_FAILED,
      detail: blocker ? `${LOGIN_FAILED_DETAIL} ${blocker}` : LOGIN_FAILED_DETAIL,
      corrective_action: LOGIN_ON_COMPANION_ACTION,
      auth: authFields(headless),
    }
  }

  if (attemptedRefresh) {
    return {
      error: ERROR_RECGOV_REFRESH_FAILED,
      detail: REFRESH_FAILED_DETAIL,
      corrective_action: LOGIN_ON_COMPANION_ACTION,
      auth: authFields(headless),
    }
  }

  if (headless) {
    return {
      error: ERROR_RECGOV_NOT_AUTHENTICATED,
      detail: HEADLESS_NO_SESSION_DETAIL,
      corrective_action: LOGIN_ON_COMPANION_ACTION,
      auth: authFields(headless),
    }
  }

  return {
    error: ERROR_RECGOV_NOT_AUTHENTICATED,
    detail: HEADED_NO_SESSION_DETAIL,
    corrective_action: LOGIN_ON_HOST_ACTION,
    auth: authFields(headless),
  }
}

function authFields (headless) {
  return {
    headless,
  }
}

function spaLoggedOutFailure () {
  return {
    error: ERROR_RECGOV_SPA_LOGGED_OUT,
    detail: SPA_LOGGED_OUT_DETAIL,
    corrective_action: LOGIN_ON_HOST_ACTION,
  }
}

function confirmationDisabledFailure (cartCheck, confirmation) {
  return {
    error: ERROR_RECGOV_CONFIRMATION_DISABLED,
    detail: CONFIRMATION_DISABLED_DETAIL,
    corrective_action: INSPECT_COMPANION_ACTION,
    cart_check: {
      ...cartCheck,
      action_failure: confirmation,
    },
  }
}

function cartReservationMatchResult (reservation, expected) {
  const values = primitiveStrings(reservation)
  if (!values.length) return { ok: false, score: 0, matched: {} }

  const campsiteId = firstMatchingToken(values, expected.campsite_ids)
  const campsiteLabel = firstMatchingToken(values, expected.campsite_labels)
  const campgroundId = firstMatchingToken(values, expected.campground_ids)
  const arrivalDate = firstMatchingDate(values, expected.arrival_date)
  const checkoutDate = firstMatchingDate(values, expected.checkout_date)
  const campsiteMatched = campsiteId || (campsiteLabel && campgroundId)
  const score = [campsiteMatched, arrivalDate, checkoutDate].filter(Boolean).length

  return {
    ok: Boolean(campsiteMatched && arrivalDate && checkoutDate),
    score,
    matched: {
      campsite_id: campsiteId,
      campsite_label: campsiteLabel,
      campground_id: campgroundId,
      arrival_date: arrivalDate,
      checkout_date: checkoutDate,
    },
  }
}

function expectedCartMatch (match) {
  const bookingUrlCampsiteId = String(match.booking_url || '').match(CART_URL_CAMPSITE_ID_RE)?.[1]
  const preferredIds = compactStrings([
    match.provider_campsite_id,
    match.vendor_id,
    bookingUrlCampsiteId,
  ])
  const campsiteIds = preferredIds.length ? preferredIds : compactStrings([match.campsite_id])

  return {
    campsite_ids: campsiteIds,
    campsite_labels: compactStrings([match.campsite_site]),
    campground_ids: compactStrings([match.provider_campground_id, match.campground_id]),
    arrival_date: normalizeNeedle(match.first_date),
    checkout_date: normalizeNeedle(match.checkout_date || checkoutDateForMatch(match)),
  }
}

function checkoutDateForMatch (match) {
  const availableDates = match.available_dates || (match.first_date ? [match.first_date] : [])
  const lastNight = availableDates[availableDates.length - 1]
  return lastNight ? toCheckoutDate(lastNight) : null
}

function compactStrings (values) {
  return [...new Set(values.map(value => normalizeNeedle(value)).filter(Boolean))]
}

function primitiveStrings (value, seen = new Set(), out = []) {
  if (value === null || value === undefined) return out
  if (typeof value === 'object') {
    if (seen.has(value)) return out
    seen.add(value)
    const children = Array.isArray(value) ? value : Object.values(value)
    for (const child of children) primitiveStrings(child, seen, out)
    return out
  }
  out.push(String(value).toLowerCase())
  return out
}

function firstMatchingDate (values, date) {
  const expected = normalizeNeedle(date)
  if (!expected) return null
  return values.some(value => value.includes(expected)) ? expected : null
}

function firstMatchingToken (values, needles) {
  return needles.find(needle => {
    const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const tokenRe = new RegExp(`(^|[^a-z0-9])${escaped}([^a-z0-9]|$)`, 'i')
    return values.some(value => value === needle || tokenRe.test(value))
  }) || null
}

function normalizeNeedle (value) {
  if (value === null || value === undefined) return ''
  return String(value).trim().toLowerCase()
}

async function waitForRequestedCartItem (page, responses, match, getCart = () => getCartItems(page)) {
  const deadline = Date.now() + CART_VERIFY_WAIT_MS
  let lastCheck = null
  while (Date.now() < deadline) {
    const cart = await getCart()
    lastCheck = verifyCartContainsMatch(cart, match)
    if (lastCheck.ok) {
      console.log(`Cart: verified requested campsite/date in cart ${cartVerificationSummary(lastCheck)}`)
      return { ok: true, check: lastCheck }
    }
    await page.waitForTimeout(Math.min(CART_VERIFY_POLL_MS, deadline - Date.now()))
  }

  const responseSignal = cartHoldCompletionObserved(responses)
  const check = { ...lastCheck, response_signal: responseSignal }
  console.log(`Cart: verification failed ${cartVerificationSummary(check)}`)
  return { ok: false, check }
}

function cartVerificationSummary (check) {
  const expected = check?.expected || {}
  const fields = [
    `reason=${check?.reason || 'unknown'}`,
    `status=${check?.status ?? '?'}`,
    `reservations=${check?.reservation_count ?? '?'}`,
    `response_signal=${check?.response_signal ?? '?'}`,
    `campsite_ids="${expected.campsite_ids?.join(',') || ''}"`,
    `site_labels="${expected.campsite_labels?.join(',') || ''}"`,
    `campground_ids="${expected.campground_ids?.join(',') || ''}"`,
    `arrival=${expected.arrival_date || '?'}`,
    `checkout=${expected.checkout_date || '?'}`,
  ]
  if (check?.best_match) {
    fields.push(`best_score=${check.best_match.score}`)
    fields.push(`best_index=${check.best_match.reservation_index}`)
    fields.push(`best_campsite_id=${check.best_match.matched?.campsite_id || '?'}`)
    fields.push(`best_arrival=${check.best_match.matched?.arrival_date || '?'}`)
    fields.push(`best_checkout=${check.best_match.matched?.checkout_date || '?'}`)
  }
  return fields.join(' ')
}

export async function clickReserveButton (page, { screenshots = null } = {}) {
  for (const sel of RESERVE_SELECTORS) {
    if (!await page.locator(sel).first().isVisible().catch(() => false)) continue

    console.log(`Cart: clicking "${sel}"`)
    await page.locator(sel).first().click()
    await page.waitForTimeout(2000)
    await screenshots?.capture('reserve-click')

    const loginModal = await page.locator(
      'button:has-text("Sign In"), button:has-text("Log In"), [data-testid="login-modal"]'
    ).first().isVisible().catch(() => false)
    if (loginModal) {
      console.log('Cart: login modal appeared after ATC click — SPA still considers user logged out')
      await screenshots?.capture('login-modal-after-reserve-click')
      return {
        clicked: false,
        failure: spaLoggedOutFailure(),
      }
    }

    const confirmation = await clickConfirmationButton(page, { screenshots })

    return { clicked: true, confirmation }
  }
  return { clicked: false }
}

async function clickConfirmationButton (page, { screenshots = null } = {}) {
  await page.waitForSelector(CONFIRMATION_COMBINED, { timeout: CONFIRMATION_WAIT_MS }).catch(() => {})
  const candidates = await confirmationButtonCandidates(page)
  const visibleCandidates = candidates.filter(candidate => candidate.visible)
  if (!visibleCandidates.length) return { seen: false }

  await page.waitForSelector(CONFIRMATION_ENABLED_COMBINED, { timeout: CONFIRMATION_ENABLED_WAIT_MS }).catch(() => {})
  const enabled = await firstEnabledConfirmationButton(page)
  if (!enabled) {
    console.log(`Cart: confirmation buttons visible but none enabled — ${formatConfirmationCandidates(visibleCandidates)}`)
    await screenshots?.capture('confirmation-disabled')
    return {
      seen: true,
      clicked: false,
      reason: 'confirmation_disabled',
      candidates: visibleCandidates,
    }
  }

  console.log(`Cart: clicking confirmation button text="${enabled.text || '?'}" index=${enabled.index}`)
  await enabled.button.click({ timeout: CONFIRMATION_CLICK_TIMEOUT_MS })
  await page.waitForTimeout(POST_CONFIRMATION_CLICK_SETTLE_MS)
  await screenshots?.capture('confirmation-click')
  return {
    seen: true,
    clicked: true,
    index: enabled.index,
    text: enabled.text,
  }
}

async function firstEnabledConfirmationButton (page) {
  const locator = page.locator(CONFIRMATION_COMBINED)
  const count = Math.min(await locator.count().catch(() => 0), CONFIRMATION_DIAGNOSTIC_LIMIT)
  for (let index = 0; index < count; index++) {
    const button = locator.nth(index)
    if (!await button.isVisible().catch(() => false)) continue
    if (!await button.isEnabled().catch(() => false)) continue
    return {
      button,
      index,
      text: await normalizedLocatorText(button),
    }
  }
  return null
}

async function confirmationButtonCandidates (page) {
  const locator = page.locator(CONFIRMATION_COMBINED)
  const count = Math.min(await locator.count().catch(() => 0), CONFIRMATION_DIAGNOSTIC_LIMIT)
  const candidates = []
  for (let index = 0; index < count; index++) {
    const button = locator.nth(index)
    const visible = await button.isVisible().catch(() => false)
    const enabled = visible && await button.isEnabled().catch(() => false)
    candidates.push({
      index,
      text: await normalizedLocatorText(button),
      visible,
      enabled,
      disabled: visible ? !enabled : null,
      aria_disabled: await button.getAttribute('aria-disabled').catch(() => null),
    })
  }
  return candidates
}

async function normalizedLocatorText (locator) {
  const text = await locator.innerText().catch(() => '')
  return truncateText(text.replace(/\s+/g, ' ').trim(), CONFIRMATION_TEXT_LOG_LIMIT)
}

function formatConfirmationCandidates (candidates) {
  return candidates
    .map(candidate => `#${candidate.index} "${candidate.text || '?'}" enabled=${candidate.enabled} aria_disabled=${candidate.aria_disabled ?? '?'}`)
    .join('; ')
}

function truncateText (value, maxLength) {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength)}...`
}

export async function setupAuthPage ({
  getContextFn = getContext,
  profileId = null,
  resolveRecaccountFn = resolveRecaccount,
} = {}) {
  const context = await getContextFn()
  await injectStoredCookies(context, null, profileId)
  const page = await context.newPage()

  // Never the manual-login wait: nobody watches an ATC, so parking the request
  // for RECGOV_LOGIN_TIMEOUT_MS holds the profile lock and loses the hold
  // anyway — and /atc is traced, so a password typed into that window lands in
  // the trace past the COMPANION_TRACE_LOGIN gate. Fail fast instead; sessions
  // get minted headed by `make recgov-login`.
  const recaccount = await resolveRecaccountFn(page, { allowManualLogin: false })
  const authFailure = recaccount ? null : recgovAuthenticationFailure()
  if (recaccount) {
    console.log(`Cart: session ready (expires ${recaccount.expiration})`)
  } else {
    console.log('Cart: no Recreation.gov login session available — cannot add to cart')
    console.log(`Cart: corrective action — ${authFailure.corrective_action}`)
  }

  if (recaccount) await injectRecaccount(page, recaccount)
  await injectBearerRoute(page, recaccount?.access_token)
  await injectFingerprintCookie(context, recaccount?.access_token)

  return { context, page, recaccount, authFailure }
}

const CAPTCHA_SELECTORS = [
  '#px-captcha',
  '.px-captcha-container',
  '#akam-sc-modal',
  '#akam-sc-overlay',
  'iframe[src*="captcha"]',
  'iframe[src*="challenge"]',
  'iframe[src*="px-captcha"]',
  '[id*="px-captcha"]',
].join(', ')

async function waitForCaptchaIfPresent (page, solveTimeout = 90000) {
  const appeared = await page.waitForSelector(CAPTCHA_SELECTORS, { timeout: 1500 })
    .then(() => true).catch(() => false)

  if (!appeared) return false

  if (IS_HEADLESS) {
    console.log('Cart: captcha detected in headless mode — cannot solve automatically, proceeding anyway')
    return true
  }

  console.log('Cart: captcha detected — waiting up to 90s for manual solve in browser window…')
  await page.waitForSelector(CAPTCHA_SELECTORS, { state: 'hidden', timeout: solveTimeout })
    .catch(() => console.log('Cart: captcha wait timed out — proceeding'))
  console.log('Cart: captcha cleared, resuming')
  return true
}

function createAtcScreenshotCollector (page) {
  const screenshots = []
  return {
    screenshots,
    async capture (label) {
      const capturedAt = new Date().toISOString()
      // Named with its profile so `POST /destroy` erases it with the rest of
      // the profile: these are pictures of that user's signed-in cart.
      const filename = `${diagnosticArtifactName(
        ATC_SCREENSHOT_OPERATION,
        sanitizeScreenshotLabel(label),
        { profileId: activeProfileId(), capturedAt },
      )}.png`
      const screenshot = {
        label,
        captured_at: capturedAt,
        page_url: safePageUrl(page),
        screenshot_url: `${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/${filename}`,
      }
      try {
        await fs.mkdir(RECGOV_DIAGNOSTIC_DIR, { recursive: true })
        await captureRecgovPageImage(page, { path: path.join(RECGOV_DIAGNOSTIC_DIR, filename) })
        console.log(`Cart: captured screenshot label=${label} url=${screenshot.screenshot_url}`)
      } catch (error) {
        screenshot.screenshot_error = error.message
        console.log(`Cart: screenshot capture failed label=${label} error="${truncateText(error.message, CONFIRMATION_TEXT_LOG_LIMIT)}"`)
      }
      screenshots.push(screenshot)
      return screenshot
    },
  }
}

function withScreenshots (result, screenshots) {
  return {
    ...result,
    screenshots: screenshots?.screenshots || [],
  }
}

function sanitizeScreenshotLabel (value) {
  return String(value || 'step')
    .replace(/[^a-z0-9_-]+/gi, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, ATC_SCREENSHOT_LABEL_MAX_CHARS)
    .toLowerCase() || 'step'
}

function safePageUrl (page) {
  try {
    return page.url()
  } catch {
    return null
  }
}

// Adds a match to the cart on rec.gov. Returns true if the cart now has the reservation.
// `match` is the backend Match shape (snake_case fields from /api/campsite/matches).
export function bookingUrlForMatch (match) {
  const firstDate = match.first_date
  const availableDates = match.available_dates || (firstDate ? [firstDate] : [])
  const checkout = match.checkout_date || toCheckoutDate(availableDates[availableDates.length - 1])
  const campsiteId = match.provider_campsite_id || match.vendor_id || match.campsite_id
  return match.booking_url || (
    campsiteId
      ? campsiteUrl(campsiteId, firstDate, checkout)
      : reservationUrl(match.campground_id, firstDate, checkout)
  )
}

export async function addToCart (match, contextOptions = {}) {
  return withRecgovProfileScope(contextOptions.profileId ?? null, () => runAddToCart(match, contextOptions))
}

async function runAddToCart (match, contextOptions) {
  const firstDate = match.first_date
  const availableDates = match.available_dates || (firstDate ? [firstDate] : [])
  const site = match.campsite_site
  const checkout = match.checkout_date || toCheckoutDate(availableDates[availableDates.length - 1])
  const url = bookingUrlForMatch(match)
  console.log(`Cart: opening ${url}`)

  const { page, recaccount, authFailure } = await setupAuthPage(contextOptions)
  const screenshots = createAtcScreenshotCollector(page)
  await screenshots.capture(recaccount ? 'auth-session-ready' : 'auth-session-missing')
  if (!recaccount) return withScreenshots({ ok: false, page, ...authFailure }, screenshots)

  const captured = []
  let cartVerificationRequests = 0
  const getCartForVerification = async () => {
    cartVerificationRequests += 1
    try {
      return await getCartItems(page)
    } finally {
      cartVerificationRequests -= 1
    }
  }
  page.on('response', r => {
    if (/api.*(cart|reserv|booking|order)/i.test(r.url())) {
      if (cartVerificationRequests > 0 && r.url().includes('/api/cart/shoppingcart')) return
      const path = r.url().replace('https://www.recreation.gov', '').slice(0, 80)
      const entry = { status: r.status(), path, line: `${r.status()} ${path}` }
      captured.push(entry)
    }
  })

  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await waitForCaptchaIfPresent(page)
    await screenshots.capture('opened-booking-url')

    const signInSel = 'button:has-text("Sign Up / Log In"), a:has-text("Sign Up / Log In")'
    await page.waitForSelector(
      `${RESERVE_COMBINED}, ${ENTER_DATES_SEL}, ${signInSel}`,
      { timeout: 12000 }
    ).catch(() => {})

    if (await page.locator(signInSel).first().isVisible().catch(() => false)) {
      console.log('Cart: SPA shows logged-out state — cannot add to cart')
      await screenshots.capture('spa-logged-out')
      return withScreenshots({
        ok: false,
        page,
        ...spaLoggedOutFailure(),
      }, screenshots)
    }
    console.log('Cart: SPA header does not show logged-out CTA')
    await screenshots.capture('spa-logged-in')

    const reserveClick = await clickReserveButton(page, { screenshots })
    if (reserveClick.failure) return withScreenshots({ ok: false, page, ...reserveClick.failure }, screenshots)
    if (reserveClick.clicked) {
      await waitForCaptchaIfPresent(page)
      console.log('Cart: verifying requested campsite/date in cart after reserve click')
      const verification = await waitForRequestedCartItem(page, captured, match, getCartForVerification)
      await screenshots.capture('cart-verification-after-reserve-click')
      if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
      if (!verification.ok && reserveClick.confirmation?.reason === 'confirmation_disabled') {
        return withScreenshots({ ok: false, page, ...confirmationDisabledFailure(verification.check, reserveClick.confirmation) }, screenshots)
      }
      return withScreenshots({ ok: verification.ok, page, cart_check: verification.check }, screenshots)
    }

    if (await page.locator(ENTER_DATES_SEL).first().isVisible().catch(() => false)) {
      const dateStatus = await enterDates(page, firstDate, checkout)
      await screenshots.capture('dates-entered')
      // Fail here rather than spending the rest of the budget waiting for a
      // Reserve button that cannot appear for dates the site will not accept.
      if (dateStatus === DATES_REFUSED) {
        console.log(`Cart: site ${site} does not offer ${firstDate} → ${checkout}`)
        await screenshots.capture('dates-not-offered')
        return withScreenshots({
          ok: false,
          page,
          error: ERROR_RECGOV_DATES_NOT_OFFERED,
          detail: DATES_NOT_OFFERED_DETAIL,
        }, screenshots)
      }
      await page.waitForSelector(RESERVE_COMBINED, { timeout: 12000 }).catch(() => {})
      const datedReserveClick = await clickReserveButton(page, { screenshots })
      if (datedReserveClick.failure) return withScreenshots({ ok: false, page, ...datedReserveClick.failure }, screenshots)
      if (datedReserveClick.clicked) {
        await waitForCaptchaIfPresent(page)
        console.log('Cart: verifying requested campsite/date in cart after dated reserve click')
        const verification = await waitForRequestedCartItem(page, captured, match, getCartForVerification)
        await screenshots.capture('cart-verification-after-dated-reserve-click')
        if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
        if (!verification.ok && datedReserveClick.confirmation?.reason === 'confirmation_disabled') {
          return withScreenshots({ ok: false, page, ...confirmationDisabledFailure(verification.check, datedReserveClick.confirmation) }, screenshots)
        }
        return withScreenshots({ ok: verification.ok, page, cart_check: verification.check }, screenshots)
      }
    }

    const btns = await page.locator('button:visible').allTextContents().catch(() => [])
    const btnStr = btns.map(t => t.trim()).filter(Boolean).join(', ')

    if (btnStr.includes('Unavailable')) {
      console.log(`Cart: site ${site} shows Unavailable (Akamai/headless or booking window closed) — skipping`)
    } else {
      console.log(`Cart: no Reserve button for Site ${site} — buttons: [${btnStr}]`)
    }
    await screenshots.capture('no-reserve-button')
    // Distinct from `cart_not_added`: nothing was ever attempted, because
    // rec.gov offered no control to attempt it with.
    return withScreenshots({
      ok: false,
      page,
      error: ERROR_RECGOV_NO_RESERVE_BUTTON,
      detail: NO_RESERVE_BUTTON_DETAIL,
    }, screenshots)
  } catch (err) {
    console.error('Cart automation error:', err.message)
    await screenshots.capture('automation-error')
    return withScreenshots({ ok: false, page }, screenshots)
  }
}

/** Names the persist log line for the CLI/refresh auth probe. */
const OPERATION_TEST_CHROMIUM = 'test-chromium'

export async function testChromium (rawCookieInput = null, options = {}) {
  return withRecgovProfileScope(options.profileId ?? null, () => runTestChromium(rawCookieInput, options))
}

async function runTestChromium (rawCookieInput, options) {
  const {
    getContextFn = getContext,
    injectStoredCookiesFn = injectStoredCookies,
    persistProfileCookiesFn = persistProfileCookies,
    resolveRecaccountFn = resolveRecaccount,
    clearBrowserRecaccountFn = clearBrowserRecaccount,
    injectRecaccountFn = injectRecaccount,
    injectBearerRouteFn = injectBearerRoute,
    isSpaLoggedInFn = isSpaLoggedIn,
    ...resolveOptions
  } = options

  const profileId = resolveOptions.profileId ?? null
  const context = await getContextFn()
  await injectStoredCookiesFn(context, rawCookieInput, profileId)
  let page = await context.newPage()
  try {
    const first = await resolveAndVerifyRecgovSession(page, resolveOptions, {
      resolveRecaccountFn,
      injectRecaccountFn,
      injectBearerRouteFn,
      isSpaLoggedInFn,
    })
    if (first.loggedIn) {
      lastLoginState = true
      // The headed runbook mints a session on the host; without this it dies
      // with the browser and never reaches the container.
      await persistProfileCookiesFn(context, profileId, { operation: OPERATION_TEST_CHROMIUM })
      console.log(`Logged in to recreation.gov ✓ (token expires ${first.recaccount.expiration})`)
      return { ok: true, loggedIn: true }
    }
    if (!first.recaccount) {
      console.log('testChromium: no logged-in Recreation.gov browser session found')
      lastLoginState = false
      return { ok: true, loggedIn: false }
    }

    console.log('Companion browser recaccount injected but SPA still shows logged-out — token may have been rejected')
    console.log('Cart: clearing stale Recreation.gov browser session and retrying auth flow')
    await clearBrowserRecaccountFn(page)
    await page.close().catch(() => {})
    page = await context.newPage()

    const recovered = await resolveAndVerifyRecgovSession(page, recgovAuthRecoveryOptions(resolveOptions), {
      resolveRecaccountFn,
      injectRecaccountFn,
      injectBearerRouteFn,
      isSpaLoggedInFn,
    })
    lastLoginState = recovered.loggedIn
    if (recovered.loggedIn) {
      await persistProfileCookiesFn(context, profileId, { operation: OPERATION_TEST_CHROMIUM })
      console.log(`Logged in to recreation.gov ✓ (token expires ${recovered.recaccount.expiration})`)
    } else {
      console.log('testChromium: no logged-in Recreation.gov browser session found after fallback')
    }
    return { ok: true, loggedIn: recovered.loggedIn }
  } finally {
    await page.close().catch(() => {})
  }
}

async function resolveAndVerifyRecgovSession (page, options, helpers) {
  const recaccount = await helpers.resolveRecaccountFn(page, options)
  if (!recaccount) return { recaccount: null, loggedIn: false }

  await helpers.injectRecaccountFn(page, recaccount)
  await helpers.injectBearerRouteFn(page, recaccount.access_token)
  await page.goto(RECGOV_HOME_URL, { waitUntil: 'domcontentloaded', timeout: RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS })
  await page.waitForTimeout(RECGOV_LOGIN_STATE_SETTLE_MS)
  return {
    recaccount,
    loggedIn: (await helpers.isSpaLoggedInFn(page)) === true,
  }
}

function recgovAuthRecoveryOptions (options) {
  return {
    ...options,
    forceRefresh: false,
    allowManualLoginAfterRefreshFailure: true,
  }
}
