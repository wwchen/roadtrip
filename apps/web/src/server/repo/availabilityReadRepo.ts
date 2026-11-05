import type { AVAILABILITY_STATUSES, Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';

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
