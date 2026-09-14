// The campgrounds list: along the route when there is one, in view when there is not.
//
// Collapse state stays local and defaults closed on phones so results do not cover
// the route immediately after it is computed.
import { useState, type ReactNode } from 'react';
import { Button, EmptyState, Icon } from '@ui';
import { token } from '@tokens';
import { filterCopy, inViewCopy } from '@/lib/strings';
import { useMapContext } from '@/map/context';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { countLine as facetCountLine, type InViewCard } from './campground-cards';
import { CorridorSlider } from './CorridorSlider';
import { facetsFor, type FacetState } from './facets';
import { formatDistanceAlongRoute } from './route-summary';
import { visibleCards, type TripCard } from './trip-cards';
import { shouldAutoFocus } from '@/domain/trip/viewport';

/** Where a card click puts the camera: tight enough to see the pin, wide enough to place it. */
const CARD_FLY_ZOOM = 13;
const FLY_SPEED = 1.6;

const ROUTE_HEADING = 'Campgrounds along route';
const RATING_PREFIX = '★';

/** A facet's icon and class, keyed by state rather than chained ternaries. */
const FACET_APPEARANCE: Record<FacetState, { icon: string; className: string }> = {
  match: { icon: 'check', className: 'tb-facet' },
  miss: { icon: 'close', className: 'tb-facet tb-facet--miss' },
  'no-data': { icon: 'help', className: 'tb-facet tb-facet--no-data' },
};

interface CommonProps {
  cards: readonly TripCard[];
}

interface RouteProps extends CommonProps {
  variant: 'route';
  /** True while the corridor request is in flight, for the count line. */
  loading: boolean;
  corridorMiles: number;
  onCorridorMilesChange: (miles: number) => void;
}

interface ViewportProps extends CommonProps {
  variant: 'viewport';
  cards: readonly InViewCard[];
  /** False below the zoom gate, where nothing is asked. */
  campgroundsRequested: boolean;
  /** True while the search or the summaries are in flight and the list is empty. */
  loading: boolean;
  /** True once the search or the summaries have failed. */
  error: boolean;
  totalInBoundary: number;
  totalMatching: number;
}

export type TripResultsProps = RouteProps | ViewportProps;

