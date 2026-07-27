# Account: Login + Settings — Frontend (Track A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app login card and a tabbed settings modal (Profile / Notifications / Account) built from the design system, wired to `/api/me`, `/auth/*`, and the `/api/settings/*` endpoints from the backend plan.

**Architecture:** Three new design-system primitives (`Modal`, `Tabs`, `SecretField`) fill real gaps; a new `web/account/` domain area composes them plus existing primitives (`FormSection`, `Banner`, `DoubleConfirmButton`) into `LoginCard`, `SettingsModal`, and three panels. `web/topbar/auth.js` gains triggers: anonymous → open `LoginCard`; signed-in name → open `SettingsModal`.

**Tech Stack:** Vanilla JS (no framework), the `mountComponent(container, config) → { dispose(), ... }` contract, pure `*-template.js` files, `*.css` using `--rt-*` tokens injected via `<link>`, `node:test` + `node:assert/strict` (`*.test.mjs`).

## Global Constraints

- Component contract (`docs/frontend-components.md`): three files per component (`*.js` controller, `*-template.js` pure/`escapeHtml`-only, `*.css` with `--rt-*` tokens); no HTML fragments in `.js`; styles injected via `<link>` with an id guard; event delegation on the container; `dispose()` cleans up listeners and children; parents dispose children before re-render.
- **Design system: follow, then update.** Reuse `web/design-system/` primitives + `tokens.css` first; `--rt-*` tokens only, no inline hex. Honour README conventions: blue (`--rt-brand`) is the *only* interactive color, one primary per surface, 4px grid, ≥44px touch targets, 16px input font, `tabular-nums` on counts/dates. **A new primitive is not done until it is registered in `web/design-system/roadtrip-design-system.html` (the visual gallery) and any new convention added to `README.md`.**
- The Slack bot token is **write-only**: the UI never receives it — only `slack_configured` + `slack_token_hint` (last 4). `SecretField` can set or clear, never read back.
- Auth is optional: when `/api/me` returns `auth_enabled: false`, render nothing (preserve current `web/topbar/auth.js` behavior).
- Mobile: login card → bottom sheet; settings modal → full-height sheet (`Modal` handles this via a `sheetOnMobile` option).
- **Testing (READ — governs every task's test step).** Tests run `node --test <path>.test.mjs`. This repo has **no DOM library** (no `package.json`, no jsdom). The pattern (see `web/watch-editor.test.mjs`) temporarily assigns `globalThis.document` a MINIMAL stub (`getElementById`, `createElement`, `head.appendChild`) and passes a fake `host` `{ innerHTML: '', addEventListener() {}, removeEventListener() {} }`, then asserts on `host.innerHTML` strings and injected `<style>`/`<link>` content. It does **NOT** support `querySelector`, real event dispatch, `.click()`, `KeyboardEvent`, or focus. Therefore, put interaction/state LOGIC in **pure functions** and unit-test those: the pure `*-template.js` functions (assert on the returned HTML string, including `escapeHtml`), a pure `SecretField` mode reducer, a pure error-code→message map, and dirty-state comparison helpers. Controller DOM/event glue stays thin and is exercised only as far as the stub allows (mount renders expected markup + injects styles; `dispose()` clears). **Any richer-DOM test snippet shown in a task step below (e.g. `.click()`, dispatching an event, `querySelector`) is ILLUSTRATIVE INTENT ONLY — implement the equivalent as a pure-function test.** Do NOT add jsdom or any dependency.
- **Backend error contract (surface via `Banner`).** The `/api/settings/*` endpoints (shipped in PR #501) return typed error codes; map each to a user-facing message: `invalid_field` (bad email/channel), `slack_invalid_auth` (Slack rejected the token), `slack_not_configured` (no Slack token set), `slack_send_failed` (Slack post failed), `encryption_unavailable` (server has no encryption key configured). Implement this mapping as a pure function (e.g. `web/account/settings-errors.js`) and unit-test it.

---

### Task 1: `Modal` design-system primitive

**Files:**
- Create: `web/design-system/modal.js`, `web/design-system/modal-template.js`, `web/design-system/modal.css`
- Modify: `web/design-system/roadtrip-design-system.html`, `web/design-system/README.md`
- Test: `web/design-system/modal.test.mjs`

**Interfaces:**
- Produces `mountModal(container, config) → { close(), setBody(el), dispose() }` where `config = { title?, sheetOnMobile?: boolean, onClose?: () => void, closeOnBackdrop?: boolean }`. Emits close on Escape, backdrop click (if enabled), and the header ✕. Traps focus while open.

- [ ] **Step 1: Write the failing test** (pure template + stub-mount — NO jsdom; see the Testing constraint)

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import { modalTemplate } from './modal-template.js';

test('modalTemplate renders title, close affordance, scrim, and a body host', () => {
  const html = modalTemplate({ title: 'Sign in', sheetOnMobile: true });
  assert.match(html, /data-modal-close/);   // header ✕
  assert.match(html, /data-modal-body/);     // host for setBody()
  assert.match(html, /Sign in/);
});

test('modalTemplate escapes the title', () => {
  assert.match(modalTemplate({ title: '<script>x</script>' }), /&lt;script&gt;/);
});
```

Then a mount test following `web/watch-editor.test.mjs`: assign a minimal `globalThis.document` stub + a fake `host`, call `mountModal(host, { title: 'X' })`, assert `host.innerHTML` contains the modal markup and injected style, and that `dispose()` clears listeners/DOM. The Escape / backdrop-click close paths are thin DOM-event glue verified at integration (the stub can't dispatch events) — do not unit-test them here.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/design-system/modal.test.mjs`
Expected: FAIL — `modalTemplate` (and `mountModal`) not defined.

- [ ] **Step 3: Write the template** (`modal-template.js`) — pure function returning scrim + centered card (or bottom sheet) with a header (`title` + ✕ button `data-modal-close`) and a `[data-modal-body]` host. Use `escapeHtml` on `title`.

- [ ] **Step 4: Implement `modal.js`** — inject `modal.css` via `<link>`; render template; delegate click for `[data-modal-close]` and (optional) backdrop; `keydown` Escape on `document`; simple focus trap (focus first focusable on open, restore on dispose). `setBody(el)` appends into `[data-modal-body]`. `close()` calls `onClose`. `dispose()` removes listeners + DOM.

- [ ] **Step 5: Write `modal.css`** — scrim (`rgba` over map) + card using `--rt-surface`, `--rt-border-strong`, `--rt-r-xl`, `--rt-e4`; `@media (max-width:560px)` bottom-sheet variant with grab handle when `sheetOnMobile`.

- [ ] **Step 6: Update the design system** — add a "Modal" entry to `roadtrip-design-system.html` (a live example) and note the overlay/sheet convention in `README.md`.

- [ ] **Step 7: Run test to verify it passes**

Run: `node --test web/design-system/modal.test.mjs`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add web/design-system/modal.js web/design-system/modal-template.js web/design-system/modal.css \
        web/design-system/modal.test.mjs web/design-system/roadtrip-design-system.html web/design-system/README.md
git commit -m "feat(ds): Modal primitive (+gallery/README)"
```

---

### Task 2: `Tabs` design-system primitive

**Files:**
- Create: `web/design-system/tabs.js`, `web/design-system/tabs-template.js`, `web/design-system/tabs.css`
- Modify: `web/design-system/roadtrip-design-system.html`, `web/design-system/README.md`
- Test: `web/design-system/tabs.test.mjs`

**Interfaces:**
- Produces `mountTabs(container, config) → { getActive(), setActive(id), dispose() }` where `config = { tabs: [{ id, label }], active, onChange(id) }`. Renders a rail (vertical desktop / segmented top on mobile) of `[data-tab=<id>]` buttons; clicking one calls `onChange` and sets the active style.

- [ ] **Step 1: Write the failing test** — mount with two tabs, click the second, assert `onChange` fired with its id and `getActive()` updated.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/design-system/tabs.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement template + controller + css** — delegation on `[data-tab]`; active class uses `--rt-brand-tint`/`--rt-text`; `README` convention: tab rail collapses to a top segmented control ≤560px.

- [ ] **Step 4: Update gallery + README.**

- [ ] **Step 5: Run test to verify it passes**

Run: `node --test web/design-system/tabs.test.mjs`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/design-system/tabs.* web/design-system/roadtrip-design-system.html web/design-system/README.md
git commit -m "feat(ds): Tabs primitive (+gallery/README)"
```

---

### Task 3: `SecretField` design-system primitive

**Files:**
- Create: `web/design-system/secret-field.js`, `web/design-system/secret-field-template.js`, `web/design-system/secret-field.css`
- Modify: `web/design-system/roadtrip-design-system.html`, `web/design-system/README.md`
- Test: `web/design-system/secret-field.test.mjs`

**Interfaces:**
- Produces `mountSecretField(container, config) → { getValue(), getMode(), reset(), dispose() }` where `config = { label, hint?: string|null, help? }`.
  - Modes: `stored` (a `hint` exists) shows `••••<hint>` + a **Replace** button (`data-action="replace"`); clicking it → `replacing` mode: an empty input + **Cancel** (`data-action="cancel"`).
  - When no `hint`, starts in `replacing` (empty input, no cancel).
  - `getValue()` returns the entered string in `replacing` mode, or `null` in `stored` mode (meaning "unchanged"). This maps directly to the backend's "null = leave unchanged" contract.

- [ ] **Step 1: Write the failing test**

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import { mountSecretField } from './secret-field.js';
// (DOM setup per the repo's existing test pattern.)

test('stored mode returns null until Replace is clicked', () => {
  const host = document.createElement('div');
  const f = mountSecretField(host, { label: 'Slack bot token', hint: '3f9a' });
  assert.equal(f.getMode(), 'stored');
  assert.equal(f.getValue(), null);
  host.querySelector('[data-action="replace"]').click();
  assert.equal(f.getMode(), 'replacing');
  host.querySelector('input').value = 'xoxb-new';
  host.querySelector('input').dispatchEvent(new Event('input', { bubbles: true }));
  assert.equal(f.getValue(), 'xoxb-new');
  f.dispose();
});

test('no hint starts in replacing mode', () => {
  const host = document.createElement('div');
  const f = mountSecretField(host, { label: 'Slack bot token', hint: null });
  assert.equal(f.getMode(), 'replacing');
  f.dispose();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/design-system/secret-field.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement template + controller + css** — template renders either the masked row or the input row from a `mode` arg (pure; `escapeHtml` the hint). Controller holds `{ mode, value }`, re-renders on Replace/Cancel via delegated clicks, tracks input. 16px input font; monospace masked value.

- [ ] **Step 4: Update gallery + README** — document the write-only-secret pattern (masked→replace, `getValue()===null` means unchanged).

- [ ] **Step 5: Run test to verify it passes**

Run: `node --test web/design-system/secret-field.test.mjs`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/design-system/secret-field.* web/design-system/roadtrip-design-system.html web/design-system/README.md
git commit -m "feat(ds): SecretField primitive (+gallery/README)"
```

---

### Task 4: `account-api.js` client

**Files:**
- Create: `web/api/account-api.js`
- Test: `web/api/account-api.test.mjs`

**Interfaces:**
- Consumes `web/api/http.js` (`jsonGetOk`, and a JSON PUT/POST/DELETE helper — check `http.js` for the exact exported names and reuse them; do not hand-roll fetch).
- Produces:
  - `fetchSettings()` → GET `/api/settings`
  - `updateProfile({ display_name })` → PUT `/api/settings/profile`
  - `updateNotifications({ notification_email, slack_channel, slack_token })` → PUT `/api/settings/notifications` (omit `slack_token` when unchanged)
  - `clearSlack()` → DELETE `/api/settings/notifications/slack`
  - `sendSlackTest(channel)` → POST `/api/settings/notifications/slack/test`

- [ ] **Step 1: Write the failing test** — stub the http helpers, assert each function calls the right method+path and forwards the body; assert `updateNotifications` drops a `null` `slack_token` key.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/api/account-api.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement `account-api.js`** — thin wrappers over the `http.js` helpers, mirroring `web/api/auth-api.js` style (module-level `const` URLs, JSDoc). Strip `null`/`undefined` keys before sending.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/api/account-api.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/api/account-api.js web/api/account-api.test.mjs
git commit -m "feat(account): settings API client"
```

---

### Task 5: `LoginCard`

**Files:**
- Create: `web/account/login-card.js`, `web/account/login-card-template.js`, `web/account/login-card.css`
- Test: `web/account/login-card.test.mjs`

**Interfaces:**
- Consumes `mountModal` (Task 1), `signIn` from `web/api/auth-api.js`, `fetchMe`.
- Produces `mountLoginCard(config?) → { dispose() }` — creates its own mount host, opens a `Modal` (`sheetOnMobile: true`) containing the brand mark, title, rationale line, and a single primary button "Continue with `<provider_label>`" (fallback label "single sign-on" when `provider_label` is absent). Clicking the button calls `signIn(config?.returnTo)`.

- [ ] **Step 1: Write the failing test** — mount, assert one primary button exists, click it, assert the injected `signIn` stub was called. Assert the label uses `provider_label` when present.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/account/login-card.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement** template (pure, `escapeHtml` label) + controller (compose Modal, delegate the button click to `signIn`) + css (tokens only).

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/account/login-card.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/account/login-card.* web/account/login-card.test.mjs
git commit -m "feat(account): LoginCard"
```

---

### Task 6: Settings panels (Profile, Notifications, Account)

**Files:**
- Create: `web/account/profile-panel.js`, `web/account/notifications-panel.js`, `web/account/account-panel.js` (+ their `*-template.js` / `*.css` as needed)
- Test: `web/account/profile-panel.test.mjs`, `web/account/notifications-panel.test.mjs`, `web/account/account-panel.test.mjs`

**Interfaces:**
- Each panel: `mount<Name>Panel(container, { settings, onDirtyChange(bool) }) → { getPayload(), isDirty(), dispose() }`.
  - `ProfilePanel`: `FormSection` for display name (editable), read-only login email + verified badge. `getPayload()` → `{ display_name }`.
  - `NotificationsPanel`: `FormSection` for notification email; `SecretField` for the Slack token (`hint` from `settings.notifications.slack_token_hint`); `FormSection` for Slack channel; a "Send a test message" button that calls the injected `onTest(channel)` and renders the result via `Banner`. `getPayload()` → `{ notification_email, slack_channel, slack_token }` where `slack_token` is `SecretField.getValue()` (null when unchanged).
  - `AccountPanel`: "signed in as", a `DoubleConfirmButton` **Sign out** (calls injected `onSignOut`), and a danger-zone `DoubleConfirmButton` **Disconnect Slack** (calls injected `onDisconnectSlack`).

- [ ] **Step 1: Write the failing tests** — per panel: dirty flips true on edit; `getPayload()` shape correct; NotificationsPanel `slack_token` is `null` until Replace; AccountPanel Sign out / Disconnect call their injected callbacks only after the double-confirm.

- [ ] **Step 2: Run tests to verify they fail**

Run: `node --test web/account/profile-panel.test.mjs web/account/notifications-panel.test.mjs web/account/account-panel.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement the three panels** — compose the DS primitives; track dirty by comparing current field values to the initial `settings`; call `onDirtyChange`. Templates pure; `escapeHtml` all user text.

- [ ] **Step 4: Run tests to verify they pass**

Run: `node --test web/account/profile-panel.test.mjs web/account/notifications-panel.test.mjs web/account/account-panel.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/account/profile-panel.* web/account/notifications-panel.* web/account/account-panel.* \
        web/account/*-panel.test.mjs
git commit -m "feat(account): settings panels"
```

---

### Task 7: `SettingsModal`

**Files:**
- Create: `web/account/settings-modal.js`, `web/account/settings-modal-template.js`, `web/account/settings-modal.css`
- Test: `web/account/settings-modal.test.mjs`

**Interfaces:**
- Consumes `mountModal`, `mountTabs`, the three panels (Task 6), and `account-api.js` (Task 4).
- Produces `mountSettingsModal(config?) → { dispose() }` — creates a host, opens a `Modal` (`sheetOnMobile: true`) whose body is a `Tabs` rail (Profile/Notifications/Account) + the active panel. Loads data via `fetchSettings()` on open. Save button in the modal footer is **disabled until the active tab is dirty**; on Save it calls the matching api function with the active panel's `getPayload()`, shows a `Banner` on success/error, and re-reads settings. Sign out → `signOut()`; Disconnect Slack → `clearSlack()` then reload. Blocks Save while a Slack token is being validated (server does the validation; surface its error Banner).

- [ ] **Step 1: Write the failing test** — inject stubbed api; assert: Save disabled initially; editing display name enables Save; clicking Save calls `updateProfile` with `{ display_name }`; a rejected notifications save surfaces an error Banner; switching tabs re-scopes Save's dirty state.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/account/settings-modal.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement** — orchestrate load → mount Tabs + panel → wire dirty/save; dispose child panel on tab switch before mounting the next (per the dispose-before-render rule).

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/account/settings-modal.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/account/settings-modal.* web/account/settings-modal.test.mjs
git commit -m "feat(account): SettingsModal (tabbed, per-tab save)"
```

---

### Task 8: Top-bar integration

**Files:**
- Modify: `web/topbar/auth.js`
- Modify: `index.html` (ensure a mount host exists; `#tb-auth` already does)
- Test: `web/topbar/auth.test.mjs` (create if absent; see `web/topbar-alerts.test.mjs` for the pattern)

**Interfaces:**
- Consumes `mountLoginCard` (Task 5), `mountSettingsModal` (Task 7).
- Changes: `signedOutHtml()`'s "Sign in" button opens `mountLoginCard()` instead of navigating directly (keep `signIn()` as the button inside the card). `signedInHtml(user)`'s name becomes a button (`data-auth-action="open-settings"`) that opens `mountSettingsModal()`; keep an explicit "Sign out" affordance too (it also lives in the Account tab). Preserve: render nothing when `auth_enabled === false`.

- [ ] **Step 1: Write the failing test** — with `fetchMe` stubbed authenticated, assert clicking the name mounts the settings modal (spy the mount fn); with anonymous, assert clicking "Sign in" mounts the login card; with `auth_enabled:false`, assert the row is empty/hidden.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/topbar/auth.test.mjs`
Expected: FAIL.

- [ ] **Step 3: Implement** — add the two `data-auth-action` handlers in `onClick`; import the mount functions. Keep the existing style-injection and null-safety.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/topbar/auth.test.mjs`
Expected: PASS.

- [ ] **Step 5: Run the whole frontend test suite**

Run: `node --test web/**/*.test.mjs`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/topbar/auth.js web/topbar/auth.test.mjs index.html
git commit -m "feat(account): open LoginCard / SettingsModal from the top bar"
```

---

## Frontend self-review notes

- Spec coverage: login card A→sheet (T5, T1's `sheetOnMobile`); tabbed settings C (T7); Profile/Notifications/Account content (T6); write-only token via `SecretField` (T3, asserted null-until-replace in T3 & T6); validate-on-save surfaced as Banner (T6/T7 error path); test-message wiring (T6 → `sendSlackTest` T4); disconnect (T6/T7 → `clearSlack`); DS follow-and-update baked into T1–T3 (gallery/README steps). ✅
- Placeholder scan: DOM-driver choice defers to "match the existing `*.test.mjs` pattern" rather than inventing one — implementers verify against a real file in Step 1 of T1. No TODO/TBD steps. ✅
- Type consistency: `getValue()===null` (SecretField, T3) ⇄ `slack_token` null = "unchanged" (account-api T4, NotificationsPanel T6, backend `UpdateNotificationsRequest`). `provider_label` label fallback (T5) matches the backend `ProfileDto.provider_label` / `/api/me` field. Panel `getPayload()` shapes match `account-api.js` params. ✅

## Cross-plan dependency

Track A's `SettingsModal` (T7) and `account-api.js` (T4) require the backend plan's `/api/settings/*` endpoints. `LoginCard` (T5) and the top-bar login trigger (T8) depend only on existing `/auth/*` + `/api/me` and can land first. Recommended order: backend plan → frontend T1–T3 (primitives, no backend needed) → T4–T8.
