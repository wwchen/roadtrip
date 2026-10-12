// The campgrounds list: along the route when there is one, in view when there is not.
//
// Presentational. Which campgrounds a list holds, and why it is empty when it
// is, are decided by whoever owns the list (`useInViewCampgrounds`,
// `useRouteCampgrounds`) and arrive here as a `ResultsList` — this file renders
// the three shapes one can take and nothing else. It filtered the cards itself
// until the viewport list began narrowing by agency before its card cap, at
// which point "no cards" stopped telling it which kind of empty it was looking
// at.
//
// Collapse state stays local and defaults closed on phones so results do not cover
// the route immediately after it is computed.
import { useState, type ReactNode } from 'react';
import { Button, EmptyState, Icon } from '@ui';
import { filterCopy, inViewCopy, listCopy, routeListCopy, stayCopy } from '@/lib/strings';
import { useNow } from '@/lib/use-now';
import { relativeTime } from '@/lib/watch-format';
import { useMapContext } from '@/map/context';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { countLine as facetCountLine, type InViewCard } from './campground-cards';
import { CorridorSlider } from './CorridorSlider';
import { formatDateShort } from './DateWindowFields';
import { facetsFor, type FacetState } from './facets';
import { formatDistanceAlongRoute } from './route-summary';
import type { ResultsList } from './results-list';
import { stayTallies, stayToneOf, type StayTone } from './stay-check';
import type { TripCard } from './trip-cards';
import type { CurrentStayCheck } from './useStayCheck';
import { shouldAutoFocus } from '@/domain/trip/viewport';

/** Where a card click puts the camera: tight enough to see the pin, wide enough to place it. */
const CARD_FLY_ZOOM = 13;
const FLY_SPEED = 1.6;

const RATING_PREFIX = '★';

/** How often "Checked 4m ago" is re-read, so it ages on screen rather than when something else re-renders. */
const CHECKED_AGO_TICK_MS = 30_000;

/** A facet's icon, class, and state word, keyed by state rather than chained ternaries. */
const FACET_APPEARANCE: Record<FacetState, { icon: string; className: string; stateWord: string }> = {
  match: { icon: 'check', className: 'tb-facet', stateWord: filterCopy.facetMatches },
  miss: { icon: 'close', className: 'tb-facet tb-facet--miss', stateWord: filterCopy.facetMisses },
  'no-data': { icon: 'help', className: 'tb-facet tb-facet--no-data', stateWord: filterCopy.noData },
};

/** A stay result line's colour, by tone. */
const STAY_LINE_CLASS: Record<StayTone, string> = {
  open: 'tb-stay tb-stay--open',
  few: 'tb-stay tb-stay--few',
  full: 'tb-stay tb-stay--full',
  unknown: 'tb-stay tb-stay--unknown',
};

/** A card's dot once the check has answered for it; until then it keeps the campground colour. */
const STAY_DOT_CLASS: Record<StayTone, string> = {
  open: 'tb-card-dot tb-card-dot--open',
  few: 'tb-card-dot tb-card-dot--few',
  full: 'tb-card-dot tb-card-dot--full',
  unknown: 'tb-card-dot tb-card-dot--unknown',
};

interface RouteProps {
  variant: 'route';
  list: ResultsList<TripCard>;
  corridorMiles: number;
  onCorridorMilesChange: (miles: number) => void;
}

interface ViewportProps {
  variant: 'viewport';
  list: ResultsList<InViewCard>;
  /** The availability check of the current stay, the one the cards were ordered by; null when there is none. */
  stayCheck: CurrentStayCheck | null;
}

export type TripResultsProps = RouteProps | ViewportProps;

