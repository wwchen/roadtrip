import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { adapterRegistry } from '../../etl/adapter-registry.js';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { loadPoiRegistry, type EtlEntry } from '../../registry/poi-registry.js';
import { BcParksCampgroundsEtl } from './campgrounds.js';
import type { BcParksCampgroundsDto } from './dto.js';

const TEMP_DIR_PREFIX = 'bcparks-campgrounds-';
const HTTP_OK = 200;
const RAW_DIR = '/unused';
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const SLUG = 'aspira-bc-campgrounds';
const MAPS = 'aspira-maps-bc';
const STRAPI = 'bcparks-strapi';
const INVENTORY = 'aspira-inventory-bc';
const DICTIONARIES = 'aspira-dictionaries-bc';
const BOOKING_URL = 'https://camping.bcparks.ca/';
const WIDE_ID = '9007199254740993';
const CAMPSITE_CATEGORY = 1;
const PARKING_CATEGORY = 2;

const REGISTRY = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
const CTX = TransformCtx.load(RAW_DIR, REGISTRY);

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

function entryOf(): EtlEntry {
  const entry = REGISTRY.poiData.flatMap((row) => row.etls).find((etl) => etl.slug === SLUG);
  if (entry === undefined) throw new Error(`no etl ${SLUG}`);
  return entry;
}

function etlOf(): BcParksCampgroundsEtl {
  const entry = entryOf();
  const factory = adapterRegistry[entry.adapter];
  if (factory === undefined) throw new Error(`no adapter ${entry.adapter}`);
  return factory(entry).etl as BcParksCampgroundsEtl;
}

type Inputs = Record<string, string[]>;

function bundleOf(inputs: Inputs): InputBundle {
  return new InputBundle(new Map(Object.entries(inputs).map(([slug, payloads]) => [slug, payloads.map(envelopeOf)])));
}

function parsed(inputs: Inputs): ParseResult<BcParksCampgroundsDto>[] {
  return [...etlOf().parse(bundleOf(inputs))];
}

function transformed(inputs: Inputs): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = etlOf();
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(inputs))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto, CTX));
  }
  return results;
}

