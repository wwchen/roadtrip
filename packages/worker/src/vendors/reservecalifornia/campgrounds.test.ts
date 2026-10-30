import { AmenityKey, BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { parseEnvelope, parseKeepingDigits, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { loadPoiRegistry } from '../../registry/poi-registry.js';
import {
  highlightAmenities,
  isActivityHighlight,
  parseHighlights,
  ReserveCaliforniaCampgroundsEtl,
} from './campgrounds.js';
import type { ReserveCaliforniaCatalog } from './catalog.js';

const SLUG = 'reservecalifornia-campgrounds';
const INPUT = 'reservecalifornia-catalog';
const RAW_DIR = '/unused';
const HTTP_OK = 200;
const FETCHED_AT = '2026-06-22T20:14:13Z';
const OBJECT_NOT_PRIMITIVE = 'Element a JsonObject is not a JsonPrimitive';
const ARRAY_NOT_PRIMITIVE = 'Element a JsonArray is not a JsonPrimitive';
const NOT_AN_OBJECT = 'Element is not a JsonObject';
const SOLE_INPUT_REFUSAL = 'soleEnvelopes() requires exactly one data_source input; got 2';
const NO_ENVELOPES = `${SLUG}: no envelopes captured (run fetch_reservecalifornia.py first)`;
const NO_FACILITIES = `${SLUG}: no ReserveCalifornia place payloads with facilities parsed`;
const FIXTURE_ROOT = decodeURIComponent(new URL('./fixtures/', import.meta.url).pathname);
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const PRODUCTION_CTX = TransformCtx.load(RAW_DIR, loadPoiRegistry(PRODUCTION_REGISTRY_PATH));
const BEYOND_SAFE = '9007199254740993';
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);

interface Capture {
  part?: string;
  payload: unknown;
}

function envelopeOf(capture: Capture): Envelope {
  return {
    fetcher: 'f',
    fetcherVersion: '1',
    fetchedAt: FETCHED_AT,
    request: { url: 'https://example.test/', method: 'GET', headers: {} },
    response: { status: HTTP_OK, headers: {} },
    pollerRunId: null,
    payload: capture.payload,
    ...(capture.part === undefined ? {} : { part: capture.part }),
  };
}

function bundleOf(captures: Capture[], input = INPUT): InputBundle {
  return new InputBundle(new Map([[input, captures.map(envelopeOf)]]));
}

function objectOf(text: string): JsonObject {
  return parseKeepingDigits(text) as JsonObject;
}

interface PlaceFields {
  placeId?: string;
  name?: string;
  lat?: string;
  lon?: string;
  facilities?: string;
  extra?: string;
}

function placeText(fields: PlaceFields = {}): string {
  const placeId = fields.placeId ?? '690';
  const name = fields.name ?? '"Emerald Bay SP"';
  const lat = fields.lat ?? '"38.954"';
  const lon = fields.lon ?? '"-120.094"';
  const facilities = fields.facilities ?? '{"611":{"FacilityId":611},"612":{"FacilityId":612}}';
  const members = [
    `"PlaceId":${placeId}`,
    `"Name":${name}`,
    `"Latitude":${lat}`,
    `"Longitude":${lon}`,
    `"Facilities":${facilities}`,
    fields.extra ?? '',
  ].filter((member) => member !== '');
  return `{"SelectedPlace":{${members.join(',')}}}`;
}

function placeCapture(fields: PlaceFields = {}, part = 'place-690'): Capture {
  return { part, payload: objectOf(placeText(fields)) };
}

function facilityCapture(text: string, part = 'facility-611'): Capture {
  return { part, payload: objectOf(text) };
}

function gridCapture(text: string, part = 'grid-611'): Capture {
  return { part, payload: objectOf(text) };
}

function fixtureCapture(name: string): Capture {
  const envelope = parseEnvelope(`${FIXTURE_ROOT}${name}.json`);
  return { part: envelope.part as string, payload: envelope.payload };
}

const COMPANION_ID = 999999n;
const COMPANION: Capture = placeCapture(
  { placeId: COMPANION_ID.toString(), name: '"Companion"', facilities: '{"1":{"FacilityId":1}}' },
  `place-${COMPANION_ID}`,
);

interface Run {
  catalog: ReserveCaliforniaCatalog | null;
  results: TransformResult<CampgroundUpsertCandidate>[];
}

function run(captures: Capture[]): Run {
  const etl = new ReserveCaliforniaCampgroundsEtl(SLUG);
  const outcome: Run = { catalog: null, results: [] };
  for (const parsed of etl.parse(bundleOf(captures))) {
    if (!parsed.ok) continue;
    outcome.catalog = parsed.dto;
    outcome.results.push(...etl.transform(parsed.dto, PRODUCTION_CTX));
  }
  return outcome;
}

function placeOrNull(fields: PlaceFields = {}) {
  const { catalog } = run([placeCapture(fields, 'place-1'), COMPANION]);
  return [...(catalog?.places.values() ?? [])].find((place) => place.placeId !== COMPANION_ID) ?? null;
}

function placeOf(fields: PlaceFields = {}) {
  const place = placeOrNull(fields);
  if (place === null) throw new Error('place did not parse');
  return place;
}

function refusedWith(fields: PlaceFields): () => void {
  return () => {
    run([placeCapture(fields, 'place-1'), COMPANION]);
  };
}

function parsedOf(captures: Capture[]): ParseResult<ReserveCaliforniaCatalog>[] {
  return [...new ReserveCaliforniaCampgroundsEtl(SLUG).parse(bundleOf(captures))];
}

function transformedOf(captures: Capture[]): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = new ReserveCaliforniaCampgroundsEtl(SLUG);
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const parsed of etl.parse(bundleOf(captures))) {
    if (!parsed.ok) throw new Error(parsed.errors.join());
    results.push(...etl.transform(parsed.dto, PRODUCTION_CTX));
  }
  return results;
}

