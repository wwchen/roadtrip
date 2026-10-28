import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createDb, migrateDb, type Db } from '@roadtrip/db';
import { DataProviderRef } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import type { AdapterRegistry } from '../etl/adapter-registry.js';
import type { ParseResult, SourceEtl, TransformResult } from '../etl/source-etl.js';
import { campgroundBinding, campsiteBinding } from '../etl/terminal-sinks.js';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import { runCli, type CliIo } from './run-cli.js';

const PRODUCTION_REGISTRY_PATH = fileURLToPath(
  new URL('../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url),
);
const DB_NAME = 'roadtrip_cli';
const CONTAINER_TIMEOUT_MS = 600_000;
const EXIT_OK = 0;
const EXIT_FAILED = 1;
const EXIT_USAGE = 2;
const CAMPGROUNDS_ADAPTER = 'CampflareCampgroundsEtl';
const CAMPSITES_ADAPTER = 'CampflareCampsitesEtl';
const CAMPGROUNDS_INPUT = 'campflare-campgrounds-export';
const CAMPSITES_INPUT = 'campflare-campsites-export';
const CAMPGROUNDS_SLUG = 'campflare-campgrounds';
const CAMPSITES_SLUG = 'campflare-campsites';
const CAMPGROUND_REF = 'cli-cg-1';
const SITE_REF = 'cli-site-1';
const FETCHED_AT = '2026-06-07T21:33:18Z';
const HTTP_OK = 200;
const CAPTURE_STAMP = '2026-06-07T21-33-18Z.json';
const LATITUDE = 37.1;
const LONGITUDE = -119.1;
const UNREACHABLE_DATABASE_URL = 'postgres://nobody:nothing@127.0.0.1:1/none';

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;
let staticDir = '';

interface Captured {
  out: string[];
  err: string[];
}

function writeCapture(slug: string, payload: string): void {
  const dir = path.join(staticDir, 'data', 'raw', slug);
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    path.join(dir, CAPTURE_STAMP),
    `{"fetcher":"fetch_fixture","fetcher_version":"1","fetched_at":"${FETCHED_AT}",` +
      `"request":{"url":"https://example.test/","method":"GET","headers":{}},` +
      `"response":{"status":${HTTP_OK},"headers":{}},"poller_run_id":null,"payload":${payload}}`,
  );
}

const campgroundEtl: SourceEtl<string, CampgroundUpsertCandidate> = {
  etlSlug: CAMPGROUNDS_SLUG,
  parse: (inputs) =>
    (inputs.envelope(CAMPGROUNDS_INPUT).payload as string[]).map((ref): ParseResult<string> => ({ ok: true, dto: ref })),
  transform: (ref): Iterable<TransformResult<CampgroundUpsertCandidate>> => [
    {
      ok: true,
      record: {
        dataProviderRef: DataProviderRef.campflare(ref),
        name: ref,
        latitude: LATITUDE,
        longitude: LONGITUDE,
        location: {},
      },
    },
  ],
};

const campsiteEtl: SourceEtl<string, CampsiteUpsertCandidate> = {
  etlSlug: CAMPSITES_SLUG,
  parse: (inputs) =>
    (inputs.envelope(CAMPSITES_INPUT).payload as string[]).map((ref): ParseResult<string> => ({ ok: true, dto: ref })),
  transform: (ref): Iterable<TransformResult<CampsiteUpsertCandidate>> => [
    {
      ok: true,
      record: {
        dataProviderRef: DataProviderRef.campflare(ref),
        parentDataProviderRef: DataProviderRef.campflare(CAMPGROUND_REF),
        name: ref,
      },
    },
  ],
};

const FAKE_ADAPTERS: AdapterRegistry = {
  [CAMPGROUNDS_ADAPTER]: () => campgroundBinding(campgroundEtl),
  [CAMPSITES_ADAPTER]: () => campsiteBinding(campsiteEtl),
};

