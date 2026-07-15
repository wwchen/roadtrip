import { test } from 'node:test'
import assert from 'node:assert/strict'
import { bookingUrlForMatch } from '../src/cart.js'

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
