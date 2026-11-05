import type { Db } from '@roadtrip/db';
import {
  bookingIdentities,
  servingAvailabilityProvider,
  shippedTenantRegistry,
  type AvailabilityProviderCapabilities,
  type CampsiteKind,
} from '@roadtrip/domain';
import { config } from '../../config';
import type { AvailabilityWatchCapabilitiesDto, PoiCampsitesAvailabilityResponseDto } from '../../contract';
import { toContractId } from '../../contractIds';
import { serverDb } from '../../db';
import { campsiteIdsOf, currentCells, findAvailabilityCampground, type AvailabilityCampgroundRow } from '../../repo/availabilityReadRepo';
import { poiDateContext, type PoiDateContext } from '../booking/dateContext';
import { resolveWeekWindow, type WeekWindow } from './availabilityWindow';
import { fuseWeek, weekCache } from './matrixFusion';

export const GATED_WATCH_CAPABILITIES: AvailabilityWatchCapabilitiesDto = { trigger_kinds: [], add_to_cart: { state: 'unsupported' } };

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

export interface WeekTarget {
  campground: AvailabilityCampgroundRow;
  provider: AvailabilityProviderCapabilities | null;
  context: PoiDateContext;
  campsiteIds: bigint[];
}

export async function weekTarget(db: Db, poiId: bigint, siteTypes: readonly CampsiteKind[], settings: WeekSettings): Promise<WeekTarget> {
  const campground = await findAvailabilityCampground(db, poiId);
  if (campground === null) throw new AvailabilityTargetError('not_found');
  const identities = bookingIdentities(campground.bookingProvider, campground.bookingProviderRef, campground.bookingAliases);
  return {
    campground,
    provider: servingAvailabilityProvider(identities, settings.enabledAvailabilityProviders, shippedTenantRegistry()),
    context: poiDateContext(campground.lat, campground.lng, settings.now),
    campsiteIds: await campsiteIdsOf(db, campground.campgroundId, siteTypes),
  };
}

export function targetWindow(target: WeekTarget, startDate: string | null, endDate: string | null): WeekWindow {
  if (target.campsiteIds.length > 0 && target.provider === null) throw new AvailabilityTargetError('unknown_campground');
  return resolveWeekWindow(target.provider, startDate, endDate, target.context);
}

export async function availabilityWeek(
  db: Db,
  poiId: bigint,
  siteTypes: readonly CampsiteKind[],
  startDate: string | null,
  endDate: string | null,
  settings: WeekSettings,
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
  if (target.campsiteIds.length === 0) return { ...base, state: 'empty', days: [] };
  const cells = await currentCells(db, target.campsiteIds, window.startDate, window.endDate);
  const cache = weekCache(target.campsiteIds, cells, window.startDate, window.endDate, settings.ttlSeconds, settings.now);
  const fused = fuseWeek(target.campsiteIds, cells, window.startDate, window.endDate);
  return { ...base, state: fused.state, ...(cache === null ? {} : { cache }), days: fused.days };
}

export async function getAvailabilityWeek(
  poiId: bigint,
  siteTypes: readonly CampsiteKind[],
  startDate: string | null,
  endDate: string | null,
): Promise<PoiCampsitesAvailabilityResponseDto> {
  return availabilityWeek(await serverDb(), poiId, siteTypes, startDate, endDate, currentWeekSettings());
}

export function currentWeekSettings(): WeekSettings {
  return { ttlSeconds: config.availabilityTtlSeconds, enabledAvailabilityProviders: config.enabledAvailabilityProviders, now: new Date() };
}