function cli(adapters: AdapterRegistry, argv: string[], env: Record<string, string> = {}): Promise<{ code: number } & Captured> {
  const captured: Captured = { out: [], err: [] };
  const io: CliIo = {
    adapters,
    env,
    out: (line) => captured.out.push(line),
    err: (line) => captured.err.push(line),
  };
  const base = ['--raw-dir', path.join(staticDir, 'data', 'raw'), '--static-dir', staticDir, '--registry', PRODUCTION_REGISTRY_PATH];
  return runCli([...base, ...argv], io).then((code) => ({ code, ...captured }));
}

beforeAll(async () => {
  staticDir = mkdtempSync(path.join(os.tmpdir(), 'cli-static-'));
  writeCapture(CAMPGROUNDS_INPUT, JSON.stringify([CAMPGROUND_REF]));
  writeCapture(CAMPSITES_INPUT, JSON.stringify([SITE_REF]));
  let started: () => void;
  const ready = new Promise<void>((resolve) => {
    started = resolve;
  });
  session = withPostgis(async (handle) => {
    postgis = handle;
    await handle.createDatabase(DB_NAME);
    started();
    await new Promise<void>((resolve) => {
      shutdown = resolve;
    });
  });
  await Promise.race([ready, session]);
  db = createDb(postgis.connectionString(DB_NAME));
  await migrateDb(db);
}, CONTAINER_TIMEOUT_MS);

