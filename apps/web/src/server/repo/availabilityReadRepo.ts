import { bookingAliasesShape, type AVAILABILITY_STATUSES, type Db } from '@roadtrip/db';
import type { BookingAlias, CampsiteKind } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { createColumnDecoder } from './columnDecoder';

export type AvailabilityStatus = (typeof AVAILABILITY_STATUSES)[number];

export interface StoredCell {
  campsiteId: bigint;
  date: string;
  status: AvailabilityStatus;
  lastObservedAt: Date;
}

interface StoredCellRow extends Record<string, unknown> {
  campsite_id: string;
  target_date: string;
  status: AvailabilityStatus;
  last_observed_at: Date | string;
}

interface CampsiteRefRow extends Record<string, unknown> {
  id: string;
  data_provider: string;
  data_provider_ref: string;
}

const KEY_SEPARATOR = '\u0000';

export function providerRefKey(dataProvider: string, dataProviderRef: string): string {
  return `${dataProvider}${KEY_SEPARATOR}${dataProviderRef}`;
}

export function pgArrayLiteral(values: readonly (string | bigint)[]): string {
  return `{${values.join(',')}}`;
}

export async function currentCells(
  db: Db,
  campsiteIds: readonly bigint[],
  startDate: string,
  endDateExclusive: string,
): Promise<StoredCell[]> {
  if (campsiteIds.length === 0) return [];
  const rows = await db.execute<StoredCellRow>(sql`
    SELECT DISTINCT ON (a.campsite_id, a.target_date)
           a.campsite_id, a.target_date::text AS target_date, a.status, a.last_observed_at
    FROM availability a
    WHERE a.campsite_id = ANY(${pgArrayLiteral(campsiteIds)}::bigint[])
      AND a.target_date >= ${startDate}::date
      AND a.target_date < ${endDateExclusive}::date
    ORDER BY a.campsite_id, a.target_date, a.last_observed_at DESC, a.id DESC
  `);
  return rows.map((row) => ({
    campsiteId: BigInt(row.campsite_id),
    date: row.target_date,
    status: row.status,
    lastObservedAt: new Date(row.last_observed_at),
  }));
}

export async function campsiteIdsByProviderRef(db: Db, campgroundId: bigint): Promise<Map<string, bigint>> {
  const rows = await db.execute<CampsiteRefRow>(sql`
    SELECT id, data_provider, data_provider_ref
    FROM campsites
    WHERE campground_id = ${campgroundId} AND deleted_at IS NULL
  `);
  return new Map(rows.map((row) => [providerRefKey(row.data_provider, row.data_provider_ref), BigInt(row.id)]));
}

export interface AvailabilityCampgroundRow {
  campgroundId: bigint;
  dataProvider: string;
  dataProviderRef: string;
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
  lat: number;
  lng: number;
}

export async function findAvailabilityCampground(db: Db, poiId: bigint): Promise<AvailabilityCampgroundRow | null> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT cg.id AS campground_id, cg.data_provider, cg.data_provider_ref, cg.booking_provider, cg.booking_provider_ref,
           cg.booking_aliases, ST_X(ST_PointOnSurface(p.geom)) AS lng, ST_Y(ST_PointOnSurface(p.geom)) AS lat
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    WHERE pc.poi_id = ${poiId} AND cg.deleted_at IS NULL AND p.deleted_at IS NULL
    ORDER BY cg.id
    LIMIT 1
  `);
  const row = rows[0];
  if (row === undefined) return null;
  const decoder = createColumnDecoder();
  return {
    campgroundId: BigInt(row.campground_id as string),
    dataProvider: row.data_provider as string,
    dataProviderRef: row.data_provider_ref as string,
    bookingProvider: row.booking_provider as string | null,
    bookingProviderRef: row.booking_provider_ref as string | null,
    bookingAliases: decoder.decode('booking_aliases', bookingAliasesShape, row.booking_aliases) ?? [],
    lat: Number(row.lat),
    lng: Number(row.lng),
  };
}

export async function campsiteIdsOf(db: Db, campgroundId: bigint, kinds: readonly CampsiteKind[]): Promise<bigint[]> {
  const kindFilter = kinds.length === 0 ? sql`true` : sql`kind = ANY(${pgArrayLiteral(kinds)}::text[])`;
  const rows = await db.execute<{ id: string }>(sql`
    SELECT id FROM campsites WHERE campground_id = ${campgroundId} AND deleted_at IS NULL AND ${kindFilter} ORDER BY id
  `);
  return rows.map((row) => BigInt(row.id));
}
