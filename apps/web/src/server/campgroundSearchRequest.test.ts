import { expect, test } from 'vitest';
import { MAX_DETAIL_IDS, parseCampgroundSearchRequest, parseDetailsRequest } from './campgroundSearchRequest';

const SQUARE = { type: 'Polygon', coordinates: [[[-120, 39], [-119, 39], [-119, 40], [-120, 40], [-120, 39]]] };

test('a polygon boundary with a filter is accepted and re-encoded', () => {
  const parsed = parseCampgroundSearchRequest({ boundary: SQUARE, filter: { site_type: 'rv', group_size: 4, amenities: ['showers'] } });
  expect(parsed).toEqual({
    boundaryGeoJson: JSON.stringify(SQUARE),
    filter: { siteType: 'rv', groupSize: 4, amenities: ['showers'] },
  });
});

test('a missing boundary is bad_boundary', () => {
  expect(parseCampgroundSearchRequest({})).toMatchObject({ problem: 'bad_boundary', detail: 'boundary is required' });
});

test('a boundary whose bounding box is over 500 square degrees is bad_boundary', () => {
  const huge = { type: 'Polygon', coordinates: [[[-130, 20], [-100, 20], [-100, 50], [-130, 20]]] };
  expect(parseCampgroundSearchRequest({ boundary: huge })).toMatchObject({ problem: 'bad_boundary' });
});

test.each([
  ['a point boundary', { boundary: { type: 'Point', coordinates: [0, 0] } }],
  ['coordinates that are not an array', { boundary: { type: 'Polygon', coordinates: 'x' } }],
  ['an unknown site_type', { boundary: SQUARE, filter: { site_type: 'yurt-palace' } }],
  ['an unknown amenity', { boundary: SQUARE, filter: { amenities: ['jacuzzi'] } }],
  ['a fractional group_size', { boundary: SQUARE, filter: { group_size: 2.5 } }],
  ['a group_size under 1', { boundary: SQUARE, filter: { group_size: 0 } }],
  ['not an object', [] as unknown],
])('%s is bad_request', (_name, body) => {
  expect(parseCampgroundSearchRequest(body)).toMatchObject({ problem: 'bad_request' });
});

test('details ids are de-duplicated and default to none', () => {
  expect(parseDetailsRequest({ campground_ids: [3, 1, 3] })).toEqual({ ids: [3n, 1n] });
  expect(parseDetailsRequest({})).toEqual({ ids: [] });
});

test('more than 50 ids is too_many_ids', () => {
  const ids = Array.from({ length: MAX_DETAIL_IDS + 1 }, (_, index) => index + 1);
  expect(parseDetailsRequest({ campground_ids: ids })).toMatchObject({ problem: 'too_many_ids' });
});

test('ids that are not integers are bad_request', () => {
  expect(parseDetailsRequest({ campground_ids: ['1'] })).toMatchObject({ problem: 'bad_request' });
});
