import { afterEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { useMe } from '@/queries/auth';
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

afterEach(() => vi.unstubAllGlobals());

describe('AppProviders', () => {
  test('provides the supplied query client to children', async () => {
    stubFetch(jsonResponse(SIGNED_IN));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('Alice')).toBeInTheDocument();
    client.clear();
  });

  test('creates a query client when none is supplied', () => {
    render(
      <AppProviders>
        <p>hello</p>
      </AppProviders>,
    );

    expect(screen.getByText('hello')).toBeInTheDocument();
  });

  test('surfaces query failures without a second auth state store', async () => {
    stubFetch(textResponse('boom', 503));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('error')).toBeInTheDocument();
    client.clear();
  });
});
