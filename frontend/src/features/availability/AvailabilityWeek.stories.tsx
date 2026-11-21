import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { PoiCampsitesAvailabilityResponse, WatchCapabilities } from '@/api/availability-api';
import type { WatchListResponse } from '@/api/watches-api';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import type { PoiFeature } from '@/lib/poi';
import { TRIGGER_KIND_ATC, TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_SLACK_NOTIFY } from '@/lib/watch-triggers';
import {
  StoryApi,
  answer,
  isolatedDocs,
  storyJson,
  storyPending,
  type StoryRoute,
  type StoryRoutes,
} from '@/test/story-api';
import { AvailabilityWeek } from './AvailabilityWeek';
import {
  STORY_CAMPSITES_RESPONSE,
  STORY_POI_ID,
  STORY_POI_NAME,
  STORY_WATCH,
  STORY_WEEK_START,
  storyWeekDays,
} from './story-fixtures';
import { WEEK_DAYS } from './useWeekAvailability';
import './availability.css';

const ZION_THEME_CLASS = 'theme-roadtrip-zion';
const DOCS_FRAME_HEIGHT_PX = 760;
const PANEL_WIDTH_PX = 560;
const BOOKING_SYSTEM = 'Recreation.gov';
const HORIZON_DAYS = 180;
const LATEST_DATE = localYmd(addLocalDays(parseLocalYmd(STORY_WEEK_START), HORIZON_DAYS));
const REOPENS_ON = '2027-05-15';

const FRESH_AGE_SECONDS = 240;
const STALE_AGE_SECONDS = 1_500;
const CACHE_TTL_SECONDS = 600;

const HTTP_UNAUTHORIZED = 401;
const HTTP_TOO_MANY_REQUESTS = 429;
const HTTP_INTERNAL_ERROR = 500;
const HTTP_BAD_GATEWAY = 502;
const HTTP_GATEWAY_TIMEOUT = 504;
const UPSTREAM_SERVICE_UNAVAILABLE = 503;

const AVAILABILITY_PATH = `/api/pois/${STORY_POI_ID}/campsites/availability`;
const REFRESH_PATH = `${AVAILABILITY_PATH}/refresh`;
const CAMPSITES_PATH = `/api/pois/${STORY_POI_ID}/campsites`;
const WATCHES_PATH = '/api/watches';

const FEATURE: PoiFeature = {
  id: STORY_POI_ID,
  properties: {
    name: STORY_POI_NAME,
    booking_system: BOOKING_SYSTEM,
    earliest_date: STORY_WEEK_START,
    latest_date: LATEST_DATE,
  },
};

const ALL_CHANNELS: WatchCapabilities = {
  trigger_kinds: [TRIGGER_KIND_SLACK_NOTIFY, TRIGGER_KIND_EMAIL_NOTIFY, TRIGGER_KIND_ATC],
  add_to_cart: { state: 'ready', provider: 'recgov', provider_display: BOOKING_SYSTEM },
};

const NO_CHANNELS: WatchCapabilities = {
  trigger_kinds: [],
  add_to_cart: { state: 'unsupported' },
};

const WATCH_LIST: WatchListResponse = { total: 1, limit: 50, offset: 0, watches: [STORY_WATCH] };

function week(over: Partial<PoiCampsitesAvailabilityResponse> = {}): StoryRoute {
  return (url) => {
    const start = url.searchParams.get('start_date') ?? STORY_WEEK_START;
    const end = url.searchParams.get('end_date') ?? localYmd(addLocalDays(parseLocalYmd(start), WEEK_DAYS));
    const body: PoiCampsitesAvailabilityResponse = {
      poi_id: STORY_POI_ID,
      start_date: start,
      end_date: end,
      latest_date: LATEST_DATE,
      state: 'success',
      cache: { hit: true, age_seconds: FRESH_AGE_SECONDS, ttl_seconds: CACHE_TTL_SECONDS },
      days: storyWeekDays(start),
      watch_capabilities: ALL_CHANNELS,
      ...over,
    };
    return storyJson(body);
  };
}

function upstreamFailure(status: number, error: string, detail: string, upstreamStatus?: number): StoryRoute {
  return answer(
    {
      error,
      detail,
      provider_display: BOOKING_SYSTEM,
      ...(upstreamStatus !== undefined ? { upstream_status: upstreamStatus } : {}),
    },
    status,
  );
}

