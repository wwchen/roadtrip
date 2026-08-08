import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { useMe } from '@/queries/auth';
import { queryKeys } from '@/queries/keys';
import { WATCHES_CHANGED_EVENT } from '@/queries/legacy-events';
import { selectIsAuthenticated, selectUser, useAuthStore } from '@/stores/authStore';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';
import { AppProviders } from './AppProviders';

const SIGNED_IN = {
  authenticated: true,
  auth_enabled: true,
  user: {
    id: 1,
    email: 'alice@example.test',
    display_name: 'Alice',
    email_verified: true,
    roles: ['admin'],
  },
};

function Identity() {
  const { data, isPending, isError } = useMe();
  if (isPending) return <p>loading</p>;
  if (isError) return <p>error</p>;
  return <p>{data?.user?.display_name ?? 'anonymous'}</p>;
}

let client: QueryClient;

beforeEach(() => {
  useAuthStore.getState().reset();
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});

afterEach(() => {
  vi.unstubAllGlobals();
  client.clear();
});

describe('AppProviders', () => {
  test('provides a query client, so a query hook can run', async () => {
    stubFetch(jsonResponse(SIGNED_IN));

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('Alice')).toBeInTheDocument();
  });

  test('renders children when no client is supplied', () => {
    stubFetch(jsonResponse(SIGNED_IN));

    render(
      <AppProviders>
        <p>hello</p>
      </AppProviders>,
    );

    expect(screen.getByText('hello')).toBeInTheDocument();
  });

  // The bridge is wired here rather than per page so a page cannot ship without
  // it and silently serve stale data after a vanilla-side sign-in.
  test('installs the legacy event bridge', async () => {
    stubFetch(jsonResponse(SIGNED_IN));
    const key = queryKeys.watches.list();
    client.setQueryData(key, []);

    render(
      <AppProviders client={client}>
        <p>hello</p>
      </AppProviders>,
    );
    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));

    await waitFor(() => {
      expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    });
  });

  test('removes the bridge on unmount', async () => {
    stubFetch(jsonResponse(SIGNED_IN));
    const { unmount } = render(
      <AppProviders client={client}>
        <p>hello</p>
      </AppProviders>,
    );
    unmount();

    const key = queryKeys.watches.list();
    client.setQueryData(key, []);
    window.dispatchEvent(new CustomEvent(WATCHES_CHANGED_EVENT));

    await waitFor(() => {
      expect(client.getQueryState(key)?.isInvalidated).toBe(false);
    });
  });
});

describe('useMe', () => {
  test('syncs the answer into authStore for non-React readers', async () => {
    stubFetch(jsonResponse(SIGNED_IN));

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );
    await screen.findByText('Alice');

    await waitFor(() => {
      expect(useAuthStore.getState().status).toBe('ready');
    });
    expect(selectIsAuthenticated(useAuthStore.getState())).toBe(true);
    expect(selectUser(useAuthStore.getState())?.display_name).toBe('Alice');
  });

  test('records an anonymous answer as ready, not as an error', async () => {
    stubFetch(jsonResponse({ authenticated: false, auth_enabled: true }));

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    await waitFor(() => {
      expect(useAuthStore.getState().status).toBe('ready');
    });
    expect(selectIsAuthenticated(useAuthStore.getState())).toBe(false);
  });

  test('leaves authStore unknown when the request fails', async () => {
    stubFetch(textResponse('boom', 503));

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('error')).toBeInTheDocument();
    expect(useAuthStore.getState().status).toBe('unknown');
  });
});
