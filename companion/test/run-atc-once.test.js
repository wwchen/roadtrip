import { test } from 'node:test'
import assert from 'node:assert/strict'
import { runAtcOnce } from '../src/runAtcOnce.js'

const PAYLOAD_JSON = JSON.stringify({
  start_date: '2026-07-15',
  end_date: '2026-07-16',
  campsite_id: '300',
})

test('runAtcOnce emits one success JSON result on stdout', async () => {
  const stdout = bufferWriter()
  const stderr = bufferWriter()
  let receivedMatch
  let pageClosed = false

  const code = await runAtcOnce({
    argv: ['--payload-json', PAYLOAD_JSON],
    stdout,
    stderr,
    addToCartFn: async (match) => {
      console.log('browser automation log')
      console.error('browser automation error log')
      receivedMatch = match
      return {
        ok: true,
        screenshots: [
          {
            label: 'opened-booking-url',
            screenshot_url: '/screenshot/diagnostics/recgov-atc-opened-booking-url.png',
          },
        ],
        page: {
          close: async () => { pageClosed = true },
        },
      }
    },
  })

  assert.equal(code, 0)
  assert.equal(receivedMatch.campsite_id, '300')
  assert.equal(pageClosed, true)
  assert.match(stderr.text(), /browser automation log/)
  assert.match(stderr.text(), /browser automation error log/)
  assert.doesNotMatch(stdout.text(), /browser automation log/)
  assert.doesNotMatch(stdout.text(), /browser automation error log/)
  assert.deepEqual(JSON.parse(stdout.text()), {
    ok: true,
    cart_added: true,
    booking_url: 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
    first_date: '2026-07-15',
    checkout_date: '2026-07-16',
    screenshots: [
      {
        label: 'opened-booking-url',
        screenshot_url: '/screenshot/diagnostics/recgov-atc-opened-booking-url.png',
      },
    ],
  })
})

test('runAtcOnce exits one when cart automation does not confirm a hold', async () => {
  const stdout = bufferWriter()

  const code = await runAtcOnce({
    argv: ['--payload-json', PAYLOAD_JSON],
    stdout,
    stderr: bufferWriter(),
    addToCartFn: async () => ({ ok: false }),
  })

  const result = JSON.parse(stdout.text())
  assert.equal(code, 1)
  assert.equal(result.ok, false)
  assert.equal(result.cart_added, false)
  assert.equal(result.error, 'cart_not_added')
  assert.deepEqual(result.screenshots, [])
})

test('runAtcOnce includes cart verification details on ATC failure', async () => {
  const stdout = bufferWriter()

  const code = await runAtcOnce({
    argv: ['--payload-json', PAYLOAD_JSON],
    stdout,
    stderr: bufferWriter(),
    addToCartFn: async () => ({
      ok: false,
      cart_check: {
        reason: 'missing_expected_item',
        status: 200,
        reservation_count: 1,
        response_signal: true,
      },
    }),
  })

  const result = JSON.parse(stdout.text())
  assert.equal(code, 1)
  assert.equal(result.detail, 'cart verification failed: reason=missing_expected_item status=200 reservations=1')
  assert.deepEqual(result.cart_check, {
    reason: 'missing_expected_item',
    status: 200,
    reservation_count: 1,
    response_signal: true,
  })
  assert.deepEqual(result.screenshots, [])
})

test('runAtcOnce preserves actionable auth failure details', async () => {
  const stdout = bufferWriter()

  const code = await runAtcOnce({
    argv: ['--payload-json', PAYLOAD_JSON],
    stdout,
    stderr: bufferWriter(),
    addToCartFn: async () => ({
      ok: false,
      error: 'recgov_not_authenticated',
      detail: 'No Recreation.gov browser session is available in the companion profile.',
      corrective_action: 'Run make recgov-login on the host profile mounted by the companion.',
      auth: {
        headless: true,
      },
    }),
  })

  const result = JSON.parse(stdout.text())
  assert.equal(code, 1)
  assert.equal(result.ok, false)
  assert.equal(result.cart_added, false)
  assert.equal(result.error, 'recgov_not_authenticated')
  assert.equal(result.detail, 'No Recreation.gov browser session is available in the companion profile.')
  assert.equal(result.corrective_action, 'Run make recgov-login on the host profile mounted by the companion.')
  assert.deepEqual(result.auth, {
    headless: true,
  })
  assert.deepEqual(result.screenshots, [])
})

test('runAtcOnce exits two for invalid payloads', async () => {
  const stdout = bufferWriter()

  const code = await runAtcOnce({
    argv: ['--payload-json', '{"end_date":"2026-07-16"}'],
    stdout,
    stderr: bufferWriter(),
    addToCartFn: async () => {
      throw new Error('should not run')
    },
  })

  const result = JSON.parse(stdout.text())
  assert.equal(code, 2)
  assert.equal(result.ok, false)
  assert.equal(result.cart_added, false)
  assert.equal(result.error, 'invalid_payload')
})

function bufferWriter () {
  const chunks = []
  return {
    write: (chunk) => {
      chunks.push(String(chunk))
    },
    text: () => chunks.join(''),
  }
}
