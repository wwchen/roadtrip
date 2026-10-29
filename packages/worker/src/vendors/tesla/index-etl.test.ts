import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { PoiRegistry } from '../../registry/poi-registry.js';
import type { TeslaSuperchargerUpsertCandidate } from '../../model/tesla-candidate.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import { decodeTeslaIndex } from './dto.js';
import { TeslaIndexEtl } from './index-etl.js';

const OLDEST_CAPTURE = '2026-01-01T00-00-00Z.json';
const MIDDLE_CAPTURE = '2026-03-01T00-00-00Z.json';
const NEWEST_CAPTURE = '2026-06-09T15-33-15Z.json';
const SHOWN_ON_FIND_US = '1';
const SITE_STATUS = 'open';
const INDEX_LATITUDE = 44.1;
const INDEX_LONGITUDE = -123.2;
const HTTP_OK = 200;

const EMPTY_REGISTRY: PoiRegistry = {
  dataSources: [],
  poiData: [],
  campsiteData: [],
  bookingProviders: [],
  dataSource: () => undefined,
  poiDataByName: () => undefined,
  campsiteDataByName: () => undefined,
};

let rawDir = '';

function envelopeWithName(name: string): string {
  return JSON.stringify({
    fetcher: 'fetch_tesla_locations',
    fetcher_version: '1',
    fetched_at: '2026-06-09T15:33:15Z',
    request: { url: 'https://example.invalid', method: 'GET', headers: {} },
    response: { status: HTTP_OK, headers: {} },
    poller_run_id: null,
    payload: { data: { data: { name } } },
  });
}

function writeCapture(slug: string, file: string, contents: string): void {
  const dir = path.join(rawDir, 'tesla-locations', slug);
  mkdirSync(dir, { recursive: true });
  writeFileSync(path.join(dir, file), contents);
}

function indexRowsFor(slugs: string[]) {
  return slugs.map((slug) => ({
    latitude: INDEX_LATITUDE,
    longitude: INDEX_LONGITUDE,
    title: 'locations',
    location_type: ['supercharger'],
    location_url_slug: slug,
    supercharger_function: { show_on_find_us: SHOWN_ON_FIND_US, site_status: SITE_STATUS },
  }));
}

function transformedNames(slugs: string[]): Map<string, string> {
  const dto = decodeTeslaIndex({ data: { data: indexRowsFor(slugs) } });
  const ctx = TransformCtx.load(rawDir, EMPTY_REGISTRY);
  const names = new Map<string, string>();
  for (const result of new TeslaIndexEtl().transform(dto, ctx)) {
    if (!result.ok) throw new Error(result.errors.join(', '));
    const record: TeslaSuperchargerUpsertCandidate = result.record;
    names.set(record.locationSlug, record.commonSiteName);
  }
  return names;
}

beforeEach(() => {
  rawDir = mkdtempSync(path.join(os.tmpdir(), 'tesla-index-etl-'));
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  rmSync(rawDir, { recursive: true, force: true });
});

describe('tesla-locations side-load', () => {
  test('reads the newest capture by file name, not by write order or directory order', () => {
    writeCapture('woodburn', NEWEST_CAPTURE, envelopeWithName('Newest'));
    writeCapture('woodburn', OLDEST_CAPTURE, envelopeWithName('Oldest'));
    writeCapture('woodburn', MIDDLE_CAPTURE, envelopeWithName('Middle'));

    expect(transformedNames(['woodburn']).get('woodburn')).toBe('Newest');
  });

  test('a slug directory with no .json file is skipped rather than thrown on', () => {
    mkdirSync(path.join(rawDir, 'tesla-locations', 'empty'), { recursive: true });
    writeCapture('notes', 'zzz-detail.txt', envelopeWithName('Not A Json File'));

    const names = transformedNames(['empty', 'notes', 'never-fetched']);

    expect(names.get('empty')).toBe('Supercharger empty');
    expect(names.get('notes')).toBe('Supercharger notes');
    expect(names.get('never-fetched')).toBe('Supercharger never-fetched');
  });

  test('a directory named like a capture is not mistaken for the newest capture', () => {
    writeCapture('nested', OLDEST_CAPTURE, envelopeWithName('The Real Capture'));
    mkdirSync(path.join(rawDir, 'tesla-locations', 'nested', NEWEST_CAPTURE));

    expect(transformedNames(['nested']).get('nested')).toBe('The Real Capture');
  });

  test('an unreadable newest capture is not replaced by an older readable one', () => {
    writeCapture('broken', OLDEST_CAPTURE, envelopeWithName('Older But Readable'));
    writeCapture('broken', NEWEST_CAPTURE, '{ not json');

    expect(transformedNames(['broken']).get('broken')).toBe('Supercharger broken');
    expect(console.error).toHaveBeenCalledWith(expect.stringContaining('slug=broken'));
  });
});

describe('sanitized slug collisions', () => {
  test('two upstream slugs that sanitize to the same key fail the run instead of overwriting a row', () => {
    const dto = decodeTeslaIndex({ data: { data: indexRowsFor(['A/b', 'a-b']) } });
    const ctx = TransformCtx.load(rawDir, EMPTY_REGISTRY);

    expect(() => [...new TeslaIndexEtl().transform(dto, ctx)]).toThrow(/1 sanitized location_slug collision\(s\).*a-b/);
  });

  test('distinct slugs that stay distinct once sanitized do not collide', () => {
    expect([...transformedNames(['A/b', 'a-c']).keys()]).toEqual(['a-b', 'a-c']);
  });
});
