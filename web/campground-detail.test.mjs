import assert from 'node:assert/strict';
import test from 'node:test';

import { flattenHydratedPoi } from './core.js';
import {
  cellCoveragePillsHTML,
  parseAmenities,
  parseCellCoverage,
  reserveButtonHTML,
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
      address: { state_code: 'CA', country_code: 'US' },
      directions: 'From Truckee, take Highway 89 north approximately 20 miles.',
    },
    raw: {
      long_description: 'Cold Creek Campground is located on Highway 89.\n\nLake Tahoe is only 35 minutes away.',
      reservation_url: 'https://www.recreation.gov/camping/campgrounds/232869',
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
      },
      contact: {
        primary_phone: '+1 (530) 994-3401',
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
  assert.equal(p.upstream.FacilityDirections, 'From Truckee, take Highway 89 north approximately 20 miles.');
});

test('campground drawer helpers accept canonical amenities and cell service', () => {
  const p = flattenHydratedPoi(campflareDetail).properties;

  assert.deepEqual(parseAmenities(p), ['No water', 'No showers', 'Pit toilets', 'Fires allowed']);
  assert.deepEqual(parseCellCoverage(p), { verizon: 0, tmobile: 2.5 });
  assert.match(cellCoveragePillsHTML(parseCellCoverage(p)), /T-Mobile/);
  assert.match(reserveButtonHTML(p, 'cg-btn'), /View on recreation\.gov/);
});
