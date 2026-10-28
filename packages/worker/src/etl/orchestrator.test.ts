import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createDb, migrateDb, type Db } from '@roadtrip/db';
import { DataProviderRef } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { withPostgis, type PostgisSession } from '../../../../scripts/schema-reference.mjs';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import type { CampsiteDataEntry, DataSourceEntry, PoiDataEntry, PoiRegistry } from '../registry/poi-registry.js';
import { importTargets, type ImportTarget } from '../registry/targets.js';
import type { FlushCounts } from '../repo/bulk.js';
import { implementedSlugs, type AdapterRegistry } from './adapter-registry.js';
import { BatchAccumulator, type TerminalSink } from './batch.js';
import type { InputBundle } from './input-bundle.js';
import { NoCaptureError, RawCaptureStore } from './raw-capture-store.js';
import { runTarget, type EtlStats, type OrchestratorDeps, type RunLog } from './orchestrator.js';
import type { ParseResult, SourceEtl, TransformResult } from './source-etl.js';
import type { TransformCtx } from './transform-ctx.js';
import type { AnyTerminalEtlBinding, TerminalEtlBinding } from './terminal-binding.js';
import { campgroundBinding, campsiteBinding } from './terminal-sinks.js';

const DB_NAME = 'roadtrip_orchestrator';
const CONTAINER_TIMEOUT_MS = 600_000;
const ADAPTER_NAME = 'FakeEtl';
const STATUS_COMPLETED = 'completed';
const STATUS_FAILED = 'failed';
const DEFAULT_SINK_BATCH_SIZE = 1000;
const NO_COUNTS: FlushCounts = { upserted: 0, skipped: 0 };
const FETCHED_AT = '2026-06-07T21:33:18Z';
const HTTP_OK = 200;
const WARN_SAMPLE_LIMIT = 10;
const CAPTURE_STAMP = '2026-06-07T21-33-18Z.json';
const LATITUDE = 37.1;
const LONGITUDE = -119.1;

let postgis: PostgisSession;
let shutdown: () => void;
let session: Promise<void>;
let db: Db;
let rawDir = '';
let slugCounter = 0;

type Item = { kind: 'ok' | 'transformBad'; id: number };

interface Script {
  ok?: number;
  parseBad?: number;
  transformBad?: number;
}

function nextSlug(): string {
  slugCounter += 1;
  return `fake-etl-${slugCounter}`;
}

function fakeEtl(slug: string, script: Script): SourceEtl<Item, number> {
  return {
    etlSlug: slug,
    *parse(): Iterable<ParseResult<Item>> {
      for (let id = 0; id < (script.parseBad ?? 0); id++) yield { ok: false, sourceId: `p${id}`, errors: ['unparseable'] };
      for (let id = 0; id < (script.transformBad ?? 0); id++) yield { ok: true, dto: { kind: 'transformBad', id } };
      for (let id = 0; id < (script.ok ?? 0); id++) yield { ok: true, dto: { kind: 'ok', id } };
    },
    *transform(dto): Iterable<TransformResult<number>> {
      yield dto.kind === 'ok'
        ? { ok: true, record: dto.id }
        : { ok: false, sourceId: `t${dto.id}`, errors: ['untransformable'] };
    },
  };
}

function fanOutEtl(slug: string, recordsPerDto: number[]): SourceEtl<number, number> {
  return {
    etlSlug: slug,
    *parse(): Iterable<ParseResult<number>> {
      for (const count of recordsPerDto) yield { ok: true, dto: count };
    },
    *transform(count): Iterable<TransformResult<number>> {
      for (let record = 0; record < count; record++) yield { ok: true, record };
    },
  };
}

interface RecordingSink<OUT> extends TerminalSink<OUT> {
  written: number[];
}

function recordingSink<OUT>(options: { batchSize?: number; skipPerWrite?: number } = {}): RecordingSink<OUT> {
  const written: number[] = [];
  const skip = options.skipPerWrite ?? 0;
  return {
    batchSize: options.batchSize ?? DEFAULT_SINK_BATCH_SIZE,
    written,
    async write(records) {
      written.push(records.length);
      return { upserted: records.length - skip, skipped: skip };
    },
  };
}

