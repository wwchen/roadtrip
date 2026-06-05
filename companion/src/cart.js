// Add-to-cart orchestration. Owns Playwright-driven rec.gov interaction.
// Reports the result back to the backend; the backend persists, the companion does not.

import {
  jwtExpiry,
  refreshRecgovSession,
} from './auth.js'
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
import { getSetting, setSetting } from './store.js'

let lastLoginState = null
export function getLastLoginState () { return lastLoginState }

export async function getCartItems (page) {
  return page.evaluate(async () => {
    try {
      const resp = await fetch('https://www.recreation.gov/api/cart/shoppingcart', { credentials: 'include' })
      const body = await resp.json().catch(() => ({}))
      return { status: resp.status, reservations: body?.reservations ?? null }
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

async function clickReserveButton (page) {
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
      return false
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

    return true
  }
  return false
}

export async function setupAuthPage () {
  const context = await getContext()
  await injectStoredCookies(context)

  const storedToken = getSetting('recgov_token')
  const tokenMsLeft = storedToken ? (jwtExpiry(storedToken) ?? new Date(0)) - Date.now() : 0
  const needsRefresh = tokenMsLeft < 5 * 60 * 1000

  let recaccount = null
  if (needsRefresh) {
    recaccount = await refreshRecgovSession()
    if (recaccount) {
      console.log(`Cart: session refreshed (expires ${recaccount.expiration})`)
    } else {
      console.log('Cart: no refresh creds — proceeding without recaccount injection')
    }
  } else {
    console.log(`Cart: token fresh (${Math.round(tokenMsLeft / 60000)}m left) — skipping refresh`)
  }

  const page = await context.newPage()

  if (recaccount) await injectRecaccount(page, recaccount)
  await injectBearerRoute(page, recaccount?.access_token)
  await injectFingerprintCookie(context, recaccount?.access_token || getSetting('recgov_token'))

  return { context, page, recaccount }
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

// PATCH cart expiry — extends the hold window after a successful ATC.
// Without this the reservation expires after ~15 min; with it, calling every 5 min
// keeps the cart locked for hours.
export async function extendCartHold (page) {
  await page.evaluate(async () => {
    await fetch('https://www.recreation.gov/api/cart/shoppingcart/expiration', {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      credentials: 'include',
      body: '{}',
    }).catch(() => {})
  }).catch(() => {})
}

// Adds a match to the cart on rec.gov. Returns true if the cart now has the reservation.
// `match` is the backend Match shape (snake_case fields from /api/campsite/matches).
export async function addToCart (match) {
  const campgroundId = match.campground_id
  const campsiteId = match.campsite_id
  const firstDate = match.first_date
  const availableDates = match.available_dates || []
  const site = match.campsite_site
  const checkout = toCheckoutDate(availableDates[availableDates.length - 1])
  const url = campsiteId
    ? campsiteUrl(campsiteId, firstDate, checkout)
    : reservationUrl(campgroundId, firstDate, checkout)
  console.log(`Cart: opening ${url}`)

  const { page } = await setupAuthPage()

  const captured = []
  page.on('response', r => {
    if (/api.*(cart|reserv|booking|order)/i.test(r.url())) {
      const path = r.url().replace('https://www.recreation.gov', '').slice(0, 80)
      const entry = { status: r.status(), path, line: '' }
      captured.push(entry)
      r.json()
        .then(b => { entry.line = `${entry.status} ${path} → ${JSON.stringify(b).slice(0, 100)}` })
        .catch(() => { entry.line = `${entry.status} ${path}` })
    }
  })

  // Returns true if any captured cart/reservation API call returned 2xx.
  // Click-the-button succeeded does not mean the cart actually accepted —
  // 401 'bad fingerprint' / 4xx still leaves the SPA in a "Reserved!"
  // momentary state on some flows.
  const cartAccepted = () => captured.some(e => e.status >= 200 && e.status < 300 &&
    /\/api\/(cart|camps\/reservations)/.test(e.path))

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
      return { ok: false, page }
    }
    console.log('Cart: SPA logged-in ✓')

    if (await clickReserveButton(page)) {
      await waitForCaptchaIfPresent(page)
      await page.waitForTimeout(500)
      const ok = cartAccepted()
      if (ok) await extendCartHold(page)
      if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
      return { ok, page }
    }

    if (await page.locator(ENTER_DATES_SEL).first().isVisible().catch(() => false)) {
      await enterDates(page, firstDate, checkout)
      await page.waitForSelector(RESERVE_COMBINED, { timeout: 12000 }).catch(() => {})
      if (await clickReserveButton(page)) {
        await waitForCaptchaIfPresent(page)
        await page.waitForTimeout(500)
        const ok = cartAccepted()
        if (ok) await extendCartHold(page)
        if (captured.length) console.log(`Cart: API responses:\n  ${captured.map(e => e.line || `${e.status} ${e.path}`).join('\n  ')}`)
        return { ok, page }
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

export async function testChromium (rawCookieInput = null) {
  const context = await getContext()
  await injectStoredCookies(context, rawCookieInput)

  const recaccount = await refreshRecgovSession()
  if (recaccount) {
    const page = await context.newPage()
    try {
      await injectRecaccount(page, recaccount)
      await injectBearerRoute(page, recaccount.access_token)
      await page.goto('https://www.recreation.gov/', { waitUntil: 'domcontentloaded', timeout: 20000 })
      await page.waitForTimeout(2000)
      const loggedIn = (await isSpaLoggedIn(page)) === true
      lastLoginState = loggedIn
      if (loggedIn) console.log(`Logged in to recreation.gov ✓ (token expires ${recaccount.expiration})`)
      else console.log('Refresh succeeded but SPA still shows logged-out — recaccount may have been rejected')
      return { ok: true, loggedIn }
    } finally {
      await page.close().catch(() => {})
    }
  }

  console.log('testChromium: no refresh creds — waiting for SPA silent auth (6s)…')
  const page = await context.newPage()
  try {
    await injectBearerRoute(page)
    await page.goto('https://www.recreation.gov/', { waitUntil: 'domcontentloaded', timeout: 20000 })
    await page.waitForTimeout(6000)

    const captured = await page.evaluate(() => {
      const raw = localStorage.getItem('recaccount')
      if (!raw) return null
      try { return JSON.parse(raw) } catch { return null }
    })

    if (captured?.refresh_id && captured?.account?.account_id) {
      setSetting('recgov_refresh_creds', JSON.stringify({
        account_id: captured.account.account_id,
        refresh_id: captured.refresh_id,
      }))
      setSetting('recgov_token', captured.access_token)
      console.log(`testChromium: captured refresh_id from browser session (expires ${captured.expiration})`)
      lastLoginState = true
      return { ok: true, loggedIn: true }
    }

    const loggedIn = (await isSpaLoggedIn(page)) === true
    lastLoginState = loggedIn
    if (loggedIn) console.log('Logged in to recreation.gov ✓ (no refresh_id captured — session may not persist)')
    else console.log('Not logged in — browser session has no live Auth0 session; paste a fresh cURL in Settings')
    return { ok: true, loggedIn }
  } finally {
    await page.close().catch(() => {})
  }
}
