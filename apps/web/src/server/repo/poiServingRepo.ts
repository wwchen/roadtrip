import { CAMPGROUND_POI_TYPE, GEOMETRY_SRID, type Db } from '@roadtrip/db';
import { sql, type SQL } from 'drizzle-orm';
import type { Bbox } from '../poiRequest';

const SAMPLE_GRID_DIM = 10;
const MIN_PER_CATEGORY_ALLOCATION = 50;
const LIST_SEPARATOR = sql`, `;

export interface PoiRow {
  id: bigint;
  category: string;
  subcategory: string | null;
  agency: string | null;
  lng: number;
  lat: number;
}

export interface ViewportQuery {
  bbox: Bbox;
  categories: readonly string[];
  limit: number;
  providers: readonly string[];
}

export interface PoiResult {
  rows: PoiRow[];
  truncated: boolean;
}

const list = (values: readonly string[]) => sql.join(values.map((value) => sql`${value}`), LIST_SEPARATOR);

export function campgroundProviderFilter(providers: readonly string[]): SQL {
  if (providers.length === 0) return sql`AND p.poi_type <> ${CAMPGROUND_POI_TYPE}`;
  return sql`AND (p.poi_type <> ${CAMPGROUND_POI_TYPE} OR cg.data_provider IN (${list(providers)}))`;
}

const envelope = (bbox: Bbox) => sql`ST_MakeEnvelope(${bbox.west}, ${bbox.south}, ${bbox.east}, ${bbox.north}, ${GEOMETRY_SRID})`;

async function countByCategory(db: Db, query: ViewportQuery): Promise<Map<string, number>> {
  const rows = await db.execute<{ category: string; n: number | string }>(sql`
    SELECT p.poi_type AS category, COUNT(*) AS n
    FROM pois p
    LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
    LEFT JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
    WHERE p.deleted_at IS NULL
      AND p.poi_type IN (${list(query.categories)})
      AND p.geom && ${envelope(query.bbox)}
      ${campgroundProviderFilter(query.providers)}
    GROUP BY p.poi_type
  `);
  return new Map(rows.map((row) => [row.category, Number(row.n)]));
}

export function allocateBudget(present: ReadonlyMap<string, number>, cap: number): Map<string, number> {
  const baseline = new Map([...present].map(([category, n]) => [category, Math.min(n, MIN_PER_CATEGORY_ALLOCATION)]));
  const baselineSum = [...baseline.values()].reduce((a, b) => a + b, 0);
  if (baselineSum >= cap) return baseline;
  const remaining = cap - baselineSum;
  const excess = new Map([...present].map(([category, n]) => [category, Math.max(n - (baseline.get(category) ?? 0), 0)]));
  const excessTotal = [...excess.values()].reduce((a, b) => a + b, 0);
  if (excessTotal === 0) return baseline;
  return new Map(
    [...present.keys()].map((category) => [
      category,
      (baseline.get(category) ?? 0) + Math.floor(((excess.get(category) ?? 0) * remaining) / excessTotal),
    ]),
  );
}

async function fetchSampled(db: Db, query: ViewportQuery, allocation: Map<string, number>): Promise<PoiRow[]> {
  const { bbox } = query;
  const dx = (bbox.east - bbox.west) / SAMPLE_GRID_DIM;
  const dy = (bbox.north - bbox.south) / SAMPLE_GRID_DIM;
  const perCategory = [...allocation].map(
    ([category, take]) => sql`(SELECT id, category, subcategory, agency, lng, lat FROM (
      SELECT p.id,
             p.poi_type AS category,
             cg.kind AS subcategory,
             cg.management->>'agency' AS agency,
             ST_X(ST_Centroid(p.geom)) AS lng,
             ST_Y(ST_Centroid(p.geom)) AS lat,
             row_number() OVER (
               PARTITION BY
                 floor((ST_X(ST_Centroid(p.geom)) - ${bbox.west}) / ${dx})::int,
                 floor((ST_Y(ST_Centroid(p.geom)) - ${bbox.south}) / ${dy})::int
               ORDER BY p.id
             ) AS rn
      FROM pois p
      LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
      LEFT JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
      WHERE p.deleted_at IS NULL
        AND p.poi_type = ${category}
        AND p.geom && ${envelope(bbox)}
        ${campgroundProviderFilter(query.providers)}
    ) sub ORDER BY rn ASC, id ASC LIMIT ${Math.max(take, 1)})`,
  );
  const rows = await db.execute<{ id: string | bigint; category: string; subcategory: string | null; agency: string | null; lng: number; lat: number }>(
    sql.join(perCategory, sql`\nUNION ALL\n`),
  );
  return rows.map((row) => ({
    id: BigInt(row.id),
    category: row.category,
    subcategory: row.subcategory,
    agency: row.agency,
    lng: Number(row.lng),
    lat: Number(row.lat),
  }));
}

export async function fetchPois(db: Db, query: ViewportQuery): Promise<PoiResult> {
  if (query.categories.length === 0) return { rows: [], truncated: false };
  const counts = await countByCategory(db, query);
  const present = new Map(query.categories.filter((c) => (counts.get(c) ?? 0) > 0).map((c) => [c, counts.get(c) ?? 0]));
  if (present.size === 0) return { rows: [], truncated: false };
  const rawTotal = [...present.values()].reduce((a, b) => a + b, 0);
  const rows = await fetchSampled(db, query, allocateBudget(present, query.limit));
  return { rows, truncated: rows.length < rawTotal };
}
