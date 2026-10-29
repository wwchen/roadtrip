import { expect, test } from 'vitest';
import {
  DEFAULT_SEARCH_BBOX,
  MAX_BBOX_AREA_SQUARE_DEGREES,
  bboxToPolygonGeoJson,
  parseBbox,
  parsePoiId,
} from './boundary';

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

test.each(['-180,-90,-174,-84', '174,84,180,90', '-180,-90,-180,90'])(
  'bboxToPolygonGeoJson accepts the extreme valid corners %j',
  (raw) => {
    expect(bboxToPolygonGeoJson(raw)).not.toBeNull();
  },
);

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

test.each(['170,0,-170,10', '0.0001,0,0,10', '180,-90,-180,90'])(
  'bboxToPolygonGeoJson rejects the west-greater-than-east box %j and parseBbox names why',
  (raw) => {
    expect(bboxToPolygonGeoJson(raw)).toBeNull();
    expect(parseBbox(raw)).toEqual({ problem: 'crosses-antimeridian' });
  },
);

test('parseBbox calls a malformed box malformed, not antimeridian', () => {
  expect(parseBbox('garbage')).toEqual({ problem: 'malformed' });
  expect(parseBbox('-200,0,-300,10')).toEqual({ problem: 'malformed' });
});

test('parseBbox accepts west equal to east without calling it antimeridian', () => {
  expect(parseBbox('5,0,5,10')).toHaveProperty('polygon');
});

test.each(['-130,25,-65,50', '-180,-90,180,90', '-125,42,-116,49', '0,0,6.01,6', '0,0,10,10'])(
  'parseBbox names the box %j too-large and builds no polygon',
  (raw) => {
    expect(parseBbox(raw)).toEqual({ problem: 'too-large' });
    expect(bboxToPolygonGeoJson(raw)).toBeNull();
  },
);

test.each(['0,0,6,6', '-123,38,-120,41', '-180,0,-144,1', '0,0,36,1', DEFAULT_SEARCH_BBOX])(
  'parseBbox accepts the box %j at or under the area limit',
  (raw) => {
    expect(parseBbox(raw)).toHaveProperty('polygon');
  },
);

test('the area limit is the one the measurements chose', () => {
  expect(MAX_BBOX_AREA_SQUARE_DEGREES).toBe(36);
});

test('an antimeridian box is still reported as such when it is also large', () => {
  expect(parseBbox('180,-90,-180,90')).toEqual({ problem: 'crosses-antimeridian' });
});
