import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { cartActionFor, holdInFlight } from '@/domain/place/cart-action';
import { CART_HOLD_MS } from '@/lib/cart-hold';
import { jsonResponse } from '@/test/fetch-stub';
import { NO_WATCH_CAPABILITIES, type WatchCapabilities } from '@/lib/watch-windows';
import { useSettingsStore } from '@/stores/settingsStore';
import { usePlaceCart } from './use-place-cart';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';
const CAMPSITE_ID = '131744';
const OTHER_CAMPSITE_ID = '131745';
const NIGHT = '2026-10-29';
const MORNING_AFTER = '2026-10-30';
const OTHER_NIGHT = '2026-10-31';
const HOLDER = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const BAD_GATEWAY = 502;
const A_MINUTE_MS = 60_000;

const capabilities = (over: Partial<WatchCapabilities> = {}): WatchCapabilities => ({
  ...NO_WATCH_CAPABILITIES,
  ...over,
});

const held = () =>
  jsonResponse({
    status: 'completed',
    cart_url: CART_URL,
    provider: 'recgov',
    provider_display: HOLDER,
  });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  useSettingsStore.getState().closeSettings();
});

describe('usePlaceCart, the gate it publishes', () => {
  test('the four wire states each become the one branch written for them', () => {
    const ready = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));
    expect(ready.result.current.gate.state).toBe('ready');

    const out = renderHook(() => usePlaceCart(capabilities({ addToCart: 'signed_out' })));
    expect(out.result.current.gate.state).toBe('signed_out');

    const none = renderHook(() =>
      usePlaceCart(capabilities({ addToCart: 'no_credentials', addToCartProviderDisplay: HOLDER })),
    );
    expect(none.result.current.gate).toMatchObject({ state: 'no_credentials', holder: HOLDER });

    const off = renderHook(() => usePlaceCart(capabilities()));
    expect(off.result.current.gate.state).toBe('unsupported');
  });

  test('no_credentials sends the caller to the Booking section of Settings', () => {
    const { result } = renderHook(() => usePlaceCart(capabilities({ addToCart: 'no_credentials' })));
    const gate = result.current.gate;
    if (gate.state !== 'no_credentials') throw new Error(gate.state);

    act(() => gate.onOpenSettings());

    expect(useSettingsStore.getState()).toMatchObject({ open: true, tab: 'booking' });
  });

  test('nothing is in flight before anything is asked for', () => {
    const { result } = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));

    expect(result.current.actions.size).toBe(0);
  });
});

const ready = () => {
  const hook = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));
  const gate = hook.result.current.gate;
  if (gate.state !== 'ready') throw new Error(gate.state);
  return { hook, hold: gate.onAddToCart };
};

const actionAt = (
  hook: ReturnType<typeof ready>['hook'],
  campsiteId = CAMPSITE_ID,
  date = NIGHT,
) => cartActionFor(hook.result.current.actions, campsiteId, date);

