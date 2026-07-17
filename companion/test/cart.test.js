import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { COMPANION_USER_AGENT, resolveSessionDir } from '../src/browser.js'
import {
  bookingUrlForMatch,
  cartContainsMatch,
  cartHoldCompletionObserved,
  clickReserveButton,
  recgovAuthenticationFailure,
  testChromium,
  verifyCartContainsMatch,
} from '../src/cart.js'
import { parseRecaccount, recaccountNeedsRefresh } from '../src/recgovSession.js'

const TEST_NOW_MS = Date.parse('2026-07-15T20:00:00Z')
const FRESH_OFFSET_SECONDS = 60 * 60
const NEAR_EXPIRY_OFFSET_SECONDS = 60
const JWT_HEADER = { alg: 'none' }
const JWT_SIGNATURE = 'sig'
const MIN_CHROME_MAJOR_VERSION = 140

test('bookingUrlForMatch prefers explicit booking_url', () => {
  const url = 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16'

  assert.equal(
    bookingUrlForMatch({
      booking_url: url,
      campground_id: 16821,
      campsite_id: 131925,
      provider_campsite_id: '300',
      first_date: '2026-07-15',
      checkout_date: '2026-07-16',
    }),
    url,
  )
})

test('bookingUrlForMatch derives campsite URL from provider id before internal id', () => {
  assert.equal(
    bookingUrlForMatch({
      campground_id: 16821,
      campsite_id: 131925,
      provider_campsite_id: '300',
      first_date: '2026-07-15',
      checkout_date: '2026-07-16',
    }),
    'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
  )
})

test('parseRecaccount reads browser localStorage JSON and rejects garbage', () => {
  assert.equal(parseRecaccount('not-json'), null)
  assert.deepEqual(parseRecaccount('{"access_token":"jwt"}'), { access_token: 'jwt' })
})

test('recaccountNeedsRefresh uses the browser JWT expiration', () => {
  assert.equal(
    recaccountNeedsRefresh(
      { access_token: fakeJwt(TEST_NOW_MS, FRESH_OFFSET_SECONDS) },
      TEST_NOW_MS,
    ),
    false,
  )
  assert.equal(
    recaccountNeedsRefresh(
      { access_token: fakeJwt(TEST_NOW_MS, NEAR_EXPIRY_OFFSET_SECONDS) },
      TEST_NOW_MS,
    ),
    true,
  )
})

test('companion user agent stays on a current Chrome major', () => {
  const chromeMajor = Number(COMPANION_USER_AGENT.match(/Chrome\/(\d+)/)?.[1])

  assert.ok(Number.isFinite(chromeMajor))
  assert.ok(chromeMajor >= MIN_CHROME_MAJOR_VERSION)
})

test('resolveSessionDir uses mounted companion profile env before legacy session dir', () => {
  assert.equal(
    resolveSessionDir(
      {
        COMPANION_BROWSER_PROFILE: '/var/lib/campsite-companion/browser-session',
        SESSION_DIR: '/legacy/session',
      },
      '/home/test',
    ),
    '/var/lib/campsite-companion/browser-session',
  )
  assert.equal(resolveSessionDir({ SESSION_DIR: '/legacy/session' }, '/home/test'), '/legacy/session')
  assert.equal(resolveSessionDir({}, '/home/test'), '/home/test/.campsite-companion/browser-session')
})

test('recgovAuthenticationFailure reports the operator action for headless missing session', () => {
  const failure = recgovAuthenticationFailure({
    headless: true,
  })

  assert.equal(failure.error, 'recgov_not_authenticated')
  assert.match(failure.detail, /headless companion is not logged in/)
  assert.match(failure.corrective_action, /companion root page/)
  assert.deepEqual(failure.auth, { headless: true })
})

test('recgovAuthenticationFailure reports failed login attempts distinctly', () => {
  const failure = recgovAuthenticationFailure({
    headless: true,
    attemptedLogin: true,
  })

  assert.equal(failure.error, 'recgov_login_failed')
  assert.match(failure.detail, /MFA code/)
  assert.match(failure.corrective_action, /companion root page/)
})

