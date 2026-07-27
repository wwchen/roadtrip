import { escapeHtml } from '../core.js';

const FALLBACK_PROVIDER_LABEL = 'single sign-on';

/**
 * Pure function — no DOM access. Returns an HTML string for the login card body.
 *
 * @param {{ providerLabel?: string | null }} config
 * @returns {string}
 */
export function loginCardTemplate({ providerLabel } = {}) {
  const label = providerLabel || FALLBACK_PROVIDER_LABEL;

  return `
    <div class="lc-brand-mark" aria-hidden="true">
      <svg class="lc-brand-mark-svg" width="40" height="40" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect class="lc-brand-mark-bg" width="40" height="40" rx="8"/>
        <path class="lc-brand-mark-glyph" d="M20 8 L30 28 H10 Z"/>
      </svg>
    </div>
    <h2 class="lc-title">Sign in to Roadtrip</h2>
    <p class="lc-rationale">Save your watches and access your account from any device.</p>
    <button
      class="rt-btn rt-btn--primary lc-sign-in-btn"
      type="button"
      data-action="sign-in"
      style="width:100%"
    >Continue with ${escapeHtml(label)}</button>
  `;
}
