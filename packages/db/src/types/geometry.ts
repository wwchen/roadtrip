import { customType } from 'drizzle-orm/pg-core';

export const GEOMETRY_SRID = 4326;

const EWKT_SRID_PREFIX = 'SRID=';

declare const geometryFormat: unique symbol;

export type Ewkt = string & { readonly [geometryFormat]: 'ewkt' };

export type Ewkb = string & { readonly [geometryFormat]: 'ewkb' };

export type GeometryLiteral = Ewkt | Ewkb;

export const ewkt = (wkt: string, srid: number = GEOMETRY_SRID): Ewkt =>
  `${EWKT_SRID_PREFIX}${srid};${wkt}` as Ewkt;

export const isEwkt = (value: GeometryLiteral): value is Ewkt =>
  value.startsWith(EWKT_SRID_PREFIX);

export const geometry = customType<{ data: GeometryLiteral; driverData: string }>({
  dataType() {
    return `geometry(Geometry,${GEOMETRY_SRID})`;
  },
  toDriver(value) {
    return value;
  },
  fromDriver(value) {
    return value as Ewkb;
  },
});
