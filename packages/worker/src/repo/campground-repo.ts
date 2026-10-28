import {
  alertsShape,
  amenitiesShape,
  bookingAliasesShape,
  campgrounds,
  cellServiceShape,
  contactShape,
  geometryProvenanceShape,
  linksShape,
  locationShape,
  managementShape,
  metadataShape,
  photosShape,
  poiCampgrounds,
  priceShape,
  scheduleShape,
  type Db,
} from '@roadtrip/db';
import { getTableColumns, inArray, sql } from 'drizzle-orm';
import type { PgInsertValue } from 'drizzle-orm/pg-core';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import {
  BULK_CHUNK_SIZE,
  chunked,
  distinctBy,
  encodedColumn,
  encodedNullableColumn,
  excluded,
  opaqueObjectColumn,
  providerRefKey,
  requireCatalogBatchWithinLimit,
  type Executor,
} from './bulk.js';
import { assertFiniteCoordinates, bulkUpdatePoiGeometry, insertPoi } from './poi-repo.js';

const CAMPGROUND_POI_TYPE = 'campground';

const CONFLICT_UPDATE_KEYS = [
  'bookingProvider',
  'bookingProviderRef',
  'bookingAliases',
  'name',
  'parentName',
  'status',
  'statusDescription',
  'kind',
  'shortDescription',
  'mediumDescription',
  'longDescription',
  'location',
  'defaultCampsiteSchedule',
  'amenities',
  'maxRvLength',
  'maxTrailerLength',
  'hasPullThroughSites',
  'bigRigFriendly',
  'reservationUrl',
  'links',
  'photos',
  'alerts',
  'price',
  'cellService',
  'management',
  'contact',
  'connections',
  'metadata',
  'sourcePayload',
  'geometryProvenance',
] as const;

interface CampgroundPoiRow {
  campgroundId: bigint;
  longitude: number;
  latitude: number;
}

const refKeyOf = (record: CampgroundUpsertCandidate) =>
  providerRefKey(record.dataProviderRef.provider, record.dataProviderRef.serialize());

function campgroundRow(record: CampgroundUpsertCandidate): PgInsertValue<typeof campgrounds> {
  return {
    dataProvider: record.dataProviderRef.provider,
    dataProviderRef: record.dataProviderRef.serialize(),
    bookingProvider: record.bookingProvider ?? null,
    bookingProviderRef: record.bookingProviderRef ?? null,
    bookingAliases: encodedColumn(bookingAliasesShape, record.bookingAliases ?? []),
    name: record.name,
    parentName: record.parentName ?? null,
    status: record.status ?? null,
    statusDescription: record.statusDescription ?? null,
    kind: record.kind ?? null,
    shortDescription: record.shortDescription ?? null,
    mediumDescription: record.mediumDescription ?? null,
    longDescription: record.longDescription ?? null,
    location: encodedColumn(locationShape, record.location),
    defaultCampsiteSchedule: encodedColumn(scheduleShape, record.defaultCampsiteSchedule ?? null),
    amenities: encodedColumn(amenitiesShape, record.amenities ?? []),
    maxRvLength: record.maxRvLength ?? null,
    maxTrailerLength: record.maxTrailerLength ?? null,
    hasPullThroughSites: record.hasPullThroughSites ?? null,
    bigRigFriendly: record.bigRigFriendly ?? null,
    reservationUrl: record.reservationUrl ?? null,
    links: encodedColumn(linksShape, record.links ?? []),
    photos: encodedColumn(photosShape, record.photos ?? []),
    alerts: encodedColumn(alertsShape, record.alerts ?? []),
    price: encodedColumn(priceShape, record.price ?? null),
    cellService: encodedColumn(cellServiceShape, record.cellService ?? []),
    management: encodedColumn(managementShape, record.management ?? null),
    contact: encodedColumn(contactShape, record.contact ?? null),
    connections: opaqueObjectColumn(record.connections),
    metadata: encodedColumn(metadataShape, record.metadata ?? null),
    sourcePayload: opaqueObjectColumn(record.sourcePayload),
    geometryProvenance: encodedNullableColumn(geometryProvenanceShape, record.geometryProvenance ?? null),
    updatedAt: sql`now()`,
  };
}

