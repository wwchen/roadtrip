import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { BookingProvider, Carrier, DataProviderRef, type CarrierSignal } from '@roadtrip/domain';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { loadPoiRegistry, type PoiDataEntry, type PoiRegistry } from '../../registry/poi-registry.js';
import { RecGovCampgroundsEtl, rawFacilityId, stringAtRawPath } from './campgrounds.js';
import type { RecGovDto } from './dto.js';

const TEMP_DIR_PREFIX = 'recgov-campgrounds-';
const HTTP_OK = 200;
const SLUG = 'recgov-campgrounds';
const RIDB_INPUT = 'recgov-campgrounds-raw';
const ENRICHMENT_INPUT = 'recgov-campground-enrichment';
const RAW_DIR = '/unused';
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const NO_BREAK_SPACE = String.fromCodePoint(0xa0);
const SHARP_S = String.fromCodePoint(0xdf);

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function envelopeOf(payloadJson: string): Envelope {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"2026-06-17T00:00:00Z","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function bundleOf(pages: string[], enrichment: string[] | null = null): InputBundle {
  const captures = new Map<string, Envelope[]>([[RIDB_INPUT, pages.map(envelopeOf)]]);
  if (enrichment !== null) captures.set(ENRICHMENT_INPUT, enrichment.map(envelopeOf));
  return new InputBundle(captures);
}

function registryOf(poiData: PoiDataEntry[]): PoiRegistry {
  return {
    dataSources: [],
    poiData,
    campsiteData: [],
    bookingProviders: [],
    dataSource: () => undefined,
    poiDataByName: (name) => poiData.find((row) => row.name === name),
    campsiteDataByName: () => undefined,
  };
}

function ctxWithAgency(agency: PoiDataEntry['agency']): TransformCtx {
  return TransformCtx.load(
    RAW_DIR,
    registryOf([
      {
        name: 'Rec.gov Campgrounds',
        enabled: true,
        category: 'campground',
        subcategory: 'federal',
        agency,
        etls: [{ slug: SLUG, adapter: 'RecGovCampgroundsEtl', inputs: [RIDB_INPUT, ENRICHMENT_INPUT], args: {} }],
      },
    ]),
  );
}

const PRODUCTION_CTX = TransformCtx.load(RAW_DIR, loadPoiRegistry(PRODUCTION_REGISTRY_PATH));

function parsed(bundle: InputBundle): ParseResult<RecGovDto>[] {
  return [...new RecGovCampgroundsEtl(SLUG).parse(bundle)];
}

function transformed(
  pages: string[],
  enrichment: string[] | null = null,
  ctx: TransformCtx = PRODUCTION_CTX,
): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = new RecGovCampgroundsEtl(SLUG);
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(pages, enrichment))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto, ctx));
  }
  return results;
}

function page(...facilityJson: string[]): string {
  return `{"RECDATA":[${facilityJson.join(',')}]}`;
}

function facility(extra = ''): string {
  return `{"FacilityID":"1","FacilityName":"Camp","FacilityLatitude":10,"FacilityLongitude":20${extra === '' ? '' : `,${extra}`}}`;
}

function recordOf(extra = '', enrichment: string | null = null, ctx: TransformCtx = PRODUCTION_CTX): CampgroundUpsertCandidate {
  const [only, ...rest] = transformed([page(facility(extra))], enrichment === null ? null : [enrichment], ctx);
  if (only === undefined || !only.ok || rest.length > 0) throw new Error('expected exactly one ok record');
  return only.record;
}

function enrichmentOf(aggregate: string, facilityId = '1'): string {
  return `{"facility_id":"${facilityId}","aggregate":${aggregate}}`;
}

const UPPER_PINES = `{
  "FacilityID": 232447,
  "FacilityName": "Upper Pines",
  "FacilityLatitude": 37.739,
  "FacilityLongitude": -119.565,
  "FacilityReservationURL": "https://www.recreation.gov/camping/campgrounds/232447",
  "FacilityDescription": "<p>Upper Pines is a Yosemite campground.</p>",
  "Reservable": true,
  "ACTIVITY": [{"ActivityName": "CAMPING"}, {"ActivityName": "HIKING"}],
  "MEDIA": [
    {"URL": "https://cdn.example/preview.webp", "IsPreview": true, "IsPrimary": false},
    {"URL": "https://cdn.example/primary.webp", "IsPreview": false, "IsPrimary": true}
  ],
  "ORGANIZATION": [{"OrgAbbrevName": "NPS", "OrgName": "National Park Service"}],
  "RECAREA": [
    {"RecAreaID": 2782, "RecAreaName": "Yosemite National Park"},
    {"RecAreaID": 1234, "RecAreaName": "Ignored Second Parent"}
  ],
  "FACILITYADDRESS": [{"AddressStateCode": "CA", "AddressCountryCode": "USA"}]
}`;

const WHITE_WOLF = `{
  "FacilityID": 10083567,
  "FacilityName": "White Wolf",
  "FacilityLatitude": 37.869,
  "FacilityLongitude": -119.647,
  "FacilityReservationURL": "",
  "Reservable": true,
  "ORGANIZATION": [{"OrgAbbrevName": "NPS", "OrgName": "National Park Service"}],
  "FACILITYADDRESS": [{"AddressStateCode": "CA", "AddressCountryCode": "USA"}]
}`;

const BUTTE_MEADOWS = `{
  "FacilityID": 248965,
  "FacilityName": "Butte Meadows Campground",
  "FacilityLatitude": 40.078517,
  "FacilityLongitude": -121.558811,
  "FacilityReservationURL": "",
  "Reservable": false,
  "CAMPSITE": [],
  "LINK": [{"URL": "https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", "Title": "Butte Meadows Campground", "LinkType": "Official Web Site"}],
  "ORGANIZATION": [{"OrgAbbrevName": "FS", "OrgName": "USDA Forest Service"}],
  "FACILITYADDRESS": [{"AddressStateCode": "CA", "AddressCountryCode": "USA"}]
}`;

const UPPER_PINES_ENRICHMENT = `{
  "facility_id": "232447",
  "aggregate": {
    "average_rating": 4.25,
    "number_of_ratings": 8,
    "aggregate_cell_coverage_ratings": [
      {"carrier": "Verizon", "average_rating": 3.5, "number_of_ratings": 4},
      {"carrier": "AT&T", "average_rating": 1.25, "number_of_ratings": 2},
      {"carrier": "T-Mobile", "average_rating": 3.0, "number_of_ratings": 5},
      {"carrier": "Sprint", "average_rating": 2.75, "number_of_ratings": 6},
      {"carrier": "US Cellular", "average_rating": 2.0, "number_of_ratings": 3},
      {"carrier": "Boost Mobile", "average_rating": 5.0, "number_of_ratings": 1}
    ]
  }
}`;

