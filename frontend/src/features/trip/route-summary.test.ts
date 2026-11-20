import { describe, expect, test } from 'vitest';
import type { TripStop } from '@/stores/tripStore';
import {
  formatDistanceAlongRoute,
  formatDrivingTime,
  formatTotalKm,
  routeErrorMessage,
  routeLegLines,
  routeSummary,
} from './route-summary';

const stop = (name: string): TripStop => ({ name, lng: -122, lat: 47 });

describe('formatTotalKm', () => {
  test('rounds to whole kilometres and separates thousands', () => {
    expect(formatTotalKm(1_842_400)).toBe('1,842 km');
    expect(formatTotalKm(499)).toBe('0 km');
  });

  test('treats a missing distance as zero rather than NaN', () => {
    expect(formatTotalKm(undefined)).toBe('0 km');
  });
});

describe('formatDistanceAlongRoute', () => {
  test('reads as distance into the trip', () => {
    expect(formatDistanceAlongRoute(0.4)).toBe('400 m in');
    expect(formatDistanceAlongRoute(4.25)).toBe('4.3 km in');
    expect(formatDistanceAlongRoute(184.6)).toBe('185 km in');
  });
});

describe('formatDrivingTime', () => {
  test('minutes under an hour, hours and minutes above', () => {
    expect(formatDrivingTime(1_500)).toBe('25m');
    expect(formatDrivingTime(66_000)).toBe('18h 20m');
    expect(formatDrivingTime(7_200)).toBe('2h');
  });

  test('never shows seconds', () => {
    expect(formatDrivingTime(45)).toBe('1m');
    expect(formatDrivingTime(0)).toBe('0m');
  });

  test('does not render a negative duration', () => {
    expect(formatDrivingTime(-500)).toBe('0m');
  });
});

describe('routeSummary', () => {
  test('reports the whole trip', () => {
    expect(routeSummary({ distance_m: 320_000, duration_s: 12_600 })).toEqual({
      distance: '320 km',
      duration: '3h 30m',
    });
  });

  test('answers null with no route properties', () => {
    expect(routeSummary(null)).toBeNull();
  });
});

describe('routeLegLines', () => {
  test('says nothing about a two-stop trip', () => {
    expect(routeLegLines({ legs: [{ distance_m: 1000, duration_s: 60 }] }, [])).toEqual([]);
    expect(routeLegLines({}, [])).toEqual([]);
  });

  test('names each leg by its stops" first words', () => {
    const stops = [stop('Seattle WA'), stop('Bowman Bay Campground'), stop('Bellingham')];

    expect(
      routeLegLines(
        {
          legs: [
            { distance_m: 120_000, duration_s: 5_400 },
            { distance_m: 40_000, duration_s: 2_100 },
          ],
        },
        stops,
      ),
    ).toEqual([
      { from: 'Seattle', to: 'Bowman', distance: '120 km', duration: '1h 30m' },
      { from: 'Bowman', to: 'Bellingham', distance: '40 km', duration: '35m' },
    ]);
  });
});

describe('routeErrorMessage', () => {
  test('names the failures a user can act on and keeps the raw code and detail', () => {
    expect(routeErrorMessage({ error: 'duplicate_adjacent', detail: 'points 1 and 0 are identical', status: 400 })).toBe(
      'Two adjacent stops are the same. (duplicate_adjacent: points 1 and 0 are identical · HTTP 400)',
    );
    expect(routeErrorMessage({ error: 'too_few_points' })).toBe('Need at least 2 stops. (too_few_points)');
    expect(routeErrorMessage({ error: 'too_many_points' })).toBe('Too many stops. (too_many_points)');
  });

  test('a stop pair with no drivable route says so, with Mapbox\'s words', () => {
    expect(
      routeErrorMessage({ error: 'route_not_found', detail: 'Mapbox code=NoRoute: No route found', status: 422 }),
    ).toBe('No drivable route connects these stops. (route_not_found: Mapbox code=NoRoute: No route found · HTTP 422)');
  });

  test('a code it does not know renders verbatim with its detail', () => {
    expect(routeErrorMessage({ error: 'teapot', detail: 'short and stout', status: 418 })).toBe(
      'teapot: short and stout · HTTP 418',
    );
  });

  test('a transport failure keeps its class and message', () => {
    expect(routeErrorMessage(new TypeError('Failed to fetch'))).toBe('TypeError: Failed to fetch');
  });

  test('does not resolve a prototype member as a message', () => {
    expect(routeErrorMessage({ error: 'toString', status: 500 })).toBe('toString · HTTP 500');
    expect(routeErrorMessage({ error: 'constructor', status: 500 })).toBe('constructor · HTTP 500');
  });
});
