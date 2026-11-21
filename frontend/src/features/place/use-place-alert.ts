import { useCallback, useMemo } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { signIn } from '@/api/auth-api';
import type { WatchStatus } from '@/api/generated/api-types';
import { createWatch } from '@/api/watches-api';
import type { AlertRowProps } from '@/domain/place/AlertRow';
import { nightOf } from '@/domain/place/night-state';
import { isWatchUnauthorized, useInvalidateWatches, watchListQuery } from '@/domain/watch/queries';
import { TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY } from '@/lib/watch-triggers';
import {
  indexWatchesByWindow,
  stayEndDate,
  supportsWatchAlerts,
  watchedDates,
} from '@/lib/watch-windows';
import type { PlaceNightsView } from './use-place-nights';

const ACTIVE_WATCHES: WatchStatus = 'active';

const ALERT_CHANNELS: readonly string[] = [TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY];

const ALERT_STOPS_WHEN_TRIGGERED = true;

const RETRY_AUTOMATICALLY = false;

const NO_WATCHED_NIGHTS: ReadonlySet<string> = new Set();

export interface PlaceAlert {
  row: AlertRowProps | null;
  failed: boolean;
}

export function shutNightInView(
  nights: PlaceNightsView,
  watched: ReadonlySet<string>,
): string | null {
  const byDate = new Map(nights.days.map((day) => [day.date, day]));
  for (const date of nights.dates) {
    const day = byDate.get(date);
    if (!day || !day.watchable || watched.has(date)) continue;
    if (nightOf(day).state !== 'open') return date;
  }
  return null;
}

export function usePlaceAlert(poiId: number, nights: PlaceNightsView): PlaceAlert {
  const watches = useQuery({
    ...watchListQuery({ status: ACTIVE_WATCHES, poiId: String(poiId) }),
    retry: RETRY_AUTOMATICALLY,
  });
  const invalidateWatches = useInvalidateWatches();
  const channel = ALERT_CHANNELS.find((kind) => nights.capabilities.triggerKinds.has(kind));

  const created = useMutation({
    mutationFn: (night: string) =>
      createWatch({
        poi_id: poiId,
        campsite_filters: {},
        start_date: night,
        end_date: stayEndDate(night),
        trigger_kinds: channel ? [channel] : [],
        trigger_config: {},
        stop_when_triggered: ALERT_STOPS_WHEN_TRIGGERED,
      }),
    onSuccess: invalidateWatches,
  });

  const { mutate } = created;
  const onCreate = useCallback((night: string) => mutate(night), [mutate]);

  const watched = useMemo(
    () =>
      watches.data
        ? watchedDates(indexWatchesByWindow(watches.data.watches, poiId))
        : NO_WATCHED_NIGHTS,
    [watches.data, poiId],
  );

  const failed = created.isError;
  const night = shutNightInView(nights, watched);
  if (night === null || !nights.dates.includes(night)) return { row: null, failed };

  if (!supportsWatchAlerts(nights.capabilities)) {
    return { row: { night, gate: 'blocked', onCreate }, failed };
  }
  if (watches.error) {
    if (!isWatchUnauthorized(watches.error)) return { row: null, failed };
    return { row: { night, gate: 'signed-out', onCreate, onSignIn: () => signIn() }, failed };
  }
  if (!watches.data) return { row: null, failed };
  return { row: { night, gate: 'ready', pending: created.isPending, onCreate }, failed };
}