function byFacility(results: TransformResult<CampgroundUpsertCandidate>[]): Map<string, CampgroundUpsertCandidate> {
  const found = new Map<string, CampgroundUpsertCandidate>();
  for (const result of results) {
    if (result.ok) found.set(result.record.dataProviderRef.serialize(), result.record);
  }
  return found;
}

describe('the production registry row', () => {
  test('derives the agency from ORGANIZATION[0].OrgName and files the bucket under federal', () => {
    expect(PRODUCTION_CTX.agencyFor(SLUG)).toEqual({ kind: 'derived', field: 'ORGANIZATION[0].OrgName' });
    expect(PRODUCTION_CTX.subcategoryFor(SLUG)).toBe('federal');
  });
});

describe('transform, ported from the Kotlin RecGovCampgroundsEtlTest', () => {
  test('treats nonreservable RIDB facilities as agency info pages, not RecGov booking targets', () => {
    const campgrounds = byFacility(transformed([page(UPPER_PINES, WHITE_WOLF, BUTTE_MEADOWS)]));

    const reservable = campgrounds.get('232447');
    expect(reservable?.dataProviderRef).toEqual(DataProviderRef.recGov('232447'));
    expect(reservable?.bookingProvider).toBe(BookingProvider.RECGOV);
    expect(reservable?.bookingProviderRef).toBe('232447');
    expect(reservable?.reservationUrl).toBe('https://www.recreation.gov/camping/campgrounds/232447');

    expect(campgrounds.get('10083567')?.reservationUrl).toBe('https://www.recreation.gov/camping/campgrounds/10083567');

    const nonReservable = campgrounds.get('248965');
    expect(nonReservable?.reservationUrl).toBe('https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276');
    expect(nonReservable?.bookingProvider).toBeNull();
    expect(nonReservable?.bookingProviderRef).toBeNull();
  });

  test('promotes RIDB description, media, activities and the recgov rating and cell enrichment', () => {
    const campgrounds = byFacility(transformed([page(UPPER_PINES, WHITE_WOLF)], [UPPER_PINES_ENRICHMENT]));
    const upperPines = campgrounds.get('232447');

    expect(upperPines?.management).toEqual({ agency: 'National Park Service' });
    expect(upperPines?.mediumDescription).toBe('<p>Upper Pines is a Yosemite campground.</p>');
    expect(upperPines?.photos).toEqual([{ url: 'https://cdn.example/primary.webp' }]);
    expect(upperPines?.metadata).toEqual({ activities: ['Camping', 'Hiking'], rating: { average: 4.25, count: 8 } });
    expect(upperPines?.cellService).toEqual([
      { carrier: Carrier.VERIZON, average: 3.5, count: 4 },
      { carrier: Carrier.ATT, average: 1.25, count: 2 },
      { carrier: Carrier.TMOBILE, average: 3, count: 5 },
      { carrier: Carrier.SPRINT, average: 2.75, count: 6 },
      { carrier: Carrier.US_CELLULAR, average: 2, count: 3 },
    ]);
    expect(upperPines?.parentName).toBe('Yosemite National Park');
    expect(campgrounds.get('10083567')?.parentName).toBeNull();
  });

  test('leaves activities-only metadata without a rating', () => {
    const campgrounds = byFacility(transformed([page(UPPER_PINES, BUTTE_MEADOWS)]));
    expect(campgrounds.get('232447')?.metadata).toEqual({ activities: ['Camping', 'Hiking'] });
    expect(campgrounds.get('248965')?.metadata).toBeNull();
    expect(campgrounds.get('232447')?.cellService).toEqual([]);
  });

  test('treats a non-scalar agency path as a missing value', () => {
    const ctx = ctxWithAgency({ kind: 'derived', field: 'ORGANIZATION[0]' });
    const campgrounds = byFacility(transformed([page(UPPER_PINES)], null, ctx));
    expect(campgrounds.get('232447')?.management).toBeNull();
  });
});

describe('the agency, derived through ORGANIZATION[0].OrgName', () => {
  function agencyOf(organization: string): string | null {
    return recordOf(organization === '' ? '' : `"ORGANIZATION":${organization}`).management?.agency ?? null;
  }

  test('reads the first organization name', () => {
    expect(agencyOf('[{"OrgAbbrevName":"NPS","OrgName":"National Park Service"}]')).toBe('National Park Service');
  });

  test('reads only index 0, so a later organization never stands in for a nameless first one', () => {
    expect(agencyOf('[{"OrgName":"First"},{"OrgName":"Second"}]')).toBe('First');
    expect(agencyOf('[{"OrgAbbrevName":"X"},{"OrgName":"Second"}]')).toBeNull();
    expect(agencyOf('[{"OrgName":null},{"OrgName":"Second"}]')).toBeNull();
    expect(agencyOf('[{"OrgName":"  "},{"OrgName":"Second"}]')).toBeNull();
  });

  test.each([
    ['a facility with no ORGANIZATION key', ''],
    ['an empty ORGANIZATION list', '[]'],
    ['a null ORGANIZATION', 'null'],
    ['an ORGANIZATION that is an object, not a list', '{"OrgName":"National Park Service"}'],
    ['an ORGANIZATION that is a string', '"National Park Service"'],
    ['a first organization without OrgName', '[{"OrgAbbrevName":"NPS"}]'],
    ['a first organization that is not an object', '["National Park Service"]'],
    ['a null first organization', '[null]'],
    ['a null OrgName', '[{"OrgName":null}]'],
    ['an empty OrgName', '[{"OrgName":""}]'],
    ['a whitespace OrgName, no-break space included', '[{"OrgName":" \\t\\u00a0 "}]'],
    ['an OrgName of only a file separator, which Kotlin counts as whitespace', '[{"OrgName":"\\u001c"}]'],
    ['an OrgName that is an object', '[{"OrgName":{"Name":"National Park Service"}}]'],
    ['an OrgName that is a list', '[{"OrgName":["National Park Service"]}]'],
  ])('%s has no management', (_label, organization) => {
    expect(agencyOf(organization)).toBeNull();
  });

  test('a byte order mark is a name, because Kotlin does not count it as whitespace', () => {
    expect(agencyOf('[{"OrgName":"\\ufeff"}]')).toBe(String.fromCodePoint(0xfeff));
  });

  test('keeps the name exactly as written, untrimmed', () => {
    expect(agencyOf('[{"OrgName":" National Park Service "}]')).toBe(' National Park Service ');
  });

  test('a scalar that is not text is read by its content', () => {
    expect(agencyOf('[{"OrgName":5}]')).toBe('5');
    expect(agencyOf('[{"OrgName":true}]')).toBe('true');
    expect(agencyOf('[{"OrgName":1.10}]')).toBe('1.10');
  });

  test('a constant agency applies to every facility, whatever its organization', () => {
    const ctx = ctxWithAgency({ kind: 'constant', value: 'Fixed Agency' });
    expect(recordOf('', null, ctx).management).toEqual({ agency: 'Fixed Agency' });
    expect(recordOf('"ORGANIZATION":[{"OrgName":"Other"}]', null, ctx).management).toEqual({ agency: 'Fixed Agency' });
  });

  test('no agency configured means no management', () => {
    const ctx = ctxWithAgency(undefined);
    expect(recordOf('"ORGANIZATION":[{"OrgName":"Other"}]', null, ctx).management).toBeNull();
  });

  test('a facility the page lists twice reads the organization of the last listing', () => {
    const results = transformed([
      page(facility('"ORGANIZATION":[{"OrgName":"First Listing"}]'), facility('"ORGANIZATION":[{"OrgName":"Last Listing"}]')),
    ]);
    const agencies = results.map((result) => (result.ok ? result.record.management?.agency : undefined));
    expect(agencies).toEqual(['Last Listing', 'Last Listing']);
  });
});

