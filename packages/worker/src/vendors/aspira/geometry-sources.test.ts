import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import type { RunLog } from '../../etl/orchestrator.js';
import { loadPoiRegistry, type GeometryFormat, type GeometrySourceSpec } from '../../registry/poi-registry.js';
import { BcParksStrapiSource } from '../bcparks/strapi-source.js';
import { ArcGisCentroidSource } from './arcgis-centroid.js';
import { GeoJsonFeaturesSource } from './geojson-features.js';
import { buildGeometryIndex, type GeometrySourceRef } from './geometry-index.js';
import type { GeometryPoint } from './geometry-point.js';
import { geometrySourceForSpec } from './geometry-sources.js';
import type { GeometrySource } from './geometry-source.js';
import { normalize } from './normalize.js';
import { UsCampgroundsCsvSource } from './us-campgrounds-csv.js';

const PRODUCTION_REGISTRY_PATH = fileURLToPath(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url),
);
const TEMP_DIR_PREFIX = 'aspira-geometry-sources-';
const HTTP_OK = 200;
const ETL_SLUG = 'geometry-sources-test';
const SLUG = 'geom';
const PARK = 'Two Jack Lakeside';
const DECOY_PARK = 'Lake Louise';
const LON = -115.49;
const LAT = 51.22;
const PC_ETL_SLUG = 'aspira-pc-campgrounds';
const ACCOMMODATION_SLUG = 'apca-accommodation';
const PLACES_SLUG = 'apca-places';
const CENTROID_LON = -115.57;
const CENTROID_LAT = 51.42;
const SILENT: RunLog = { info: () => undefined, warn: () => undefined };

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

function csvEnvelope(state: string): Envelope {
  const row = [String(LON), String(LAT), '', '', PARK, '', '', '', '', '', '', '', state].join(',');
  return envelopeOf(JSON.stringify(row));
}

function geoJsonEnvelope(propertiesJson: string): Envelope {
  return envelopeOf(
    `{"type":"FeatureCollection","features":[{"type":"Feature","properties":${propertiesJson},` +
      `"geometry":{"type":"Point","coordinates":[${LON},${LAT}]}}]}`,
  );
}

function centroidEnvelope(name: string, longitude: number, latitude: number): Envelope {
  return envelopeOf(
    `{"features":[{"attributes":{"DESC_EN":${JSON.stringify(name)}},"centroid":{"x":${longitude},"y":${latitude}}}]}`,
  );
}

function indexOf(source: GeometrySource): Map<string, GeometryPoint> {
  return buildGeometryIndex([[SLUG, source]], SILENT, ETL_SLUG);
}

function point(): GeometryPoint {
  return { latitude: LAT, longitude: LON, source: SLUG };
}

function forSpec(
  format: GeometryFormat,
  envelopes: Envelope[],
  extras: Partial<GeometrySourceSpec> = {},
): GeometrySource {
  return geometrySourceForSpec({ input: SLUG, format, ...extras }, envelopes);
}

describe('the format dispatch, and the per-source filters only one format honours', () => {
  test('uscampgrounds_csv builds the CSV reader and carries the declared state through', () => {
    const source = forSpec('uscampgrounds_csv', [csvEnvelope('WA')], { state: 'WA' });
    expect(source).toBeInstanceOf(UsCampgroundsCsvSource);
    expect(indexOf(source).get(normalize(PARK))).toEqual(point());

    const filteredOut = forSpec('uscampgrounds_csv', [csvEnvelope('SD')], { state: 'WA' });
    expect(indexOf(filteredOut).get(normalize(PARK))).toBeUndefined();
  });

  test('bcparks_strapi builds the Strapi reader', () => {
    const source = forSpec('bcparks_strapi', [
      envelopeOf(`{"data":[{"protectedAreaName":${JSON.stringify(PARK)},"latitude":"${LAT}","longitude":"${LON}"}]}`),
    ]);
    expect(source).toBeInstanceOf(BcParksStrapiSource);
    expect(indexOf(source).get(normalize(PARK))).toEqual(point());
  });

  test('arcgis_centroids builds the centroid reader', () => {
    const source = forSpec('arcgis_centroids', [centroidEnvelope(PARK, LON, LAT)]);
    expect(source).toBeInstanceOf(ArcGisCentroidSource);
    expect(indexOf(source).get(normalize(PARK))).toEqual(point());
  });

  test('geojson_points carries the declared name_property through', () => {
    const source = forSpec('geojson_points', [geoJsonEnvelope(`{"Name_e":${JSON.stringify(PARK)}}`)], {
      nameProperty: 'Name_e',
    });
    expect(source).toBeInstanceOf(GeoJsonFeaturesSource);
    expect(indexOf(source).get(normalize(PARK))).toEqual(point());
  });

  test('an unknown format is refused rather than silently reading nothing', () => {
    expect(() => forSpec('nonsense' as GeometryFormat, [])).toThrow("declares unknown format 'nonsense'");
  });
});

