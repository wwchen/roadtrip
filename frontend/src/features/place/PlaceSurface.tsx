import { useEffect, useState, type ReactNode } from 'react';
import { Banner, Button, Skeleton } from '@ui';
import { availabilitySupported } from '@/domain/poi/campground-detail';
import type { PoiPageParts, PoiTypeComponent } from '@/domain/poi/types/common';
import { poiPageFor } from '@/domain/poi/types/registry';
import type { PoiPageVariant } from '@/domain/poi/PoiPageShell';
import type { FlatPoiFeature } from '@/lib/poi';
import { clearPoiFromUrl, showPoiInUrl } from '@/lib/poi-url';
import { alertCopy, placeCopy, stayCopy } from '@/lib/strings';
import { usePoiDetail } from '@/queries/poi-detail';
import { useMapStore } from '@/stores/mapStore';
import { PlaceSheet, type PlaceArrival } from './PlaceSheet';
import { usePinnedStay } from './place-stay-window';
import { usePlaceAlert } from './use-place-alert';
import { usePlaceNights } from './use-place-nights';
import { stayRetryLine, usePlaceStay, type PlaceStayCheck } from './use-place-stay';

const SHEET_VARIANT: PoiPageVariant = 'panel';

const MAP_ARRIVAL: PlaceArrival = 'from-map';
const COLD_ARRIVAL: PlaceArrival = 'cold';

const LOADING_HEIGHT = '220px';

const NO_DRIVE_ORIGIN = null;

const NO_NIGHTS = {
  days: [],
  dates: [],
  selected: null,
  onSelect: () => undefined,
} as const;

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
    if (!query.isError) {
      return (
        <PlaceChrome>
          <Skeleton height={LOADING_HEIGHT} />
        </PlaceChrome>
      );
    }
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

  const stale = query.isError ? <RefreshFailed onRetry={() => void query.refetch()} /> : null;

  return id !== null && availabilitySupported(feature.properties) ? (
    <StayPlace feature={feature} poiId={id} Poi={Poi} arrival={arrival} stale={stale} onClose={onClose} />
  ) : (
    <PlainPlace feature={feature} Poi={Poi} stale={stale} onClose={onClose} />
  );
}

function RefreshFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <Banner
      status="warning"
      title={placeCopy.refreshFailed}
      actions={
        <Button variant="secondary" size="sm" iconStart="refresh" onClick={onRetry}>
          {placeCopy.tryAgain}
        </Button>
      }
    >
      <p>{placeCopy.refreshFailedBody}</p>
    </Banner>
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
  stale: ReactNode;
  onClose?: () => void;
}

function PlainPlace({ feature, Poi, stale, onClose }: PlainPlaceProps) {
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
          {...(stale ? { notice: stale } : null)}
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

function StayPlace({ feature, poiId, Poi, arrival, stale, onClose }: StayPlaceProps) {
  const stay = usePinnedStay();
  const check = usePlaceStay(poiId, stay);
  const nights = usePlaceNights(poiId, stay);
  const { row, failed } = usePlaceAlert(poiId, nights);
  const [picked, setPicked] = useState<string | null>(null);

  const selected = picked !== null && nights.dates.includes(picked) ? picked : (nights.dates[0] ?? null);
  const own = stayRetry(check) ?? (failed ? <Banner status="warning" title={alertCopy.createFailed} /> : null);
  const notice =
    stale || own ? (
      <>
        {stale}
        {own}
      </>
    ) : null;

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
          {...(check.kind === 'unanswered'
            ? null
            : {
                verdict: {
                  name: parts.name,
                  answer: check.kind === 'answered' ? check.answer : null,
                  origin: NO_DRIVE_ORIGIN,
                  ...(nights.reopensOn ? { reopensOn: nights.reopensOn } : null),
                },
              })}
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
  return (
    <Banner
      status="warning"
      title={stayRetryLine(check.outcome)}
      actions={
        <Button variant="secondary" size="sm" iconStart="refresh" onClick={check.retry}>
          {stayCopy.checkAgain}
        </Button>
      }
    />
  );
}
