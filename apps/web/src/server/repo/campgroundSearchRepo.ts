import { GEOMETRY_SRID, POI_TYPES, type Db, type PoiType } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { causeChain, isGeometryParseFault, isTopologyFault } from './faults';
import { idString } from './ids';

const CAMPGROUND_POI_TYPE: PoiType = POI_TYPES[0];
const TOPOLOGY_FAULT_EMPTY_RESULT = 'GEOS topology fault in a polygon query, returning empty:';
const INVALID_BOUNDARY_MESSAGE = 'boundary is not valid GeoJSON geometry';
const PROVIDER_SEPARATOR = sql`, `;

export interface BoundarySearchResult {
  poiIds: string[];
  totalInBoundary: number;
  totalMatching: number;
  truncated: boolean;
}

export class InvalidBoundaryError extends Error {
  constructor(cause: unknown) {
    super(INVALID_BOUNDARY_MESSAGE, { cause });
    this.name = 'InvalidBoundaryError';
  }
}

interface SearchRow extends Record<string, unknown> {
  poi_id: unknown;
  passes: boolean;
  total_in_boundary: number;
  total_passing: number;
}

function assertBoundaryIsJson(boundaryGeoJson: string): void {
  try {
    JSON.parse(boundaryGeoJson);
  } catch (error) {
    throw new InvalidBoundaryError(error);
  }
}

const emptyResult = (): BoundarySearchResult => ({
  poiIds: [],
  totalInBoundary: 0,
  totalMatching: 0,
  truncated: false,
});

export async function searchWithinBoundary(
  db: Db,
  boundaryGeoJson: string,
  limit: number,
  providers: readonly string[],
): Promise<BoundarySearchResult> {
  if (providers.length === 0) return emptyResult();
  assertBoundaryIsJson(boundaryGeoJson);

  const providerList = sql.join(
    providers.map((provider) => sql`${provider}`),
    PROVIDER_SEPARATOR,
  );

  let rows: SearchRow[];
  try {
    rows = await db.execute<SearchRow>(sql`
      WITH boundary AS (
        SELECT ST_SetSRID(ST_GeomFromGeoJSON(${boundaryGeoJson}), ${GEOMETRY_SRID}) AS poly
      ),
      inside AS (
        SELECT p.id AS poi_id,
               ST_Distance(
                 ST_Centroid(p.geom)::geography,
                 ST_Centroid(boundary.poly)::geography
               ) AS dist,
               true AS passes
        FROM pois p
        JOIN poi_campgrounds pc ON pc.poi_id = p.id
        JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
        LEFT JOIN LATERAL campground_site_summary(cg.id) s ON true,
        boundary
        WHERE p.deleted_at IS NULL
          AND p.poi_type = ${CAMPGROUND_POI_TYPE}
          AND cg.data_provider IN (${providerList})
          AND p.geom && boundary.poly
          AND ST_Within(ST_Centroid(p.geom), boundary.poly)
      )
      SELECT poi_id,
             passes,
             (COUNT(*) OVER ())::int AS total_in_boundary,
             (COUNT(*) FILTER (WHERE passes) OVER ())::int AS total_passing
      FROM inside
      ORDER BY passes DESC, dist ASC, poi_id ASC
      LIMIT ${limit}
    `);
  } catch (error) {
    if (isGeometryParseFault(error)) throw new InvalidBoundaryError(error);
    if (!isTopologyFault(error)) throw error;
    console.warn(TOPOLOGY_FAULT_EMPTY_RESULT, causeChain(error));
    return emptyResult();
  }

  const passing = rows.filter((row) => row.passes);
  const first = rows[0];
  const totalMatching = first?.total_passing ?? 0;
  return {
    poiIds: passing.map((row) => idString(row.poi_id)),
    totalInBoundary: first?.total_in_boundary ?? 0,
    totalMatching,
    truncated: totalMatching > passing.length,
  };
}
