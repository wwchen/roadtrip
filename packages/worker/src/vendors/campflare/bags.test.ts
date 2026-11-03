import { AmenityKey, Carrier } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { parseKeepingDigits } from '../../etl/envelope.js';
import type { JsonObject } from '../../etl/json-decode.js';
import { alerts, amenities, carrierSignals, metadata, price, schedule } from './bags.js';

const INT_MAX = 2147483647;
const SHARP_S = String.fromCodePoint(0xdf);

function obj(text: string): JsonObject {
  return JSON.parse(text) as JsonObject;
}

describe('amenities', () => {
  test('unknown keys that look like integers are listed in the order the document writes them', () => {
    const raw = parseKeepingDigits('{"30": true, "water": true, "4": false, "toilets": true}') as JsonObject;
    expect(amenities(raw)).toEqual([
      { key: AmenityKey.OTHER, present: true, detail: '30' },
      { key: AmenityKey.WATER, present: true },
      { key: AmenityKey.OTHER, present: false, detail: '4' },
      { key: AmenityKey.TOILETS, present: true },
    ]);
  });

  test('keys map to the vocabulary and json nulls drop out', () => {
    expect(amenities(obj('{"toilets": true, "water": false, "wifi": null, "showers": true}'))).toEqual([
      { key: AmenityKey.TOILETS, present: true },
      { key: AmenityKey.WATER, present: false },
      { key: AmenityKey.SHOWERS, present: true },
    ]);
  });

  test('toilet kind rides as detail on the toilets amenity', () => {
    expect(amenities(obj('{"toilets": true, "toilet_kind": "vault"}'))).toEqual([
      { key: AmenityKey.TOILETS, present: true, detail: 'vault' },
    ]);
  });

  test('toilet kind creates the toilets amenity, first, when the boolean is json null', () => {
    expect(amenities(obj('{"toilets": null, "toilet_kind": "vault", "water": true}'))).toEqual([
      { key: AmenityKey.TOILETS, present: true, detail: 'vault' },
      { key: AmenityKey.WATER, present: true },
    ]);
  });

  test('toilet kind overrides the detail of a toilets amenity that is false', () => {
    expect(amenities(obj('{"water": true, "toilets": false, "toilet_kind": "pit"}'))).toEqual([
      { key: AmenityKey.WATER, present: true },
      { key: AmenityKey.TOILETS, present: false, detail: 'pit' },
    ]);
  });

  test('an unknown key lands as other carrying the humanized key as detail', () => {
    expect(amenities(obj('{"camp_kitchen": true, "boat_ramp": null}'))).toEqual([
      { key: AmenityKey.OTHER, present: true, detail: 'Camp kitchen' },
    ]);
  });

  test('an unknown key keeps the vendor false', () => {
    expect(amenities(obj('{"camp_kitchen": false}'))).toEqual([
      { key: AmenityKey.OTHER, present: false, detail: 'Camp kitchen' },
    ]);
  });

  test('a literal other key still lands as other carrying the key as detail', () => {
    expect(amenities(obj('{"other": true}'))).toEqual([{ key: AmenityKey.OTHER, present: true, detail: 'Other' }]);
  });

  test('a json string value is present and rides along as the detail', () => {
    expect(amenities(obj('{"wifi": "guest network"}'))).toEqual([
      { key: AmenityKey.WIFI, present: true, detail: 'guest network' },
    ]);
    expect(amenities(obj('{"wifi": "false"}'))).toEqual([{ key: AmenityKey.WIFI, present: true, detail: 'false' }]);
  });

  test('a number, an object and an array are all present and carry no detail', () => {
    expect(amenities(obj('{"wifi": 0, "water": {"a": 1}, "trash": [1]}'))).toEqual([
      { key: AmenityKey.WIFI, present: true },
      { key: AmenityKey.WATER, present: true },
      { key: AmenityKey.TRASH, present: true },
    ]);
  });

  test('humanizing uppercases one UTF-16 unit and leaves a character with no single-unit uppercase alone', () => {
    expect(amenities(obj(`{"${SHARP_S}eta_room": true}`))).toEqual([
      { key: AmenityKey.OTHER, present: true, detail: `${SHARP_S}eta room` },
    ]);
  });

  test('every underscore of an unknown key becomes a space', () => {
    expect(amenities(obj('{"a_b_c": true}'))).toEqual([{ key: AmenityKey.OTHER, present: true, detail: 'A b c' }]);
  });
});

