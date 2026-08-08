import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/authStore';
import { queryKeys } from './keys';
import {
  AUTH_CHANGED_EVENT,
  installLegacyEventBridge,
  notifyLegacyWatchesChanged,
  WATCHES_CHANGED_EVENT,
} from './legacy-events';

let queryClient: QueryClient;
let dispose: () => void;

const isInvalidated = (key: readonly unknown[]): boolean =>
  queryClient.getQueryState(key)?.isInvalidated === true;

beforeEach(() => {
  useAuthStore.getState().reset();
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  dispose = installLegacyEventBridge(queryClient);
});

afterEach(() => {
  dispose();
  queryClient.clear();
});

// The names must match web/availability/{auth,watch}-events.js exactly, or the
// bridge silently never fires.
describe('event names', () => {
  test('match the legacy constants', () => {
    expect(AUTH_CHANGED_EVENT).toBe('roadtrip:auth-changed');
    expect(WATCHES_CHANGED_EVENT).toBe('roadtrip:watches-changed');
  });
});

describe('watches-changed', () => {
  test('invalidates the watch queries', () => {
    const key = queryKeys.watches.list();
    queryClient.setQueryData(key, []);

    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));

    expect(isInvalidated(key)).toBe(true);
  });

  test('leaves unrelated queries alone', () => {
    const key = queryKeys.pois.detail(1);
    queryClient.setQueryData(key, {});

    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));

    expect(isInvalidated(key)).toBe(false);
  });
});

describe('auth-changed', () => {
  test('invalidates everything scoped to identity', () => {
    const keys = [queryKeys.me(), queryKeys.watches.list(), queryKeys.settings()];
    for (const key of keys) queryClient.setQueryData(key, {});

    window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));

    for (const key of keys) expect(isInvalidated(key)).toBe(true);
  });

  // Resetting rather than leaving the old identity in place stops a subscriber
  // rendering the previous user while /api/me is in flight.
  test('resets authStore so no subscriber renders the previous user', () => {
    useAuthStore.getState().setMe({ authenticated: true, auth_enabled: true });

    window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));

    expect(useAuthStore.getState()).toMatchObject({ status: 'unknown', me: null });
  });

  test('leaves POI data alone — it is not user-scoped', () => {
    const key = queryKeys.pois.detail(1);
    queryClient.setQueryData(key, {});

    window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));

    expect(isInvalidated(key)).toBe(false);
  });
});

describe('dispose', () => {
  test('stops listening', () => {
    dispose();
    const key = queryKeys.watches.list();
    queryClient.setQueryData(key, []);

    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));

    expect(isInvalidated(key)).toBe(false);
  });
});

describe('notifyLegacyWatchesChanged', () => {
  // The mirror direction: a watch created on the React page has to announce
  // itself so the still-vanilla topbar alerts list refreshes.
  test('dispatches the event the vanilla side listens for', () => {
    let fired = 0;
    const handler = () => {
      fired += 1;
    };
    window.addEventListener(WATCHES_CHANGED_EVENT, handler);

    notifyLegacyWatchesChanged();
    window.removeEventListener(WATCHES_CHANGED_EVENT, handler);

    expect(fired).toBe(1);
  });

  test('round-trips through the bridge into an invalidation', () => {
    const key = queryKeys.watches.list();
    queryClient.setQueryData(key, []);

    notifyLegacyWatchesChanged();

    expect(isInvalidated(key)).toBe(true);
  });
});
