import { existsSync, mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest';
import type { DataSourceEntry } from '../registry/poi-registry.js';
import { NoCaptureError, RawCaptureStore } from './raw-capture-store.js';

const listing = vi.hoisted(() => ({ descending: false }));

vi.mock('node:fs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs')>();
  const listNames = actual.readdirSync as (dir: string) => string[];
  const listInOrder = (dir: string): string[] => {
    const ascending = [...listNames(dir)].sort();
    return listing.descending ? ascending.reverse() : ascending;
  };
  return { ...actual, readdirSync: listInOrder };
});

const LISTING_ORDERS = [
  { label: 'ascending', descending: false },
  { label: 'descending', descending: true },
];

const TEMP_DIR_PREFIX = 'raw-capture-store-';
const RAW_DIR_SEGMENTS = ['data', 'raw'];
const SIBLING_RAW_DIR = 'raw-evil';
const REAL_ROOT = fileURLToPath(new URL('../../../../', import.meta.url));
const REAL_RAW_DIR = path.join(REAL_ROOT, ...RAW_DIR_SEGMENTS);
const REAL_RECGOV_SOURCE = 'recgov-campsites';
const REAL_RECGOV_ENVELOPES = 4001;
const REAL_RECGOV_FIRST_PART = 'facility-10000305';
const REAL_RECGOV_LAST_PART = 'facility-275092';
const REAL_DATA_TIMEOUT_MS = 60_000;
const OLD_TIME = new Date('2001-01-01T00:00:00Z');
const NEW_TIME = new Date('2030-01-01T00:00:00Z');

let root = '';
let rawDir = '';
let store: RawCaptureStore;

function envelopeJson(part: string | null, marker = part): string {
  return JSON.stringify({
    fetcher: 'fetch_fixture',
    fetcher_version: '1',
    fetched_at: '2026-06-07T21:33:18Z',
    request: { url: 'https://example.test/', method: 'GET', headers: {} },
    response: { status: 200, headers: {} },
    poller_run_id: null,
    payload: { marker },
    ...(part === null ? {} : { part }),
  });
}

function put(relative: string, content: string): string {
  const file = path.join(rawDir, relative);
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, content);
  return file;
}

function putEnvelope(relative: string, part: string | null, marker = part): string {
  return put(relative, envelopeJson(part, marker));
}

function entryFor(slug: string, outputDirPrefix: string): DataSourceEntry {
  return { slug, name: slug, fetcher: { outputDirPrefix } };
}

function parts(source: string | DataSourceEntry): (string | undefined)[] {
  return store.loadNewestEnvelopes(source).map((e) => e.part);
}

function markers(source: string | DataSourceEntry): unknown[] {
  return store.loadNewestEnvelopes(source).map((e) => (e.payload as { marker: unknown }).marker);
}

beforeAll(() => {
  root = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
  rawDir = path.join(root, ...RAW_DIR_SEGMENTS);
  mkdirSync(rawDir, { recursive: true });

  putEnvelope('paged/2026-01-01T00-00-00Z/page-001.json', 'old-1');
  putEnvelope('paged/2026-01-01T00-00-00Z/page-002.json', 'old-2');
  putEnvelope('paged/2026-02-01T00-00-00Z/page-010.json', 'page-010');
  putEnvelope('paged/2026-02-01T00-00-00Z/page-002.json', 'page-002');
  putEnvelope('paged/2026-02-01T00-00-00Z/page-001.json', 'page-001');
  put('paged/2026-02-01T00-00-00Z/notes.txt', 'not an envelope');
  mkdirSync(path.join(rawDir, 'paged/2026-02-01T00-00-00Z/nested.json'));
  putEnvelope('paged/2026-02-01T00-00-00Z/nested.json/page-000.json', 'nested');
  put('paged/zzz-notes.txt', 'sorts after every capture and is not one');
  utimesSync(path.join(rawDir, 'paged/2026-01-01T00-00-00Z'), NEW_TIME, NEW_TIME);
  utimesSync(path.join(rawDir, 'paged/2026-02-01T00-00-00Z'), OLD_TIME, OLD_TIME);

  const oldSingle = putEnvelope('single/2026-01-01T00-00-00Z.json', 'single-old');
  const newSingle = putEnvelope('single/2026-03-01T00-00-00Z.json', 'single-new');
  put('single/zzz-notes.txt', 'ignored');
  utimesSync(oldSingle, NEW_TIME, NEW_TIME);
  utimesSync(newSingle, OLD_TIME, OLD_TIME);

  putEnvelope('mixed/2026-01-01T00-00-00Z/page-001.json', 'mixed-dir');
  putEnvelope('mixed/2026-01-01T00-00-00Z.json', 'mixed-file');

  mkdirSync(path.join(rawDir, 'empty-source'));
  put('only-junk/notes.txt', 'not a capture');

  putEnvelope('empty-capture/2025-12-01T00-00-00Z/page-001.json', 'older-but-populated');
  mkdirSync(path.join(rawDir, 'empty-capture/2026-01-01T00-00-00Z'));

  putEnvelope('ordering/2026-01-01T00-00-00Z/facility-10.json', 'facility-10');
  putEnvelope('ordering/2026-01-01T00-00-00Z/facility-9.json', 'facility-9');
  putEnvelope('ordering/2026-01-01T00-00-00Z/Facility-5.json', 'Facility-5');

  putEnvelope('newest-ordering/snap-9.json', 'snap-9');
  putEnvelope('newest-ordering/snap-10.json', 'snap-10');
  putEnvelope('newest-ordering/Snap-99.json', 'Snap-99');

  mkdirSync(path.join(root, 'data', SIBLING_RAW_DIR, 'cap'), { recursive: true });
  writeFileSync(path.join(root, 'data', SIBLING_RAW_DIR, 'cap', 'page-001.json'), envelopeJson('sibling'));
  mkdirSync(path.join(root, 'etc', '2026-01-01T00-00-00Z'), { recursive: true });
  writeFileSync(path.join(root, 'etc', '2026-01-01T00-00-00Z', 'page-001.json'), envelopeJson('outside'));

  store = new RawCaptureStore(rawDir, root);
});

