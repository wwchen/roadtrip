import type { ReserveCaliforniaUnit } from './unit.js';

export interface ReserveCaliforniaGridCatalog {
  facilityId: bigint;
  placeId: bigint | null;
  facilityName: string | null;
  units: ReserveCaliforniaUnit[];
}