test('cartHoldCompletionObserved ignores pre-confirmation cart and multi responses', () => {
  assert.equal(
    cartHoldCompletionObserved([
      api(200, '/api/cart/shoppingcart/header'),
      api(200, '/api/cart/shoppingcart/header'),
      api(200, '/api/camps/reservations/campgrounds/10083845/multi'),
      api(200, '/api/cart/shoppingcart'),
    ]),
    false,
  )
})

test('cartHoldCompletionObserved accepts reservation detail or buy-now responses', () => {
  assert.equal(
    cartHoldCompletionObserved([
      api(200, '/api/cart/shoppingcart/header'),
      api(200, '/api/camps/reservations/campgrounds/10083845/multi'),
      api(200, '/api/cart/shoppingcart'),
      api(200, '/api/camps/reservations?id=de5cd7f7-ea09-4d92-9f6d-80f6d2af17dc'),
    ]),
    true,
  )
  assert.equal(cartHoldCompletionObserved([api(200, '/api/cart/buy-now')]), true)
})

test('clickReserveButton surfaces a login modal as an auth failure', async () => {
  const page = reserveClickPage({ loginModalVisible: true })

  const result = await clickReserveButton(page)

  assert.equal(result.clicked, false)
  assert.equal(result.failure.error, 'recgov_spa_logged_out')
  assert.match(result.failure.detail, /logged-out state/)
  assert.match(result.failure.corrective_action, /recgov-login/)
  assert.deepEqual(page.clickedSelectors, ['button:has-text("Add to Cart")'])
})

test('clickReserveButton skips disabled confirmation candidates and clicks the enabled one', async () => {
  const page = reserveClickPage({
    loginModalVisible: false,
    confirmationCandidates: [
      { text: 'Continue', visible: true, enabled: false, ariaDisabled: 'true' },
      { text: 'Continue', visible: true, enabled: true },
    ],
  })

  const result = await clickReserveButton(page)

  assert.equal(result.clicked, true)
  assert.equal(result.confirmation.clicked, true)
  assert.equal(result.confirmation.index, 1)
  assert.equal(result.confirmation.text, 'Continue')
  assert.deepEqual(page.clickedSelectors, ['button:has-text("Add to Cart")'])
  assert.deepEqual(page.clickedConfirmationIndexes, [1])
})

test('clickReserveButton reports visible confirmation buttons that never enable', async () => {
  const page = reserveClickPage({
    loginModalVisible: false,
    confirmationCandidates: [
      { text: 'Continue', visible: true, enabled: false, ariaDisabled: 'true' },
    ],
  })

  const result = await clickReserveButton(page)

  assert.equal(result.clicked, true)
  assert.equal(result.confirmation.clicked, false)
  assert.equal(result.confirmation.reason, 'confirmation_disabled')
  assert.deepEqual(result.confirmation.candidates, [
    {
      index: 0,
      text: 'Continue',
      visible: true,
      enabled: false,
      disabled: true,
      aria_disabled: 'true',
    },
  ])
  assert.deepEqual(page.clickedConfirmationIndexes, [])
})