function recordsOf(inputs: Inputs): CampgroundUpsertCandidate[] {
  return transformed(inputs).map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function title(text: string): string {
  return `[{"cultureName":"en-US","title":${JSON.stringify(text)}}]`;
}

function leafNode(
  mapId: string,
  name: string,
  fields: { transactionLocationId?: string; resourceLocationId?: string | null; parentMapId?: string } = {},
): string {
  const parts = [
    `"mapId":${mapId}`,
    `"transactionLocationId":${fields.transactionLocationId ?? '7'}`,
    `"localizedValues":${title(name)}`,
  ];
  if (fields.resourceLocationId !== null) parts.push(`"resourceLocationId":${fields.resourceLocationId ?? '100'}`);
  if (fields.parentMapId !== undefined) parts.push(`"parentMap":{"mapId":${fields.parentMapId}}`);
  return `{${parts.join(',')}}`;
}

function parentNode(mapId: string, name: string): string {
  return `{"mapId":${mapId},"localizedValues":${title(name)}}`;
}

function maps(...nodes: string[]): string {
  return `[${nodes.join(',')}]`;
}

function area(name: string, fields: Record<string, string> = {}, latitude = '50.4', longitude = '-123.1'): string {
  const extra = Object.entries(fields).map(([key, value]) => `,${JSON.stringify(key)}:${value}`);
  return `{"protectedAreaName":${JSON.stringify(name)},"latitude":"${latitude}","longitude":"${longitude}"${extra.join('')}}`;
}

function strapi(...areas: string[]): string {
  return `{"data":[${areas.join(',')}]}`;
}

function inventory(...resources: string[]): string {
  return `{${resources.map((resource, index) => `"r${index}":${resource}`).join(',')}}`;
}

function resource(resourceLocationId: string, mapIds: string[], category = CAMPSITE_CATEGORY): string {
  return `{"resourceLocationId":${resourceLocationId},"resourceCategoryId":${category},"mapIds":[${mapIds.join(',')}]}`;
}

const DICTIONARY =
  `{"resource_categories":[{"resourceCategoryId":${CAMPSITE_CATEGORY},"showResourceCapacityOnline":true},` +
  `{"resourceCategoryId":${PARKING_CATEGORY},"showResourceCapacityOnline":false}]}`;

function inputsOf(mapsJson: string, strapiJson: string, inventoryJson = '{}', dictionaryJson: string | null = null): Inputs {
  return {
    [MAPS]: [mapsJson],
    [STRAPI]: [strapiJson],
    [INVENTORY]: [inventoryJson],
    ...(dictionaryJson === null ? {} : { [DICTIONARIES]: [dictionaryJson] }),
  };
}

const ALICE_LAKE = area('Alice Lake Provincial Park');

describe('the BC Parks campgrounds adapter, parse', () => {
  test('a run with the four inputs yields one DTO holding the leaves, the Strapi rows and the Strapi geometry source', () => {
    const [result, ...rest] = parsed(inputsOf(maps(leafNode('1', 'Alice Lake')), strapi(ALICE_LAKE)));
    expect(rest).toEqual([]);
    if (result === undefined || !result.ok) throw new Error('expected a DTO');
    expect(result.dto.leaves.map((leaf) => leaf.name)).toEqual(['Alice Lake']);
    expect(result.dto.strapiRows.map((row) => row.name)).toEqual(['Alice Lake Provincial Park']);
    expect(result.dto.geomSources.map(([slug]) => slug)).toEqual([STRAPI]);
    expect(result.dto.dictionaryPayload).toBeNull();
  });

  test('empty feeds collect all three errors together', () => {
    const inputs = inputsOf(maps(parentNode('1', 'Park')), '{"data":[]}');
    inputs[INVENTORY] = [];
    expect(parsed(inputs)).toEqual([
      { ok: false, errors: ['no Aspira leaves from /api/maps', 'no BC Parks Strapi rows parsed', 'no inventory envelopes'] },
    ]);
  });

  test('a missing maps, inventory or Strapi input throws rather than reporting', () => {
    for (const slug of [MAPS, INVENTORY, STRAPI]) {
      const inputs = inputsOf(maps(leafNode('1', 'Alice Lake')), strapi(ALICE_LAKE));
      delete inputs[slug];
      expect(() => parsed(inputs), slug).toThrow();
    }
  });

  test('a dictionary payload that is not an object is read as no dictionary', () => {
    const [result] = parsed(inputsOf(maps(leafNode('1', 'Alice Lake')), strapi(ALICE_LAKE), '{}', '[]'));
    expect(result?.ok && result.dto.dictionaryPayload).toBeNull();
  });
});

describe('the BC Parks campgrounds adapter, transform', () => {
  test('a joined leaf takes its point, description, photo, contact and links from the Strapi row', () => {
    const [record, ...rest] = recordsOf(
      inputsOf(
        maps(leafNode('11', 'Alice Lake Provincial Park', { transactionLocationId: WIDE_ID })),
        strapi(
          area('Alice Lake Provincial Park', {
            orcs: `"${WIDE_ID}"`,
            url: '"https://bcparks.ca/alice"',
            description: '"  A lake.  "',
            parkContact: '" 250-555-0100 "',
            parkPhotos: '[{"imageUrl":"https://img/a.jpg"}]',
          }),
        ),
        inventory(resource('100', ['11'])),
      ),
    );
    expect(rest).toEqual([]);
    expect(record).toMatchObject({
      bookingProvider: 'aspira',
      bookingProviderRef: `bc:${WIDE_ID}:11:100`,
      name: 'Alice Lake Provincial Park',
      latitude: 50.4,
      longitude: -123.1,
      kind: 'provincial',
      mediumDescription: 'A lake.',
      location: { latitude: 50.4, longitude: -123.1, region: 'BC', country: 'CA' },
      reservationUrl: BOOKING_URL,
      links: [{ url: BOOKING_URL }, { url: 'https://bcparks.ca/alice' }],
      photos: [{ url: 'https://img/a.jpg' }],
      management: { agency: 'BC Parks' },
      contact: { phone: '250-555-0100' },
      sourceUrl: BOOKING_URL,
      geometryProvenance: { match_kind: 'exact', source: STRAPI, matched_name: 'alice lake' },
    });
    expect(record?.dataProviderRef.serialize()).toBe(`${WIDE_ID}:11`);
    expect(record?.dataProviderRef.provider).toBe('bcparks-strapi');
    expect(record?.sourcePayload).toBe(
      `{"name":"Alice Lake Provincial Park","transactionLocationId":${WIDE_ID},"mapId":11,"resourceLocationId":100,` +
        `"strapi_orcs":${WIDE_ID},"strapi_url":"https://bcparks.ca/alice"}`,
    );
  });

  test('a Strapi url equal to the booking url is not listed twice, and a bare row has no optional fields', () => {
    const [record] = recordsOf(
      inputsOf(maps(leafNode('11', 'Alice Lake')), strapi(area('Alice Lake', { url: `"${BOOKING_URL}"` }))),
    );
    expect(record?.links).toEqual([{ url: BOOKING_URL }]);
    expect(record?.photos).toEqual([]);
    expect(record?.contact).toBeNull();
    expect(record?.mediumDescription).toBeNull();
    expect(record?.sourcePayload).toBe(
      `{"name":"Alice Lake","transactionLocationId":7,"mapId":11,"resourceLocationId":100,"strapi_url":"${BOOKING_URL}"}`,
    );
  });

  test('the point and description come from the Strapi row the match names, even through a parent fallback', () => {
    const [record] = recordsOf(
      inputsOf(
        maps(parentNode('50', 'Garibaldi Provincial Park'), leafNode('12', 'Unrelated Loop', { parentMapId: '50' })),
        strapi(area('Garibaldi Provincial Park', { description: '"Alpine."' }, '49.9', '-123.0'), ALICE_LAKE),
      ),
    );
    expect(record).toMatchObject({
      latitude: 49.9,
      longitude: -123.0,
      mediumDescription: 'Alpine.',
      parentName: 'Garibaldi Provincial Park',
      geometryProvenance: { match_kind: 'parent', source: STRAPI, matched_name: 'garibaldi' },
    });
  });

  test('two Strapi rows sharing a normalized name resolve to the first', () => {
    const [record] = recordsOf(
      inputsOf(
        maps(leafNode('11', 'Alice Lake')),
        strapi(
          area('Alice Lake Provincial Park', { description: '"First."' }, '50.0', '-123.0'),
          area('Alice Lake', { description: '"Second."' }, '51.0', '-124.0'),
        ),
      ),
    );
    expect([record?.latitude, record?.longitude, record?.mediumDescription]).toEqual([50, -123, 'First.']);
  });

  test('the first Strapi row for a name supplies the point even when that row is off the earth', () => {
    const [record] = recordsOf(
      inputsOf(
        maps(leafNode('11', 'Alice Lake')),
        strapi(area('Alice Lake', {}, '95', '-123'), area('Alice Lake Provincial Park', {}, '50.4', '-123.1')),
      ),
    );
    expect([record?.latitude, record?.longitude]).toEqual([95, -123]);
    expect(record?.geometryProvenance).toEqual({ match_kind: 'exact', source: STRAPI, matched_name: 'alice lake' });
  });

  test('a Strapi row off the earth is never matched, so its leaf is dropped', () => {
    const records = recordsOf(
      inputsOf(maps(leafNode('11', 'Nowhere'), leafNode('12', 'Alice Lake', { resourceLocationId: '101' })), strapi(area('Nowhere', {}, '95', '-123'), ALICE_LAKE)),
    );
    expect(records.map((record) => record.name)).toEqual(['Alice Lake']);
  });

  test('container leaves and non-bookable resource locations are dropped', () => {
    const records = recordsOf(
      inputsOf(
        maps(
          leafNode('11', 'Alice Lake', { resourceLocationId: null }),
          leafNode('12', 'Garibaldi', { resourceLocationId: '101' }),
          leafNode('13', 'Alice Lake Beach', { resourceLocationId: '102' }),
        ),
        strapi(ALICE_LAKE, area('Garibaldi Provincial Park')),
        inventory(resource('101', ['12'], PARKING_CATEGORY), resource('102', ['13'])),
        DICTIONARY,
      ),
    );
    expect(records.map((record) => record.name)).toEqual(['Alice Lake Beach']);
  });

  test('a leaf with no inventory entry is emitted with no booking reference', () => {
    const [record] = recordsOf(inputsOf(maps(leafNode('11', 'Alice Lake')), strapi(ALICE_LAKE)));
    expect(record?.bookingProvider).toBe('aspira');
    expect(record?.bookingProviderRef).toBeNull();
  });
});
