import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { fetchMe, signIn, signInWithConnection, signOut } from './auth-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';

const assign = vi.fn();

beforeEach(() => {
  // jsdom's window.location is not assignable, so replace it with the three
  // fields these functions read plus the navigation method they call.
  vi.stubGlobal('window', {
    location: { pathname: '/watches', search: '', hash: '', assign },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  assign.mockReset();
});

describe('fetchMe', () => {
  test('issues GET /api/me', async () => {
    const fetchStub = stubFetch(jsonResponse({ authenticated: false, auth_enabled: true }));

    const me = await fetchMe();

    expect(fetchStub.last.url).toBe('/api/me');
    expect(fetchStub.last.init.credentials).toBe('same-origin');
    expect(me).toEqual({ authenticated: false, auth_enabled: true });
  });

  test('resolves for an anonymous visitor', async () => {
    stubFetch(jsonResponse({ authenticated: false, auth_enabled: true }));

    await expect(fetchMe()).resolves.toMatchObject({ authenticated: false });
  });

  test('returns the user block when signed in', async () => {
    stubFetch(
      jsonResponse({
        authenticated: true,
        auth_enabled: true,
        user: {
          id: 1,
          email: 'a@b.com',
          display_name: 'Alice',
          email_verified: true,
          roles: ['admin'],
        },
      }),
    );

    const me = await fetchMe();

    expect(me.user?.roles).toEqual(['admin']);
  });

  test('throws HttpError on a failed response', async () => {
    stubFetch(textResponse('boom', 503));

    await expect(fetchMe()).rejects.toMatchObject({ name: 'HttpError', status: 503 });
  });

  test('forwards the abort signal', async () => {
    const fetchStub = stubFetch(jsonResponse({ authenticated: false, auth_enabled: true }));
    const controller = new AbortController();

    await fetchMe({ signal: controller.signal });

    expect(fetchStub.last.init.signal).toBe(controller.signal);
  });
});

describe('signIn', () => {
  test('navigates to /auth/login with the current path as return_to', () => {
    signIn();

    expect(assign).toHaveBeenCalledWith('/auth/login?return_to=%2Fwatches');
  });

  test('includes the query string and hash in the default return_to', () => {
    vi.stubGlobal('window', {
      location: { pathname: '/watches', search: '?action=new', hash: '#form', assign },
    });

    signIn();

    expect(assign).toHaveBeenCalledWith(
      '/auth/login?return_to=%2Fwatches%3Faction%3Dnew%23form',
    );
  });

  test('url-encodes an explicit return_to', () => {
    signIn('/watches?action=edit&id=7');

    expect(assign).toHaveBeenCalledWith(
      '/auth/login?return_to=%2Fwatches%3Faction%3Dedit%26id%3D7',
    );
  });

  test('falls back to / when the location has no path at all', () => {
    vi.stubGlobal('window', { location: { pathname: '', search: '', hash: '', assign } });

    signIn();

    expect(assign).toHaveBeenCalledWith('/auth/login?return_to=%2F');
  });
});

describe('signInWithConnection', () => {
  test('appends the connection after return_to', () => {
    signInWithConnection('google-oauth2');

    expect(assign).toHaveBeenCalledWith(
      '/auth/login?return_to=%2Fwatches&connection=google-oauth2',
    );
  });

  test('url-encodes the connection', () => {
    signInWithConnection('a b&c', '/');

    expect(assign).toHaveBeenCalledWith('/auth/login?return_to=%2F&connection=a%20b%26c');
  });
});

describe('signOut', () => {
  test('navigates to /auth/logout with no params', () => {
    signOut();

    expect(assign).toHaveBeenCalledWith('/auth/logout');
  });
});