function rawOf(json: string): JsonObject {
  return JSON.parse(json) as JsonObject;
}

describe('stringAtRawPath, the path grammar behind derived_from_field', () => {
  const RAW = rawOf(
    '{"ORGANIZATION":[{"OrgName":"A"},{"OrgName":"B"}],"nested":{"deeper":{"name":"deep"},"list":[[1],"x"]},' +
      '"plain":"text","blank":" ","num":7,"flag":false,"nothing":null,"empty":[],"text":"x"}',
  );

  test.each([
    ['plain', 'text'],
    ['num', '7'],
    ['flag', 'false'],
    ['nested.deeper.name', 'deep'],
    ['ORGANIZATION[0].OrgName', 'A'],
    ['ORGANIZATION[1].OrgName', 'B'],
    ['ORGANIZATION[01].OrgName', 'B'],
    ['nested.list[1]', 'x'],
  ])('%s reads %j', (expression, expected) => {
    expect(stringAtRawPath(RAW, expression)).toBe(expected);
  });

  test.each([
    ['an empty path', ''],
    ['a missing field', 'absent'],
    ['a missing nested field', 'nested.absent.name'],
    ['a blank scalar', 'blank'],
    ['a null', 'nothing'],
    ['an index into a null', 'nothing[0]'],
    ['an object, which is not a scalar', 'nested'],
    ['a list, which is not a scalar', 'ORGANIZATION'],
    ['an index past the end', 'ORGANIZATION[2].OrgName'],
    ['an index into an empty list', 'empty[0]'],
    ['an index into an object', 'nested[0]'],
    ['an index into a string', 'plain[0]'],
    ['a list element that is not a scalar', 'nested.list[0]'],
    ['a field read through a list without an index', 'ORGANIZATION.OrgName'],
    ['a field read through a scalar', 'plain.more'],
    ['an empty segment', 'nested..deeper'],
    ['a trailing separator', 'nested.'],
    ['a leading separator', '.nested'],
    ['an unterminated index', 'ORGANIZATION[0'],
    ['an unopened index', 'ORGANIZATION0]'],
    ['a negative index', 'ORGANIZATION[-1].OrgName'],
    ['a non-numeric index', 'ORGANIZATION[x].OrgName'],
    ['an empty index', 'ORGANIZATION[].OrgName'],
    ['two indexes on one segment', 'nested.list[0][0]'],
    ['an index with no field', '[0]'],
    ['text after an index', 'ORGANIZATION[0]x.OrgName'],
    ['an index before the field name', '[0]ORGANIZATION'],
    ['a stray closing bracket before the field name', ']plain'],
    ['an empty index after a scalar', 'plain[]'],
  ])('%s reads nothing', (_label, expression) => {
    expect(stringAtRawPath(RAW, expression)).toBeNull();
  });

  test('an index too large for an Int is read as no index at all, as Kotlin toIntOrNull does', () => {
    expect(stringAtRawPath(RAW, 'plain[99999999999]')).toBe('text');
    expect(stringAtRawPath(RAW, 'ORGANIZATION[99999999999].OrgName')).toBeNull();
    expect(stringAtRawPath(RAW, 'ORGANIZATION[2147483647].OrgName')).toBeNull();
    expect(stringAtRawPath(RAW, 'plain[2147483648]')).toBe('text');
  });

  test('field names may carry spaces and other punctuation, but not dots or brackets', () => {
    const odd = rawOf('{"Org Name":"spaced","a-b":"dashed","a:b":"colon","a\\nb":"newline"}');
    expect(stringAtRawPath(odd, 'Org Name')).toBe('spaced');
    expect(stringAtRawPath(odd, 'a-b')).toBe('dashed');
    expect(stringAtRawPath(odd, 'a:b')).toBe('colon');
    expect(stringAtRawPath(odd, 'a\nb')).toBe('newline');
  });

  test('a key that exists only on the prototype chain is absent', () => {
    expect(stringAtRawPath(RAW, 'constructor')).toBeNull();
    expect(stringAtRawPath(RAW, 'constructor.name')).toBeNull();
    expect(stringAtRawPath(RAW, '__proto__')).toBeNull();
    expect(stringAtRawPath(RAW, 'toString')).toBeNull();
  });
});

describe('activities', () => {
  function activitiesOf(names: string[]): string[] | undefined {
    const rows = names.map((name) => `{"ActivityName":${JSON.stringify(name)}}`).join(',');
    return recordOf(`"ACTIVITY":[${rows}]`).metadata?.activities;
  }

  test.each([
    ['CAMPING', 'Camping'],
    ['HISTORIC & CULTURAL SITE', 'Historic And Cultural Site'],
    ['E-Biking, Class 1', 'E-biking, Class 1'],
    ['Dogs on Leash (Leash Required)', 'Dogs On Leash (leash Required)'],
    ['TRAILS, ALL TERRAIN/OHV', 'Trails, All Terrain/ohv'],
    ['A&B', 'Aandb'],
    ['  spaced   out  ', 'Spaced Out'],
    ['tab\tand\nnewline', 'Tab And Newline'],
    [`${SHARP_S}a ${SHARP_S}b`, 'SSa SSb'],
    [`stra${SHARP_S}e`, `Stra${SHARP_S}e`],
  ])('%j becomes %j', (name, expected) => {
    expect(activitiesOf([name])).toEqual([expected]);
  });

  test('only ASCII whitespace splits words, and a word of other whitespace is dropped', () => {
    expect(activitiesOf([`a${NO_BREAK_SPACE}b`])).toEqual([`A${NO_BREAK_SPACE}b`]);
    expect(activitiesOf([`a ${NO_BREAK_SPACE} b`])).toEqual(['A B']);
  });

  test('drops blank names, keeps the first of each repeat, and keeps order', () => {
    expect(activitiesOf(['HIKING', 'camping', '', ' ', 'Hiking', 'CAMPING', 'BIKING'])).toEqual([
      'Hiking',
      'Camping',
      'Biking',
    ]);
  });

  test('an activity without a name, with a null name or a numeric name', () => {
    const rows = '[{"ActivityID":1},{"ActivityName":null},{"ActivityName":5},{"ActivityName":"HIKING"}]';
    expect(recordOf(`"ACTIVITY":${rows}`).metadata?.activities).toEqual(['5', 'Hiking']);
  });

  test.each([
    ['ACTIVITY is null', '"ACTIVITY":null'],
    ['ACTIVITY is an object', '"ACTIVITY":{}'],
    ['an ACTIVITY entry is not an object', '"ACTIVITY":["HIKING"]'],
    ['an ActivityName is an object', '"ACTIVITY":[{"ActivityName":{}}]'],
  ])('fails the run when %s', (_label, field) => {
    expect(() => recordOf(field)).toThrow();
  });

  test('a facility with no activities and no rating has no metadata', () => {
    expect(recordOf('').metadata).toBeNull();
    expect(recordOf('"ACTIVITY":[]').metadata).toBeNull();
    expect(recordOf('"ACTIVITY":[{"ActivityName":" "}]').metadata).toBeNull();
  });
});

