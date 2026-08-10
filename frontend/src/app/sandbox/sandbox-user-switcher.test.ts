import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { Me } from '@/api/auth-api';
import type { SandboxUser } from '@/api/sandbox-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';
import { initUserSwitcher, renderUserSwitcher } from './sandbox-user-switcher';

// Ported from web/sandbox-user-switcher.test.mjs, which drove a hand-rolled
// document stub. jsdom is a real DOM, so these assert against rendered markup —
// and the cookie assertion goes through `document.cookie` itself, captured by a
// setter spy, because jsdom's getter reports only name=value and the `path=/`
// attribute is the part that makes the session apply site-wide.

const me = (authEnabled: boolean): Me => ({ authenticated: false, auth_enabled: authEnabled });
const user = (id: number, name: string, roles: string[] = []): SandboxUser => ({ id, name, roles });

const switcher = () => document.querySelector('.sandbox-user-switcher');
const buttons = () =>
  [...document.querySelectorAll<HTMLButtonElement>('.sandbox-user-switcher__btn')];

let cookieWrites: string[];
const noReload = { reload: () => {} };

beforeEach(() => {
  document.body.innerHTML = '';
  cookieWrites = [];
  vi.spyOn(Document.prototype, 'cookie', 'set').mockImplementation((value: string) => {
    cookieWrites.push(value);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('renderUserSwitcher', () => {
  test('lists one button per seeded user when auth is disabled', () => {
    const bar = renderUserSwitcher([user(1, 'Will', ['admin']), user(2, 'Matt')], me(false), noReload);

    expect(bar).toBe(switcher());
    expect(bar!.getAttribute('role')).toBe('navigation');
    expect(bar!.getAttribute('aria-label')).toBe('Assume sandbox user');
    expect(buttons().map((b) => b.textContent)).toEqual(['Will (admin)', 'Matt']);
  });

  test('renders nothing when auth is enabled', () => {
    expect(renderUserSwitcher([user(1, 'Will', ['admin'])], me(true), noReload)).toBeNull();
    expect(switcher()).toBeNull();
  });

  // A response we could not read is not a sandbox: a stray session picker on a
  // live deployment is the worse failure, so the check is `auth_enabled === false`
  // rather than falsy.
  test('renders nothing when /api/me said nothing about auth', () => {
    expect(renderUserSwitcher([user(1, 'Will')], {} as Me, noReload)).toBeNull();
    expect(renderUserSwitcher([user(1, 'Will')], null, noReload)).toBeNull();
    expect(switcher()).toBeNull();
  });

  test('renders nothing without a usable user list', () => {
    expect(renderUserSwitcher([], me(false), noReload)).toBeNull();
    expect(renderUserSwitcher(null, me(false), noReload)).toBeNull();
    expect(switcher()).toBeNull();
  });

  test('selecting a user sets a site-wide rt_session cookie and reloads', () => {
    let reloaded = false;
    renderUserSwitcher([user(2, 'Matt')], me(false), { reload: () => (reloaded = true) });

    buttons()[0]!.click();

    expect(cookieWrites).toEqual(['rt_session=sandbox:2; path=/']);
    expect(reloaded).toBe(true);
  });

  test('each button carries its own user id', () => {
    renderUserSwitcher([user(10, 'User10'), user(99, 'User99', ['admin'])], me(false), noReload);

    buttons()[1]!.click();
    buttons()[0]!.click();

    expect(cookieWrites).toEqual([
      'rt_session=sandbox:99; path=/',
      'rt_session=sandbox:10; path=/',
    ]);
  });
});

describe('initUserSwitcher', () => {
  test('renders when auth is off and users come back', async () => {
    const fetched = stubFetch(
      jsonResponse(me(false)),
      jsonResponse([user(1, 'Alice')]),
    );

    await initUserSwitcher();

    expect(fetched.requests.map((r) => r.url)).toEqual(['/api/me', '/api/sandbox/users']);
    expect(switcher()).not.toBeNull();
  });

  test('renders nothing when auth is enabled', async () => {
    stubFetch(jsonResponse(me(true)), jsonResponse([user(1, 'Alice')]));

    await initUserSwitcher();

    expect(switcher()).toBeNull();
  });

  // The normal answer outside a sandbox: the route 404s unless assume-user is on.
  test('swallows a 404 from the users endpoint', async () => {
    stubFetch(jsonResponse(me(false)), textResponse('', 404));

    await expect(initUserSwitcher()).resolves.toBeUndefined();
    expect(switcher()).toBeNull();
  });

  test('swallows a failed /api/me', async () => {
    stubFetch(textResponse('nope', 500), jsonResponse([user(1, 'Alice')]));

    await expect(initUserSwitcher()).resolves.toBeUndefined();
    expect(switcher()).toBeNull();
  });

  test('swallows a network error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('network error'))),
    );

    await expect(initUserSwitcher()).resolves.toBeUndefined();
    expect(switcher()).toBeNull();
  });
});
