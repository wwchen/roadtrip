import { escapeHtml } from '../core.js';

const DEFAULT_GOOGLE_LABEL = 'Google';

/**
 * Pure function — no DOM access. Returns the login card body HTML: an owned
 * email/password form plus a Google button that redirects. The password form
 * authenticates in-page; the Google button leaves the page (OAuth requires it).
 *
 * @param {{ googleLabel?: string | null }} [config]
 * @returns {string}
 */
export function loginCardTemplate({ googleLabel } = {}) {
  const google = escapeHtml(googleLabel || DEFAULT_GOOGLE_LABEL);

  return `
    <div class="lc-brand-mark" aria-hidden="true">
      <svg class="lc-brand-mark-svg" width="40" height="40" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect class="lc-brand-mark-bg" width="40" height="40" rx="8"/>
        <path class="lc-brand-mark-glyph" d="M20 8 L30 28 H10 Z"/>
      </svg>
    </div>
    <h2 class="lc-title" data-role="title">Sign in to Roadtrip</h2>
    <p class="lc-rationale">Save your notification settings.</p>

    <form class="lc-form" data-role="password-form" novalidate>
      <label class="lc-label" for="lc-email">Email</label>
      <input class="lc-input" id="lc-email" data-field="email" type="email"
             name="email" autocomplete="email" required />

      <label class="lc-label" for="lc-password">Password</label>
      <input class="lc-input" id="lc-password" data-field="password" type="password"
             name="password" autocomplete="current-password" required />

      <p class="lc-hint" data-role="password-hint" hidden>Use at least 8 characters.</p>
      <p class="lc-form-error" data-role="form-error" role="alert" hidden></p>

      <button class="rt-btn rt-btn--primary lc-submit-btn" type="submit"
              data-action="password-submit" style="width:100%">Sign in</button>
    </form>

    <p class="lc-mode-switch">
      <span data-role="mode-prompt">Don't have an account?</span>
      <button type="button" class="lc-mode-toggle" data-action="toggle-mode">Sign up</button>
    </p>

    <div class="lc-divider" aria-hidden="true"><span>or</span></div>

    <button class="rt-btn rt-btn--secondary lc-google-btn" type="button"
            data-action="sign-in-google" style="width:100%">Continue with ${google}</button>
  `;
}
