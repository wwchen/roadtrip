// `useMe` runs on every page, so it is what applies a saved theme with no
// Settings interaction. Tested directly: the behaviour is a side effect on
// `themeStore`, not markup.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { DARK_MODE_CLASS } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';
import { useMe } from './auth';

/** A controllable `matchMedia`, since jsdom does not implement it. */
function installMatchMedia(prefersDark: boolean) {
  vi.stubGlobal('matchMedia', () => ({
    matches: prefersDark,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

let me: unknown;

function mountUseMe() {
  const client = createTestQueryClient();
  return {
    client,
    ...renderHook(() => useMe(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    }),
  };
}

beforeEach(() => {
  document.documentElement.className = 'theme-roadtrip-zion';
  document.head.innerHTML = '<meta name="theme-color" content="#ffffff">';
  installMatchMedia(false);
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => json(me)),
  );
  // A clean, known store between tests — the store is a module singleton.
  useThemeStore.getState().setChoice('system');
});

afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useMe applies a signed-in user’s saved theme', () => {
  test('a saved dark theme applies with no Settings interaction', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', email_verified: true, roles: [], theme: 'dark' },
    };

    mountUseMe();

    await waitFor(() =>
      expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true),
    );
    expect(useThemeStore.getState().choice).toBe('dark');
  });

  // An unknown theme degrades to the default rather than throwing.
  test('an unrecognized theme value degrades to system rather than throwing', async () => {
    // Starts on a value that is neither the pre-fetch default ('system', set in
    // beforeEach) nor the post-coercion answer, so the assertion below can only
    // pass once the effect has actually run `coerceChoice` on the fetched value
    // — a store that never updated, or one that applied the raw 'solarized'
    // string uncoerced, both fail it.
    act(() => useThemeStore.getState().setChoice('dark'));

    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', email_verified: true, roles: [], theme: 'solarized' },
    };

    const { result } = mountUseMe();

    await waitFor(() => expect(result.current.data).toBeDefined());
    await waitFor(() => expect(useThemeStore.getState().choice).toBe('system'));
  });

  test('an anonymous response leaves the applied theme alone', async () => {
    // OS prefers dark, but an explicit local choice (light) already governs —
    // exactly the case a naive "apply the default when theme is absent" bug
    // would get wrong: it would recompute from `system`/OS and flip this to
    // dark, even though no server data justifies touching it at all.
    installMatchMedia(true);
    act(() => useThemeStore.getState().setChoice('light'));
    expect(useThemeStore.getState().mode).toBe('light');

    me = { authenticated: false, auth_enabled: true };
    mountUseMe();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(useThemeStore.getState().choice).toBe('light');
    expect(useThemeStore.getState().mode).toBe('light');
  });

  test('a refetch reporting the same theme does not stomp an unsaved preview', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', email_verified: true, roles: [], theme: 'light' },
    };

    const { result, client } = mountUseMe();
    await waitFor(() => expect(useThemeStore.getState().choice).toBe('light'));

    // Simulate SettingsModal's ProfilePanel previewing an unsaved choice: it
    // calls setChoice directly, bypassing any query.
    act(() => useThemeStore.getState().setChoice('dark'));
    expect(useThemeStore.getState().choice).toBe('dark');

    // A routine refetch (window focus, staleness) that reports the SAME
    // server theme as before must not re-apply it over the live preview.
    await act(async () => {
      await client.refetchQueries({ queryKey: ['me'] });
    });
    await waitFor(() => expect(result.current.data?.user?.theme).toBe('light'));

    expect(useThemeStore.getState().choice).toBe('dark');
    expect(document.documentElement.classList.contains(DARK_MODE_CLASS)).toBe(true);
  });
});
