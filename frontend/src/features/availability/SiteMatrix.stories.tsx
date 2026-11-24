import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AddToCartState } from '@/api/availability-api';
import { SiteMatrix, type SiteMatrixProps } from './SiteMatrix';
import { DEFAULT_MATRIX_FILTERS } from './matrix-rows';
import { WeekNav } from './WeekNav';
import {
  STORY_CAMPSITES as CAMPSITES,
  STORY_RESERVATION_URL_TEMPLATES as RESERVATION_URL_TEMPLATES,
  STORY_WATCHED_DATE as WATCHED_DATE,
  STORY_WEEK_DATES as WEEK_DATES,
  STORY_WEEK_DAYS as DAYS,
} from './story-fixtures';
import './availability.css';

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
 * The catalog request failed. The week-level failures are `AvailabilityWeek`'s
 * stories; this is the nearest thing `SiteMatrix` itself can render.
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