test('testChromium falls back after a stored recaccount is rejected by the SPA', async () => {
  const { context, pages } = authCheckContext()
  const stale = { access_token: 'stale-token', expiration: '2026-07-16T20:00:00Z' }
  const fresh = { access_token: 'fresh-token', expiration: '2026-07-16T21:00:00Z' }
  const resolved = [stale, fresh]
  const loginStates = [false, true]
  const clearedPages = []
  const resolveCalls = []

  const result = await testChromium(null, {
    credentials: { username: 'user@example.test', password: 'secret' },
    forceRefresh: true,
    getContextFn: async () => context,
    injectStoredCookiesFn: async () => 0,
    resolveRecaccountFn: async (page, options) => {
      resolveCalls.push({ page, options })
      return resolved.shift()
    },
    clearBrowserRecaccountFn: async (page) => {
      clearedPages.push(page)
    },
    injectRecaccountFn: async (page, recaccount) => {
      page.injectedRecaccounts.push(recaccount.access_token)
    },
    injectBearerRouteFn: async (page, token) => {
      page.bearerRoutes.push(token)
    },
    isSpaLoggedInFn: async () => loginStates.shift(),
  })

  assert.deepEqual(result, { ok: true, loggedIn: true })
  assert.equal(pages.length, 2)
  assert.equal(pages[0].closed, true)
  assert.deepEqual(clearedPages, [pages[0]])
  assert.equal(resolveCalls[0].options.forceRefresh, true)
  assert.equal(resolveCalls[1].options.forceRefresh, false)
  assert.equal(resolveCalls[1].options.allowManualLoginAfterRefreshFailure, true)
  assert.equal(resolveCalls[1].options.credentials.username, 'user@example.test')
  assert.deepEqual(pages[0].injectedRecaccounts, ['stale-token'])
  assert.deepEqual(pages[1].injectedRecaccounts, ['fresh-token'])
})

test('cartContainsMatch requires the requested campsite and dates in a nonempty cart', () => {
  const match = recgovMatch()

  assert.equal(
    cartContainsMatch({
      reservations: [
        {
          campsite_id: '999999',
          start_date: '2026-07-16T00:00:00Z',
          end_date: '2026-07-17T00:00:00Z',
        },
      ],
    }, match),
    false,
  )
})

test('cartContainsMatch accepts the requested campsite and dates in a nonempty cart', () => {
  const match = recgovMatch()

  const check = verifyCartContainsMatch({
    reservations: [
      {
        campsite_id: '999999',
        start_date: '2026-07-16T00:00:00Z',
        end_date: '2026-07-17T00:00:00Z',
      },
      {
        reservation: {
          campsite_id: '10174587',
          start_date: '2026-07-16T00:00:00Z',
          end_date: '2026-07-17T00:00:00Z',
        },
      },
    ],
  }, match)

  assert.equal(check.ok, true)
  assert.equal(check.reason, 'matched')
  assert.equal(check.reservation_index, 1)
  assert.deepEqual(check.matched, {
    campsite_id: '10174587',
    campsite_label: null,
    campground_id: null,
    arrival_date: '2026-07-16',
    checkout_date: '2026-07-17',
  })
})

test('cartContainsMatch rejects the requested campsite on a different date', () => {
  const match = recgovMatch()

  const check = verifyCartContainsMatch({
    reservations: [
      {
        campsite_id: '10174587',
        start_date: '2026-07-18T00:00:00Z',
        end_date: '2026-07-19T00:00:00Z',
      },
    ],
  }, match)

  assert.equal(check.ok, false)
  assert.equal(check.reason, 'missing_expected_item')
  assert.equal(check.reservation_count, 1)
  assert.deepEqual(check.best_match, {
    reservation_index: 0,
    score: 1,
    matched: {
      campsite_id: '10174587',
      campsite_label: null,
      campground_id: null,
      arrival_date: null,
      checkout_date: null,
    },
  })
})

test('verifyCartContainsMatch reports cart fetch and HTTP failures explicitly', () => {
  const match = recgovMatch()

  assert.deepEqual(
    pickFailureFields(verifyCartContainsMatch({ error: 'Failed to fetch' }, match)),
    {
      ok: false,
      reason: 'cart_fetch_failed',
      status: null,
      reservation_count: 0,
      detail: 'Failed to fetch',
    },
  )
  assert.deepEqual(
    pickFailureFields(verifyCartContainsMatch({ status: 401, reservations: [] }, match)),
    {
      ok: false,
      reason: 'cart_http_error',
      status: 401,
      reservation_count: 0,
      detail: undefined,
    },
  )
})

