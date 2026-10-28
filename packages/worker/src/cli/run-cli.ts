import path from 'node:path';
import { parseArgs } from 'node:util';
import { createDb, type Db } from '@roadtrip/db';
import { implementedSlugs, type AdapterRegistry } from '../etl/adapter-registry.js';
import { runTarget, type EtlStats } from '../etl/orchestrator.js';
import { RawCaptureStore } from '../etl/raw-capture-store.js';
import { loadPoiRegistry } from '../registry/poi-registry.js';
import { importTargets, type ImportTarget } from '../registry/targets.js';

const EXIT_OK = 0;
const EXIT_FAILED = 1;
const EXIT_USAGE = 2;
const DEFAULT_REGISTRY_RELATIVE_PATH = 'backend/src/main/resources/poi-registry.yaml';
const DATABASE_URL_ENV = 'DATABASE_URL';

export interface CliIo {
  adapters: AdapterRegistry;
  env: Readonly<Record<string, string | undefined>>;
  out: (line: string) => void;
  err: (line: string) => void;
}

const USAGE =
  'usage: import --raw-dir <dir> --static-dir <dir> [--registry <yaml>] [--database-url <url>] [--target <name>]... [--list]';

function targetLabel(target: ImportTarget): string {
  return `${target.section}:${target.name}`;
}

function statsLine(stats: EtlStats): string {
  return JSON.stringify(stats, (_key, value) => (typeof value === 'bigint' ? value.toString() : value));
}

function selectTargets(all: ImportTarget[], names: string[]): ImportTarget[] | string {
  if (names.length === 0) return all;
  const known = new Set(all.map((target) => target.name));
  const unknown = names.filter((name) => !known.has(name));
  if (unknown.length > 0) {
    return `unknown or unrunnable target(s): ${unknown.join(', ')}; runnable: ${[...known].join(', ')}`;
  }
  const wanted = new Set(names);
  return all.filter((target) => wanted.has(target.name));
}

export async function runCli(argv: string[], io: CliIo): Promise<number> {
  let values;
  try {
    ({ values } = parseArgs({
      args: argv,
      strict: true,
      options: {
        'raw-dir': { type: 'string' },
        'static-dir': { type: 'string' },
        registry: { type: 'string' },
        'database-url': { type: 'string' },
        target: { type: 'string', multiple: true },
        list: { type: 'boolean' },
      },
    }));
  } catch (error) {
    io.err(`${(error as Error).message}\n${USAGE}`);
    return EXIT_USAGE;
  }

  const rawDir = values['raw-dir'];
  const staticDir = values['static-dir'];
  if (rawDir === undefined || staticDir === undefined) {
    io.err(`--raw-dir and --static-dir are required\n${USAGE}`);
    return EXIT_USAGE;
  }

  if (Object.keys(io.adapters).length === 0) {
    io.err('no adapters registered: refusing to report an empty import as success');
    return EXIT_FAILED;
  }

  let db: Db | undefined;
  try {
    const registry = loadPoiRegistry(values.registry ?? path.join(staticDir, DEFAULT_REGISTRY_RELATIVE_PATH));
    const runnable = importTargets(registry, implementedSlugs(registry, io.adapters));
    const selected = selectTargets(runnable, values.target ?? []);
    if (typeof selected === 'string') {
      io.err(selected);
      return EXIT_USAGE;
    }
    if (selected.length === 0) {
      io.err('no runnable targets: no registry row has every one of its etl adapters registered');
      return EXIT_FAILED;
    }

    if (values.list === true) {
      for (const target of selected) io.out(targetLabel(target));
      return EXIT_OK;
    }

    const databaseUrl = values['database-url'] ?? io.env[DATABASE_URL_ENV];
    if (databaseUrl === undefined || databaseUrl === '') {
      io.err(`--database-url or ${DATABASE_URL_ENV} is required\n${USAGE}`);
      return EXIT_USAGE;
    }

    db = createDb(databaseUrl);
    const store = new RawCaptureStore(rawDir, staticDir);
    for (const target of selected) {
      const stats = await runTarget(target, { db, store, registry, rawDir, adapters: io.adapters });
      io.out(statsLine(stats));
    }
    return EXIT_OK;
  } catch (error) {
    io.err(`import failed: ${error instanceof Error ? error.message : String(error)}`);
    return EXIT_FAILED;
  } finally {
    await db?.$client.end();
  }
}
