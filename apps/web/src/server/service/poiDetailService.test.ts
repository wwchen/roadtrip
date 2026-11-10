import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { ENABLED_PROVIDERS, UNKNOWN_POI_ID, UNSERVED_POI_ID, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { poiDetail } from './poiDetailService';

const RICH_POI = 3101n;
const ASPIRA_POI = 3102n;
const ALIASED_POI = 3103n;
const RECGOV_ONLY_POI = 3104n;
const NOW = new Date('2026-06-17T20:00:00Z');
const MAHOOD_DEEPLINK =
  'https://camping.bcparks.ca/create-booking/results?transactionLocationId=-2147483429&mapId=-2147483235&searchTabGroupId=0' +
  '&bookingCategoryId=0&startDate=2026-06-17&endDate=2026-06-18&nights=1&isReserving=true&equipmentId=-32768' +
  '&subEquipmentId=-32768&peopleCapacityCategoryCounts=%5B%5B-32767%2Cnull%2C1%2Cnull%5D%5D' +
  '&searchTime=2026-06-17T00%3A00%3A00.000&flexibleSearch=%5Bfalse%2Cfalse%2C%222026-06-17%22%2C1%5D&view=grid' +
  '&resourceLocationId=-2147483486';
const TESLA_POI = 3201n;
const GYM_POI = 3301n;

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  const db = fixture.writeDb;
  await db.execute(sql`INSERT INTO pois (id, poi_type, geom) VALUES
    (${RICH_POI}, 'campground', ST_SetSRID(ST_MakePoint(-120.09, 52.2), 4326)),
    (${ASPIRA_POI}, 'campground', ST_SetSRID(ST_MakePoint(-120.09, 52.2), 4326)),
    (${ALIASED_POI}, 'campground', ST_SetSRID(ST_MakePoint(-119.6, 37.7), 4326)),
    (${RECGOV_ONLY_POI}, 'campground', ST_SetSRID(ST_MakePoint(-92.27, 35.55), 4326)),
    (${TESLA_POI}, 'tesla_supercharger', ST_SetSRID(ST_MakePoint(-121, 37), 4326)),
    (${GYM_POI}, 'planet_fitness_location', ST_SetSRID(ST_MakePoint(-122, 38), 4326))`);
  await db.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, parent_name, kind, status,
      short_description, long_description, reservation_url, location, amenities, links, photos, alerts, cell_service,
      metadata, management, contact, default_campsite_schedule, price, source_payload, max_rv_length)
    VALUES (3001, 'campflare', 'rich-1', 'Rich Camp', ' rich camp ', 'state', 'open',
      'Short text', 'Long text', 'https://www.Example.org:443/book?x', '{"region":"BC","country":"CA","elevation":400}',
      '[{"key":"showers","present":false},{"key":"toilets","detail":"vault"}]', '[{"url":"https://info.test/a"}]',
      '[{"url":"https://img.test/p.jpg"}]', '[{"title":"Notice","body":"Closed"}]',
      '[{"carrier":"verizon","average":3.5,"count":2}]',
      '{"activities":["hiking"],"rating":{"average":4.5,"count":0},"last_updated":" 2026-01-01 "}',
      '{"agency":"BC Parks"}', '{"phone":"555-0101","email":"a@b.test"}', '{"check_in":"14:00"}', '{"minimum":10}',
      '{"vendor":"x"}', 40)`);
  await db.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${RICH_POI}, 3001)`);
  await db.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, location, booking_provider,
      booking_provider_ref, booking_aliases)
    VALUES (3002, 'campflare', 'mahood-1', 'Mahood Lake', '{"latitude":52.1979,"longitude":-120.0902}', 'aspira',
      'bc:-2147483429:-2147483235:-2147483486', '[{"provider":"campflare","ref":"mahood-lake"}]')`);
  await db.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${ASPIRA_POI}, 3002)`);
  await db.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, reservation_url, links,
      booking_provider, booking_provider_ref, booking_aliases)
    VALUES (3003, 'campflare', 'flowing-1', 'Flowing Well', 'https://www.recreation.gov/camping/campgrounds/232001',
      '[{"url":"http://traveloregon.com"}]', 'campflare', 'flowing-well-campground-001',
      '[{"provider":"recgov","ref":"232001"}]')`);
  await db.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${ALIASED_POI}, 3003)`);
  await db.execute(sql`INSERT INTO campgrounds (id, data_provider, data_provider_ref, name, reservation_url, booking_provider,
      booking_provider_ref)
    VALUES (3004, 'campflare', 'sugar-1', 'Sugar Loaf', 'https://www.recreation.gov/camping/campgrounds/232713', 'recgov', '232713')`);
  await db.execute(sql`INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (${RECGOV_ONLY_POI}, 3004)`);
  await db.execute(sql`INSERT INTO tesla_superchargers (id, location_slug, location_guid, common_site_name, site_status,
      access_type, open_to_public, open_to_non_teslas, stall_count, max_power_kw, region, country, time_zone, info_url,
      address, amenities, pricebooks, index_payload, detail_payload)
    VALUES (4001, 'slug-1', 'guid-1', 'Gilroy', 'OPEN', 'public', true, true, 12, 250, 'CA', 'US', 'America/Los_Angeles',
      'https://tesla.test/1', '{"city":"Gilroy"}', '["restrooms",3]', '[]', '{"i":1}', '{"d":2}')`);
  await db.execute(sql`INSERT INTO poi_tesla_superchargers (poi_id, tesla_supercharger_id) VALUES (${TESLA_POI}, 4001)`);
  await db.execute(sql`INSERT INTO planet_fitness_locations (id, location_id, name, phone, info_url, opening_hours, region,
      country, address, payload)
    VALUES (5001, 'pf-1', 'PF Sacramento', '555-0102', 'https://pf.test/1', 'Mo-Su 00:00-24:00', 'CA', 'US',
      '{"city":"Sacramento"}', '{"tags":{"brand":"Planet Fitness"}}')`);
  await db.execute(sql`INSERT INTO poi_planet_fitness_locations (poi_id, planet_fitness_location_id) VALUES (${GYM_POI}, 5001)`);
});

