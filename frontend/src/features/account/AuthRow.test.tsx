import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createTestQueryClient } from '@/test/query-client';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { AuthRow } from './AuthRow';

const signIn = vi.fn();
const signOut = vi.fn();
vi.mock('@/api/auth-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/auth-api')>()),
  signIn: (...args: unknown[]) => signIn(...args),
  signOut: (...args: unknown[]) => signOut(...args),
}));

let me: unknown;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  signIn.mockClear();
  signOut.mockClear();
  me = { authenticated: false, auth_enabled: true };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith('/api/me')) return json(me);
      if (url.startsWith('/api/settings')) {
        return json({
          profile: { display_name: 'Bo', email: 'bo@example.com' },
          notifications: {},
          booking: { recgov_configured: false },
        });
      }
      return json({}, 404);
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

const mount = () =>
  render(
    <AppProviders client={createTestQueryClient()}>
      <AuthRow />
    </AppProviders>,
  );

describe('the auth row', () => {
  test('offers sign-in to an anonymous visitor', async () => {
    mount();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
    await act(async () => {
      screen.getByRole('button', { name: 'Sign in' }).click();
    });

    expect(signIn).toHaveBeenCalled();
  });

  test('renders nothing when no provider is configured', async () => {
    me = { authenticated: false, auth_enabled: false };
    const { container } = mount();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    // Not `toBeEmptyDOMElement`: AppProviders renders a toast viewport of its own.
    expect(container.querySelector('.acct-pill')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  test('names a signed-in user with their first name and initials', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: {
        id: 1,
        email: 'bo@example.com',
        display_name: 'Bo Carter',
        email_verified: true,
        roles: [],
      },
    };
    mount();

    // The avatar's initials are `aria-hidden`, so the button's accessible name
    // is the first name alone.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Bo' })).toBeInTheDocument());
    expect(screen.getByText('BC')).toBeInTheDocument();
  });

  test('falls back to the email address', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', email_verified: true, roles: [] },
    };
    mount();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'bo@example.com' })).toBeInTheDocument(),
    );
  });

  test('opens the settings modal', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', display_name: 'Bo', email_verified: true, roles: [] },
    };
    mount();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Bo' })).toBeInTheDocument());

    await act(async () => {
      screen.getByRole('button', { name: 'Bo' }).click();
    });

    // The modal is LDS's, so it is identified by what it shows rather than by a
    // role — and "Settings" appears twice in it (title and tab), which is why the
    // field is the assertion. `SettingsModal.test.tsx` locates it the same way.
    expect(await screen.findByLabelText('Display name')).toBeInTheDocument();
  });
});