async function bulkUpsertCampgroundRows(
  tx: Executor,
  records: readonly CampgroundUpsertCandidate[],
): Promise<Map<string, bigint>> {
  const idByProviderRef = new Map<string, bigint>();
  const columns = getTableColumns(campgrounds);
  for (const chunk of chunked(distinctBy(records, refKeyOf), BULK_CHUNK_SIZE)) {
    const returned = await tx
      .insert(campgrounds)
      .values(chunk.map(campgroundRow))
      .onConflictDoUpdate({
        target: [campgrounds.dataProvider, campgrounds.dataProviderRef],
        targetWhere: sql`deleted_at IS NULL`,
        set: {
          ...excluded(columns, CONFLICT_UPDATE_KEYS),
          updatedAt: sql`now()`,
          deletedAt: null,
        },
      })
      .returning({
        id: campgrounds.id,
        dataProvider: campgrounds.dataProvider,
        dataProviderRef: campgrounds.dataProviderRef,
      });
    for (const row of returned) {
      idByProviderRef.set(providerRefKey(row.dataProvider, row.dataProviderRef), row.id);
    }
  }
  return idByProviderRef;
}

async function bulkUpsertCampgroundPois(tx: Executor, rows: readonly CampgroundPoiRow[]): Promise<void> {
  if (rows.length === 0) return;
  const deduped = distinctBy(rows, (row) => String(row.campgroundId));

  const existingPoiByCampground = new Map<bigint, bigint>();
  for (const chunk of chunked(deduped, BULK_CHUNK_SIZE)) {
    const found = await tx
      .select({ poiId: poiCampgrounds.poiId, campgroundId: poiCampgrounds.campgroundId })
      .from(poiCampgrounds)
      .where(
        inArray(
          poiCampgrounds.campgroundId,
          chunk.map((row) => row.campgroundId),
        ),
      );
    for (const row of found) existingPoiByCampground.set(row.campgroundId, row.poiId);
  }

  const existingRows = deduped.filter((row) => existingPoiByCampground.has(row.campgroundId));
  const newRows = deduped.filter((row) => !existingPoiByCampground.has(row.campgroundId));

  await bulkUpdatePoiGeometry(
    tx,
    existingRows.map((row) => ({
      poiId: existingPoiByCampground.get(row.campgroundId)!,
      longitude: row.longitude,
      latitude: row.latitude,
    })),
  );

  const linkPairs: { poiId: bigint; campgroundId: bigint }[] = [];
  for (const row of newRows) {
    const poiId = await insertPoi(tx, CAMPGROUND_POI_TYPE, row.longitude, row.latitude);
    linkPairs.push({ poiId, campgroundId: row.campgroundId });
  }
  for (const chunk of chunked(linkPairs, BULK_CHUNK_SIZE)) {
    await tx
      .insert(poiCampgrounds)
      .values(chunk.map((pair) => ({ ...pair, updatedAt: sql`now()` })))
      .onConflictDoNothing({ target: poiCampgrounds.campgroundId });
  }
}

async function upsertCampgroundsInTransaction(
  tx: Executor,
  records: readonly CampgroundUpsertCandidate[],
): Promise<number> {
  if (records.length === 0) return 0;
  const idByProviderRef = await bulkUpsertCampgroundRows(tx, records);
  const poiRows = records.map((record) => ({
    campgroundId: idByProviderRef.get(refKeyOf(record))!,
    longitude: record.longitude,
    latitude: record.latitude,
  }));
  await bulkUpsertCampgroundPois(tx, poiRows);
  return records.length;
}

export async function upsertCampgroundBatch(db: Db, records: CampgroundUpsertCandidate[]): Promise<number> {
  requireCatalogBatchWithinLimit('campground upsert', records.length);
  for (const record of records) {
    assertFiniteCoordinates(`campground ${record.dataProviderRef.provider}:${record.dataProviderRef.serialize()}`, record.longitude, record.latitude);
  }
  return db.transaction((tx) => upsertCampgroundsInTransaction(tx, records));
}
