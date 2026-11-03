import { planetFitnessLocations, poiPlanetFitnessLocations, type Db } from '@roadtrip/db';
import { eq, sql } from 'drizzle-orm';
import type { PgInsertValue } from 'drizzle-orm/pg-core';
import type { PlanetFitnessLocationUpsertCandidate } from '../model/planet-fitness-candidate.js';
import {
  opaqueArrayColumn,
  opaqueObjectColumn,
  requireCatalogBatchWithinLimit,
  type Executor,
} from './bulk.js';
import { assertFiniteCoordinates, insertPoi, updatePoiGeometry } from './poi-repo.js';

const PLANET_FITNESS_LOCATION_POI_TYPE = 'planet_fitness_location';

function mutableColumns(record: PlanetFitnessLocationUpsertCandidate) {
  return {
    name: record.name,
    address: opaqueObjectColumn(record.address),
    region: record.region ?? null,
    country: record.country ?? null,
    phone: record.phone ?? null,
    infoUrl: record.infoUrl ?? null,
    openingHours: record.openingHours ?? null,
    amenities: opaqueArrayColumn(record.amenities),
    payload: opaqueObjectColumn(record.payload),
  } satisfies Partial<PgInsertValue<typeof planetFitnessLocations>>;
}

async function idForLocationId(tx: Executor, locationId: string): Promise<bigint | null> {
  const [row] = await tx
    .select({ id: planetFitnessLocations.id })
    .from(planetFitnessLocations)
    .where(eq(planetFitnessLocations.locationId, locationId));
  return row?.id ?? null;
}

async function insertLocation(tx: Executor, record: PlanetFitnessLocationUpsertCandidate): Promise<bigint> {
  const [row] = await tx
    .insert(planetFitnessLocations)
    .values({ locationId: record.locationId, ...mutableColumns(record) })
    .returning({ id: planetFitnessLocations.id });
  return row!.id;
}

async function updateLocation(
  tx: Executor,
  id: bigint,
  record: PlanetFitnessLocationUpsertCandidate,
): Promise<void> {
  await tx
    .update(planetFitnessLocations)
    .set({ ...mutableColumns(record), updatedAt: sql`now()`, deletedAt: null })
    .where(eq(planetFitnessLocations.id, id));
}

async function upsertLocationPoi(
  tx: Executor,
  locationId: bigint,
  longitude: number,
  latitude: number,
): Promise<void> {
  const [existing] = await tx
    .select({ poiId: poiPlanetFitnessLocations.poiId })
    .from(poiPlanetFitnessLocations)
    .where(eq(poiPlanetFitnessLocations.planetFitnessLocationId, locationId));
  if (existing === undefined) {
    const poiId = await insertPoi(tx, PLANET_FITNESS_LOCATION_POI_TYPE, longitude, latitude);
    await tx.insert(poiPlanetFitnessLocations).values({ poiId, planetFitnessLocationId: locationId });
  } else {
    await updatePoiGeometry(tx, existing.poiId, longitude, latitude);
  }
}

async function upsertLocation(tx: Executor, record: PlanetFitnessLocationUpsertCandidate): Promise<void> {
  const existingId = await idForLocationId(tx, record.locationId);
  if (existingId !== null) await updateLocation(tx, existingId, record);
  const id = existingId ?? (await insertLocation(tx, record));
  await upsertLocationPoi(tx, id, record.longitude, record.latitude);
}

export async function upsertPlanetFitnessLocationBatch(
  db: Db,
  records: PlanetFitnessLocationUpsertCandidate[],
): Promise<number> {
  requireCatalogBatchWithinLimit('planet fitness location upsert', records.length);
  for (const record of records) {
    assertFiniteCoordinates(`planet fitness location ${record.locationId}`, record.longitude, record.latitude);
  }
  return db.transaction(async (tx) => {
    for (const record of records) await upsertLocation(tx, record);
    return records.length;
  });
}
