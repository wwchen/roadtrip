// web/account/login-card.js
//
// Mounts an in-app sign-in card inside a Modal (sheetOnMobile: true). The card
// owns an email/password form (authenticates in-page via the embedded-auth
// port) and a "Continue with Google" button (a full-page redirect — OAuth
// cannot embed it). The embedded-auth port, completeLogin, signInGoogle and
// mountModal are injectable so tests pass fakes without network or navigation.
import { mountModal as _defaultMountModal } from '../design-system/modal.js';
import { signInWithConnection as _defaultSignInGoogle, fetchMe } from '../api/auth-api.js';
import { completePasswordLogin as _defaultCompleteLogin, beginPasswordLogin } from '../api/password-auth-api.js';
import { makeAuth0EmbeddedAuth } from './auth0-embedded.js';
import { loginCardTemplate } from './login-card-template.js';

// Lazily builds the real adapter from public /api/me config. Kept out of the
// default parameter so tests that inject _embeddedAuth never call fetchMe.
async function buildDefaultEmbeddedAuth() {
  const me = await fetchMe();
  return makeAuth0EmbeddedAuth({
    domain: me.auth_domain,
    clientID: me.auth_client_id,
    realm: me.auth_realm,
    begin: (returnTo) => beginPasswordLogin(returnTo),
  });
}

const STYLE_ID = 'rt-login-card-styles';
const BUTTONS_STYLE_ID = 'rt-buttons-styles';
const GOOGLE_CONNECTION = 'google-oauth2';

const ERROR_MESSAGES = {
  invalid_credentials: 'That email or password is incorrect.',
  too_many_attempts: 'Too many attempts. Please wait a moment and try again.',
  unverified_email: 'Please verify your email address before signing in.',
  network: 'We could not reach the sign-in service. Please try again.',
};
const GENERIC_ERROR = 'Something went wrong signing you in. Please try again.';
const VALIDATION_ERROR = 'Email and password are required.';

function messageForCode(code) {
  return ERROR_MESSAGES[code] || GENERIC_ERROR;
}

/**
 * Validate + authenticate + complete. Extracted for unit testing without a DOM.
 *
 * @param {{ querySelector: (s: string) => ({ value: string } | null) }} form
 * @param {{
 *   embeddedAuth: { authenticateWithPassword: (e: string, p: string) => Promise<{artifact: string, state: string}> },
 *   completeLogin: (artifact: string, state: string, returnTo: string) => Promise<unknown>,
 *   onError: (msg: string | null) => void,
 *   onLoading: (busy: boolean) => void,
 *   returnTo: string,
 * }} deps
 */
export async function _handlePasswordSubmit(form, { embeddedAuth, completeLogin, onError, onLoading, returnTo }) {
  const email = (form.querySelector('[data-field="email"]')?.value || '').trim();
  const password = form.querySelector('[data-field="password"]')?.value || '';

  onError(null);
  if (!email || !password) {
    onError(VALIDATION_ERROR);
    return;
  }

  onLoading(true);
  try {
    const { artifact, state } = await embeddedAuth.authenticateWithPassword(email, password);
    await completeLogin(artifact, state, returnTo);
    if (typeof window !== 'undefined') window.location.assign(returnTo || '/');
  } catch (err) {
    onError(messageForCode(err && err.code));
  } finally {
    onLoading(false);
  }
}

export function mountLoginCard(config = {}) {
  const {
    returnTo = currentPath(),
    _embeddedAuth = null, // null → lazily built from /api/me in the submit handler; tests inject a fake to skip fetchMe
    _completeLogin = _defaultCompleteLogin,
    _signInGoogle = _defaultSignInGoogle,
    _mountModal = _defaultMountModal,
  } = config;

  injectStyles();

  const host = typeof document !== 'undefined' ? document.createElement('div') : null;
  if (host) {
    host.className = 'rt-login-card-host';
    document.body.appendChild(host);
  }

  let isDisposed = false;
  function dispose() {
    if (isDisposed) return;
    isDisposed = true;
    modal.dispose();
    if (host && host.parentNode) host.parentNode.removeChild(host);
  }

  const modal = _mountModal(host, {
    title: '',
    sheetOnMobile: true,
    closeOnBackdrop: true,
    onClose: dispose,
  });

  const wrapper = typeof document !== 'undefined' ? document.createElement('div') : null;
  if (wrapper) {
    wrapper.className = 'rt-login-card-body';
    wrapper.innerHTML = loginCardTemplate({});

    const form = wrapper.querySelector('[data-role="password-form"]');
    const errorEl = wrapper.querySelector('[data-role="form-error"]');
    const submitBtn = wrapper.querySelector('[data-action="password-submit"]');

    const onError = (msg) => {
      if (!errorEl) return;
      errorEl.textContent = msg || '';
      errorEl.hidden = !msg;
    };
    const onLoading = (busy) => {
      if (submitBtn) {
        submitBtn.disabled = busy;
        submitBtn.textContent = busy ? 'Signing in…' : 'Sign in';
      }
    };

    if (form) {
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        // Disable immediately — before any await — to close the double-submit
        // window. onLoading(false) in _handlePasswordSubmit's finally re-enables.
        if (submitBtn) submitBtn.disabled = true;
        let embeddedAuth = _embeddedAuth;
        if (!embeddedAuth) {
          try { embeddedAuth = await buildDefaultEmbeddedAuth(); }
          catch { onError(GENERIC_ERROR); if (submitBtn) submitBtn.disabled = false; return; }
        }
        _handlePasswordSubmit(form, {
          embeddedAuth, completeLogin: _completeLogin, onError, onLoading, returnTo,
        });
      });
    }

    wrapper.addEventListener('click', (e) => {
      if (e.target.closest && e.target.closest('[data-action="sign-in-google"]')) {
        _signInGoogle(GOOGLE_CONNECTION, returnTo);
      }
    });

    modal.setBody(wrapper);
  }

  return { dispose };
}

function currentPath() {
  if (typeof window === 'undefined') return '/';
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (!document.getElementById(BUTTONS_STYLE_ID)) {
    const btnLink = document.createElement('link');
    btnLink.id = BUTTONS_STYLE_ID;
    btnLink.rel = 'stylesheet';
    btnLink.href = '/web/design-system/buttons.css';
    document.head.appendChild(btnLink);
  }
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/login-card.css';
  document.head.appendChild(link);
}
