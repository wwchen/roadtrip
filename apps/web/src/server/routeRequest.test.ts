import { expect, test } from 'vitest';
import { parseOnRouteRequest, parseRouteQuery } from './routeRequest';

const query = (text: string) => parseRouteQuery(new URLSearchParams(text));

test('coords and an optional radius are parsed', () => {
  expect(query('coords=-120,39;%20-119,39.5%20&radius_miles=5')).toEqual({ coords: [[-120, 39], [-119, 39.5]], radiusMiles: 5 });
  expect(query('coords=-120,39;-119,39.5')).toEqual({ coords: [[-120, 39], [-119, 39.5]], radiusMiles: null });
});

test.each([
  ['coords=-120,39', 'too_few_points'],
  [`coords=${Array.from({ length: 26 }, (_, i) => `${i},1`).join(';')}`, 'too_many_points'],
  ['coords=-120,39;nope', 'bad_coords'],
  ['coords=-120,39;1,2,3', 'bad_coords'],
  ['coords=-120,39;-200,39', 'out_of_range'],
  ['coords=-120,39;-119,39&radius_miles=abc', 'bad_radius'],
  ['coords=-120,39;-119,39&radius_miles=0', 'bad_radius'],
  ['coords=-120,39;-119,39&radius_miles=101', 'bad_radius'],
])('%s is %s with a detail', (text, error) => {
  expect(query(text)).toMatchObject({ error, detail: expect.any(String) });
});

test('an on-route body is parsed with canonical categories', () => {
  expect(parseOnRouteRequest({ waypoints: [{ lat: 39, lng: -120 }, { lat: 39.5, lng: -119 }], radius_miles: 10, categories: ['supercharger'] })).toEqual({
    coords: [[-120, 39], [-119, 39.5]],
    radiusMiles: 10,
    categories: ['tesla_supercharger'],
  });
});

test.each([
  ['radius 0', { waypoints: [{ lat: 0, lng: 0 }, { lat: 1, lng: 1 }], radius_miles: 0 }],
  ['radius 101', { waypoints: [{ lat: 0, lng: 0 }, { lat: 1, lng: 1 }], radius_miles: 101 }],
  ['one waypoint', { waypoints: [{ lat: 0, lng: 0 }], radius_miles: 5 }],
  ['a latitude out of range', { waypoints: [{ lat: 91, lng: 0 }, { lat: 1, lng: 1 }], radius_miles: 5 }],
  ['no radius', { waypoints: [{ lat: 0, lng: 0 }, { lat: 1, lng: 1 }] }],
])('on-route with %s is bad_request', (_name, body) => {
  expect(parseOnRouteRequest(body)).toMatchObject({ error: 'bad_request' });
});
