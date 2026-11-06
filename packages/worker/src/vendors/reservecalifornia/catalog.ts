import type { ReserveCaliforniaFacility } from './facility.js';
import type { ReserveCaliforniaGridCatalog } from './grid-catalog.js';
import type { ReserveCaliforniaPlace } from './place.js';

export interface ReserveCaliforniaCatalog {
  places: ReadonlyMap<bigint, ReserveCaliforniaPlace>;
  facilities: ReadonlyMap<bigint, ReserveCaliforniaFacility>;
  grids: ReadonlyMap<bigint, ReserveCaliforniaGridCatalog>;
}
