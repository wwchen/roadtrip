import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  requestPoiCampsitesAvailability,
  seasonReopensOn,
  type AvailabilityDay,
  type PoiCampsitesAvailabilityResponse,
} from '@/api/availability-api';
import { fetchPoiCampsites, type Campsite } from '@/api/campsite-api';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import { nightsCopy } from '@/lib/strings';
import {
  NO_WATCH_CAPABILITIES,
  normalizeWatchCapabilities,
  type WatchCapabilities,
} from '@/lib/watch-windows';
import { queryKeys } from '@/queries/keys';
import type { PlaceStay } from './use-place-stay';

const MAX_NIGHTS_IN_VIEW = 30;

const CATALOG_STALE_MS = 5 * 60_000;

const RETRY_AUTOMATICALLY = false;

const GRID_READ_FAILED = 'the per-night availability read failed';

export interface PlaceNightsView {
  days: readonly AvailabilityDay[];
  dates: readonly string[];
  siteNames: ReadonlyMap<number, string>;
  capabilities: WatchCapabilities;
  reopensOn: string | undefined;
}

const NO_DAYS: readonly AvailabilityDay[] = [];
const NO_SITE_NAMES: ReadonlyMap<number, string> = new Map();

export function nightsOf(start: string, end: string): readonly string[] {
  const dates: string[] = [];
  let day = parseLocalYmd(start);
  while (dates.length < MAX_NIGHTS_IN_VIEW && localYmd(day) < end) {
    dates.push(localYmd(day));
    day = addLocalDays(day, 1);
  }
  return dates;
}

export function siteLabel(site: Partial<Campsite>): string {
  const name = site.name?.trim();
  const loop = site.loop_name?.trim();
  if (name) return loop ? `${loop} / ${name}` : name;
  const ref = site.data_provider_ref?.trim();
  return ref ? nightsCopy.site(ref) : '';
}

function namesOf(campsites: readonly Campsite[] | undefined): ReadonlyMap<number, string> {
  if (!campsites) return NO_SITE_NAMES;
  const names = new Map<number, string>();
  for (const site of campsites) {
    const label = siteLabel(site);
    if (label) names.set(site.id, label);
  }
  return names;
}

export function usePlaceNights(poiId: number, stay: PlaceStay): PlaceNightsView {
  const siteType = stay.siteType ?? undefined;

  const grid = useQuery({
    queryKey: queryKeys.availability.forPoi(poiId, stay.start, stay.end, siteType),
    retry: RETRY_AUTOMATICALLY,
    queryFn: async ({ signal }): Promise<PoiCampsitesAvailabilityResponse> => {
      const response = await requestPoiCampsitesAvailability(poiId, {
        startDate: stay.start,
        endDate: stay.end,
        siteType,
        signal,
      });
      if (!response.ok) throw new Error(GRID_READ_FAILED);
      return (await response.json()) as PoiCampsitesAvailabilityResponse;
    },
  });

  const catalog = useQuery({
    queryKey: queryKeys.campsites.forPoi(poiId),
    staleTime: CATALOG_STALE_MS,
    queryFn: ({ signal }) => fetchPoiCampsites(poiId, { signal }),
  });

  const served = grid.data;
  const campsites = catalog.data?.campsites;

  const dates = useMemo(() => nightsOf(stay.start, stay.end), [stay.start, stay.end]);
  const siteNames = useMemo(() => namesOf(campsites), [campsites]);
  const capabilities = useMemo(
    () =>
      served?.watch_capabilities
        ? normalizeWatchCapabilities(served.watch_capabilities)
        : NO_WATCH_CAPABILITIES,
    [served?.watch_capabilities],
  );

  return {
    days: Array.isArray(served?.days) ? served.days : NO_DAYS,
    dates,
    siteNames,
    capabilities,
    reopensOn: seasonReopensOn(served?.season),
  };
}
