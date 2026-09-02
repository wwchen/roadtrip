import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  RECGOV_ACCOUNT_URL,
  VERIFY_ERROR_NOT_AUTHENTICATED,
  verifyRecgovSession,
} from '../src/recgovVerify.js'

const RECACCOUNT = { access_token: 'token', expiration: '2026-09-01T00:00:00Z' }

test('verify loads the account page and reads the cart without ever clicking Reserve', async () => {
  const page = fakePage({ cart: { status: 200, reservations: [] } })
  const deps = fakeDeps({ page })

  const result = await verifyRecgovSession(deps)

  assert.equal(result.ok, true)
  assert.equal(result.logged_in, true)
  assert.equal(result.cart_status, 200)
  assert.equal(result.cart_reservation_count, 0)
  assert.equal(result.account_url, RECGOV_ACCOUNT_URL)
  assert.ok(Date.parse(result.checked_at))
  assert.deepEqual(page.gotos, [RECGOV_ACCOUNT_URL])
  assert.deepEqual(page.clicks, [])
  assert.deepEqual(page.locators, [])
  assert.equal(page.closed, true)
})

test('verify never attempts an interactive login', async () => {
  const page = fakePage({ cart: { status: 200, reservations: [] } })
  const deps = fakeDeps({ page })

  await verifyRecgovSession(deps)

  assert.equal(deps.resolveCalls[0].options.allowManualLogin, false)
  assert.equal(deps.resolveCalls[0].options.credentials, undefined)
})

test('verify reports an unauthenticated profile without opening the account page', async () => {
  const page = fakePage({ cart: { status: 200 } })
  const deps = fakeDeps({ page, recaccount: null })

  const result = await verifyRecgovSession(deps)

  assert.equal(result.ok, false)
  assert.equal(result.logged_in, false)
  assert.equal(result.error, VERIFY_ERROR_NOT_AUTHENTICATED)
  assert.deepEqual(page.gotos, [])
  assert.equal(page.closed, true)
})

test('verify fails when the cart API rejects the session', async () => {
  const page = fakePage({ cart: { status: 401 } })
  const deps = fakeDeps({ page })

  const result = await verifyRecgovSession(deps)

  assert.equal(result.ok, false)
  assert.equal(result.logged_in, true)
  assert.equal(result.cart_status, 401)
  assert.equal(result.error, 'recgov_cart_unreachable')
})

test('verify fails when the SPA still renders as logged out', async () => {
  const page = fakePage({ cart: { status: 200, reservations: [] }, loggedIn: false })
  const deps = fakeDeps({ page })

  const result = await verifyRecgovSession(deps)

  assert.equal(result.ok, false)
  assert.equal(result.logged_in, false)
  assert.equal(result.error, VERIFY_ERROR_NOT_AUTHENTICATED)
})

function fakeDeps ({ page, recaccount = RECACCOUNT }) {
  const resolveCalls = []
  return {
    resolveCalls,
    getContextFn: async () => ({ newPage: async () => page }),
    injectStoredCookiesFn: async () => 0,
    resolveRecaccountFn: async (resolvePage, options) => {
      resolveCalls.push({ page: resolvePage, options })
      return recaccount
    },
    injectRecaccountFn: async () => {},
    injectBearerRouteFn: async () => true,
    injectFingerprintCookieFn: async () => true,
    isSpaLoggedInFn: async (spaPage) => spaPage.isSpaLoggedIn,
    getCartItemsFn: async (cartPage) => cartPage.cartResponse,
  }
}

function fakePage ({ cart, loggedIn = true }) {
  const page = {
    cartResponse: cart,
    gotos: [],
    clicks: [],
    locators: [],
    closed: false,
    isSpaLoggedIn: loggedIn,
    goto: async (url) => {
      page.gotos.push(url)
    },
    waitForTimeout: async () => {},
    click: async (selector) => {
      page.clicks.push(selector)
    },
    locator: (selector) => {
      page.locators.push(selector)
      return { first: () => ({ click: async () => { page.clicks.push(selector) } }) }
    },
    close: async () => {
      page.closed = true
    },
  }
  return page
}
