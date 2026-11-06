import type { Envelope } from '../../etl/envelope.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { GeometrySourceRef } from '../aspira/geometry-index.js';
import type { AspiraLeaf } from '../aspira/leaf.js';
import type { BcParksStrapiRow } from './strapi-source.js';

export interface BcParksCampgroundsDto {
  leaves: readonly AspiraLeaf[];
  strapiRows: readonly BcParksStrapiRow[];
  geomSources: readonly GeometrySourceRef[];
  inventoryEnvelopes: readonly Envelope[];
  dictionaryPayload: JsonObject | null;
}
