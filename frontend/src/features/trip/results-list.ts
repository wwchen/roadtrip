// What a results section shows — decided once, per list, before the component.
//
// Both lists (the corridor's and the viewport's) are the same small machine:
// some campgrounds, some switches that can hide them, and a handful of reasons
// the result can come back empty. The component used to answer that question
// itself, from the cards it had been handed — which stopped working once the
// viewport list began narrowing by agency upstream of its card cap, because a
// view whose every campground is switched off then arrives with no cards at
// all, exactly like a view with nothing in it.
//
// So the answer is built here instead, where the facts still exist, and the
// component renders it. The list's population is filtered ONCE, on the way in.
import { inViewCopy, listCopy, routeListCopy } from '@/lib/strings';
import type { InViewCard } from './campground-cards';
import { visibleCards, type CardFilter, type TripCard } from './trip-cards';

/**
 * A results section's contents, closed so the component has no conditions of
 * its own left to get wrong.
 *
 * `total` is what `cards` is counted against: every corridor campground for the
 * route list, every campground the filter matched for the viewport's. They are
 * equal when nothing is held back, and the head then shows one number.
 */
export type ResultsList<C extends TripCard> =
  | { kind: 'empty'; message: string }
  | { kind: 'layer-off'; inView: number }
  | { kind: 'ready'; cards: readonly C[]; total: number };

export interface RouteListFacts extends CardFilter {
  /** True while the corridor request is in flight. */
  loading: boolean;
  cards: readonly TripCard[];
}

/**
 * The corridor list.
 *
 * A computing or empty corridor outranks the layer-off card: those states
 * explain themselves, where "turn it back on" would be answering a question the
 * user did not ask.
 */
export function routeList(facts: RouteListFacts): ResultsList<TripCard> {
  const visible = visibleCards(facts.cards, facts);
  if (facts.loading && facts.cards.length === 0) return { kind: 'empty', message: routeListCopy.loading };
  if (facts.cards.length === 0) return { kind: 'empty', message: routeListCopy.none };
  if (facts.campgroundsHidden) return { kind: 'layer-off', inView: facts.cards.length };
  if (visible.length === 0) return { kind: 'empty', message: listCopy.allAgenciesHidden };
  return { kind: 'ready', cards: visible, total: facts.cards.length };
}

export interface InViewListFacts {
  /** False below the campground zoom gate, where nothing is asked. */
  requested: boolean;
  /** True once the search or the summaries have failed. */
  failed: boolean;
  /** True while either request is in flight. */
  loading: boolean;
  /** True when the legend has switched the whole campground layer off. */
  campgroundsHidden: boolean;
  /** Campgrounds in the viewport, before any switch — the server's own count. */
  inBoundary: number;
  /** Those of them the campground filter matched, before any switch. */
  matching: number;
  /** Campgrounds in view the legend leaves visible: what turning the layer on would draw. */
  inView: number;
  /** The cards to render: already narrowed by agency, capped and ranked. */
  cards: readonly InViewCard[];
  /** What `cards` is counted against — matches the filter AND survives the legend. */
  total: number;
}

/**
 * The viewport list.
 *
 * The order is the precedence: nothing asked, then a failure, then a wait, then
 * the two ways a view can hold nothing to show — neither of which is the
 * legend's doing, so neither may blame it. Only past those does a switch get
 * the blame, and the layer's card outranks the agency rows' sentence because it
 * carries the switch that undoes it.
 */
export function inViewList(facts: InViewListFacts): ResultsList<InViewCard> {
  if (!facts.requested) return { kind: 'empty', message: inViewCopy.zoomIn };
  if (facts.failed) return { kind: 'empty', message: inViewCopy.failed };
  if (facts.loading && facts.cards.length === 0) return { kind: 'empty', message: inViewCopy.loading };
  if (facts.inBoundary === 0 || facts.matching === 0) return { kind: 'empty', message: inViewCopy.none };
  if (facts.campgroundsHidden) return { kind: 'layer-off', inView: facts.inView };
  if (facts.cards.length === 0) return { kind: 'empty', message: listCopy.allAgenciesHidden };
  return { kind: 'ready', cards: facts.cards, total: facts.total };
}
