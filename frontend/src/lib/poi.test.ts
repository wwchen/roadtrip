import { describe, expect, test } from 'vitest';
import { flattenHydratedPoi, type PoiFeature } from './poi';

// One fixture per flattener branch, plus malformed and sparse inputs.

const FIXTURES: Readonly<Record<string, PoiFeature>> = {
  campgroundNested: {
    id: 42,
    type: 'Feature',
    properties: {
      category: 'campground',
      name: 'Manzanita Lake',
      agency: 'National Park Service',
      detail: {
        description: 'Lakeside sites near the Loomis Museum.',
        photo_url: 'https://example.test/m.jpg',
        phone: '530.336.5521',
        email: 'lavo_info@nps.gov',
        elevation: 1798,
        cell_coverage: 'weak',
        reserve_url: 'https://example.test/reserve',
        status: 'Open',
        status_description: 'Open seasonally',
        price: { minimum: 26, maximum: 36, currency_code: 'USD' },
        schedule: 'May-Oct',
        last_verified: '2026-06-01',
        // The vendor record verbatim. Inert for a campground — the facts above
        // are what the drawer actually reads, named, from the backend now.
        raw: {
          description: '  Lakeside sites near the Loomis Museum.  ',
          management: { agency_name: 'National Park Service' },
          contact: { primary_phone: '530.336.5521', email: 'lavo_info@nps.gov' },
          location: { elevation: 1798, directions: 'Hwy 89 north entrance' },
          metadata: { last_updated: '2026-06-01' },
          photos: [{ medium_url: 'https://example.test/m.jpg', large_url: '' }],
          price: { minimum: 26, maximum: 36, currency_code: 'USD' },
          status: 'Open',
          status_description: 'Open seasonally',
          reservation_url: 'https://example.test/reserve',
          default_campsite_schedule: 'May-Oct',
          cell_service: 'weak',
        },
      },
      address: { city: 'Mineral', state_code: 'CA', postcode: '96063' },
    },
  },

  // `detail` arrives as an encoded JSON string from the JSONB column.
  campgroundJsonStrings: {
    id: 'poi-7',
    properties: {
      category: 'campground',
      subcategory: 'rv-park',
      agency: 'USFS',
      detail: JSON.stringify({
        address: { full: '1 Loop Rd', directions: 'Second left' },
        email: 'usfs-rvpark@example.test',
        raw: { management: { agency: 'USFS' }, price: { minimum: 20, maximum: 20 } },
      }),
    },
  },

  // A charger as `/api/pois/{id}` sends it: its canonical columns are named
  // fields on `detail`, same as a campground or a gym.
  supercharger: {
    id: 'sc-1',
    properties: {
      category: 'tesla_supercharger',
      name: 'Redding, CA',
      detail: {
        stall_count: 12,
        power_kilowatt: 250,
        status: 'CONSTRUCTION',
        time_zone: 'America/Los_Angeles',
        amenities: ['AMENITIES_WIFI'],
        twenty_four_seven: true,
        open_to_non_teslas: false,
        pricebooks: [],
        availability_profile: { availabilityProfile: { weekday: 'busy' } },
        upstream: {
          index: { supercharger_function: { site_status: 'CONSTRUCTION' } },
          detail: { commonSiteName: 'Downtown Redding' },
        },
      },
    },
  },

  // A gym as `/api/pois/{id}` sends it: hours, brand and the tag map are named
  // fields on `detail`. Nothing reaches into `payload.tags` any more.
  planetFitness: {
    id: 'pf-1',
    properties: {
      category: 'planet-fitness',
      name: 'Planet Fitness',
      detail: {
        phone: '+1 515-555-0113',
        info_url: 'https://www.planetfitness.com/gyms/ankeny-ia',
        opening_hours: 'Mo-Su 00:00-24:00',
        brand: 'Planet Fitness',
        upstream: { brand: 'Planet Fitness', opening_hours: 'Mo-Su 00:00-24:00' },
        raw: {
          location_id: 'node-123',
          name: 'Planet Fitness',
          opening_hours: 'Mo-Su 00:00-24:00',
          brand: 'Planet Fitness',
          payload: { type: 'node', id: 123 },
        },
      },
    },
  },

  // The sparse end: an element that tagged nothing. `brand` is still there — it
  // is a constant of the source, not something the element had to tag.
  planetFitnessBare: {
    id: 'pf-2',
    properties: {
      category: 'planet_fitness_location',
      detail: {
        brand: 'Planet Fitness',
        raw: { location_id: 'node-9', name: 'Planet Fitness', payload: { type: 'node', id: 9 } },
      },
    },
  },

  // A recreation.gov campground: `detail.upstream` is the RIDB facility record the
  // backend captured, which is what `upstream-html.ts` reads its fields off.
  campgroundUpstream: {
    id: 'cg-ridb',
    properties: {
      category: 'campground',
      detail: {
        upstream: {
          RECAREA: { RecAreaName: 'Lassen' },
          FacilityDirections: 'Hwy 89 north entrance',
          StayLimit: '14 days',
        },
        raw: {},
      },
    },
  },

  nestedAddress: {
    id: 5,
    properties: {
      category: 'poi',
      address: {
        address: { street1: '10 Ranger Way', city: 'Mineral', postal_code: '96063', country_code: 'US' },
      },
      info_url: 'https://example.test/info',
    },
  },

  malformedDetail: {
    id: 6,
    properties: { category: 'campground', detail: '{not json', raw: '[1,2,3]' },
  },

  empty: {},
  noProperties: { id: 1, properties: null },
};

const flatten = (key: keyof typeof FIXTURES) =>
  flattenHydratedPoi(structuredClone(FIXTURES[key]!)).properties;