interface Harness {
  target: ImportTarget;
  deps: OrchestratorDeps;
  slug: string;
  warnings: string[];
  infos: string[];
}

function dataSource(slug: string): DataSourceEntry {
  return { slug, name: slug, fetcher: { outputDirPrefix: path.relative(process.cwd(), path.join(rawDir, slug)) } };
}

interface HarnessOptions {
  section?: 'poi_data' | 'campsite_data';
  inputs?: string[];
  subcategory?: string;
  extraEtls?: number;
  withDataSources?: string[];
  adapterName?: string;
}

function harness(
  slug: string,
  binding: AnyTerminalEtlBinding | undefined,
  options: HarnessOptions = {},
): Harness {
  const inputs = options.inputs ?? [];
  const etls = Array.from({ length: 1 + (options.extraEtls ?? 0) }, (_, index) => ({
    slug: index === 0 ? slug : `${slug}-extra-${index}`,
    adapter: options.adapterName ?? ADAPTER_NAME,
    inputs,
    args: {},
  }));
  const section = options.section ?? 'poi_data';
  const poiData: PoiDataEntry[] =
    section === 'poi_data'
      ? [{ name: slug, enabled: true, category: 'campground', subcategory: options.subcategory, etls }]
      : [];
  const campsiteData: CampsiteDataEntry[] = section === 'campsite_data' ? [{ name: slug, enabled: true, etls }] : [];
  const sources = (options.withDataSources ?? inputs).map(dataSource);
  const registry: PoiRegistry = {
    dataSources: sources,
    poiData,
    campsiteData,
    bookingProviders: [],
    dataSource: (name) => sources.find((entry) => entry.slug === name),
    poiDataByName: (name) => poiData.find((row) => row.name === name),
    campsiteDataByName: (name) => campsiteData.find((row) => row.name === name),
  };
  const adapters: AdapterRegistry = binding === undefined ? {} : { [options.adapterName ?? ADAPTER_NAME]: () => binding };
  const target: ImportTarget = {
    name: slug,
    section,
    entry: etls[etls.length - 1]!,
  };
  const wired = importTargets(registry, implementedSlugs(registry, adapters)).find((t) => t.name === slug);
  const warnings: string[] = [];
  const infos: string[] = [];
  const log: RunLog = { info: (line) => infos.push(line), warn: (line) => warnings.push(line) };
  return {
    target: wired ?? target,
    slug,
    warnings,
    infos,
    deps: { db, store: new RawCaptureStore(rawDir, process.cwd()), registry, rawDir, adapters, log },
  };
}

function bindingOf<DTO, OUT>(etl: SourceEtl<DTO, OUT>, sink: TerminalSink<OUT>): TerminalEtlBinding<DTO, OUT> {
  return { etl, sink: () => sink };
}

function run<OUT>(script: Script, sink: TerminalSink<number> = recordingSink<number>()): Promise<EtlStats> {
  const slug = nextSlug();
  const h = harness(slug, bindingOf(fakeEtl(slug, script), sink) as AnyTerminalEtlBinding);
  return runTarget(h.target, h.deps);
}

async function importRunsFor(slug: string): Promise<Array<{ status: string; seen_count: number | null; notes: string | null }>> {
  return [
    ...(await db.execute(
      sql`SELECT status, seen_count, notes FROM import_runs WHERE source = ${slug} ORDER BY id`,
    )),
  ] as Array<{ status: string; seen_count: number | null; notes: string | null }>;
}

function writeCapture(slug: string, payload: string): void {
  const dir = path.join(rawDir, slug);
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    path.join(dir, CAPTURE_STAMP),
    `{"fetcher":"fetch_fixture","fetcher_version":"1","fetched_at":"${FETCHED_AT}",` +
      `"request":{"url":"https://example.test/","method":"GET","headers":{}},` +
      `"response":{"status":${HTTP_OK},"headers":{}},"poller_run_id":null,"payload":${payload}}`,
  );
}

