// web/account/login-card.js
//
// Mounts an in-app sign-in card inside a Modal (sheetOnMobile: true).
// The card shows a brand mark, title, rationale, and a single "Continue with
// <provider>" button that triggers signIn.
//
// signIn and fetchMe are injectable so tests can pass fakes without real
// navigation or network calls.

import { mountModal } from '../design-system/modal.js';
import { signIn as _defaultSignIn, fetchMe as _defaultFetchMe } from '../api/auth-api.js';
import { loginCardTemplate } from './login-card-template.js';

const STYLE_ID = 'rt-login-card-styles';
const FALLBACK_LABEL = 'single sign-on';

/**
 * Handle a click on the sign-in button.
 * Extracted so it can be unit-tested without touching the DOM.
 *
 * @param {Event} e
 * @param {(returnTo?: string) => void} signInFn
 * @param {string|undefined} returnTo
 */
export function _handleSignInClick(e, signInFn, returnTo) {
  if (!e.target || !e.target.closest) return;
  if (!e.target.closest('[data-action="sign-in"]')) return;
  signInFn(returnTo);
}

/**
 * Mount a sign-in card modal.
 *
 * @param {{
 *   returnTo?: string,
 *   _fetchMe?: () => Promise<object>,
 *   _signIn?: (returnTo?: string) => void,
 * }} [config]
 * @returns {{ dispose(): void }}
 */
export function mountLoginCard(config = {}) {
  const {
    returnTo,
    _fetchMe = _defaultFetchMe,
    _signIn = _defaultSignIn,
  } = config;

  injectStyles();

  // Create a detached host element that we own and can remove on dispose.
  const host = typeof document !== 'undefined' ? document.createElement('div') : null;
  if (host) {
    host.className = 'rt-login-card-host';
    document.body.appendChild(host);
  }

  const modal = mountModal(host, {
    title: '',
    sheetOnMobile: true,
    closeOnBackdrop: true,
  });

  // Render with fallback label immediately, then update when fetchMe resolves.
  function renderContent(providerLabel) {
    const wrapper = document.createElement('div');
    wrapper.className = 'rt-login-card-body';
    wrapper.innerHTML = loginCardTemplate({ providerLabel });

    // Wire up the click handler on the wrapper.
    wrapper.addEventListener('click', (e) => _handleSignInClick(e, _signIn, returnTo));

    modal.setBody(wrapper);
  }

  // First render with fallback.
  renderContent(FALLBACK_LABEL);

  // Async update: fetch the real provider label and re-render.
  _fetchMe()
    .then((me) => {
      const label = (me && me.provider_label) || FALLBACK_LABEL;
      renderContent(label);
    })
    .catch(() => {
      // fetchMe failed — keep the fallback already rendered.
    });

  return {
    dispose() {
      modal.dispose();
      if (host.parentNode) host.parentNode.removeChild(host);
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/login-card.css';
  document.head.appendChild(link);
}
