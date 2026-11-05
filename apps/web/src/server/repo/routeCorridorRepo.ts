import { GEOMETRY_SRID, type Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';

const POLYGONAL_TYPE = 3;

export class CorridorUnavailableError extends Error {
  constructor(cause: unknown) {
    super('corridor_unavailable', { cause });
    this.name = 'CorridorUnavailableError';
  }
}

export async function bufferedCorridor(db: Db, lineGeoJson: string, radiusMeters: number): Promise<string> {
  let rows: { geom_json: string | null }[];
  try {
    rows = await db.execute<{ geom_json: string | null }>(sql`
      SELECT ST_AsGeoJSON(
               ST_CollectionExtract(
                 ST_MakeValid(
                   ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(${lineGeoJson}), ${GEOMETRY_SRID})::geography, ${radiusMeters})::geometry
                 ),
                 ${POLYGONAL_TYPE}
               )
             ) AS geom_json
    `);
  } catch (error) {
    throw new CorridorUnavailableError(error);
  }
  const geometry = rows[0]?.geom_json;
  if (geometry === null || geometry === undefined) throw new CorridorUnavailableError(new Error('route corridor query returned no geometry'));
  return geometry;
}