function recordsOf(captures: Capture[]): CampgroundUpsertCandidate[] {
  return transformedOf(captures).map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function payloadOf(record: CampgroundUpsertCandidate): Record<string, unknown> {
  return JSON.parse(record.sourcePayload as string) as Record<string, unknown>;
}

describe('transform', () => {
  test('a place becomes a campground with the canonical ref carrying its place id and facility ids', () => {
    const [campground] = recordsOf([
      placeCapture({
        extra: '"ImageUrl":"https://cdn.example/emerald.jpg","Description":"Lakefront camping."',
      }),
    ]);
    expect(campground?.dataProviderRef).toEqual(DataProviderRef.reserveCalifornia('690'));
    expect(campground?.bookingProvider).toBe(BookingProvider.RESERVECALIFORNIA);
    expect(campground?.bookingProviderRef).toBe('690:611,612');
    expect(campground?.name).toBe('Emerald Bay SP');
    expect(campground?.kind).toBe('state');
    expect(campground?.reservationUrl).toBe('https://reservecalifornia.com/park/690');
    expect(campground?.sourceUrl).toBe('https://reservecalifornia.com/park/690');
    expect(campground?.links).toEqual([{ url: 'https://reservecalifornia.com/park/690' }]);
    expect(campground?.mediumDescription).toBe('Lakefront camping.');
    expect(campground?.photos).toEqual([{ url: 'https://cdn.example/emerald.jpg' }]);
    expect(campground?.location).toEqual({ latitude: 38.954, longitude: -120.094, region: 'CA', country: 'US' });
    expect(campground?.management).toEqual({ agency: 'California State Parks' });
    expect(campground?.latitude).toBe(38.954);
    expect(campground?.longitude).toBe(-120.094);
  });

  test('highlight labels map onto the amenity vocabulary and activities land in metadata', () => {
    const [campground] = recordsOf([
      placeCapture({
        extra:
          '"Allhighlights":" restrooms <br/>Rinse Showers<br>Store - Convenience<BR />Fire Rings<br>Museum<br>Hiking<br>Museum"',
      }),
    ]);
    expect(campground?.amenities).toEqual([
      { key: AmenityKey.TOILETS, present: true },
      { key: AmenityKey.SHOWERS, present: true },
      { key: AmenityKey.CAMP_STORE, present: true },
      { key: AmenityKey.FIRES_ALLOWED, present: true },
      { key: AmenityKey.OTHER, present: true, detail: 'Museum' },
    ]);
    expect(campground?.metadata).toEqual({ activities: ['Hiking'] });
  });

  test('a place with no activities states no metadata and one with none of anything states no photo', () => {
    const [campground] = recordsOf([placeCapture()]);
    expect(campground?.metadata).toBeNull();
    expect(campground?.photos).toEqual([]);
    expect(campground?.mediumDescription).toBeNull();
    expect(campground?.amenities).toEqual([]);
  });

  test('the booking ref lists the facility ids in ascending numeric order, not the order written', () => {
    const [campground] = recordsOf([
      placeCapture({
        facilities: '{"611":{"FacilityId":10},"612":{"FacilityId":9},"613":{"FacilityId":100},"614":{"FacilityId":9}}',
      }),
    ]);
    expect(campground?.bookingProviderRef).toBe('690:9,10,100');
  });

  test('facility ids past 2^53 keep every digit in the booking ref', () => {
    const [campground] = recordsOf([
      placeCapture({ facilities: `{"1":{"FacilityId":${BEYOND_SAFE}},"2":{"FacilityId":9007199254740992}}` }),
    ]);
    expect(campground?.bookingProviderRef).toBe(`690:9007199254740992,${BEYOND_SAFE}`);
  });

  test('a place id past 2^53 keeps every digit in the ref and the data provider id', () => {
    const [campground] = recordsOf([placeCapture({ placeId: BEYOND_SAFE })]);
    expect(campground?.dataProviderRef).toEqual(DataProviderRef.reserveCalifornia(BEYOND_SAFE));
    expect(campground?.bookingProviderRef).toBe(`${BEYOND_SAFE}:611,612`);
    expect(campground?.reservationUrl).toBe(`https://reservecalifornia.com/park/${BEYOND_SAFE}`);
  });

  test('the source payload is the selected place as captured', () => {
    const [campground] = recordsOf([placeCapture({ extra: '"ParkSize":12.50,"Restrictions":{"MinimumStay":1}' })]);
    expect(payloadOf(campground as CampgroundUpsertCandidate)).toMatchObject({
      PlaceId: 690,
      Name: 'Emerald Bay SP',
      Restrictions: { MinimumStay: 1 },
    });
  });

  test('a place with no facility ids is reported bad and the places after it still transform', () => {
    const results = transformedOf([
      placeCapture({ placeId: '1', facilities: '{}' }, 'place-1'),
      placeCapture({ placeId: '2' }, 'place-2'),
    ]);
    expect(results[0]).toEqual({ ok: false, sourceId: '1', errors: ['place has no facility ids'] });
    expect(results[1]?.ok).toBe(true);
  });

  test('places are emitted in the order their pages arrived', () => {
    const records = recordsOf([
      placeCapture({ placeId: '30' }, 'place-30'),
      placeCapture({ placeId: '4' }, 'place-4'),
      placeCapture({ placeId: '200' }, 'place-200'),
    ]);
    expect(records.map((record) => record.dataProviderRef.serialize())).toEqual(['30', '4', '200']);
  });
});

describe('parse', () => {
  test('no envelopes is refused with the capture hint', () => {
    expect(() => parsedOf([])).toThrow(NO_ENVELOPES);
  });

  test('two inputs are refused as the sole-input rule', () => {
    const bundle = new InputBundle(
      new Map([
        ['a', [envelopeOf(placeCapture())]],
        ['b', [envelopeOf(placeCapture())]],
      ]),
    );
    expect(() => [...new ReserveCaliforniaCampgroundsEtl(SLUG).parse(bundle)]).toThrow(SOLE_INPUT_REFUSAL);
  });

  test('a catalog with no place carrying facilities is a bad parse, not an empty success', () => {
    expect(parsedOf([placeCapture({ facilities: '{}' })])).toEqual([{ ok: false, errors: [NO_FACILITIES] }]);
    expect(parsedOf([facilityCapture('{"FacilityId":611}')])).toEqual([{ ok: false, errors: [NO_FACILITIES] }]);
  });

  test('one place with facilities makes the whole catalog a success', () => {
    const [result] = parsedOf([placeCapture({ facilities: '{}' }, 'place-1'), placeCapture()]);
    expect(result?.ok).toBe(true);
  });

  test('envelopes are routed by part prefix, and a page with a Facility object is a grid whatever its part', () => {
    const { catalog } = run([
      placeCapture(),
      facilityCapture('{"FacilityId":611,"PlaceId":690}'),
      gridCapture('{"Facility":{"FacilityId":611,"Units":{}}}', 'grid-611'),
      gridCapture('{"Facility":{"FacilityId":612,"Units":{}}}', 'misc'),
      { payload: objectOf('{"Facility":{"FacilityId":613,"Units":{}}}') },
      gridCapture('{"Other":1}', 'search'),
    ]);
    expect([...(catalog?.places.keys() ?? [])]).toEqual([690n]);
    expect([...(catalog?.facilities.keys() ?? [])]).toEqual([611n]);
    expect([...(catalog?.grids.keys() ?? [])]).toEqual([611n, 612n, 613n]);
  });

  test('a facility-prefixed part is a facility even when its payload also holds a Facility object', () => {
    const { catalog } = run([placeCapture(), facilityCapture('{"FacilityId":5,"Facility":{"FacilityId":6}}', 'facility-5')]);
    expect([...(catalog?.facilities.keys() ?? [])]).toEqual([5n]);
    expect(catalog?.grids.size).toBe(0);
  });

  test('a payload that is not an object is skipped', () => {
    const { catalog } = run([
      { part: 'place-1', payload: [1, 2] },
      { part: 'place-2', payload: 'text' },
      { part: 'place-3', payload: null },
      placeCapture(),
    ]);
    expect([...(catalog?.places.keys() ?? [])]).toEqual([690n]);
  });

  test('a later page for the same id replaces the earlier one in its original position', () => {
    const { catalog, results } = run([
      placeCapture({ placeId: '1', name: '"first"' }, 'place-1'),
      placeCapture({ placeId: '2' }, 'place-2'),
      placeCapture({ placeId: '1', name: '"again"' }, 'place-1'),
    ]);
    expect([...(catalog?.places.keys() ?? [])]).toEqual([1n, 2n]);
    expect(catalog?.places.get(1n)?.name).toBe('again');
    expect(results.map((result) => (result.ok ? result.record.name : ''))).toEqual(['again', 'Emerald Bay SP']);
  });

  test('a page that fails to parse is dropped and leaves the earlier one in place', () => {
    const { catalog } = run([
      placeCapture({ placeId: '1', name: '"kept"' }, 'place-1'),
      placeCapture({ placeId: '1', name: '"   "' }, 'place-1'),
    ]);
    expect(catalog?.places.get(1n)?.name).toBe('kept');
  });
});

describe('the place payload', () => {
  test('a payload without SelectedPlace is not a place', () => {
    expect(run([{ part: 'place-1', payload: objectOf('{}') }, COMPANION]).catalog?.places.size).toBe(1);
  });

  test('a SelectedPlace that is not an object is refused', () => {
    expect(() => run([{ part: 'place-1', payload: objectOf('{"SelectedPlace":null}') }, COMPANION])).toThrow(NOT_AN_OBJECT);
    expect(() => run([{ part: 'place-1', payload: objectOf('{"SelectedPlace":[]}') }, COMPANION])).toThrow(NOT_AN_OBJECT);
  });

  test('the place id must read as a Long', () => {
    expect(placeOrNull({ placeId: '"abc"' })).toBeNull();
    expect(placeOrNull({ placeId: 'null' })).toBeNull();
    expect(placeOrNull({ placeId: '1.5' })).toBeNull();
    expect(placeOf({ placeId: '"42"' }).placeId).toBe(42n);
    expect(placeOf({ placeId: '4e1' }).placeId).toBe(40n);
    expect(placeOrNull({ placeId: '4.2e1' })).toBeNull();
  });

  test('a place id that is an object or array is refused', () => {
    expect(refusedWith({ placeId: '{}' })).toThrow(OBJECT_NOT_PRIMITIVE);
    expect(refusedWith({ placeId: '[1]' })).toThrow(ARRAY_NOT_PRIMITIVE);
  });

  test('the name is kept as captured and a blank one drops the place', () => {
    expect(placeOf({ name: '"  Padded  "' }).name).toBe('  Padded  ');
    expect(placeOrNull({ name: '""' })).toBeNull();
    expect(placeOrNull({ name: '"   "' })).toBeNull();
    expect(placeOrNull({ name: '"\\u001c"' })).toBeNull();
    expect(placeOrNull({ name: 'null' })).toBeNull();
    expect(placeOf({ name: `"${String.fromCodePoint(0xfeff)}"` }).name).toBe(String.fromCodePoint(0xfeff));
  });

  test('latitude and longitude are read as text through the double parser', () => {
    expect(placeOf({ lat: '"38.5"', lon: '"-120"' })).toMatchObject({ latitude: 38.5, longitude: -120 });
    expect(placeOf({ lat: '38.5', lon: '-120.25' })).toMatchObject({ latitude: 38.5, longitude: -120.25 });
    expect(placeOf({ lat: '" 38.5 "', lon: '"1e1"' })).toMatchObject({ latitude: 38.5, longitude: 10 });
    expect(placeOf({ lat: '"0x1p3"', lon: '"-0x1.8p1"' })).toMatchObject({ latitude: 8, longitude: -3 });
    expect(placeOf({ lat: '"38.5d"', lon: '"38.5F"' })).toMatchObject({ latitude: 38.5, longitude: 38.5 });
  });

  test('a non-finite coordinate parses', () => {
    const coordinates = (lat: string, lon: string) => {
      const [parsed] = parsedOf([placeCapture({ lat, lon }, 'place-1')]);
      if (parsed?.ok !== true) throw new Error('catalog did not parse');
      const place = parsed.dto.places.get(690n);
      return [place?.latitude, place?.longitude];
    };
    expect(coordinates('"NaN"', '"-Infinity"')).toEqual([NaN, -Infinity]);
    expect(coordinates('"1e999"', '"0"')).toEqual([Infinity, 0]);
  });

  test('a coordinate that is not a number drops the place', () => {
    expect(placeOrNull({ lat: '"north"' })).toBeNull();
    expect(placeOrNull({ lon: '""' })).toBeNull();
    expect(placeOrNull({ lat: 'null' })).toBeNull();
    expect(placeOrNull({ lon: 'true' })).toBeNull();
    expect(placeOrNull({ lat: '"1,5"' })).toBeNull();
    expect(placeOrNull({ lat: `"${NO_BREAK_SPACE}1"` })).toBeNull();
  });

  test('a missing coordinate drops the place', () => {
    const missing = (selected: string) => run([{ part: 'place-1', payload: objectOf(selected) }, COMPANION]).catalog?.places.size;
    expect(missing('{"SelectedPlace":{"PlaceId":1,"Name":"n","Longitude":"1"}}')).toBe(1);
    expect(missing('{"SelectedPlace":{"PlaceId":1,"Name":"n","Latitude":"1"}}')).toBe(1);
  });

  test('a missing Facilities object is an empty one, a null one is refused', () => {
    const { catalog } = run([
      {
        part: 'place-1',
        payload: objectOf('{"SelectedPlace":{"PlaceId":1,"Name":"n","Latitude":"1","Longitude":"2"}}'),
      },
      COMPANION,
    ]);
    expect(catalog?.places.get(1n)?.facilityIds).toEqual([]);
    expect(refusedWith({ facilities: 'null' })).toThrow(NOT_AN_OBJECT);
    expect(refusedWith({ facilities: '[]' })).toThrow(NOT_AN_OBJECT);
  });

  test('facility ids sort numerically, not as text', () => {
    const place = placeOf({
      facilities: '{"a":{"FacilityId":10},"b":{"FacilityId":9},"c":{"FacilityId":100},"d":{"FacilityId":2}}',
    });
    expect(place.facilityIds).toEqual([2n, 9n, 10n, 100n]);
  });

  test('facility ids sort numerically when the keys are written out of order in the document', () => {
    const place = placeOf({ facilities: '{"30":{},"4":{},"200":{},"15":{}}' });
    expect(place.facilityIds).toEqual([4n, 15n, 30n, 200n]);
  });

  test('negative ids sort below positive ones and duplicates collapse', () => {
    const place = placeOf({
      facilities: '{"x":{"FacilityId":-5},"y":{"FacilityId":3},"z":{"FacilityId":3},"w":{"FacilityId":0}}',
    });
    expect(place.facilityIds).toEqual([-5n, 0n, 3n]);
  });

  test('a facility id past 2^53 sorts after one just under it', () => {
    const place = placeOf({
      facilities: `{"a":{"FacilityId":${BEYOND_SAFE}},"b":{"FacilityId":9007199254740992}}`,
    });
    expect(place.facilityIds).toEqual([9007199254740992n, 9007199254740993n]);
  });

  test('the facility id falls back to the key only when FacilityId does not read as a Long', () => {
    const place = placeOf({
      facilities:
        '{"7":{"FacilityId":70},"8":{},"9":{"FacilityId":null},"x":{},"10":{"FacilityId":"nope"},"11":{"FacilityId":1.5}}',
    });
    expect(place.facilityIds).toEqual([8n, 9n, 10n, 11n, 70n]);
  });

  test('a key falls back through the stdlib Long parser, signs included', () => {
    const place = placeOf({ facilities: '{"+5":{},"-6":{},"007":{},"1e2":{},"":{}," 3":{}}' });
    expect(place.facilityIds).toEqual([-6n, 5n, 7n]);
  });

  test('a facility entry that is not an object is skipped', () => {
    const place = placeOf({ facilities: '{"1":5,"2":null,"3":[],"4":{"FacilityId":4},"5":"x"}' });
    expect(place.facilityIds).toEqual([4n]);
  });

  test('a FacilityId that is an object or array is refused', () => {
    expect(refusedWith({ facilities: '{"1":{"FacilityId":{}}}' })).toThrow(OBJECT_NOT_PRIMITIVE);
    expect(refusedWith({ facilities: '{"1":{"FacilityId":[]}}' })).toThrow(ARRAY_NOT_PRIMITIVE);
  });

  test('the unit type of a facility is the name of its first unit type with one', () => {
    const place = placeOf({
      facilities:
        '{"1":{"UnitTypes":{"a":{"Name":"Alpha"},"b":{"Name":"Beta"}}},"2":{"UnitTypes":{"a":{"Id":1},"b":5,"c":null,"d":{"Name":null},"e":{"Name":"Echo"}}},"3":{"UnitTypes":{}},"4":{},"5":{"UnitTypes":{"a":{"Name":""}}}}',
    });
    expect([...place.unitTypeByFacilityId]).toEqual([
      [1n, 'Alpha'],
      [2n, 'Echo'],
      [5n, ''],
    ]);
  });

  test('a unit type name that is a number reads as its text', () => {
    const place = placeOf({ facilities: '{"1":{"UnitTypes":{"a":{"Name":12.50}}}}' });
    expect(place.unitTypeByFacilityId.get(1n)).toBe('12.50');
  });

  test('UnitTypes that is not an object is refused, an array one included', () => {
    expect(refusedWith({ facilities: '{"1":{"UnitTypes":null}}' })).toThrow(NOT_AN_OBJECT);
    expect(refusedWith({ facilities: '{"1":{"UnitTypes":[]}}' })).toThrow(NOT_AN_OBJECT);
  });

  test('a later unit type is not read once an earlier one has a name', () => {
    const place = placeOf({ facilities: '{"1":{"UnitTypes":{"a":{"Name":"First"},"b":{"Name":[]}}}}' });
    expect(place.unitTypeByFacilityId.get(1n)).toBe('First');
  });

  test('a unit type name that is an array is refused when no earlier unit type had one', () => {
    expect(refusedWith({ facilities: '{"1":{"UnitTypes":{"a":{"Name":[]}}}}' })).toThrow(ARRAY_NOT_PRIMITIVE);
  });

  test('two entries resolving to one facility id keep the unit type of the later one in the document', () => {
    const place = placeOf({
      facilities:
        '{"9":{"FacilityId":7,"UnitTypes":{"a":{"Name":"Nine"}}},"3":{"FacilityId":7,"UnitTypes":{"a":{"Name":"Three"}}}}',
    });
    expect(place.facilityIds).toEqual([7n]);
    expect(place.unitTypeByFacilityId.get(7n)).toBe('Three');
  });

  test('image and description are trimmed and an empty one is absent', () => {
    expect(placeOf({ extra: '"ImageUrl":"  https://x/y.jpg  ","Description":"\\t text \\n"' })).toMatchObject({
      imageUrl: 'https://x/y.jpg',
      description: 'text',
    });
    expect(placeOf({ extra: '"ImageUrl":"   ","Description":""' })).toMatchObject({ imageUrl: null, description: null });
    expect(placeOf({ extra: '"ImageUrl":null' }).imageUrl).toBeNull();
    expect(placeOf({ extra: `"Description":"\\u001fd\\u001c"` }).description).toBe('d');
  });

  test('an image that is an object is refused', () => {
    expect(refusedWith({ extra: '"ImageUrl":{}' })).toThrow(OBJECT_NOT_PRIMITIVE);
  });

  test('the raw payload is the selected place', () => {
    const payload = objectOf(placeText());
    const { catalog } = run([{ part: 'place-690', payload }]);
    expect(catalog?.places.get(690n)?.raw).toBe((payload as { SelectedPlace: JsonObject }).SelectedPlace);
  });
});

describe('document order of unit types', () => {
  test('place-1077 names the unit type of facility 1940 from the entry the document writes first', () => {
    const { catalog } = run([fixtureCapture('place-1077')]);
    expect(catalog?.places.get(1077n)?.unitTypeByFacilityId.get(1940n)).toBe('Hike/Bike Campsite');
  });

  test('place-1138 names facility 1989 from 4312, which the document writes before 4303 and 4304', () => {
    const { catalog } = run([fixtureCapture('place-1138')]);
    expect(catalog?.places.get(1138n)?.unitTypeByFacilityId.get(1989n)).toBe('Group Campsite');
  });

  test('place-1192 reads each of its three facilities from the first entry written', () => {
    const { catalog } = run([fixtureCapture('place-1192')]);
    const place = catalog?.places.get(1192n);
    expect([...(place?.unitTypeByFacilityId ?? [])]).toEqual([
      [2035n, 'Premium Cottage_4'],
      [2036n, 'Premium Cabin (3 People)'],
      [2037n, 'Campsite'],
    ]);
    expect(place?.facilityIds).toEqual([2035n, 2036n, 2037n]);
  });

  test('unit types written out of order read in the order written when built from text', () => {
    const place = placeOf({
      facilities: '{"1":{"UnitTypes":{"30":{"Name":"Thirty"},"4":{"Name":"Four"},"200":{"Name":"Two hundred"}}}}',
    });
    expect(place.unitTypeByFacilityId.get(1n)).toBe('Thirty');
  });

  test('a unit type keyed by text and one keyed by a number keep the order written', () => {
    const place = placeOf({
      facilities: '{"1":{"UnitTypes":{"b":{"Name":"Bee"},"7":{"Name":"Seven"},"a":{"Name":"Ay"}}}}',
    });
    expect(place.unitTypeByFacilityId.get(1n)).toBe('Bee');
  });

  test('when the first entry in the document has no name the next one written is used, not the smallest key', () => {
    const place = placeOf({
      facilities: '{"1":{"UnitTypes":{"9":{"Id":9},"5":{"Name":"Five"},"2":{"Name":"Two"}}}}',
    });
    expect(place.unitTypeByFacilityId.get(1n)).toBe('Five');
  });
});

describe('highlights', () => {
  test('labels split on a break tag in any case, with any ASCII space and an optional slash', () => {
    const place = placeOf({ extra: '"Allhighlights":"a<br>b<BR/>c<Br />d<br \\t\\n/>e"' });
    expect([...place.amenities, ...place.activities]).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  test('a no-break space inside the tag is not Java whitespace so the tag does not split', () => {
    const place = placeOf({ extra: `"Allhighlights":"a<br${NO_BREAK_SPACE}/>b"` });
    expect(place.amenities).toEqual(['a b']);
  });

  test('other tags are stripped from a label and empty labels and repeats go', () => {
    const place = placeOf({
      extra: '"Allhighlights":"<b>Showers</b><br>  <br><i></i><br>Showers<br>Fire  Rings"',
    });
    expect(place.amenities).toEqual(['Showers', 'Fire Rings']);
  });

  test('a place with no highlights, or an empty one, has neither amenities nor activities', () => {
    expect(placeOf().amenities).toEqual([]);
    expect(placeOf({ extra: '"Allhighlights":""' }).amenities).toEqual([]);
    expect(placeOf({ extra: '"Allhighlights":null' }).activities).toEqual([]);
  });

  test('an Allhighlights that is an object is refused', () => {
    expect(refusedWith({ extra: '"Allhighlights":{}' })).toThrow(OBJECT_NOT_PRIMITIVE);
  });

  test('parseHighlights on nothing returns nothing', () => {
    expect(parseHighlights(null)).toEqual([]);
    expect(parseHighlights('')).toEqual([]);
  });

  test('a label is an activity when it contains a hint in any case', () => {
    const place = placeOf({
      extra:
        '"Allhighlights":"Hiking<br>BIRD watching<br>Water Sports<br>Ski<br>Horseback Riding<br>Trails<br>Restrooms<br>Museum<br>Water<br>Sport"',
    });
    expect(place.activities).toEqual(['Hiking', 'BIRD watching', 'Water Sports', 'Ski', 'Horseback Riding', 'Trails']);
    expect(place.amenities).toEqual(['Restrooms', 'Museum', 'Water', 'Sport']);
    expect(['Hiking', 'Museum'].map(isActivityHighlight)).toEqual([true, false]);
  });

  test('a place splits its highlights into amenities and activities, each in the order written', () => {
    const place = placeOf({ extra: '"Allhighlights":"Museum<br>Hiking<br>Showers<br>Fishing<br>Showers"' });
    expect(place.amenities).toEqual(['Museum', 'Showers']);
    expect(place.activities).toEqual(['Hiking', 'Fishing']);
  });

  test('every spec highlight label maps onto its amenity key', () => {
    const cases: [string, AmenityKey][] = [
      ['Restrooms', AmenityKey.TOILETS],
      ['Toilet, Accessible', AmenityKey.TOILETS],
      ['Comfort Station', AmenityKey.TOILETS],
      ['Showers', AmenityKey.SHOWERS],
      ['Rinse Showers', AmenityKey.SHOWERS],
      ['Dump Station', AmenityKey.DUMP_STATION],
      ['Store-convenience', AmenityKey.CAMP_STORE],
      ['Store - Convenience', AmenityKey.CAMP_STORE],
      ['Fire Rings', AmenityKey.FIRES_ALLOWED],
      ['Museum', AmenityKey.OTHER],
      ['  rinse SHOWERS ', AmenityKey.SHOWERS],
    ];
    expect(cases.map(([label]) => highlightAmenities([label])[0]?.key)).toEqual(cases.map(([, key]) => key));
    expect(highlightAmenities(['Museum'])[0]?.detail).toBe('Museum');
    expect(highlightAmenities(['Restrooms'])[0]?.detail).toBeUndefined();
  });

  test('every spec label survives the whole pipeline to the candidate', () => {
    const labels = 'Restrooms<br>Toilet, Accessible<br>Comfort Station<br>Showers<br>Rinse Showers<br>Dump Station<br>Store-convenience<br>Store - Convenience<br>Fire Rings<br>RESTROOMS<br>Dump station';
    const [campground] = recordsOf([placeCapture({ extra: `"Allhighlights":"${labels}"` })]);
    expect(campground?.amenities?.map((amenity) => amenity.key)).toEqual([
      AmenityKey.TOILETS,
      AmenityKey.SHOWERS,
      AmenityKey.DUMP_STATION,
      AmenityKey.CAMP_STORE,
      AmenityKey.FIRES_ALLOWED,
    ]);
  });

  test('two highlights naming the same amenity collapse to one, and two other labels do not', () => {
    expect(highlightAmenities(['Restrooms', 'Comfort Station'])).toEqual([{ key: AmenityKey.TOILETS, present: true }]);
    expect(highlightAmenities(['Museum', 'Gift Shop', 'Museum'])).toEqual([
      { key: AmenityKey.OTHER, present: true, detail: 'Museum' },
      { key: AmenityKey.OTHER, present: true, detail: 'Gift Shop' },
    ]);
  });

  test('a blank label is dropped and the label is trimmed the Kotlin way', () => {
    expect(highlightAmenities(['   ', '', String.fromCodePoint(0x1c), ` ${String.fromCodePoint(0x1f)}Museum `])).toEqual([
      { key: AmenityKey.OTHER, present: true, detail: 'Museum' },
    ]);
  });
});

describe('real captures', () => {
  test('place pages from the capture become campgrounds with sorted facility ids', () => {
    const records = recordsOf([fixtureCapture('place-1077'), fixtureCapture('place-1138'), fixtureCapture('place-1192')]);
    expect(records.map((record) => record.bookingProviderRef)).toEqual(['1077:1940', '1138:1989', '1192:2035,2036,2037']);
    expect(records.every((record) => record.kind === 'state')).toBe(true);
  });

  test('the captured place carries its payload through unchanged', () => {
    const [record] = recordsOf([fixtureCapture('place-1077')]);
    const envelope = parseEnvelope(`${FIXTURE_ROOT}place-1077.json`);
    const selected = (envelope.payload as { SelectedPlace: unknown }).SelectedPlace;
    expect(payloadOf(record as CampgroundUpsertCandidate)).toEqual(JSON.parse(JSON.stringify(selected)));
  });
});
