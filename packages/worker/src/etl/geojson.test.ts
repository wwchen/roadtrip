import { expect, test } from 'vitest';
import { pointGeoJson } from './geojson.js';

const LNG = -123.1;
const LAT = 49.2;

test('serializes as a Point geometry with longitude first', () => {
  expect(JSON.parse(pointGeoJson(LNG, LAT))).toEqual({ type: 'Point', coordinates: [LNG, LAT] });
});
