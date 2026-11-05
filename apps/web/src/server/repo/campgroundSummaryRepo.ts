import { amenitiesShape, locationShape, managementShape, metadataShape, type Db } from '@roadtrip/db';
import type { CampgroundAmenity, CampgroundMetadata } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { createColumnDecoder } from './columnDecoder';

const ID_SEPARATOR = sql`, `;

export interface CampgroundSummaryRow {
  poiId: bigint;
  campgroundId: bigint;
  name: string;
  region: string | null;
  agency: string | null;
  lng: number;
  lat: number;
  metadata: CampgroundMetadata | null;
  amenities: CampgroundAmenity[];
  siteCounts: Record<string, number>;
  siteTotal: number;
  maxPeople: number | null;
}

export async function findCampgroundSummaries(
  db: Db,
  poiIds: readonly bigint[],
  providers: readonly string[],
): Promise<CampgroundSummaryRow[]> {
  if (poiIds.length === 0 || providers.length === 0) return [];
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT p.id AS poi_id, cg.id AS campground_id, cg.name,
           ST_X(ST_PointOnSurface(p.geom)) AS lng, ST_Y(ST_PointOnSurface(p.geom)) AS lat,
           cg.location, cg.management, cg.metadata, cg.amenities,
           s.site_total, s.site_counts, s.max_people
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    LEFT JOIN LATERAL campground_site_summary(cg.id) s ON true
    WHERE pc.poi_id IN (${sql.join(poiIds.map((id) => sql`${id}`), ID_SEPARATOR)})
      AND cg.data_provider IN (${sql.join(providers.map((p) => sql`${p}`), ID_SEPARATOR)})
      AND cg.deleted_at IS NULL
      AND p.deleted_at IS NULL
    ORDER BY cg.id
  `);
  const byPoi = new Map<bigint, CampgroundSummaryRow>();
  for (const row of rows) {
    const poiId = BigInt(row.poi_id as string);
    if (byPoi.has(poiId)) continue;
    const decoder = createColumnDecoder();
    byPoi.set(poiId, {
      poiId,
      campgroundId: BigInt(row.campground_id as string),
      name: row.name as string,
      region: decoder.decode('location', locationShape, row.location)?.region ?? null,
      agency: decoder.decode('management', managementShape, row.management)?.agency ?? null,
      lng: Number(row.lng),
      lat: Number(row.lat),
      metadata: decoder.decode('metadata', metadataShape, row.metadata),
      amenities: decoder.decode('amenities', amenitiesShape, row.amenities) ?? [],
      siteCounts: (row.site_counts as Record<string, number> | null) ?? {},
      siteTotal: (row.site_total as number | null) ?? 0,
      maxPeople: row.max_people as number | null,
    });
  }
  return poiIds.flatMap((id) => byPoi.get(id) ?? []);
}
