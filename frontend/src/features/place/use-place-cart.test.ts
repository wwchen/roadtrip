import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { jsonResponse } from '@/test/fetch-stub';
import { NO_WATCH_CAPABILITIES, type WatchCapabilities } from '@/lib/watch-windows';
import { useSettingsStore } from '@/stores/settingsStore';
import { usePlaceCart } from './use-place-cart';

const ADD_TO_CART_URL = '/api/booking/add-to-cart';
const CAMPSITE_ID = '131744';
const NIGHT = '2026-10-29';
const MORNING_AFTER = '2026-10-30';
const OTHER_NIGHT = '2026-10-31';
const HOLDER = 'Recreation.gov';
const CART_URL = 'https://www.recreation.gov/cart';
const BAD_GATEWAY = 502;

const capabilities = (over: Partial<WatchCapabilities> = {}): WatchCapabilities => ({
  ...NO_WATCH_CAPABILITIES,
  ...over,
});

const held = () =>
  jsonResponse({
    status: 'added',
    cart_url: CART_URL,
    provider: 'recgov',
    provider_display: HOLDER,
  });

afterEach(() => {
  vi.unstubAllGlobals();
  useSettingsStore.getState().closeSettings();
});

describe('usePlaceCart, the gate it publishes', () => {
  test('the four wire states each become the one branch written for them', () => {
    const ready = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));
    expect(ready.result.current.gate.state).toBe('ready');

    const out = renderHook(() => usePlaceCart(capabilities({ addToCart: 'signed_out' })));
    expect(out.result.current.gate.state).toBe('signed_out');

    const none = renderHook(() =>
      usePlaceCart(
        capabilities({ addToCart: 'no_credentials', addToCartProviderDisplay: HOLDER }),
      ),
    );
    expect(none.result.current.gate).toMatchObject({ state: 'no_credentials', holder: HOLDER });

    const off = renderHook(() => usePlaceCart(capabilities()));
    expect(off.result.current.gate.state).toBe('unsupported');
  });

  test('no_credentials sends the caller to the Booking section of Settings', () => {
    const { result } = renderHook(() =>
      usePlaceCart(capabilities({ addToCart: 'no_credentials' })),
    );
    const gate = result.current.gate;
    if (gate.state !== 'no_credentials') throw new Error(gate.state);

    act(() => gate.onOpenSettings());

    expect(useSettingsStore.getState()).toMatchObject({ open: true, tab: 'booking' });
  });

  test('nothing is in flight before anything is asked for', () => {
    const { result } = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));

    expect(result.current.action).toBeNull();
  });
});

describe('usePlaceCart, holding a site', () => {
  const ready = () => {
    const hook = renderHook(() => usePlaceCart(capabilities({ addToCart: 'ready' })));
    const gate = hook.result.current.gate;
    if (gate.state !== 'ready') throw new Error(gate.state);
    return { hook, hold: gate.onAddToCart };
  };

  test('it posts the one night, and says whose cart it landed in', async () => {
    const fetchMock = vi.fn(async () => held());
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));

    expect(hook.result.current.action).toEqual({
      kind: 'pending',
      cell: { campsiteId: CAMPSITE_ID, date: NIGHT },
    });

    await waitFor(() =>
      expect(hook.result.current.action).toEqual({
        kind: 'held',
        cell: { campsiteId: CAMPSITE_ID, date: NIGHT },
        cartUrl: CART_URL,
        holder: HOLDER,
      }),
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
      hold(CAMPSITE_ID, OTHER_NIGHT);
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(hook.result.current.action).toEqual({
      kind: 'pending',
      cell: { campsiteId: CAMPSITE_ID, date: NIGHT },
    });

    await waitFor(() => expect(hook.result.current.action?.kind).toBe('held'));
  });

  test('a hold that settled does not block the next one', async () => {
    const fetchMock = vi.fn(async () => held());
    vi.stubGlobal('fetch', fetchMock);

    const { hook, hold } = ready();
    act(() => hold(CAMPSITE_ID, NIGHT));
    await waitFor(() => expect(hook.result.current.action?.kind).toBe('held'));

    act(() => hold(CAMPSITE_ID, OTHER_NIGHT));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(hook.result.current.action).toEqual({
      kind: 'pending',
      cell: { campsiteId: CAMPSITE_ID, date: OTHER_NIGHT },
    });
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
      expect(hook.result.current.action).toEqual({
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
    await waitFor(() => expect(hook.result.current.action?.kind).toBe('failed'));

    act(() => hold(CAMPSITE_ID, NIGHT));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(hook.result.current.action?.kind).toBe('pending');
  });
});
