import { expect, test } from 'vitest';
import { DEFAULT_SEARCH_BBOX, bboxToPolygonGeoJson, parsePoiId } from './boundary';

const MAX_BIGINT_ID = '9223372036854775807';
const PAST_BIGINT_ID = '92233720368547758070';
const JUST_PAST_BIGINT_ID = '9223372036854775808';
const CLOSED_RING_POINT_COUNT = 5;

test('parsePoiId accepts a positive integer string', () => {
  expect(parsePoiId('42')).toBe(42n);
});

test.each(['abc', '-1', '0', '', '1.5', '1e3', '+5', ' 5', '5 ', '00', '٣'])(
  'parsePoiId rejects %j',
  (raw) => {
    expect(parsePoiId(raw)).toBeNull();
  },
);

test('parsePoiId accepts an id at the bigint boundary without throwing', () => {
  expect(parsePoiId(MAX_BIGINT_ID)).toBe(9223372036854775807n);
  expect(parsePoiId(JUST_PAST_BIGINT_ID)).toBeNull();
  expect(parsePoiId(PAST_BIGINT_ID)).toBeNull();
});

test('parsePoiId canonicalises so a leading zero reaches the repo as the same id', () => {
  expect(parsePoiId('01001')).toBe(1001n);
  expect(parsePoiId('01001')).toBe(parsePoiId('1001'));
  expect(String(parsePoiId('0000001001'))).toBe('1001');
});

function ringOf(geoJson: string | null): number[][] {
  expect(geoJson).not.toBeNull();
  const parsed = JSON.parse(geoJson as string) as { type: string; coordinates: number[][][] };
  expect(parsed.type).toBe('Polygon');
  expect(parsed.coordinates).toHaveLength(1);
  return parsed.coordinates[0] as number[][];
}

test('bboxToPolygonGeoJson produces a closed 5-point polygon', () => {
  const ring = ringOf(bboxToPolygonGeoJson('-120.4,38.7,-119.6,39.4'));
  expect(ring).toHaveLength(CLOSED_RING_POINT_COUNT);
  expect(ring[0]).toEqual(ring[CLOSED_RING_POINT_COUNT - 1]);
  expect(ring).toEqual([
    [-120.4, 38.7],
    [-119.6, 38.7],
    [-119.6, 39.4],
    [-120.4, 39.4],
    [-120.4, 38.7],
  ]);
});

test('bboxToPolygonGeoJson accepts the extreme valid corners', () => {
  expect(bboxToPolygonGeoJson('-180,-90,180,90')).not.toBeNull();
});

test.each([
  '1,2,3',
  '1,2,3,4,5',
  'a,b,c,d',
  '-200,0,10,10',
  '0,-91,10,10',
  '0,0,181,10',
  '0,0,10,91',
  '1,,3,4',
  ',,,',
  '',
  'garbage',
  '1,2,3,Infinity',
  '1,2,3,1e1',
  '1, 2,3,4',
])('bboxToPolygonGeoJson rejects %j', (raw) => {
  expect(bboxToPolygonGeoJson(raw)).toBeNull();
});

test('the default search bbox is itself a valid bbox', () => {
  expect(bboxToPolygonGeoJson(DEFAULT_SEARCH_BBOX)).not.toBeNull();
});
