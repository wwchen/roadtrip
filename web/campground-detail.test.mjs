import assert from 'node:assert/strict';
import test from 'node:test';

import { flattenHydratedPoi } from './core.js';
import {
  cellCoveragePillsHTML,
  parseAmenities,
  parseCellCoverage,
  reserveButtonHTML,
  structuredCampgroundDetailsHTML,
} from './campground-card.js';

const campflareDetail = {
  type: 'Feature',
  id: 5490,
  geometry: { type: 'Point', coordinates: [-120.3147222, 39.5427778] },
  properties: {
    source: 'campground',
    source_id: 'cold-creek-869',
    category: 'campground',
    subcategory: 'established',
    name: 'Cold Creek',
    reserve_url: 'https://www.recreation.gov/camping/campgrounds/232869',
    address: {
      address: {
        city: 'Truckee',
        full: 'Cold Creek Rd, Truckee, CA 96161',
        state_code: 'CA',
        country_code: 'US',
        street1: 'Cold Creek Rd',
        zipcode: '96161',
      },
      directions: 'From Truckee, take Highway 89 north approximately 20 miles.',
    },
    raw: {
      long_description: 'Cold Creek Campground is located on Highway 89.\n\nLake Tahoe is only 35 minutes away.',
      reservation_url: 'https://www.recreation.gov/camping/campgrounds/232869',
      status: 'open',
      status_description: 'Open May through October',
      kind: 'campground',
      location: {
        elevation: 6200,
      },
      price: {
        minimum: 24,
        maximum: 32,
        currency_code: 'USD',
      },
      default_campsite_schedule: {
        check_in_time: '14:00',
        check_out_time: '11:00',
        uniform: true,
      },
      has_pull_through_sites: true,
      big_rig_friendly: false,
      max_rv_length: 35,
      max_trailer_length: 28,
      photos: [
        {
          large_url: 'https://cdn.campflare.com/photo/large.jpg',
          medium_url: 'https://cdn.campflare.com/photo/medium.jpg',
        },
      ],
      amenities: {
        water: false,
        showers: false,
        toilets: true,
        toilet_kind: 'pit',
        fires_allowed: true,
      },
      cell_service: {
        verizon: 0,
        tmobile: 2.5,
      },
      management: {
        agency_name: 'USDA Forest Service',
        agency_website: 'https://www.fs.usda.gov',
      },
      contact: {
        primary_phone: '+1 (530) 994-3401',
        email: 'hello@example.test',
      },
      links: [
        { title: 'Official page', url: 'https://example.test/cold-creek' },
        { title: 'Map', url: 'https://example.test/cold-creek-map' },
      ],
      alerts: [
        { title: 'Water unavailable', description: 'Bring drinking water.' },
      ],
      connections: {
        recreation_gov: '232869',
        ridb: '100001',
      },
      metadata: {
        last_updated: '2026-05-16T06:40:02Z',
      },
    },
  },
};

test('flattenHydratedPoi promotes canonical Campflare fields for the drawer', () => {
  const p = flattenHydratedPoi(campflareDetail).properties;

  assert.equal(p.description, campflareDetail.properties.raw.long_description);
  assert.equal(p.photo_url, 'https://cdn.campflare.com/photo/large.jpg');
  assert.equal(p.agency, 'USDA Forest Service');
  assert.equal(p.phone, '+1 (530) 994-3401');
  assert.equal(p.reserve_url, 'https://www.recreation.gov/camping/campgrounds/232869');
  assert.equal(p.last_verified, '2026-05-16T06:40:02Z');
  assert.equal(p.state, 'CA');
  assert.equal(p.country, 'US');
  assert.equal(p.status, 'open');
  assert.equal(p.status_description, 'Open May through October');
  assert.deepEqual(p.price, campflareDetail.properties.raw.price);
  assert.deepEqual(p.schedule, campflareDetail.properties.raw.default_campsite_schedule);
  assert.equal(p.has_pull_through_sites, true);
  assert.equal(p.big_rig_friendly, false);
  assert.equal(p.max_rv_length, 35);
  assert.equal(p.max_trailer_length, 28);
  assert.equal(p.elevation, 6200);
  assert.deepEqual(p.links, campflareDetail.properties.raw.links);
  assert.deepEqual(p.alerts, campflareDetail.properties.raw.alerts);
  assert.deepEqual(p.connections, campflareDetail.properties.raw.connections);
  assert.equal(p.upstream.FacilityDirections, 'From Truckee, take Highway 89 north approximately 20 miles.');
});

test('campground drawer helpers accept canonical amenities and cell service', () => {
  const p = flattenHydratedPoi(campflareDetail).properties;

  assert.deepEqual(parseAmenities(p), ['No water', 'No showers', 'Pit toilets', 'Fires allowed']);
  assert.deepEqual(parseCellCoverage(p), { verizon: 0, tmobile: 2.5 });
  assert.match(cellCoveragePillsHTML(parseCellCoverage(p)), /T-Mobile/);
  assert.match(reserveButtonHTML(p, 'cg-btn'), /View on recreation\.gov/);
});

test('structuredCampgroundDetailsHTML renders Campflare campground fields', () => {
  const p = flattenHydratedPoi(campflareDetail).properties;
  const html = structuredCampgroundDetailsHTML(p);

  assert.match(html, /Stay details/);
  assert.match(html, /Open May through October/);
  assert.match(html, /\$24-\$32/);
  assert.match(html, /Check-in/);
  assert.match(html, /2:00 PM/);
  assert.match(html, /Check-out/);
  assert.match(html, /11:00 AM/);
  assert.match(html, /Max RV/);
  assert.match(html, /35 ft/);
  assert.match(html, /Pull-through/);
  assert.match(html, /Yes/);
  assert.match(html, /Big-rig friendly/);
  assert.match(html, /No/);
  assert.match(html, /6,200 ft/);
  assert.match(html, /Cold Creek Rd/);
  assert.match(html, /hello@example\.test/);
  assert.match(html, /https:\/\/www\.fs\.usda\.gov/);
  assert.match(html, /Official page/);
  assert.match(html, /Water unavailable/);
  assert.match(html, /recreation_gov/);
  assert.doesNotMatch(html, /source_payload/);
});