test('cartContainsMatch can identify the campsite from the booking URL', () => {
  assert.equal(
    cartContainsMatch({
      reservations: [
        {
          campsite_id: '85735',
          start_date: '2026-07-23',
          end_date: '2026-07-24',
        },
      ],
    }, {
      booking_url: 'https://www.recreation.gov/camping/campsites/85735?startDate=2026-07-23&endDate=2026-07-24',
      campground_id: 232038,
      campsite_site: '116',
      first_date: '2026-07-23',
      checkout_date: '2026-07-24',
    }),
    true,
  )
})

function fakeJwt (nowMs, offsetSeconds) {
  const exp = Math.floor(nowMs / 1000) + offsetSeconds
  return `${base64Url(JWT_HEADER)}.${base64Url({ exp })}.${JWT_SIGNATURE}`
}

function recgovMatch () {
  return {
    campground_id: '10083845',
    provider_campsite_id: '10174587',
    campsite_site: '007',
    first_date: '2026-07-16',
    checkout_date: '2026-07-17',
  }
}

function api (status, path) {
  return { status, path }
}

function reserveClickPage ({ loginModalVisible, confirmationCandidates = [] }) {
  return {
    clickedSelectors: [],
    clickedConfirmationIndexes: [],
    locator (selector) {
      const page = this
      if (isConfirmationSelector(selector)) {
        return confirmationLocator(page, confirmationCandidates)
      }
      return {
        first () {
          return this
        },
        async isVisible () {
          if (selector.includes('Sign In') || selector.includes('Log In') || selector.includes('login-modal')) {
            return loginModalVisible
          }
          return selector === 'button:has-text("Add to Cart")'
        },
        async click () {
          page.clickedSelectors.push(selector)
        },
        async waitFor () {},
      }
    },
    async waitForTimeout () {},
    async waitForSelector (selector) {
      if (!isConfirmationSelector(selector)) return
      const visible = confirmationCandidates.some(candidate => candidate.visible)
      const enabled = confirmationCandidates.some(candidate => candidate.visible && candidate.enabled)
      if (isEnabledConfirmationSelector(selector) ? !enabled : !visible) {
        throw new Error(`selector not found: ${selector}`)
      }
    },
  }
}

function isConfirmationSelector (selector) {
  return ['Continue', 'Confirm', 'Book Now', 'Next'].some(label => selector.includes(label))
}

function isEnabledConfirmationSelector (selector) {
  return selector.includes(':enabled')
}

function confirmationLocator (page, candidates) {
  return {
    first () {
      return this.nth(0)
    },
    async count () {
      return candidates.length
    },
    nth (index) {
      return confirmationCandidateLocator(page, candidates[index], index)
    },
    async isVisible () {
      return candidates.some(candidate => candidate.visible)
    },
    async isEnabled () {
      return candidates.some(candidate => candidate.visible && candidate.enabled)
    },
  }
}

function confirmationCandidateLocator (page, candidate = {}, index) {
  return {
    async isVisible () {
      return Boolean(candidate.visible)
    },
    async isEnabled () {
      return Boolean(candidate.enabled)
    },
    async innerText () {
      return candidate.text || ''
    },
    async getAttribute (name) {
      if (name === 'aria-disabled') return candidate.ariaDisabled || null
      return null
    },
    async click () {
      page.clickedConfirmationIndexes.push(index)
    },
  }
}

function authCheckContext () {
  const pages = []
  const context = {
    addCookies: async () => {},
    newPage: async () => {
      const page = {
        bearerRoutes: [],
        closed: false,
        gotos: [],
        injectedRecaccounts: [],
        waits: [],
        close: async () => {
          page.closed = true
        },
        goto: async (url) => {
          page.gotos.push(url)
        },
        waitForTimeout: async (ms) => {
          page.waits.push(ms)
        },
      }
      pages.push(page)
      return page
    },
  }
  return { context, pages }
}

function pickFailureFields (check) {
  return {
    ok: check.ok,
    reason: check.reason,
    status: check.status,
    reservation_count: check.reservation_count,
    detail: check.detail,
  }
}

function base64Url (value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}
