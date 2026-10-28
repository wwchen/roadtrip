import { readdirSync, statSync, type Stats } from 'node:fs';
import path from 'node:path';
import type { DataSourceEntry } from '../registry/poi-registry.js';
import { parseEnvelope, type Envelope } from './envelope.js';

const JSON_SUFFIX = '.json';
const PARENT_SEGMENT = '..';

export class NoCaptureError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'NoCaptureError';
  }
}

function compareCodeUnits(a: string, b: string): number {
  if (a < b) return -1;
  return a > b ? 1 : 0;
}

function statOrNull(file: string): Stats | null {
  try {
    return statSync(file);
  } catch {
    return null;
  }
}

function isDirectory(file: string): boolean {
  return statOrNull(file)?.isDirectory() ?? false;
}

function isJsonFile(file: string): boolean {
  return file.endsWith(JSON_SUFFIX) && (statOrNull(file)?.isFile() ?? false);
}

function listNames(dir: string): string[] | null {
  try {
    return readdirSync(dir);
  } catch {
    return null;
  }
}

function isWithin(parent: string, candidate: string): boolean {
  const relative = path.relative(parent, candidate);
  if (relative === '') return true;
  if (path.isAbsolute(relative)) return false;
  return relative !== PARENT_SEGMENT && !relative.startsWith(PARENT_SEGMENT + path.sep);
}

function loadPages(captureDir: string): Envelope[] {
  const pages = (listNames(captureDir) ?? [])
    .filter((name) => isJsonFile(path.join(captureDir, name)))
    .sort(compareCodeUnits);
  if (pages.length === 0) throw new NoCaptureError(`no pages under ${captureDir}`);
  return pages.map((name) => parseEnvelope(path.join(captureDir, name)));
}

function loadNewestEnvelopesFrom(dir: string): Envelope[] {
  if (!isDirectory(dir)) throw new NoCaptureError(`${dir} is not a directory`);

  const names = (listNames(dir) ?? []).filter(
    (name) => isDirectory(path.join(dir, name)) || isJsonFile(path.join(dir, name)),
  );
  if (names.length === 0) throw new NoCaptureError(`no captures under ${dir}`);

  const newest = path.join(dir, names.reduce((best, name) => (compareCodeUnits(name, best) > 0 ? name : best)));
  return isDirectory(newest) ? loadPages(newest) : [parseEnvelope(newest)];
}

export class RawCaptureStore {
  constructor(
    private readonly rawDir: string,
    private readonly staticDir: string,
  ) {}

  loadNewestEnvelopes(source: string | DataSourceEntry): Envelope[] {
    const dir = typeof source === 'string' ? path.join(this.rawDir, source) : this.captureDirFor(source);
    return loadNewestEnvelopesFrom(dir);
  }

  private captureDirFor(source: DataSourceEntry): string {
    const prefix = source.fetcher.outputDirPrefix;
    const candidate = path.resolve(this.staticDir, prefix);
    const normalizedRawDir = path.resolve(this.rawDir);
    if (!isWithin(normalizedRawDir, candidate)) {
      throw new Error(
        `data_source '${source.slug}' output_dir_prefix '${prefix}' resolves outside raw dir '${this.rawDir}'`,
      );
    }
    return candidate;
  }
}
