import {
  amenitiesShape,
  campgrounds,
  contactShape,
  locationShape,
  pois,
  priceShape,
  type Db,
} from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import type { CampgroundDetailDto, CampgroundSiteSummaryDto, CampgroundSummaryDto } from '../dto';
import { createColumnDecoder } from './columnDecoder';
import { idString } from './ids';

const LOCATION_COLUMN = 'location';
const AMENITIES_COLUMN = 'amenities';
const PRICE_COLUMN = 'price';
const CONTACT_COLUMN = 'contact';
const ID_SEPARATOR = sql`, `;

interface DetailRow extends Record<string, unknown> {
  poi_id: unknown;
  id: unknown;
  data_provider: string;
  data_provider_ref: string;
  name: string;
  parent_name: string | null;
  kind: string | null;
  status: string | null;
  status_description: string | null;
  short_description: string | null;
  medium_description: string | null;
  long_description: string | null;
  reservation_url: string | null;
  big_rig_friendly: boolean | null;
  has_pull_through_sites: boolean | null;
  max_rv_length: number | null;
  max_trailer_length: number | null;
  location: unknown;
  amenities: unknown;
  price: unknown;
  contact: unknown;
}

interface SummaryRow extends Record<string, unknown> {
  poi_id: unknown;
  id: unknown;
  name: string;
  lng: number;
  lat: number;
  location: unknown;
  amenities: unknown;
  site_total: number | null;
  site_counts: unknown;
  max_people: number | null;
}

function siteCountsOf(raw: unknown): Record<string, number> {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new TypeError('campground_site_summary returned site_counts that is not an object');
  }
  const counts: Record<string, number> = {};
  for (const [kind, count] of Object.entries(raw)) {
    if (typeof count !== 'number') {
      throw new TypeError(`campground_site_summary returned a non-numeric count for ${kind}`);
    }
    counts[kind] = count;
  }
  return counts;
}

function siteSummaryOf(row: SummaryRow): CampgroundSiteSummaryDto | null {
  if (row.site_total === null) return null;
  return {
    siteTotal: row.site_total,
    siteCounts: siteCountsOf(row.site_counts),
    maxPeople: row.max_people,
  };
}

export async function findDetailByPoi(db: Db, poiId: bigint): Promise<CampgroundDetailDto | null> {
  const rows = await db.execute<DetailRow>(sql`
    SELECT pc.poi_id,
           cg.id,
           cg.data_provider,
           cg.data_provider_ref,
           cg.name,
           cg.parent_name,
           cg.kind,
           cg.status,
           cg.status_description,
           cg.short_description,
           cg.medium_description,
           cg.long_description,
           cg.reservation_url,
           cg.big_rig_friendly,
           cg.has_pull_through_sites,
           cg.max_rv_length,
           cg.max_trailer_length,
           cg.location,
           cg.amenities,
           cg.price,
           cg.contact
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    WHERE pc.poi_id = ${poiId}
      AND cg.deleted_at IS NULL
      AND p.deleted_at IS NULL
  `);
  const row = rows[0];
  if (row === undefined) return null;

  const decoder = createColumnDecoder();
  return {
    poiId: idString(pois.id, row.poi_id),
    campgroundId: idString(campgrounds.id, row.id),
    dataProvider: row.data_provider,
    dataProviderRef: row.data_provider_ref,
    name: row.name,
    parentName: row.parent_name,
    kind: row.kind,
    status: row.status,
    statusDescription: row.status_description,
    shortDescription: row.short_description,
    mediumDescription: row.medium_description,
    longDescription: row.long_description,
    reservationUrl: row.reservation_url,
    bigRigFriendly: row.big_rig_friendly,
    hasPullThroughSites: row.has_pull_through_sites,
    maxRvLength: row.max_rv_length,
    maxTrailerLength: row.max_trailer_length,
    location: decoder.decode(LOCATION_COLUMN, locationShape, row.location),
    amenities: decoder.decode(AMENITIES_COLUMN, amenitiesShape, row.amenities),
    price: decoder.decode(PRICE_COLUMN, priceShape, row.price),
    contact: decoder.decode(CONTACT_COLUMN, contactShape, row.contact),
    decodeErrors: decoder.decodeErrors,
  };
}

export async function findSummariesByPoiIds(
  db: Db,
  poiIds: readonly string[],
): Promise<CampgroundSummaryDto[]> {
  const wanted = [...new Set(poiIds)];
  if (wanted.length === 0) return [];

  const idList = sql.join(
    wanted.map((id) => sql`${id}`),
    ID_SEPARATOR,
  );
  const rows = await db.execute<SummaryRow>(sql`
    SELECT p.id AS poi_id,
           cg.id,
           cg.name,
           ST_X(ST_PointOnSurface(p.geom)) AS lng,
           ST_Y(ST_PointOnSurface(p.geom)) AS lat,
           cg.location,
           cg.amenities,
           s.site_total,
           s.site_counts,
           s.max_people
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    LEFT JOIN LATERAL campground_site_summary(cg.id) s ON true
    WHERE pc.poi_id IN (${idList})
      AND cg.deleted_at IS NULL
      AND p.deleted_at IS NULL
  `);

  const byPoi = new Map<string, CampgroundSummaryDto>();
  for (const row of rows) {
    const decoder = createColumnDecoder();
    const location = decoder.decode(LOCATION_COLUMN, locationShape, row.location);
    const poiId = idString(pois.id, row.poi_id);
    byPoi.set(poiId, {
      poiId,
      campgroundId: idString(campgrounds.id, row.id),
      name: row.name,
      lng: row.lng,
      lat: row.lat,
      region: location?.region ?? null,
      amenities: decoder.decode(AMENITIES_COLUMN, amenitiesShape, row.amenities),
      siteSummary: siteSummaryOf(row),
      decodeErrors: decoder.decodeErrors,
    });
  }
  return wanted.flatMap((id) => byPoi.get(id) ?? []);
}