afterAll(async () => {
  await fixture?.stop();
});

const detail = (id: bigint) => poiDetail(fixture.readDb, id, ENABLED_PROVIDERS, NOW);

test("a campground's detail carries the Kotlin fields", async () => {
  const feature = await detail(RICH_POI);
  expect(feature).toMatchObject({ type: 'Feature', id: Number(RICH_POI), geometry: { type: 'Point', coordinates: [-120.09, 52.2] } });
  const properties = feature?.properties;
  expect(properties).toMatchObject({
    source: 'campflare',
    source_id: 'rich-1',
    category: 'campground',
    subcategory: 'state',
    agency: 'BC Parks',
    name: 'Rich Camp',
    region: 'BC',
    country: 'CA',
  });
  expect(properties?.detail).toEqual({
    sources: ['campflare'],
    time_zone: expect.any(String),
    earliest_date: expect.any(String),
    reserve_url: 'https://www.Example.org:443/book?x',
    booking_site: 'example.org',
    phone: '555-0101',
    info_url: 'https://info.test/a',
    address: { region: 'BC', country: 'CA', elevation: 400 },
    description: 'Short text',
    photo_url: 'https://img.test/p.jpg',
    upstream: { vendor: 'x' },
    status: 'open',
    kind: 'state',
    amenities: [
      { key: 'showers', label: 'No showers', present: false },
      { key: 'toilets', label: 'Vault toilets', present: true, detail: 'vault' },
    ],
    cell_coverage: [{ carrier: 'verizon', label: 'Verizon', average: 3.5, count: 2 }],
    activities: ['hiking'],
    price: { minimum: 10 },
    schedule: { check_in: '14:00' },
    max_rv_length: 40,
    links: [{ url: 'https://info.test/a' }],
    alerts: [{ title: 'Notice', body: 'Closed' }],
    connections: {},
    management: { agency: 'BC Parks' },
    contact: { phone: '555-0101', email: 'a@b.test' },
    email: 'a@b.test',
    elevation: 400,
    last_verified: '2026-01-01',
    charger_amenities: [],
    cta: [{ url: 'https://info.test/a', label: 'Visit info.test', kind: 'info' }],
  });
});