export function TripResults(props: TripResultsProps) {
  const { map } = useMapContext();
  const selectPoi = useMapStore((s) => s.selectPoi);

  // Collapsed on a phone, expanded where there is room. `shouldAutoFocus` answers the
  // same question — "is this a desktop" — and having one reader of the breakpoint keeps
  // the two from disagreeing.
  const [collapsed, setCollapsed] = useState(() => !shouldAutoFocus());

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
        {props.variant === 'route' ? routeListCopy.heading : inViewCopy.heading}
        <span className="tb-results-count">{countLine(props)}</span>
        {/* Points up when expanded; `.tb-results.collapsed` rotates it. */}
        <Icon name="chevron-up" className="tb-results-chevron" aria-hidden="true" />
      </div>

      <div className="tb-results-body">
        {props.variant === 'route' ? (
          <div className="tb-results-controls">
            <CorridorSlider miles={props.corridorMiles} onChange={props.onCorridorMilesChange} />
          </div>
        ) : props.stayCheck ? (
          <StayStatus check={props.stayCheck} />
        ) : null}

        <div className="tb-results-cards" id="tb-results-cards">
          {props.variant === 'route' ? (
            <ListBody
              list={props.list}
              renderCard={(card) => (
                <Card key={String(card.id)} card={card} onOpen={openCard} sub={card.sub} meta={<RouteMeta card={card} />} />
              )}
            />
          ) : (
            <ListBody
              list={props.list}
              renderCard={(card) => (
                <Card
                  key={String(card.id)}
                  card={card}
                  onOpen={openCard}
                  sub={card.agency || card.sub}
                  meta={<ViewportMeta card={card} stayCheck={props.stayCheck} />}
                  facets={<FacetRow card={card} />}
                  tone={toneFor(card.summary.id, props.stayCheck)}
                />
              )}
            />
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The three shapes a list can take.
 *
 * Generic over the card so each variant carries its own card type through the
 * call. The alternative is to read `list.cards` where the props union is still
 * open and cast it back — at exactly the point where a cast could be wrong.
 */
function ListBody<C extends TripCard>({
  list,
  renderCard,
}: {
  list: ResultsList<C>;
  renderCard: (card: C) => ReactNode;
}) {
  const setOverlayHidden = useMapStore((s) => s.setOverlayHidden);

  if (list.kind === 'empty') return <div className="tb-card-empty">{list.message}</div>;
  if (list.kind === 'layer-off') {
    return (
      <EmptyState
        icon="eye-off"
        title={listCopy.layerOffTitle}
        body={listCopy.layerOffBody(list.inView)}
        actions={
          <Button variant="primary" size="sm" onClick={() => setOverlayHidden('cg', false)}>
            {listCopy.layerOffAction}
          </Button>
        }
      />
    );
  }
  return <>{list.cards.map(renderCard)}</>;
}

function Card({
  card,
  onOpen,
  sub,
  meta,
  facets,
  tone = null,
}: {
  card: TripCard;
  onOpen: (card: TripCard) => void;
  sub: string;
  meta: ReactNode;
  facets?: ReactNode;
  /** How the check answered for this card; null keeps the dot the campground colour. */
  tone?: StayTone | null;
}) {
  return (
    <button type="button" className="tb-card" data-id={String(card.id)} onClick={() => onOpen(card)}>
      <span className={tone ? STAY_DOT_CLASS[tone] : 'tb-card-dot'} aria-hidden="true" />
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

/**
 * "3 of 12" only while something is held back or capped — otherwise the second
 * number is noise. A list that is not `ready` has nothing to count.
 */
function countLine(props: TripResultsProps): string {
  const { list } = props;
  if (list.kind !== 'ready') return '· 0';
  const shown = list.cards.length;
  const count = shown === list.total ? String(list.total) : `${shown} of ${list.total}`;
  if (props.variant === 'route') return `· ${count}`;
  const checkable = list.cards.filter((card) => card.checkable).length;
  return `· ${count} · ${inViewCopy.checkableCount(checkable)}`;
}

function RouteMeta({ card }: { card: TripCard }) {
  return card.routeKm != null ? (
    <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
  ) : null;
}

function ViewportMeta({ card, stayCheck }: { card: InViewCard; stayCheck: CurrentStayCheck | null }) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const line = facetCountLine(card.summary, siteType);
  return (
    <>
      {stayCheck ? <StayLine id={card.summary.id} check={stayCheck} /> : null}
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
        return (
          <span
            key={facet.key}
            className={appearance.className}
            title={facet.state === 'no-data' ? filterCopy.noData : undefined}
            aria-label={`${facet.label}, ${appearance.stateWord}`}
          >
            <Icon name={appearance.icon} aria-hidden="true" />
            {facet.label}
          </span>
        );
      })}
    </span>
  );
}

/**
 * The availability check, above the in-view cards: how far it has got while it
 * runs, then what it found and how old that is.
 */
function StayStatus({ check }: { check: CurrentStayCheck }) {
  const now = useNow(CHECKED_AGO_TICK_MS, check.observedAt !== null);
  const tallies = stayTallies(check.asked, check.results);
  const parts = check.running
    ? [stayCopy.progress(tallies.answered, check.asked.length)]
    : [
        stayCopy.summary(
          stayCopy.range(formatDateShort(check.query.start), formatDateShort(check.query.end)),
          tallies.withSite,
          tallies.full,
        ),
        ...(tallies.closed > 0 ? [stayCopy.closed(tallies.closed)] : []),
        ...(tallies.refused > 0 ? [stayCopy.refused(tallies.refused)] : []),
        ...(tallies.unknown > 0 ? [stayCopy.unknown(tallies.unknown)] : []),
        ...(check.observedAt ? [stayCopy.checkedAgo(relativeTime(check.observedAt, now))] : []),
      ];
  return (
    <div className="tb-results-controls tb-stay-status" aria-live="polite">
      <span>{parts.join(' · ')}</span>
    </div>
  );
}

/** The tone of a card's answer from the check; null before it answers, or when it was not asked. */
function toneFor(id: number, check: CurrentStayCheck | null): StayTone | null {
  const result = check?.results[id];
  return result ? stayToneOf(result) : null;
}

/** One card's answer from the check; nothing for a card the check did not ask about. */
function StayLine({ id, check }: { id: number; check: CurrentStayCheck }) {
  const result = check.results[id];
  if (!result) {
    return check.running && check.asked.includes(id) ? (
      <span className="tb-stay tb-stay--unknown">{stayCopy.pending}</span>
    ) : null;
  }
  const className = STAY_LINE_CLASS[stayToneOf(result)];
  if (result.kind === 'error') return <span className={className}>{stayCopy.errorFor(result.code)}</span>;
  const { status, open_sites: openSites } = result.summary;
  return <span className={className}>{stayCopy.result[status](openSites)}</span>;
}
