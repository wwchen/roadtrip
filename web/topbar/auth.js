// web/topbar/auth.js — sign-in / sign-out row in the nav.
//
// Self-contained in the same style as topbar/alerts.js: owns its own DOM
// (#tb-auth), injects its own tb-auth-* styles, and asks the backend who the
// caller is rather than reading a cookie — the session cookie is HttpOnly and
// deliberately invisible to script.
//
// Renders nothing at all when the backend reports no identity provider
// configured. A fresh clone with no Auth0 tenant should look exactly as it did
// before auth existed, not show a control that cannot work.
//
// The topbar "Sign in" button is provider-aware. For a provider with an
// embedded login card (Auth0, `auth_embedded:true` on /api/me) it opens the
// in-app LoginCard (modal). For a hosted provider (Clerk and every other
// vendor) it does a full-page redirect to the provider's hosted flow via
// `signIn()`. The topbar "Sign out" button is likewise a full-page navigation
// (cross-site redirects to the provider, which fetch cannot follow).

import { fetchMe, signIn, signOut } from '../api/auth-api.js';
import { mountLoginCard } from '../account/login-card.js';
import { mountSettingsModal } from '../account/settings-modal.js';
import { escapeHtml } from '../core.js';
import { notifyAuthChanged } from '../availability/auth-events.js';

const ROOT_ID = 'tb-auth';
const STYLE_ID = 'tb-auth-styles';
const SIGN_IN_ACTION = 'sign-in';
const SIGN_OUT_ACTION = 'sign-out';
const OPEN_SETTINGS_ACTION = 'open-settings';

let rootEl = null;
// The last /api/me the row rendered, so the click handler can branch the
// sign-in flow (embedded vs hosted) on the same data the row was drawn from.
let lastMe = null;

// Resolved at init-time; may be overridden by tests via the deps argument.
let _fetchMe = fetchMe;
let _mountLoginCard = mountLoginCard;
let _mountSettingsModal = mountSettingsModal;
let _signIn = signIn;
let _signOut = signOut;

/**
 * Initialise the auth row.
 *
 * @param {object} [deps] - Optional overrides for testability.
 * @param {Function} [deps._fetchMe]            - Replaces the real fetchMe.
 * @param {Function} [deps._mountLoginCard]     - Replaces the real mountLoginCard.
 * @param {Function} [deps._mountSettingsModal] - Replaces the real mountSettingsModal.
 * @param {Function} [deps._signIn]             - Replaces the real signIn (hosted redirect).
 * @param {Function} [deps._signOut]            - Replaces the real signOut.
 */
export function initAuth(deps = {}) {
  if (deps._fetchMe)            _fetchMe            = deps._fetchMe;
  if (deps._mountLoginCard)     _mountLoginCard     = deps._mountLoginCard;
  if (deps._mountSettingsModal) _mountSettingsModal = deps._mountSettingsModal;
  if (deps._signIn)             _signIn             = deps._signIn;
  if (deps._signOut)            _signOut            = deps._signOut;

  rootEl = document.getElementById(ROOT_ID);
  if (!rootEl) return;
  injectAuthStyles();
  rootEl.addEventListener('click', onClick);
  refresh();
}

/**
 * Re-reads the caller's identity. Exported so a later PR can refresh the row
 * after an action that changes it, without reloading the page.
 */
export async function refresh() {
  if (!rootEl) return;
  try {
    render(await _fetchMe());
  } catch {
    // A failed /api/me must not break the nav. Sign-in is optional on every
    // surface that exists today, so degrade to showing nothing.
    render(null);
  }
}

function onClick(e) {
  const btn = e.target.closest('[data-auth-action]');
  if (!btn) return;
  e.preventDefault();
  if (btn.dataset.authAction === SIGN_IN_ACTION)      startSignIn();
  if (btn.dataset.authAction === SIGN_OUT_ACTION)     _signOut();
  if (btn.dataset.authAction === OPEN_SETTINGS_ACTION) _mountSettingsModal();
}

// Embedded providers (Auth0) open the in-app card; hosted providers (Clerk and
// every other vendor) redirect the full page to their hosted flow. The signal
// is `auth_embedded` on /api/me; default to the hosted redirect when it is
// absent, so a provider that never advertises embedded login is never handed
// the Auth0-only card.
function startSignIn() {
  if (lastMe?.auth_embedded) {
    _mountLoginCard();
  } else {
    // signIn() defaults returnTo to the current path, re-validated server-side.
    _signIn();
  }
}

function render(me) {
  lastMe = me;
  if (!me || me.auth_enabled === false) {
    rootEl.innerHTML = '';
    rootEl.hidden = true;
    notifyAuthChanged();
    return;
  }
  rootEl.hidden = false;
  rootEl.innerHTML = me.authenticated ? signedInHtml(me.user) : signedOutHtml();
  notifyAuthChanged();
}

function signedOutHtml() {
  return `<button class="tb-auth-btn" type="button" data-auth-action="${SIGN_IN_ACTION}">Sign in</button>`;
}

function signedInHtml(user) {
  // display_name is absent for providers that do not return one, and for Apple
  // after the first authorization; the address is always present.
  const label = user?.display_name || user?.email || 'Signed in';
  return `
    <button class="tb-auth-who tb-auth-btn" type="button"
      title="${escapeHtml(user?.email || '')}"
      data-auth-action="${OPEN_SETTINGS_ACTION}">${escapeHtml(label)}</button>
    <button class="tb-auth-btn" type="button" data-auth-action="${SIGN_OUT_ACTION}">Sign out</button>
  `;
}

function injectAuthStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const tag = document.createElement('style');
  tag.id = STYLE_ID;
  tag.textContent = `
    #${ROOT_ID} {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
      padding: 4px 2px 0;
      font-size: 13px;
    }
    #${ROOT_ID}[hidden] { display: none; }
    .tb-auth-who {
      color: var(--rt-muted);
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .tb-auth-btn {
      background: none;
      border: none;
      padding: 2px 4px;
      color: var(--rt-brand-text);
      cursor: pointer;
      font: inherit;
    }
    .tb-auth-btn:hover { text-decoration: underline; }
    .tb-auth-btn:focus-visible { outline: 2px solid var(--rt-brand); outline-offset: 2px; }
  `;
  document.head.appendChild(tag);
}
