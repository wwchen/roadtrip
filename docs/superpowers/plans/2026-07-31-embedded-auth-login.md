# Embedded Sign-In Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single "Continue with single sign-on" button with an inline sign-in card — an owned email/password form that authenticates without leaving the page, plus a "Continue with Google" button that redirects.

**Architecture:** The frontend collects credentials in an owned form and passes them to a vendor adapter (`auth0-embedded.js`) hidden behind a port interface (`embedded-auth-port.js`). The adapter turns `(email, password)` into a standard OAuth artifact in-page (no redirect). The frontend POSTs that artifact to two new backend routes (`/auth/password/begin`, `/auth/password/complete`) that reuse the existing vendor-neutral `AuthController` + `OidcClient` + `IdTokenVerifier` path and mint the same first-party HttpOnly session cookie. Google stays a plain redirect to the existing `/auth/login?connection=google`.

**Tech Stack:** Vanilla ES modules (no bundler; third-party libs load via CDN `<script>` like maplibre), Node's built-in test runner (`node --test`, `.test.mjs`, `node:assert/strict`), Kotlin/Ktor backend, jOOQ, JUnit 5 + `kotlin.test`, `auth0-js` v9 (frontend only).

## Global Constraints

- **Backend stays vendor-neutral.** Do NOT add the Auth0 Java SDK. New routes reuse `AuthController`, `OidcClient`, `IdTokenVerifier` only.
- **`auth0-js` is imported in exactly one file:** `web/account/auth0-embedded.js`. No other frontend file may reference it or any Auth0-specific type.
- **Typed DTOs only** for request/response bodies (`@Serializable` data classes in `ca.floo.roadtrip.model.api`). No hand-built JSON strings in routes.
- **Layering:** routes → `AuthController` → repo. Routes are thin HTTP shells; no route-to-repo path, no business logic in routes.
- **No inline magic constants.** Extract literals to named `const val` / module constants.
- **Backend build uses jvmToolchain(21).** Do NOT export `JAVA_HOME`; Gradle provisions its own JDK. `gradlew` is at repo root.
- **Backend failures surface one generic `login_failed` code** (existing constant in `AuthRoutes.kt`) — never reveal which check failed.
- **Session/flow cookies:** reuse existing `setSessionCookie` / `setLoginFlowCookie` / `clearLoginFlowCookie` in `AuthCookies.kt`. `HttpOnly; SameSite=Lax`.
- **Frontend tests run via** `node --test <files>`; each test file is `*.test.mjs` using `import test from 'node:test'` and `import assert from 'node:assert/strict'`. Dependencies are injected as `_`-prefixed params (existing convention).
- **Custom Auth0 domain** `auth.roadtrip.floo.ca` is the origin the embedded flow uses. Allowed Web Origins must include `https://roadtrip.floo.ca` and local dev origins (`http://127.0.0.1:8765`).

---

## File Structure

**Create:**
- `web/account/embedded-auth-port.js` — the port: interface docs + a `fakeEmbeddedAuth` for tests. No vendor code.
- `web/account/embedded-auth-port.test.mjs` — contract test for the fake.
- `web/account/auth0-embedded.js` — the single `auth0-js` implementation of the port.
- `web/api/password-auth-api.js` — client for `/auth/password/begin` + `/auth/password/complete`.
- `web/api/password-auth-api.test.mjs` — tests for the client using injected `fetch`.
- `backend/.../model/api/PasswordAuthDto.kt` — request/response DTOs.

**Modify:**
- `web/account/login-card-template.js` — inline form markup (email, password, submit, Google button, error slot).
- `web/account/login-card.js` — wire the form to the port; validation, loading, error states.
- `web/account/login-card.test.mjs` — updated template + interaction tests.
- `web/account/login-card.css` — styles for the form/error/loading.
- `web/api/auth-api.js` — add `signInWithConnection(connection, returnTo)` helper for the Google button.
- `backend/.../service/auth/AuthController.kt` — add `beginPasswordLogin()`; reuse `completeLogin()`.
- `backend/.../route/auth/AuthRoutes.kt` — add `POST /auth/password/begin` and `POST /auth/password/complete`.
- `backend/.../model/api/MeResponseDto.kt` — add non-secret `auth_client_id`, `auth_domain`, `auth_realm`.
- `backend/.../service/auth/AuthControllerTest.kt` — tests for the new controller method.
- `index.html` — add the `auth0-js` CDN `<script>`.

Backend route logic is thin; the unit under test is the `AuthController` method (matching the existing pattern — there is no `AuthRoutesTest`).

---

## Task 1: Spike — confirm the auth0-js cross-origin mechanism

