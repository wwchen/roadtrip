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

interface CampsiteRefRow extends Record<string, unknown> {
  id: string;
  data_provider: string;
  data_provider_ref: string;
  max_people: number | string | null;
}

const KEY_SEPARATOR = '\u0000';

export function providerRefKey(dataProvider: string, dataProviderRef: string): string {
  return `${dataProvider}${KEY_SEPARATOR}${dataProviderRef}`;
}

export function pgArrayLiteral(values: readonly (string | bigint)[]): string {
  return `{${values.join(',')}}`;
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

export interface CampsiteKey {
  id: bigint;
  key: string;
  maxPeople: number | null;
}

export async function campsitesOf(db: Db, campgroundId: bigint, kinds: readonly CampsiteKind[]): Promise<CampsiteKey[]> {
  const kindFilter = kinds.length === 0 ? sql`true` : sql`kind = ANY(${pgArrayLiteral(kinds)}::text[])`;
  const rows = await db.execute<CampsiteRefRow>(sql`
    SELECT id, data_provider, data_provider_ref, max_people
    FROM campsites
    WHERE campground_id = ${campgroundId} AND deleted_at IS NULL AND ${kindFilter}
    ORDER BY id
  `);
  return rows.map((row) => ({
    id: BigInt(row.id),
    key: providerRefKey(row.data_provider, row.data_provider_ref),
    maxPeople: row.max_people === null ? null : Number(row.max_people),
  }));
}

export interface BookingIdentityRow {
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
}

export interface CampgroundBookingIdentities {
  campground: BookingIdentityRow;
  campsites: BookingIdentityRow[];
}

function identityOf(row: Record<string, unknown>): BookingIdentityRow {
  return {
    bookingProvider: row.booking_provider as string | null,
    bookingProviderRef: row.booking_provider_ref as string | null,
    bookingAliases: createColumnDecoder().decode('booking_aliases', bookingAliasesShape, row.booking_aliases) ?? [],
  };
}

export async function campgroundBookingIdentities(
  db: Db,
  campgroundId: bigint,
  campsiteIds: readonly bigint[],
): Promise<CampgroundBookingIdentities> {
  const [campground] = await db.execute<Record<string, unknown>>(sql`
    SELECT booking_provider, booking_provider_ref, booking_aliases FROM campgrounds WHERE id = ${campgroundId}
  `);
  const sites =
    campsiteIds.length === 0
      ? []
      : await db.execute<Record<string, unknown>>(sql`
          SELECT booking_provider, booking_provider_ref, booking_aliases
          FROM campsites
          WHERE id = ANY(${pgArrayLiteral(campsiteIds.map(String))}::bigint[])
          ORDER BY id
        `);
  return {
    campground: campground === undefined ? { bookingProvider: null, bookingProviderRef: null, bookingAliases: [] } : identityOf(campground),
    campsites: sites.map(identityOf),
  };
}