beforeAll(async () => {
  rawDir = mkdtempSync(path.join(os.tmpdir(), 'orchestrator-raw-'));
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
  rmSync(rawDir, { recursive: true, force: true });
  await db?.$client.end();
  shutdown?.();
  await session;
}, CONTAINER_TIMEOUT_MS);

describe('BatchAccumulator', () => {
  const NONE: FlushCounts = NO_COUNTS;

  test('the accumulator flushes at the sink batch size and once more at the end', async () => {
    const written: number[] = [];
    const acc = new BatchAccumulator<number>({
      batchSize: 2,
      write: async (records) => {
        written.push(records.length);
        return NONE;
      },
    });
    for (const record of [1, 2, 3]) await acc.add(record);
    await acc.flush();
    expect(written).toEqual([2, 1]);
  });

  test('a final flush with nothing buffered does not call the sink', async () => {
    const written: number[] = [];
    const acc = new BatchAccumulator<number>({
      batchSize: 2,
      write: async (records) => {
        written.push(records.length);
        return NONE;
      },
    });
    for (const record of [1, 2]) await acc.add(record);
    await acc.flush();
    expect(written).toEqual([2]);
  });

  test('add returns the counts of the flush it triggered and zeroes otherwise', async () => {
    const acc = new BatchAccumulator<number>({
      batchSize: 2,
      write: async (records) => ({ upserted: records.length, skipped: 1 }),
    });
    expect(await acc.add(1)).toEqual({ upserted: 0, skipped: 0 });
    expect(await acc.add(2)).toEqual({ upserted: 2, skipped: 1 });
    expect(await acc.flush()).toEqual({ upserted: 0, skipped: 0 });
  });

  test('records are handed to the sink in arrival order', async () => {
    const batches: number[][] = [];
    const acc = new BatchAccumulator<number>({
      batchSize: 3,
      write: async (records) => {
        batches.push([...records]);
        return NONE;
      },
    });
    for (const record of [5, 4, 3, 2, 1]) await acc.add(record);
    await acc.flush();
    expect(batches).toEqual([
      [5, 4, 3],
      [2, 1],
    ]);
  });
});

