import type { Db } from '@roadtrip/db';
import type { CampgroundUpsertCandidate } from '../model/campground-candidate.js';
import type { CampsiteUpsertCandidate } from '../model/campsite-candidate.js';
import type { PlanetFitnessLocationUpsertCandidate } from '../model/planet-fitness-candidate.js';
import type { TeslaSuperchargerUpsertCandidate } from '../model/tesla-candidate.js';
import { MAX_CATALOG_UPSERT_BATCH_SIZE, type FlushCounts } from '../repo/bulk.js';
import { campgroundNaturalKey, upsertCampgroundBatch } from '../repo/campground-repo.js';
import { campsiteNaturalKey, upsertCampsiteBatch } from '../repo/campsite-repo.js';
import { upsertPlanetFitnessLocationBatch } from '../repo/planet-fitness-repo.js';
import { upsertTeslaSuperchargerBatch } from '../repo/tesla-repo.js';
import type { TerminalSink } from './batch.js';
import type { SourceEtl } from './source-etl.js';
import type { TerminalEtlBinding } from './terminal-binding.js';

const NO_SKIPS = 0;

export function terminalSink<OUT>(
  write: (records: OUT[]) => Promise<FlushCounts>,
  batchSize: number = MAX_CATALOG_UPSERT_BATCH_SIZE,
): TerminalSink<OUT> {
  if (!Number.isInteger(batchSize) || batchSize <= 0) {
    throw new Error('terminal sink batch size must be positive');
  }
  return { batchSize, write };
}

export function campgroundBinding<DTO>(
  etl: SourceEtl<DTO, CampgroundUpsertCandidate>,
): TerminalEtlBinding<DTO, CampgroundUpsertCandidate> {
  return {
    etl,
    naturalKeyOf: campgroundNaturalKey,
    sink: (db: Db) =>
      terminalSink(async (records) => ({
        upserted: await upsertCampgroundBatch(db, records),
        skipped: NO_SKIPS,
      })),
  };
}

export function campsiteBinding<DTO>(
  etl: SourceEtl<DTO, CampsiteUpsertCandidate>,
): TerminalEtlBinding<DTO, CampsiteUpsertCandidate> {
  return {
    etl,
    naturalKeyOf: campsiteNaturalKey,
    sink: (db: Db) => terminalSink((records) => upsertCampsiteBatch(db, records)),
  };
}

export function teslaSuperchargerBinding<DTO>(
  etl: SourceEtl<DTO, TeslaSuperchargerUpsertCandidate>,
): TerminalEtlBinding<DTO, TeslaSuperchargerUpsertCandidate> {
  return {
    etl,
    naturalKeyOf: (record) => record.locationSlug,
    sink: (db: Db) =>
      terminalSink(async (records) => ({
        upserted: await upsertTeslaSuperchargerBatch(db, records),
        skipped: NO_SKIPS,
      })),
  };
}

export function planetFitnessBinding<DTO>(
  etl: SourceEtl<DTO, PlanetFitnessLocationUpsertCandidate>,
): TerminalEtlBinding<DTO, PlanetFitnessLocationUpsertCandidate> {
  return {
    etl,
    naturalKeyOf: (record) => record.locationId,
    sink: (db: Db) =>
      terminalSink(async (records) => ({
        upserted: await upsertPlanetFitnessLocationBatch(db, records),
        skipped: NO_SKIPS,
      })),
  };
}
