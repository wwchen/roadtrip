// The viewport list, start to finish: search, narrow, cap, hydrate, count.
//
// One owner, because the steps are not independent. The legend's agency
// switches have to narrow the id set BEFORE the card cap — the search answers
// for every agency, so capping first and dropping the switched-off rows
// afterwards leaves a handful of cards in any view a hidden agency dominates —
// and the counts the topbar shows have to be taken from the same narrowing, or
// the head and the filter block disagree with the legend beside them.
//
// The switches are therefore applied twice, to two different sources, and both
// are load-bearing:
//
//   1. by PIN agency, to the id set, before the cap. The pins are the only
//      thing that knows an id's agency before it is fetched, and the cap is
//      what makes knowing early matter.
//   2. by SUMMARY agency, to the hydrated cards. This is the authoritative
//      answer and it arrives last, so it catches whatever the pin set could
//      not: an id the pin loop had not returned yet, or a pan it still lags.
//
// Step 1 decides which 50 are worth fetching; step 2 decides what is true.
//
// Once an availability check of the current stay is back, the cards are ordered
// by its answers (`rankByStay`). Not before: while it runs every card keeps its
// place and fills in, so nothing moves under the pointer.
import { useMemo } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { hiddenAgencyPinIds, idsWithVisibleAgency } from '@/map/agencies';
import { bboxCenter } from '@/map/viewport';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { cardsFromSummaries, rankCards, type InViewCard } from './campground-cards';
import { inViewList, type ResultsList } from './results-list';
import { rankByStay } from './stay-check';
import { visibleCards } from './trip-cards';
import { useCampgroundSearch } from './useCampgroundSearch';
import { MAX_IN_VIEW_CARDS, useCampgroundSummaries } from './useCampgroundSummaries';
import { useCurrentStayCheck, type CurrentStayCheck } from './useStayCheck';

export interface InViewCampgrounds {
  /** What the results section renders. */
  list: ResultsList<InViewCard>;
  /** Campgrounds in view the legend leaves visible — the filter block's "in view". */
  inView: number;
  /** Those of them that pass the campground filter. */
  matching: number;
  /** Every id the search matched, agency-blind: the map's pin filter and the legend count it. */
  matchingIds: readonly number[];
  /** False below the zoom gate or while a route owns the list. */
  enabled: boolean;
  /** True once the search has failed, so the caller can stop publishing a stale filter. */
  failed: boolean;
  /**
   * The availability check of the current stay, the same read the cards were
   * ordered by — so each card's answer and the order it sits in cannot come from
   * two reads; null when there is none.
   */
  stayCheck: CurrentStayCheck | null;
  /**
   * What an availability check asks about: the cards on screen that a provider
   * can answer for, in the list's own order — nearest first, as the stream wants
   * them — never the order a previous check's answers sorted them into. Null
   * until the list is ready, so a list still loading does not read as "nothing
   * checkable".
   */
  checkableIds: readonly number[] | null;
}

export interface UseInViewCampgroundsOptions {
  /** True while something else (e.g. a route) already owns the list. */
  paused: boolean;
}

export function useInViewCampgrounds({ paused }: UseInViewCampgroundsOptions): InViewCampgrounds {
  const search = useCampgroundSearch({ paused });
  const campgroundsRequested = useMapStore((s) => s.campgroundsRequested);
  const viewportBbox = useMapStore((s) => s.viewport?.bbox ?? null);
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const viewportCampgrounds = useMapStore((s) => s.viewportCampgrounds);
  const stayCheck = useCurrentStayCheck();
  const stayResults = stayCheck && !stayCheck.running ? stayCheck.results : null;
  const rankFilter = useCampgroundFilterStore(
    useShallow((s) => ({ siteType: s.siteType, groupSize: s.groupSize, amenities: s.amenities })),
  );

  const hiddenIds = useMemo(
    () => hiddenAgencyPinIds(viewportCampgrounds, hiddenAgencies),
    [viewportCampgrounds, hiddenAgencies],
  );
  const shownIds = useMemo(() => idsWithVisibleAgency(search.ids, hiddenIds), [search.ids, hiddenIds]);
  const cappedIds = useMemo(() => shownIds.slice(0, MAX_IN_VIEW_CARDS), [shownIds]);
  const summaries = useCampgroundSummaries(cappedIds);

  const ranked = useMemo(() => {
    const built = cardsFromSummaries(cappedIds, summaries.byId, viewportBbox ? bboxCenter(viewportBbox) : null);
    return rankCards(visibleCards(built, { hiddenAgencies, campgroundsHidden }), rankFilter);
  }, [cappedIds, summaries.byId, viewportBbox, rankFilter, hiddenAgencies, campgroundsHidden]);
  const cards = useMemo(() => (stayResults ? rankByStay(ranked, stayResults) : ranked), [ranked, stayResults]);

  // With nothing hidden the server's own counts are the honest ones — they
  // survive a truncated search, which the id list does not. With something
  // hidden, the switched-off pins come off them.
  const matching = hiddenIds.size === 0 ? search.totalMatching : shownIds.length;
  const inView = Math.max(search.totalInBoundary - hiddenIds.size, matching);
  const failed = search.isError || summaries.isError;
  const loading = search.isFetching || summaries.isFetching;

  const list = useMemo(
    () =>
      inViewList({
        requested: campgroundsRequested && search.enabled,
        failed,
        loading,
        campgroundsHidden,
        agenciesHidden: hiddenAgencies.length > 0,
        inBoundary: search.totalInBoundary,
        matching: search.totalMatching,
        inView,
        cards,
        total: matching,
      }),
    [
      campgroundsRequested,
      search.enabled,
      search.totalInBoundary,
      search.totalMatching,
      failed,
      loading,
      campgroundsHidden,
      hiddenAgencies,
      inView,
      cards,
      matching,
    ],
  );

  // The rendered cards are `ranked` in another order, so its order is the one asked in.
  const checkableIds = useMemo(
    () => (list.kind === 'ready' ? ranked.filter((card) => card.checkable).map((card) => card.summary.id) : null),
    [list.kind, ranked],
  );

  return { list, inView, matching, matchingIds: search.ids, enabled: search.enabled, failed, stayCheck, checkableIds };
}
