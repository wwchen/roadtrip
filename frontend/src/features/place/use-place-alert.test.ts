import { createElement, type ReactNode } from 'react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { dayFull, dayOpen } from '@/domain/place/stay-fixtures';
import { TRIGGER_KIND_EMAIL_NOTIFY } from '@/lib/watch-triggers';
import { NO_WATCH_CAPABILITIES, type WatchCapabilities } from '@/lib/watch-windows';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';
import { createTestQueryClient } from '@/test/query-client';
import { usePlaceAlert } from './use-place-alert';
import type { PlaceNightsView } from './use-place-nights';

const POI_ID = 21;
const OPEN_SITES = 3;
const HTTP_UNAUTHORIZED = 401;

const FIRST = '2026-10-25';
const SECOND = '2026-10-26';
const THIRD = '2026-10-27';
const DATES = [FIRST, SECOND, THIRD];

const CAN_EMAIL: WatchCapabilities = {
  triggerKinds: new Set([TRIGGER_KIND_EMAIL_NOTIFY]),
  addToCart: 'unsupported',
};

const watchable = (date: string) => ({ ...dayFull(date), watchable: true });

function nightsView(over: Partial<PlaceNightsView> = {}): PlaceNightsView {
  return {
    days: [dayOpen(FIRST, OPEN_SITES), watchable(SECOND), watchable(THIRD)],
    dates: DATES,
    siteNames: new Map(),
    capabilities: CAN_EMAIL,
    reopensOn: undefined,
    ...over,
  };
}

const emptyWatchList = () => jsonResponse({ total: 0, limit: 0, offset: 0, watches: [] });

function mount(nights: PlaceNightsView) {
  const client = createTestQueryClient();
  const settled = () =>
    waitFor(() =>
      expect(client.getQueryCache().getAll().every((query) => query.state.status !== 'pending')).toBe(true),
    );
  return {
    settled,
    ...renderHook(() => usePlaceAlert(POI_ID, nights), {
      wrapper: ({ children }: { children: ReactNode }) =>
        createElement(QueryClientProvider, { client }, children),
    }),
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usePlaceAlert', () => {
  test('offers the first shut night in view, not the first night', async () => {
    stubFetch(emptyWatchList());

    const { result } = mount(nightsView());

    await waitFor(() => expect(result.current.row).not.toBeNull());
    expect(result.current.row).toMatchObject({ night: SECOND, gate: 'ready' });
  });

  test('the night it offers is always one the strip is showing', async () => {
    stubFetch(emptyWatchList());

    const { result } = mount(nightsView());

    await waitFor(() => expect(result.current.row).not.toBeNull());
    expect(DATES).toContain(result.current.row?.night);
  });

  test('offers nothing when every night in view is open', async () => {
    stubFetch(emptyWatchList());

    const { result, settled } = mount(
      nightsView({ days: DATES.map((date) => dayOpen(date, OPEN_SITES)) }),
    );

    await settled();
    expect(result.current.row).toBeNull();
  });

  test('skips a shut night the user is already watching', async () => {
    stubFetch(
      jsonResponse({
        total: 1,
        limit: 1,
        offset: 0,
        watches: [
          {
            id: 1,
            targets: [],
            poi_id: POI_ID,
            campsite_filters: {},
            start_date: SECOND,
            end_date: THIRD,
            trigger_kinds: [TRIGGER_KIND_EMAIL_NOTIFY],
            trigger_config: {},
            stop_when_triggered: true,
            status: 'active',
            created_at: '2026-10-01T00:00:00Z',
            updated_at: '2026-10-01T00:00:00Z',
          },
        ],
      }),
    );

    const { result } = mount(nightsView());

    await waitFor(() => expect(result.current.row?.night).toBe(THIRD));
  });

  test('a provider that cannot notify anyone blocks the row rather than hiding it', async () => {
    stubFetch(emptyWatchList());

    const { result } = mount(nightsView({ capabilities: NO_WATCH_CAPABILITIES }));

    await waitFor(() => expect(result.current.row).not.toBeNull());
    expect(result.current.row).toMatchObject({ night: SECOND, gate: 'blocked' });
  });

  test('an anonymous reader gets the step that unlocks the offer', async () => {
    stubFetch(jsonResponse({ error: 'unauthorized' }, HTTP_UNAUTHORIZED));

    const { result } = mount(nightsView());

    await waitFor(() => expect(result.current.row).not.toBeNull());
    expect(result.current.row).toMatchObject({ night: SECOND, gate: 'signed-out' });
  });

  test('a watch list that failed for any other reason offers nothing to act on', async () => {
    stubFetch(jsonResponse({ error: 'boom' }, 500));

    const { result, settled } = mount(nightsView());

    await settled();
    expect(result.current.row).toBeNull();
  });
});
