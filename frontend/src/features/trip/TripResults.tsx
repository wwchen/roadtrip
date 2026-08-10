// The campgrounds along the route.
//
// Port of `renderResults` / `bindResultsHead` from web/topbar.js. Three pieces of that
// function are gone rather than translated:
//
//   - **The scroll save/restore is gone.** `renderResults` rewrote the list's
//     `innerHTML` on every corridor refresh and had to put `scrollTop` back, or a
//     background refresh would yank the user to the top mid-scan. React keeps the node.
//   - **`bindResultsHead`'s re-binding is gone.** It re-attached the collapse listener
//     after every render and used a `data-bound` attribute to avoid stacking handlers.
//   - **`tripResults.legendBound` is gone.** It subscribed once to the legend's change
//     event to re-filter the list; the hidden-agency set is store state here, so the
//     list re-renders because it reads it.
//
// The collapse state stays local and starts collapsed on a phone, which is a product
// decision the vanilla made for a good reason: an expanded list eats the whole screen
// right after the user asked to see a route on the map.
import { useState } from 'react';
import { token } from '@tokens';
import { useMapContext } from '@/map/context';
import { useMapStore } from '@/stores/mapStore';
import { CorridorSlider } from './CorridorSlider';
import { formatDistanceAlongRoute } from './route-summary';
import { compactSeasonLabel, visibleCards, type TripCard } from './trip-cards';
import { shouldAutoFocus } from '@/domain/trip/viewport';

/** Where a card click puts the camera: tight enough to see the pin, wide enough to place it. */
const CARD_FLY_ZOOM = 13;
const FLY_SPEED = 1.6;

export interface TripResultsProps {
  cards: readonly TripCard[];
  /** True while the corridor request is in flight, for the count line. */
  loading: boolean;
  corridorMiles: number;
  onCorridorMilesChange: (miles: number) => void;
}

export function TripResults({
  cards,
  loading,
  corridorMiles,
  onCorridorMilesChange,
}: TripResultsProps) {
  const { map } = useMapContext();
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));
  const selectPoi = useMapStore((s) => s.selectPoi);

  // Collapsed on a phone, expanded where there is room. `shouldAutoFocus` answers the
  // same question — "is this a desktop" — and having one reader of the breakpoint keeps
  // the two from disagreeing.
  const [collapsed, setCollapsed] = useState(() => !shouldAutoFocus());

  const visible = visibleCards(cards, { hiddenAgencies, campgroundsHidden });
  const total = cards.length;

  const openCard = (card: TripCard) => {
    // Fly, then select. The vanilla synthesised a map click at the pin's coordinates so
    // the layer handler would open the drawer — and needed a `suppressPinClick` flag so
    // that synthetic click did not also overwrite the destination input. Selecting the
    // POI directly is the same outcome with none of that: the drawer reads
    // `selectedPoiId`, and hydrates from the id.
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
        Campgrounds along route
        <span className="tb-results-count">
          {/* "3 of 12" only while something is filtered out — otherwise the second
              number is noise. */}
          · {visible.length === total ? total : `${visible.length} of ${total}`}
        </span>
        <ChevronIcon />
      </div>

      <div className="tb-results-body">
        <div className="tb-results-controls">
          <CorridorSlider miles={corridorMiles} onChange={onCorridorMilesChange} />
        </div>

        <div className="tb-results-cards" id="tb-results-cards">
          {visible.length === 0 ? (
            <div className="tb-card-empty">
              {loading
                ? 'Looking for campgrounds along the route…'
                : total === 0
                  ? 'Pan the map or widen the corridor to find campgrounds.'
                  : 'All campgrounds hidden — re-enable a category in the legend.'}
            </div>
          ) : (
            visible.map((card) => (
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
                  {card.sub ? <span className="tb-card-sub">{card.sub}</span> : null}
                  <span className="tb-card-meta">
                    <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
                    {card.rating?.[0] != null ? (
                      <span className="tb-card-rating">★ {card.rating[0].toFixed(1)}</span>
                    ) : null}
                    {card.sites ? <span className="tb-card-sites">{card.sites} sites</span> : null}
                    <CardSeason card={card} />
                  </span>
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

function CardSeason({ card }: { card: TripCard }) {
  const label = compactSeasonLabel(card.season, card.reservable);
  return label ? <span className="tb-card-season">{label}</span> : null;
}

/** Points down when collapsed, up when expanded — the CSS rotates it. */
function ChevronIcon() {
  return (
    <svg
      className="tb-results-chevron"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points="6 15 12 9 18 15" />
    </svg>
  );
}
