import type { Envelope } from '../../etl/envelope.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { GeometrySourceRef } from './geometry-index.js';
import type { AspiraLeaf } from './leaf.js';

export interface AspiraJoinDto {
  leaves: readonly AspiraLeaf[];
  geomSources: readonly GeometrySourceRef[];
  inventoryEnvelopes: readonly Envelope[];
  dictionaryPayload: JsonObject | null;
  fetchedAt: string;
}
