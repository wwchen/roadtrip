import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { STAY_ANSWER_EVENT, STAY_DONE_EVENT } from '@/api/availability-api';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useStayCheckStore } from '@/stores/stayCheckStore';
import { eventStreamResponse, openEventStream, type OpenEventStream } from '@/test/fetch-stub';
import { CHECK_IDLE_TIMEOUT_MS } from './stay-check';
import { useStayCheck } from './useStayCheck';

const ASKED = [21, 22];
/** Short of the idle limit, so a sign of life inside it keeps the check going. */
const MOSTLY_IDLE_MS = CHECK_IDLE_TIMEOUT_MS - 1_000;

const OBSERVED_AT = '2026-10-12T11:56:00Z';

const answer = (poiId: number, observedAt: string | null = OBSERVED_AT) => ({
  event: STAY_ANSWER_EVENT,
  data: {
    poi_id: poiId,
    outcome: 'answered',
    stay: {
      status: 'open',
      open_sites: 3,
      checked_sites: 9,
      from_cache: false,
      ...(observedAt === null ? {} : { observed_at: observedAt }),
    },
  },
});

/** The check's stream: the next request answers with whatever [respond] builds from its signal. */
function stubStream(respond: (signal: AbortSignal | null | undefined) => Response): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: unknown, init?: RequestInit) => respond(init?.signal)),
  );
}

describe('useStayCheck', () => {
  beforeEach(() => {
    useCampgroundFilterStore.getState().reset();
    useCampgroundFilterStore.getState().setDateWindow({ start: '2026-09-11', end: '2026-09-13' });
    useStayCheckStore.getState().reset();
    useStayCheckStore.setState({ retryAt: null });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  test('a stream that goes quiet past the idle limit is cut off and reported as timed out', async () => {
    vi.useFakeTimers();
    stubStream((signal) => openEventStream(signal).response);
    const { result } = renderHook(() => useStayCheck(ASKED));

    act(() => result.current.check());
    await act(() => vi.advanceTimersByTimeAsync(CHECK_IDLE_TIMEOUT_MS));

    expect(useStayCheckStore.getState()).toMatchObject({ running: false, failure: 'timed_out' });
  });

  test('keep-alives are signs of life: a slow check that keeps writing is not cut off', async () => {
    vi.useFakeTimers();
    let stream!: OpenEventStream;
    stubStream((signal) => {
      stream = openEventStream(signal);
      return stream.response;
    });
    const { result } = renderHook(() => useStayCheck(ASKED));

    act(() => result.current.check());
    for (let beat = 0; beat < 3; beat += 1) {
      await act(() => vi.advanceTimersByTimeAsync(MOSTLY_IDLE_MS));
      act(() => stream.ping());
    }
    expect(useStayCheckStore.getState().running).toBe(true);

    await act(async () => {
      stream.send(answer(21));
      stream.send(answer(22));
      stream.send({ event: STAY_DONE_EVENT, data: { outcome: 'complete' } });
      stream.close();
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(useStayCheckStore.getState()).toMatchObject({ running: false, failure: null });
  });

  test('reports a deadline-reached check as timed out even though every campground answered', async () => {
    // The server answers `timed_out` for whoever was still waiting when its deadline fired, so
    // every campground has an answer — a count or a gap check would read this as a clean finish.
    stubStream(() =>
      eventStreamResponse([
        answer(21),
        { event: STAY_ANSWER_EVENT, data: { poi_id: 22, outcome: 'timed_out' } },
        { event: STAY_DONE_EVENT, data: { outcome: 'deadline_reached' } },
      ]),
    );
    const { result } = renderHook(() => useStayCheck(ASKED));

    await act(async () => {
      result.current.check();
      await vi.waitFor(() => expect(useStayCheckStore.getState().running).toBe(false));
    });

    expect(useStayCheckStore.getState().failure).toBe('timed_out');
    expect(Object.keys(useStayCheckStore.getState().results)).toEqual(['21', '22']);
  });

  test('a stream that closes before done was cut off, whatever had answered', async () => {
    stubStream(() => eventStreamResponse([answer(21), answer(22)]));
    const { result } = renderHook(() => useStayCheck(ASKED));

    await act(async () => {
      result.current.check();
      await vi.waitFor(() => expect(useStayCheckStore.getState().running).toBe(false));
    });

    expect(useStayCheckStore.getState().failure).toBe('failed');
  });

  test('dates the check by the instant the server says its oldest answer was observed', async () => {
    stubStream(() =>
      eventStreamResponse([
        answer(21, '2026-10-12T11:56:00Z'),
        answer(22, '2026-10-12T11:30:00Z'),
        { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
      ]),
    );
    const { result } = renderHook(() => useStayCheck(ASKED));

    await act(async () => {
      result.current.check();
      await vi.waitFor(() => expect(useStayCheckStore.getState().running).toBe(false));
    });

    expect(useStayCheckStore.getState().observedAt).toBe('2026-10-12T11:30:00Z');
  });

  // A regression guard, not a test that drove the code: the old `observedAtOf` also
  // answered now when an answer reported no age, so this passed before it was written.
  // It pins that behaviour across the move to `stay.observed_at`.
  test('an answer with no observation to date it by is dated now', async () => {
    stubStream(() =>
      eventStreamResponse([
        answer(21, null),
        { event: STAY_ANSWER_EVENT, data: { poi_id: 22, outcome: 'timed_out' } },
        { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
      ]),
    );
    const { result } = renderHook(() => useStayCheck(ASKED));

    const before = Date.now();
    await act(async () => {
      result.current.check();
      await vi.waitFor(() => expect(useStayCheckStore.getState().running).toBe(false));
    });
    const after = Date.now();

    const { failure, observedAt } = useStayCheckStore.getState();
    expect(failure).toBeNull();
    expect(Date.parse(observedAt ?? '')).toBeGreaterThanOrEqual(before);
    expect(Date.parse(observedAt ?? '')).toBeLessThanOrEqual(after);
  });
});