afterAll(async () => {
  rmSync(staticDir, { recursive: true, force: true });
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

describe('import cli', () => {
  test('an empty adapter registry exits non-zero and says so, rather than reporting an empty success', async () => {
    const result = await cli({}, ['--list']);
    expect(result.code).toBe(EXIT_FAILED);
    expect(result.err.join('\n')).toContain('no adapters registered');
    expect(result.out).toEqual([]);
  });

  test('the same refusal applies to a run, not only to --list', async () => {
    const result = await cli({}, [], { DATABASE_URL: UNREACHABLE_DATABASE_URL });
    expect(result.code).toBe(EXIT_FAILED);
    expect(result.err.join('\n')).toContain('no adapters registered');
  });

  test('registered adapters that match no registry row leave nothing to run, which is a failure, not a success', async () => {
    const unmatched: AdapterRegistry = { MisspeltEtl: FAKE_ADAPTERS[CAMPGROUNDS_ADAPTER]! };
    const listed = await cli(unmatched, ['--list']);
    expect(listed.code).toBe(EXIT_FAILED);
    expect(listed.err.join('\n')).toContain('no runnable targets');
    expect(listed.out).toEqual([]);
    const run = await cli(unmatched, ['--database-url', UNREACHABLE_DATABASE_URL]);
    expect(run.code).toBe(EXIT_FAILED);
    expect(run.err.join('\n')).toContain('no runnable targets');
    expect(run.out).toEqual([]);
  });

  test('--list prints the runnable targets in registry order and nothing else', async () => {
    const result = await cli(FAKE_ADAPTERS, ['--list']);
    expect(result).toMatchObject({
      code: EXIT_OK,
      out: ['poi_data:Campflare Campgrounds', 'campsite_data:Campflare Campsites'],
      err: [],
    });
  });

  test('--target selects by name but still lists in registry order', async () => {
    const result = await cli(FAKE_ADAPTERS, ['--list', '--target', 'Campflare Campsites', '--target', 'Campflare Campgrounds']);
    expect(result.out).toEqual(['poi_data:Campflare Campgrounds', 'campsite_data:Campflare Campsites']);
    const only = await cli(FAKE_ADAPTERS, ['--list', '--target', 'Campflare Campsites']);
    expect(only.out).toEqual(['campsite_data:Campflare Campsites']);
  });

  test('an unknown or unrunnable --target is a usage error naming the runnable ones', async () => {
    const result = await cli(FAKE_ADAPTERS, ['--list', '--target', 'Planet Fitness']);
    expect(result.code).toBe(EXIT_USAGE);
    expect(result.err.join('\n')).toContain('Planet Fitness');
    expect(result.err.join('\n')).toContain('Campflare Campgrounds');
    expect(result.out).toEqual([]);
  });

  test('a run without a database url is a usage error', async () => {
    const result = await cli(FAKE_ADAPTERS, []);
    expect(result.code).toBe(EXIT_USAGE);
    expect(result.err.join('\n')).toContain('DATABASE_URL');
  });

  test('missing required directories and unknown flags are usage errors', async () => {
    const io: CliIo = { adapters: FAKE_ADAPTERS, env: {}, out: () => {}, err: () => {} };
    expect(await runCli(['--list'], io)).toBe(EXIT_USAGE);
    expect(await runCli(['--raw-dir', 'a', '--static-dir', 'b', '--bogus'], io)).toBe(EXIT_USAGE);
  });

  test('a database that cannot be reached fails the run non-zero', async () => {
    const result = await cli(FAKE_ADAPTERS, ['--database-url', UNREACHABLE_DATABASE_URL]);
    expect(result.code).toBe(EXIT_FAILED);
    expect(result.err.join('\n')).toContain('import failed');
    expect(result.out).toEqual([]);
  });

  test('an unreadable registry path fails as an import failure, not a raw stack trace', async () => {
    const captured: Captured = { out: [], err: [] };
    const io: CliIo = {
      adapters: FAKE_ADAPTERS,
      env: {},
      out: (line) => captured.out.push(line),
      err: (line) => captured.err.push(line),
    };
    const code = await runCli(
      ['--raw-dir', staticDir, '--static-dir', staticDir, '--registry', path.join(staticDir, 'missing.yaml'), '--list'],
      io,
    );
    expect(code).toBe(EXIT_FAILED);
    expect(captured.err.join('\n')).toContain('import failed:');
    expect(captured.err.join('\n')).toContain('missing.yaml');
    expect(captured.out).toEqual([]);
  });

  test('the first failing target stops the run: no later target runs and nothing is reported as done', async () => {
    let campsitesParsed = false;
    const adapters: AdapterRegistry = {
      [CAMPGROUNDS_ADAPTER]: () =>
        campgroundBinding({
          etlSlug: CAMPGROUNDS_SLUG,
          parse: () => {
            throw new Error('campgrounds exploded');
          },
          transform: () => [],
        }),
      [CAMPSITES_ADAPTER]: () =>
        campsiteBinding({
          ...campsiteEtl,
          parse: (inputs) => {
            campsitesParsed = true;
            return campsiteEtl.parse(inputs);
          },
        }),
    };
    const result = await cli(adapters, ['--database-url', postgis.connectionString(DB_NAME)]);
    expect(result.code).toBe(EXIT_FAILED);
    expect(result.err.join('\n')).toContain('import failed: campgrounds exploded');
    expect(result.out).toEqual([]);
    expect(campsitesParsed).toBe(false);
  });

  test('targets run sequentially in registry order, campsites resolving the campground the first target wrote', async () => {
    const result = await cli(FAKE_ADAPTERS, [
      '--database-url',
      postgis.connectionString(DB_NAME),
      '--target',
      'Campflare Campsites',
      '--target',
      'Campflare Campgrounds',
    ]);
    expect(result.err.join('\n')).not.toContain('import failed');
    expect(result.code).toBe(EXIT_OK);
    const stats = result.out.map((line) => JSON.parse(line));
    expect(stats).toMatchObject([
      { name: 'Campflare Campgrounds', etlSlug: CAMPGROUNDS_SLUG, parsed: 1, transformed: 1, upserted: 1, skipped: 0 },
      { name: 'Campflare Campsites', etlSlug: CAMPSITES_SLUG, parsed: 1, transformed: 1, upserted: 1, skipped: 0 },
    ]);
    expect(BigInt(stats[1].runId)).toBeGreaterThan(BigInt(stats[0].runId));
    const [row] = await db.execute(sql`SELECT count(*)::int AS n FROM campsites WHERE name = ${SITE_REF}`);
    expect(row!.n).toBe(1);
  });

  test('the database url may come from DATABASE_URL', async () => {
    const result = await cli(FAKE_ADAPTERS, ['--target', 'Campflare Campgrounds'], {
      DATABASE_URL: postgis.connectionString(DB_NAME),
    });
    expect(result.code).toBe(EXIT_OK);
    expect(JSON.parse(result.out[0]!)).toMatchObject({ name: 'Campflare Campgrounds', upserted: 1 });
  });
});
