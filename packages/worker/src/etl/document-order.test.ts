import { describe, expect, test } from 'vitest';
import { documentEntries, documentKeys, documentValues, recordDocumentOrder, stringifyInDocumentOrder } from './document-order.js';
import { parseEnvelope, parseKeepingDigits } from './envelope.js';

const FIXTURES = decodeURIComponent(new URL('../vendors/reservecalifornia/fixtures/', import.meta.url).pathname);
const PLACE_TWO_UNIT_TYPES = `${FIXTURES}place-1077.json`;
const PLACE_THREE_UNIT_TYPES = `${FIXTURES}place-1138.json`;
const PLACE_MIXED_ORDER = `${FIXTURES}place-1192.json`;

interface UnitTypesHolder {
  payload: { SelectedPlace: { Facilities: Record<string, { UnitTypes: Record<string, unknown> }> } };
}

function unitTypesOf(file: string, facility: string): Record<string, unknown> {
  const envelope = parseEnvelope(file);
  return (envelope.payload as UnitTypesHolder['payload']).SelectedPlace.Facilities[facility]!.UnitTypes;
}

function parsed(text: string): Record<string, unknown> {
  return parseKeepingDigits(text) as Record<string, unknown>;
}

describe('document order of integer-like keys', () => {
  test('a plain object reads place-1077 unit types in ascending order, the document writes 4320 first', () => {
    const unitTypes = unitTypesOf(PLACE_TWO_UNIT_TYPES, '1940');
    expect(Object.keys(unitTypes)).toEqual(['4317', '4320']);
    expect(documentKeys(unitTypes)).toEqual(['4320', '4317']);
  });

  test('the first unit-type name is the one the document writes first, not the smallest key', () => {
    const unitTypes = unitTypesOf(PLACE_TWO_UNIT_TYPES, '1940');
    const names = documentValues(unitTypes).map((entry) => (entry as { Name: string }).Name);
    expect(names).toEqual(['Hike/Bike Campsite', 'Hike in Campsite']);
    expect((Object.values(unitTypes)[0] as { Name: string }).Name).toBe('Hike in Campsite');
  });

  test('place-1138 is written 4312, 4303, 4304 and reads back in that order', () => {
    const unitTypes = unitTypesOf(PLACE_THREE_UNIT_TYPES, '1989');
    expect(documentKeys(unitTypes)).toEqual(['4312', '4303', '4304']);
    expect(Object.keys(unitTypes)).toEqual(['4303', '4304', '4312']);
  });

  test('entries pair each key with its own value', () => {
    const unitTypes = unitTypesOf(PLACE_THREE_UNIT_TYPES, '1989');
    expect(documentEntries(unitTypes).map(([key, value]) => [key, (value as { UnitTypeId: number }).UnitTypeId])).toEqual([
      ['4312', 4312],
      ['4303', 4303],
      ['4304', 4304],
    ]);
  });

  test('place-1192 holds one facility already ascending and one that is not, and only the second differs', () => {
    expect(documentKeys(unitTypesOf(PLACE_MIXED_ORDER, '2037'))).toEqual(['4303', '4427', '4328']);
    expect(documentKeys(unitTypesOf(PLACE_MIXED_ORDER, '2035'))).toEqual(['4405']);
  });

  test('an object whose integer keys are already ascending is untouched', () => {
    const value = parsed('{"1":1,"2":2,"10":10}');
    expect(documentKeys(value)).toEqual(['1', '2', '10']);
    expect(documentKeys(value)).toEqual(Object.keys(value));
  });

  test('an object with no integer-like key is untouched', () => {
    const value = parsed('{"b":1,"a":2}');
    expect(documentKeys(value)).toEqual(['b', 'a']);
  });

  test('a string key written before an integer key is read in the order written', () => {
    const value = parsed('{"b":1,"7":2,"a":3}');
    expect(Object.keys(value)).toEqual(['7', 'b', 'a']);
    expect(documentKeys(value)).toEqual(['b', '7', 'a']);
  });

  test('keys in an object nested inside an array nested inside an object keep their own order', () => {
    const value = parsed('{"outer":[{"9":1,"3":2},{"4":0,"5":0}],"2":{"8":1,"1":2}}');
    const outer = value.outer as Record<string, unknown>[];
    expect(documentKeys(outer[0]!)).toEqual(['9', '3']);
    expect(documentKeys(outer[1]!)).toEqual(['4', '5']);
    expect(documentKeys(value['2'] as Record<string, unknown>)).toEqual(['8', '1']);
    expect(documentKeys(value)).toEqual(['outer', '2']);
  });

  test('a key written with escapes is read as the key it spells', () => {
    const value = parsed('{"\\u0032":"b","\\u0031":"a"}');
    expect(documentKeys(value)).toEqual(['2', '1']);
  });

  test('a key that is not an array index but starts with a digit does not disturb the order', () => {
    const value = parsed('{"9":1,"1a":2,"3":3}');
    expect(documentKeys(value)).toEqual(['9', '1a', '3']);
    expect(Object.keys(value)).toEqual(['3', '9', '1a']);
  });

  test('a repeated key keeps the position of its first occurrence and the value of its last', () => {
    const value = parsed('{"5":"first","2":"mid","5":"last"}');
    expect(documentKeys(value)).toEqual(['5', '2']);
    expect(value['5']).toBe('last');
  });

  test('a repeated container key does not leave the earlier occurrence order on the later object', () => {
    const value = parsed('{"a":{"9":0,"1":0},"a":{"1":0,"9":0}}');
    expect(documentKeys(value.a as Record<string, unknown>)).toEqual(['1', '9']);
    const swapped = parsed('{"a":{"1":0,"9":0},"a":{"9":0,"1":0}}');
    expect(documentKeys(swapped.a as Record<string, unknown>)).toEqual(['9', '1']);
  });

  test('strings, numbers and literals around the keys do not confuse the scanner', () => {
    const value = parsed('{ "7" : "a,}\\"{" , "3" : [1, 2.5e3, true, null, "x]"], "5" :{ } , "4" : [ ] }');
    expect(documentKeys(value)).toEqual(['7', '3', '5', '4']);
  });

  test('wide numbers keep their digits next to reordered keys', () => {
    const value = parsed('{"9":12345678901234567890,"2":1}');
    expect(documentKeys(value)).toEqual(['9', '2']);
    expect(JSON.stringify(value['9'])).toBe('12345678901234567890');
  });

  test('a document whose only integer key spelling is escaped is still scanned', () => {
    const value = parsed('{"\\u0039":1,"\\u0031":2}');
    expect(documentKeys(value)).toEqual(['9', '1']);
  });

  test('the scanner adds no depth limit of its own to a deeply nested document', () => {
    const depth = 20000;
    const text = `${'['.repeat(depth)}{"2":1,"1":2}${']'.repeat(depth)}`;
    const value = JSON.parse(text) as unknown;
    recordDocumentOrder(text, value);
    let cursor: unknown = value;
    for (let level = 0; level < depth; level += 1) cursor = (cursor as unknown[])[0];
    expect(documentKeys(cursor as Record<string, unknown>)).toEqual(['2', '1']);
  });

  test('recordDocumentOrder on text with no integer-like key records nothing', () => {
    const value = JSON.parse('{"b":1,"a":2}') as Record<string, unknown>;
    recordDocumentOrder('{"b":1,"a":2}', value);
    expect(documentKeys(value)).toEqual(['b', 'a']);
  });

  test('the serializer writes keys in document order and matches JSON.stringify where order is natural', () => {
    const reordered = parsed('{"9":{"4":1,"2":2},"3":[1,{"8":0,"7":0}]}');
    expect(stringifyInDocumentOrder(reordered)).toBe('{"9":{"4":1,"2":2},"3":[1,{"8":0,"7":0}]}');
    const natural = parsed('{"a":1,"b":[true,null,"s"],"c":{"d":1.5}}');
    expect(stringifyInDocumentOrder(natural)).toBe(JSON.stringify(natural));
  });

  test('the serializer carries a number beyond 2^53 with every digit', () => {
    const value = parsed('{"2":12345678901234567890,"1":0.1000000000000000055511151231257827}');
    expect(stringifyInDocumentOrder(value)).toBe('{"2":12345678901234567890,"1":0.1000000000000000055511151231257827}');
  });
});
