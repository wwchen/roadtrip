import { BookingProvider, CampsiteKind, DataProviderRef } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { parseEnvelope, parseKeepingDigits, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { loadPoiRegistry } from '../../registry/poi-registry.js';
import { PARENT_PLACE_ID_KEY } from './campgrounds.js';
import type { ReserveCaliforniaCatalog } from './catalog.js';
import { isStandardBookable } from './facility.js';
import { ReserveCaliforniaSitesEtl } from './sites.js';

const SLUG = 'reservecalifornia-campsites';
const INPUT = 'reservecalifornia-catalog';
const RAW_DIR = '/unused';
const HTTP_OK = 200;
const FETCHED_AT = '2026-06-22T20:14:13Z';
const OBJECT_NOT_PRIMITIVE = 'Element a JsonObject is not a JsonPrimitive';
const NOT_AN_OBJECT = 'Element is not a JsonObject';
const SOLE_INPUT_REFUSAL = 'soleEnvelopes() requires exactly one data_source input; got 2';
const NO_ENVELOPES = `${SLUG}: no envelopes captured (run fetch_reservecalifornia.py first)`;
const NO_UNITS = `${SLUG}: no ReserveCalifornia grid payloads with units parsed`;
const NOT_BOOKABLE = 'facility is not standard bookable';
const FIXTURE_ROOT = decodeURIComponent(new URL('./fixtures/', import.meta.url).pathname);
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const PRODUCTION_CTX = TransformCtx.load(RAW_DIR, loadPoiRegistry(PRODUCTION_REGISTRY_PATH));
const BEYOND_SAFE = '9007199254740993';

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

function bundleOf(captures: Capture[]): InputBundle {
  return new InputBundle(new Map([[INPUT, captures.map(envelopeOf)]]));
}

function objectOf(text: string): JsonObject {
  return parseKeepingDigits(text) as JsonObject;
}

function placeCapture(
  placeId: string,
  facilities: Record<string, string | null>,
  extra = '',
): Capture {
  const members = Object.entries(facilities).map(([id, unitType]) => {
    const unitTypes =
      unitType === null ? '' : `,"UnitTypes":{"1":{"Name":${JSON.stringify(unitType)}}}`;
    return `"${id}":{"FacilityId":${id}${unitTypes}}`;
  });
  const text = `{"SelectedPlace":{"PlaceId":${placeId},"Name":"Park ${placeId}","Latitude":"38.5","Longitude":"-120.5","Facilities":{${members.join(',')}}${extra}}}`;
  return { part: `place-${placeId}`, payload: objectOf(text) };
}

function rawPlaceCapture(placeId: string, facilitiesText: string): Capture {
  const text = `{"SelectedPlace":{"PlaceId":${placeId},"Name":"Park ${placeId}","Latitude":"38.5","Longitude":"-120.5","Facilities":${facilitiesText}}}`;
  return { part: `place-${placeId}`, payload: objectOf(text) };
}

function facilityCapture(id: string, fields: string): Capture {
  return { part: `facility-${id}`, payload: objectOf(`{"FacilityId":${id}${fields === '' ? '' : `,${fields}`}}`) };
}

function unitText(unitId: string, fields = ''): string {
  return `{"UnitId":${unitId}${fields === '' ? '' : `,${fields}`}}`;
}

function gridCapture(facilityId: string, header: string, unitsText: string): Capture {
  const text = `{"Facility":{"FacilityId":${facilityId}${header === '' ? '' : `,${header}`},"Units":${unitsText}}}`;
  return { part: `grid-${facilityId}`, payload: objectOf(text) };
}

function singleUnitGrid(facilityId: string, header = '', unit = unitText('9001', '"Name":"PINE 001"')): Capture {
  return gridCapture(facilityId, header, `{"u":${unit}}`);
}

function fixtureCapture(name: string): Capture {
  const envelope = parseEnvelope(`${FIXTURE_ROOT}${name}.json`);
  return { part: envelope.part as string, payload: envelope.payload };
}

interface Run {
  catalog: ReserveCaliforniaCatalog | null;
  results: TransformResult<CampsiteUpsertCandidate>[];
}

function run(captures: Capture[]): Run {
  const etl = new ReserveCaliforniaSitesEtl(SLUG);
  const outcome: Run = { catalog: null, results: [] };
  for (const parsed of etl.parse(bundleOf(captures))) {
    if (!parsed.ok) continue;
    outcome.catalog = parsed.dto;
    outcome.results.push(...etl.transform(parsed.dto));
  }
  return outcome;
}

function parsedOf(captures: Capture[]): ParseResult<ReserveCaliforniaCatalog>[] {
  return [...new ReserveCaliforniaSitesEtl(SLUG).parse(bundleOf(captures))];
}

function recordsOf(captures: Capture[]): CampsiteUpsertCandidate[] {
  return run(captures).results.map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function onlyRecord(captures: Capture[]): CampsiteUpsertCandidate {
  const [record, ...rest] = recordsOf(captures);
  if (record === undefined || rest.length > 0) throw new Error('expected exactly one campsite');
  return record;
}

function badOf(captures: Capture[]): { sourceId?: string; errors: string[] }[] {
  return run(captures).results.flatMap((result) =>
    result.ok ? [] : [{ ...(result.sourceId === undefined ? {} : { sourceId: result.sourceId }), errors: result.errors }],
  );
}

function payloadOf(record: CampsiteUpsertCandidate): Record<string, unknown> {
  return JSON.parse(record.sourcePayload as string) as Record<string, unknown>;
}

function kindOf(unitType: string | null): { kind: CampsiteKind | undefined; electric: boolean | null | undefined } {
  const record = onlyRecord([placeCapture('1', { '5': unitType }), singleUnitGrid('5', '"PlaceId":1')]);
  return { kind: record.kind, electric: record.electricHookups };
}

describe('transform', () => {
  const emeraldBay = (): Capture[] => [
    placeCapture('690', { '611': 'Tent Site', '612': 'Day Use' }),
    facilityCapture('611', '"PlaceId":690,"Name":"Pine Loop","FacilityTypeNew":1,"FacilityBehaviourType":1,"AllowWebBooking":true'),
    facilityCapture('612', '"PlaceId":690,"Name":"Day Use","FacilityTypeNew":2,"FacilityBehaviourType":1,"AllowWebBooking":true'),
    singleUnitGrid('611', '"PlaceId":690,"Name":"Pine Loop"'),
    singleUnitGrid('612', '"PlaceId":690,"Name":"Day Use"', unitText('9002', '"Name":"DAY 001"')),
  ];

  test('a unit becomes a campsite linked to the california campground of its place', () => {
    const [campsite] = run(emeraldBay()).results.flatMap((result) => (result.ok ? [result.record] : []));
    expect(campsite?.dataProviderRef).toEqual(DataProviderRef.reserveCalifornia('9001'));
    expect(campsite?.bookingProvider).toBe(BookingProvider.RESERVECALIFORNIA);
    expect(campsite?.bookingProviderRef).toBe('9001');
    expect(campsite?.parentDataProviderRef).toEqual(DataProviderRef.reserveCalifornia('690'));
    expect(campsite?.name).toBe('PINE 001');
    expect(campsite?.kind).toBe(CampsiteKind.TENT);
    expect(campsite?.kindListed).toBe('Tent Site');
    expect(campsite?.loopName).toBe('Pine Loop');
    expect(campsite?.reservationUrl).toBe('https://reservecalifornia.com/park/690');
    expect(payloadOf(campsite as CampsiteUpsertCandidate)[PARENT_PLACE_ID_KEY]).toBe(690);
  });

  test('a facility that is not standard bookable is reported bad and the others still produce campsites', () => {
    const run1 = run(emeraldBay());
    expect(run1.results.map((result) => result.ok)).toEqual([true, false]);
    expect(badOf(emeraldBay())).toEqual([{ sourceId: '612', errors: [NOT_BOOKABLE] }]);
  });

  test('each way a facility is not bookable', () => {
    const bad = (fields: string) =>
      badOf([placeCapture('1', { '5': null }), facilityCapture('5', `"PlaceId":1,${fields}`), singleUnitGrid('5')]);
    expect(bad('"FacilityTypeNew":2')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"FacilityBehaviourType":2')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"AllowWebBooking":false')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"AllowWebBooking":"false"')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"AllowWebBooking":"False"')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"AllowWebBooking":"FALSE"')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
    expect(bad('"FacilityTypeNew":"2"')).toEqual([{ sourceId: '5', errors: [NOT_BOOKABLE] }]);
  });

  test('a facility is bookable when none of the three says otherwise, however much is missing', () => {
    const records = (fields: string) =>
      recordsOf([placeCapture('1', { '5': null }), facilityCapture('5', `"PlaceId":1${fields}`), singleUnitGrid('5')]);
    expect(records('')).toHaveLength(1);
    expect(records(',"FacilityTypeNew":1,"FacilityBehaviourType":0,"AllowWebBooking":true')).toHaveLength(1);
    expect(records(',"FacilityTypeNew":null,"FacilityBehaviourType":"x","AllowWebBooking":"no"')).toHaveLength(1);
    expect(records(',"FacilityTypeNew":2.5,"AllowWebBooking":1')).toHaveLength(1);
    expect(records(',"FacilityTypeNew":3,"FacilityBehaviourType":1')).toHaveLength(1);
  });

  test('isStandardBookable reads the three fields', () => {
    const facility = { facilityId: 1n, placeId: null, name: null, raw: {} };
    expect(isStandardBookable({ ...facility, facilityTypeNew: null, facilityBehaviourType: null, allowWebBooking: null })).toBe(true);
    expect(isStandardBookable({ ...facility, facilityTypeNew: 2n, facilityBehaviourType: null, allowWebBooking: null })).toBe(false);
    expect(isStandardBookable({ ...facility, facilityTypeNew: null, facilityBehaviourType: 2n, allowWebBooking: null })).toBe(false);
    expect(isStandardBookable({ ...facility, facilityTypeNew: null, facilityBehaviourType: null, allowWebBooking: false })).toBe(false);
  });

  test('a grid with no place id and no facility page is reported bad', () => {
    expect(badOf([placeCapture('1', { '5': null }), singleUnitGrid('5')])).toEqual([
      { sourceId: '5', errors: ['missing parent place id'] },
    ]);
  });

  test('the place id comes from the grid first and from the facility page second', () => {
    const fromFacility = onlyRecord([
      placeCapture('1', { '5': null }),
      facilityCapture('5', '"PlaceId":1'),
      singleUnitGrid('5'),
    ]);
    expect(fromFacility.parentDataProviderRef).toEqual(DataProviderRef.reserveCalifornia('1'));
    const gridWins = onlyRecord([
      placeCapture('1', { '5': null }),
      placeCapture('2', { '5': null }),
      facilityCapture('5', '"PlaceId":2'),
      singleUnitGrid('5', '"PlaceId":1'),
    ]);
    expect(gridWins.parentDataProviderRef).toEqual(DataProviderRef.reserveCalifornia('1'));
  });

  test('a facility page whose place id is unreadable does not supply one', () => {
    expect(
      badOf([placeCapture('1', { '5': null }), facilityCapture('5', '"PlaceId":"x"'), singleUnitGrid('5')]),
    ).toEqual([{ sourceId: '5', errors: ['missing parent place id'] }]);
  });

  test('a place that was not parsed is named in the report', () => {
    expect(badOf([singleUnitGrid('5', '"PlaceId":77')])).toEqual([
      { sourceId: '5', errors: ['parent place 77 not parsed'] },
    ]);
  });

  test('a facility the parent place does not list is reported bad', () => {
    expect(badOf([placeCapture('1', { '6': null }), singleUnitGrid('5', '"PlaceId":1')])).toEqual([
      { sourceId: '5', errors: ['facility not linked from parent place 1'] },
    ]);
  });

  test('a facility listed only by key is linked', () => {
    const records = recordsOf([rawPlaceCapture('1', '{"5":{}}'), singleUnitGrid('5', '"PlaceId":1')]);
    expect(records).toHaveLength(1);
  });

  test('the report ids and messages carry ids past 2^53 whole', () => {
    expect(badOf([singleUnitGrid(BEYOND_SAFE, '"PlaceId":9007199254740995')])).toEqual([
      { sourceId: BEYOND_SAFE, errors: ['parent place 9007199254740995 not parsed'] },
    ]);
  });

  test('a unit name that is blank falls back to the unit id and one with edges keeps them', () => {
    const names = (unit: string) => onlyRecord([placeCapture('1', { '5': null }), singleUnitGrid('5', '"PlaceId":1', unit)]).name;
    expect(names(unitText('9001', '"Name":"  "'))).toBe('9001');
    expect(names(unitText('9001', '"Name":""'))).toBe('9001');
    expect(names(unitText('9001', '"Name":null'))).toBe('9001');
    expect(names(unitText('9001'))).toBe('9001');
    expect(names(unitText('9001', '"Name":"\\u001c"'))).toBe('9001');
    expect(names(unitText('9001', '"Name":" A 1 "'))).toBe(' A 1 ');
    expect(names(unitText('9001', '"Name":12'))).toBe('12');
    expect(names(unitText('9001', `"Name":"${String.fromCodePoint(0xfeff)}"`))).toBe(String.fromCodePoint(0xfeff));
  });

  test('the loop name is the grid name, then the facility page name, then nothing', () => {
    const loop = (facility: Capture[], header: string) =>
      onlyRecord([placeCapture('1', { '5': null }), ...facility, singleUnitGrid('5', `"PlaceId":1${header}`)]).loopName;
    expect(loop([facilityCapture('5', '"Name":"Facility name"')], ',"Name":"Grid name"')).toBe('Grid name');
    expect(loop([facilityCapture('5', '"Name":"Facility name"')], '')).toBe('Facility name');
    expect(loop([facilityCapture('5', '"Name":"Facility name"')], ',"Name":null')).toBe('Facility name');
    expect(loop([], '')).toBeNull();
    expect(loop([facilityCapture('5', '"PlaceId":1')], '')).toBeNull();
    expect(loop([], ',"Name":""')).toBe('');
  });

  test('a unit id past 2^53 keeps every digit in the refs and the payload', () => {
    const record = onlyRecord([
      placeCapture('1', { '5': null }),
      singleUnitGrid('5', '"PlaceId":1', unitText(BEYOND_SAFE, '"Name":"x"')),
    ]);
    expect(record.bookingProviderRef).toBe(BEYOND_SAFE);
    expect(record.dataProviderRef.serialize()).toBe(BEYOND_SAFE);
    expect(record.sourcePayload).toContain(`"unit_id":${BEYOND_SAFE}`);
  });

  test('units are emitted facility by facility, each grid in the order its units are written', () => {
    const records = recordsOf([
      placeCapture('1', { '5': null, '6': null }),
      gridCapture('6', '"PlaceId":1', `{"x":${unitText('3')},"y":${unitText('1')}}`),
      gridCapture('5', '"PlaceId":1', `{"30":${unitText('20')},"4":${unitText('10')},"200":${unitText('30')}}`),
    ]);
    expect(records.map((record) => record.bookingProviderRef)).toEqual(['3', '1', '20', '10', '30']);
  });

  test('units that are not objects or have no Long UnitId are dropped without a report', () => {
    const records = recordsOf([
      placeCapture('1', { '5': null }),
      gridCapture('5', '"PlaceId":1', `{"a":5,"b":null,"c":[],"d":{"Name":"no id"},"e":${unitText('"x"')},"f":${unitText('"7"')},"g":${unitText('1.5')}}`),
    ]);
    expect(records.map((record) => record.bookingProviderRef)).toEqual(['7']);
  });

  test('a grid with no units produces nothing and does not stop the others', () => {
    const results = run([
      placeCapture('1', { '5': null, '6': null }),
      gridCapture('5', '"PlaceId":1', '{}'),
      singleUnitGrid('6', '"PlaceId":1'),
    ]).results;
    expect(results.map((result) => result.ok)).toEqual([true]);
  });

  test('a unit repeated across two grids is emitted from both', () => {
    const records = recordsOf([
      placeCapture('1', { '5': null, '6': null }),
      singleUnitGrid('5', '"PlaceId":1'),
      singleUnitGrid('6', '"PlaceId":1'),
    ]);
    expect(records.map((record) => record.bookingProviderRef)).toEqual(['9001', '9001']);
  });

  test('a later grid page for the same facility replaces the earlier one in place', () => {
    const records = recordsOf([
      placeCapture('1', { '5': null, '6': null }),
      singleUnitGrid('5', '"PlaceId":1', unitText('1')),
      singleUnitGrid('6', '"PlaceId":1', unitText('2')),
      singleUnitGrid('5', '"PlaceId":1', unitText('3')),
    ]);
    expect(records.map((record) => record.bookingProviderRef)).toEqual(['3', '2']);
  });
});

describe('kind', () => {
  test('the unit type name chooses the kind and electric hookups only when named', () => {
    expect(kindOf('Tent Site')).toEqual({ kind: CampsiteKind.TENT, electric: null });
    expect(kindOf('Day Use Pavilion')).toEqual({ kind: CampsiteKind.DAY_USE, electric: null });
    expect(kindOf('Group Campsite')).toEqual({ kind: CampsiteKind.GROUP, electric: null });
    expect(kindOf('Equestrian Camp')).toEqual({ kind: CampsiteKind.EQUESTRIAN, electric: null });
    expect(kindOf('Equestrain Camp')).toEqual({ kind: CampsiteKind.EQUESTRIAN, electric: null });
    expect(kindOf('Premium Cabin (3 People)')).toEqual({ kind: CampsiteKind.CABIN, electric: null });
    expect(kindOf('Boat In Campsite')).toEqual({ kind: CampsiteKind.BOAT_IN, electric: null });
    expect(kindOf('Hike/Bike Campsite')).toEqual({ kind: CampsiteKind.WALK_IN, electric: null });
    expect(kindOf('Walk-In Site')).toEqual({ kind: CampsiteKind.WALK_IN, electric: null });
    expect(kindOf('Premium Campsite')).toEqual({ kind: CampsiteKind.STANDARD, electric: null });
    expect(kindOf('Mystery')).toEqual({ kind: CampsiteKind.OTHER, electric: null });
  });

  test('a hook up site is an RV site and electric only when the name also opens a parenthesis with e', () => {
    expect(kindOf('RV Hook Up (Electric)')).toEqual({ kind: CampsiteKind.RV, electric: true });
    expect(kindOf('Full Hook Up (E/W)')).toEqual({ kind: CampsiteKind.RV, electric: true });
    expect(kindOf('Hook Up')).toEqual({ kind: CampsiteKind.RV, electric: null });
    expect(kindOf('Hook Up (Water)')).toEqual({ kind: CampsiteKind.RV, electric: null });
  });

  test('the name is trimmed and lower-cased before the match', () => {
    expect(kindOf('  TENT SITE  ')).toEqual({ kind: CampsiteKind.TENT, electric: null });
    expect(kindOf('\u001fGROUP\u001c')).toEqual({ kind: CampsiteKind.GROUP, electric: null });
  });

  test('an empty or blank unit type is OTHER and a facility with no unit type is OTHER with nothing listed', () => {
    expect(kindOf('')).toEqual({ kind: CampsiteKind.OTHER, electric: null });
    expect(kindOf('   ')).toEqual({ kind: CampsiteKind.OTHER, electric: null });
    const unlisted = onlyRecord([placeCapture('1', { '5': null }), singleUnitGrid('5', '"PlaceId":1')]);
    expect(unlisted.kind).toBe(CampsiteKind.OTHER);
    expect(unlisted.kindListed).toBeNull();
  });

  test('the listed kind is the unit type exactly as captured', () => {
    expect(onlyRecord([placeCapture('1', { '5': ' Tent Site ' }), singleUnitGrid('5', '"PlaceId":1')]).kindListed).toBe(' Tent Site ');
  });

  test('facility 1989 of place-1138 takes its kind from the unit type the document writes first, not the smallest key', () => {
    const record = onlyRecord([fixtureCapture('place-1138'), singleUnitGrid('1989', '"PlaceId":1138')]);
    expect(record.kindListed).toBe('Group Campsite');
    expect(record.kind).toBe(CampsiteKind.GROUP);
  });

  test('facility 2037 of place-1192 takes the first of three out-of-order unit types', () => {
    const record = onlyRecord([fixtureCapture('place-1192'), singleUnitGrid('2037', '"PlaceId":1192')]);
    expect(record.kindListed).toBe('Campsite');
    expect(record.kind).toBe(CampsiteKind.STANDARD);
  });

  test('facility 1940 of place-1077 lists the unit type written first of the two', () => {
    const record = onlyRecord([fixtureCapture('place-1077'), singleUnitGrid('1940', '"PlaceId":1077')]);
    expect(record.kindListed).toBe('Hike/Bike Campsite');
  });
});

describe('source payload', () => {
  test('it is the unit as captured with the unit, facility and place added', () => {
    const record = onlyRecord([
      placeCapture('1', { '5': null }),
      facilityCapture('5', '"PlaceId":1,"Name":"Loop","Extra":[1,2]'),
      singleUnitGrid('5', '"Name":"Grid loop"', unitText('9001', '"Name":"A","Slices":{"2":1,"1":2},"Rate":8.0')),
    ]);
    expect(payloadOf(record)).toEqual({
      UnitId: 9001,
      Name: 'A',
      Slices: { '1': 2, '2': 1 },
      Rate: 8,
      unit_id: 9001,
      facility_id: 5,
      [PARENT_PLACE_ID_KEY]: 1,
      facility_name: 'Grid loop',
      facility: { FacilityId: 5, PlaceId: 1, Name: 'Loop', Extra: [1, 2] },
    });
    expect(record.sourcePayload).toContain('"Rate":8.0');
  });

  test('the facility name and the facility object are left out when there is no grid name or facility page', () => {
    const record = onlyRecord([placeCapture('1', { '5': null }), singleUnitGrid('5', '"PlaceId":1')]);
    expect(Object.keys(payloadOf(record)).sort()).toEqual(['Name', 'UnitId', PARENT_PLACE_ID_KEY, 'facility_id', 'unit_id']);
  });

  test('keys the adapter adds replace the unit\'s own and keys it does not add are left', () => {
    const record = onlyRecord([
      placeCapture('1', { '5': null }),
      singleUnitGrid('5', '"PlaceId":1', unitText('9001', '"unit_id":"mine","facility_id":0,"_parent_place_id":-1,"facility_name":"x","facility":1')),
    ]);
    expect(payloadOf(record)).toMatchObject({ unit_id: 9001, facility_id: 5, [PARENT_PLACE_ID_KEY]: 1, facility: 1, facility_name: 'x' });
  });

  test('a unit key named __proto__ is carried as a key', () => {
    const record = onlyRecord([
      placeCapture('1', { '5': null }),
      singleUnitGrid('5', '"PlaceId":1', unitText('9001', '"__proto__":{"polluted":true}')),
    ]);
    expect(Object.hasOwn(payloadOf(record), '__proto__')).toBe(true);
    expect(({} as { polluted?: boolean }).polluted).toBeUndefined();
  });

  test('a number beyond 2^53 inside the unit keeps its digits', () => {
    const record = onlyRecord([
      placeCapture('1', { '5': null }),
      singleUnitGrid('5', '"PlaceId":1', unitText('9001', '"Big":12345678901234567890,"Small":0.1000000000000000055511151231257827')),
    ]);
    expect(record.sourcePayload).toContain('"Big":12345678901234567890');
    expect(record.sourcePayload).toContain('"Small":0.1000000000000000055511151231257827');
  });
});

describe('parse', () => {
  test('no envelopes is refused with the capture hint', () => {
    expect(() => parsedOf([])).toThrow(NO_ENVELOPES);
  });

  test('two inputs are refused as the sole-input rule', () => {
    const bundle = new InputBundle(
      new Map([
        ['a', [envelopeOf(singleUnitGrid('5'))]],
        ['b', [envelopeOf(singleUnitGrid('6'))]],
      ]),
    );
    expect(() => [...new ReserveCaliforniaSitesEtl(SLUG).parse(bundle)]).toThrow(SOLE_INPUT_REFUSAL);
  });

  test('a catalog whose grids hold no unit is a bad parse', () => {
    expect(parsedOf([gridCapture('5', '', '{}')])).toEqual([{ ok: false, errors: [NO_UNITS] }]);
    expect(parsedOf([gridCapture('5', '', '{"a":{"Name":"no id"}}')])).toEqual([{ ok: false, errors: [NO_UNITS] }]);
    expect(parsedOf([placeCapture('1', { '5': null })])).toEqual([{ ok: false, errors: [NO_UNITS] }]);
  });

  test('one grid with a unit makes the catalog a success', () => {
    expect(parsedOf([gridCapture('5', '', '{}'), singleUnitGrid('6')])[0]?.ok).toBe(true);
  });

  test('a facility object that is null on a page is refused', () => {
    expect(() => parsedOf([{ part: 'grid-5', payload: objectOf('{"Facility":null}') }])).toThrow(NOT_AN_OBJECT);
    expect(() => parsedOf([{ part: 'misc', payload: objectOf('{"Facility":null}') }])).toThrow(NOT_AN_OBJECT);
  });

  test('a units value that is not an object is refused', () => {
    expect(() => parsedOf([gridCapture('5', '', '[]')])).toThrow(NOT_AN_OBJECT);
    expect(() => parsedOf([gridCapture('5', '', 'null')])).toThrow(NOT_AN_OBJECT);
  });

  test('a facility or place field that is an object is refused', () => {
    expect(() => parsedOf([gridCapture('5', '"PlaceId":{}', '{}'), singleUnitGrid('6')])).toThrow(OBJECT_NOT_PRIMITIVE);
    expect(() => parsedOf([facilityCapture('5', '"AllowWebBooking":{}'), singleUnitGrid('6')])).toThrow(OBJECT_NOT_PRIMITIVE);
  });

  test('a grid without a Long facility id is dropped, and a facility page without one is too', () => {
    const { catalog } = run([
      { part: 'grid-1', payload: objectOf('{"Facility":{"Units":{"a":{"UnitId":1}}}}') },
      { part: 'facility-1', payload: objectOf('{"PlaceId":1}') },
      singleUnitGrid('6'),
    ]);
    expect([...(catalog?.grids.keys() ?? [])]).toEqual([6n]);
    expect(catalog?.facilities.size).toBe(0);
  });
});

describe('real captures', () => {
  test('the Russian Gulch grid has no place id of its own, so without a facility page it is reported bad', () => {
    expect(badOf([fixtureCapture('place-701'), fixtureCapture('grid-2174')])).toEqual([
      { sourceId: '2174', errors: ['missing parent place id'] },
    ]);
  });

  test('with the facility page naming its place, the grid becomes a walk-in campsite of that place', () => {
    const record = onlyRecord([
      fixtureCapture('place-701'),
      facilityCapture('2174', '"PlaceId":701,"Name":"Hike/Bike"'),
      fixtureCapture('grid-2174'),
    ]);
    expect(record.name).toBe('Hike/Bike Campsite #HB1');
    expect(record.bookingProviderRef).toBe('54831');
    expect(record.parentDataProviderRef).toEqual(DataProviderRef.reserveCalifornia('701'));
    expect(record.kind).toBe(CampsiteKind.WALK_IN);
    expect(record.kindListed).toBe('Hike/Bike Campsite');
    expect(record.loopName).toBe('Hike/Bike Campsite');
  });
});
