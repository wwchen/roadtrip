import { poiTeslaSuperchargers, teslaSuperchargers, type Db } from '@roadtrip/db';
import { eq, sql } from 'drizzle-orm';
import type { PgInsertValue } from 'drizzle-orm/pg-core';
import { DEFAULT_TESLA_SITE_STATUS, type TeslaSuperchargerUpsertCandidate } from '../model/tesla-candidate.js';
import {
  opaqueArrayColumn,
  opaqueObjectColumn,
  requireCatalogBatchWithinLimit,
  type Executor,
} from './bulk.js';
import { assertFiniteCoordinates, insertPoi, updatePoiGeometry } from './poi-repo.js';

const TESLA_SUPERCHARGER_POI_TYPE = 'tesla_supercharger';
const DEFAULT_OPEN_TO_PUBLIC = true;

function mutableColumns(record: TeslaSuperchargerUpsertCandidate) {
  return {
    locationGuid: record.locationGuid ?? null,
    commonSiteName: record.commonSiteName,
    siteStatus: record.siteStatus ?? DEFAULT_TESLA_SITE_STATUS,
    accessType: record.accessType ?? null,
    openToPublic: record.openToPublic ?? DEFAULT_OPEN_TO_PUBLIC,
    openToNonTeslas: record.openToNonTeslas ?? null,
    trailerFriendly: record.trailerFriendly ?? null,
    twentyFourSeven: record.twentyFourSeven ?? null,
    stallCount: record.stallCount ?? null,
    maxPowerKw: record.maxPowerKw ?? null,
    address: opaqueObjectColumn(record.address),
    region: record.region ?? null,
    country: record.country ?? null,
    timeZone: record.timeZone ?? null,
    amenities: opaqueArrayColumn(record.amenities),
    hardwareCounts: opaqueObjectColumn(record.hardwareCounts),
    pricebooks: opaqueArrayColumn(record.pricebooks),
    availabilityProfile: opaqueObjectColumn(record.availabilityProfile),
    infoUrl: record.infoUrl ?? null,
    indexPayload: opaqueObjectColumn(record.indexPayload),
    detailPayload: opaqueObjectColumn(record.detailPayload),
  } satisfies Partial<PgInsertValue<typeof teslaSuperchargers>>;
}

async function superchargerIdForLocationSlug(tx: Executor, locationSlug: string): Promise<bigint | null> {
  const [row] = await tx
    .select({ id: teslaSuperchargers.id })
    .from(teslaSuperchargers)
    .where(eq(teslaSuperchargers.locationSlug, locationSlug));
  return row?.id ?? null;
}

async function insertSupercharger(tx: Executor, record: TeslaSuperchargerUpsertCandidate): Promise<bigint> {
  const [row] = await tx
    .insert(teslaSuperchargers)
    .values({ locationSlug: record.locationSlug, ...mutableColumns(record) })
    .returning({ id: teslaSuperchargers.id });
  return row!.id;
}

async function updateSupercharger(
  tx: Executor,
  superchargerId: bigint,
  record: TeslaSuperchargerUpsertCandidate,
): Promise<void> {
  await tx
    .update(teslaSuperchargers)
    .set({ ...mutableColumns(record), updatedAt: sql`now()`, deletedAt: null })
    .where(eq(teslaSuperchargers.id, superchargerId));
}

async function upsertSuperchargerPoi(
  tx: Executor,
  superchargerId: bigint,
  longitude: number,
  latitude: number,
): Promise<void> {
  const [existing] = await tx
    .select({ poiId: poiTeslaSuperchargers.poiId })
    .from(poiTeslaSuperchargers)
    .where(eq(poiTeslaSuperchargers.teslaSuperchargerId, superchargerId));
  if (existing === undefined) {
    const poiId = await insertPoi(tx, TESLA_SUPERCHARGER_POI_TYPE, longitude, latitude);
    await tx.insert(poiTeslaSuperchargers).values({ poiId, teslaSuperchargerId: superchargerId });
  } else {
    await updatePoiGeometry(tx, existing.poiId, longitude, latitude);
  }
}

async function upsertSupercharger(tx: Executor, record: TeslaSuperchargerUpsertCandidate): Promise<void> {
  const existingId = await superchargerIdForLocationSlug(tx, record.locationSlug);
  if (existingId !== null) await updateSupercharger(tx, existingId, record);
  const superchargerId = existingId ?? (await insertSupercharger(tx, record));
  await upsertSuperchargerPoi(tx, superchargerId, record.longitude, record.latitude);
}

export async function upsertTeslaSuperchargerBatch(
  db: Db,
  records: TeslaSuperchargerUpsertCandidate[],
): Promise<number> {
  requireCatalogBatchWithinLimit('tesla supercharger upsert', records.length);
  for (const record of records) {
    assertFiniteCoordinates(`supercharger ${record.locationSlug}`, record.longitude, record.latitude);
  }
  return db.transaction(async (tx) => {
    for (const record of records) await upsertSupercharger(tx, record);
    return records.length;
  });
}
