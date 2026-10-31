import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope } from '../../etl/envelope.js';
import { decodeRidbPage, type RidbFacility } from './dto.js';

const TEMP_DIR_PREFIX = 'recgov-dto-';
const HTTP_OK = 200;
const ARABIC_INDIC_THREE = String.fromCodePoint(0x663);

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function payloadOf(jsonText: string): unknown {
  counter += 1;
  const file = path.join(dir, `payload-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${jsonText}}`,
  );
  return parseEnvelope(file).payload;
}

function facilities(jsonText: string): RidbFacility[] {
  return decodeRidbPage(payloadOf(jsonText));
}

function oneFacility(fields: string): RidbFacility {
  const [only, ...rest] = facilities(`{"RECDATA":[{"FacilityID":1${fields === '' ? '' : `,${fields}`}}]}`);
  if (only === undefined || rest.length > 0) throw new Error('expected exactly one facility');
  return only;
}

function idOf(idJson: string): string {
  const [only] = facilities(`{"RECDATA":[{"FacilityID":${idJson}}]}`);
  if (only === undefined) throw new Error('expected one facility');
  return only.facilityId;
}

describe('FacilityID, decoded as kotlinx decodeFromJsonElement reads a Long', () => {
  test.each([
    ['257029', '257029'],
    ['"257029"', '257029'],
    ['"0257029"', '257029'],
    ['1e3', '1000'],
    ['"1e3"', '1000'],
    ['" 12"', '12'],
    ['9223372036854775807', '9223372036854775807'],
    ['"9223372036854775807"', '9223372036854775807'],
    ['-9223372036854775808', '-9223372036854775808'],
  ])('%s reads as %s', (idJson, expected) => {
    expect(idOf(idJson)).toBe(expected);
  });

  test.each([
    '257029.0',
    '"257029.0"',
    '"+5"',
    `"${ARABIC_INDIC_THREE}"`,
    'true',
    'null',
    '[1]',
    '{}',
    '9223372036854775808',
    '"9223372036854775808"',
    '""',
    '"abc"',
    '"\\u00a012"',
    '"\\ufeff12"',
    '"\\f12"',
    '"12\\u00a0"',
  ])('%s is refused', (idJson) => {
    expect(() => facilities(`{"RECDATA":[{"FacilityID":${idJson}}]}`)).toThrow();
  });

  test('a facility without one is refused', () => {
    expect(() => facilities('{"RECDATA":[{}]}')).toThrow('FacilityID');
  });

  test('the last of two FacilityID keys wins', () => {
    expect(idOf('1,"FacilityID":2')).toBe('2');
  });
});

describe('nullable text fields, which kotlinx reads strictly', () => {
  test.each([
    ['FacilityName', 'facilityName'],
    ['FacilityPhone', 'facilityPhone'],
    ['FacilityReservationURL', 'facilityReservationUrl'],
  ] as const)('%s', (wire, property) => {
    expect(oneFacility(`"${wire}":"x"`)[property]).toBe('x');
    expect(oneFacility(`"${wire}":""`)[property]).toBe('');
    expect(oneFacility(`"${wire}":null`)[property]).toBeNull();
    expect(oneFacility('')[property]).toBeNull();
    for (const refused of ['5', 'true', '["x"]', '{}', '1.10']) {
      expect(() => oneFacility(`"${wire}":${refused}`)).toThrow(wire);
    }
  });
});

describe('FacilityLatitude and FacilityLongitude, decoded as kotlinx reads a Double', () => {
  test.each([
    ['38.65677222', 38.65677222],
    ['38', 38],
    ['"38.5"', 38.5],
    ['" 3"', 3],
    ['"1d"', 1],
    ['-119.5', -119.5],
    ['1.10', 1.1],
    ['25.0', 25],
    ['0', 0],
  ])('%s reads as %s', (valueJson, expected) => {
    expect(oneFacility(`"FacilityLatitude":${valueJson}`).facilityLatitude).toBe(expected);
    expect(oneFacility(`"FacilityLongitude":${valueJson}`).facilityLongitude).toBe(expected);
  });

  test('an absent or null coordinate is null', () => {
    expect(oneFacility('').facilityLatitude).toBeNull();
    expect(oneFacility('"FacilityLatitude":null').facilityLatitude).toBeNull();
    expect(oneFacility('"FacilityLongitude":null').facilityLongitude).toBeNull();
  });

  test.each(['"NaN"', '"Infinity"', '"-Infinity"', '1e400', '"1e400"', 'true', '""', '"abc"', '"\\u00a03"', '"\\ufeff3"', '"3\\u00a0"', '[1]', '{}'])(
    '%s is refused',
    (valueJson) => {
      expect(() => oneFacility(`"FacilityLatitude":${valueJson}`)).toThrow('FacilityLatitude');
      expect(() => oneFacility(`"FacilityLongitude":${valueJson}`)).toThrow('FacilityLongitude');
    },
  );
});

describe('FACILITYADDRESS', () => {
  test('absent and null are null, an empty list is empty', () => {
    expect(oneFacility('').facilityAddress).toBeNull();
    expect(oneFacility('"FACILITYADDRESS":null').facilityAddress).toBeNull();
    expect(oneFacility('"FACILITYADDRESS":[]').facilityAddress).toEqual([]);
  });

  test('every address is decoded, not only the first', () => {
    const decoded = oneFacility(
      '"FACILITYADDRESS":[{"City":"Price"},{"FacilityStreetAddress1":"1 Main","AddressStateCode":"UT","PostalCode":"84501","AddressCountryCode":"USA","Ignored":1}]',
    ).facilityAddress;
    expect(decoded).toEqual([
      {
        facilityStreetAddress1: null,
        city: 'Price',
        addressStateCode: null,
        postalCode: null,
        addressCountryCode: null,
      },
      {
        facilityStreetAddress1: '1 Main',
        city: null,
        addressStateCode: 'UT',
        postalCode: '84501',
        addressCountryCode: 'USA',
      },
    ]);
  });

  test('a bad second address fails the whole page', () => {
    expect(() => oneFacility('"FACILITYADDRESS":[{"City":"x"},{"City":5}]')).toThrow('City');
  });

  test.each(['[null]', '{}', '"x"', '[1]', '[[]]', '[{"City":5}]', '[{"AddressStateCode":true}]'])('%s is refused', (value) => {
    expect(() => oneFacility(`"FACILITYADDRESS":${value}`)).toThrow();
  });
});

describe('the page', () => {
  test('a page without RECDATA is empty, and one with RECDATA null is refused', () => {
    expect(facilities('{}')).toEqual([]);
    expect(facilities('{"METADATA":{"x":1}}')).toEqual([]);
    expect(() => facilities('{"RECDATA":null}')).toThrow('RECDATA');
  });

  test.each(['{"RECDATA":{}}', '{"RECDATA":[1]}', '{"RECDATA":[null]}', '{"RECDATA":"x"}', '[]', 'null', '"x"', '1'])(
    '%s is refused',
    (page) => {
      expect(() => facilities(page)).toThrow();
    },
  );

  test('keeps every facility in order, duplicates included', () => {
    const decoded = facilities('{"RECDATA":[{"FacilityID":3},{"FacilityID":1},{"FacilityID":3}]}');
    expect(decoded.map((facility) => facility.facilityId)).toEqual(['3', '1', '3']);
  });
});
