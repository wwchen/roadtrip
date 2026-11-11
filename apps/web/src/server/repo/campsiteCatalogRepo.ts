import { attributesShape, bookingAliasesShape, photosShape, type Db } from '@roadtrip/db';
import type { BookingAlias, CampsiteAttribute, CampsiteKind } from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { pgArrayLiteral } from './availabilityReadRepo';
import { createColumnDecoder } from './columnDecoder';

export interface CampsiteCatalogRow {
  id: bigint;
  campgroundId: bigint;
  name: string;
  kind: CampsiteKind;
  kindListed: string | null;
  loopName: string | null;
  description: string | null;
  minPeople: number | null;
  maxPeople: number | null;
  maxCars: number | null;
  drivewayLength: number | null;
  maxRvLength: number | null;
  maxTrailerLength: number | null;
  firepit: boolean | null;
  picnicTable: boolean | null;
  adaAccessible: boolean | null;
  waterHookups: boolean | null;
  electricHookups: boolean | null;
  sewerHookups: boolean | null;
  pullThrough: boolean | null;
  equipment: string[];
  attributes: CampsiteAttribute[];
  photoUrl: string | null;
  reservationUrl: string | null;
  dataProvider: string;
  dataProviderRef: string;
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
}

export interface CampsiteCampgroundRow {
  campgroundId: bigint;
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
}

export async function findCampgroundsOfPoi(db: Db, poiId: bigint): Promise<CampsiteCampgroundRow[]> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT cg.id, cg.booking_provider, cg.booking_provider_ref, cg.booking_aliases
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    WHERE pc.poi_id = ${poiId} AND cg.deleted_at IS NULL AND p.deleted_at IS NULL
    ORDER BY cg.id
  `);
  return rows.map((row) => ({
    campgroundId: BigInt(row.id as string),
    bookingProvider: row.booking_provider as string | null,
    bookingProviderRef: row.booking_provider_ref as string | null,
    bookingAliases: createColumnDecoder().decode('booking_aliases', bookingAliasesShape, row.booking_aliases) ?? [],
  }));
}

const numberOrNull = (value: unknown): number | null => (value === null || value === undefined ? null : Number(value));

export async function findCampsitesOfPoi(db: Db, poiId: bigint, kinds: readonly CampsiteKind[]): Promise<CampsiteCatalogRow[]> {
  const kindFilter = kinds.length === 0 ? sql`true` : sql`c.kind = ANY(${pgArrayLiteral(kinds)}::text[])`;
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT c.*
    FROM campsites c
    JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
    JOIN campgrounds cg ON cg.id = c.campground_id
    JOIN pois p ON p.id = pc.poi_id
    WHERE pc.poi_id = ${poiId} AND c.deleted_at IS NULL AND cg.deleted_at IS NULL AND p.deleted_at IS NULL AND ${kindFilter}
    ORDER BY c.loop_name NULLS LAST, c.name, c.id
  `);
  return rows.map((row) => {
    const decoder = createColumnDecoder();
    return {
      id: BigInt(row.id as string),
      campgroundId: BigInt(row.campground_id as string),
      name: row.name as string,
      kind: row.kind as CampsiteKind,
      kindListed: row.kind_listed as string | null,
      loopName: row.loop_name as string | null,
      description: row.description as string | null,
      minPeople: numberOrNull(row.min_people),
      maxPeople: numberOrNull(row.max_people),
      maxCars: numberOrNull(row.max_cars),
      drivewayLength: numberOrNull(row.driveway_length),
      maxRvLength: numberOrNull(row.max_rv_length),
      maxTrailerLength: numberOrNull(row.max_trailer_length),
      firepit: row.firepit as boolean | null,
      picnicTable: row.picnic_table as boolean | null,
      adaAccessible: row.ada_accessible as boolean | null,
      waterHookups: row.water_hookups as boolean | null,
      electricHookups: row.electric_hookups as boolean | null,
      sewerHookups: row.sewer_hookups as boolean | null,
      pullThrough: row.pull_through as boolean | null,
      equipment: Array.isArray(row.equipment) ? (row.equipment as unknown[]).filter((item): item is string => typeof item === 'string') : [],
      attributes: decoder.decode('attributes', attributesShape, row.attributes) ?? [],
      photoUrl: decoder.decode('photos', photosShape, row.photos)?.[0]?.url ?? null,
      reservationUrl: row.reservation_url as string | null,
      dataProvider: row.data_provider as string,
      dataProviderRef: row.data_provider_ref as string,
      bookingProvider: row.booking_provider as string | null,
      bookingProviderRef: row.booking_provider_ref as string | null,
      bookingAliases: decoder.decode('booking_aliases', bookingAliasesShape, row.booking_aliases) ?? [],
    };
  });
}

export interface BookableCampsite {
  id: bigint;
  dataProvider: string;
  dataProviderRef: string;
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
  campground: {
    dataProvider: string;
    dataProviderRef: string;
    bookingProvider: string | null;
    bookingProviderRef: string | null;
    bookingAliases: BookingAlias[];
  };
}

export async function findBookableCampsite(db: Db, campsiteId: bigint): Promise<BookableCampsite | null> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT c.id, c.data_provider, c.data_provider_ref, c.booking_provider, c.booking_provider_ref, c.booking_aliases,
           cg.data_provider AS cg_data_provider, cg.data_provider_ref AS cg_data_provider_ref,
           cg.booking_provider AS cg_booking_provider, cg.booking_provider_ref AS cg_booking_provider_ref,
           cg.booking_aliases AS cg_booking_aliases
    FROM campsites c
    JOIN campgrounds cg ON cg.id = c.campground_id
    WHERE c.id = ${campsiteId} AND c.deleted_at IS NULL AND cg.deleted_at IS NULL
  `);
  const [row] = rows;
  if (row === undefined) return null;
  const decoder = createColumnDecoder();
  return {
    id: BigInt(row.id as string),
    dataProvider: row.data_provider as string,
    dataProviderRef: row.data_provider_ref as string,
    bookingProvider: row.booking_provider as string | null,
    bookingProviderRef: row.booking_provider_ref as string | null,
    bookingAliases: decoder.decode('booking_aliases', bookingAliasesShape, row.booking_aliases) ?? [],
    campground: {
      dataProvider: row.cg_data_provider as string,
      dataProviderRef: row.cg_data_provider_ref as string,
      bookingProvider: row.cg_booking_provider as string | null,
      bookingProviderRef: row.cg_booking_provider_ref as string | null,
      bookingAliases: decoder.decode('booking_aliases', bookingAliasesShape, row.cg_booking_aliases) ?? [],
    },
  };
}
