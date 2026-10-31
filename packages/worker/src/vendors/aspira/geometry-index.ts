import type { RunLog } from '../../etl/orchestrator.js';
import type { GeometryPoint } from './geometry-point.js';
import type { GeometrySource } from './geometry-source.js';
import type { NamedPoint } from './named-point.js';
import { normalize } from './normalize.js';

const MIN_GEOMETRY_KEYS = 1;
const MIN_LATITUDE = -90;
const MAX_LATITUDE = 90;
const MIN_LONGITUDE = -180;
const MAX_LONGITUDE = 180;
const EMPTY_KEY = '';

export type GeometrySourceRef = readonly [slug: string, source: GeometrySource];

export function byNormalizedName<T>(rows: Iterable<T>, name: (row: T) => string): Map<string, T> {
  const byName = new Map<string, T>();
  for (const row of rows) {
    const key = normalize(name(row));
    if (key !== EMPTY_KEY && !byName.has(key)) byName.set(key, row);
  }
  return byName;
}

function isOnEarth(point: NamedPoint): boolean {
  return (
    Number.isFinite(point.latitude) &&
    Number.isFinite(point.longitude) &&
    point.latitude >= MIN_LATITUDE &&
    point.latitude <= MAX_LATITUDE &&
    point.longitude >= MIN_LONGITUDE &&
    point.longitude <= MAX_LONGITUDE
  );
}

export function buildGeometryIndex(
  sources: readonly GeometrySourceRef[],
  log: RunLog,
  etlSlug: string,
): Map<string, GeometryPoint> {
  const byName = new Map<string, GeometryPoint>();
  for (const [slug, source] of sources) {
    const before = byName.size;
    const plottable: NamedPoint[] = [];
    let dropped = 0;
    for (const point of source.points()) {
      if (isOnEarth(point)) plottable.push(point);
      else dropped += 1;
    }
    for (const [key, point] of byNormalizedName(plottable, (row) => row.name)) {
      if (!byName.has(key)) byName.set(key, { latitude: point.latitude, longitude: point.longitude, source: slug });
    }
    const added = byName.size - before;
    log.info(
      `${etlSlug}: geometry input slug=${slug} contributed ${added} new keys, ` +
        `dropped ${dropped} off-earth points (total=${byName.size})`,
    );
    if (added === 0) {
      log.warn(
        `${etlSlug}: geometry input slug=${slug} contributed no new keys; either an earlier source already claimed ` +
          'every name, or its declared format / name_property does not match the feed',
      );
    }
  }
  return byName;
}

export function buildGeometryIndexOrFail(
  sources: readonly GeometrySourceRef[],
  log: RunLog,
  etlSlug: string,
): Map<string, GeometryPoint> {
  const index = buildGeometryIndex(sources, log, etlSlug);
  if (index.size < MIN_GEOMETRY_KEYS) {
    throw new Error(`${etlSlug}: geometry index is empty; no declared source yielded a point`);
  }
  return index;
}
