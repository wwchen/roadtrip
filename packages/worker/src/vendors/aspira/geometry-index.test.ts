import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { isWideNumber, parseEnvelope, type Envelope } from '../../etl/envelope.js';
import type { RunLog } from '../../etl/orchestrator.js';
import { GeoJsonFeaturesSource } from './geojson-features.js';
import { buildGeometryIndex, buildGeometryIndexOrFail, byNormalizedName, type GeometrySourceRef } from './geometry-index.js';
import type { GeometryPoint } from './geometry-point.js';
import { normalize } from './normalize.js';

const TEMP_DIR_PREFIX = 'aspira-geometry-index-';
const HTTP_OK = 200;
const ETL_SLUG = 'geometry-index-test';
const ACCOMMODATION = 'apca-accommodation';
const PLACES = 'apca-places';

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
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"test://geom","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function rawGeoJson(name: string, longitude: string, latitude: string): Envelope {
  return envelopeOf(
    `{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":${JSON.stringify(name)}},` +
      `"geometry":{"type":"Point","coordinates":[${longitude},${latitude}]}}]}`,
  );
}

function geoJson(name: string, longitude: number, latitude: number): Envelope {
  return rawGeoJson(name, String(longitude), String(latitude));
}

function silentLog(): RunLog {
  return { info: () => undefined, warn: () => undefined };
}

interface CapturedLog {
  log: RunLog;
  info: string[];
  warn: string[];
}

function capturingLog(): CapturedLog {
  const info: string[] = [];
  const warn: string[] = [];
  return { log: { info: (line) => info.push(line), warn: (line) => warn.push(line) }, info, warn };
}

function indexOf(sources: GeometrySourceRef[], log: RunLog = silentLog()): Map<string, GeometryPoint> {
  return buildGeometryIndex(sources, log, ETL_SLUG);
}

describe('the geometry index, whose source order is preference order', () => {
  test('the earlier source wins a name both sources carry', () => {
    const index = indexOf([
      [ACCOMMODATION, new GeoJsonFeaturesSource([geoJson('Two Jack Lakeside', -115.49, 51.22)])],
      [PLACES, new GeoJsonFeaturesSource([geoJson('Two Jack Lakeside Campground', -115, 51)])],
    ]);

    expect(index.get(normalize('Two Jack Lakeside'))).toEqual({
      latitude: 51.22,
      longitude: -115.49,
      source: ACCOMMODATION,
    });
  });

  test('a second point from the same source does not displace the first either', () => {
    const index = indexOf([
      [
        ACCOMMODATION,
        new GeoJsonFeaturesSource([
          geoJson('Two Jack Lakeside', -115.49, 51.22),
          geoJson('Two Jack Lakeside Campground', -115, 51),
        ]),
      ],
    ]);

    expect(index.get(normalize('Two Jack Lakeside'))).toEqual({
      latitude: 51.22,
      longitude: -115.49,
      source: ACCOMMODATION,
    });
    expect(index.size).toBe(1);
  });

  test('a name only the later source carries is still indexed, tagged with that source', () => {
    const index = indexOf([
      [ACCOMMODATION, new GeoJsonFeaturesSource([geoJson('Two Jack Lakeside', -115.49, 51.22)])],
      [PLACES, new GeoJsonFeaturesSource([geoJson('Banff National Park of Canada', -115.57, 51.18)])],
    ]);

    expect(index.get(normalize('Banff National Park of Canada'))).toEqual({
      latitude: 51.18,
      longitude: -115.57,
      source: PLACES,
    });
  });

  test('a feature whose name normalizes to nothing is not indexed', () => {
    expect(indexOf([['only', new GeoJsonFeaturesSource([geoJson('park', -1, 2)])]])).toEqual(new Map());
  });

  test('a declared source that contributes no keys warns, naming the slug', () => {
    const captured = capturingLog();
    const index = indexOf(
      [
        [ACCOMMODATION, new GeoJsonFeaturesSource([geoJson('Two Jack Lakeside', -115.49, 51.22)], 'Name_fr')],
        [PLACES, new GeoJsonFeaturesSource([geoJson('Banff', -115.57, 51.18)])],
      ],
      captured.log,
    );

    expect([...index.keys()]).toEqual([normalize('Banff')]);
    expect(captured.warn).toHaveLength(1);
    expect(captured.warn[0]).toContain(`slug=${ACCOMMODATION}`);
  });

  test('a point at exactly the coordinate bounds is kept', () => {
    const index = indexOf([
      [
        'edges',
        new GeoJsonFeaturesSource([
          rawGeoJson('North Pole Site', '180.0', '90.0'),
          rawGeoJson('South Pole Site', '-180.0', '-90.0'),
        ]),
      ],
    ]);

    expect(index).toEqual(
      new Map([
        [normalize('North Pole Site'), { latitude: 90, longitude: 180, source: 'edges' }],
        [normalize('South Pole Site'), { latitude: -90, longitude: -180, source: 'edges' }],
      ]),
    );
  });

  test('non-finite and off-globe coordinates are dropped and counted in the source log line', () => {
    const captured = capturingLog();
    const index = indexOf(
      [
        [
          'poisoned',
          new GeoJsonFeaturesSource([
            rawGeoJson('Poison Park Site', '-120.5', '"NaN"'),
            rawGeoJson('Inf Park Site', '"Infinity"', '47.1'),
            rawGeoJson('Negative Inf Site', '"-Infinity"', '47.1'),
            rawGeoJson('Off Globe Site', '-120.5', '91.0'),
            rawGeoJson('Under Globe Site', '-120.5', '-90.5'),
            rawGeoJson('Wrapped Site', '181.0', '47.1'),
            rawGeoJson('Unwrapped Site', '-181.0', '47.1'),
            rawGeoJson('Real Site', '-120.5', '47.1'),
          ]),
        ],
      ],
      captured.log,
    );

    expect(index).toEqual(new Map([[normalize('Real Site'), { latitude: 47.1, longitude: -120.5, source: 'poisoned' }]]));
    expect(captured.info).toHaveLength(1);
    expect(captured.info[0]).toContain('dropped 7 off-earth points');
    expect(captured.info[0]).toContain('contributed 1 new keys');
  });

  test('a coordinate the vendor spelled so it arrives as a carrier is still read as a number', () => {
    const envelope = rawGeoJson('Carrier Site', '-120.50', '47.10');
    const coordinates = (
      (envelope.payload as { features: { geometry: { coordinates: unknown[] } }[] }).features[0] as {
        geometry: { coordinates: unknown[] };
      }
    ).geometry.coordinates;
    expect(coordinates.map(isWideNumber)).toEqual([true, true]);

    expect(indexOf([['carriers', new GeoJsonFeaturesSource([envelope])]])).toEqual(
      new Map([[normalize('Carrier Site'), { latitude: 47.1, longitude: -120.5, source: 'carriers' }]]),
    );
  });

  test('an exponent-spelled carrier coordinate out of range is still rejected', () => {
    expect(indexOf([['carriers', new GeoJsonFeaturesSource([rawGeoJson('Big Site', '1e2', '1e2')])]])).toEqual(new Map());
    expect(indexOf([['carriers', new GeoJsonFeaturesSource([rawGeoJson('Fine Site', '1e2', '4.71e1')])]])).toEqual(
      new Map([[normalize('Fine Site'), { latitude: 47.1, longitude: 100, source: 'carriers' }]]),
    );
  });
});

