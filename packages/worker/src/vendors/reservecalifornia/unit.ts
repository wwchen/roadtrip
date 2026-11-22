import type { JsonObject } from '../../etl/json-decode.js';

export interface ReserveCaliforniaUnit {
  unitId: bigint;
  name: string | null;
  unitTypeId: bigint | null;
  raw: JsonObject;
}
