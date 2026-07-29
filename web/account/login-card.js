// web/account/login-card.js
//
// Mounts an in-app sign-in card inside a Modal (sheetOnMobile: true).
// The card shows a brand mark, title, rationale, and a single "Continue with
// <provider>" button that triggers signIn.
//
// signIn and fetchMe are injectable so tests can pass fakes without real
// navigation or network calls.

import { mountModal as _defaultMountModal } from '../design-system/modal.js';
import { signIn as _defaultSignIn, fetchMe as _defaultFetchMe } from '../api/auth-api.js';
import { loginCardTemplate } from './login-card-template.js';

const STYLE_ID = 'rt-login-card-styles';
const BUTTONS_STYLE_ID = 'rt-buttons-styles';
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
 *   _mountModal?: Function,
 * }} [config]
 * @returns {{ dispose(): void }}
 */
export function mountLoginCard(config = {}) {
  const {
    returnTo,
    _fetchMe = _defaultFetchMe,
    _signIn = _defaultSignIn,
    _mountModal = _defaultMountModal,
  } = config;

  injectStyles();

  // Create a detached host element that we own and can remove on dispose.
  const host = typeof document !== 'undefined' ? document.createElement('div') : null;
  if (host) {
    host.className = 'rt-login-card-host';
    document.body.appendChild(host);
  }

  let isDisposed = false;

  /** Idempotent — the ✕, the backdrop, Escape and a drag-dismiss can all land here. */
  function dispose() {
    if (isDisposed) return;
    isDisposed = true;
    modal.dispose();
    if (host && host.parentNode) host.parentNode.removeChild(host);
  }

  // onClose is what actually makes the ✕, the backdrop and Escape do anything:
  // Modal's close() only invokes this callback, so omitting it leaves every
  // dismissal affordance inert. settings-modal.js wires it the same way.
  const modal = _mountModal(host, {
    title: '',
    sheetOnMobile: true,
    closeOnBackdrop: true,
    onClose: dispose,
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

  return { dispose };
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