const RATE_LIMITED = upstreamFailure(
  HTTP_TOO_MANY_REQUESTS,
  'rate_limited',
  'Recreation.gov asked us to slow down',
  HTTP_TOO_MANY_REQUESTS,
);

interface Overrides {
  availability?: StoryRoute;
  campsites?: StoryRoute;
  watches?: StoryRoute;
}

function routes({ availability = week(), campsites, watches }: Overrides = {}): StoryRoutes {
  return {
    [AVAILABILITY_PATH]: availability,
    [REFRESH_PATH]: answer({}),
    [CAMPSITES_PATH]: campsites ?? answer(STORY_CAMPSITES_RESPONSE),
    [WATCHES_PATH]: watches ?? answer(WATCH_LIST),
  };
}

function failsAfterFirst(first: StoryRoute, rest: StoryRoute): StoryRoute {
  let calls = 0;
  return (url, init) => {
    calls += 1;
    return calls === 1 ? first(url, init) : rest(url, init);
  };
}

function Week({ overrides }: { overrides: () => Overrides }) {
  const [table] = useState(() => routes(overrides()));
  useEffect(() => {
    document.documentElement.classList.add(ZION_THEME_CLASS);
  }, []);
  return (
    <StoryApi routes={table}>
      <div style={{ maxWidth: PANEL_WIDTH_PX }}>
        <AvailabilityWeek feature={FEATURE} />
      </div>
    </StoryApi>
  );
}

const weekStory = (overrides: () => Overrides = () => ({})): Story => ({
  render: () => <Week overrides={overrides} />,
});

const meta = {
  title: 'Availability/AvailabilityWeek',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The availability section of the campground drawer: the week grid, its freshness ' +
          'line, the day panel and site list, and every banner that replaces the grid. Each ' +
          'story runs the real queries against a stubbed API, so the week pages, cells arm ' +
          'and a fully booked day opens its day panel.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loaded: Story = weekStory();

export const Loading: Story = weekStory(() => ({ availability: storyPending }));

export const SignedOut: Story = weekStory(() => ({
  watches: answer({ error: 'unauthorized' }, HTTP_UNAUTHORIZED),
}));

export const WatchListFailed: Story = weekStory(() => ({
  watches: answer({ error: 'internal_error', detail: 'watch store unavailable' }, HTTP_INTERNAL_ERROR),
}));

export const AlertsUnsupported: Story = weekStory(() => ({
  availability: week({ watch_capabilities: NO_CHANNELS }),
}));

export const StaleCache: Story = weekStory(() => ({
  availability: week({ cache: { hit: true, age_seconds: STALE_AGE_SECONDS, ttl_seconds: CACHE_TTL_SECONDS } }),
}));

export const RateLimited: Story = weekStory(() => ({ availability: RATE_LIMITED }));

export const RateLimitedOnRefresh: Story = {
  ...weekStory(() => ({ availability: failsAfterFirst(week(), RATE_LIMITED) })),
  parameters: {
    docs: { description: { story: 'Click refresh: the refetch is throttled and the last week stays one click away.' } },
  },
};

export const BookingSiteError: Story = weekStory(() => ({
  availability: upstreamFailure(
    HTTP_BAD_GATEWAY,
    'upstream_5xx',
    'Recreation.gov answered 503 Service Unavailable',
    UPSTREAM_SERVICE_UNAVAILABLE,
  ),
}));

export const BookingSiteUnreachable: Story = weekStory(() => ({
  availability: upstreamFailure(HTTP_GATEWAY_TIMEOUT, 'upstream_unreachable', 'connect timed out after 10s'),
}));

export const UnclassifiedFailure: Story = weekStory(() => ({
  availability: answer(
    { error: 'provider_misconfigured', detail: 'no adapter registered for booking system "Camis"' },
    HTTP_INTERNAL_ERROR,
  ),
}));

export const ClosedForSeason: Story = weekStory(() => ({
  availability: week({ state: 'closed_for_season', days: [], season: { reopens_on: REOPENS_ON } }),
}));

export const NoData: Story = weekStory(() => ({ availability: week({ state: 'empty', days: [] }) }));

export const SitesFailedToLoad: Story = weekStory(() => ({
  campsites: answer({ error: 'internal_error', detail: 'campsite catalog query timed out' }, HTTP_INTERNAL_ERROR),
}));