describe('rating', () => {
  function ratingOf(aggregate: string): { average: number; count: number } | undefined {
    return recordOf('', enrichmentOf(aggregate)).metadata?.rating;
  }

  test.each([
    ['{"average_rating":4.336283185840708,"number_of_ratings":8}', { average: 4.34, count: 8 }],
    ['{"average_rating":0,"number_of_ratings":3}', { average: 0, count: 3 }],
    ['{"average_rating":5,"number_of_ratings":1}', { average: 5, count: 1 }],
    ['{"average_rating":"4.5","number_of_ratings":"8"}', { average: 4.5, count: 8 }],
    ['{"average_rating":4.5,"number_of_ratings":"+8"}', { average: 4.5, count: 8 }],
  ])('%s', (aggregate, expected) => {
    expect(ratingOf(aggregate)).toEqual(expected);
  });

  test('a tie rounds to the even hundredth, not up', () => {
    expect(ratingOf('{"average_rating":0.125,"number_of_ratings":1}')?.average).toBe(0.12);
    expect(ratingOf('{"average_rating":0.375,"number_of_ratings":1}')?.average).toBe(0.38);
    expect(ratingOf('{"average_rating":0.625,"number_of_ratings":1}')?.average).toBe(0.62);
    expect(ratingOf('{"average_rating":0.875,"number_of_ratings":1}')?.average).toBe(0.88);
  });

  test.each([
    ['a zero count', '{"average_rating":4,"number_of_ratings":0}'],
    ['a negative count', '{"average_rating":4,"number_of_ratings":-2}'],
    ['a count written with a fraction', '{"average_rating":4,"number_of_ratings":8.0}'],
    ['a count written with an exponent, which kotlinx would read but String.toIntOrNull does not', '{"average_rating":4,"number_of_ratings":1e1}'],
    ['a count with a leading space', '{"average_rating":4,"number_of_ratings":" 8"}'],
    ['a count beyond an Int', '{"average_rating":4,"number_of_ratings":2147483648}'],
    ['a missing count', '{"average_rating":4}'],
    ['a null count', '{"average_rating":4,"number_of_ratings":null}'],
    ['a boolean count', '{"average_rating":4,"number_of_ratings":true}'],
    ['a missing average', '{"number_of_ratings":8}'],
    ['a null average', '{"average_rating":null,"number_of_ratings":8}'],
    ['an unparseable average', '{"average_rating":"high","number_of_ratings":8}'],
  ])('%s gives no rating', (_label, aggregate) => {
    expect(ratingOf(aggregate)).toBeUndefined();
  });

  test('a count at the Int bound is kept', () => {
    expect(ratingOf('{"average_rating":4,"number_of_ratings":2147483647}')?.count).toBe(2147483647);
  });

  test('an object where a count belongs fails the run', () => {
    expect(() => ratingOf('{"average_rating":4,"number_of_ratings":{}}')).toThrow();
    expect(() => ratingOf('{"average_rating":[4],"number_of_ratings":8}')).toThrow();
  });

  test('a payload with no aggregate object is read flat', () => {
    expect(recordOf('', '{"facility_id":"1","average_rating":3.5,"number_of_ratings":2}').metadata?.rating).toEqual({
      average: 3.5,
      count: 2,
    });
    expect(recordOf('', '{"facility_id":"1","aggregate":null,"average_rating":3.5,"number_of_ratings":2}').metadata?.rating).toEqual({
      average: 3.5,
      count: 2,
    });
  });

  test('an aggregate object shadows the flat fields beside it', () => {
    const enrichment = '{"facility_id":"1","average_rating":1,"number_of_ratings":1,"aggregate":{"average_rating":5,"number_of_ratings":9}}';
    expect(recordOf('', enrichment).metadata?.rating).toEqual({ average: 5, count: 9 });
  });
});