describe('runTarget', () => {
  test('counts follow the Kotlin arithmetic', async () => {
    const stats = await run({ parseBad: 2, transformBad: 3, ok: 10 });
    expect(stats).toMatchObject({ parsed: 15, transformed: 10, upserted: 10, skipped: 5 });
  });

  test('the persisted seen_count is the parsed figure and the run is completed', async () => {
    const stats = await run({ parseBad: 2, transformBad: 3, ok: 10 });
    expect(await importRunsFor(stats.etlSlug)).toEqual([{ status: STATUS_COMPLETED, seen_count: 15, notes: null }]);
  });

  test('a sink that reports skips rolls them into the run skipped count', async () => {
    const stats = await run({ ok: 4 }, recordingSink<number>({ skipPerWrite: 1 }));
    expect(stats).toMatchObject({ transformed: 4, upserted: 3, skipped: 1 });
  });

  test('repo skips are not seen: seen excludes repoSkipped and skipped includes it', async () => {
    const stats = await run({ ok: 4 }, recordingSink<number>({ skipPerWrite: 1 }));
    expect(stats.parsed).toBe(4);
    expect(await importRunsFor(stats.etlSlug)).toEqual([{ status: STATUS_COMPLETED, seen_count: 4, notes: null }]);
  });

  test('skipped sums parse, transform and repo skips, each with its own weight', async () => {
    const stats = await run({ parseBad: 2, transformBad: 3, ok: 10 }, recordingSink<number>({ skipPerWrite: 4 }));
    expect(stats).toMatchObject({ parsed: 15, transformed: 10, upserted: 6, skipped: 9 });
  });

  test('seen counts transformed records, so a dto fanning out to two counts twice and one yielding none counts zero', async () => {
    const slug = nextSlug();
    const sink = recordingSink<number>();
    const h = harness(slug, bindingOf(fanOutEtl(slug, [2, 0, 1]), sink) as AnyTerminalEtlBinding);
    const stats = await runTarget(h.target, h.deps);
    expect(stats).toMatchObject({ parsed: 3, transformed: 3, upserted: 3, skipped: 0 });
  });

  test('an empty parse completes a run with zero counts and never calls the sink', async () => {
    const sink = recordingSink<number>();
    const stats = await run({}, sink);
    expect(stats).toMatchObject({ parsed: 0, transformed: 0, upserted: 0, skipped: 0 });
    expect(sink.written).toEqual([]);
  });

  test('mid-stream flushes and the final flush are both added into upserted', async () => {
    const sink = recordingSink<number>({ batchSize: 2 });
    const stats = await run({ ok: 5 }, sink);
    expect(sink.written).toEqual([2, 2, 1]);
    expect(stats).toMatchObject({ transformed: 5, upserted: 5, skipped: 0 });
  });

  test('the stats carry the row name, the etl slug and the persisted run id', async () => {
    const stats = await run({ ok: 1 });
    const [row] = await db.execute(sql`SELECT id::text AS id, source FROM import_runs WHERE source = ${stats.etlSlug}`);
    expect(stats.name).toBe(stats.etlSlug);
    expect(stats.runId.toString()).toBe(row!.id);
  });

  test('a throwing terminal marks the import run failed and rethrows', async () => {
    const slug = nextSlug();
    const sink = recordingSink<number>();
    const throwing: SourceEtl<number, number> = {
      etlSlug: slug,
      *parse(): Iterable<ParseResult<number>> {
        yield { ok: true, dto: 1 };
        throw new Error('boom');
      },
      transform: (dto) => [{ ok: true, record: dto }],
    };
    const h = harness(slug, bindingOf(throwing, sink) as AnyTerminalEtlBinding);
    await expect(runTarget(h.target, h.deps)).rejects.toThrow('boom');
    expect(await importRunsFor(slug)).toEqual([{ status: STATUS_FAILED, seen_count: null, notes: 'boom' }]);
    expect(sink.written).toEqual([]);
  });

  test('a sink that throws on write also fails the run and rethrows', async () => {
    const slug = nextSlug();
    const sink: TerminalSink<number> = {
      batchSize: 1,
      write: async () => {
        throw new Error('write exploded');
      },
    };
    const h = harness(slug, bindingOf(fakeEtl(slug, { ok: 1 }), sink) as AnyTerminalEtlBinding);
    await expect(runTarget(h.target, h.deps)).rejects.toThrow('write exploded');
    expect(await importRunsFor(slug)).toEqual([{ status: STATUS_FAILED, seen_count: null, notes: 'write exploded' }]);
  });

  test('a thrown value that is not an Error is recorded by its string form', async () => {
    const slug = nextSlug();
    const throwing: SourceEtl<number, number> = {
      etlSlug: slug,
      *parse(): Iterable<ParseResult<number>> {
        throw 'plain string';
      },
      transform: () => [],
    };
    const h = harness(slug, bindingOf(throwing, recordingSink<number>()) as AnyTerminalEtlBinding);
    await expect(runTarget(h.target, h.deps)).rejects.toBe('plain string');
    expect((await importRunsFor(slug))[0]).toMatchObject({ status: STATUS_FAILED, notes: 'plain string' });
  });

  test('an unregistered adapter is rejected before any run row exists', async () => {
    const slug = nextSlug();
    const h = harness(slug, undefined);
    const target: ImportTarget = { name: slug, section: 'poi_data', entry: h.deps.registry.poiData[0]!.etls[0]! };
    await expect(runTarget(target, h.deps)).rejects.toThrow(`no adapter registered for etl slug='${slug}'`);
    expect(await importRunsFor(slug)).toEqual([]);
  });

  test('an adapter name that Object.prototype owns is not treated as registered', async () => {
    const slug = nextSlug();
    const h = harness(slug, undefined, { adapterName: 'constructor' });
    await expect(runTarget(h.target, h.deps)).rejects.toThrow(`no adapter registered for etl slug='${slug}'`);
    expect(await importRunsFor(slug)).toEqual([]);
  });

  test('a row with several etls, or no row of that name, is rejected before any run row exists', async () => {
    const slug = nextSlug();
    const single = fakeEtl(slug, { ok: 1 });
    const chained = harness(slug, bindingOf(single, recordingSink<number>()) as AnyTerminalEtlBinding, { extraEtls: 1 });
    await expect(runTarget(chained.target, chained.deps)).rejects.toThrow('must declare exactly one etl');
    const missing = harness(slug, undefined);
    await expect(runTarget({ name: 'no-such-row', section: 'poi_data', entry: missing.target.entry }, missing.deps)).rejects.toThrow(
      "no poi_data row with name='no-such-row'",
    );
    expect(await importRunsFor(slug)).toEqual([]);
  });

  test('a data_source input with no capture fails before a run is started', async () => {
    const slug = nextSlug();
    const input = `${slug}-input`;
    const h = harness(slug, bindingOf(fakeEtl(slug, { ok: 1 }), recordingSink<number>()) as AnyTerminalEtlBinding, {
      inputs: [input],
    });
    await expect(runTarget(h.target, h.deps)).rejects.toBeInstanceOf(NoCaptureError);
    expect(await importRunsFor(slug)).toEqual([]);
  });

  test('an input that is not a registered data_source is rejected', async () => {
    const slug = nextSlug();
    const h = harness(slug, bindingOf(fakeEtl(slug, { ok: 1 }), recordingSink<number>()) as AnyTerminalEtlBinding, {
      inputs: ['not-a-data-source'],
      withDataSources: [],
    });
    await expect(runTarget(h.target, h.deps)).rejects.toThrow("input 'not-a-data-source' is not a data_source");
    expect(await importRunsFor(slug)).toEqual([]);
  });

  test('the etl receives the newest capture of each declared input, keyed by slug', async () => {
    const slug = nextSlug();
    const input = `${slug}-input`;
    writeCapture(input, '{"marker":"seen-by-etl"}');
    const seen: unknown[] = [];
    const reading: SourceEtl<number, number> = {
      etlSlug: slug,
      parse(inputs: InputBundle): Iterable<ParseResult<number>> {
        seen.push(inputs.dataSourceSlugs(), inputs.envelope(input).payload);
        return [];
      },
      transform: () => [],
    };
    const h = harness(slug, bindingOf(reading, recordingSink<number>()) as AnyTerminalEtlBinding, { inputs: [input] });
    await runTarget(h.target, h.deps);
    expect(seen).toEqual([[input], { marker: 'seen-by-etl' }]);
  });

  test('transform receives a context loaded from the registry', async () => {
    const slug = nextSlug();
    const seen: Array<string | undefined> = [];
    const reading: SourceEtl<number, number> = {
      etlSlug: slug,
      parse: () => [{ ok: true, dto: 1 }],
      transform(_dto, ctx: TransformCtx) {
        seen.push(ctx.subcategoryFor(slug), ctx.rawDir);
        return [];
      },
    };
    const h = harness(slug, bindingOf(reading, recordingSink<number>()) as AnyTerminalEtlBinding, {
      subcategory: 'state_park',
    });
    await runTarget(h.target, h.deps);
    expect(seen).toEqual(['state_park', rawDir]);
  });

  test('row errors are logged for only the first ten of each stage', async () => {
    const slug = nextSlug();
    const h = harness(slug, bindingOf(fakeEtl(slug, { parseBad: 12, transformBad: 11 }), recordingSink<number>()) as AnyTerminalEtlBinding);
    await runTarget(h.target, h.deps);
    const parseWarnings = h.warnings.filter((line) => line.includes(' parse bad '));
    const transformWarnings = h.warnings.filter((line) => line.includes(' transform bad '));
    expect(parseWarnings).toHaveLength(WARN_SAMPLE_LIMIT);
    expect(transformWarnings).toHaveLength(WARN_SAMPLE_LIMIT);
    expect(parseWarnings[0]).toContain('sourceId=p0');
  });

  test('a run with skips logs a skipped warning and a clean run does not', async () => {
    const dirty = nextSlug();
    const dirtyHarness = harness(dirty, bindingOf(fakeEtl(dirty, { ok: 1, parseBad: 1 }), recordingSink<number>()) as AnyTerminalEtlBinding);
    await runTarget(dirtyHarness.target, dirtyHarness.deps);
    expect(dirtyHarness.warnings.some((line) => line.includes('skipped 1 records'))).toBe(true);

    const clean = nextSlug();
    const cleanHarness = harness(clean, bindingOf(fakeEtl(clean, { ok: 1 }), recordingSink<number>()) as AnyTerminalEtlBinding);
    await runTarget(cleanHarness.target, cleanHarness.deps);
    expect(cleanHarness.warnings).toEqual([]);
    expect(cleanHarness.infos).toHaveLength(1);
  });
});

