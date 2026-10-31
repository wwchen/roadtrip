import type { Envelope } from '../../etl/envelope.js';
import { fieldOf } from '../../etl/json-decode.js';
import { contentOrNull, jsonArrayOf, jsonObjectOf } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText } from '../../etl/kotlin-number.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import type { GeometrySource } from './geometry-source.js';
import type { NamedPoint } from './named-point.js';

const POINT_GEOMETRY_TYPE = 'Point';
const LONGITUDE_INDEX = 0;
const LATITUDE_INDEX = 1;
const MIN_COORDINATES = 2;
const FEATURES_KEY = 'features';
const PROPERTIES_KEY = 'properties';
const GEOMETRY_KEY = 'geometry';
const TYPE_KEY = 'type';
const COORDINATES_KEY = 'coordinates';

const DEFAULT_NAME_PROPERTIES = ['name', 'Name'];

export class GeoJsonFeaturesSource implements GeometrySource {
  private readonly nameKeys: readonly string[];

  constructor(
    private readonly envelopes: readonly Envelope[],
    nameProperty: string | null = null,
  ) {
    this.nameKeys = nameProperty === null ? DEFAULT_NAME_PROPERTIES : [nameProperty];
  }

  *points(): Iterable<NamedPoint> {
    for (const envelope of this.envelopes) {
      const features = fieldOf(jsonObjectOf(envelope.payload), FEATURES_KEY);
      if (features === undefined) continue;
      for (const feature of jsonArrayOf(features)) {
        const object = jsonObjectOf(feature);
        const properties = fieldOf(object, PROPERTIES_KEY);
        if (properties === undefined) continue;
        const name = this.nameOf(jsonObjectOf(properties));
        if (name === null) continue;
        const geometry = fieldOf(object, GEOMETRY_KEY);
        if (geometry === undefined) continue;
        const shape = jsonObjectOf(geometry);
        if (contentOrNull(fieldOf(shape, TYPE_KEY)) !== POINT_GEOMETRY_TYPE) continue;
        const rawCoordinates = fieldOf(shape, COORDINATES_KEY);
        if (rawCoordinates === undefined) continue;
        const coordinates = jsonArrayOf(rawCoordinates);
        if (coordinates.length < MIN_COORDINATES) continue;
        const longitudeText = contentOrNull(coordinates[LONGITUDE_INDEX]);
        const longitude = longitudeText === null ? null : kotlinDoubleFromText(longitudeText);
        if (longitude === null) continue;
        const latitudeText = contentOrNull(coordinates[LATITUDE_INDEX]);
        const latitude = latitudeText === null ? null : kotlinDoubleFromText(latitudeText);
        if (latitude === null) continue;
        yield { name, latitude, longitude };
      }
    }
  }

  private nameOf(properties: Readonly<Record<string, unknown>>): string | null {
    for (const key of this.nameKeys) {
      const name = nonBlank(contentOrNull(fieldOf(properties, key)));
      if (name !== null) return name;
    }
    return null;
  }
}
