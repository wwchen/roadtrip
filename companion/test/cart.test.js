import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { COMPANION_USER_AGENT, resolveSessionDir } from '../src/browser.js'
import { bookingUrlForMatch } from '../src/cart.js'
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

function fakeJwt (nowMs, offsetSeconds) {
  const exp = Math.floor(nowMs / 1000) + offsetSeconds
  return `${base64Url(JWT_HEADER)}.${base64Url({ exp })}.${JWT_SIGNATURE}`
}

function base64Url (value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}