test('an Aspira campground gets its dated booking deeplink, its Campflare page and its booking system', async () => {
  const detailOf = (await detail(ASPIRA_POI))?.properties.detail;
  expect(detailOf?.cta).toEqual([
    { url: MAHOOD_DEEPLINK, label: 'Reserve on BC Parks', kind: 'reserve' },
    { url: 'https://campflare.com/campground/mahood-lake', label: 'View on Campflare', kind: 'info' },
  ]);
  expect(detailOf?.booking_system).toBe('BC Parks');
});

test('an Aspira campground with a Campflare alias is served by campflare, registered first, with its zone and dates', async () => {
  const detailOf = (await detail(ASPIRA_POI))?.properties.detail;
  expect(detailOf).toMatchObject({
    availability_supported: true,
    availability_provider: 'campflare',
    time_zone: 'America/Vancouver',
    earliest_date: '2026-06-17',
    latest_date: '2027-06-17',
  });
});

test('a Campflare campground rec.gov also sells is served by campflare while rec.gov is not enabled', async () => {
  const detailOf = (await detail(ALIASED_POI))?.properties.detail;
  expect(detailOf).toMatchObject({ availability_supported: true, availability_provider: 'campflare', latest_date: '2027-06-17' });
});

test('a Campflare campground that rec.gov also sells reserves on rec.gov, then links its Campflare page', async () => {
  const detailOf = (await detail(ALIASED_POI))?.properties.detail;
  expect(detailOf?.cta).toEqual([
    { url: 'https://www.recreation.gov/camping/campgrounds/232001', label: 'Reserve on Recreation.gov', kind: 'reserve' },
    { url: 'https://campflare.com/campground/flowing-well-campground-001', label: 'View on Campflare', kind: 'info' },
  ]);
  expect(detailOf?.booking_system).toBe('Recreation.gov');
});

test('a campground only rec.gov sells, with rec.gov not enabled, reserves on rec.gov but is not availability-supported', async () => {
  const detailOf = (await detail(RECGOV_ONLY_POI))?.properties.detail ?? {};
  expect(detailOf).toMatchObject({ booking_system: 'Recreation.gov' });
  expect(detailOf).not.toHaveProperty('availability_supported');
  expect(detailOf).not.toHaveProperty('latest_date');
});

test('a campground with no booking ref is not availability-supported but still states its zone and earliest date', async () => {
  const detailOf = (await detail(RICH_POI))?.properties.detail ?? {};
  expect(detailOf).not.toHaveProperty('availability_supported');
  expect(detailOf).not.toHaveProperty('availability_provider');
  expect(detailOf).not.toHaveProperty('latest_date');
  expect(detailOf).toHaveProperty('time_zone');
  expect(detailOf).toHaveProperty('earliest_date');
});

test("a Tesla supercharger's detail", async () => {
  const feature = await detail(TESLA_POI);
  expect(feature?.properties).toMatchObject({ source: 'tesla_supercharger', source_id: 'slug-1', category: 'tesla_supercharger', name: 'Gilroy', region: 'CA', country: 'US' });
  expect(feature?.properties.detail).toMatchObject({
    info_url: 'https://tesla.test/1',
    address: { city: 'Gilroy' },
    status: 'OPEN',
    time_zone: 'America/Los_Angeles',
    charger_amenities: ['restrooms', '3'],
    stall_count: 12,
    power_kilowatt: 250,
    pricebooks: [],
    open_to_non_teslas: true,
    upstream: { index: { i: 1 }, detail: { d: 2 } },
  });
  expect(feature?.properties.detail.raw).toMatchObject({ location_slug: 'slug-1' });
});

test("a Planet Fitness location's detail", async () => {
  const feature = await detail(GYM_POI);
  expect(feature?.properties).toMatchObject({ source: 'planet_fitness_location', source_id: 'pf-1', name: 'PF Sacramento' });
  expect(feature?.properties.detail).toMatchObject({
    phone: '555-0102',
    info_url: 'https://pf.test/1',
    opening_hours: 'Mo-Su 00:00-24:00',
    brand: 'Planet Fitness',
    address: { city: 'Sacramento' },
    upstream: { brand: 'Planet Fitness' },
  });
});

test('a campground from a disabled provider is not found', async () => {
  expect(await detail(UNSERVED_POI_ID)).toBeNull();
});

test('an unknown id is not found', async () => {
  expect(await detail(UNKNOWN_POI_ID)).toBeNull();
});
