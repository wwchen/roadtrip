// Sandbox assume-user switcher.
//
// A fixed bottom bar listing seeded users, rendered ONLY when auth is disabled
// (which is what a sandbox does) AND `/api/sandbox/users` returns a non-empty
// list. Every other env, every fetch failure and an empty list produce no
// visible output.
//
// **Load-bearing for review, not a convenience.** An auth-disabled sandbox 401s
// every API call until an `rt_session=sandbox:<id>` cookie is picked, and this is
// the only page-local way to pick one. A page without it looks signed-out, which
// is indistinguishable from a real auth failure — so `initSandboxChrome` is
// called from `mountPage`, where no page can forget it.
import { fetchMe, type Me } from '@/api/auth-api';
import { fetchSandboxUsers, type SandboxUser } from '@/api/sandbox-api';

const SESSION_COOKIE_NAME = 'rt_session';
const SESSION_COOKIE_PREFIX = 'sandbox';
const ADMIN_ROLE = 'admin';

/**
 * The one browser API this module cannot reach through jsdom, so it stays a
 * parameter: `window.location.reload` is not stubbable there.
 */
export interface Reloadable {
  reload(): void;
}

/**
 * Render the switcher, or nothing when this is not an auth-disabled sandbox.
 *
 * @returns the inserted bar, or null when there is nothing to show.
 */
export function renderUserSwitcher(
  users: SandboxUser[] | null,
  currentMe: Me | null,
  loc: Reloadable = window.location,
): HTMLElement | null {
  // `!== false` rather than `!authEnabled`: the switcher appears only when the
  // backend explicitly says auth is off. A response missing the field is a
  // response we cannot read, and a stray session-picker on a live deployment is
  // the worse failure.
  if (!currentMe || currentMe.auth_enabled !== false) return null;
  if (!Array.isArray(users) || users.length === 0) return null;

  const wrap = document.createElement('div');
  wrap.className = 'sandbox-user-switcher';
  wrap.setAttribute('role', 'navigation');
  wrap.setAttribute('aria-label', 'Assume sandbox user');

  for (const user of users) {
    const btn = document.createElement('button');
    const isAdmin = Array.isArray(user.roles) && user.roles.includes(ADMIN_ROLE);
    btn.className = 'sandbox-user-switcher__btn';
    btn.type = 'button';
    btn.textContent = isAdmin ? `${user.name} (admin)` : user.name;
    btn.addEventListener('click', () => {
      document.cookie = `${SESSION_COOKIE_NAME}=${SESSION_COOKIE_PREFIX}:${user.id}; path=/`;
      loc.reload();
    });
    wrap.append(btn);
  }

  document.body.append(wrap);
  return wrap;
}

/**
 * Ask who we are and which users exist, then render the switcher if applicable.
 *
 * Errors are swallowed: outside a sandbox `/api/sandbox/users` is a 404, which is
 * the normal answer rather than a fault.
 */
export async function initUserSwitcher(): Promise<void> {
  try {
    const [me, users] = await Promise.all([fetchMe(), fetchSandboxUsers()]);
    renderUserSwitcher(users, me);
  } catch {
    // not a sandbox / endpoint absent — no switcher, never block the page.
  }
}