**This task is investigative. Its deliverable is a written decision, not shippable code.** It exists because the exact `auth0-js` call sequence for in-page credential validation must not be guessed (per the design's "one real technical risk"). Every later task's concrete shape depends on its outcome.

**Files:**
- Create: `docs/superpowers/specs/2026-07-31-embedded-auth-login-spike.md` (decision record)

**Interfaces:**
- Produces: a decision — **Path A** (adapter returns `{ code }`, backend runs `exchangeCode`) or **Path B** (adapter returns `{ idToken }`, backend runs `IdTokenVerifier.verify`). Later tasks reference this as "the spike outcome."

- [ ] **Step 1: Confirm tenant configuration prerequisites**

Against the Auth0 dashboard (tenant using `auth.roadtrip.floo.ca`):
- Cross-Origin Authentication is enabled.
- Allowed Web Origins includes `http://127.0.0.1:8765`.
- A database connection with real users exists and its name is recorded (the `realm`, e.g. `Username-Password-Authentication`).

Record these values in the decision record.

- [ ] **Step 2: Prototype the in-page credential exchange**

In a throwaway HTML page served from the local origin, load `auth0-js` v9 from `https://cdn.auth0.com/js/auth0/9.24/auth0.min.js` and attempt, in order of preference:

1. **Path A candidate:** `WebAuth` configured with `domain: 'auth.roadtrip.floo.ca'`, `clientID`, `responseType: 'code'`, `responseMode: 'web_message'`, a `redirectUri` of `<root>/auth/callback`, and a PKCE `code_challenge` supplied by the backend `begin` call; call `webAuth.login({ realm, username, password })` and capture whether a `code` is returned in-page via the web_message channel.
2. **Path B candidate:** same but `responseType: 'id_token'` (or `token id_token`), capturing the `id_token` in-page.

Note which actually returns an artifact in-page without a top-level redirect on this tenant.

- [ ] **Step 3: Write the decision record**

Document: chosen path (A or B), the exact `auth0-js` config object used, the `realm` name, and the artifact field name the adapter will resolve (`code` vs `idToken`). Note the redirect behavior for the Google button — confirm the exact connection slug from `Auth0ClaimsDialect.kt` (it maps `google`; the Auth0 connection is typically `google-oauth2`).

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-07-31-embedded-auth-login-spike.md
git commit -m "docs: embedded-auth spike decision (path A/B)"
```

> **Note to implementer:** Tasks 5, 6, and 7 below are written for **Path A** (the preferred path). Each of those tasks has a clearly marked **"If the spike chose Path B"** block. Follow the block matching the decision record. Path-independent tasks (2, 3, 4, 8) are unaffected.

---

## Task 2: Frontend port interface + fake

**Files:**
- Create: `web/account/embedded-auth-port.js`
- Test: `web/account/embedded-auth-port.test.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - Contract (JSDoc): `authenticateWithPassword(email: string, password: string) → Promise<{ artifact: string, state: string }>` where `artifact` is the code or id_token (opaque to callers) and `state` is the CSRF state that the backend `begin` returned.
  - `makeFakeEmbeddedAuth({ artifact = 'fake-artifact', state = 'fake-state', failWith = null }) → { authenticateWithPassword }` — a test double. When `failWith` is set (a string error code like `'invalid_credentials'`), `authenticateWithPassword` rejects with an `Error` whose `.code` is that string.

- [ ] **Step 1: Write the failing test**

```javascript
// web/account/embedded-auth-port.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeFakeEmbeddedAuth } from './embedded-auth-port.js';

test('fake resolves with the configured artifact and state', async () => {
  const port = makeFakeEmbeddedAuth({ artifact: 'abc123', state: 'st-1' });
  const result = await port.authenticateWithPassword('a@b.com', 'pw');
  assert.equal(result.artifact, 'abc123');
  assert.equal(result.state, 'st-1');
});

test('fake rejects with a coded error when failWith is set', async () => {
  const port = makeFakeEmbeddedAuth({ failWith: 'invalid_credentials' });
  await assert.rejects(
    () => port.authenticateWithPassword('a@b.com', 'wrong'),
    (err) => err.code === 'invalid_credentials',
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/account/embedded-auth-port.test.mjs`
Expected: FAIL — `makeFakeEmbeddedAuth` not exported / module not found.

- [ ] **Step 3: Write minimal implementation**

```javascript
// web/account/embedded-auth-port.js
//
// Port for embedded (in-page) credential authentication. Internal callers speak
// only this contract; the vendor SDK lives behind it in auth0-embedded.js. This
// is the frontend mirror of the backend's IdentityProvider seam — swapping
// vendors means rewriting only the adapter, not this contract or its callers.
//
// Contract:
//   authenticateWithPassword(email, password)
//     -> Promise<{ artifact: string, state: string }>
//     Resolves with an opaque OAuth artifact (an authorization code or an
//     id_token, per the spike decision) that the backend redeems, plus the CSRF
//     `state` returned by /auth/password/begin so the caller can echo it to
//     /auth/password/complete. Rejects with an Error whose `.code` is a stable
//     string ('invalid_credentials', 'too_many_attempts', 'unverified_email',
//     'network') so the UI can map it to an owned message without parsing
//     vendor text.

/**
 * A test double for the port. No network, no SDK.
 *
 * @param {{ artifact?: string, state?: string, failWith?: string|null }} [config]
 * @returns {{ authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }> }}
 */
export function makeFakeEmbeddedAuth({ artifact = 'fake-artifact', state = 'fake-state', failWith = null } = {}) {
  return {
    async authenticateWithPassword(_email, _password) {
      if (failWith) {
        const err = new Error(`fake auth failure: ${failWith}`);
        err.code = failWith;
        throw err;
      }
      return { artifact, state };
    },
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/account/embedded-auth-port.test.mjs`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add web/account/embedded-auth-port.js web/account/embedded-auth-port.test.mjs
git commit -m "feat(web): embedded-auth port interface + fake"
```

---

## Task 3: Rework the login-card template into an inline form

**Files:**
- Modify: `web/account/login-card-template.js`
- Test: `web/account/login-card.test.mjs` (replace the template tests)

**Interfaces:**
- Consumes: nothing (pure function).
- Produces: `loginCardTemplate({ googleLabel?: string }) → string`. The returned HTML contains: an email input `[data-field="email"]`, a password input `[data-field="password"]`, a submit button `[data-action="password-submit"]`, an error region `[data-role="form-error"]`, a form `[data-role="password-form"]`, and a Google button `[data-action="sign-in-google"]`. The `providerLabel` parameter is removed.

- [ ] **Step 1: Write the failing tests**

Replace the template tests in `web/account/login-card.test.mjs` with:

```javascript
import assert from 'node:assert/strict';
import test from 'node:test';
import { loginCardTemplate } from './login-card-template.js';

test('template renders the sign-in title', () => {
  assert.match(loginCardTemplate({}), /Sign in to Roadtrip/);
});

test('template renders email and password inputs', () => {
  const html = loginCardTemplate({});
  assert.match(html, /data-field="email"/);
  assert.match(html, /type="email"/);
  assert.match(html, /data-field="password"/);
  assert.match(html, /type="password"/);
});

test('template renders a password submit button', () => {
  assert.match(loginCardTemplate({}), /data-action="password-submit"/);
});

test('template renders an error region', () => {
  assert.match(loginCardTemplate({}), /data-role="form-error"/);
});

test('template renders a Google button', () => {
  const html = loginCardTemplate({});
  assert.match(html, /data-action="sign-in-google"/);
  assert.match(html, /Continue with Google/);
});

test('template escapes a dangerous googleLabel', () => {
  const html = loginCardTemplate({ googleLabel: '<script>evil</script>' });
  assert.doesNotMatch(html, /<script>evil/);
  assert.match(html, /&lt;script&gt;/);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `node --test web/account/login-card.test.mjs`
Expected: FAIL — new selectors absent. (Legacy stub-mount/`providerLabel` tests in this file will also fail now; they are removed in Task 4. If running strictly task-by-task, delete the legacy `data-action="sign-in"` and `providerLabel` tests as part of this step.)

- [ ] **Step 3: Rewrite the template**

```javascript
// web/account/login-card-template.js
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
    <h2 class="lc-title">Sign in to Roadtrip</h2>
    <p class="lc-rationale">Save your notification settings.</p>

    <form class="lc-form" data-role="password-form" novalidate>
      <label class="lc-label" for="lc-email">Email</label>
      <input class="lc-input" id="lc-email" data-field="email" type="email"
             name="email" autocomplete="email" required />

      <label class="lc-label" for="lc-password">Password</label>
      <input class="lc-input" id="lc-password" data-field="password" type="password"
             name="password" autocomplete="current-password" required />

      <p class="lc-form-error" data-role="form-error" role="alert" hidden></p>

      <button class="rt-btn rt-btn--primary lc-submit-btn" type="submit"
              data-action="password-submit" style="width:100%">Sign in</button>
    </form>

    <div class="lc-divider" aria-hidden="true"><span>or</span></div>

    <button class="rt-btn rt-btn--secondary lc-google-btn" type="button"
            data-action="sign-in-google" style="width:100%">Continue with ${google}</button>
  `;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `node --test web/account/login-card.test.mjs`
Expected: template tests PASS. (Interaction tests are added in Task 4.)

- [ ] **Step 5: Commit**

```bash
git add web/account/login-card-template.js web/account/login-card.test.mjs
git commit -m "feat(web): inline email/password + Google login-card template"
```

---

## Task 4: Wire the form to the port (validation, loading, error states)

**Files:**
- Modify: `web/account/login-card.js`
- Modify: `web/account/login-card.css`
- Modify: `web/api/auth-api.js`
- Test: `web/account/login-card.test.mjs` (add interaction tests, remove legacy `providerLabel` stub tests)

**Interfaces:**
- Consumes: `makeFakeEmbeddedAuth` (Task 2); a `completeLogin(artifact, state, returnTo)` callback (real client wired in Task 8 — inject a fake here); `loginCardTemplate` (Task 3); `signInWithConnection` (Step 1).
- Produces:
  - `signInWithConnection(connection, returnTo)` in `auth-api.js` (Step 1).
  - `mountLoginCard({ returnTo, _embeddedAuth, _completeLogin, _signInGoogle, _mountModal }) → { dispose }`.
  - `_handlePasswordSubmit(form, { embeddedAuth, completeLogin, onError, onLoading, returnTo })` — exported for unit testing. `completeLogin` is called as `completeLogin(artifact, state, returnTo)`.

- [ ] **Step 1: Add the connection-scoped Google helper (auth-api.js)**

In `web/api/auth-api.js`, extract the existing `return_to` literal to a constant and add the helper:

```javascript
// Add near the other URL constants:
const RETURN_TO_PARAM = 'return_to';
const CONNECTION_PARAM = 'connection';

// Update signIn to reuse RETURN_TO_PARAM (behavior unchanged):
export function signIn(returnTo = currentPath()) {
  window.location.assign(`${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}`);
}

/**
 * Starts a social sign-in that redirects to the provider's consent screen.
 * OAuth cannot embed this step, so it is a full-page navigation like signIn.
 */
export function signInWithConnection(connection, returnTo = currentPath()) {
  const url = `${LOGIN_URL}?${RETURN_TO_PARAM}=${encodeURIComponent(returnTo)}` +
    `&${CONNECTION_PARAM}=${encodeURIComponent(connection)}`;
  window.location.assign(url);
}
```

- [ ] **Step 2: Write the failing interaction tests**

In `web/account/login-card.test.mjs`, delete the old `data-action="sign-in"` / `providerLabel` stub-mount tests and add:

```javascript
import { makeFakeEmbeddedAuth } from './embedded-auth-port.js';

test('_handlePasswordSubmit: valid credentials call embeddedAuth then completeLogin', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const embeddedAuth = makeFakeEmbeddedAuth({ artifact: 'code-xyz', state: 'st-9' });
  const completed = [];
  const completeLogin = async (artifact, state, returnTo) => { completed.push([artifact, state, returnTo]); };
  const errors = [];
  const loading = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'a@b.com' };
      if (sel === '[data-field="password"]') return { value: 'secret' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin,
    onError: (m) => errors.push(m), onLoading: (b) => loading.push(b),
    returnTo: '/watches',
  });
  assert.deepEqual(completed, [['code-xyz', 'st-9', '/watches']]);
  assert.equal(errors.filter(Boolean).length, 0);
  assert.deepEqual(loading, [true, false]);
});

test('_handlePasswordSubmit: empty fields report a validation error and skip the network', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  let called = false;
  const embeddedAuth = { authenticateWithPassword: async () => { called = true; return { artifact: 'x', state: 's' }; } };
  const errors = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: '' };
      if (sel === '[data-field="password"]') return { value: '' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin: async () => {},
    onError: (m) => errors.push(m), onLoading: () => {}, returnTo: '/',
  });
  assert.equal(called, false);
  assert.equal(errors.filter(Boolean).length, 1);
  assert.match(errors.filter(Boolean)[0], /email|password|required/i);
});

test('_handlePasswordSubmit: invalid_credentials maps to an owned message and clears loading', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const embeddedAuth = makeFakeEmbeddedAuth({ failWith: 'invalid_credentials' });
  const errors = [];
  const loading = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'a@b.com' };
      if (sel === '[data-field="password"]') return { value: 'wrong' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin: async () => {},
    onError: (m) => errors.push(m), onLoading: (b) => loading.push(b), returnTo: '/',
  });
  assert.equal(errors.filter(Boolean).length, 1);
  assert.match(errors.filter(Boolean)[0], /incorrect|invalid|wrong/i);
  assert.deepEqual(loading, [true, false]);
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `node --test web/account/login-card.test.mjs`
Expected: FAIL — `_handlePasswordSubmit` not exported.

- [ ] **Step 4: Implement the form wiring**

Rewrite `web/account/login-card.js`:

```javascript
// web/account/login-card.js
//
// Mounts an in-app sign-in card inside a Modal (sheetOnMobile: true). The card
// owns an email/password form (authenticates in-page via the embedded-auth
// port) and a "Continue with Google" button (a full-page redirect — OAuth
// cannot embed it). The embedded-auth port, completeLogin, signInGoogle and
// mountModal are injectable so tests pass fakes without network or navigation.
import { mountModal as _defaultMountModal } from '../design-system/modal.js';
import { signInWithConnection as _defaultSignInGoogle } from '../api/auth-api.js';
import { completePasswordLogin as _defaultCompleteLogin } from '../api/password-auth-api.js';
import { makeFakeEmbeddedAuth } from './embedded-auth-port.js';
import { loginCardTemplate } from './login-card-template.js';

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
    _embeddedAuth = makeFakeEmbeddedAuth(), // Task 8 replaces this default with the real adapter builder
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
      form.addEventListener('submit', (e) => {
        e.preventDefault();
        _handlePasswordSubmit(form, {
          embeddedAuth: _embeddedAuth,
          completeLogin: _completeLogin,
          onError, onLoading, returnTo,
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
```

- [ ] **Step 5: Add styles for the form, divider, error, and loading**

Append to `web/account/login-card.css` (reuse existing `var(--rt-*)` tokens):

```css
.lc-form { display: flex; flex-direction: column; gap: 6px; text-align: left; }
.lc-label { font-size: 12px; color: var(--rt-muted); margin-top: 8px; }
.lc-input {
  width: 100%; padding: 8px 10px; border-radius: 6px;
  border: 1px solid var(--rt-border); background: var(--rt-surface); color: var(--rt-text);
  font: inherit;
}
.lc-input:focus-visible { outline: 2px solid var(--rt-brand); outline-offset: 1px; }
.lc-form-error { color: var(--rt-danger, #d33); font-size: 13px; margin: 6px 0 0; }
.lc-submit-btn { margin-top: 12px; }
.lc-submit-btn:disabled { opacity: 0.7; cursor: progress; }
.lc-divider {
  display: flex; align-items: center; gap: 8px; margin: 14px 0;
  color: var(--rt-muted); font-size: 12px;
}
.lc-divider::before, .lc-divider::after {
  content: ''; flex: 1; height: 1px; background: var(--rt-border);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `node --test web/account/login-card.test.mjs web/account/embedded-auth-port.test.mjs`
Expected: PASS. Then the whole web suite: `find web -name '*.test.mjs' | sort | xargs node --test` — Expected: PASS (`topbar/auth.js` still calls `mountLoginCard()` with no args, which remains valid).

- [ ] **Step 7: Commit**

```bash
git add web/account/login-card.js web/account/login-card.css web/account/login-card.test.mjs web/api/auth-api.js
git commit -m "feat(web): wire login-card form to embedded-auth port"
```

---

## Task 5: Backend — POST /auth/password/begin

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/PasswordAuthDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/AuthController.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt`

**Interfaces:**
- Consumes: existing `AuthController` fields; `IdentityProviderRegistry.active().authorizationRequest(returnTo)` → `AuthorizationRequest(authorizationUrl, state, nonce, codeVerifier)`; `LoginFlowState(state, nonce, codeVerifier, returnTo)`; `Pkce.challengeFor(verifier)`; `LoginFlowState.encode(key)`; cookie helper `setLoginFlowCookie`.
- Produces:
  - `AuthController.PasswordLoginStart(flow: LoginFlowState, passwordChallenge: String)` and `AuthController.beginPasswordLogin(rawReturnTo: String?) → PasswordLoginStart`.
  - `PasswordBeginRequestDto(returnTo: String?)`, `PasswordBeginResponseDto(state, nonce, codeChallenge)` (`@Serializable`).
  - Route `POST /auth/password/begin` sets the flow cookie and returns `PasswordBeginResponseDto`.

**Path A (preferred):** as written. **If the spike chose Path B** (id_token): `PasswordBeginResponseDto` omits `codeChallenge` (response is `state, nonce`), and `PasswordLoginStart` omits `passwordChallenge`. Implement the variant matching the decision record.

- [ ] **Step 1: Write the failing controller test**

```kotlin
// Add to AuthControllerTest.kt
@Test
fun `beginPasswordLogin mints a flow whose challenge derives from its verifier`() {
    val start = kotlinx.coroutines.runBlocking { authController.beginPasswordLogin("/watches") }

    assertNotNull(start.flow.state)
    assertNotNull(start.flow.codeVerifier)
    assertEquals(Pkce.challengeFor(start.flow.codeVerifier), start.passwordChallenge)
    assertEquals("/watches", start.flow.returnTo)
}
```

> If the spike chose Path B, assert `start.flow.state`/`nonce` are present and drop the challenge assertion.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.AuthControllerTest"`
Expected: FAIL — `beginPasswordLogin` / `passwordChallenge` unresolved.

- [ ] **Step 3: Implement the controller method + DTO + route**

Add to `AuthController`:

```kotlin
/** A started embedded password login: the flow to remember, and the PKCE
 *  challenge the in-page adapter forwards to the provider. */
data class PasswordLoginStart(
    val flow: LoginFlowState,
    val passwordChallenge: String,
)

/**
 * Starts an embedded password login. Identical flow-secret minting to
 * [beginLogin], but returns the PKCE challenge rather than a redirect URL: the
 * browser talks to the provider in-page, so there is nowhere to redirect. The
 * verifier stays server-side in the signed flow cookie.
 */
suspend fun beginPasswordLogin(rawReturnTo: String?): PasswordLoginStart {
    val returnTo = sanitizeReturnTo(rawReturnTo)
    val request = identityProviderRegistry.active().authorizationRequest(returnTo)
    return PasswordLoginStart(
        flow = LoginFlowState(
            state = request.state,
            nonce = request.nonce,
            codeVerifier = request.codeVerifier,
            returnTo = returnTo,
        ),
        passwordChallenge = Pkce.challengeFor(request.codeVerifier),
    )
}
```

Create `PasswordAuthDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PasswordBeginRequestDto(
    @SerialName("return_to") val returnTo: String? = null,
)

@Serializable
data class PasswordBeginResponseDto(
    val state: String,
    val nonce: String,
    @SerialName("code_challenge") val codeChallenge: String,
)
```

Add the route in `AuthRoutes.kt` inside `route("/auth")` (add imports `io.ktor.server.request.receive`, `io.ktor.server.routing.post`, and the DTOs):

```kotlin
post("/password/begin") {
    val auth = wiring ?: return@post call.respondAuthDisabled()
    val body = runCatching { call.receive<PasswordBeginRequestDto>() }.getOrNull()
    val start = runCatching { auth.authController.beginPasswordLogin(body?.returnTo) }
        .getOrElse { failure ->
            log.error("could not begin password login", failure)
            return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadGateway)
        }
    call.response.setLoginFlowCookie(start.flow.encode(auth.flowSigningKey), auth.isCookieSecure)
    call.respond(
        PasswordBeginResponseDto(
            state = start.flow.state,
            nonce = start.flow.nonce,
            codeChallenge = start.passwordChallenge,
        ),
    )
}.access(RouteAccess.Anonymous)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.AuthControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/PasswordAuthDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/auth/AuthController.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt
git commit -m "feat(backend): POST /auth/password/begin"
```

---

## Task 6: Backend — POST /auth/password/complete

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/PasswordAuthDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt`

**Interfaces:**
- Consumes: existing `AuthController.completeLogin(code, returnedState, flow) → LoginResult(session, returnTo)`; `LoginFlowState.decode`; cookie helpers `loginFlowCookie`, `clearLoginFlowCookie`, `setSessionCookie`.
- Produces:
  - `PasswordCompleteRequestDto(code: String, state: String)` (`@Serializable`).
  - Route `POST /auth/password/complete` — decodes the flow cookie, calls `completeLogin(code, state, flow)`, clears the flow cookie, sets the session cookie, returns 204.

**Path A (preferred):** the artifact is a `code`; reuse `completeLogin` unchanged (it runs `exchangeCode`). **If the spike chose Path B** (id_token): the request DTO is `PasswordCompleteRequestDto(idToken, state, nonce)`, and you add `AuthController.completePasswordLoginWithIdToken(idToken, returnedState, flow)` that verifies via the existing `IdTokenVerifier` (add an `IdentityProvider.verifyIdToken` seam) instead of `exchange`. The route contract (decode flow, set session cookie, 204) is identical.

- [ ] **Step 1: Write the failing controller test**

```kotlin
// Add to AuthControllerTest.kt — proves the password path lands the same session as the callback.
@Test
fun `completeLogin from a password-begin flow issues a resolvable session`() {
    val start = kotlinx.coroutines.runBlocking { authController.beginPasswordLogin("/watches") }
    val result = kotlinx.coroutines.runBlocking {
        authController.completeLogin("good-code", start.flow.state, start.flow)
    }
    val principal = authController.resolve(result.session.token)
    assertTrue(principal is Principal.User)
    assertEquals("/watches", result.returnTo)
}
```

- [ ] **Step 2: Run test to verify it fails/passes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.AuthControllerTest"`
Expected: compiles once Task 5's `beginPasswordLogin` exists; PASS (it documents that the password flow reuses `completeLogin`).

- [ ] **Step 3: Add the DTO and route**

Add to `PasswordAuthDto.kt`:

```kotlin
@Serializable
data class PasswordCompleteRequestDto(
    val code: String,
    val state: String,
)
```

Add the route in `AuthRoutes.kt`:

```kotlin
post("/password/complete") {
    val auth = wiring ?: return@post call.respondAuthDisabled()

    val flowCookie = call.request.loginFlowCookie()
    call.response.clearLoginFlowCookie(auth.isCookieSecure)

    val body = runCatching { call.receive<PasswordCompleteRequestDto>() }.getOrNull()
    val flow = flowCookie?.let { LoginFlowState.decode(it, auth.flowSigningKey) }
    if (body == null || flow == null) {
        log.warn("password/complete without a usable flow or body")
        return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadRequest)
    }

    val result = runCatching { auth.authController.completeLogin(body.code, body.state, flow) }
        .getOrElse { failure ->
            log.warn("password login could not be completed: {}", failure.message)
            return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized)
        }

    call.response.setSessionCookie(
        token = result.session.token,
        isSecure = auth.isCookieSecure,
        maxAgeSeconds = auth.sessionMaxAgeSeconds,
    )
    call.respond(HttpStatusCode.NoContent)
}.access(RouteAccess.Anonymous)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.AuthControllerTest"`
Expected: PASS.

- [ ] **Step 5: Run ktlint + detekt (CI gates these separately)**

Run: `./gradlew :backend:ktlintCheck :backend:detekt`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/PasswordAuthDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt
git commit -m "feat(backend): POST /auth/password/complete"
```

---

## Task 7: Expose public auth config on /api/me + the auth0-js adapter

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/MeResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt` (`meResponse`) + `AuthRouteWiring` (carry config)
- Create: `web/account/auth0-embedded.js`
- Modify: `index.html` (add the CDN `<script>`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt` (no new backend test needed; config passthrough is covered by manual verify — but add a DTO field-presence assertion if a `MeResponseDto` test exists)

**Interfaces:**
- Consumes: `AuthConfig` (`issuer`, `clientId`); the `realm` (database connection name) from the spike decision record; the global `auth0` object from the CDN script; the `beginPasswordLogin` client (Task 8, injected as `begin`).
- Produces:
  - `MeResponseDto` gains `authClientId`, `authDomain`, `authRealm` (nullable; present only when auth is enabled) — wire names `auth_client_id`, `auth_domain`, `auth_realm`.
  - `makeAuth0EmbeddedAuth({ domain, clientID, realm, begin }) → { authenticateWithPassword(email, password) → Promise<{ artifact, state }> }`, satisfying the port contract from Task 2.

> **Written for Path A.** If the spike chose Path B, set `responseType: 'id_token'`, drop `code_challenge`/`redirectUri`, and resolve `{ artifact: authResult.idToken, state }`. The port contract and callers do not change.

- [ ] **Step 1: Add the public config fields to MeResponseDto + meResponse**

In `MeResponseDto.kt`, add three nullable fields with `@SerialName("auth_client_id")`, `@SerialName("auth_domain")`, `@SerialName("auth_realm")`. In `AuthRoutes.meResponse`, populate them from config. Carry the needed values on `AuthRouteWiring` (add `authClientId`, `authIssuer`, `authRealm`), populated in `authRouteWiring()` in `RouteModule.kt` from `authConfig.clientId`, `authConfig.issuer`, and a new `AuthConfig.realm` (add to `AuthConfig` + `application.yaml` with env `ROADTRIP_AUTH_REALM`, default the common Auth0 DB connection name). The `domain` the adapter uses is the custom domain `auth.roadtrip.floo.ca`; if it differs from `issuer`, add `ROADTRIP_AUTH_EMBEDDED_DOMAIN` — otherwise derive from `issuer`. Record the exact value in the spike decision.

- [ ] **Step 2: Implement the adapter (Path A) using the spike's confirmed config**

```javascript
// web/account/auth0-embedded.js
//
// THE ONLY file that imports/uses auth0-js. Everything else speaks the
// embedded-auth-port contract. Swapping providers means rewriting this file.
// auth0-js loads via a CDN <script> in index.html as the global `auth0`
// (same pattern as maplibre/turf).

const CODE_CHALLENGE_METHOD = 'S256';

/**
 * @param {{ domain: string, clientID: string, realm: string,
 *   begin: (returnTo: string) => Promise<{ state: string, nonce: string, code_challenge: string }> }} config
 * @returns {{ authenticateWithPassword: (email: string, password: string) => Promise<{ artifact: string, state: string }> }}
 */
export function makeAuth0EmbeddedAuth({ domain, clientID, realm, begin }) {
  return {
    async authenticateWithPassword(email, password) {
      // Backend mints + stores the PKCE verifier and returns its challenge + state.
      const { state, nonce, code_challenge: codeChallenge } = await begin(currentPath());

      const webAuth = new globalThis.auth0.WebAuth({
        domain, clientID,
        redirectUri: `${window.location.origin}/auth/callback`,
        responseType: 'code',
        responseMode: 'web_message',
      });

      const artifact = await new Promise((resolve, reject) => {
        webAuth.login(
          {
            realm, username: email, password, state, nonce,
            code_challenge: codeChallenge,
            code_challenge_method: CODE_CHALLENGE_METHOD,
          },
          (err, authResult) => {
            if (err) return reject(mapAuth0Error(err));
            resolve(authResult.code);
          },
        );
      });

      return { artifact, state };
    },
  };
}

// Map vendor error shapes to the port's stable codes.
function mapAuth0Error(err) {
  const code = err && (err.code || err.error);
  const e = new Error((err && (err.description || err.error_description)) || 'auth failed');
  if (code === 'invalid_user_password' || code === 'access_denied') e.code = 'invalid_credentials';
  else if (code === 'too_many_attempts') e.code = 'too_many_attempts';
  else if (code === 'unauthorized' && /verif/i.test(e.message)) e.code = 'unverified_email';
  else e.code = 'network';
  return e;
}

function currentPath() {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}` || '/';
}
```

- [ ] **Step 3: Add the CDN script to index.html**

Near the maplibre/turf script tags (`index.html:1773-1776`), add before `/web/app.js`:

```html
<script src="https://cdn.auth0.com/js/auth0/9.24.1/auth0.min.js"></script>
```

- [ ] **Step 4: Run backend tests + lint**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt`
Expected: PASS. Run the web suite too: `find web -name '*.test.mjs' | sort | xargs node --test` — Expected: PASS (adapter has no automated test yet; verified in Task 8).

- [ ] **Step 5: Commit**

```bash
git add web/account/auth0-embedded.js index.html \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/MeResponseDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/main/resources/application.yaml
git commit -m "feat: public auth config on /api/me + auth0-js embedded adapter"
```

---

## Task 8: Password-auth client, real adapter as default, end-to-end verify

**Files:**
- Create: `web/api/password-auth-api.js`
- Test: `web/api/password-auth-api.test.mjs`
- Modify: `web/account/login-card.js` (build the real adapter from `/api/me` config as the default `_embeddedAuth`)

**Interfaces:**
- Consumes: `makeAuth0EmbeddedAuth` (Task 7); `/api/me` config fields (Task 7); `fetchMe` (`auth-api.js`).
- Produces:
  - `beginPasswordLogin(returnTo, { _fetch }) → Promise<{ state, nonce, code_challenge }>` (POST `/auth/password/begin`).
  - `completePasswordLogin(code, state, returnTo, { _fetch }) → Promise<null>` (POST `/auth/password/complete`; resolves on 204).

- [ ] **Step 1: Write the failing client test**

```javascript
// web/api/password-auth-api.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { beginPasswordLogin, completePasswordLogin } from './password-auth-api.js';

test('beginPasswordLogin POSTs return_to and returns the flow material', async () => {
  const calls = [];
  const fakeFetch = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: true, status: 200, json: async () => ({ state: 's', nonce: 'n', code_challenge: 'c' }) };
  };
  const out = await beginPasswordLogin('/watches', { _fetch: fakeFetch });
  assert.equal(out.code_challenge, 'c');
  assert.match(calls[0].url, /\/auth\/password\/begin/);
  assert.equal(calls[0].opts.method, 'POST');
  assert.deepEqual(JSON.parse(calls[0].opts.body), { return_to: '/watches' });
});

test('completePasswordLogin POSTs code+state and resolves on 204', async () => {
  const calls = [];
  const fakeFetch = async (url, opts) => { calls.push({ url, opts }); return { ok: true, status: 204, json: async () => null }; };
  const out = await completePasswordLogin('code-1', 'st-1', '/', { _fetch: fakeFetch });
  assert.equal(out, null);
  assert.deepEqual(JSON.parse(calls[0].opts.body), { code: 'code-1', state: 'st-1', return_to: '/' });
});

test('completePasswordLogin rejects with .code on error body', async () => {
  const fakeFetch = async () => ({ ok: false, status: 401, json: async () => ({ error: 'login_failed' }) });
  await assert.rejects(
    () => completePasswordLogin('bad', 'st', '/', { _fetch: fakeFetch }),
    (err) => err.code === 'login_failed',
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/api/password-auth-api.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the client**

```javascript
// web/api/password-auth-api.js
//
// Client for the embedded password endpoints. The session lands as an HttpOnly
// cookie set by /auth/password/complete; nothing sensitive is returned to script.
const BEGIN_URL = '/auth/password/begin';
const COMPLETE_URL = '/auth/password/complete';
const CREDENTIALS = 'same-origin';

async function postJson(url, body, _fetch) {
  const response = await _fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
  });
  if (!response.ok) {
    let code;
    try { code = (await response.json())?.error; } catch { /* non-JSON body */ }
    const err = new Error(`${url}: HTTP ${response.status}`);
    err.code = code;
    throw err;
  }
  return response.status === 204 ? null : response.json();
}

export function beginPasswordLogin(returnTo, { _fetch = fetch } = {}) {
  return postJson(BEGIN_URL, { return_to: returnTo }, _fetch);
}

export function completePasswordLogin(code, state, returnTo, { _fetch = fetch } = {}) {
  return postJson(COMPLETE_URL, { code, state, return_to: returnTo }, _fetch);
}
```

- [ ] **Step 4: Build the real adapter as the login-card default**

In `login-card.js`, add a lazy default builder and use it when `_embeddedAuth` is not injected. Tests still inject `_embeddedAuth` (the fake), so this path is production-only:

```javascript
import { makeAuth0EmbeddedAuth } from './auth0-embedded.js';
import { beginPasswordLogin } from '../api/password-auth-api.js';
import { fetchMe } from '../api/auth-api.js';

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
```

Change `mountLoginCard` so `_embeddedAuth` defaults to `null`; in the submit handler, resolve the adapter lazily: `const embeddedAuth = _embeddedAuth || await buildDefaultEmbeddedAuth();` before calling `_handlePasswordSubmit`. Pass that resolved `embeddedAuth` in. Keep the injected-fake path (tests pass `_embeddedAuth`) unchanged and synchronous-friendly.

Update the submit listener to:

```javascript
if (form) {
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    let embeddedAuth = _embeddedAuth;
    if (!embeddedAuth) {
      try { embeddedAuth = await buildDefaultEmbeddedAuth(); }
      catch { onError(GENERIC_ERROR); return; }
    }
    _handlePasswordSubmit(form, {
      embeddedAuth, completeLogin: _completeLogin, onError, onLoading, returnTo,
    });
  });
}
```

- [ ] **Step 5: Run the full frontend suite**

Run: `find web -name '*.test.mjs' | sort | xargs node --test`
Expected: PASS (login-card tests inject `_embeddedAuth`, so no `fetchMe` is hit).

- [ ] **Step 6: End-to-end manual verification**

With the Tilt dev stack up (`tilt up`) and the Auth0 tenant configured (Task 1 prerequisites), against `http://127.0.0.1:8765`:
1. Open the sign-in modal → the inline email/password form + "Continue with Google" render.
2. Enter a known good email/password → login completes **without a page redirect to Auth0**, the session cookie is set, and the topbar shows the signed-in identity.
3. Enter a wrong password → the owned error message appears and no navigation happens.
4. Click "Continue with Google" → redirects to Google and returns signed in.

Document the results in the commit message.

- [ ] **Step 7: Commit**

```bash
git add web/api/password-auth-api.js web/api/password-auth-api.test.mjs web/account/login-card.js
git commit -m "feat(web): password-auth client + real adapter wired end-to-end"
```

---

## Self-Review notes

- **Spec coverage:** Inline form + Google redirect → Tasks 3,4,7,8. Vendor quarantine (port + single adapter file) → Tasks 2,7. Backend reuse, no Auth0 Java SDK → Tasks 5,6. First-party HttpOnly session preserved → Tasks 5,6 (reuse `setSessionCookie`/`completeLogin`). Spike-first risk retirement → Task 1. Owned error messages + generic backend `login_failed` → Tasks 4,6. Testing (port contract, interaction, controller, client) → Tasks 2,4,5,6,8. Tenant config → Task 1 + design doc.
- **Path A/B contingency:** Tasks 1,5,6,7 carry explicit Path B variants; path-independent tasks (2,3,4,8) do not branch.
- **State threading:** `{ artifact, state }` is the port's resolved shape from Task 2 onward; the fake (Task 2), adapter (Task 7), handler (Task 4: `completeLogin(artifact, state, returnTo)`), and client (Task 8: `completePasswordLogin(code, state, returnTo)`) all agree. No later reconciliation needed.
- **Type consistency:** `beginPasswordLogin`/`completePasswordLogin` (client), `beginPasswordLogin`/`PasswordLoginStart.passwordChallenge` (controller), `PasswordBegin/CompleteRequestDto`/`PasswordBeginResponseDto` (DTOs), `authenticateWithPassword` (port), `signInWithConnection` (auth-api) are used consistently across tasks.
- **Lazy adapter:** the real adapter (which calls `fetchMe`) is built only in production; every test injects `_embeddedAuth`, so no test hits the network.
