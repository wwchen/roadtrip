import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestQueryClient } from '@/test/query-client';
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
    const client = createTestQueryClient();

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

  test('shows a reloadable banner instead of a white page when a child throws', () => {
    // React reports a caught error itself, on top of the boundary's own log.
    const logged = vi.spyOn(console, 'error').mockImplementation(() => {});

    function Boom(): never {
      throw new Error('render exploded');
    }

    render(
      <AppProviders client={createTestQueryClient()}>
        <Boom />
      </AppProviders>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
    expect(logged.mock.calls.some(([first]) => first === 'page render failed:')).toBe(true);
    logged.mockRestore();
  });

  test('surfaces query failures without a second auth state store', async () => {
    stubFetch(textResponse('boom', 503));
    const client = createTestQueryClient();

    render(
      <AppProviders client={client}>
        <Identity />
      </AppProviders>,
    );

    expect(await screen.findByText('error')).toBeInTheDocument();
    client.clear();
  });
});
