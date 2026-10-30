import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { BookingProvider, CampsiteKind, DataProviderRef } from '@roadtrip/domain';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { RecGovCampsitesEtl } from './campsites.js';

const TEMP_DIR_PREFIX = 'recgov-campsites-';
const HTTP_OK = 200;
const SLUG = 'recgov-campsites';
const INPUT = 'recgov-campsites-raw';
const FACILITY_URL =
  'https://www.recreation.gov/api/camps/availability/campground/232447/month?start_date=2026-06-01T00%3A00%3A00Z';
const ARABIC_INDIC_FOUR = String.fromCodePoint(0x664);
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function envelopeOf(payloadJson: string, url: string, part: string | null): Envelope {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  const partJson = part === null ? '' : `,"part":${JSON.stringify(part)}`;
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":${JSON.stringify(url)},"method":"GET"},` +
      `"response":{"status":${HTTP_OK}}${partJson},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

interface Capture {
  payload: string;
  url?: string;
  part?: string | null;
}

function bundleOf(captures: Capture[]): InputBundle {
  const envelopes = captures.map((capture) => envelopeOf(capture.payload, capture.url ?? FACILITY_URL, capture.part ?? null));
  return new InputBundle(new Map([[INPUT, envelopes]]));
}

function parsed(captures: Capture[]): ParseResult<Envelope[]>[] {
  return [...new RecGovCampsitesEtl(SLUG).parse(bundleOf(captures))];
}

function transformed(captures: Capture[]): TransformResult<CampsiteUpsertCandidate>[] {
  const etl = new RecGovCampsitesEtl(SLUG);
  const results: TransformResult<CampsiteUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(captures))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto));
  }
  return results;
}

function campsitesOf(payloadJson: string, url?: string): CampsiteUpsertCandidate[] {
  return transformed([{ payload: payloadJson, ...(url === undefined ? {} : { url }) }]).map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function oneCampsite(fieldsJson: string, id = '123456'): CampsiteUpsertCandidate {
  const [only, ...rest] = campsitesOf(`{"campsites":{"${id}":{${fieldsJson}}}}`);
  if (only === undefined || rest.length > 0) throw new Error('expected exactly one campsite');
  return only;
}

function attributesPayload(...attributes: string[]): string {
  return `"attributes":[${attributes.join(',')}]`;
}

function attribute(name: string, value: string | null): string {
  return `{"attribute_name":${JSON.stringify(name)},"attribute_value":${value === null ? 'null' : JSON.stringify(value)}}`;
}

describe('transform, ported from the Kotlin RecGovCampsitesEtlTest', () => {
  test('falls back to the campsite id when the recgov site fields are blank', () => {
    const campsite = oneCampsite('"site":"","loop":"","campsite_type":"","max_num_people":8');
    expect(campsite.dataProviderRef).toEqual(DataProviderRef.recGov('123456'));
    expect(campsite.name).toBe('123456');
    expect(campsite.kind).toBe(CampsiteKind.OTHER);
    expect(campsite.parentDataProviderRef).toEqual(DataProviderRef.recGov('232447'));
    expect(campsite.kindListed).toBeNull();
    expect(campsite.loopName).toBeNull();
    expect(campsite.maxPeople).toBe(8);
  });

  test('promotes recgov attributes onto typed columns and keeps the rest as attributes', () => {
    const campsite = oneCampsite(
      `"site":"A12","min_num_people":2,"max_num_people":6,"campsite_reserve_type":"Site-Specific","type_of_use":"Overnight",${attributesPayload(
        attribute('Fire Pit', 'Yes'),
        attribute('Driveway Length', '40'),
        attribute('Shade', 'Partial'),
      )}`,
    );
    expect(campsite.minPeople).toBe(2);
    expect(campsite.maxPeople).toBe(6);
    expect(campsite.firepit).toBe(true);
    expect(campsite.drivewayLength).toBe(40);
    expect(campsite.attributes).toEqual([
      { name: 'Shade', value: 'Partial' },
      { name: 'Reserve type', value: 'Site-Specific' },
      { name: 'Type of use', value: 'Overnight' },
    ]);
    const payload = JSON.parse(campsite.sourcePayload as string) as Record<string, unknown>;
    expect(payload._roadtrip_tags).toBeUndefined();
    expect(payload._parent_facility_id).toBe('232447');
  });

  test('promotes both spellings of the vehicle and accessibility attributes', () => {
    const [first, second] = campsitesOf(
      `{"campsites":{` +
        `"B1":{"site":"B1",${attributesPayload(
          attribute('Picnic Table', 'No'),
          attribute('Accessible', 'Y'),
          attribute('Max Num of Vehicles', '2'),
          attribute('Max Vehicle Length', '27 ft'),
          attribute('Pets Allowed', ''),
        )}},` +
        `"B2":{"site":"B2",${attributesPayload(attribute('ada accessible', 'false'), attribute('Max Vehicles', '3 cars'))}}}}`,
    );
    expect(first?.name).toBe('B1');
    expect(first?.picnicTable).toBe(false);
    expect(first?.adaAccessible).toBe(true);
    expect(first?.maxCars).toBe(2);
    expect(first?.maxRvLength).toBe(27);
    expect(first?.attributes).toEqual([{ name: 'Pets Allowed', value: undefined }]);
    expect(second?.name).toBe('B2');
    expect(second?.adaAccessible).toBe(false);
    expect(second?.maxCars).toBe(3);
  });

  test('keeps a promoted attribute the column cannot hold', () => {
    const campsite = oneCampsite(`"site":"C1",${attributesPayload(attribute('Fire Pit', 'Seasonal'))}`);
    expect(campsite.firepit).toBeNull();
    expect(campsite.attributes).toEqual([{ name: 'Fire Pit', value: 'Seasonal' }]);
  });
});

describe('identity and linking', () => {
  test('every campsite links to the facility named in the captured URL and books through recgov', () => {
    const campsite = oneCampsite('"site":"A"', '777');
    expect(campsite.bookingProvider).toBe(BookingProvider.RECGOV);
    expect(campsite.bookingProviderRef).toBe('777');
    expect(campsite.dataProviderRef).toEqual(DataProviderRef.recGov('777'));
    expect(campsite.parentDataProviderRef).toEqual(DataProviderRef.recGov('232447'));
  });

  test.each([
    ['a path ending the URL', 'https://x/api/campground/55', '55'],
    ['a query after the id', 'https://x/api/campground/55?start=1', '55'],
    ['a further path segment', 'https://x/api/campground/55/month?start=1', '55'],
    ['the first marker when the URL has two', 'https://x/campground/55/month/campground/66', '55'],
    ['an id with no digits', 'https://x/campground/abc/month', 'abc'],
    ['an id with leading zeros, kept as written', 'https://x/campground/0055/month', '0055'],
  ])('reads the facility id from %s', (_label, url, expected) => {
    const [only] = campsitesOf('{"campsites":{"1":{"site":"A"}}}', url);
    expect(only?.parentDataProviderRef).toEqual(DataProviderRef.recGov(expected));
  });

  test.each([
    ['no marker', 'https://x/api/camps/availability/55/month'],
    ['a marker without a trailing slash', 'https://x/api/campground'],
    ['an empty id before a slash', 'https://x/api/campground//month'],
    ['an empty id before a query', 'https://x/api/campground/?start=1'],
    ['an empty id at the end', 'https://x/api/campground/'],
    ['the marker in a different case', 'https://x/api/CAMPGROUND/55/month'],
  ])('reports %s as a missing facility id, against the URL', (_label, url) => {
    const results = transformed([{ payload: '{"campsites":{"1":{"site":"A"}}}', url }]);
    expect(results).toEqual([{ ok: false, sourceId: url, errors: ['missing facility id in request URL'] }]);
  });

  test('reports against the envelope part when it has one', () => {
    const results = transformed([
      { payload: '{"campsites":{"1":{"site":"A"}}}', url: 'https://x/nothing', part: 'facility-9' },
    ]);
    expect(results).toEqual([{ ok: false, sourceId: 'facility-9', errors: ['missing facility id in request URL'] }]);
  });

  test('a bad URL stops only its own envelope', () => {
    const results = transformed([
      { payload: '{"campsites":{"1":{"site":"A"}}}', url: 'https://x/nothing' },
      { payload: '{"campsites":{"2":{"site":"B"}}}' },
    ]);
    expect(results.map((result) => result.ok)).toEqual([false, true]);
  });
});

describe('payload shape guards', () => {
  test.each([
    ['a list', '[]'],
    ['null', 'null'],
    ['a string', '"x"'],
    ['a number', '5'],
  ])('a payload that is %s is not an object', (_label, payload) => {
    expect(transformed([{ payload, part: 'p' }])).toEqual([
      { ok: false, sourceId: 'p', errors: ['expected JSON object payload'] },
    ]);
  });

  test.each([
    ['absent', '{}'],
    ['null', '{"campsites":null}'],
    ['a list', '{"campsites":[]}'],
    ['a string', '{"campsites":"x"}'],
  ])('a campsites value that is %s is missing', (_label, payload) => {
    expect(transformed([{ payload, part: 'p' }])).toEqual([
      { ok: false, sourceId: 'p', errors: ['missing campsites object'] },
    ]);
  });

  test('an empty campsites object yields nothing', () => {
    expect(transformed([{ payload: '{"campsites":{}}' }])).toEqual([]);
  });

  test('an entry that is not an object is reported by its campsite id and does not stop its siblings', () => {
    const results = transformed([{ payload: '{"campsites":{"1":"x","2":null,"3":[],"4":{"site":"D"}}}' }]);
    expect(results.map((result) => (result.ok ? result.record.name : result.sourceId))).toEqual(['1', '2', '3', 'D']);
    expect(results[0]).toEqual({ ok: false, sourceId: '1', errors: ['expected campsite JSON object'] });
  });
});

describe('text fields', () => {
  test('are trimmed, and read by content when they are not text', () => {
    const campsite = oneCampsite('"site":"  A12 ","loop":"\\tNorth Loop\\n","campsite_type":" STANDARD NONELECTRIC "');
    expect(campsite.name).toBe('A12');
    expect(campsite.loopName).toBe('North Loop');
    expect(campsite.kindListed).toBe('STANDARD NONELECTRIC');
    expect(oneCampsite('"site":12,"loop":1.10').name).toBe('12');
    expect(oneCampsite('"site":12,"loop":1.10').loopName).toBe('1.10');
    expect(oneCampsite('"site":true').name).toBe('true');
  });

  test('the campsite id key, not a campsite_id field, names a campsite that has no site', () => {
    expect(oneCampsite('"campsite_id":"999","site":" "', '123456').name).toBe('123456');
    expect(oneCampsite('"campsite_id":"999"', '123456').name).toBe('123456');
  });

  test('the literal text null is a value here, unlike in the Campflare fields', () => {
    expect(oneCampsite('"site":"null","loop":"null"').name).toBe('null');
    expect(oneCampsite('"site":"null","loop":"null"').loopName).toBe('null');
  });

  test('null, absent, blank and non-primitive values read as absent without failing', () => {
    const campsite = oneCampsite('"site":null,"loop":{"a":1},"campsite_type":["x"]');
    expect(campsite.name).toBe('123456');
    expect(campsite.loopName).toBeNull();
    expect(campsite.kindListed).toBeNull();
    expect(oneCampsite(`"site":" ${NO_BREAK_SPACE} ","loop":"\\u001c"`).loopName).toBeNull();
    expect(oneCampsite(`"site":" ${NO_BREAK_SPACE} "`).name).toBe('123456');
  });

  test('the kind and electric flag come from the campsite type', () => {
    expect(oneCampsite('"campsite_type":"STANDARD NONELECTRIC"')).toMatchObject({ kind: CampsiteKind.STANDARD, electricHookups: false });
    expect(oneCampsite('"campsite_type":"RV ELECTRIC"')).toMatchObject({ kind: CampsiteKind.RV, electricHookups: true });
    expect(oneCampsite('"campsite_type":"TENT ONLY NONELECTRIC"')).toMatchObject({ kind: CampsiteKind.TENT, electricHookups: false });
    expect(oneCampsite('"campsite_type":"GROUP STANDARD AREA ELECTRIC"')).toMatchObject({ kind: CampsiteKind.GROUP, electricHookups: true });
    expect(oneCampsite('"campsite_type":"Unlisted Thing"')).toMatchObject({ kind: CampsiteKind.OTHER, electricHookups: null });
    expect(oneCampsite('')).toMatchObject({ kind: CampsiteKind.OTHER, electricHookups: null });
  });
});

describe('equipment', () => {
  test('trims each entry, drops blanks and non-primitives, and reads numbers by content', () => {
    expect(oneCampsite('"equipment_types":[" Tent ","","  ",null,5,true,1.10,{"a":1},["x"],"RV"]').equipment).toEqual([
      'Tent',
      '5',
      'true',
      '1.10',
      'RV',
    ]);
  });

  test('absent, null and non-list values are no equipment', () => {
    expect(oneCampsite('').equipment).toEqual([]);
    expect(oneCampsite('"equipment_types":null').equipment).toEqual([]);
    expect(oneCampsite('"equipment_types":"Tent"').equipment).toEqual([]);
    expect(oneCampsite('"equipment_types":{"a":"Tent"}').equipment).toEqual([]);
  });

  test('keeps repeats', () => {
    expect(oneCampsite('"equipment_types":["Tent","Tent"]').equipment).toEqual(['Tent', 'Tent']);
  });
});

describe('people counts, read by the kotlinx intOrNull rule', () => {
  test.each([
    ['6', 6],
    ['"6"', 6],
    ['1e1', 10],
    ['"1e1"', 10],
    ['10e-1', 1],
    ['6.0', null],
    ['"6.0"', null],
    ['6.5', null],
    ['true', null],
    ['"abc"', null],
    ['""', null],
    ['null', null],
    ['2147483647', 2147483647],
    ['2147483648', null],
    ['-1', -1],
    ['0', 0],
    ['" 6"', 6],
    ['" 6 "', 6],
    ['"+6"', null],
    ['"6 7"', null],
  ])('%s reads as %s', (valueJson, expected) => {
    expect(oneCampsite(`"max_num_people":${valueJson}`).maxPeople).toBe(expected);
    expect(oneCampsite(`"min_num_people":${valueJson}`).minPeople).toBe(expected);
  });

  test('absent is null', () => {
    expect(oneCampsite('').maxPeople).toBeNull();
    expect(oneCampsite('').minPeople).toBeNull();
  });

  test.each(['{}', '[]', '[6]'])('%s fails the run', (valueJson) => {
    expect(() => oneCampsite(`"max_num_people":${valueJson}`)).toThrow();
    expect(() => oneCampsite(`"min_num_people":${valueJson}`)).toThrow();
  });
});

describe('promoted attributes', () => {
  test('match their names ignoring case, and read yes and no spelled several ways', () => {
    const cases: Array<[string, string, 'firepit' | 'picnicTable' | 'adaAccessible', boolean | null]> = [
      ['FIRE PIT', 'YES', 'firepit', true],
      ['fire pit', 'y', 'firepit', true],
      ['Fire Pit', 'TRUE', 'firepit', true],
      ['Fire Pit', 'No', 'firepit', false],
      ['Fire Pit', 'N', 'firepit', false],
      ['Fire Pit', 'False', 'firepit', false],
      ['Picnic Table', 'yes', 'picnicTable', true],
      ['PICNIC TABLE', 'no', 'picnicTable', false],
      ['Accessible', 'Y', 'adaAccessible', true],
      ['ADA Accessible', 'n', 'adaAccessible', false],
      ['ada accessible', 'true', 'adaAccessible', true],
    ];
    for (const [name, value, column, expected] of cases) {
      const campsite = oneCampsite(attributesPayload(attribute(name, value)));
      expect(campsite[column], `${name}=${value}`).toBe(expected);
      expect(campsite.attributes, `${name}=${value}`).toEqual([]);
    }
  });

  test('a value outside yes and no leaves the column empty and keeps the attribute', () => {
    for (const value of ['Seasonal', '', 'maybe', '1', '0', 'yes please', 'y e s']) {
      const campsite = oneCampsite(attributesPayload(attribute('Fire Pit', value)));
      expect(campsite.firepit, value).toBeNull();
      expect(campsite.attributes, value).toEqual(value === '' ? [{ name: 'Fire Pit', value: undefined }] : [{ name: 'Fire Pit', value }]);
    }
  });

  test('the value is trimmed before it is read, so padding does not hide a yes', () => {
    expect(oneCampsite(attributesPayload(attribute('Fire Pit', ' yes '))).firepit).toBe(true);
    expect(oneCampsite(attributesPayload(attribute('Fire Pit', `\t${NO_BREAK_SPACE}No`))).firepit).toBe(false);
    expect(oneCampsite(attributesPayload(attribute('Shade', '  Partial '))).attributes).toEqual([{ name: 'Shade', value: 'Partial' }]);
  });

  test('a null or absent value keeps the attribute with no value', () => {
    expect(oneCampsite(attributesPayload(attribute('Pets Allowed', null))).attributes).toEqual([
      { name: 'Pets Allowed', value: undefined },
    ]);
    expect(oneCampsite(attributesPayload('{"attribute_name":"Fire Pit"}')).attributes).toEqual([
      { name: 'Fire Pit', value: undefined },
    ]);
  });

  test('numeric columns read the leading digits of the value', () => {
    const cases: Array<[string, string, 'maxCars' | 'drivewayLength' | 'maxRvLength', number | null]> = [
      ['Max Num of Vehicles', '2', 'maxCars', 2],
      ['MAX VEHICLES', '3 cars', 'maxCars', 3],
      ['Driveway Length', '40', 'drivewayLength', 40],
      ['driveway length', '25.5 ft', 'drivewayLength', 25],
      ['Max Vehicle Length', '27 ft', 'maxRvLength', 27],
      ['Max Vehicle Length', '0', 'maxRvLength', 0],
      ['Max Vehicle Length', '007', 'maxRvLength', 7],
      ['Max Vehicle Length', '2147483647', 'maxRvLength', 2147483647],
      ['Max Vehicle Length', '2147483648', 'maxRvLength', null],
      ['Max Vehicle Length', '99999999999', 'maxRvLength', null],
      ['Max Vehicle Length', 'ft 27', 'maxRvLength', null],
      ['Max Vehicle Length', ' 27', 'maxRvLength', 27],
      ['Max Vehicle Length', '-27', 'maxRvLength', null],
      ['Max Vehicle Length', '+27', 'maxRvLength', null],
      ['Max Vehicle Length', 'N/A', 'maxRvLength', null],
      ['Max Vehicle Length', ARABIC_INDIC_FOUR, 'maxRvLength', null],
      ['Max Vehicle Length', `27${ARABIC_INDIC_FOUR}`, 'maxRvLength', 27],
    ];
    for (const [name, value, column, expected] of cases) {
      const campsite = oneCampsite(attributesPayload(attribute(name, value)));
      expect(campsite[column], `${name}=${value}`).toBe(expected);
      expect(campsite.attributes, `${name}=${value}`).toEqual(expected === null ? [{ name, value }] : []);
    }
  });

  test('a zero is a promoted value, not a missing one, so the attribute does not stay behind', () => {
    const campsite = oneCampsite(attributesPayload(attribute('Max Vehicles', '0'), attribute('Fire Pit', 'No')));
    expect(campsite.maxCars).toBe(0);
    expect(campsite.firepit).toBe(false);
    expect(campsite.attributes).toEqual([]);
  });

  test('the attribute name is trimmed before matching and kept trimmed when it stays', () => {
    const campsite = oneCampsite(attributesPayload(attribute('  Fire Pit ', 'Yes'), attribute('  Shade ', ' Partial ')));
    expect(campsite.firepit).toBe(true);
    expect(campsite.attributes).toEqual([{ name: 'Shade', value: 'Partial' }]);
  });

  test('of a repeated promoted name the last readable value wins, and an unreadable repeat stays an attribute', () => {
    const campsite = oneCampsite(
      attributesPayload(
        attribute('Fire Pit', 'Yes'),
        attribute('Fire Pit', 'No'),
        attribute('Fire Pit', 'Seasonal'),
        attribute('Max Vehicles', '2'),
        attribute('Max Num of Vehicles', '4'),
      ),
    );
    expect(campsite.firepit).toBe(false);
    expect(campsite.maxCars).toBe(4);
    expect(campsite.attributes).toEqual([{ name: 'Fire Pit', value: 'Seasonal' }]);
  });

  test('entries that are not objects, or have no usable name, are skipped even when they carry a value', () => {
    const campsite = oneCampsite(
      attributesPayload('"Fire Pit"', 'null', '5', '[]', '{"attribute_value":"x"}', attribute('', 'x'), attribute('   ', 'x'), '{"attribute_name":null,"attribute_value":"x"}', attribute('Shade', 'Full')),
    );
    expect(campsite.attributes).toEqual([{ name: 'Shade', value: 'Full' }]);
  });

  test('a name or value that is not text is read by content', () => {
    const campsite = oneCampsite(attributesPayload('{"attribute_name":5,"attribute_value":1.10}', '{"attribute_name":"Max Vehicles","attribute_value":3}'));
    expect(campsite.attributes).toEqual([{ name: '5', value: '1.10' }]);
    expect(campsite.maxCars).toBe(3);
  });

  test('attributes that are absent, null or not a list are none', () => {
    expect(oneCampsite('').attributes).toEqual([]);
    expect(oneCampsite('"attributes":null').attributes).toEqual([]);
    expect(oneCampsite('"attributes":{"a":1}').attributes).toEqual([]);
    expect(oneCampsite('"attributes":"x"').attributes).toEqual([]);
  });

  test('reserve type and type of use follow the attributes, trimmed, and are omitted when blank', () => {
    const campsite = oneCampsite(
      `"campsite_reserve_type":" Site-Specific ","type_of_use":" Overnight ",${attributesPayload(attribute('Shade', 'Full'))}`,
    );
    expect(campsite.attributes).toEqual([
      { name: 'Shade', value: 'Full' },
      { name: 'Reserve type', value: 'Site-Specific' },
      { name: 'Type of use', value: 'Overnight' },
    ]);
    expect(oneCampsite('"campsite_reserve_type":" ","type_of_use":null').attributes).toEqual([]);
    expect(oneCampsite('"type_of_use":"Day"').attributes).toEqual([{ name: 'Type of use', value: 'Day' }]);
  });
});

describe('the source payload', () => {
  test('is the raw campsite plus the parent facility id as text, after the raw keys', () => {
    const campsite = oneCampsite('"site":"A","availabilities":{"2026-06-02T00:00:00Z":"Reserved"}');
    expect(campsite.sourcePayload).toBe(
      '{"site":"A","availabilities":{"2026-06-02T00:00:00Z":"Reserved"},"_parent_facility_id":"232447"}',
    );
  });

  test('keeps every vendor spelling of a number', () => {
    const campsite = oneCampsite('"a":1.10,"b":25.0,"c":100000000000000000000,"d":1e2,"quantities":{"x":1}');
    expect(campsite.sourcePayload).toContain('"a":1.10,"b":25.0,"c":100000000000000000000,"d":1e2');
  });

  test('a key already named _parent_facility_id keeps its place and takes the URL value', () => {
    const campsite = oneCampsite('"first":1,"_parent_facility_id":"stale","last":2');
    expect(campsite.sourcePayload).toBe('{"first":1,"_parent_facility_id":"232447","last":2}');
  });

  test('is carried verbatim even for a campsite whose other fields are all unusable', () => {
    const campsite = oneCampsite('"site":{},"attributes":7');
    expect(JSON.parse(campsite.sourcePayload as string)).toEqual({ site: {}, attributes: 7, _parent_facility_id: '232447' });
  });
});

describe('columns the Rec.gov adapter leaves empty', () => {
  test('price, schedule, location, photos and description stay unset, whatever the raw campsite carries', () => {
    const campsite = oneCampsite(
      '"price":{"per_night":10},"schedule":{"check_in":"3pm"},"latitude":1,"longitude":2,"description":"d","photos":[{"url":"u"}],"reservation_url":"u","loop_name":"l"',
    );
    expect(campsite.price ?? null).toBeNull();
    expect(campsite.schedule ?? null).toBeNull();
    expect(campsite.latitude ?? null).toBeNull();
    expect(campsite.longitude ?? null).toBeNull();
    expect(campsite.description ?? null).toBeNull();
    expect(campsite.photos ?? []).toEqual([]);
    expect(campsite.reservationUrl ?? null).toBeNull();
    expect(campsite.waterHookups ?? null).toBeNull();
    expect(campsite.sewerHookups ?? null).toBeNull();
    expect(campsite.pullThrough ?? null).toBeNull();
    expect(campsite.maxTrailerLength ?? null).toBeNull();
  });
});

describe('ordering and volume', () => {
  test('yields campsites envelope by envelope, in each facility order of non-numeric keys', () => {
    const results = transformed([
      { payload: '{"campsites":{"b":{"site":"B"},"a":{"site":"A"}}}', url: 'https://x/campground/1/m' },
      { payload: '{"campsites":{"c":{"site":"C"}}}', url: 'https://x/campground/2/m' },
    ]);
    expect(results.map((result) => (result.ok ? `${result.record.parentDataProviderRef?.serialize()}:${result.record.name}` : 'bad'))).toEqual([
      '1:B',
      '1:A',
      '2:C',
    ]);
  });

  test('the same campsite id under two facilities yields two records, left for the sink to dedupe', () => {
    const results = transformed([
      { payload: '{"campsites":{"9":{"site":"first"}}}', url: 'https://x/campground/1/m' },
      { payload: '{"campsites":{"9":{"site":"second"}}}', url: 'https://x/campground/2/m' },
    ]);
    expect(results.map((result) => (result.ok ? result.record.name : 'bad'))).toEqual(['first', 'second']);
  });
});

describe('parse', () => {
  test('yields the envelopes of the one declared input as a single dto', () => {
    const results = parsed([{ payload: '{"campsites":{}}' }, { payload: '{"campsites":{}}' }]);
    expect(results).toHaveLength(1);
    const [only] = results;
    expect(only?.ok).toBe(true);
    if (only?.ok === true) expect(only.dto).toHaveLength(2);
  });

  test('refuses a bundle with no envelopes, naming the slug and the fetcher to run', () => {
    expect(() => [...new RecGovCampsitesEtl(SLUG).parse(new InputBundle(new Map([[INPUT, []]])))]).toThrow(
      `${SLUG}: no envelopes captured (run fetch_recgov_campsites.py first)`,
    );
  });

  test('refuses a bundle with more than one input', () => {
    const bundle = new InputBundle(
      new Map([
        [INPUT, []],
        ['other', []],
      ]),
    );
    expect(() => [...new RecGovCampsitesEtl(SLUG).parse(bundle)]).toThrow('exactly one');
  });
});