describe('usePlaceCart, holding a site', () => {
  test('it posts the one night, and says whose cart it landed in', async () => {
    const fetchMock = vi.fn(async () => held());
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));

    expect(actionAt(hook)).toMatchObject({ kind: 'pending' });

    await waitFor(() =>
      expect(actionAt(hook)).toMatchObject({ kind: 'held', cartUrl: CART_URL, holder: HOLDER }),
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(ADD_TO_CART_URL);
    expect(JSON.parse(String(init.body))).toEqual({
      campsite_id: Number(CAMPSITE_ID),
      start_date: NIGHT,
      end_date: MORNING_AFTER,
    });
  });

  test('a second request while one is running sends nothing, here or anywhere', async () => {
    const fetchMock = vi.fn(async () => held());
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => {
      hold(CAMPSITE_ID, NIGHT);
      hold(CAMPSITE_ID, NIGHT);
      hold(OTHER_CAMPSITE_ID, OTHER_NIGHT);
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(holdInFlight(hook.result.current.actions)).toEqual({
      campsiteId: CAMPSITE_ID,
      date: NIGHT,
    });

    await waitFor(() => expect(actionAt(hook)?.kind).toBe('held'));
  });

  test('a held site keeps its hold when the next site is held', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => held()));

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));
    await waitFor(() => expect(actionAt(hook)?.kind).toBe('held'));

    act(() => hold(OTHER_CAMPSITE_ID, OTHER_NIGHT));
    await waitFor(() => expect(actionAt(hook, OTHER_CAMPSITE_ID, OTHER_NIGHT)?.kind).toBe('held'));

    expect(actionAt(hook)).toMatchObject({ kind: 'held', cartUrl: CART_URL });
    expect(hook.result.current.actions.size).toBe(2);
  });

  test('a site already held is never posted again', async () => {
    const fetchMock = vi.fn(async () => held());
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));
    await waitFor(() => expect(actionAt(hook)?.kind).toBe('held'));

    act(() => hold(CAMPSITE_ID, NIGHT));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(actionAt(hook)).toMatchObject({ kind: 'held' });
  });

  test('a refused hold becomes a failure naming the reason, not a silent revert', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          { error: 'cart_not_added', provider: 'recgov', provider_display: HOLDER },
          BAD_GATEWAY,
        ),
      ),
    );

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));

    await waitFor(() =>
      expect(actionAt(hook)).toEqual({
        kind: 'failed',
        cell: { campsiteId: CAMPSITE_ID, date: NIGHT },
        code: 'cart_not_added',
        holder: HOLDER,
      }),
    );
  });

  test('a failure clears the lock, so the caller can try again', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ error: 'not_available' }, BAD_GATEWAY));
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));
    await waitFor(() => expect(actionAt(hook)?.kind).toBe('failed'));

    act(() => hold(CAMPSITE_ID, NIGHT));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(actionAt(hook)?.kind).toBe('pending');
  });
});

describe('usePlaceCart, the vendor deadline', () => {
  const holdThenWait = async () => {
    vi.stubGlobal('fetch', vi.fn(async () => held()));
    const { hook, hold } = ready();
    await act(async () => {
      hold(CAMPSITE_ID, NIGHT);
    });
    await waitFor(() => expect(actionAt(hook)?.kind).toBe('held'));
    return hook;
  };

  test('the hold is still claimed a minute before the deadline', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const hook = await holdThenWait();

    await act(async () => {
      vi.advanceTimersByTime(CART_HOLD_MS - A_MINUTE_MS);
    });

    expect(actionAt(hook)).toMatchObject({ kind: 'held' });
  });

  test('once the deadline passes the UI stops claiming the hold', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const hook = await holdThenWait();

    await act(async () => {
      vi.advanceTimersByTime(CART_HOLD_MS);
    });

    expect(actionAt(hook)).toBeNull();
    expect(hook.result.current.actions.size).toBe(0);
  });

  test('a lapsed hold may be taken again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const hook = await holdThenWait();
    await act(async () => {
      vi.advanceTimersByTime(CART_HOLD_MS);
    });

    const gate = hook.result.current.gate;
    if (gate.state !== 'ready') throw new Error(gate.state);
    await act(async () => {
      gate.onAddToCart(CAMPSITE_ID, NIGHT);
    });

    expect(actionAt(hook)?.kind).toBe('held');
  });

  test('unmounting drops the deadline rather than firing into a gone tree', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const hook = await holdThenWait();

    hook.unmount();

    expect(vi.getTimerCount()).toBe(0);
  });

  test('a failure carries no deadline, so nothing is scheduled for it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'not_available' }, BAD_GATEWAY)));

    const { hook, hold } = ready();
    await act(async () => {
      hold(CAMPSITE_ID, NIGHT);
    });
    await waitFor(() => expect(actionAt(hook)?.kind).toBe('failed'));

    expect(vi.getTimerCount()).toBe(0);
  });
});
