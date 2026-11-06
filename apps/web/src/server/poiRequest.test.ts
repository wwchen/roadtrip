import { expect, test } from 'vitest';
import { parsePoisRequest } from './poiRequest';

test("accepts the frontend's body", () => {
  expect(parsePoisRequest({ bbox: [-121, 38, -119, 40], zoom: 7, categories: ['campground', 'tesla_supercharger'] })).toEqual({
    bbox: { west: -121, south: 38, east: -119, north: 40 },
    zoom: 7,
    categories: ['campground', 'tesla_supercharger'],
  });
});

test('zoom and categories are optional; blank categories mean the default set', () => {
  expect(parsePoisRequest({ bbox: [0, 0, 1, 1], categories: [' ', ''] })).toEqual({
    bbox: { west: 0, south: 0, east: 1, north: 1 },
    zoom: null,
    categories: null,
  });
});

test.each([
  ['west after east', { bbox: [2, 0, 1, 1] }],
  ['south after north', { bbox: [0, 2, 1, 1] }],
  ['latitude past 90', { bbox: [0, 0, 1, 91] }],
  ['longitude past 180', { bbox: [0, 0, 181, 1] }],
  ['three numbers', { bbox: [0, 0, 1] }],
  ['strings', { bbox: ['0', '0', '1', '1'] }],
  ['no bbox', {}],
  ['a fractional zoom', { bbox: [0, 0, 1, 1], zoom: 6.5 }],
  ['categories that are not strings', { bbox: [0, 0, 1, 1], categories: [1] }],
  ['not an object', 'bbox'],
])('rejects %s as bad_request', (_name, body) => {
  expect(parsePoisRequest(body)).toMatchObject({ problem: 'bad_request' });
});

test('maps both category aliases', () => {
  const parsed = parsePoisRequest({ bbox: [0, 0, 1, 1], categories: ['planet-fitness', 'supercharger'] });
  expect(parsed).toMatchObject({ categories: ['planet_fitness_location', 'tesla_supercharger'] });
});
