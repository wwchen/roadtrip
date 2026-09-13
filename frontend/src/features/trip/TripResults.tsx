// The campgrounds list: along the route when there is one, in view when there is not.
//
// Collapse state stays local and defaults closed on phones so results do not cover
// the route immediately after it is computed.
import { useState } from 'react';
import { Button, EmptyState, Icon } from '@ui';
import { token } from '@tokens';
import { inViewCopy } from '@/lib/strings';
import { useMapContext } from '@/map/context';
import { useMapStore } from '@/stores/mapStore';
import { CorridorSlider } from './CorridorSlider';
import { formatDistanceAlongRoute } from './route-summary';
import { visibleCards, type TripCard } from './trip-cards';
import type { SiteCountsById } from './useSiteCounts';
import { shouldAutoFocus } from '@/domain/trip/viewport';

/** Where a card click puts the camera: tight enough to see the pin, wide enough to place it. */
const CARD_FLY_ZOOM = 13;
const FLY_SPEED = 1.6;

const ROUTE_HEADING = 'Campgrounds along route';
const RATING_PREFIX = '★';

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
  siteCounts: SiteCountsById;
  /** False below the zoom gate, where the server sends no campgrounds. */
  campgroundsRequested: boolean;
  /** The whole viewport count, before the rendered list is capped. */
  totalInView: number;
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

  const visible = visibleCards(cards, { hiddenAgencies, campgroundsHidden });
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
        <span className="tb-results-count">{countLine(props, visible, total)}</span>
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
          ) : (
            visible.map((card) => {
              const sub = subLine(props, card);
              return (
                <button
                  type="button"
                  className="tb-card"
                  key={String(card.id)}
                  data-id={String(card.id)}
                  onClick={() => openCard(card)}
                >
                  <span className="tb-card-dot" style={{ background: token('--rt-layer-cg') }} />
                  <span className="tb-card-body">
                    <span className="tb-card-head">
                      <span className="tb-card-name">{card.name}</span>
                      {card.location ? (
                        <span className="tb-card-location">{card.location}</span>
                      ) : null}
                    </span>
                    {sub ? <span className="tb-card-sub">{sub}</span> : null}
                    <span className="tb-card-meta">
                      {props.variant === 'route' ? (
                        <RouteMeta card={card} />
                      ) : (
                        <ViewportMeta card={card} siteCounts={props.siteCounts} />
                      )}
                    </span>
                  </span>
                </button>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}

/** "3 of 12" only while something is filtered out — otherwise the second number is noise. */
function countLine(props: TripResultsProps, visible: TripCard[], total: number): string {
  // The rendered list is capped below the true viewport count: report against
  // that whole count rather than the (already-truncated) list length.
  if (props.variant === 'viewport' && visible.length < props.totalInView) {
    return `· ${visible.length} of ${props.totalInView}`;
  }
  const count = visible.length === total ? String(total) : `${visible.length} of ${total}`;
  if (props.variant === 'route') return `· ${count}`;
  // The checkable count waits for every visible card, so it never counts up from 0.
  if (visible.length === 0 || !visible.every((card) => card.hydrated)) return `· ${count}`;
  const checkable = visible.filter((card) => card.checkable).length;
  return `· ${count} · ${inViewCopy.checkableCount(checkable)}`;
}

/**
 * The empty-list copy, or null when the hidden-campgrounds card should render instead.
 *
 * Precedence: a computing/empty route or an unrequested/empty viewport outranks the
 * layer-off card, because those states explain themselves better than "turn it back
 * on" does. Only once neither applies does a hidden layer get its own card.
 */
function emptyCopy(props: TripResultsProps, total: number, campgroundsHidden: boolean): string | null {
  if (props.variant === 'route') {
    if (props.loading) return 'Looking for campgrounds along the route…';
    if (total === 0) return 'Pan the map or widen the corridor to find campgrounds.';
  } else {
    if (!props.campgroundsRequested) return inViewCopy.zoomIn;
    if (total === 0) return inViewCopy.none;
  }
  if (campgroundsHidden) return null;
  return 'All campgrounds hidden — re-enable a category in the legend.';
}

/** The route list reads the type; the in-view list reads the agency, as the design does. */
function subLine(props: TripResultsProps, card: TripCard): string {
  return props.variant === 'route' ? card.sub : card.agency || card.sub;
}

function RouteMeta({ card }: { card: TripCard }) {
  return card.routeKm != null ? (
    <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
  ) : null;
}

function ViewportMeta({ card, siteCounts }: { card: TripCard; siteCounts: SiteCountsById }) {
  const counts = siteCounts.get(String(card.id));
  return (
    <>
      {counts && counts.total > 0 ? <span>{inViewCopy.sites(counts.total)}</span> : null}
      {card.rating != null ? <span>{`${RATING_PREFIX} ${card.rating.toFixed(1)}`}</span> : null}
      {card.hydrated && !card.checkable ? <span>{inViewCopy.notCheckable}</span> : null}
    </>
  );
}
