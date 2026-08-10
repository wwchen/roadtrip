// The topbar's auth row, which is also the settings modal's only trigger.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
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
        });
      }
      return json({}, 404);
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

const mount = () =>
  render(
    <AppProviders client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
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

    // A full-page navigation to the provider's hosted flow, which is what the live
    // configuration uses — the embedded card is deliberately not ported.
    expect(signIn).toHaveBeenCalled();
  });

  // A fresh clone with no identity provider should look exactly as it did before
  // auth existed, rather than showing a control that cannot work.
  test('renders nothing when no provider is configured', async () => {
    me = { authenticated: false, auth_enabled: false };
    const { container } = mount();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    // Not `toBeEmptyDOMElement`: AppProviders renders a toast viewport of its own.
    expect(container.querySelector('.tb-auth')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  test('names a signed-in user and offers sign-out', async () => {
    me = {
      authenticated: true,
      auth_enabled: true,
      user: { id: 1, email: 'bo@example.com', display_name: 'Bo', email_verified: true, roles: [] },
    };
    mount();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Bo' })).toBeInTheDocument());
    await act(async () => {
      screen.getByRole('button', { name: 'Sign out' }).click();
    });

    expect(signOut).toHaveBeenCalled();
  });

  // Providers that return no display name — and Apple, after the first
  // authorization — still always return the address.
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

  // The mounting task Phase 3 left for 4e: every panel behind this button existed
  // and was tested, but nothing rendered the modal.
  test('opens the settings modal, which Phase 3 built and nothing mounted', async () => {
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
