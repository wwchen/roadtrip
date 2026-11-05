import { expect, test } from 'vitest';
import { poiDateContext } from './dateContext';

const VANCOUVER = { lat: 49.2827, lng: -123.1207 };
const NEW_YORK = { lat: 40.7128, lng: -74.006 };

test('a campground takes the time zone of its own coordinates', () => {
  const now = new Date('2026-06-17T20:00:00Z');
  expect(poiDateContext(VANCOUVER.lat, VANCOUVER.lng, now).timeZone).toBe('America/Vancouver');
  expect(poiDateContext(NEW_YORK.lat, NEW_YORK.lng, now).timeZone).toBe('America/New_York');
});

test('the earliest date is today before the 18:00 local cutoff and tomorrow after it', () => {
  const instant = new Date('2026-06-17T23:30:00Z');
  expect(poiDateContext(VANCOUVER.lat, VANCOUVER.lng, instant).earliestDate).toBe('2026-06-17');
  expect(poiDateContext(NEW_YORK.lat, NEW_YORK.lng, instant).earliestDate).toBe('2026-06-18');
});

test('exactly 18:00 local is already past the cutoff', () => {
  expect(poiDateContext(VANCOUVER.lat, VANCOUVER.lng, new Date('2026-06-18T01:00:00Z')).earliestDate).toBe('2026-06-18');
});

test('missing or impossible coordinates fall back to America/Vancouver', () => {
  const now = new Date('2026-06-17T20:00:00Z');
  expect(poiDateContext(null, null, now).timeZone).toBe('America/Vancouver');
  expect(poiDateContext(91, 0, now).timeZone).toBe('America/Vancouver');
  expect(poiDateContext(Number.NaN, 0, now).timeZone).toBe('America/Vancouver');
});
