import { ewkt, GEOMETRY_SRID, pois, type PoiType } from '@roadtrip/db';
import { eq, sql } from 'drizzle-orm';
import { BULK_CHUNK_SIZE, chunked, distinctBy, type Executor } from './bulk.js';

export interface PoiGeometryUpdate {
  poiId: bigint;
  longitude: number;
  latitude: number;
}

export function assertFiniteCoordinates(label: string, longitude: number, latitude: number): void {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    throw new RangeError(`${label}: coordinates must be finite, got longitude=${longitude} latitude=${latitude}`);
  }
}

const pointFrom = (longitude: number, latitude: number) =>
  sql`ST_SetSRID(ST_MakePoint(${longitude}::float8, ${latitude}::float8), ${GEOMETRY_SRID})`;

export async function insertPoi(
  tx: Executor,
  poiType: PoiType,
  longitude: number,
  latitude: number,
): Promise<bigint> {
  assertFiniteCoordinates(`${poiType} poi`, longitude, latitude);
  const [row] = await tx
    .insert(pois)
    .values({ poiType, geom: ewkt(`POINT(${longitude} ${latitude})`) })
    .returning({ id: pois.id });
  return row!.id;
}

export async function updatePoiGeometry(
  tx: Executor,
  poiId: bigint,
  longitude: number,
  latitude: number,
): Promise<void> {
  assertFiniteCoordinates(`poi ${poiId}`, longitude, latitude);
  await tx
    .update(pois)
    .set({ geom: pointFrom(longitude, latitude), updatedAt: sql`now()`, deletedAt: null })
    .where(eq(pois.id, poiId));
}

export async function bulkUpdatePoiGeometry(tx: Executor, rows: readonly PoiGeometryUpdate[]): Promise<void> {
  if (rows.length === 0) return;
  for (const row of rows) assertFiniteCoordinates(`poi ${row.poiId}`, row.longitude, row.latitude);
  const deduped = distinctBy(rows, (row) => String(row.poiId));
  for (const chunk of chunked(deduped, BULK_CHUNK_SIZE)) {
    const values = sql.join(
      chunk.map((row) => sql`(${row.poiId}::bigint, ${row.longitude}::float8, ${row.latitude}::float8)`),
      sql`, `,
    );
    await tx.execute(sql`
      UPDATE pois AS p
         SET geom = ST_SetSRID(ST_MakePoint(v.lon, v.lat), ${GEOMETRY_SRID}),
             updated_at = now(),
             deleted_at = NULL
        FROM (VALUES ${values}) AS v(id, lon, lat)
       WHERE p.id = v.id
    `);
  }
}
