import { BookingProvider, CampsiteKind, DataProvider, DataProviderRef } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import {
  PARENT_CAMPGROUND_REF_PREFIX,
  PARENT_CONTRACT_KEY,
  PARENT_PARK_KEY,
  parentCampgroundVendor,
  ReserveAmericaSitesEtl,
  type ReserveAmericaSitesDto,
} from './sites.js';

const AB_SLUG = 'reserveamerica-ab-campsites';
const NY_SLUG = 'reserveamerica-ny-campsites';
const AB_INPUT = 'reserveamerica-campsites-abpp';
const NY_INPUT = 'reserveamerica-campsites-ny';
const HTTP_OK = 200;
const FETCHED_AT = '2026-06-17T00:00:00Z';
const UNIT_SEPARATOR = String.fromCodePoint(0x1f);
const CAPTURE_ROOT = decodeURIComponent(new URL('../../../../../data/raw/', import.meta.url).pathname);
const CAPTURE_DIR = '2026-07-05T20-28-53Z';
const KOTLIN_WHITESPACE_CODE_POINTS = [
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa0, 0x1680, 0x2000, 0x2001, 0x2005, 0x2007, 0x200a,
  0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
];
const NOT_KOTLIN_WHITESPACE_CODE_POINTS = [0x00, 0x01, 0x85, 0x180e, 0x200b, 0x200c, 0x2060, 0xfeff];

function envelopeOf(payload: unknown, part: string): Envelope {
  return {
    fetcher: 'f',
    fetcherVersion: '1',
    fetchedAt: FETCHED_AT,
    request: { url: 'https://example.test/', method: 'GET', headers: {} },
    response: { status: HTTP_OK, headers: {} },
    pollerRunId: null,
    payload,
    part,
  };
}

function bundleOf(input: string, payloads: unknown[]): InputBundle {
  return new InputBundle(new Map([[input, payloads.map((payload, index) => envelopeOf(payload, `campsite-1-${index}`))]]));
}

function row(siteId: string, parkId: string, label: string): string {
  return `<div class='siteListLabel'><a href="/x/r/campsiteDetails.do?contractCode=NY&amp;siteId=${siteId}&amp;parkId=${parkId}">${label}</a></div>`;
}

function parsedOf(slug: string, contract: string, input: string, payloads: unknown[]): ParseResult<ReserveAmericaSitesDto>[] {
  return [...new ReserveAmericaSitesEtl(slug, contract).parse(bundleOf(input, payloads))];
}

function sitesOf(slug: string, contract: string, input: string, payloads: unknown[]): CampsiteUpsertCandidate[] {
  const etl = new ReserveAmericaSitesEtl(slug, contract);
  const records: CampsiteUpsertCandidate[] = [];
  for (const parsed of etl.parse(bundleOf(input, payloads))) {
    if (!parsed.ok) throw new Error(parsed.errors.join());
    for (const result of etl.transform(parsed.dto)) records.push(okRecord(result));
  }
  return records;
}

function okRecord(result: TransformResult<CampsiteUpsertCandidate>): CampsiteUpsertCandidate {
  if (!result.ok) throw new Error(result.errors.join());
  return result.record;
}

function nySites(...payloads: string[]): CampsiteUpsertCandidate[] {
  return sitesOf(NY_SLUG, 'NY', NY_INPUT, payloads);
}

function payloadOf(record: CampsiteUpsertCandidate): Record<string, unknown> {
  return JSON.parse(record.sourcePayload as string) as Record<string, unknown>;
}

