# Account: Login + User Settings — Design

**Date:** 2026-07-26
**Status:** Design approved; frontend spec ready, backend proposed as a follow-up.
**Context:** RFC 0009 landed the auth provider layer; RFC 0010 PR 1 landed route
access declarations and `Principal` resolution. `/api/me` already reports the
signed-in user. This design adds the two user-facing surfaces on top of that:
a **login overlay** and a **settings panel**.

## Scope

This session designs the UI and specs the work. It splits into two shippable
tracks:

- **Track A — Frontend (this design's primary deliverable).** Login card and
  settings modal built from the design system, wired to `/api/me` and to
  backend endpoints defined below. Where the backend does not exist yet, the
  frontend is specced against the endpoint contract so it can land in lockstep.
- **Track B — Backend (proposed here, built as a follow-up).** Per-user
  notification settings: persistence, encrypted secret storage, read/write
  endpoints, Slack token validation, per-user test messages, and wiring alerts
  to each user's own Slack destination.

**Non-goals:** account deletion, multi-provider account linking UI, email
verification flows, notification-channel toggles beyond the fields below,
organization/team settings.

## User stories

- As a signed-out visitor, I can sign in from an overlay without leaving the map.
- As a signed-in user, I can open Settings, edit my display name, and see my
  verified login email.
- As a signed-in user, I can set a notification email, a Slack bot token, and a
  Slack channel, and send a test Slack message to confirm they work.
- As a signed-in user, I can sign out, and I can disconnect Slack.

---

## Track A — Frontend design

### Surfaces and triggers

Both surfaces are **in-app overlays** — no page router is introduced. They live
in a new domain area `web/account/` (peer to `web/watches/`, `web/topbar/`),
composing `web/design-system/` primitives per `docs/frontend-components.md`.

| Surface | Trigger |
|---|---|
| Login card | Anonymous user clicks "Sign in" in the top bar, **or** attempts a gated action (e.g. opening the watch editor once watches become `User`-gated in RFC 0010 PR 2). |
| Settings modal | Signed-in user clicks their name/avatar in the top bar. |

### Login card (chosen treatment: centered modal → mobile bottom sheet)

- Centered modal card over a scrim-blurred map. Brand mark, title
  ("Sign in to Roadtrip"), one-line rationale, a single **Continue with
  `<provider>`** button, and a reassurance line.
- The button performs the **existing full-page navigation** to `/auth/login`
  (`signIn()` in `web/api/auth-api.js`) — no change to the auth flow itself.
- `return_to` is the current path so the user lands back where they were.
- **Mobile (≤ ~560px):** the card docks to the bottom as a full-width sheet
  with a grab handle; button spans the width for thumb reach.
- Provider label comes from `/api/me` (extend `MeResponseDto` with a
  `provider_label`, or a small `/api/auth/config` read) so the button is not
  hardcoded to one vendor.

### Settings modal (chosen treatment: tabbed modal → mobile full-height sheet)

A modal with a left tab rail: **Profile · Notifications · Account**. Save is
**per-tab**, disabled until the tab is dirty. On mobile the rail collapses to a
top segmented control and the modal becomes a full-height sheet.

**Profile tab**
- Avatar/brand mark, name, "member since" + role pill (read-only).
- **Display name** — editable text field. Persists to `app_user.display_name`.
- **Login email** — read-only, with a "✓ verified" badge when
  `is_email_verified`. Sourced from the identity provider.

**Notifications tab**
- **Notification email** — editable; placeholder/default is the login email.
- **Slack bot token** — **write-only secret** (see below).
- **Slack channel** — editable text (e.g. `#campsite-alerts`), with a
  **Send a test message** link.

**Account tab**
- "Signed in as `<email>`" (read-only).
- **Sign out** — navigates to `/auth/logout` (`signOut()`).
- **Danger zone → Disconnect Slack** — clears the stored token + channel.
- Destructive actions use `DoubleConfirmButton`.

### The Slack bot token — write-only secret field

The browser can **set or clear** the token but can **never read it back**. The
API returns only a masked hint (last 4 chars) and a boolean "configured" flag.

| State | UI |
|---|---|
| 1 · Stored (masked) | `••••••••3f9a` + **Replace** link. Default when a token exists. |
| 2 · Replacing | Empty input revealed (+ **Cancel**). On Save, backend validates the token against Slack and echoes the resolved bot identity via a `Banner` (success). |
| 3 · Invalid | Save blocked; `Banner` (error) shows the reason (e.g. `invalid_auth`). |

Rationale: a missed `escapeHtml` or a DB leak must not exfiltrate a live Slack
token. Same principle as the HttpOnly session cookie.

### Validation and save

- **Save** validates the dirty tab, calls the settings write endpoint, and on
  success re-reads settings to refresh masked hints.
- Field errors and Slack validation results render as `Banner` (the design
  system's success/error/info message primitive).
- Notification email is format-validated client-side and server-side.
- Slack channel is length-bounded (matches the existing test endpoint's
  `MAX_TEST_SLACK_CHANNEL_CHARS = 255`).

### Send a test message

The **Send a test message** link maps to the existing `POST /test/slack`
contract: request `{ "channel": "#…" }`, response `{ sent, channel }` or a typed
error (`slack_send_failed`, `invalid_channel`, `invalid_body`).

> **Contract note / Track B dependency.** The endpoint that exists *today* sends
> using the **global** configured bot token and lives under `/test/*`, which
> RFC 0010 PR 2 gates to **admin**. To let a normal signed-in user test **their
> own** token+channel, Track B adds a user-accessible endpoint
> (`POST /api/settings/notifications/slack/test`, access `User`) that resolves
> the caller's stored Slack destination. The frontend is built against that
> user-scoped contract; the shape mirrors `/test/slack`.

### Frontend component inventory

Per `docs/frontend-components.md`: each component is up to three files
(`*.js` controller, `*-template.js` pure HTML, `*.css` with `--rt-*` tokens),
mounted via `mountComponent(container, config) → { dispose(), ... }`, styles
injected by `<link>`, templates use `escapeHtml`.

**Design-system adherence (follow, then update).** Reuse `web/design-system/`
primitives and `tokens.css` before writing anything new — no ad-hoc colors, no
inline hex, `--rt-*` tokens only. Honour the README conventions: blue
(`--rt-brand`) is the *only* interactive color, one primary (solid blue) per
surface, 4px spacing grid, ≥44px touch targets, 16px input font (prevents iOS
zoom), `tabular-nums` on any count/date. **When a genuinely new primitive is
added (see below), it is not done until the design system is updated to match:**
add the three-file component under `web/design-system/`, register it in the
visual reference gallery `web/design-system/roadtrip-design-system.html`, and
add any new convention to `README.md`. The gallery must stay a true catalog of
what exists.

**New design-system primitives (only if a gap exists):**
- `Modal` / overlay shell (scrim + focus trap + escape/close) — check for an
  existing drawer/overlay primitive first; add to `web/design-system/` only if
  none is reusable. Login and Settings both consume it.
- `Tabs` (tab rail + panel switch) — same "check first, then add" rule.
- `SecretField` — masked value + Replace/Cancel + set/clear semantics. New,
  because the write-only-secret pattern is reusable and non-trivial.

**New domain components (`web/account/`):**
- `LoginCard` — composes the modal shell + provider button; calls `signIn()`.
- `SettingsModal` — modal + tab shell; owns dirty state and per-tab save.
- `ProfilePanel`, `NotificationsPanel`, `AccountPanel` — each composes
  `FormSection`, `Banner`, `SecretField`, `DoubleConfirmButton`.

**API client additions (`web/api/`):**
- Extend `auth-api.js` or add `account-api.js`:
  `fetchSettings()`, `updateProfile(...)`, `updateNotifications(...)`,
  `clearSlack()`, `sendSlackTest(channel)`. All via `web/api/http.js`.

**Top bar integration (`web/topbar/auth.js`):**
- `signedInHtml(user)` becomes a button that opens `SettingsModal` (mount into
  a host element) instead of only rendering a name + Sign out link.
- Keep the "render nothing when `auth_enabled === false`" behavior.

---

## Track B — Backend proposal (follow-up)

Layered per `docs/backend-architecture.md`: DTOs in `models/api`, SQL in a repo,
orchestration + policy in a service, HTTP shell in routes. Routes declare
`.access(...)`.

### Data model — new migration (next version, V48 at time of writing)

New table, one row per user, created lazily on first write. Named generically
(`user_settings`, not `user_notification_settings`) so future non-notification
preferences can share it without another migration:

```sql
CREATE TABLE user_settings (
  user_id            BIGINT      PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  notification_email TEXT,                 -- NULL = fall back to app_user.email
  slack_channel      TEXT,                 -- NULL = Slack channel unset
  slack_token_cipher BYTEA,                -- AES-GCM ciphertext; NULL = no token
  slack_token_hint   TEXT,                 -- last 4 chars, safe to return to the browser
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`display_name` stays on `app_user` (Profile tab writes there). These settings
are a separate table from identity because they are optional, secret-bearing,
and not part of who the user *is*.

### Secret handling — encryption at rest

No symmetric-encryption utility exists yet (`LoginFlowState` only HMAC-signs;
`AuthConfig` resolves secrets from env placeholders). Add a small
**`SecretCipher`** (AES-256-GCM) keyed from an env-provided master key, wired
through config like the login-flow signing key. It exposes
`seal(plaintext) → bytes` and `open(bytes) → plaintext`. The **service** seals
before persist and the repo stores only ciphertext bytes; plaintext exists only
transiently during validation and sealing. The browser never receives the
token — only `slack_token_hint`.

### Models (`models/api`, `@Serializable` DTOs)

- `SettingsResponseDto` — `{ profile: { display_name, login_email, is_email_verified, roles, provider_label }, notifications: { notification_email, slack_channel, slack_configured: Boolean, slack_token_hint: String? } }`.
- `UpdateProfileRequest` — `{ display_name }`.
- `UpdateNotificationsRequest` — `{ notification_email?, slack_channel?, slack_token?: String? }` where `slack_token` present-and-nonblank = set/replace, present-and-null = leave unchanged, explicit clear via the dedicated endpoint. (Exact "unchanged vs clear" semantics fixed in the plan; the write-only rule is the constraint.)
- `SlackTestResponseDto` — mirrors `/test/slack`'s `{ sent, channel }`.

Follow existing conventions: DTOs are typed, no hand-built JSON in routes.

### Repo (`repo/`)

`UserSettingsRepo` owns the new table's full surface:
`find(userId)`, `upsertNotifications(...)`, `setSlackToken(userId, cipher, hint)`,
`clearSlack(userId)`. Display-name writes stay in `UserRepo` (identity's repo
owns `app_user`). SQL/jOOQ lives here only.

### Service (`service/`)

`UserSettingsService` orchestrates:
- Read: assemble `SettingsResponseDto` from `UserRepo` + settings repo + the
  resolved principal's roles + provider label. Never returns the token.
- Write profile: validate + delegate display-name write to `UserRepo`.
- Write notifications: validate email/channel; if a new token is present,
  **validate it against Slack** (`auth.test`) via a Slack client call, then
  `seal` and persist with a fresh hint.
- Test: resolve the caller's stored token + channel and send a test message.

Slack token validation and per-user test sending reuse the notification/Slack
client but must accept a **caller-supplied token** rather than only the global
config token. Today `SlackNotificationService` is bound to `SlackConfig`; extend
the Slack client boundary so a per-request token/channel can be passed without
leaking vendor types upward (keep the port provider-neutral).

### Routes (`routes/`) and access levels

All under `/api/settings`, all `.access(RouteAccess.User)`:

| Method + path | Purpose |
|---|---|
| `GET /api/settings` | Read the caller's settings (never the token). |
| `PUT /api/settings/profile` | Update display name. |
| `PUT /api/settings/notifications` | Update notification email / channel / set-or-replace token. |
| `DELETE /api/settings/notifications/slack` | Disconnect Slack (clear token + hint). |
| `POST /api/settings/notifications/slack/test` | Send a test message using the caller's own token+channel. |

Routes parse input, call `UserSettingsService`, map known errors
(`invalid_email`, `invalid_channel`, `slack_invalid_auth`, `slack_send_failed`)
to status codes, and return DTOs. The caller's `UserId` comes from the ambient
`Principal.User`; a route never trusts a user id from the body.

The existing admin `/test/slack` endpoint stays as the operator smoke test of
the **global** config; the new user-scoped test endpoint is separate.

### Wiring alerts to per-user destinations

Once settings exist, `WatchAlertDispatcher` / the Slack notification path should
resolve the **owning user's** notification settings (token + channel, falling
back to global config when unset) rather than only the global default. This is
the payoff of the feature and should be called out in the Track B plan as its
final step, with its own tests. It depends on watches carrying an owner
(`Principal.User`) — landing alongside RFC 0010 PR 2's `User`-gating of watches.

---

## Testing approach

**Frontend:** component tests in the existing `*.test.mjs` style — masked→replace
token transitions, dirty-state gating of Save, `auth_enabled === false` hides
the surfaces, mobile sheet rendering. Template purity (no DOM access) enforced
by the existing pattern.

**Backend:** repo round-trip (seal/store/read hint, clear); service validation
(email/channel rejection, Slack `invalid_auth` blocks save, token never in any
response DTO); route access (401 anonymous, 403 wrong principal, 200 owner);
`SecretCipher` seal/open round-trip and tamper rejection. The RFC 0010 coverage
guard already forces every new route to declare `.access(...)`.

## Open questions (resolve in the implementation plan)

1. Is there an existing overlay/drawer primitive to reuse for the modal shell,
   or is a new `Modal` primitive warranted?
2. Exact "leave unchanged vs clear" wire semantics for `slack_token` on the
   notifications PUT (vs the dedicated DELETE).
3. Whether `provider_label` rides on `/api/me` or a small separate read.
4. Master-key management for `SecretCipher` (env var name, rotation posture).
