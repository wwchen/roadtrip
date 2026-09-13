import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AddToCartState, AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import { SiteMatrix, type SiteMatrixProps } from './SiteMatrix';
import { DEFAULT_MATRIX_FILTERS } from './matrix-rows';
import { WeekNav } from './WeekNav';
import './availability.css';

/**
 * `AvailabilityWeek` (`frontend/src/features/availability/AvailabilityWeek.tsx`) is
 * the component the drawer mounts, but hosting it here would pull in far more than
 * the grid itself: `useToast` needs `<ToastProvider>` (app-wide, not part of the
 * Storybook preview), `usePoiWatches`/`useWatchMutations` are a third React Query
 * key this file would have to fabricate on top of the two the brief names, and
 * `useSettingsStore` is another store to seed. None of that is what makes the grid
 * worth a story — the cell states are — and `SiteMatrix` draws every one of them
 * from plain props with no hooks of its own beyond local popover-anchor state. This
 * file stories `SiteMatrix` instead, per the brief's fallback.
 *
 * One consequence: `SiteMatrix` has no fetch of its own, so there is no true
 * "the week request failed" banner to show — that lives in `AvailabilityWeek`'s
 * `WeekSurface`. The closest state in `SiteMatrix`'s own vocabulary is its catalog
 * load failing (`SitesFailedToLoad` below), which is what this file uses in its
 * place.
 */
const WEEK_DATES = [
  '2026-09-08',
  '2026-09-09',
  '2026-09-10',
  '2026-09-11',
  '2026-09-12',
  '2026-09-13',
  '2026-09-14',
];

/** A date already covered by one of the user's active watches, across every site. */
const WATCHED_DATE = WEEK_DATES[4];

const CAMPSITES: Campsite[] = [
  campsite({ id: 101, name: 'A12', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop A' }),
  campsite({ id: 102, name: 'A14', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop A' }),
  campsite({
    id: 103,
    name: 'B02',
    kind: 'rv',
    kind_label: 'RV site with hookups',
    loop_name: 'Loop B',
    electric_hookups: true,
    water_hookups: true,
  }),
  campsite({ id: 104, name: 'B05', kind: 'rv', kind_label: 'RV site with hookups', loop_name: 'Loop B' }),
  campsite({ id: 105, name: 'C01', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop C' }),
];

/** One status per site per day, in `WEEK_DATES` order. */
const STATUS_ROWS: Record<number, AvailabilityDay['status'][]> = {
  101: ['available', 'reserved', 'available', 'available', 'unknown', 'closed', 'available'],
  102: ['reserved', 'reserved', 'available', 'reserved', 'reserved', 'reserved', 'available'],
  103: ['available', 'available', 'available', 'available', 'available', 'available', 'available'],
  104: ['first_come', 'first_come', 'unknown', 'first_come', 'closed', 'first_come', 'first_come'],
  105: ['unknown', 'unknown', 'unknown', 'unknown', 'unknown', 'unknown', 'unknown'],
};

/** A deep link for every site, so an `available` cell is a real booking button. */
const RESERVATION_URL_TEMPLATES: Record<string, string> = Object.fromEntries(
  CAMPSITES.map((site) => [String(site.id), 'https://booking.example.com/sites/{start_date}/{end_date}']),
);

const DAYS: AvailabilityDay[] = WEEK_DATES.map((date, dayIndex) => ({
  date,
  status: 'available',
  watchable: true,
  cells: Object.fromEntries(
    CAMPSITES.map((site) => {
      const status = STATUS_ROWS[site.id][dayIndex];
      return [
        String(site.id),
        { status, watchable: status === 'reserved' || status === 'first_come' },
      ];
    }),
  ),
}));

function campsite(over: Partial<Campsite> & Pick<Campsite, 'id' | 'name' | 'kind' | 'kind_label'>): Campsite {
  return {
    campground_id: 1,
    equipment: [],
    attributes: [],
    data_provider: 'recgov',
    data_provider_ref: String(over.id),
    booking_system: 'Recreation.gov',
    ...over,
  };
}

const weekActions = (
  <WeekNav
    startIso={WEEK_DATES[0]}
    endIso={WEEK_DATES[WEEK_DATES.length - 1]}
    showEarliest
    canGoBack
    canGoForward
    onPrev={() => {}}
    onNext={() => {}}
    onEarliest={() => {}}
    onPickDate={() => {}}
  />
);

const BASE_VIEW: SiteMatrixProps['view'] = {
  filters: DEFAULT_MATRIX_FILTERS,
  siteColumnWidth: 128,
  selectedSiteId: null,
  armedBook: null,
  watchedDates: new Set([WATCHED_DATE]),
  watchGate: 'ready',
  cart: 'ready' as AddToCartState,
  cartAction: null,
};

const NOOP_EVENTS: SiteMatrixProps['events'] = {
  filtersChanged: () => {},
  siteColumnResized: () => {},
  siteSelected: () => {},
  bookingArmed: () => {},
  bookingOpened: () => {},
  cartRequested: () => {},
  signInRequested: () => {},
  settingsRequested: () => {},
  dateSelected: () => {},
  watchOpened: () => {},
};

const meta = {
  title: 'Availability/SiteMatrix',
  parameters: {
    docs: {
      description: {
        component:
          'The site-by-date grid: available, reserved, first-come and unknown cells across ' +
          'two site kinds, plus the states that replace it — loading, an empty catalog, and ' +
          'a catalog that failed to load.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** A loaded week: available, reserved, first-come, closed and unknown cells across five sites. */
export const Loaded: Story = {
  render: () => (
    <SiteMatrix
      days={DAYS}
      catalog={{
        campsites: CAMPSITES,
        reservationUrlTemplates: RESERVATION_URL_TEMPLATES,
        state: 'success',
        error: null,
        retry: () => {},
      }}
      view={BASE_VIEW}
      events={NOOP_EVENTS}
      weekActions={weekActions}
    />
  ),
};

/** Before the catalog resolves: the nav and legend are real, the rows are placeholders. */
export const Loading: Story = {
  render: () => (
    <SiteMatrix
      days={DAYS}
      catalog={{
        campsites: [],
        reservationUrlTemplates: undefined,
        state: 'loading',
        error: null,
        retry: () => {},
      }}
      view={BASE_VIEW}
      events={NOOP_EVENTS}
      weekActions={weekActions}
    />
  ),
};

/** A campground with no reservable sites in its catalog. */
export const Empty: Story = {
  render: () => (
    <SiteMatrix
      days={DAYS}
      catalog={{ campsites: [], reservationUrlTemplates: undefined, state: 'success', error: null, retry: () => {} }}
      view={BASE_VIEW}
      events={NOOP_EVENTS}
      weekActions={weekActions}
    />
  ),
};

/**
 * The catalog request failed. Not the week-level failure `AvailabilityWeek`'s
 * `WeekSurface` shows for a failed availability fetch — see this file's header
 * comment — but the nearest thing `SiteMatrix` itself can render.
 */
export const SitesFailedToLoad: Story = {
  render: () => (
    <SiteMatrix
      days={DAYS}
      catalog={{
        campsites: [],
        reservationUrlTemplates: undefined,
        state: 'error',
        error: "Couldn't load sites",
        retry: () => {},
      }}
      view={BASE_VIEW}
      events={NOOP_EVENTS}
      weekActions={weekActions}
    />
  ),
};
