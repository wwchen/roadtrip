import { useCallback, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  requestPoiCampsitesAvailability,
  seasonReopensOn,
  type AvailabilityDay,
  type PoiCampsitesAvailabilityResponse,
} from '@/api/availability-api';
import { fetchPoiCampsites, type Campsite } from '@/api/campsite-api';
import {
  AvailabilityRequestError,
  GENERIC_AVAILABILITY_ERROR,
  classifyAvailabilityErrorCode,
  formatAvailabilityError,
  retryingHelps,
  type AvailabilityErrorBody,
} from '@/lib/availability-errors';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import {
  NO_WATCH_CAPABILITIES,
  normalizeWatchCapabilities,
  type WatchCapabilities,
} from '@/lib/watch-windows';
import { siteTitleText } from '@/domain/place/matrix-rows';
import { queryKeys } from '@/queries/keys';
import type { PlaceStay } from './use-place-stay';

const MAX_NIGHTS_IN_VIEW = 30;

const CATALOG_STALE_MS = 5 * 60_000;

const RETRY_AUTOMATICALLY = false;

export interface PlaceNightsFailure {
  reason: string;
  retry: (() => void) | null;
}

export interface PlaceNightsView {
  days: readonly AvailabilityDay[];
  dates: readonly string[];
  campsites: readonly Campsite[];
  siteNames: ReadonlyMap<number, string>;
  capabilities: WatchCapabilities;
  reopensOn: string | undefined;
  failure: PlaceNightsFailure | null;
}

const NO_DAYS: readonly AvailabilityDay[] = [];
const NO_CAMPSITES: readonly Campsite[] = [];
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

function namesOf(campsites: readonly Campsite[] | undefined): ReadonlyMap<number, string> {
  if (!campsites) return NO_SITE_NAMES;
  const names = new Map<number, string>();
  for (const site of campsites) names.set(site.id, siteTitleText(site));
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
      if (!response.ok) {
        const body = (await response.json().catch(() => null)) as AvailabilityErrorBody | null;
        throw new AvailabilityRequestError(
          formatAvailabilityError(body, response.status),
          response.status,
          typeof body?.error === 'string' ? body.error : null,
        );
      }
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

  const { refetch } = grid;
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);

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
    campsites: Array.isArray(campsites) ? campsites : NO_CAMPSITES,
    siteNames,
    capabilities,
    reopensOn: seasonReopensOn(served?.season),
    failure: served ? null : failureOf(grid.error, retry),
  };
}

export function failureOf(error: unknown, retry: () => void): PlaceNightsFailure | null {
  if (error == null) return null;
  const stated = error instanceof AvailabilityRequestError ? error : null;
  const kind = classifyAvailabilityErrorCode(stated?.code ?? null);
  return {
    reason: stated?.message || GENERIC_AVAILABILITY_ERROR,
    retry: retryingHelps(kind) ? retry : null,
  };
}
