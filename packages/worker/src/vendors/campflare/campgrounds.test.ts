import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { AmenityKey, BookingProvider, Carrier } from '@roadtrip/domain';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { TransformResult } from '../../etl/source-etl.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { CampflareCampgroundsEtl } from './campgrounds.js';

const TEMP_DIR_PREFIX = 'campflare-campgrounds-';
const HTTP_OK = 200;
const INPUT_SLUG = 'campflare-campgrounds-export';
const PART = 'part-000001';

let dir = '';

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function captured(rowsJson: string): InputBundle {
  const file = path.join(dir, 'page.json');
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"part":"${PART}","payload":${rowsJson}}`,
  );
  return new InputBundle(new Map([[INPUT_SLUG, [parseEnvelope(file)]]]));
}

function transformed(rowsJson: string): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = new CampflareCampgroundsEtl();
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const parsed of etl.parse(captured(rowsJson))) {
    if (!parsed.ok) throw new Error(parsed.errors.join());
    results.push(...etl.transform(parsed.dto));
  }
  return results;
}

function recordOf(rowJson: string): CampgroundUpsertCandidate {
  const [only, ...rest] = transformed(`[${rowJson}]`);
  if (only === undefined || !only.ok || rest.length > 0) throw new Error('expected exactly one ok record');
  return only.record;
}

const MINIMAL = '"id":"c1","name":"Camp","location":{"latitude":1,"longitude":2}';

const FULL_ROW = `{
  "id": "upper-pines-campground-447",
  "name": " Upper Pines ",
  "status": "open",
  "status_description": "Open for the season",
  "kind": "established",
  "short_description": "A Yosemite campground.",
  "location": {"latitude": 37.739, "longitude": -119.565, "elevation": 4000, "address": {"state_code": "CA", "country_code": "US"}},
  "default_campsite_schedule": {"check_in_time": "14:00", "uniform": true},
  "amenities": {"toilets": true, "water": true, "wifi": null, "toilet_kind": "vault"},
  "alerts": [{"title": "Fire ban", "content": "No open flames.", "end_date": "2026-09-01"}, {"title": "Nothing to say"}],
  "max_rv_length": 35,
  "reservation_url": "https://www.recreation.gov/camping/campgrounds/232447",
  "links": [{"url": "https://www.nps.gov/yose", "title": "NPS"}],
  "photos": [{"original_url": "https://cdn.example/p.jpg"}],
  "price": {"minimum": 36, "currency_code": "USD"},
  "cell_service": {"verizon": 0.6},
  "management": {"agency_name": "National Park Service"},
  "contact": {"primary_phone": "555-0100"},
  "connections": {"ridb_facility_id": "232447"},
  "metadata": {"last_updated": "2026-07-01T00:00:00Z"}
}`;

describe('a full campground row', () => {
  let record: CampgroundUpsertCandidate;

  beforeAll(() => {
    record = recordOf(FULL_ROW);
  });

  test('identity, coordinates and booking', () => {
    expect(record).toMatchObject({
      name: 'Upper Pines',
      status: 'open',
      kind: 'established',
      latitude: 37.739,
      longitude: -119.565,
      sourceUrl: 'https://campflare.com/campground/upper-pines-campground-447',
      bookingProvider: BookingProvider.CAMPFLARE,
      bookingProviderRef: 'upper-pines-campground-447',
      bookingAliases: [{ provider: BookingProvider.RECGOV, ref: '232447' }],
      maxRvLength: 35,
      reservationUrl: 'https://www.recreation.gov/camping/campgrounds/232447',
    });
    expect(record.dataProviderRef.serialize()).toBe('upper-pines-campground-447');
    expect(record.statusDescription).toBeUndefined();
    expect(record.shortDescription).toBeUndefined();
  });

  test('the bags land on the typed vocabularies', () => {
    expect(record.amenities).toEqual([
      { key: AmenityKey.TOILETS, present: true, detail: 'vault' },
      { key: AmenityKey.WATER, present: true },
    ]);
    expect(record.cellService).toEqual([{ carrier: Carrier.VERIZON, average: 0.6 }]);
    expect(record.price).toEqual({ minimum: 36, currency: 'USD' });
    expect(record.defaultCampsiteSchedule).toEqual({ check_in: '14:00' });
    expect(record.alerts).toEqual([{ title: 'Fire ban', body: 'No open flames.', ends_on: '2026-09-01' }]);
    expect(record.metadata).toEqual({ last_updated: '2026-07-01T00:00:00Z' });
    expect(record.management).toEqual({ agency: 'National Park Service' });
    expect(record.contact).toEqual({ phone: '555-0100' });
    expect(record.photos).toEqual([{ url: 'https://cdn.example/p.jpg' }]);
    expect(record.location).toMatchObject({ elevation: 4000, address: { state: 'CA', country: 'US' } });
  });

  test('connections and the source payload are the row verbatim, name untrimmed', () => {
    expect(JSON.parse(record.connections as string)).toEqual({ ridb_facility_id: '232447' });
    expect((JSON.parse(record.sourcePayload as string) as { name: string }).name).toBe(' Upper Pines ');
  });

  test('the Campflare source link is appended last', () => {
    expect(record.links?.at(-1)).toEqual({
      url: 'https://campflare.com/campground/upper-pines-campground-447',
      title: 'Campflare source',
    });
  });
});

describe('booking aliases', () => {
  test('fall back to the reservation url facility id when connections carry none', () => {
    const record = recordOf(
      '{"id":"white-wolf","name":"White Wolf","location":{"latitude":1,"longitude":2},"reservation_url":"https://www.recreation.gov/camping/campgrounds/10083567"}',
    );
    expect(record.bookingAliases).toEqual([{ provider: BookingProvider.RECGOV, ref: '10083567' }]);
  });

  test('a campground that rec.gov never sells carries none', () => {
    const record = recordOf(`{${MINIMAL},"reservation_url":"https://washington.goingtocamp.com/"}`);
    expect(record.bookingAliases).toEqual([]);
  });
});

describe('the five guards', () => {
  test('a row reports every guard it fails, in the order id, name, location, latitude, longitude', () => {
    expect(transformed('[{}]')).toEqual([
      {
        ok: false,
        errors: [
          'missing id',
          'missing name',
          'missing location',
          'missing or invalid latitude',
          'missing or invalid longitude',
        ],
      },
    ]);
  });

  test('a row carries its id when it has one, and one failing guard reports one error', () => {
    expect(
      transformed(
        '[{"id":"missing-name","location":{"latitude":1,"longitude":2}},{"id":"missing-lat","name":"No Lat","location":{"longitude":2}},{"id":"missing-lon","name":"No Lon","location":{"latitude":1}}]',
      ),
    ).toEqual([
      { ok: false, sourceId: 'missing-name', errors: ['missing name'] },
      { ok: false, sourceId: 'missing-lat', errors: ['missing or invalid latitude'] },
      { ok: false, sourceId: 'missing-lon', errors: ['missing or invalid longitude'] },
    ]);
  });

  test('a location that is not an object fails the location guard and both coordinate guards', () => {
    expect(transformed('[{"id":"x","name":"n","location":"here"}]')).toEqual([
      {
        ok: false,
        sourceId: 'x',
        errors: ['missing location', 'missing or invalid latitude', 'missing or invalid longitude'],
      },
    ]);
  });

  test('a coordinate out of range even after rescaling is invalid, and a blank name or id is missing', () => {
    expect(transformed('[{"id":"  ","name":"","location":{"latitude":2000000000,"longitude":2}}]')).toEqual([
      { ok: false, errors: ['missing id', 'missing name', 'missing or invalid latitude'] },
    ]);
  });

  test('a good row among bad ones still transforms', () => {
    const results = transformed(`[{"id":"a"},{${MINIMAL}}]`);
    expect(results.map((result) => result.ok)).toEqual([false, true]);
  });
});

describe('links', () => {
  test('the source link is added when the dump has none', () => {
    expect(recordOf(`{${MINIMAL}}`).links).toEqual([
      { url: 'https://campflare.com/campground/c1', title: 'Campflare source' },
    ]);
  });

  test('an existing href to the source page is not duplicated', () => {
    const record = recordOf(
      `{${MINIMAL},"links":[{"title":"Existing","href":"https://campflare.com/campground/c1"}]}`,
    );
    expect(record.links).toEqual([{ url: 'https://campflare.com/campground/c1', title: 'Existing' }]);
  });
});

describe('absent values', () => {
  test('a json null string field is absent', () => {
    expect(recordOf(`{${MINIMAL},"kind":null,"status":"null"}`)).toMatchObject({ kind: null, status: null });
  });

  test('no agency leaves management null', () => {
    expect(recordOf(`{${MINIMAL}}`).management).toBeNull();
  });

  test('a null cell_service or contact is the empty default', () => {
    const record = recordOf(`{${MINIMAL},"cell_service":null,"contact":null,"amenities":null}`);
    expect(record).toMatchObject({ cellService: [], contact: null, amenities: [] });
  });
});
