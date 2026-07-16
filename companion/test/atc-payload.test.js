import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  cartMatchFromArgs,
  cartMatchFromAtcInput,
  cartMatchFromDispatch,
  validateCartMatch,
} from '../src/atcPayload.js'

const DISPATCH = {
  id: 1,
  kind: 'atc',
  payload: {
    watch_id: 12,
    start_date: '2026-07-15',
    end_date: '2026-07-16',
    openings: [
      {
        label: '116',
        date: '2026-07-15',
        booking_url: 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
        campground_id: 232447,
        campsite_id: 131925,
        vendor_id: '300',
      },
    ],
  },
}

test('cartMatchFromDispatch maps backend ATC dispatch payload to addToCart match', () => {
  assert.deepEqual(cartMatchFromDispatch(DISPATCH), {
    booking_url: 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
    campground_id: 232447,
    campsite_id: 131925,
    provider_campsite_id: '300',
    first_date: '2026-07-15',
    checkout_date: '2026-07-16',
    available_dates: ['2026-07-15'],
    campsite_site: '116',
  })
})

test('cartMatchFromAtcInput accepts raw payload or dispatch wrapper', () => {
  assert.deepEqual(
    cartMatchFromAtcInput(DISPATCH.payload),
    cartMatchFromDispatch(DISPATCH),
  )
  assert.deepEqual(
    cartMatchFromAtcInput(DISPATCH),
    cartMatchFromDispatch(DISPATCH),
  )
})

test('cartMatchFromArgs builds a direct command-line match', () => {
  assert.deepEqual(
    cartMatchFromArgs({
      'booking-url': 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
      'start-date': '2026-07-15',
      'end-date': '2026-07-16',
      site: '116',
    }),
    {
      booking_url: 'https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16',
      campground_id: undefined,
      campsite_id: undefined,
      provider_campsite_id: undefined,
      first_date: '2026-07-15',
      checkout_date: '2026-07-16',
      available_dates: ['2026-07-15'],
      campsite_site: '116',
    },
  )
})

test('validateCartMatch rejects unusable one-shot inputs', () => {
  assert.equal(validateCartMatch({ checkout_date: '2026-07-16', booking_url: 'url' }), 'missing first_date/start-date')
  assert.equal(validateCartMatch({ first_date: '2026-07-15', booking_url: 'url' }), 'missing checkout_date/end-date')
  assert.equal(
    validateCartMatch({ first_date: '2026-07-15', checkout_date: '2026-07-16' }),
    'missing booking_url or campsite/campground identifier',
  )
  assert.equal(
    validateCartMatch(cartMatchFromArgs({ 'booking-url': true, 'start-date': '2026-07-15', 'end-date': '2026-07-16' })),
    'missing booking_url or campsite/campground identifier',
  )
})
