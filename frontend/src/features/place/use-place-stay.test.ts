import { createElement, type ReactNode } from 'react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { STAY_ANSWER_EVENT, STAY_DONE_EVENT, STAY_STREAM_URL } from '@/api/availability-api';
import { eventStreamResponse, stubFetch } from '@/test/fetch-stub';
import { createTestQueryClient } from '@/test/query-client';
import { usePlaceStay, type PlaceStay } from './use-place-stay';

const POI_ID = 21;
const NEIGHBOUR_POI_ID = 22;
const OPEN_SITES = 8;
const CHECKED_SITES = 40;

const STAY: PlaceStay = {
  start: '2026-09-11',
  end: '2026-09-13',
  siteType: null,
  groupSize: null,
};

const ownAnswer = {
  poi_id: POI_ID,
  outcome: 'answered',
  stay: { status: 'open', open_sites: OPEN_SITES, checked_sites: CHECKED_SITES, from_cache: false },
} as const;

const neighbourAnswer = {
  poi_id: NEIGHBOUR_POI_ID,
  outcome: 'answered',
  stay: { status: 'full', open_sites: 0, checked_sites: CHECKED_SITES, from_cache: false },
} as const;

function mount() {
  const client = createTestQueryClient();
  return renderHook(() => usePlaceStay(POI_ID, STAY), {
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client }, children),
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('usePlaceStay', () => {
  test('resolves the one answer for the POI', async () => {
    const fetchStub = stubFetch(
      eventStreamResponse([
        { event: STAY_ANSWER_EVENT, data: neighbourAnswer },
        { event: STAY_ANSWER_EVENT, data: ownAnswer },
        { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
      ]),
    );

    const { result } = mount();

    await waitFor(() => expect(result.current.kind).toBe('answered'));
    expect(result.current).toMatchObject({ kind: 'answered', answer: ownAnswer });
    expect(fetchStub.last.url).toBe(STAY_STREAM_URL);
    expect(fetchStub.last.body).toMatchObject({
      poi_ids: [POI_ID],
      start_date: STAY.start,
      end_date: STAY.end,
    });
  });

  test('a deadline-reached done with no answer resolves unanswered, not checking', async () => {
    stubFetch(eventStreamResponse([{ event: STAY_DONE_EVENT, data: { outcome: 'deadline_reached' } }]));

    const { result } = mount();

    await waitFor(() => expect(result.current.kind).toBe('unanswered'));
    expect(result.current).toMatchObject({ kind: 'unanswered', outcome: 'deadline_reached' });
  });
});