describe('carrierSignals', () => {
  test('bare numbers become countless signals and unknown carriers and nulls drop', () => {
    expect(carrierSignals(obj('{"verizon": 0.6, "uscell": 2.5, "tmobile": 1.8, "boost": 1.0, "att": null}'))).toEqual([
      { carrier: Carrier.VERIZON, average: 0.6 },
      { carrier: Carrier.US_CELLULAR, average: 2.5 },
      { carrier: Carrier.TMOBILE, average: 1.8 },
    ]);
  });

  test('object carriers keep their sample count and string numbers drop', () => {
    expect(
      carrierSignals(obj('{"verizon": {"avg": 3, "count": 5}, "att": "4", "tmobile": {"count": 5}}')),
    ).toEqual([{ carrier: Carrier.VERIZON, average: 3, count: 5 }]);
  });

  test.each([
    [2.9, 2],
    [-2.9, -2],
    [1e10, INT_MAX],
  ])('a count of %s is narrowed the way Kotlin Double.toInt does, to %s', (count, expected) => {
    const [signal] = carrierSignals(obj(`{"sprint": {"avg": 1, "count": ${count}}}`));
    expect(signal?.count).toBe(expected);
  });

  test('a quoted count is ignored while the average still counts', () => {
    expect(carrierSignals(obj('{"att": {"avg": 2, "count": "5"}}'))).toEqual([{ carrier: Carrier.ATT, average: 2 }]);
  });
});

describe('price', () => {
  test('prefers the currency code over the spelled-out currency', () => {
    expect(price(obj('{"minimum": 36, "maximum": 45, "currency": "US Dollar", "currency_code": "USD"}'))).toEqual({
      minimum: 36,
      maximum: 45,
      currency: 'USD',
    });
    expect(price(obj('{"currency": "CAD"}'))).toEqual({ currency: 'CAD' });
  });

  test('a blank currency code falls through to the currency', () => {
    expect(price(obj('{"currency_code": "  ", "currency": "CAD"}'))).toEqual({ currency: 'CAD' });
  });

  test('is null when nothing priced is present', () => {
    expect(price(null)).toBeNull();
    expect(price(obj('{"uniform": true}'))).toBeNull();
  });
});

describe('schedule', () => {
  test('reads the upstream time keys and drops uniform', () => {
    expect(schedule(obj('{"check_in_time": "14:00", "check_out_time": "12:00", "uniform": true}'))).toEqual({
      check_in: '14:00',
      check_out: '12:00',
    });
    expect(schedule(obj('{"check_out_time": "12:00"}'))).toEqual({ check_out: '12:00' });
  });

  test('is null when neither time is present', () => {
    expect(schedule(obj('{"uniform": true}'))).toBeNull();
    expect(schedule(null)).toBeNull();
  });
});

describe('alerts', () => {
  test('read the upstream content keys and skip bodyless entries', () => {
    const entries = JSON.parse(`[
      {"title": "Fire ban", "content": "No open flames.", "end_date": "2026-09-01", "source_url": "https://example.test/alert"},
      {"title": "No body here"},
      {"content": "   "},
      "not an object"
    ]`) as unknown[];
    expect(alerts(entries)).toEqual([
      { title: 'Fire ban', body: 'No open flames.', ends_on: '2026-09-01', source_url: 'https://example.test/alert' },
    ]);
    expect(alerts(null)).toEqual([]);
  });
});

describe('metadata', () => {
  test('keeps last updated and drops the has flags', () => {
    expect(metadata(obj('{"last_updated": "2026-07-01T00:00:00Z", "has_availability_data": true}'))).toEqual({
      last_updated: '2026-07-01T00:00:00Z',
    });
    expect(metadata(obj('{"has_availability_data": true}'))).toBeNull();
    expect(metadata(null)).toBeNull();
  });
});
