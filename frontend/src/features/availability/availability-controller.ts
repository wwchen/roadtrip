import { useMemo, useReducer } from 'react';
import type { MatrixFilters } from './matrix-rows';
import { DEFAULT_MATRIX_FILTERS } from './matrix-rows';
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
    filters: DEFAULT_MATRIX_FILTERS,
    siteColumnWidth: loadSiteColumnWidth(),
    calendarOpen: false,
    watchTarget: null,
  };
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
        calendarOpen: false,
        watchTarget: null,
      };
    case 'dateSelected': {
      const selectedDate = state.selectedDate === action.date ? null : action.date;
      return { ...state, selectedDate, sitesExpanded: selectedDate != null, armedBook: null };
    }
    case 'filtersChanged':
      return { ...state, filters: action.filters, armedBook: null };
    case 'siteSelected':
      return { ...state, selectedSiteId: action.campsiteId, armedBook: null };
    case 'bookingArmed':
      return { ...state, armedBook: action.booking };
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
