import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useShallow } from 'zustand/react/shallow';
import { Banner, Button, Skeleton } from '@ui';
import { signIn } from '@/api/auth-api';
import type { StayCheckOutcome, WatchStatus } from '@/api/generated/api-types';
import { createWatch } from '@/api/watches-api';
import { nightOf } from '@/domain/place/night-state';
import type { AlertRowProps } from '@/domain/place/AlertRow';
import { availabilitySupported } from '@/domain/poi/campground-detail';
import type { PoiPageParts, PoiTypeComponent } from '@/domain/poi/types/common';
import { poiPageFor } from '@/domain/poi/types/registry';
import type { PoiPageVariant } from '@/domain/poi/PoiPageShell';
import { isWatchUnauthorized, useInvalidateWatches, watchListQuery } from '@/domain/watch/queries';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import type { FlatPoiFeature } from '@/lib/poi';
import { clearPoiFromUrl, showPoiInUrl } from '@/lib/poi-url';
import { alertCopy, placeCopy, stayCopy } from '@/lib/strings';
import { TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY } from '@/lib/watch-triggers';
import {
  indexWatchesByWindow,
  stayEndDate,
  supportsWatchAlerts,
  watchedDates,
} from '@/lib/watch-windows';
import { usePoiDetail } from '@/queries/poi-detail';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { PlaceSheet, type PlaceArrival } from './PlaceSheet';
import { usePlaceNights, type PlaceNightsView } from './use-place-nights';
import { usePlaceStay, type PlaceStay, type PlaceStayCheck } from './use-place-stay';

const SHEET_VARIANT: PoiPageVariant = 'panel';

const MAP_ARRIVAL: PlaceArrival = 'from-map';
const COLD_ARRIVAL: PlaceArrival = 'cold';

const DEFAULT_STAY_NIGHTS = 1;

const DEADLINE_REACHED: StayCheckOutcome = 'deadline_reached';

const ACTIVE_WATCHES: WatchStatus = 'active';

const ALERT_CHANNELS: readonly string[] = [TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY];

const ALERT_STOPS_WHEN_TRIGGERED = true;

const RETRY_AUTOMATICALLY = false;

const LOADING_HEIGHT = '220px';

const NO_DRIVE_ORIGIN = null;

const NO_NIGHTS = {
  days: [],
  dates: [],
  selected: null,
  onSelect: () => undefined,
} as const;

const NO_WATCHED_NIGHTS: ReadonlySet<string> = new Set();

export interface PlaceSurfaceProps {
  poiId: string | number | null;
  arrival: PlaceArrival;
  onClose?: () => void;
}

function numericPoiId(value: string | number | null | undefined): number | null {
  if (value == null) return null;
  const id = Number(value);
  return Number.isInteger(id) ? id : null;
}

export function PlaceSurface({ poiId, arrival, onClose }: PlaceSurfaceProps) {
  const query = usePoiDetail(poiId);
  const feature = query.data;
  const Poi = poiPageFor(feature?.properties);
  const id = numericPoiId(poiId);

  if (poiId == null) return null;

  if (!feature) {
    if (!query.isError) return (
      <PlaceChrome>
        <Skeleton height={LOADING_HEIGHT} />
      </PlaceChrome>
    );
    return (
      <PlaceChrome>
        <Banner
          status="error"
          title={placeCopy.loadFailed}
          actions={
            <Button variant="secondary" size="sm" iconStart="refresh" onClick={() => void query.refetch()}>
              {placeCopy.tryAgain}
            </Button>
          }
        >
          <p>{placeCopy.loadFailedBody}</p>
        </Banner>
      </PlaceChrome>
    );
  }

  if (!Poi) {
    return (
      <PlaceChrome>
        <Banner status="warning" title={placeCopy.noDetailView}>
          <p>{placeCopy.category(String(feature.properties?.category ?? ''))}</p>
        </Banner>
      </PlaceChrome>
    );
  }

  return id !== null && availabilitySupported(feature.properties) ? (
    <StayPlace feature={feature} poiId={id} Poi={Poi} arrival={arrival} onClose={onClose} />
  ) : (
    <PlainPlace feature={feature} Poi={Poi} onClose={onClose} />
  );
}

function PlaceChrome({ children }: { children: ReactNode }) {
  return <div className="rt-place-chrome">{children}</div>;
}

export function MapPlaceSurface() {
  const selectedPoiId = useMapStore((s) => s.selectedPoiId);
  const clearSelectedPoi = useMapStore((s) => s.clearSelectedPoi);

  useEffect(() => {
    if (selectedPoiId != null) showPoiInUrl(selectedPoiId);
    else clearPoiFromUrl();
  }, [selectedPoiId]);

  return <PlaceSurface poiId={selectedPoiId} arrival={MAP_ARRIVAL} onClose={clearSelectedPoi} />;
}

