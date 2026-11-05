import type { Db } from '@roadtrip/db';
import { sql, type SQL } from 'drizzle-orm';
import { campgroundProviderFilter } from './poiServingRepo';

const TERM_SEPARATOR = /\s+/;
const LIST_SEPARATOR = sql`, `;
const AND = sql` AND `;

export interface PoiSearchHit {
  id: bigint;
  name: string;
  category: string;
  region: string | null;
  lng: number;
  lat: number;
}

const escapeLike = (text: string) => text.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_');

export async function searchPoisByName(
  db: Db,
  query: string,
  categories: readonly string[],
  limit: number,
  providers: readonly string[],
): Promise<PoiSearchHit[]> {
  const terms = query.split(TERM_SEPARATOR).map((term) => term.trim()).filter((term) => term.length > 0);
  if (terms.length === 0) return [];
  const termPredicate = sql.join(
    terms.map((term) => sql`name ILIKE ${`%${escapeLike(term)}%`} ESCAPE '\\'`),
    AND,
  );
  const distinct = [...new Set(categories)];
  const categoryPredicate: SQL =
    distinct.length === 0 ? sql`` : sql`AND category IN (${sql.join(distinct.map((c) => sql`${c}`), LIST_SEPARATOR)})`;
  const prefix = `${escapeLike(terms[0] ?? '')}%`;
  const rows = await db.execute<{ id: string; name: string; category: string; region: string | null; lng: number; lat: number }>(sql`
    SELECT id, name, category, region, ST_X(ST_PointOnSurface(geom)) AS lng, ST_Y(ST_PointOnSurface(geom)) AS lat
    FROM (
      SELECT p.id,
             p.geom,
             p.poi_type AS category,
             COALESCE(cg.name, ts.common_site_name, pf.name) AS name,
             COALESCE(cg.location->>'region', ts.region, pf.region) AS region
      FROM pois p
      LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
      LEFT JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
      LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
      LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id
      LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
      LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id
      WHERE p.deleted_at IS NULL
        ${campgroundProviderFilter(providers)}
    ) catalog
    WHERE ${termPredicate}
      ${categoryPredicate}
    ORDER BY (name ILIKE ${prefix} ESCAPE '\\') DESC, length(name) ASC, name ASC
    LIMIT ${limit}
  `);
  return rows.map((row) => ({
    id: BigInt(row.id),
    name: row.name,
    category: row.category,
    region: row.region,
    lng: Number(row.lng),
    lat: Number(row.lat),
  }));
}
