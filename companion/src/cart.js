// Add-to-cart orchestration. Owns Playwright-driven rec.gov interaction.
// Returns the browser result to the CLI/HTTP caller; the companion does not persist.

import {
  IS_HEADLESS,
  getContext,
  injectStoredCookies,
  injectFingerprintCookie,
  injectBearerRoute,
  injectRecaccount,
  isSpaLoggedIn,
  reservationUrl,
  campsiteUrl,
  toCheckoutDate,
} from './browser.js'
import {
  RECGOV_HOME_URL,
  RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS,
  RECGOV_LOGIN_STATE_SETTLE_MS,
  clearBrowserRecaccount,
  resolveRecaccount,
} from './recgovSession.js'

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

async function enterDates (page, firstDate, checkoutDate) {
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
      return
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
      return
    }

    console.log(`Cart: arrival date ${firstDate} fiber click failed — closing picker, relying on URL params`)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(800)
    return
  }

  await clickCalendarDate(page, firstDate)
  await clickCalendarDate(page, checkoutDate)
  await page.locator('button:has-text("Done"), button:has-text("Apply"), button:has-text("Search"), button:has-text("Check Availability")')
    .first().click({ timeout: 2000 }).catch(() => {})
}

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
const ENTER_DATES_SEL = 'button:has-text("Enter Dates"), button:has-text("Change Dates")'
const CART_VERIFY_WAIT_MS = 10_000
const CART_VERIFY_POLL_MS = 1_000
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
const HEADLESS_NO_SESSION_DETAIL =
  'No Recreation.gov browser session is available in the companion profile, and the headless companion is not logged in.'
const HEADED_NO_SESSION_DETAIL =
  'No Recreation.gov browser session is available in the companion profile.'
const LOGIN_FAILED_DETAIL =
  'Recreation.gov credential login did not produce a browser session. If Recreation.gov prompts for 2FA, submit a current MFA code on the companion /login form.'
const REFRESH_FAILED_DETAIL =
  'Recreation.gov browser session refresh failed. The stored session may be expired or rejected.'
const SPA_LOGGED_OUT_DETAIL =
  'Recreation.gov rejected the companion browser session after page load; the SPA still shows a logged-out state.'
const LOGIN_ON_HOST_ACTION =
  'Run make recgov-login on the host profile mounted by the companion, or start the companion headed and log in once.'
const LOGIN_ON_COMPANION_ACTION =
  'Open the companion /login page and submit the Recreation.gov username, password, and MFA code when required.'

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

