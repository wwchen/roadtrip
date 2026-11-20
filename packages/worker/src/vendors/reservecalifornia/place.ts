import type { JsonObject } from '../../etl/json-decode.js';

export interface ReserveCaliforniaPlace {
  placeId: bigint;
  name: string;
  latitude: number;
  longitude: number;
  facilityIds: bigint[];
  unitTypeNamesByFacilityId: ReadonlyMap<bigint, ReadonlyMap<bigint, string>>;
  imageUrl: string | null;
  description: string | null;
  amenities: string[];
  activities: string[];
  raw: JsonObject;
}