describe('cell service', () => {
  function cellOf(rows: string): CarrierSignal[] {
    return recordOf('', enrichmentOf(`{"aggregate_cell_coverage_ratings":${rows}}`)).cellService ?? [];
  }

  test('maps the vendor carrier names, ignoring case and surrounding whitespace', () => {
    const rows = [' verizon ', 'AT&T', 't-mobile', 'SPRINT', 'Us Cellular']
      .map((name) => `{"carrier":"${name}","average_rating":2.5,"number_of_ratings":3}`)
      .join(',');
    expect(cellOf(`[${rows}]`).map((signal) => signal.carrier)).toEqual([
      Carrier.VERIZON,
      Carrier.ATT,
      Carrier.TMOBILE,
      Carrier.SPRINT,
      Carrier.US_CELLULAR,
    ]);
  });

  test('does not map the wire names, which are a different vocabulary', () => {
    expect(cellOf('[{"carrier":"att","average_rating":2,"number_of_ratings":3},{"carrier":"uscell","average_rating":2,"number_of_ratings":3},{"carrier":"tmobile","average_rating":2,"number_of_ratings":3}]')).toEqual([]);
  });

  test.each([
    ['a carrier outside the vocabulary', '{"carrier":"Boost Mobile","average_rating":2,"number_of_ratings":3}'],
    ['a missing carrier', '{"average_rating":2,"number_of_ratings":3}'],
    ['a null carrier', '{"carrier":null,"average_rating":2,"number_of_ratings":3}'],
    ['a missing count', '{"carrier":"Verizon","average_rating":2}'],
    ['a zero count', '{"carrier":"Verizon","average_rating":2,"number_of_ratings":0}'],
    ['a negative count', '{"carrier":"Verizon","average_rating":2,"number_of_ratings":-1}'],
    ['a fractional count', '{"carrier":"Verizon","average_rating":2,"number_of_ratings":3.0}'],
    ['a missing average', '{"carrier":"Verizon","number_of_ratings":3}'],
    ['an unparseable average', '{"carrier":"Verizon","average_rating":"n/a","number_of_ratings":3}'],
    ['an entry that is not an object', '"Verizon"'],
    ['an entry that is null', 'null'],
    ['an unreadable average behind a count that is null', '{"carrier":"Verizon","average_rating":{},"number_of_ratings":null}'],
    ['an unreadable average behind a count that is absent', '{"carrier":"Verizon","average_rating":[1]}'],
  ])('drops %s', (_label, entry) => {
    expect(cellOf(`[${entry}]`)).toEqual([]);
  });

  test('keeps a zero average and rounds half to even', () => {
    const rows = '[{"carrier":"Verizon","average_rating":0,"number_of_ratings":3},{"carrier":"Sprint","average_rating":0.125,"number_of_ratings":1}]';
    expect(cellOf(rows)).toEqual([
      { carrier: Carrier.VERIZON, average: 0, count: 3 },
      { carrier: Carrier.SPRINT, average: 0.12, count: 1 },
    ]);
  });

  test('a ratings list that is absent, or whose payload has no aggregate, is empty', () => {
    expect(recordOf('', enrichmentOf('{}')).cellService).toEqual([]);
    expect(recordOf('', '{"facility_id":"1","aggregate_cell_coverage_ratings":[{"carrier":"Sprint","average_rating":1,"number_of_ratings":1}]}').cellService).toEqual([
      { carrier: Carrier.SPRINT, average: 1, count: 1 },
    ]);
  });

  test.each([
    ['a ratings value that is not a list', '{"aggregate_cell_coverage_ratings":{}}'],
    ['a null ratings value', '{"aggregate_cell_coverage_ratings":null}'],
    ['a carrier that is an object', '{"aggregate_cell_coverage_ratings":[{"carrier":{},"average_rating":1,"number_of_ratings":1}]}'],
    ['a count that is a list', '{"aggregate_cell_coverage_ratings":[{"carrier":"Sprint","average_rating":1,"number_of_ratings":[1]}]}'],
    ['an average that is an object behind a readable count', '{"aggregate_cell_coverage_ratings":[{"carrier":"Verizon","average_rating":{},"number_of_ratings":3}]}'],
  ])('fails the run on %s', (_label, aggregate) => {
    expect(() => recordOf('', enrichmentOf(aggregate))).toThrow();
  });
});

describe('which enrichment payload belongs to which facility', () => {
  function ratingFor(facilityId: string, enrichments: string[]): number | undefined {
    const [only] = transformed([page(facility(`"FacilityID":"${facilityId}"`))], enrichments);
    return only?.ok === true ? only.record.metadata?.rating?.count : undefined;
  }

  const rated = (count: number): string => `{"average_rating":4,"number_of_ratings":${count}}`;

  test.each([
    ['a string facility_id', `{"facility_id":"7","aggregate":${rated(1)}}`],
    ['a numeric facility_id', `{"facility_id":7,"aggregate":${rated(1)}}`],
    ['a zero-padded facility_id', `{"facility_id":"007","aggregate":${rated(1)}}`],
    ['a signed facility_id', `{"facility_id":"+7","aggregate":${rated(1)}}`],
    ['the aggregate location_id when facility_id is absent', `{"aggregate":{"location_id":"7","average_rating":4,"number_of_ratings":1}}`],
    ['the aggregate location_id when facility_id is null', `{"facility_id":null,"aggregate":{"location_id":7,"average_rating":4,"number_of_ratings":1}}`],
    ['the aggregate location_id when facility_id is not a number', `{"facility_id":"seven","aggregate":{"location_id":"7","average_rating":4,"number_of_ratings":1}}`],
    ['the aggregate location_id when facility_id has a fraction', `{"facility_id":"7.0","aggregate":{"location_id":"7","average_rating":4,"number_of_ratings":1}}`],
    ['the aggregate location_id when facility_id has an exponent', `{"facility_id":"7e0","aggregate":{"location_id":"7","average_rating":4,"number_of_ratings":1}}`],
    ['the aggregate location_id when facility_id has a space', `{"facility_id":" 7","aggregate":{"location_id":"7","average_rating":4,"number_of_ratings":1}}`],
  ])('matches %s', (_label, enrichment) => {
    expect(ratingFor('7', [enrichment])).toBe(1);
  });

  test.each([
    ['no id at all', `{"aggregate":${rated(1)}}`],
    ['a null id and a null location', `{"facility_id":null,"aggregate":{"location_id":null,"average_rating":4,"number_of_ratings":1}}`],
    ['a payload that is a list', `[{"facility_id":"7"}]`],
    ['a payload that is a string', `"7"`],
    ['an id for a different facility', `{"facility_id":"8","aggregate":${rated(1)}}`],
    ['an id beyond a Long', `{"facility_id":"9223372036854775808","aggregate":${rated(1)}}`],
    ['a location_id when the aggregate is not an object', `{"aggregate":"7","location_id":"7"}`],
  ])('ignores %s', (_label, enrichment) => {
    expect(ratingFor('7', [enrichment])).toBeUndefined();
  });

  test('facility_id wins over a different location_id', () => {
    const enrichment = `{"facility_id":"7","aggregate":{"location_id":"8","average_rating":4,"number_of_ratings":4}}`;
    expect(ratingFor('7', [enrichment])).toBe(4);
    expect(ratingFor('8', [enrichment])).toBeUndefined();
  });

  test('of two payloads for one facility the later one wins', () => {
    const first = `{"facility_id":"7","aggregate":${rated(1)}}`;
    const second = `{"facility_id":"7","aggregate":${rated(2)}}`;
    expect(ratingFor('7', [first, second])).toBe(2);
    expect(ratingFor('7', [second, first])).toBe(1);
  });

  test('a facility_id that is an object fails the run', () => {
    expect(() => ratingFor('7', [`{"facility_id":{},"aggregate":${rated(1)}}`])).toThrow();
    expect(() => ratingFor('7', [`{"facility_id":["7"],"aggregate":${rated(1)}}`])).toThrow();
  });

  test('a location_id that is an object fails the run only when facility_id does not decide', () => {
    expect(() => ratingFor('7', [`{"aggregate":{"location_id":{}}}`])).toThrow();
    expect(ratingFor('7', [`{"facility_id":"7","aggregate":{"location_id":{},"average_rating":4,"number_of_ratings":1}}`])).toBe(1);
  });

  test('enrichment is optional, and an input that is not declared is not read', () => {
    expect(transformed([page(facility())], null)).toHaveLength(1);
  });
});