describe('a campground, whose facts all arrive named', () => {
  test('carries management, contact, and location facts through from `detail`', () => {
    const p = flatten('campgroundNested');
    expect(p.agency).toBe('National Park Service');
    expect(p.phone).toBe('530.336.5521');
    expect(p.email).toBe('lavo_info@nps.gov');
    expect(p.elevation).toBe(1798);
    expect(p.cell_coverage).toBe('weak');
    expect(p.reserve_url).toBe('https://example.test/reserve');
    expect(p.last_verified).toBe('2026-06-01');
  });

  test('carries the schedule through under its own name', () => {
    expect(flatten('campgroundNested').schedule).toBe('May-Oct');
  });

  test('takes the upstream table from the backend, whatever the vendor shipped', () => {
    // Was synthesised here from three Campflare keys, one renamed to a RIDB field
    // name, so a recreation.gov record — which has none of them — rendered nothing.
    expect(flatten('campgroundUpstream').upstream).toEqual({
      RECAREA: { RecAreaName: 'Lassen' },
      FacilityDirections: 'Hwy 89 north entrance',
      StayLimit: '14 days',
    });
  });

  test('a campground whose source sent no upstream record has no table', () => {
    expect(flatten('campgroundNested').upstream).toBeUndefined();
  });

  test('parses detail and raw when they arrive as JSON strings', () => {
    expect(flatten('campgroundJsonStrings').email).toBe('usfs-rvpark@example.test');
  });

  test('a campground subcategory overrides the category', () => {
    expect(flatten('campgroundJsonStrings').category).toBe('rv-park');
  });

  test('drops the nested detail and raw keys', () => {
    const p = flatten('campgroundNested');
    expect(p).not.toHaveProperty('detail');
    expect(p).not.toHaveProperty('raw');
  });
});

describe('a charger, whose facts all arrive named', () => {
  test('carries its canonical columns, and the upstream index/detail captures, straight through', () => {
    const p = flatten('supercharger');
    expect(p).toMatchObject({
      stall_count: 12,
      power_kilowatt: 250,
      status: 'CONSTRUCTION',
      time_zone: 'America/Los_Angeles',
      amenities: ['AMENITIES_WIFI'],
      twenty_four_seven: true,
      open_to_non_teslas: false,
    });
    const upstream = p.upstream as Record<string, unknown>;
    expect(upstream.index).toEqual({ supercharger_function: { site_status: 'CONSTRUCTION' } });
    expect(upstream.detail).toEqual({ commonSiteName: 'Downtown Redding' });
  });
});

describe('a gym, whose facts all arrive named', () => {
  test('carries hours, brand and the upstream table straight through', () => {
    const p = flatten('planetFitness');
    expect(p.opening_hours).toBe('Mo-Su 00:00-24:00');
    expect(p.brand).toBe('Planet Fitness');
    expect(p.upstream).toMatchObject({ brand: 'Planet Fitness', opening_hours: 'Mo-Su 00:00-24:00' });
  });

  test('takes its website from the canonical info_url, like every other category', () => {
    expect(flatten('planetFitness').website).toBe('https://www.planetfitness.com/gyms/ankeny-ia');
  });

  test('a record the source tagged nothing about carries no hours and no table', () => {
    const p = flatten('planetFitnessBare');
    expect(p.opening_hours).toBeUndefined();
    expect(p.upstream).toBeUndefined();
    expect(p.website).toBe('');
    expect(p.brand).toBe('Planet Fitness');
  });
});

describe('address flattening', () => {
  test('reads a doubly-nested address object', () => {
    expect(flatten('nestedAddress')).toMatchObject({
      street: '10 Ranger Way',
      city: 'Mineral',
      postcode: '96063',
      country: 'US',
    });
  });

  test('keeps both website and infoUrl alive from info_url', () => {
    const p = flatten('nestedAddress');
    expect(p.website).toBe('https://example.test/info');
    expect(p.infoUrl).toBe('https://example.test/info');
  });

  test('absent address parts become empty strings, not undefined', () => {
    expect(flatten('planetFitness')).toMatchObject({ city: '', street: '', postcode: '' });
  });
});

describe('malformed and empty input', () => {
  test('treats an unparseable detail and an array raw as absent', () => {
    const p = flatten('malformedDetail');
    expect(p).not.toHaveProperty('detail');
    expect(p).not.toHaveProperty('raw');
    expect(p.agency).toBeUndefined();
  });

  test('does not throw on a feature with no properties', () => {
    expect(() => flatten('empty')).not.toThrow();
    expect(() => flatten('noProperties')).not.toThrow();
  });

  test('preserves non-properties feature members', () => {
    const out = flattenHydratedPoi(structuredClone(FIXTURES.campgroundNested!));
    expect(out.type).toBe('Feature');
    expect(out.id).toBe(42);
    expect(out.properties.id).toBe(42);
  });

  test('does not mutate the input feature', () => {
    const input = structuredClone(FIXTURES.campgroundNested!);
    const before = structuredClone(input);
    flattenHydratedPoi(input);
    expect(input).toEqual(before);
  });
});

describe('re-flattening', () => {
  test.each([
    ['campgroundNested'],
    ['campgroundJsonStrings'],
    ['campgroundUpstream'],
    ['nestedAddress'],
    ['malformedDetail'],
    ['noProperties'],
    ['empty'],
    // Was NOT a no-op while hours were dug out of `raw`, which the first pass eats.
    ['planetFitness'],
    ['planetFitnessBare'],
    ['supercharger'],
  ] as const)('is a no-op for %s', (key) => {
    const once = flattenHydratedPoi(structuredClone(FIXTURES[key]!));
    const twice = flattenHydratedPoi(structuredClone(once));
    expect(twice).toEqual(once);
  });
});