export function recgovAuthenticationFailure ({ headless = IS_HEADLESS, attemptedLogin = false, attemptedRefresh = false } = {}) {
  if (attemptedLogin) {
    return {
      error: ERROR_RECGOV_LOGIN_FAILED,
      detail: LOGIN_FAILED_DETAIL,
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

export async function clickReserveButton (page) {
  for (const sel of RESERVE_SELECTORS) {
    if (!await page.locator(sel).first().isVisible().catch(() => false)) continue

    console.log(`Cart: clicking "${sel}"`)
    await page.locator(sel).first().click()
    await page.waitForTimeout(2000)

    const loginModal = await page.locator(
      'button:has-text("Sign In"), button:has-text("Log In"), [data-testid="login-modal"]'
    ).first().isVisible().catch(() => false)
    if (loginModal) {
      console.log('Cart: login modal appeared after ATC click — SPA still considers user logged out')
      return {
        clicked: false,
        failure: spaLoggedOutFailure(),
      }
    }

    const confirmSel = 'button:has-text("Continue"), button:has-text("Confirm"), button:has-text("Book Now"), button:has-text("Next")'
    await page.waitForSelector(confirmSel, { timeout: 3000 }).catch(() => {})
    const confirmBtn = page.locator(confirmSel).first()
    if (await confirmBtn.isVisible().catch(() => false)) {
      console.log('Cart: clicking confirmation overlay')
      await confirmBtn.waitFor({ state: 'enabled', timeout: 5000 }).catch(() => {})
      await confirmBtn.click({ timeout: 3000 }).catch(() => {
        console.log('Cart: confirmation button still disabled — proceeding (item may already be in cart)')
      })
      await page.waitForTimeout(2000)
    }

    return { clicked: true }
  }
  return { clicked: false }
}

export async function setupAuthPage () {
  const context = await getContext()
  await injectStoredCookies(context)
  const page = await context.newPage()

  const recaccount = await resolveRecaccount(page)
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

export async function addToCart (match) {
  const firstDate = match.first_date
  const availableDates = match.available_dates || (firstDate ? [firstDate] : [])
  const site = match.campsite_site
  const checkout = match.checkout_date || toCheckoutDate(availableDates[availableDates.length - 1])
  const url = bookingUrlForMatch(match)
  console.log(`Cart: opening ${url}`)

  const { page, recaccount, authFailure } = await setupAuthPage()
  if (!recaccount) return { ok: false, page, ...authFailure }

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

    const signInSel = 'button:has-text("Sign Up / Log In"), a:has-text("Sign Up / Log In")'
    await page.waitForSelector(
      `${RESERVE_COMBINED}, ${ENTER_DATES_SEL}, ${signInSel}`,
      { timeout: 12000 }
    ).catch(() => {})

    if (await page.locator(signInSel).first().isVisible().catch(() => false)) {
      console.log('Cart: SPA shows logged-out state — cannot add to cart')
      return {
        ok: false,
        page,
        ...spaLoggedOutFailure(),
      }
    }
    console.log('Cart: SPA logged-in ✓')

    const reserveClick = await clickReserveButton(page)
    if (reserveClick.failure) return { ok: false, page, ...reserveClick.failure }
    if (reserveClick.clicked) {
      await waitForCaptchaIfPresent(page)
      const verification = await waitForRequestedCartItem(page, captured, match, getCartForVerification)
      if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
      return { ok: verification.ok, page, cart_check: verification.check }
    }

    if (await page.locator(ENTER_DATES_SEL).first().isVisible().catch(() => false)) {
      await enterDates(page, firstDate, checkout)
      await page.waitForSelector(RESERVE_COMBINED, { timeout: 12000 }).catch(() => {})
      const datedReserveClick = await clickReserveButton(page)
      if (datedReserveClick.failure) return { ok: false, page, ...datedReserveClick.failure }
      if (datedReserveClick.clicked) {
        await waitForCaptchaIfPresent(page)
        const verification = await waitForRequestedCartItem(page, captured, match, getCartForVerification)
        if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
        return { ok: verification.ok, page, cart_check: verification.check }
      }
    }

    const btns = await page.locator('button:visible').allTextContents().catch(() => [])
    const btnStr = btns.map(t => t.trim()).filter(Boolean).join(', ')

    if (btnStr.includes('Unavailable')) {
      console.log(`Cart: site ${site} shows Unavailable (Akamai/headless or booking window closed) — skipping`)
    } else {
      console.log(`Cart: no Reserve button for Site ${site} — buttons: [${btnStr}]`)
    }
    return { ok: false, page }
  } catch (err) {
    console.error('Cart automation error:', err.message)
    return { ok: false, page }
  }
}

export async function testChromium (rawCookieInput = null, options = {}) {
  const {
    getContextFn = getContext,
    injectStoredCookiesFn = injectStoredCookies,
    resolveRecaccountFn = resolveRecaccount,
    clearBrowserRecaccountFn = clearBrowserRecaccount,
    injectRecaccountFn = injectRecaccount,
    injectBearerRouteFn = injectBearerRoute,
    isSpaLoggedInFn = isSpaLoggedIn,
    ...resolveOptions
  } = options

  const context = await getContextFn()
  await injectStoredCookiesFn(context, rawCookieInput)
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