afterAll(() => {
  rmSync(root, { recursive: true, force: true });
});

describe.each(LISTING_ORDERS)('with the directory listing $label', ({ descending }) => {
  beforeEach(() => {
    listing.descending = descending;
  });

  describe('RawCaptureStore.loadNewestEnvelopes', () => {
    test('the newest capture directory wins by name, not by mtime, and its pages come back in name order', () => {
      expect(parts('paged')).toEqual(['page-001', 'page-002', 'page-010']);
    });

    test('a single timestamped file is one envelope and the newest name wins over a newer mtime', () => {
      expect(markers('single')).toEqual(['single-new']);
    });

    test('a capture file outranks a capture directory whose name is its prefix', () => {
      expect(markers('mixed')).toEqual(['mixed-file']);
    });

    test.each(['empty-source', 'only-junk'])('a source directory with no captures throws: %s', (source) => {
      expect(() => store.loadNewestEnvelopes(source)).toThrow(NoCaptureError);
    });

    test('a newest capture directory with no json pages throws instead of falling back to an older capture', () => {
      expect(() => store.loadNewestEnvelopes('empty-capture')).toThrow(NoCaptureError);
    });

    test('a source directory that does not exist throws', () => {
      expect(() => store.loadNewestEnvelopes('absent')).toThrow(NoCaptureError);
    });

    test('pages sort by code unit, not by locale or number', () => {
      expect(parts('ordering')).toEqual(['Facility-5', 'facility-10', 'facility-9']);
    });

    test('the newest capture is chosen by code unit, not by locale or number', () => {
      expect(markers('newest-ordering')).toEqual(['snap-9']);
    });
  });

  describe('RawCaptureStore with a DataSourceEntry', () => {
    test('a relative prefix resolves against the static dir', () => {
      expect(parts(entryFor('paged', 'data/raw/paged'))).toEqual(['page-001', 'page-002', 'page-010']);
    });

    test('a prefix that normalizes back inside the raw dir is accepted', () => {
      expect(markers(entryFor('single', 'data/raw/paged/../single'))).toEqual(['single-new']);
    });

    test('an absolute prefix inside the raw dir is accepted', () => {
      expect(markers(entryFor('single', path.join(rawDir, 'single')))).toEqual(['single-new']);
    });

    test('an output_dir_prefix resolving outside the raw dir is rejected before anything is read', () => {
      const escapee = entryFor('escapee', 'data/../etc');
      expect(() => store.loadNewestEnvelopes(escapee)).toThrow(/resolves outside raw dir/);
      expect(() => store.loadNewestEnvelopes(escapee)).not.toThrow(NoCaptureError);
    });

    test('a sibling directory sharing the raw dir name as a string prefix is rejected', () => {
      const sibling = entryFor('sibling', `data/${SIBLING_RAW_DIR}/cap`);
      expect(() => store.loadNewestEnvelopes(sibling)).toThrow(/resolves outside raw dir/);
    });

    test('an absolute prefix outside the raw dir is rejected', () => {
      const absolute = entryFor('absolute', path.join(root, 'etc'));
      expect(() => store.loadNewestEnvelopes(absolute)).toThrow(/resolves outside raw dir/);
    });

    test('the rejection names the slug and the offending prefix', () => {
      expect(() => store.loadNewestEnvelopes(entryFor('escapee', 'data/../etc'))).toThrow(
        /data_source 'escapee' output_dir_prefix 'data\/\.\.\/etc'/,
      );
    });
  });

  describe.skipIf(!existsSync(path.join(REAL_RAW_DIR, REAL_RECGOV_SOURCE)))('against the real capture tree', () => {
    test('the recgov campsite capture loads every page in code-unit order', () => {
      const real = new RawCaptureStore(REAL_RAW_DIR, REAL_ROOT);
      const envelopes = real.loadNewestEnvelopes(REAL_RECGOV_SOURCE);
      expect(envelopes).toHaveLength(REAL_RECGOV_ENVELOPES);
      expect(envelopes[0]?.part).toBe(REAL_RECGOV_FIRST_PART);
      expect(envelopes.at(-1)?.part).toBe(REAL_RECGOV_LAST_PART);
    }, REAL_DATA_TIMEOUT_MS);
  });
});
