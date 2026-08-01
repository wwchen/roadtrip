// Sandbox assume-user switcher.
// Renders a fixed bar listing seeded users ONLY when auth is disabled (sandbox
// mode) AND /api/sandbox/users returns a non-empty list.  All other envs,
// fetch failures, and empty user lists produce no visible output — the page is
// never blocked.

const SESSION_COOKIE_NAME = 'rt_session';
const ADMIN_ROLE = 'admin';
const ME_URL = '/api/me';
const SANDBOX_USERS_URL = '/api/sandbox/users';

/**
 * Render the assume-user switcher into `doc.body`.
 * @param {Array<{ id: number|string, name: string, roles: string[] }>} users
 * @param {{ auth_enabled: boolean, authenticated?: boolean, user?: object } | null} currentMe
 * @param {Document} doc  - real document or test stub
 * @param {{ reload(): void }} loc  - real window.location or test stub
 * @returns {Element|null}  the inserted switcher element, or null when not applicable
 */
export function renderUserSwitcher(users, currentMe, doc = document, loc = globalThis.location) {
  if (!currentMe || currentMe.auth_enabled !== false) return null;
  if (!Array.isArray(users) || users.length === 0) return null;

  const wrap = doc.createElement('div');
  wrap.setAttribute('class', 'sandbox-user-switcher');
  wrap.setAttribute('role', 'navigation');
  wrap.setAttribute('aria-label', 'Assume sandbox user');

  users.forEach((u) => {
    const btn = doc.createElement('button');
    const isAdmin = Array.isArray(u.roles) && u.roles.includes(ADMIN_ROLE);
    btn.setAttribute('class', 'sandbox-user-switcher__btn');
    btn.textContent = isAdmin ? `${u.name} (admin)` : u.name;
    btn.addEventListener('click', () => {
      doc.cookie = `${SESSION_COOKIE_NAME}=sandbox:${u.id}; path=/`;
      loc.reload();
    });
    wrap.append(btn);
  });

  doc.body.append(wrap);
  return wrap;
}

/**
 * Fetch /api/me and /api/sandbox/users, then render the switcher if applicable.
 * Errors are swallowed — switcher is always optional.
 * @param {Document} doc
 * @param {typeof fetch} fetchFn
 */
export async function initUserSwitcher(doc = document, fetchFn = fetch) {
  try {
    const [meRes, usersRes] = await Promise.all([
      fetchFn(ME_URL),
      fetchFn(SANDBOX_USERS_URL),
    ]);
    if (!meRes.ok || !usersRes.ok) return;
    const [me, users] = await Promise.all([meRes.json(), usersRes.json()]);
    renderUserSwitcher(users, me, doc);
  } catch {
    // not a sandbox / endpoint absent — no switcher, never block the page
  }
}

// Auto-init when running in a real browser context.
if (typeof document !== 'undefined') {
  initUserSwitcher();
}