describe('the GeoJSON name property, which replaces the defaults rather than joining them', () => {
  test('a declared property wins outright and leaves the default keys dead', () => {
    const source = forSpec(
      'geojson_points',
      [geoJsonEnvelope(`{"name":${JSON.stringify(DECOY_PARK)},"Name_e":${JSON.stringify(PARK)}}`)],
      { nameProperty: 'Name_e' },
    );
    const byName = indexOf(source);
    expect(byName.get(normalize(PARK))).toEqual(point());
    expect(byName.get(normalize(DECOY_PARK))).toBeUndefined();
    expect(byName.size).toBe(1);

    const defaultKeyOnly = forSpec('geojson_points', [geoJsonEnvelope(`{"name":${JSON.stringify(DECOY_PARK)}}`)], {
      nameProperty: 'Name_e',
    });
    expect(indexOf(defaultKeyOnly)).toEqual(new Map());
  });

  test('without a name_property the default pair is read, and only that pair', () => {
    expect(indexOf(forSpec('geojson_points', [geoJsonEnvelope(`{"name":${JSON.stringify(PARK)}}`)])).get(normalize(PARK))).toEqual(
      point(),
    );
    expect(indexOf(forSpec('geojson_points', [geoJsonEnvelope(`{"Name_e":${JSON.stringify(PARK)}}`)]))).toEqual(new Map());
  });

  test('the default reader falls back from a blank name to Name', () => {
    expect(indexOf(new GeoJsonFeaturesSource([geoJsonEnvelope(`{"Name":${JSON.stringify(PARK)}}`)])).get(normalize(PARK))).toEqual(
      point(),
    );
    expect(
      indexOf(new GeoJsonFeaturesSource([geoJsonEnvelope(`{"name":"   ","Name":${JSON.stringify(PARK)}}`)])).get(
        normalize(PARK),
      ),
    ).toEqual(point());
  });
});

describe('the shipped Parks Canada policy, read from the real registry', () => {
  test('a park pins to its accommodation point, not its park centroid', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const pcEtl = registry.poiData.flatMap((row) => row.etls).find((etl) => etl.slug === PC_ETL_SLUG);
    const feeds = new Map<string, Envelope[]>([
      [ACCOMMODATION_SLUG, [geoJsonEnvelope(`{"Name_e":${JSON.stringify(PARK)}}`)]],
      [PLACES_SLUG, [centroidEnvelope(PARK, CENTROID_LON, CENTROID_LAT)]],
    ]);
    const sources: GeometrySourceRef[] = (pcEtl?.geometry?.sources ?? []).map((spec) => [
      spec.input,
      geometrySourceForSpec(spec, feeds.get(spec.input) as Envelope[]),
    ]);

    expect(sources.map(([slug]) => slug)).toEqual([ACCOMMODATION_SLUG, PLACES_SLUG]);
    expect(buildGeometryIndex(sources, SILENT, PC_ETL_SLUG).get(normalize(PARK))).toEqual({
      latitude: LAT,
      longitude: LON,
      source: ACCOMMODATION_SLUG,
    });
  });
});
