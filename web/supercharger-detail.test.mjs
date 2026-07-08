import assert from 'node:assert/strict';
import test from 'node:test';

import { flattenHydratedPoi } from './core.js';

const availabilityProfile = {
  availabilityProfile: {
    sunday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    monday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    tuesday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    wednesday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    thursday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    friday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
    saturday: { congestionValue: Array.from({ length: 24 }, (_, hour) => hour / 24) },
  },
};

const canonicalTeslaDetail = {
  type: 'Feature',
  id: 101,
  geometry: { type: 'Point', coordinates: [-123.0, 49.0] },
  properties: {
    source: 'tesla_supercharger',
    source_id: 'test-slug',
    category: 'tesla_supercharger',
    name: 'Test Supercharger',
    address: {
      street: '100 Main St',
      city: 'Vancouver',
      state: 'BC',
      postcode: 'V6B 1A1',
      country: 'CA',
    },
    raw: {
      location_slug: 'test-slug',
      common_site_name: 'Test Supercharger',
      stall_count: 12,
      max_power_kw: 250,
      time_zone: 'America/Vancouver',
      amenities: ['AMENITIES_RESTROOMS'],
      pricebooks: [
        {
          feeType: 'CHARGING',
          rateBase: 0.42,
          currencyCode: 'CAD',
          uom: 'kwh',
          isTou: false,
          vehicleMakeType: 'TSLA',
        },
      ],
      availability_profile: availabilityProfile,
      detail_payload: {
        commonSiteName: 'Lot B',
        timeZone: 'America/Vancouver',
        openToNonTeslas: false,
        isTrailerFriendly: true,
        accessHours: { twentyFourSeven: true },
        amenities: ['AMENITIES_CAFE'],
        availabilityProfile,
      },
      index_payload: {
        supercharger_function: {
          site_status: 'OPEN',
        },
      },
    },
  },
};

test('flattenHydratedPoi promotes canonical Tesla detail fields for the drawer', () => {
  const p = flattenHydratedPoi(canonicalTeslaDetail).properties;

  assert.equal(p.locationId, 'test-slug');
  assert.equal(p.stallCount, 12);
  assert.equal(p.powerKilowatt, 250);
  assert.equal(p.timeZone, 'America/Vancouver');
  assert.deepEqual(p.availabilityProfile, availabilityProfile);
  assert.deepEqual(p.upstream.detail.availabilityProfile, availabilityProfile);
  assert.equal(p.upstream.detail.accessHours.twentyFourSeven, true);
  assert.equal(p.upstream.detail.isTrailerFriendly, true);
  assert.equal(p.upstream.index.supercharger_function.site_status, 'OPEN');
});

test('supercharger busy-hours renderer accepts canonical availability profile', async () => {
  const drawer = await import('./drawer/supercharger.js');

  assert.equal(typeof drawer.renderBusyHours, 'function');
  const html = drawer.renderBusyHours(availabilityProfile, 'America/Vancouver');

  assert.match(html, /Today's busy hours/);
  assert.match(html, /class="sc-bar"/);
});