export function TripResults(props: TripResultsProps) {
  const { cards } = props;
  const { map } = useMapContext();
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));
  const setOverlayHidden = useMapStore((s) => s.setOverlayHidden);
  const selectPoi = useMapStore((s) => s.selectPoi);

  // Collapsed on a phone, expanded where there is room. `shouldAutoFocus` answers the
  // same question — "is this a desktop" — and having one reader of the breakpoint keeps
  // the two from disagreeing.
  const [collapsed, setCollapsed] = useState(() => !shouldAutoFocus());

  const visible = visibleCards<TripCard>(cards, { hiddenAgencies, campgroundsHidden });
  const total = cards.length;
  const emptyMessage = emptyCopy(props, total, campgroundsHidden);

  const openCard = (card: TripCard) => {
    // Fly, then select. The drawer reads `selectedPoiId` and hydrates from the id.
    map?.flyTo({ center: [card.lng, card.lat], zoom: CARD_FLY_ZOOM, speed: FLY_SPEED });
    selectPoi(card.id);
  };

  return (
    <div className={`tb-results visible${collapsed ? ' collapsed' : ''}`} id="tb-results">
      <div
        className="tb-results-head"
        role="button"
        tabIndex={0}
        aria-expanded={!collapsed}
        onClick={() => setCollapsed((current) => !current)}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          setCollapsed((current) => !current);
        }}
      >
        {props.variant === 'route' ? ROUTE_HEADING : inViewCopy.heading}
        <span className="tb-results-count">{countLine(props, visible)}</span>
        {/* Points up when expanded; `.tb-results.collapsed` rotates it. */}
        <Icon name="chevron-up" className="tb-results-chevron" aria-hidden="true" />
      </div>

      <div className="tb-results-body">
        {props.variant === 'route' ? (
          <div className="tb-results-controls">
            <CorridorSlider miles={props.corridorMiles} onChange={props.onCorridorMilesChange} />
          </div>
        ) : null}

        <div className="tb-results-cards" id="tb-results-cards">
          {visible.length === 0 ? (
            emptyMessage === null ? (
              <EmptyState
                icon="eye-off"
                title="Campgrounds are switched off"
                body={`There are ${total} in view — the Campgrounds layer is turned off, so none are drawn.`}
                actions={
                  <Button variant="primary" size="sm" onClick={() => setOverlayHidden('cg', false)}>
                    Turn campgrounds back on
                  </Button>
                }
              />
            ) : (
              <div className="tb-card-empty">{emptyMessage}</div>
            )
          ) : props.variant === 'route' ? (
            visible.map((card) => (
              <Card key={String(card.id)} card={card} onOpen={openCard} sub={card.sub} meta={<RouteMeta card={card} />} />
            ))
          ) : (
            visibleCards(props.cards, { hiddenAgencies, campgroundsHidden }).map((card) => (
              <Card
                key={String(card.id)}
                card={card}
                onOpen={openCard}
                sub={card.agency || card.sub}
                meta={<ViewportMeta card={card} />}
                facets={<FacetRow card={card} />}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
}

function Card({
  card,
  onOpen,
  sub,
  meta,
  facets,
}: {
  card: TripCard;
  onOpen: (card: TripCard) => void;
  sub: string;
  meta: ReactNode;
  facets?: ReactNode;
}) {
  return (
    <button type="button" className="tb-card" data-id={String(card.id)} onClick={() => onOpen(card)}>
      <span className="tb-card-dot" style={{ background: token('--rt-layer-cg') }} />
      <span className="tb-card-body">
        <span className="tb-card-head">
          <span className="tb-card-name">{card.name}</span>
          {card.location ? <span className="tb-card-location">{card.location}</span> : null}
        </span>
        {sub ? <span className="tb-card-sub">{sub}</span> : null}
        <span className="tb-card-meta">{meta}</span>
        {facets}
      </span>
    </button>
  );
}

/** "3 of 12" only while something is filtered out or capped — otherwise the second number is noise. */
function countLine(props: TripResultsProps, visible: readonly TripCard[]): string {
  if (props.variant === 'route') {
    const total = props.cards.length;
    const count = visible.length === total ? String(total) : `${visible.length} of ${total}`;
    return `· ${count}`;
  }
  // All viewport cards are hydrated now, so the count is against the search's total.
  const denominator = props.totalMatching;
  const count = visible.length === denominator ? String(denominator) : `${visible.length} of ${denominator}`;
  if (visible.length === 0) return `· ${count}`;
  const checkable = visible.filter((card) => card.checkable).length;
  return `· ${count} · ${inViewCopy.checkableCount(checkable)}`;
}

/**
 * The empty-list copy, or null when the hidden-campgrounds card should render instead.
 *
 * Precedence: a computing/empty route or an unrequested/loading/empty viewport
 * outranks the layer-off card, because those states explain themselves better than
 * "turn it back on" does. Only once neither applies does a hidden layer get its own card.
 */
function emptyCopy(props: TripResultsProps, total: number, campgroundsHidden: boolean): string | null {
  if (props.variant === 'route') {
    if (props.loading) return 'Looking for campgrounds along the route…';
    if (total === 0) return 'Pan the map or widen the corridor to find campgrounds.';
  } else {
    if (!props.campgroundsRequested) return inViewCopy.zoomIn;
    if (props.error) return inViewCopy.failed;
    if (props.loading) return inViewCopy.loading;
    if (total === 0) return inViewCopy.none;
  }
  if (campgroundsHidden) return null;
  return 'All campgrounds hidden — re-enable a category in the legend.';
}

function RouteMeta({ card }: { card: TripCard }) {
  return card.routeKm != null ? (
    <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
  ) : null;
}

function ViewportMeta({ card }: { card: InViewCard }) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const line = facetCountLine(card.summary, siteType);
  return (
    <>
      {line ? <span>{line}</span> : null}
      {card.rating != null ? <span>{`${RATING_PREFIX} ${card.rating.toFixed(1)}`}</span> : null}
      {!card.checkable ? <span>{inViewCopy.notCheckable}</span> : null}
    </>
  );
}

function FacetRow({ card }: { card: InViewCard }) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const facets = facetsFor(card.summary, { siteType, groupSize, amenities });
  if (facets.length === 0) return null;
  return (
    <span className="tb-facets">
      {facets.map((facet) => {
        const appearance = FACET_APPEARANCE[facet.state];
        const stateWord =
          facet.state === 'match'
            ? filterCopy.facetMatches
            : facet.state === 'miss'
              ? filterCopy.facetMisses
              : filterCopy.noData;
        return (
          <span
            key={facet.key}
            className={appearance.className}
            title={facet.state === 'no-data' ? filterCopy.noData : undefined}
            aria-label={`${facet.label}, ${stateWord}`}
          >
            <Icon name={appearance.icon} aria-hidden="true" />
            {facet.label}
          </span>
        );
      })}
    </span>
  );
}
