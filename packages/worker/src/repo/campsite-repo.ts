import {
  attributesShape,
  bookingAliasesShape,
  campsites,
  equipmentShape,
  photosShape,
  type Db,
} from '@roadtrip/db';
import { CampsiteKind, type DataProviderRef } from '@roadtrip/domain';
import { getTableColumns, sql } from 'drizzle-orm';
import type { PgInsertValue } from 'drizzle-orm/pg-core';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import {
  BULK_CHUNK_SIZE,
  chunked,
  distinctBy,
  encodedColumn,
  excluded,
  opaqueObjectColumn,
  providerRefKey,
  requireCatalogBatchWithinLimit,
  type Executor,
  type FlushCounts,
} from './bulk.js';

const DEFAULT_CAMPSITE_KIND = CampsiteKind.OTHER;

const CONFLICT_UPDATE_KEYS = [
  'bookingProvider',
  'bookingProviderRef',
  'bookingAliases',
  'campgroundId',
  'name',
  'kind',
  'loopName',
  'latitude',
  'longitude',
  'reservationUrl',
  'equipment',
  'kindListed',
  'schedule',
  'price',
  'firepit',
  'picnicTable',
  'adaAccessible',
  'waterHookups',
  'electricHookups',
  'sewerHookups',
  'maxPeople',
  'maxCars',
  'pullThrough',
  'drivewayLength',
  'maxRvLength',
  'maxTrailerLength',
  'photos',
  'sourcePayload',
  'attributes',
  'description',
  'minPeople',
] as const;

interface ResolvedCampsite {
  record: CampsiteUpsertCandidate;
  campgroundId: bigint;
}

type ParentRecord = CampsiteUpsertCandidate & {
  parentDataProviderRef: NonNullable<CampsiteUpsertCandidate['parentDataProviderRef']>;
};

const hasParent = (record: CampsiteUpsertCandidate): record is ParentRecord =>
  record.parentDataProviderRef !== null;

const keyOfRef = (ref: DataProviderRef) => providerRefKey(ref.provider, ref.serialize());

export const campsiteNaturalKey = (record: CampsiteUpsertCandidate) => keyOfRef(record.dataProviderRef);

const parentKeyOf = (record: ParentRecord) => keyOfRef(record.parentDataProviderRef);

async function loadParentCampgroundMap(
  tx: Executor,
  parentRefs: readonly DataProviderRef[],
): Promise<Map<string, bigint>> {
  const resolved = new Map<string, bigint>();
  for (const chunk of chunked(parentRefs, BULK_CHUNK_SIZE)) {
    const values = sql.join(
      chunk.map((ref) => sql`(${ref.provider}::text, ${ref.serialize()}::text)`),
      sql`, `,
    );
    const rows = await tx.execute<{ data_provider: string; data_provider_ref: string; campground_id: string }>(sql`
      SELECT cg.data_provider, cg.data_provider_ref, cg.id AS campground_id
      FROM (VALUES ${values}) AS pk(data_provider, data_provider_ref)
      JOIN campgrounds cg
        ON cg.data_provider = pk.data_provider
       AND cg.data_provider_ref = pk.data_provider_ref
       AND cg.deleted_at IS NULL
    `);
    for (const row of rows) {
      resolved.set(providerRefKey(row.data_provider, row.data_provider_ref), BigInt(row.campground_id));
    }
  }
  return resolved;
}

function campsiteRow({ record, campgroundId }: ResolvedCampsite): PgInsertValue<typeof campsites> {
  return {
    dataProvider: record.dataProviderRef.provider,
    dataProviderRef: record.dataProviderRef.serialize(),
    bookingProvider: record.bookingProvider ?? null,
    bookingProviderRef: record.bookingProviderRef ?? null,
    bookingAliases: encodedColumn(bookingAliasesShape, record.bookingAliases ?? []),
    campgroundId,
    name: record.name,
    kind: record.kind ?? DEFAULT_CAMPSITE_KIND,
    loopName: record.loopName ?? null,
    latitude: record.latitude ?? null,
    longitude: record.longitude ?? null,
    reservationUrl: record.reservationUrl ?? null,
    equipment: encodedColumn(equipmentShape, record.equipment ?? []),
    kindListed: record.kindListed ?? null,
    schedule: opaqueObjectColumn(record.schedule),
    price: opaqueObjectColumn(record.price),
    firepit: record.firepit ?? null,
    picnicTable: record.picnicTable ?? null,
    adaAccessible: record.adaAccessible ?? null,
    waterHookups: record.waterHookups ?? null,
    electricHookups: record.electricHookups ?? null,
    sewerHookups: record.sewerHookups ?? null,
    maxPeople: record.maxPeople ?? null,
    maxCars: record.maxCars ?? null,
    pullThrough: record.pullThrough ?? null,
    drivewayLength: record.drivewayLength ?? null,
    maxRvLength: record.maxRvLength ?? null,
    maxTrailerLength: record.maxTrailerLength ?? null,
    photos: encodedColumn(photosShape, record.photos ?? []),
    sourcePayload: opaqueObjectColumn(record.sourcePayload),
    attributes: encodedColumn(attributesShape, record.attributes ?? []),
    description: record.description ?? null,
    minPeople: record.minPeople ?? null,
    updatedAt: sql`now()`,
  };
}

async function bulkUpsertCampsiteRows(tx: Executor, rows: readonly ResolvedCampsite[]): Promise<void> {
  const columns = getTableColumns(campsites);
  const deduped = distinctBy(rows, (row) => campsiteNaturalKey(row.record));
  for (const chunk of chunked(deduped, BULK_CHUNK_SIZE)) {
    await tx
      .insert(campsites)
      .values(chunk.map(campsiteRow))
      .onConflictDoUpdate({
        target: [campsites.dataProvider, campsites.dataProviderRef],
        targetWhere: sql`deleted_at IS NULL`,
        set: {
          ...excluded(columns, CONFLICT_UPDATE_KEYS),
          updatedAt: sql`now()`,
          deletedAt: null,
        },
      });
  }
}

async function upsertCampsitesInTransaction(
  tx: Executor,
  records: readonly CampsiteUpsertCandidate[],
): Promise<FlushCounts> {
  if (records.length === 0) return { upserted: 0, skipped: 0 };

  const withParent = records.filter(hasParent);
  const skippedForMissingParent = records.length - withParent.length;

  const parentRefs = distinctBy(
    withParent.map((record) => record.parentDataProviderRef),
    keyOfRef,
  );
  const parentMap = await loadParentCampgroundMap(tx, parentRefs);

  const resolved: ResolvedCampsite[] = withParent.flatMap((record) => {
    const campgroundId = parentMap.get(parentKeyOf(record));
    return campgroundId === undefined ? [] : [{ record, campgroundId }];
  });
  const skipped = skippedForMissingParent + (withParent.length - resolved.length);

  if (resolved.length === 0) return { upserted: 0, skipped };

  await bulkUpsertCampsiteRows(tx, resolved);
  return { upserted: resolved.length, skipped };
}

export async function upsertCampsiteBatch(db: Db, records: CampsiteUpsertCandidate[]): Promise<FlushCounts> {
  requireCatalogBatchWithinLimit('campsite upsert', records.length);
  return db.transaction((tx) => upsertCampsitesInTransaction(tx, records));
}