describe('reservable', () => {
  function reservableOf(valueJson: string): boolean {
    return recordOf(`"Reservable":${valueJson}`).bookingProvider === BookingProvider.RECGOV;
  }

  test.each([
    ['true', true],
    ['"true"', true],
    ['"TRUE"', true],
    ['"True"', true],
    ['false', false],
    ['"false"', false],
    ['"yes"', false],
    ['"true "', false],
    ['1', false],
    ['"1"', false],
    ['null', false],
    ['""', false],
  ])('Reservable %s is %s', (valueJson, expected) => {
    expect(reservableOf(valueJson)).toBe(expected);
  });

  test('a facility with no Reservable key is not reservable', () => {
    expect(recordOf('').bookingProvider).toBeNull();
  });

  test.each(['{}', '[]', '[true]'])('Reservable %s fails the run', (valueJson) => {
    expect(() => reservableOf(valueJson)).toThrow();
  });

  test('a reservable facility carries its id as the booking ref, and a non-reservable one has none', () => {
    const reservable = recordOf('"Reservable":true');
    expect(reservable.bookingProviderRef).toBe('1');
    const plain = recordOf('"Reservable":false');
    expect(plain.bookingProvider).toBeNull();
    expect(plain.bookingProviderRef).toBeNull();
  });
});

describe('photo', () => {
  function photoOf(media: string): string | undefined {
    return recordOf(`"MEDIA":${media}`).photos?.[0]?.url;
  }

  test('prefers the primary image, then the preview, then the first image with a link', () => {
    expect(photoOf('[{"URL":"a"},{"URL":"p","IsPreview":true},{"URL":"m","IsPrimary":true}]')).toBe('m');
    expect(photoOf('[{"URL":"a"},{"URL":"p","IsPreview":true}]')).toBe('p');
    expect(photoOf('[{"URL":"a"},{"URL":"b"}]')).toBe('a');
  });

  test('falls through to the next rule when the winner has no usable link', () => {
    expect(photoOf('[{"URL":"a"},{"IsPrimary":true},{"URL":"p","IsPreview":true}]')).toBe('p');
    expect(photoOf('[{"URL":"a"},{"URL":" ","IsPrimary":true},{"URL":"p","IsPreview":true}]')).toBe('p');
    expect(photoOf('[{"IsPrimary":true},{"IsPreview":true},{"URL":"a"}]')).toBe('a');
    expect(photoOf('[{"IsPrimary":true},{"IsPreview":true}]')).toBeUndefined();
  });

  test('a link is kept as written, untrimmed', () => {
    expect(photoOf('[{"URL":" https://x/y "}]')).toBe(' https://x/y ');
  });

  test('keeps the first of two primaries', () => {
    expect(photoOf('[{"URL":"one","IsPrimary":true},{"URL":"two","IsPrimary":true}]')).toBe('one');
  });

  test('reads a flag spelled as text by its content, case-insensitively', () => {
    expect(photoOf('[{"URL":"a"},{"URL":"m","IsPrimary":"TRUE"}]')).toBe('m');
    expect(photoOf('[{"URL":"a"},{"URL":"m","IsPrimary":"yes"}]')).toBe('a');
    expect(photoOf('[{"URL":"a"},{"URL":"m","IsPrimary":1}]')).toBe('a');
  });

  test('excludes anything whose MediaType is not Image, but keeps an entry without one', () => {
    expect(photoOf('[{"URL":"v","MediaType":"Video","IsPrimary":true},{"URL":"i","MediaType":"Image"}]')).toBe('i');
    expect(photoOf('[{"URL":"i","MediaType":"IMAGE"}]')).toBe('i');
    expect(photoOf('[{"URL":"i","MediaType":"image"}]')).toBe('i');
    expect(photoOf('[{"URL":"n"}]')).toBe('n');
    expect(photoOf('[{"URL":"n","MediaType":null}]')).toBe('n');
    expect(photoOf('[{"URL":"v","MediaType":"Video"}]')).toBeUndefined();
    expect(photoOf('[{"URL":"v","MediaType":5}]')).toBeUndefined();
    expect(photoOf('[{"URL":"v","MediaType":"Images"}]')).toBeUndefined();
  });

  test('skips entries that are not objects', () => {
    expect(photoOf('["x",null,1,[],{"URL":"a"}]')).toBe('a');
  });

  test('no media is no photo', () => {
    expect(recordOf('').photos).toEqual([]);
    expect(recordOf('"MEDIA":[]').photos).toEqual([]);
  });

  test('MEDIA that is not a list fails the run, null included', () => {
    expect(() => recordOf('"MEDIA":null')).toThrow();
    expect(() => recordOf('"MEDIA":{}')).toThrow();
  });

  test('a MediaType that is an object fails the run', () => {
    expect(() => photoOf('[{"URL":"a","MediaType":{}}]')).toThrow();
  });

  test('a flag that is an object fails the run only when the search reaches it', () => {
    expect(() => photoOf('[{"URL":"a","IsPrimary":{}}]')).toThrow();
    expect(photoOf('[{"URL":"a","IsPrimary":true},{"URL":"b","IsPrimary":{}}]')).toBe('a');
  });
});

describe('the information link', () => {
  test('an upstream reservation URL wins, even for a reservable facility', () => {
    const record = recordOf('"Reservable":true,"FacilityReservationURL":"https://example.test/book"');
    expect(record.reservationUrl).toBe('https://example.test/book');
    expect(record.sourceUrl).toBe('https://example.test/book');
    expect(record.links).toEqual([{ url: 'https://example.test/book' }]);
  });

  test('a blank upstream URL falls back, and a non-blank one is kept untrimmed', () => {
    expect(recordOf('"Reservable":true,"FacilityReservationURL":" "').reservationUrl).toBe(
      'https://www.recreation.gov/camping/campgrounds/1',
    );
    expect(recordOf('"Reservable":true,"FacilityReservationURL":" x "').reservationUrl).toBe(' x ');
  });

  test('a non-reservable facility uses its official link, then any link, then none', () => {
    const official = '"LinkType":"Official Web Site"';
    expect(recordOf(`"LINK":[{"URL":"a"},{"URL":"o",${official}}]`).reservationUrl).toBe('o');
    expect(recordOf('"LINK":[{"URL":"a"},{"URL":"b"}]').reservationUrl).toBe('a');
    expect(recordOf('"LINK":[{"URL":" ","LinkType":"Official Web Site"},{"URL":"b"}]').reservationUrl).toBe('b');
    expect(recordOf('"LINK":[{"LinkType":"Official Web Site"},{"URL":"b"}]').reservationUrl).toBe('b');
    expect(recordOf('"LINK":[{"URL":"a","LinkType":"OFFICIAL WEB SITE"},{"URL":"b","LinkType":"Official Web Site"}]').reservationUrl).toBe('a');
    expect(recordOf('"LINK":[{"URL":"a","LinkType":"Official Website"},{"URL":"b","LinkType":"Other"}]').reservationUrl).toBe('a');
    expect(recordOf('"LINK":["x",null,{"URL":"a"}]').reservationUrl).toBe('a');
  });

  test('no link at all leaves every url field empty', () => {
    for (const extra of ['', '"LINK":[]', '"LINK":[{"Title":"t"}]']) {
      const record = recordOf(extra);
      expect(record.reservationUrl).toBeNull();
      expect(record.sourceUrl).toBeNull();
      expect(record.links).toEqual([]);
    }
  });

  test('a reservable facility never reads its LINK list', () => {
    expect(recordOf('"Reservable":true,"LINK":"not a list"').reservationUrl).toBe(
      'https://www.recreation.gov/camping/campgrounds/1',
    );
  });

  test('LINK that is not a list fails the run for a non-reservable facility', () => {
    expect(() => recordOf('"LINK":null')).toThrow();
    expect(() => recordOf('"LINK":{}')).toThrow();
  });
});

