import { useMemo, useReducer } from 'react';
import type { MatrixFilters } from './matrix-rows';
import { DEFAULT_MATRIX_FILTERS } from './matrix-rows';
import type { CartAction, CartActionEvent } from './cart-action';
import { nextCartAction } from './cart-action';
import type { ArmedBook } from './SiteMatrix';
import { loadSiteColumnWidth } from './site-column';

export interface WatchTarget {
  anchor: HTMLElement;
  date: string;
}

export interface AvailabilityViewState {
  weekStart: Date;
  selectedDate: string | null;
  selectedSiteId: string | null;
  sitesExpanded: boolean;
  armedBook: ArmedBook | null;
  /**
   * The one direct add-to-cart in play, if any.
   *
   * Lives here beside `armedBook` so disarm, week changes and refetch have a
   * single place that decides what survives them — the reason the arming state
   * was put in the reducer in the first place.
   */
  cartAction: CartAction | null;
  filters: MatrixFilters;
  siteColumnWidth: number;
  calendarOpen: boolean;
  watchTarget: WatchTarget | null;
}

export type AvailabilityViewAction =
  | { type: 'weekChanged'; weekStart: Date }
  | { type: 'dateSelected'; date: string }
  | { type: 'filtersChanged'; filters: MatrixFilters }
  | { type: 'siteSelected'; campsiteId: string | null }
  | { type: 'bookingArmed'; booking: ArmedBook | null }
  | { type: 'cartAction'; event: CartActionEvent }
  | { type: 'siteColumnResized'; width: number }
  | { type: 'calendarToggled'; open: boolean }
  | { type: 'watchOpened'; target: WatchTarget }
  | { type: 'watchClosed' }
  | { type: 'sitesToggled' };

export function createAvailabilityViewState(weekStart: Date): AvailabilityViewState {
  return {
    weekStart,
    selectedDate: null,
    selectedSiteId: null,
    sitesExpanded: false,
    armedBook: null,
    cartAction: null,
    filters: DEFAULT_MATRIX_FILTERS,
    siteColumnWidth: loadSiteColumnWidth(),
    calendarOpen: false,
    watchTarget: null,
  };
}

/**
 * What a navigation leaves behind.
 *
 * A hold that is still running survives: it is a real browser doing real work
 * for this user, and hiding its spinner because they changed a filter would
 * lose the only sign that anything is happening. A settled one does not —
 * its result was already shown, and its cell may not even be on screen.
 */
function survivingCartAction(action: CartAction | null): CartAction | null {
  return action?.kind === 'pending' ? action : null;
}

export function availabilityViewReducer(
  state: AvailabilityViewState,
  action: AvailabilityViewAction,
): AvailabilityViewState {
  switch (action.type) {
    case 'weekChanged':
      return {
        ...state,
        weekStart: action.weekStart,
        selectedDate: null,
        selectedSiteId: null,
        sitesExpanded: false,
        armedBook: null,
        cartAction: survivingCartAction(state.cartAction),
        calendarOpen: false,
        watchTarget: null,
      };
    case 'dateSelected': {
      const selectedDate = state.selectedDate === action.date ? null : action.date;
      return {
        ...state,
        selectedDate,
        sitesExpanded: selectedDate != null,
        armedBook: null,
        cartAction: survivingCartAction(state.cartAction),
      };
    }
    case 'filtersChanged':
      return {
        ...state,
        filters: action.filters,
        armedBook: null,
        cartAction: survivingCartAction(state.cartAction),
      };
    case 'siteSelected':
      return {
        ...state,
        selectedSiteId: action.campsiteId,
        armedBook: null,
        cartAction: survivingCartAction(state.cartAction),
      };
    case 'bookingArmed':
      return { ...state, armedBook: action.booking };
    case 'cartAction': {
      const cartAction = nextCartAction(state.cartAction, action.event);
      // Identity check, not a value check: the machine returns the same
      // reference for a refused transition (a late answer, a double-click), and
      // rebuilding state around it would re-render the whole grid for nothing.
      if (cartAction === state.cartAction) return state;
      // Starting a hold closes the popover that offered it — the cell now shows
      // the hold's own state and there is nothing left to choose.
      return { ...state, cartAction, armedBook: cartAction?.kind === 'pending' ? null : state.armedBook };
    }
    case 'siteColumnResized':
      return { ...state, siteColumnWidth: action.width };
    case 'calendarToggled':
      return { ...state, calendarOpen: action.open };
    case 'watchOpened':
      return { ...state, watchTarget: action.target };
    case 'watchClosed':
      return { ...state, watchTarget: null };
    case 'sitesToggled':
      return { ...state, sitesExpanded: !state.sitesExpanded };
  }
}

export function useAvailabilityController(initialWeek: Date) {
  const [state, dispatch] = useReducer(
    availabilityViewReducer,
    initialWeek,
    createAvailabilityViewState,
  );

  const actions = useMemo(
    () => ({
      changeWeek: (weekStart: Date) => dispatch({ type: 'weekChanged', weekStart }),
      selectDate: (date: string) => dispatch({ type: 'dateSelected', date }),
      changeFilters: (filters: MatrixFilters) => dispatch({ type: 'filtersChanged', filters }),
      selectSite: (campsiteId: string | null) => dispatch({ type: 'siteSelected', campsiteId }),
      armBooking: (booking: ArmedBook | null) => dispatch({ type: 'bookingArmed', booking }),
      cartActionChanged: (event: CartActionEvent) => dispatch({ type: 'cartAction', event }),
      resizeSiteColumn: (width: number) => dispatch({ type: 'siteColumnResized', width }),
      toggleCalendar: (open: boolean) => dispatch({ type: 'calendarToggled', open }),
      openWatch: (anchor: HTMLElement, date: string) =>
        dispatch({ type: 'watchOpened', target: { anchor, date } }),
      closeWatch: () => dispatch({ type: 'watchClosed' }),
      toggleSites: () => dispatch({ type: 'sitesToggled' }),
    }),
    [],
  );

  return { state, actions };
}