describe('runTarget through the catalog sinks', () => {
  function campgroundCandidate(ref: string): CampgroundUpsertCandidate {
    return {
      dataProviderRef: DataProviderRef.campflare(ref),
      name: `Camp ${ref}`,
      latitude: LATITUDE,
      longitude: LONGITUDE,
      location: {},
    };
  }

  function campsiteCandidate(ref: string, parentRef: string): CampsiteUpsertCandidate {
    return {
      dataProviderRef: DataProviderRef.campflare(ref),
      parentDataProviderRef: DataProviderRef.campflare(parentRef),
      name: `Site ${ref}`,
    };
  }

  function candidateEtl<OUT>(slug: string, good: OUT[], badParse: number, badTransform: number): SourceEtl<OUT | 'bad', OUT> {
    return {
      etlSlug: slug,
      *parse(): Iterable<ParseResult<OUT | 'bad'>> {
        for (let i = 0; i < badParse; i++) yield { ok: false, errors: ['unparseable'] };
        for (let i = 0; i < badTransform; i++) yield { ok: true, dto: 'bad' };
        for (const record of good) yield { ok: true, dto: record };
      },
      *transform(dto): Iterable<TransformResult<OUT>> {
        yield dto === 'bad' ? { ok: false, errors: ['untransformable'] } : { ok: true, record: dto };
      },
    };
  }

  test('campgrounds upsert through the repo and report no repo skips', async () => {
    const slug = nextSlug();
    const ref = `${slug}-cg`;
    const etl = candidateEtl<CampgroundUpsertCandidate>(slug, [campgroundCandidate(ref)], 1, 1);
    const h = harness(slug, campgroundBinding(etl) as unknown as AnyTerminalEtlBinding);
    const stats = await runTarget(h.target, h.deps);
    expect(stats).toMatchObject({ parsed: 3, transformed: 1, upserted: 1, skipped: 2 });
    const [row] = await db.execute(sql`SELECT count(*)::int AS n FROM campgrounds WHERE data_provider_ref = ${ref}`);
    expect(row!.n).toBe(1);
  });

  test('campsites whose parent campground is missing surface as repo skips', async () => {
    const slug = nextSlug();
    const parentRef = `${slug}-cg`;
    const campgrounds = candidateEtl<CampgroundUpsertCandidate>(`${slug}-parents`, [campgroundCandidate(parentRef)], 0, 0);
    const parents = harness(`${slug}-parents`, campgroundBinding(campgrounds) as unknown as AnyTerminalEtlBinding);
    await runTarget(parents.target, parents.deps);

    const sites = candidateEtl<CampsiteUpsertCandidate>(
      slug,
      [campsiteCandidate(`${slug}-s1`, parentRef), campsiteCandidate(`${slug}-s2`, `${slug}-orphan-parent`)],
      0,
      0,
    );
    const h = harness(slug, campsiteBinding(sites) as unknown as AnyTerminalEtlBinding, { section: 'campsite_data' });
    const stats = await runTarget(h.target, h.deps);
    expect(stats).toMatchObject({ parsed: 2, transformed: 2, upserted: 1, skipped: 1 });
    expect(await importRunsFor(slug)).toEqual([{ status: STATUS_COMPLETED, seen_count: 2, notes: null }]);
  });
});
