import type { Envelope } from '../../etl/envelope.js';
import { fieldOf } from '../../etl/json-decode.js';
import { contentOrNull, jsonArrayOf, jsonObjectOf } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText } from '../../etl/kotlin-number.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import type { GeometrySource } from './geometry-source.js';
import type { NamedPoint } from './named-point.js';

const CENTROID_NAME_ATTRIBUTE = 'DESC_EN';
const FEATURES_KEY = 'features';
const ATTRIBUTES_KEY = 'attributes';
const CENTROID_KEY = 'centroid';
const LONGITUDE_KEY = 'x';
const LATITUDE_KEY = 'y';

export class ArcGisCentroidSource implements GeometrySource {
  constructor(private readonly envelopes: readonly Envelope[]) {}

  *points(): Iterable<NamedPoint> {
    for (const envelope of this.envelopes) {
      const features = fieldOf(jsonObjectOf(envelope.payload), FEATURES_KEY);
      if (features === undefined) continue;
      for (const feature of jsonArrayOf(features)) {
        const object = jsonObjectOf(feature);
        const attributes = fieldOf(object, ATTRIBUTES_KEY);
        if (attributes === undefined) continue;
        const name = nonBlank(contentOrNull(fieldOf(jsonObjectOf(attributes), CENTROID_NAME_ATTRIBUTE)));
        if (name === null) continue;
        const rawCentroid = fieldOf(object, CENTROID_KEY);
        if (rawCentroid === undefined) continue;
        const centroid = jsonObjectOf(rawCentroid);
        const longitudeText = contentOrNull(fieldOf(centroid, LONGITUDE_KEY));
        const longitude = longitudeText === null ? null : kotlinDoubleFromText(longitudeText);
        if (longitude === null) continue;
        const latitudeText = contentOrNull(fieldOf(centroid, LATITUDE_KEY));
        const latitude = latitudeText === null ? null : kotlinDoubleFromText(latitudeText);
        if (latitude === null) continue;
        yield { name, latitude, longitude };
      }
    }
  }
}
