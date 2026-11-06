import { campsites } from '@roadtrip/db';
import { parseBookingProviderRef, shippedTenantRegistry } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { NEAR_CAMPGROUND_ID, NEAR_POI_ID, UNKNOWN_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { campsiteList, reservationTemplate } from './campsiteListService';

const ALL = ['aspira', 'campflare', 'recgov', 'reserveamerica', 'reservecalifornia'];
const RECGOV_SITE = 'recgov-site-77';

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  await fixture.writeDb.insert(campsites).values({
    campgroundId: NEAR_CAMPGROUND_ID,
    dataProvider: 'campflare',
    dataProviderRef: 'cf-loop-site',
    name: 'Loop Site',
    kind: 'standard',
    loopName: 'A',
    bookingProvider: 'recgov',
    bookingProviderRef: RECGOV_SITE,
    reservationUrl: `https://www.recreation.gov/camping/campsites/${RECGOV_SITE}`,
    photos: [{ url: 'https://img.test/1.jpg' }],
    equipment: ['tent'],
  });
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.execute(
    sql`UPDATE campgrounds SET booking_provider = 'campflare', booking_provider_ref = 'near', booking_aliases = '[{"provider":"recgov","ref":"232447"}]'::jsonb WHERE id = ${NEAR_CAMPGROUND_ID}`,
  );
});

test('an unknown POI has no campsite list', async () => {
  expect(await campsiteList(fixture.readDb, UNKNOWN_POI_ID, [], ALL)).toBeNull();
});

test('campsites list in loop, name, id order with their catalog fields', async () => {
  const list = await campsiteList(fixture.readDb, NEAR_POI_ID, [], ALL);
  expect(list?.type).toBe('campsite');
  expect(list?.campsites[0]?.name).toBe('Loop Site');
  const loop = list!.campsites[0]!;
  expect(loop).toMatchObject({ kind: 'standard', kind_label: expect.any(String), loop_name: 'A', photo_url: 'https://img.test/1.jpg', equipment: ['tent'] });
  expect(loop).not.toHaveProperty('description');
});

test('a campflare campground sold on rec.gov names rec.gov and gets rec.gov templates from the serving provider', async () => {
  const list = await campsiteList(fixture.readDb, NEAR_POI_ID, [], ALL);
  const loop = list!.campsites[0]!;
  expect(loop.booking_system).toBe('Recreation.gov');
  expect(list!.reservation_url_templates[String(loop.id)]).toBe(
    `https://www.recreation.gov/camping/campsites/${RECGOV_SITE}?startDate={start_date}&endDate={end_date}`,
  );
});

test('with only campflare enabled the template comes from the reservation URL', async () => {
  const list = await campsiteList(fixture.readDb, NEAR_POI_ID, [], ['campflare']);
  const loop = list!.campsites[0]!;
  expect(list!.reservation_url_templates[String(loop.id)]).toBe(
    `https://www.recreation.gov/camping/campsites/${RECGOV_SITE}?startDate={start_date}&endDate={end_date}`,
  );
  const plain = list!.campsites.find((site) => site.name !== 'Loop Site')!;
  expect(list!.reservation_url_templates[String(plain.id)]).toBeUndefined();
});

test('no serving provider means no templates but still a booking system', async () => {
  const list = await campsiteList(fixture.readDb, NEAR_POI_ID, [], []);
  expect(list!.reservation_url_templates).toEqual({});
  expect(list!.campsites[0]!.booking_system).toBe('Recreation.gov');
});

test('site_type filters the list', async () => {
  const list = await campsiteList(fixture.readDb, NEAR_POI_ID, ['rv'], ALL);
  expect(list!.campsites.map((site) => site.kind)).toEqual(['rv']);
});

test('an Aspira template prefers the campsite map and resource and fills placeholders', () => {
  const template = reservationTemplate('aspira', {
    parentRef: parseBookingProviderRef('aspira', 'bc:-2147483604:-2147483550:null')!,
    campsiteBookingRef: parseBookingProviderRef('aspira', 'bc:-2147483604:-2147483549:-2147483000'),
    reservationUrl: null,
    vendorSiteId: 'x',
    tenants: shippedTenantRegistry(),
  });
  expect(template).toContain('https://camping.bcparks.ca/create-booking/results?');
  expect(template).toContain('mapId=-2147483549');
  expect(template).toContain('resourceLocationId=-2147483000');
  expect(template).toContain('startDate={start_date}');
  expect(template).toContain('endDate={end_date}');
  expect(template).toContain('nights={nights}');
});
