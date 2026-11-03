import type { Envelope } from '../../etl/envelope.js';
import type { GeometryFormat, GeometrySourceSpec } from '../../registry/poi-registry.js';
import { BcParksStrapiSource } from '../bcparks/strapi-source.js';
import { ArcGisCentroidSource } from './arcgis-centroid.js';
import { GeoJsonFeaturesSource } from './geojson-features.js';
import type { GeometrySource } from './geometry-source.js';
import { UsCampgroundsCsvSource } from './us-campgrounds-csv.js';

type GeometrySourceFactory = (spec: GeometrySourceSpec, envelopes: readonly Envelope[]) => GeometrySource;

const GEOMETRY_SOURCE_FACTORIES: Readonly<Record<GeometryFormat, GeometrySourceFactory>> = {
  uscampgrounds_csv: (spec, envelopes) => new UsCampgroundsCsvSource(envelopes, spec.state ?? null),
  bcparks_strapi: (_spec, envelopes) => new BcParksStrapiSource(envelopes),
  arcgis_centroids: (_spec, envelopes) => new ArcGisCentroidSource(envelopes),
  geojson_points: (spec, envelopes) => new GeoJsonFeaturesSource(envelopes, spec.nameProperty ?? null),
};

export function geometrySourceForSpec(spec: GeometrySourceSpec, envelopes: readonly Envelope[]): GeometrySource {
  const factory = Object.hasOwn(GEOMETRY_SOURCE_FACTORIES, spec.format)
    ? GEOMETRY_SOURCE_FACTORIES[spec.format]
    : undefined;
  if (factory === undefined) throw new Error(`geometry source input '${spec.input}' declares unknown format '${spec.format}'`);
  return factory(spec, envelopes);
}