describe('buildOrFail, which refuses to report success on an index that can pin nothing', () => {
  test('an index with no keys fails, naming the etl slug', () => {
    expect(() => buildGeometryIndexOrFail([], silentLog(), ETL_SLUG)).toThrow(
      `${ETL_SLUG}: geometry index is empty; no declared source yielded a point`,
    );
    expect(() =>
      buildGeometryIndexOrFail([['only', new GeoJsonFeaturesSource([geoJson('park', -1, 2)])]], silentLog(), ETL_SLUG),
    ).toThrow('geometry index is empty');
  });

  test('a single key is enough', () => {
    const index = buildGeometryIndexOrFail(
      [['only', new GeoJsonFeaturesSource([geoJson('Banff', -115.57, 51.18)])]],
      silentLog(),
      ETL_SLUG,
    );
    expect(index.size).toBe(1);
  });
});

describe('byNormalizedName, the one keying rule the point and metadata indexes share', () => {
  const row = (name: string, tag: string) => ({ name, tag });

  test('the first writer of a normalized name keeps it', () => {
    const index = byNormalizedName([row('Alice Lake', 'first'), row('Alice Lake Campground', 'second')], (r) => r.name);
    expect([...index.keys()]).toEqual(['alice lake']);
    expect(index.get('alice lake')?.tag).toBe('first');
  });

  test('a row whose name normalizes to nothing is dropped, not stored under the empty key', () => {
    const index = byNormalizedName([row('park', 'cruft'), row('', 'blank'), row('   ', 'spaces')], (r) => r.name);
    expect(index).toEqual(new Map());
  });

  test('keys come out in first-seen order, which is what the matcher tie-break resolves by', () => {
    const index = byNormalizedName([row('Beta Lake', 'b'), row('Alpha Lake', 'a'), row('Beta Lake', 'b2')], (r) => r.name);
    expect([...index.keys()]).toEqual(['beta lake', 'alpha lake']);
  });
});
