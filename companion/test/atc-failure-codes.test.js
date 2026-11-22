import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'atc-failure-codes-'))
process.env.HEADLESS = 'true'
process.env.COMPANION_DIR = path.join(scratch, 'store')
process.env.RECGOV_DIAGNOSTIC_DIR = path.join(scratch, 'diagnostics')
const { addToCart } = await import('../src/cart.js')

const ADD_TO_CART_SELECTOR = 'button:has-text("Add to Cart")'
const CAPTCHA_SELECTOR_MARKER = 'px-captcha'
const RECACCOUNT = { access_token: 'not.a.jwt', expiration: '2026-07-16T20:00:00Z' }
const MATCH = {
  campsite_id: '300',
  provider_campsite_id: '300',
  first_date: '2026-07-15',
  checkout_date: '2026-07-16',
}

test('a thrown automation step names the exception instead of reading as a lost race', async () => {
  const page = atcPage({ gotoError: new TypeError('page.goto: net::ERR_NAME_NOT_RESOLVED') })

  const result = await runAtc(page)

  assert.equal(result.ok, false)
  assert.equal(result.error, 'atc_automation_exception')
  assert.equal(result.detail, 'TypeError: page.goto: net::ERR_NAME_NOT_RESOLVED')
})

test('a captcha on the booking page stops the hold as captcha_required', async () => {
  const page = atcPage({ captcha: true })

  const result = await runAtc(page)

  assert.equal(result.ok, false)
  assert.equal(result.error, 'captcha_required')
  assert.match(result.detail, /challenge/)
  assert.deepEqual(page.clicked, [])
})

test('a cart the verifier could not read reports its HTTP status as its own code', async () => {
  const result = await withMockedClock(() => runAtc(atcPage({ cart: { status: 401 } })))

  assert.equal(result.ok, false)
  assert.equal(result.error, 'cart_http_error')
  assert.match(result.detail, /status=401/)
  assert.equal(result.cart_check.reason, 'cart_http_error')
})

test('an empty cart after the reserve click is cart_empty, not cart_not_added', async () => {
  const result = await withMockedClock(() => runAtc(atcPage({ cart: { status: 200, reservations: [] } })))

  assert.equal(result.error, 'cart_empty')
  assert.match(result.detail, /reason=cart_empty status=200 reservations=0/)
})

test('a cart fetch that threw in the page carries the fetch error', async () => {
  const result = await withMockedClock(() => runAtc(atcPage({ cart: { error: 'Failed to fetch' } })))

  assert.equal(result.error, 'cart_fetch_failed')
  assert.match(result.detail, /Failed to fetch/)
})

async function runAtc (page) {
  return addToCart(MATCH, {
    getContextFn: async () => ({
      addCookies: async () => {},
      newPage: async () => page,
    }),
    resolveRecaccountFn: async () => RECACCOUNT,
  })
}

async function withMockedClock (work) {
  mock.timers.enable({ apis: ['Date'], now: Date.now() })
  try {
    return await work()
  } finally {
    mock.timers.reset()
  }
}

function atcPage ({ gotoError = null, captcha = false, cart = { status: 200, reservations: [] } } = {}) {
  const page = {
    clicked: [],
    on () {},
    async addInitScript () {},
    async route () {},
    url () {
      return 'https://www.recreation.gov/camping/campsites/300'
    },
    async goto () {
      if (gotoError) throw gotoError
    },
    async waitForTimeout (ms) {
      try {
        mock.timers.tick(ms)
      } catch {}
    },
    async waitForSelector (selector, options = {}) {
      if (selector.includes(CAPTCHA_SELECTOR_MARKER)) {
        if (!captcha) throw new Error(`timeout waiting for ${selector}`)
        if (options.state === 'hidden') throw new Error('captcha never cleared')
        return
      }
      if (selector.includes(ADD_TO_CART_SELECTOR)) return
      throw new Error(`timeout waiting for ${selector}`)
    },
    locator (selector) {
      return {
        first () {
          return this
        },
        nth () {
          return this
        },
        async isVisible () {
          return selector === ADD_TO_CART_SELECTOR
        },
        async isEnabled () {
          return false
        },
        async click () {
          page.clicked.push(selector)
        },
        async count () {
          return 0
        },
        async allTextContents () {
          return []
        },
        async innerText () {
          return ''
        },
        async getAttribute () {
          return null
        },
      }
    },
    async evaluate () {
      return cart
    },
    async screenshot () {
      return Buffer.alloc(0)
    },
    async close () {},
  }
  return page
}
