import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import {
  ENABLED_PROVIDERS,
  FAR_POI_ID,
  NEAR_POI_ID,
  TAHOE_BOUNDARY,
  UNKNOWN_POI_ID,
  UNSERVED_POI_ID,
  startCatalogFixture,
  type CatalogFixture,
} from '../testing/catalogFixture';
import { campgroundDetails, searchCampgroundIds } from './campgroundSearchService';

const NO_SHOWERS_POI = 3401n;
const RECGOV_ONLY_POI = 3403n;
const LIMIT = 100;
let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  await fixture.writeDb.execute(sql`INSERT INTO pois (id, poi_type, geom) VALUES (${NO_SHOWERS_POI}, 'campground', ST_SetSRID(ST_MakePoint(-120.1, 39.02), 4326))`);
  await fixture.writeDb.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, amenities, booking_provider, booking_provider_ref)
    VALUES (3402, 'campflare', 'ns-1', 'No Showers Camp', '[{"key":"showers","present":false}]', 'aspira', 'bc:1:2:null')`);
  await fixture.writeDb.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${NO_SHOWERS_POI}, 3402)`);
  await fixture.writeDb.execute(sql`INSERT INTO pois (id, poi_type, geom) VALUES (${RECGOV_ONLY_POI}, 'campground', ST_SetSRID(ST_MakePoint(-92.27, 35.55), 4326))`);
  await fixture.writeDb.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, booking_provider, booking_provider_ref)
    VALUES (3404, 'campflare', 'sugar-1', 'Sugar Loaf', 'recgov', '232713')`);
  await fixture.writeDb.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${RECGOV_ONLY_POI}, 3404)`);
});

afterAll(async () => {
  await fixture?.stop();
});

const search = (filter = {}) => searchCampgroundIds(fixture.readDb, { boundaryGeoJson: TAHOE_BOUNDARY, filter: { amenities: [], ...filter } }, LIMIT, ENABLED_PROVIDERS);

test('answers campground_ids, total_in_boundary, total_matching, truncated', async () => {
  const result = await search();
  expect(Object.keys(result).sort()).toEqual(['campground_ids', 'total_in_boundary', 'total_matching', 'truncated']);
  expect(result.campground_ids).toEqual(expect.arrayContaining([Number(NEAR_POI_ID), Number(FAR_POI_ID), Number(NO_SHOWERS_POI)]));
  expect(result.total_matching).toBe(result.total_in_boundary);
  expect(result.truncated).toBe(false);
});

test('an amenity filter drops a campground that says it lacks the amenity, and keeps one with no data', async () => {
  const result = await search({ amenities: ['showers'] });
  expect(result.campground_ids).not.toContain(Number(NO_SHOWERS_POI));
  expect(result.campground_ids).toContain(Number(FAR_POI_ID));
  expect(result.total_matching).toBe(result.total_in_boundary - 1);
});

test('a group size over the largest site drops the campground; one with no sites passes', async () => {
  const result = await search({ groupSize: 9 });
  expect(result.campground_ids).not.toContain(Number(NEAR_POI_ID));
  expect(result.campground_ids).toContain(Number(FAR_POI_ID));
});

test('a site type the campground has passes', async () => {
  expect((await search({ siteType: 'rv' })).campground_ids).toContain(Number(NEAR_POI_ID));
  expect((await search({ siteType: 'cabin' })).campground_ids).not.toContain(Number(NEAR_POI_ID));
});

test('details keep request order, omit unknown and disabled-provider ids, and carry the summary', async () => {
  const { campgrounds } = await campgroundDetails(fixture.readDb, [FAR_POI_ID, UNKNOWN_POI_ID, UNSERVED_POI_ID, NEAR_POI_ID], ENABLED_PROVIDERS);
  expect(campgrounds.map((c) => c.id)).toEqual([Number(FAR_POI_ID), Number(NEAR_POI_ID)]);
  expect(campgrounds[1]).toMatchObject({
    id: Number(NEAR_POI_ID),
    name: 'Near Campground',
    region: 'CA',
    lng: -120,
    lat: 39.05,
    availability_supported: false,
    amenities: [{ key: 'toilets', label: 'Toilets', present: true }],
    site_counts: { tent: 2, rv: 1 },
    site_total: 3,
    max_people: 8,
  });
  expect(campgrounds[0]).toMatchObject({ site_counts: {}, site_total: 0 });
  expect(campgrounds[1]).not.toHaveProperty('booking_system');
  expect(campgrounds[0]).not.toHaveProperty('max_people');
});

test('a summary names the booking site its campground books through and claims availability for it', async () => {
  const { campgrounds } = await campgroundDetails(fixture.readDb, [NO_SHOWERS_POI], ENABLED_PROVIDERS);
  expect(campgrounds[0]).toMatchObject({ booking_system: 'BC Parks', availability_supported: true });
});

test('a summary for a campground only rec.gov sells, with rec.gov not enabled, names rec.gov but claims no availability', async () => {
  const { campgrounds } = await campgroundDetails(fixture.readDb, [RECGOV_ONLY_POI], ENABLED_PROVIDERS);
  expect(campgrounds[0]).toMatchObject({ booking_system: 'Recreation.gov', availability_supported: false });
});