describe('transform', () => {
  test('emits one campsite per site, bound to the availability adapter by the scraped siteId', () => {
    const [first, second] = nySites(row('253478', '489', '039') + row('253497', '489', '056'));

    expect(first?.dataProviderRef).toEqual(DataProviderRef.reserveAmerica('253478'));
    expect(first?.bookingProvider).toBe(BookingProvider.RESERVEAMERICA);
    expect(first?.bookingProviderRef).toBe('253478');
    expect(first?.parentDataProviderRef).toEqual(DataProviderRef.reserveAmerica('ra-489'));
    expect(first?.name).toBe('039');
    expect(second?.dataProviderRef.serialize()).toBe('253497');
    expect(second?.name).toBe('056');
    expect(first?.kind).toBe(CampsiteKind.OTHER);
    expect(first?.loopName ?? null).toBeNull();
  });

  test('carries the site, its name and the parent it came from in the payload, in that order', () => {
    const [only] = nySites(row('253478', '489', '039'));

    expect(only?.sourcePayload).toBe(
      '{"site_id":"253478","name":"039","_parent_contract_code":"NY","_parent_park_id":"489"}',
    );
    expect(Object.keys(payloadOf(only as CampsiteUpsertCandidate))).toEqual([
      'site_id',
      'name',
      PARENT_CONTRACT_KEY,
      PARENT_PARK_KEY,
    ]);
  });

  test('the Alberta tenant parents its sites to the Alberta campground refs under its own contract code', () => {
    const [only] = sitesOf(AB_SLUG, 'ABPP', AB_INPUT, [row('9', '330101', 'A1')]);

    expect(only?.parentDataProviderRef?.serialize()).toBe('ra-330101');
    expect(payloadOf(only as CampsiteUpsertCandidate)[PARENT_CONTRACT_KEY]).toBe('ABPP');
  });

  test('keeps the first row of a repeated siteId, across pages as well as within one', () => {
    const records = nySites(row('1', '10', 'first') + row('1', '11', 'again') + row('2', '10', 'other'), row('1', '12', 'later'));

    expect(records.map((record) => [record.dataProviderRef.serialize(), record.name, payloadOf(record)[PARENT_PARK_KEY]])).toEqual([
      ['1', 'first', '10'],
      ['2', 'other', '10'],
    ]);
  });

  test('reads the roster of every page of the sole input, in order', () => {
    const records = nySites(row('1', '10', 'a'), '<p>no rows</p>', row('2', '10', 'b'));

    expect(records.map((record) => record.name)).toEqual(['a', 'b']);
  });

  test('trims a label by Kotlin whitespace and names a row with no label after its siteId', () => {
    const records = nySites(
      row('1', '10', `${UNIT_SEPARATOR} 039 `) + row('2', '10', '') + `<div class='siteListLabel'><a href="x?siteId=3&amp;parkId=10">`,
    );

    expect(records.map((record) => record.name)).toEqual(['039', '2', '3']);
  });

  test('keeps ids and names as text, so a long id and leading zeros survive', () => {
    const [only] = nySites(row('00012345678901234567890', '007', '0012'));

    expect(only?.dataProviderRef.serialize()).toBe('00012345678901234567890');
    expect(only?.parentDataProviderRef?.serialize()).toBe('ra-007');
    expect(only?.name).toBe('0012');
  });
});

describe('real captures', () => {
  function captured(tenant: string, file: string): unknown {
    return parseEnvelope(`${CAPTURE_ROOT}reserveamerica-campsites-${tenant}/${CAPTURE_DIR}/${file}`).payload;
  }

  test.each([
    ['campsite-330145-0.json', '330145', '35149', 'Alymer Group'],
    ['campsite-330199-0.json', '330199', '35177', 'Snow Creek Group'],
  ])('the Alberta page %s', (file, parkId, siteId, name) => {
    const [only, ...rest] = sitesOf(AB_SLUG, 'ABPP', AB_INPUT, [captured('abpp', file)]);

    expect(rest).toEqual([]);
    expect(only?.dataProviderRef.serialize()).toBe(siteId);
    expect(only?.name).toBe(name);
    expect(only?.parentDataProviderRef?.serialize()).toBe(`ra-${parkId}`);
  });

  test('the New York page campsite-3485-0.json', () => {
    const [only, ...rest] = sitesOf(NY_SLUG, 'NY', NY_INPUT, [captured('ny', 'campsite-3485-0.json')]);

    expect(rest).toEqual([]);
    expect(only?.dataProviderRef.serialize()).toBe('266466');
    expect(only?.name).toBe('Cottage');
    expect(only?.parentDataProviderRef?.serialize()).toBe('ra-3485');
  });
});