describe('location, address and region', () => {
  function countryOf(code: string | null): string | undefined {
    const field = code === null ? '"FACILITYADDRESS":[{"AddressStateCode":"UT"}]' : `"FACILITYADDRESS":[{"AddressCountryCode":${JSON.stringify(code)}}]`;
    return recordOf(field).location.country;
  }

  test.each([
    ['USA', 'US'],
    ['US', 'US'],
    ['usa', 'US'],
    [' Usa ', 'US'],
    [' de ', 'DE'],
    [' ca', 'CA'],
    ['can ', 'CA'],
    [' mx', 'MX'],
    ['CAN', 'CA'],
    ['ca', 'CA'],
    ['de', 'DE'],
    ['MX', 'MX'],
    ['United States', 'US'],
    ['UNITED STATES', 'US'],
    ['USA (MAILING ADDRESS ONLY)', 'US'],
    ['', 'US'],
    ['   ', 'US'],
    ['1', 'US'],
    ['DEU', 'US'],
    [SHARP_S, 'SS'],
  ])('country code %j gives %j', (code, expected) => {
    expect(countryOf(code)).toBe(expected);
  });

  test('a facility with no address defaults to US', () => {
    expect(countryOf(null)).toBe('US');
    expect(recordOf('').location.country).toBe('US');
    expect(recordOf('"FACILITYADDRESS":[]').location.country).toBe('US');
  });

  test('the address keeps only a recognised country, and drops fields that are blank', () => {
    const address = recordOf(
      '"FACILITYADDRESS":[{"FacilityStreetAddress1":" 125 South 600 West ","City":"Price","AddressStateCode":"UT","PostalCode":"84501","AddressCountryCode":"United States"}]',
    ).location.address;
    expect(address).toEqual({ street: ' 125 South 600 West ', city: 'Price', state: 'UT', postcode: '84501' });
  });

  test('an address of only blank fields is no address, but still sets the country default', () => {
    const record = recordOf('"FACILITYADDRESS":[{"FacilityStreetAddress1":" ","City":"","AddressStateCode":"\\t","PostalCode":null}]');
    expect(record.location.address).toBeUndefined();
    expect(record.location.region).toBeUndefined();
    expect(record.location.country).toBe('US');
  });

  test('only the first address counts, even if it is empty and a later one is not', () => {
    const record = recordOf('"FACILITYADDRESS":[{},{"City":"Price","AddressStateCode":"UT","AddressCountryCode":"CAN"}]');
    expect(record.location.address).toBeUndefined();
    expect(record.location.region).toBeUndefined();
    expect(record.location.country).toBe('US');
  });

  test('the region is the first address state code, kept as written when not blank', () => {
    expect(recordOf('"FACILITYADDRESS":[{"AddressStateCode":"UT"}]').location.region).toBe('UT');
    expect(recordOf('"FACILITYADDRESS":[{"AddressStateCode":" ut "}]').location.region).toBe(' ut ');
  });

  test('carries the coordinates from the typed facility', () => {
    const record = recordOf('"FacilityLatitude":"38.5","FacilityLongitude":-119.25');
    expect(record.latitude).toBe(38.5);
    expect(record.longitude).toBe(-119.25);
    expect(record.location.latitude).toBe(38.5);
    expect(record.location.longitude).toBe(-119.25);
  });
});

describe('rawFacilityId, the raw pass reading a FacilityID as String.toLong', () => {
  test.each([
    ['12', '12'],
    ['+5', '5'],
    ['-5', '-5'],
    ['007', '7'],
    [String.fromCodePoint(0x663), '3'],
    [12, '12'],
    [-7, '-7'],
    ['9223372036854775807', '9223372036854775807'],
  ])('%j reads as %j', (value, expected) => {
    expect(rawFacilityId(value)).toBe(expected);
  });

  test.each([
    ['text with a double quote inside', '1"2'],
    ['text with a backslash inside', '1\\2'],
    ['text with a quote at either end', '"12"'],
    ['text with a quote at the front', '"12'],
    ['text with a quote at the back', '12"'],
    ['text with a control character', '1\u00012'],
    ['text with a tab', '1\t2'],
    ['text with a no-break space', `12${NO_BREAK_SPACE}`],
    ['a leading space', ' 12'],
    ['an exponent', '1e3'],
    ['a fraction', '12.0'],
    ['an empty string', ''],
    ['a sign alone', '+'],
    ['a value beyond a Long', '9223372036854775808'],
    ['a boolean', true],
    ['a null', null],
    ['an absent value', undefined],
    ['an object', { FacilityID: 12 }],
    ['a list', ['12']],
  ])('%s reads as nothing', (_label, value) => {
    expect(rawFacilityId(value)).toBeNull();
  });
});

describe('rejected facilities', () => {
  function badOf(extra: string): TransformResult<CampgroundUpsertCandidate> {
    const [only] = transformed([page(facility(extra))]);
    if (only === undefined) throw new Error('expected a result');
    return only;
  }

  test.each([
    ['a null name', '"FacilityName":null'],
    ['a blank name', '"FacilityName":" \\t"'],
    ['no latitude', '"FacilityLatitude":null'],
    ['no longitude', '"FacilityLongitude":null'],
    ['the 0,0 placeholder', '"FacilityLatitude":0,"FacilityLongitude":0.0'],
  ])('%s is reported against its FacilityID', (_label, extra) => {
    expect(badOf(extra)).toEqual({ ok: false, sourceId: '1', errors: ['missing name or valid geometry'] });
  });

  test('a single zero coordinate is geometry', () => {
    expect(badOf('"FacilityLatitude":0').ok).toBe(true);
    expect(badOf('"FacilityLongitude":0').ok).toBe(true);
  });

  test('a facility with no name is rejected before its optional fields are read', () => {
    expect(badOf('"FacilityName":null,"MEDIA":null,"ACTIVITY":null').ok).toBe(false);
  });

  test('a rejected facility still reads Reservable and the agency, so a bad Reservable fails the run', () => {
    expect(() => badOf('"FacilityName":null,"Reservable":{}')).toThrow();
  });

  test('the id is reported in canonical form', () => {
    const [only] = transformed([page(facility('"FacilityID":"0042","FacilityName":null'))]);
    expect(only).toEqual({ ok: false, sourceId: '42', errors: ['missing name or valid geometry'] });
  });
});