interface PlainPlaceProps {
  feature: FlatPoiFeature;
  Poi: PoiTypeComponent;
  onClose?: () => void;
}

function PlainPlace({ feature, Poi, onClose }: PlainPlaceProps) {
  return (
    <Poi
      feature={feature}
      variant={SHEET_VARIANT}
      onClose={onClose}
      render={(parts: PoiPageParts) => (
        <PlaceSheet
          name={parts.name}
          subtitle={parts.subtitle}
          arrival={COLD_ARRIVAL}
          nights={NO_NIGHTS}
          blocks={parts.blocks}
        />
      )}
    />
  );
}

interface StayPlaceProps extends PlainPlaceProps {
  poiId: number;
  arrival: PlaceArrival;
}

function StayPlace({ feature, poiId, Poi, arrival, onClose }: StayPlaceProps) {
  const stay = usePinnedStay();
  const check = usePlaceStay(poiId, stay);
  const nights = usePlaceNights(poiId, stay);
  const { row, failed } = usePlaceAlert(poiId, nights);
  const [picked, setPicked] = useState<string | null>(null);

  const selected = picked !== null && nights.dates.includes(picked) ? picked : (nights.dates[0] ?? null);
  const answer = check.kind === 'answered' ? check.answer : null;
  const notice = stayRetry(check) ?? (failed ? <Banner status="warning" title={alertCopy.createFailed} /> : null);

  return (
    <Poi
      feature={feature}
      variant={SHEET_VARIANT}
      onClose={onClose}
      render={(parts: PoiPageParts) => (
        <PlaceSheet
          name={parts.name}
          subtitle={parts.subtitle}
          arrival={arrival}
          verdict={{
            name: parts.name,
            answer,
            origin: NO_DRIVE_ORIGIN,
            ...(nights.reopensOn ? { reopensOn: nights.reopensOn } : null),
          }}
          {...(notice ? { notice } : null)}
          nights={{
            days: nights.days,
            dates: nights.dates,
            selected,
            onSelect: setPicked,
          }}
          {...(row ? { alert: row } : null)}
          siteNames={nights.siteNames}
          blocks={parts.blocks}
        />
      )}
    />
  );
}

function stayRetry(check: PlaceStayCheck): ReactNode | null {
  if (check.kind !== 'unanswered') return null;
  const line = check.outcome === DEADLINE_REACHED ? stayCopy.timedOut : stayCopy.failed;
  return (
    <Banner
      status="warning"
      title={line}
      actions={
        <Button variant="secondary" size="sm" iconStart="refresh" onClick={check.retry}>
          {stayCopy.checkAgain}
        </Button>
      }
    />
  );
}

function usePinnedStay(): PlaceStay {
  const { dateWindow, siteType, groupSize } = useCampgroundFilterStore(
    useShallow((s) => ({ dateWindow: s.dateWindow, siteType: s.siteType, groupSize: s.groupSize })),
  );
  return useMemo(() => {
    if (dateWindow) return { start: dateWindow.start, end: dateWindow.end, siteType, groupSize };
    const today = localToday();
    return {
      start: localYmd(today),
      end: localYmd(addLocalDays(today, DEFAULT_STAY_NIGHTS)),
      siteType,
      groupSize,
    };
  }, [dateWindow, siteType, groupSize]);
}

function shutNightInView(nights: PlaceNightsView, watched: ReadonlySet<string>): string | null {
  const byDate = new Map(nights.days.map((day) => [day.date, day]));
  for (const date of nights.dates) {
    const day = byDate.get(date);
    if (!day || !day.watchable || watched.has(date)) continue;
    if (nightOf(day).state !== 'open') return date;
  }
  return null;
}

interface PlaceAlert {
  row: AlertRowProps | null;
  failed: boolean;
}

function usePlaceAlert(poiId: number, nights: PlaceNightsView): PlaceAlert {
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
    () => (watches.data ? watchedDates(indexWatchesByWindow(watches.data.watches, poiId)) : NO_WATCHED_NIGHTS),
    [watches.data, poiId],
  );

  const failed = created.isError;
  const night = shutNightInView(nights, watched);
  if (night === null || !nights.dates.includes(night)) return { row: null, failed };

  if (!supportsWatchAlerts(nights.capabilities)) return { row: { night, gate: 'blocked', onCreate }, failed };
  if (watches.error) {
    if (!isWatchUnauthorized(watches.error)) return { row: null, failed };
    return { row: { night, gate: 'signed-out', onCreate, onSignIn: () => signIn() }, failed };
  }
  if (!watches.data) return { row: null, failed };
  return { row: { night, gate: 'ready', pending: created.isPending, onCreate }, failed };
}