describe('Kotlin whitespace at the edges of a label', () => {
  const codePointLabel = (codePoint: number): string => `U+${codePoint.toString(16).toUpperCase().padStart(4, '0')}`;

  test.each(KOTLIN_WHITESPACE_CODE_POINTS.map((codePoint) => [codePointLabel(codePoint), String.fromCodePoint(codePoint)]))(
    '%s is trimmed, and a label of nothing else is named by its siteId',
    (_label, edge) => {
      const [padded, blank] = nySites(row('1', '2', `${edge}039${edge}`) + row('3', '2', edge));

      expect(padded?.name).toBe('039');
      expect(blank?.name).toBe('3');
    },
  );

  test.each(NOT_KOTLIN_WHITESPACE_CODE_POINTS.map((codePoint) => [codePointLabel(codePoint), String.fromCodePoint(codePoint)]))(
    '%s is not trimmed, and a label of nothing else keeps it',
    (_label, edge) => {
      const [padded, blank] = nySites(row('1', '2', `${edge}039${edge}`) + row('3', '2', edge));

      expect(padded?.name).toBe(`${edge}039${edge}`);
      expect(blank?.name).toBe(edge);
    },
  );
});

describe('parse', () => {
  test('is a bad parse naming the slug when no row yields a site', () => {
    expect(parsedOf(NY_SLUG, 'NY', NY_INPUT, ['<p>nothing</p>', "<div class='siteListLabel'><a href='x'>y</a></div>"])).toEqual([
      { ok: false, errors: [`${NY_SLUG}: no ReserveAmerica campsite rows parsed`] },
    ]);
  });

  test('is a bad parse for an input of one empty page', () => {
    expect(parsedOf(NY_SLUG, 'NY', NY_INPUT, [''])[0]?.ok).toBe(false);
  });

  test('refuses an input with more than one declared source', () => {
    const bundle = new InputBundle(
      new Map([
        [NY_INPUT, [envelopeOf(row('1', '2', 'a'), 'campsite-1-0')]],
        ['other', [envelopeOf(row('3', '2', 'b'), 'campsite-1-0')]],
      ]),
    );

    expect(() => [...new ReserveAmericaSitesEtl(NY_SLUG, 'NY').parse(bundle)]).toThrow();
  });

  test('reads a JSON primitive payload as its text and throws on a container', () => {
    expect(parsedOf(NY_SLUG, 'NY', NY_INPUT, [5, null, true])[0]?.ok).toBe(false);
    expect(() => parsedOf(NY_SLUG, 'NY', NY_INPUT, [{}])).toThrow();
    expect(() => parsedOf(NY_SLUG, 'NY', NY_INPUT, [row('1', '2', 'a'), []])).toThrow();
  });

  test('an input with no envelopes is a bad parse, not a thrown refusal, since there is nothing to concatenate', () => {
    const results = parsedOf(NY_SLUG, 'NY', NY_INPUT, []);

    expect(results).toEqual([{ ok: false, errors: [`${NY_SLUG}: no ReserveAmerica campsite rows parsed`] }]);
  });
});

describe('parentCampgroundVendor', () => {
  test.each([
    ['ABPP', DataProvider.RESERVEAMERICA],
    ['NY', DataProvider.RESERVEAMERICA],
    ['abpp', DataProvider.RESERVEAMERICA],
    ['Ny', DataProvider.RESERVEAMERICA],
    ['CA', null],
    ['', null],
    [' NY', null],
    ['NY ', null],
  ])('contract %j', (contract, expected) => {
    expect(parentCampgroundVendor(contract)).toBe(expected);
  });

  test('a campsite of an unknown contract has no parent but is otherwise unchanged', () => {
    const dto: ReserveAmericaSitesDto = { sites: [{ parkId: '5', siteId: '6', name: 'x' }] };
    const [only] = [...new ReserveAmericaSitesEtl('variant', 'ZZ').transform(dto)].map(okRecord);

    expect(only?.parentDataProviderRef).toBeNull();
    expect(only?.dataProviderRef.serialize()).toBe('6');
    expect(only?.sourcePayload).toBe('{"site_id":"6","name":"x","_parent_contract_code":"ZZ","_parent_park_id":"5"}');
  });

  test('a lower-case contract still finds its parent and is written to the payload as given', () => {
    const dto: ReserveAmericaSitesDto = { sites: [{ parkId: '5', siteId: '6', name: 'x' }] };
    const [only] = [...new ReserveAmericaSitesEtl('variant', 'ny').transform(dto)].map(okRecord);

    expect(only?.parentDataProviderRef?.serialize()).toBe(`${PARENT_CAMPGROUND_REF_PREFIX}5`);
    expect(payloadOf(only as CampsiteUpsertCandidate)[PARENT_CONTRACT_KEY]).toBe('ny');
  });
});
