import { customType } from 'drizzle-orm/pg-core';

export const GEOMETRY_SRID = 4326;

export type Ewkt = string;

export const geometry = customType<{ data: Ewkt; driverData: Ewkt }>({
  dataType() {
    return `geometry(Geometry,${GEOMETRY_SRID})`;
  },
  toDriver(value) {
    return value;
  },
  fromDriver(value) {
    return value;
  },
});
