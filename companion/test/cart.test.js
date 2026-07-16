import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { COMPANION_USER_AGENT, resolveSessionDir } from '../src/browser.js'
import {
  bookingUrlForMatch,
  cartContainsMatch,
  cartHoldCompletionObserved,
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
