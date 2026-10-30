import type { Db } from '@roadtrip/db';
import type { EtlEntry, PoiRegistry } from '../registry/poi-registry.js';
import type { ImportTarget } from '../registry/targets.js';
import { completeImportRun, failImportRun, startImportRun } from '../repo/import-run-repo.js';
import { adapterFor, adapterRegistry, type AdapterRegistry } from './adapter-registry.js';
import { BatchAccumulator } from './batch.js';
import type { Envelope } from './envelope.js';
import { InputBundle } from './input-bundle.js';
import type { RawCaptureStore } from './raw-capture-store.js';
import { STDERR_LOG, type RunLog } from './run-log.js';
import type { ParseResult, TransformResult } from './source-etl.js';
import { TransformCtx } from './transform-ctx.js';

const ROW_ERROR_LOG_SAMPLE_LIMIT = 10;
const STAGE_PARSE = 'parse';
const STAGE_TRANSFORM = 'transform';

export interface EtlStats {
  name: string;
  etlSlug: string;
  runId: bigint;
  parsed: number;
  transformed: number;
  upserted: number;
  skipped: number;
}

export interface OrchestratorDeps {
  db: Db;
  store: RawCaptureStore;
  registry: PoiRegistry;
  rawDir: string;
  adapters?: AdapterRegistry;
  log?: RunLog;
}

export type { RunLog };

function singleEtl(target: ImportTarget, registry: PoiRegistry): EtlEntry {
  const row =
    target.section === 'poi_data'
      ? registry.poiDataByName(target.name)
      : registry.campsiteDataByName(target.name);
  if (row === undefined) throw new Error(`no ${target.section} row with name='${target.name}'`);
  const [only] = row.etls;
  if (row.etls.length !== 1 || only === undefined) {
    throw new Error(`${target.section} '${target.name}' must declare exactly one etl`);
  }
  return only;
}

function buildBundle(inputSlugs: readonly string[], deps: OrchestratorDeps): InputBundle {
  const raw = new Map<string, Envelope[]>();
  for (const slug of inputSlugs) {
    const dataSource = deps.registry.dataSource(slug);
    if (dataSource === undefined) {
      throw new Error(`input '${slug}' is not a data_source — should have been caught by registry validation`);
    }
    raw.set(slug, deps.store.loadNewestEnvelopes(dataSource));
  }
  return new InputBundle(raw);
}

function failureNotes(error: unknown): string {
  if (error instanceof Error) return error.message === '' ? error.name : error.message;
  return String(error);
}

export async function runTarget(target: ImportTarget, deps: OrchestratorDeps): Promise<EtlStats> {
  const log = deps.log ?? STDERR_LOG;
  const entry = singleEtl(target, deps.registry);
  const ctx = TransformCtx.load(deps.rawDir, deps.registry);
  const factory = adapterFor(deps.adapters ?? adapterRegistry, entry.adapter);
  if (factory === undefined) {
    throw new Error(`no adapter registered for etl slug='${entry.slug}' (adapter '${entry.adapter}')`);
  }
  const binding = factory(entry);
  const bundle = buildBundle(entry.inputs, deps);

  const { etl } = binding;
  const runId = await startImportRun(deps.db, etl.etlSlug);
  const batcher = new BatchAccumulator(binding.sink(deps.db));
  let parseOk = 0;
  let parseBad = 0;
  let transformOk = 0;
  let transformBad = 0;
  let upserted = 0;
  let repoSkipped = 0;

  const logRowError = (
    stage: string,
    result: Extract<ParseResult<unknown> | TransformResult<unknown>, { ok: false }>,
    count: number,
  ) => {
    if (count <= ROW_ERROR_LOG_SAMPLE_LIMIT) {
      log.warn(
        `${target.section} '${target.name}' terminal slug=${etl.etlSlug} ${stage} bad sourceId=${result.sourceId} errors=${JSON.stringify(result.errors)}`,
      );
    }
  };

  try {
    for (const parseResult of etl.parse(bundle)) {
      if (!parseResult.ok) {
        parseBad++;
        logRowError(STAGE_PARSE, parseResult, parseBad);
        continue;
      }
      parseOk++;
      for (const transformResult of etl.transform(parseResult.dto, ctx)) {
        if (!transformResult.ok) {
          transformBad++;
          logRowError(STAGE_TRANSFORM, transformResult, transformBad);
          continue;
        }
        transformOk++;
        const counts = await batcher.add(transformResult.record);
        upserted += counts.upserted;
        repoSkipped += counts.skipped;
      }
    }

    const finalCounts = await batcher.flush();
    upserted += finalCounts.upserted;
    repoSkipped += finalCounts.skipped;

    const seen = transformOk + parseBad + transformBad;
    const skipped = parseBad + transformBad + repoSkipped;
    await completeImportRun(deps.db, runId, seen);

    log.info(
      `${target.section} '${target.name}' terminal slug=${etl.etlSlug} parseOk=${parseOk} parseBad=${parseBad} transformOk=${transformOk} transformBad=${transformBad} upserted=${upserted} repoSkipped=${repoSkipped}`,
    );
    if (skipped > 0) {
      log.warn(
        `${target.section} '${target.name}' terminal slug=${etl.etlSlug} skipped ${skipped} records (parseBad=${parseBad} transformBad=${transformBad} repoSkipped=${repoSkipped})`,
      );
    }
    return {
      name: target.name,
      etlSlug: etl.etlSlug,
      runId,
      parsed: seen,
      transformed: transformOk,
      upserted,
      skipped,
    };
  } catch (error) {
    await failImportRun(deps.db, runId, failureNotes(error));
    throw error;
  }
}
