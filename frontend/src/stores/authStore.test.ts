import { beforeEach, describe, expect, test } from 'vitest';
import type { Me } from '@/api/auth-api';
import {
  selectIsAdmin,
  selectIsAuthEnabled,
  selectIsAuthenticated,
  selectIsEmbeddedLogin,
  selectUser,
  useAuthStore,
} from './authStore';

const ANONYMOUS: Me = { authenticated: false, auth_enabled: true };

const SIGNED_IN: Me = {
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

beforeEach(() => useAuthStore.getState().reset());

describe('lifecycle', () => {
  test('starts unknown, so a page can tell "not asked" from "anonymous"', () => {
    expect(useAuthStore.getState().status).toBe('unknown');
    expect(useAuthStore.getState().me).toBeNull();
  });

  test('setMe moves to ready', () => {
    useAuthStore.getState().setMe(ANONYMOUS);

    expect(useAuthStore.getState().status).toBe('ready');
    expect(useAuthStore.getState().me).toEqual(ANONYMOUS);
  });

  test('an anonymous answer is ready, not unknown', () => {
    useAuthStore.getState().setMe(ANONYMOUS);

    expect(useAuthStore.getState().status).toBe('ready');
    expect(selectIsAuthenticated(useAuthStore.getState())).toBe(false);
  });

  test('reset returns to the initial state', () => {
    useAuthStore.getState().setMe(SIGNED_IN);
    useAuthStore.getState().reset();

    expect(useAuthStore.getState()).toMatchObject({ status: 'unknown', me: null });
  });

  test('notifies subscribers on change', () => {
    const seen: (string | null)[] = [];
    const unsubscribe = useAuthStore.subscribe((s) => seen.push(s.status));

    useAuthStore.getState().setMe(SIGNED_IN);
    useAuthStore.getState().reset();
    unsubscribe();
    useAuthStore.getState().setMe(ANONYMOUS);

    expect(seen).toEqual(['ready', 'unknown']);
  });
});

describe('selectors', () => {
  test('report false before /api/me answers rather than throwing', () => {
    const s = useAuthStore.getState();

    expect(selectIsAuthenticated(s)).toBe(false);
    expect(selectIsAuthEnabled(s)).toBe(false);
    expect(selectIsEmbeddedLogin(s)).toBe(false);
    expect(selectIsAdmin(s)).toBe(false);
    expect(selectUser(s)).toBeNull();
  });

  test('read the signed-in identity', () => {
    useAuthStore.getState().setMe(SIGNED_IN);
    const s = useAuthStore.getState();

    expect(selectIsAuthenticated(s)).toBe(true);
    expect(selectIsAuthEnabled(s)).toBe(true);
    expect(selectUser(s)?.display_name).toBe('Alice');
    expect(selectIsAdmin(s)).toBe(true);
  });

  // auth_enabled false means no identity provider is configured: hide sign-in
  // rather than offer a control that cannot work.
  test('selectIsAuthEnabled is false when no provider is configured', () => {
    useAuthStore.getState().setMe({ authenticated: false, auth_enabled: false });

    expect(selectIsAuthEnabled(useAuthStore.getState())).toBe(false);
  });

  test('selectIsEmbeddedLogin follows auth_embedded', () => {
    useAuthStore.getState().setMe({ ...ANONYMOUS, auth_embedded: true });

    expect(selectIsEmbeddedLogin(useAuthStore.getState())).toBe(true);
  });

  test('selectIsAdmin is false for a user without the role', () => {
    useAuthStore.getState().setMe({
      authenticated: true,
      auth_enabled: true,
      user: { id: 2, email: 'b@c.test', email_verified: true, roles: ['viewer'] },
    });

    expect(selectIsAdmin(useAuthStore.getState())).toBe(false);
  });

  test('selectUser is null for an authenticated response with no user block', () => {
    useAuthStore.getState().setMe({ authenticated: true, auth_enabled: true });

    expect(selectUser(useAuthStore.getState())).toBeNull();
  });
});
