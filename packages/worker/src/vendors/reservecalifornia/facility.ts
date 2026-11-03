import type { JsonObject } from '../../etl/json-decode.js';

const NOT_BOOKABLE_TYPE = 2n;

export interface ReserveCaliforniaFacility {
  facilityId: bigint;
  placeId: bigint | null;
  name: string | null;
  facilityTypeNew: bigint | null;
  facilityBehaviourType: bigint | null;
  allowWebBooking: boolean | null;
  raw: JsonObject;
}

export function isStandardBookable(facility: ReserveCaliforniaFacility): boolean {
  return (
    facility.facilityTypeNew !== NOT_BOOKABLE_TYPE &&
    facility.facilityBehaviourType !== NOT_BOOKABLE_TYPE &&
    facility.allowWebBooking !== false
  );
}
