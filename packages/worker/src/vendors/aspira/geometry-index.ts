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
const AMBIGUOUS_SPREAD_DEGREES = 0.2;

export type GeometrySourceRef = readonly [slug: string, source: GeometrySource];

export function byNormalizedName<T>(rows: Iterable<T>, name: (row: T) => string): Map<string, T> {
  const byName = new Map<string, T>();
  for (const row of rows) {
    const key = normalize(name(row));
    if (key !== EMPTY_KEY && !byName.has(key)) byName.set(key, row);
  }
  return byName;
}

function isFarFrom(point: NamedPoint, other: NamedPoint): boolean {
  return (
    Math.abs(point.latitude - other.latitude) > AMBIGUOUS_SPREAD_DEGREES ||
    Math.abs(point.longitude - other.longitude) > AMBIGUOUS_SPREAD_DEGREES
  );
}

function ambiguousNames(points: readonly NamedPoint[]): Map<string, string[]> {
  const byKey = new Map<string, NamedPoint[]>();
  for (const point of points) {
    const key = normalize(point.name);
    if (key === EMPTY_KEY) continue;
    const group = byKey.get(key);
    if (group === undefined) byKey.set(key, [point]);
    else group.push(point);
  }
  const ambiguous = new Map<string, string[]>();
  for (const [key, group] of byKey) {
    const first = group[0] as NamedPoint;
    if (group.some((point) => isFarFrom(point, first))) {
      ambiguous.set(key, [...new Set(group.map((point) => point.name))]);
    }
  }
  return ambiguous;
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
    const ambiguous = ambiguousNames(plottable);
    for (const [key, point] of byNormalizedName(plottable, (row) => row.name)) {
      if (ambiguous.has(key)) continue;
      if (!byName.has(key)) byName.set(key, { latitude: point.latitude, longitude: point.longitude, source: slug });
    }
    if (ambiguous.size > 0) {
      const listed = [...ambiguous].map(([key, names]) => `${key} <- [${names.join(', ')}]`).join('; ');
      log.warn(
        `${etlSlug}: geometry input slug=${slug} left ${ambiguous.size} names unindexed because points sharing them ` +
          `lie more than ${AMBIGUOUS_SPREAD_DEGREES} degrees apart: ${listed}`,
      );
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