describe('the remaining promoted fields', () => {
  test('parent name is the first rec area, trimmed, and null otherwise', () => {
    expect(recordOf('"RECAREA":[{"RecAreaName":" Yosemite "},{"RecAreaName":"Other"}]').parentName).toBe('Yosemite');
    expect(recordOf('"RECAREA":[{"RecAreaName":" "},{"RecAreaName":"Other"}]').parentName).toBeNull();
    expect(recordOf('"RECAREA":[{"RecAreaID":1}]').parentName).toBeNull();
    expect(recordOf('"RECAREA":[]').parentName).toBeNull();
    expect(recordOf('"RECAREA":null').parentName).toBeNull();
    expect(recordOf('"RECAREA":{"RecAreaName":"x"}').parentName).toBeNull();
    expect(recordOf('"RECAREA":["x"]').parentName).toBeNull();
    expect(recordOf('"RECAREA":["x",{"RecAreaName":"Later"}]').parentName).toBeNull();
    expect(recordOf('"RECAREA":[null,{"RecAreaName":"Later"}]').parentName).toBeNull();
    expect(recordOf('').parentName).toBeNull();
    expect(recordOf('"RECAREA":[{"RecAreaName":7}]').parentName).toBe('7');
  });

  test('a rec area name that is an object fails the run', () => {
    expect(() => recordOf('"RECAREA":[{"RecAreaName":{}}]')).toThrow();
  });

  test('description is trimmed, null when blank, and read by content when not text', () => {
    expect(recordOf('"FacilityDescription":"  <p>Hi</p> \\n"').mediumDescription).toBe('<p>Hi</p>');
    expect(recordOf('"FacilityDescription":" \\t"').mediumDescription).toBeNull();
    expect(recordOf('"FacilityDescription":null').mediumDescription).toBeNull();
    expect(recordOf('"FacilityDescription":12').mediumDescription).toBe('12');
    expect(recordOf('').mediumDescription).toBeNull();
    expect(() => recordOf('"FacilityDescription":{}')).toThrow();
  });

  test('contact is the phone when it is not blank, kept as written', () => {
    expect(recordOf('"FacilityPhone":" 555-0100 "').contact).toEqual({ phone: ' 555-0100 ' });
    expect(recordOf('"FacilityPhone":" "').contact).toBeNull();
    expect(recordOf('"FacilityPhone":null').contact).toBeNull();
    expect(recordOf('').contact).toBeNull();
  });

  test('kind is the registry subcategory', () => {
    expect(recordOf('').kind).toBe('federal');
    expect(recordOf('', null, ctxWithAgency(undefined)).kind).toBe('federal');
  });

  test('the name is kept untrimmed once it is not blank', () => {
    expect(recordOf('"FacilityName":" Upper Pines "').name).toBe(' Upper Pines ');
  });

  test('the source payload is the raw facility, keeping every vendor spelling', () => {
    const record = recordOf('"Extra":{"n":1.10,"big":100000000000000000000,"z":25.0},"Unpromoted":[1,2]');
    const payload = record.sourcePayload;
    expect(payload).toBeDefined();
    expect(payload).toContain('"Extra":{"n":1.10,"big":100000000000000000000,"z":25.0}');
    expect(payload).toContain('"Unpromoted":[1,2]');
    expect(payload).toContain('"FacilityID":"1"');
  });
});

describe('parse', () => {
  test('refuses a bundle with no pages, naming the slug', () => {
    const bundle = new InputBundle(new Map([[RIDB_INPUT, []]]));
    expect(() => parsed(bundle)).toThrow(`${SLUG}: no pages`);
  });

  test('refuses a bundle that does not declare the RIDB input', () => {
    expect(() => parsed(new InputBundle(new Map()))).toThrow(RIDB_INPUT);
  });

  test('a page that decodes to no facilities is one bad result', () => {
    expect(parsed(bundleOf(['{"RECDATA":[]}']))).toEqual([{ ok: false, errors: [`${SLUG}: no rows in payload`] }]);
    expect(parsed(bundleOf(['{}', '{"RECDATA":[]}']))).toEqual([{ ok: false, errors: [`${SLUG}: no rows in payload`] }]);
  });

  test('concatenates every page in order into one dto', () => {
    const [result] = parsed(bundleOf([page(facility('"FacilityID":"1"')), page(facility('"FacilityID":"2"'), facility('"FacilityID":"3"'))]));
    expect(result?.ok).toBe(true);
    if (result?.ok !== true) return;
    expect(result.dto.rows.map((row) => row.facilityId)).toEqual(['1', '2', '3']);
  });

  test('an undecodable page fails the run', () => {
    expect(() => parsed(bundleOf(['[]']))).toThrow();
    expect(() => parsed(bundleOf([page(facility()), '{"RECDATA":[{"FacilityName":"x"}]}']))).toThrow('FacilityID');
  });

  test('indexes the raw facility by id, the last page winning a repeated id', () => {
    const [result] = parsed(
      bundleOf([page(facility('"FacilityID":"5","Marker":"first"')), page(facility('"FacilityID":"5","Marker":"last"'))]),
    );
    if (result?.ok !== true) throw new Error('expected an ok dto');
    expect(result.dto.rawById.size).toBe(1);
    expect(result.dto.rawById.get('5')?.Marker).toBe('last');
    expect(result.dto.rows).toHaveLength(2);
  });

  test('keys the raw facility by the Long its id spells, quoted or not, zero-padded or not', () => {
    const [result] = parsed(bundleOf([page(facility('"FacilityID":"007"'), facility('"FacilityID":8'))]));
    if (result?.ok !== true) throw new Error('expected an ok dto');
    expect([...result.dto.rawById.keys()]).toEqual(['7', '8']);
  });

  test('a facility whose id the raw pass cannot read is typed but has no raw object', () => {
    const [result] = parsed(bundleOf([page(facility('"FacilityID":1e3'), facility('"FacilityID":"2"'))]));
    if (result?.ok !== true) throw new Error('expected an ok dto');
    expect(result.dto.rows.map((row) => row.facilityId)).toEqual(['1000', '2']);
    expect([...result.dto.rawById.keys()]).toEqual(['2']);
  });

  test('a facility with no raw object still transforms, with no raw-derived fields', () => {
    const [only] = transformed([page(facility('"FacilityID":1e3,"Reservable":true,"ORGANIZATION":[{"OrgName":"Agency"}]'))]);
    if (only?.ok !== true) throw new Error('expected an ok record');
    expect(only.record.dataProviderRef.serialize()).toBe('1000');
    expect(only.record.bookingProvider).toBeNull();
    expect(only.record.management).toBeNull();
    expect(only.record.sourcePayload).toBeNull();
    expect(only.record.reservationUrl).toBeNull();
  });

  test('a facility the page lists twice yields two records', () => {
    expect(transformed([page(facility(), facility())])).toHaveLength(2);
  });
});
