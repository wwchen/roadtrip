import type { Db } from '@roadtrip/db';
import type { CampsiteKind } from '@roadtrip/domain';
import { fetchEgress, type EgressResult } from '../../client/availabilityEgress';
import { AVAILABILITY_STORED_TIMEOUT_MS, config } from '../../config';
import type {
  AvailabilityEgressRequestDto,
  AvailabilityEgressResponseDto,
  AvailabilityErrorDto,
  AvailabilityWatchCapabilitiesDto,
  PoiCampsitesAvailabilityResponseDto,
} from '../../contract';
import { toContractId } from '../../contractIds';
import { serverDb } from '../../db';
import {
  campsitesOf,
  findAvailabilityCampground,
  providerRefKey,
  type AvailabilityCampgroundRow,
  type CampsiteKey,
  type StoredCell,
} from '../../repo/availabilityReadRepo';
import { addDays, poiDateContext } from '../booking/dateContext';
import { fuseWeek, weekCache } from './matrixFusion';

export const GATED_WATCH_CAPABILITIES: AvailabilityWatchCapabilitiesDto = { trigger_kinds: [], add_to_cart: { state: 'unsupported' } };
const STORED_READ_LOG = 'availability stored read';
const STATUS_BAD_REQUEST = 400;
const STATUS_NOT_FOUND = 404;
const STATUS_NOT_IMPLEMENTED = 501;
const DEFAULT_WEEK_DAYS = 7;
const CATALOG_DRIFT_LOG = 'availability catalog drift';

export type AvailabilityTargetCode = 'not_found' | 'unknown_campground';

export class AvailabilityTargetError extends Error {
  constructor(readonly code: AvailabilityTargetCode) {
    super(code);
    this.name = 'AvailabilityTargetError';
  }
}

export class AvailabilityUnavailableError extends Error {
  constructor() {
    super('availability is unavailable');
    this.name = 'AvailabilityUnavailableError';
  }
}

export class AvailabilityWindowRefusedError extends Error {
  constructor(readonly body: AvailabilityErrorDto) {
    super(body.error);
    this.name = 'AvailabilityWindowRefusedError';
  }
}

export interface WeekSettings {
  ttlSeconds: number;
  now: Date;
}

export type EgressFetch = (request: AvailabilityEgressRequestDto) => Promise<EgressResult>;

export interface WeekTarget {
  campground: AvailabilityCampgroundRow;
  campsites: CampsiteKey[];
}

export async function weekTarget(db: Db, poiId: bigint, siteTypes: readonly CampsiteKind[]): Promise<WeekTarget> {
  const campground = await findAvailabilityCampground(db, poiId);
  if (campground === null) throw new AvailabilityTargetError('not_found');
  return { campground, campsites: await campsitesOf(db, campground.campgroundId, siteTypes) };
}

export function egressRequest(
  target: WeekTarget,
  startDate: string | null,
  endDate: string | null,
  mode: AvailabilityEgressRequestDto['mode'],
): AvailabilityEgressRequestDto {
  return {
    mode,
    campground: { data_provider: target.campground.dataProvider, data_provider_ref: target.campground.dataProviderRef },
    ...(startDate === null ? {} : { start_date: startDate }),
    ...(endDate === null ? {} : { end_date: endDate }),
  };
}

function unknownToMiniCa(target: WeekTarget, startDate: string | null, endDate: string | null, now: Date): AvailabilityEgressResponseDto {
  console.warn(CATALOG_DRIFT_LOG, JSON.stringify({ campground: target.campground.dataProviderRef, provider: target.campground.dataProvider }));
  const start = startDate ?? poiDateContext(target.campground.lat, target.campground.lng, now).earliestDate;
  return { start_date: start, end_date: endDate ?? addDays(start, DEFAULT_WEEK_DAYS), campsites: [], cells: [] };
}

async function storedWeek(
  target: WeekTarget,
  startDate: string | null,
  endDate: string | null,
  fetchStored: EgressFetch | null,
  now: Date,
): Promise<AvailabilityEgressResponseDto> {
  if (fetchStored === null) throw new AvailabilityUnavailableError();
  const result = await fetchStored(egressRequest(target, startDate, endDate, 'stored'));
  if (result.kind === 'cells') return result.body;
  if (result.status === STATUS_NOT_FOUND) return unknownToMiniCa(target, startDate, endDate, now);
  if (result.status === STATUS_BAD_REQUEST && result.body !== null) throw new AvailabilityWindowRefusedError(result.body);
  if (result.status === STATUS_NOT_IMPLEMENTED) throw new AvailabilityTargetError('unknown_campground');
  console.warn(STORED_READ_LOG, JSON.stringify({ status: result.status, code: result.code, campground: target.campground.dataProviderRef }));
  throw new AvailabilityUnavailableError();
}

function cellsOf(stored: AvailabilityEgressResponseDto, idByKey: ReadonlyMap<string, bigint>): StoredCell[] {
  return stored.cells.flatMap((cell) => {
    const campsiteId = idByKey.get(providerRefKey(cell.campsite.data_provider, cell.campsite.data_provider_ref));
    return campsiteId === undefined ? [] : [{ campsiteId, date: cell.date, status: cell.status, lastObservedAt: new Date(cell.observed_at) }];
  });
}

export async function availabilityWeek(
  db: Db,
  poiId: bigint,
  siteTypes: readonly CampsiteKind[],
  startDate: string | null,
  endDate: string | null,
  settings: WeekSettings,
  fetchStored: EgressFetch | null,
): Promise<PoiCampsitesAvailabilityResponseDto> {
  const target = await weekTarget(db, poiId, siteTypes);
  const stored = await storedWeek(target, startDate, endDate, fetchStored, settings.now);
  const base = {
    poi_id: toContractId(poiId),
    start_date: stored.start_date,
    end_date: stored.end_date,
    ...(stored.latest_date === undefined ? {} : { latest_date: stored.latest_date }),
    watch_capabilities: GATED_WATCH_CAPABILITIES,
  };
  const known = new Set(stored.campsites.map((campsite) => providerRefKey(campsite.data_provider, campsite.data_provider_ref)));
  const tracked = target.campsites.filter((campsite) => known.has(campsite.key));
  if (tracked.length === 0) return { ...base, state: 'empty', days: [] };
  const cells = cellsOf(stored, new Map(tracked.map((campsite) => [campsite.key, campsite.id])));
  const cache = weekCache(tracked.map((campsite) => campsite.id), cells, stored.start_date, stored.end_date, settings.ttlSeconds, settings.now);
  const fused = fuseWeek(target.campsites.map((campsite) => campsite.id), cells, stored.start_date, stored.end_date);
  return { ...base, state: fused.state, ...(cache === null ? {} : { cache }), days: fused.days };
}

export function configuredEgress(timeoutMs: number): EgressFetch | null {
  const egress = config.availabilityEgress;
  if (egress === null) return null;
  const endpoint = { url: egress.egressUrl, access: egress.egressAccess };
  return (request) => fetchEgress(endpoint, request, timeoutMs);
}

export async function getAvailabilityWeek(
  poiId: bigint,
  siteTypes: readonly CampsiteKind[],
  startDate: string | null,
  endDate: string | null,
): Promise<PoiCampsitesAvailabilityResponseDto> {
  return availabilityWeek(await serverDb(), poiId, siteTypes, startDate, endDate, currentWeekSettings(), configuredEgress(AVAILABILITY_STORED_TIMEOUT_MS));
}

export function currentWeekSettings(): WeekSettings {
  return { ttlSeconds: config.availabilityTtlSeconds, now: new Date() };
}
