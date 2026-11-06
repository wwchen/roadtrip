import type { Db } from '@roadtrip/db';
import {
  bookingIdentities,
  servingAvailabilityProvider,
  shippedTenantRegistry,
  type AvailabilityProviderCapabilities,
  type CampsiteKind,
} from '@roadtrip/domain';
import { fetchEgress, type EgressResult } from '../../client/availabilityEgress';
import { AVAILABILITY_STORED_TIMEOUT_MS, config } from '../../config';
import type { AvailabilityEgressRequestDto, AvailabilityWatchCapabilitiesDto, PoiCampsitesAvailabilityResponseDto } from '../../contract';
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
import { poiDateContext, type PoiDateContext } from '../booking/dateContext';
import { resolveWeekWindow, type WeekWindow } from './availabilityWindow';
import { fuseWeek, weekCache } from './matrixFusion';

export const GATED_WATCH_CAPABILITIES: AvailabilityWatchCapabilitiesDto = { trigger_kinds: [], add_to_cart: { state: 'unsupported' } };
const STORED_READ_LOG = 'availability stored read';

export type AvailabilityTargetCode = 'not_found' | 'unknown_campground';

export class AvailabilityTargetError extends Error {
  constructor(readonly code: AvailabilityTargetCode) {
    super(code);
    this.name = 'AvailabilityTargetError';
  }
}

export interface WeekSettings {
  ttlSeconds: number;
  enabledAvailabilityProviders: readonly string[];
  now: Date;
}

export type EgressFetch = (request: AvailabilityEgressRequestDto) => Promise<EgressResult>;

export interface WeekTarget {
  campground: AvailabilityCampgroundRow;
  provider: AvailabilityProviderCapabilities | null;
  context: PoiDateContext;
  campsites: CampsiteKey[];
}

export async function weekTarget(db: Db, poiId: bigint, siteTypes: readonly CampsiteKind[], settings: WeekSettings): Promise<WeekTarget> {
  const campground = await findAvailabilityCampground(db, poiId);
  if (campground === null) throw new AvailabilityTargetError('not_found');
  const identities = bookingIdentities(campground.bookingProvider, campground.bookingProviderRef, campground.bookingAliases);
  return {
    campground,
    provider: servingAvailabilityProvider(identities, settings.enabledAvailabilityProviders, shippedTenantRegistry()),
    context: poiDateContext(campground.lat, campground.lng, settings.now),
    campsites: await campsitesOf(db, campground.campgroundId, siteTypes),
  };
}

export function targetWindow(target: WeekTarget, startDate: string | null, endDate: string | null): WeekWindow {
  if (target.campsites.length > 0 && target.provider === null) throw new AvailabilityTargetError('unknown_campground');
  return resolveWeekWindow(target.provider, startDate, endDate, target.context);
}

export function egressRequest(target: WeekTarget, window: WeekWindow, mode: AvailabilityEgressRequestDto['mode']): AvailabilityEgressRequestDto {
  return {
    mode,
    campground: { data_provider: target.campground.dataProvider, data_provider_ref: target.campground.dataProviderRef },
    start_date: window.startDate,
    end_date: window.endDate,
  };
}

async function storedCells(target: WeekTarget, window: WeekWindow, fetchStored: EgressFetch | null): Promise<StoredCell[]> {
  if (fetchStored === null) return [];
  const result = await fetchStored(egressRequest(target, window, 'stored'));
  if (result.kind === 'error') {
    console.warn(STORED_READ_LOG, JSON.stringify({ status: result.status, code: result.code, campground: target.campground.dataProviderRef }));
    return [];
  }
  const idByKey = new Map(target.campsites.map((campsite) => [campsite.key, campsite.id]));
  return result.body.cells.flatMap((cell) => {
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
  const target = await weekTarget(db, poiId, siteTypes, settings);
  const window = targetWindow(target, startDate, endDate);
  const base = {
    poi_id: toContractId(poiId),
    start_date: window.startDate,
    end_date: window.endDate,
    ...(window.latestDate === null ? {} : { latest_date: window.latestDate }),
    watch_capabilities: GATED_WATCH_CAPABILITIES,
  };
  if (target.campsites.length === 0) return { ...base, state: 'empty', days: [] };
  const campsiteIds = target.campsites.map((campsite) => campsite.id);
  const cells = await storedCells(target, window, fetchStored);
  const cache = weekCache(campsiteIds, cells, window.startDate, window.endDate, settings.ttlSeconds, settings.now);
  const fused = fuseWeek(campsiteIds, cells, window.startDate, window.endDate);
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
  return { ttlSeconds: config.availabilityTtlSeconds, enabledAvailabilityProviders: config.enabledAvailabilityProviders, now: new Date() };
}
