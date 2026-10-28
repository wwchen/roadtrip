import { expect, test } from 'vitest';
import { bookingProviders } from './campground.js';

const KOTLIN_BOOKING_PROVIDERS = ['recgov', 'campflare', 'aspira', 'reserveamerica', 'reservecalifornia'];

test('booking providers match the Kotlin enum exactly', () => {
  expect([...bookingProviders]).toEqual(KOTLIN_BOOKING_PROVIDERS);
});
