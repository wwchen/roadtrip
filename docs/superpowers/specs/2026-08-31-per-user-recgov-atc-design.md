# Per-user rec.gov credentials and companion-backed ATC

**Date:** 2026-08-31
**Status:** Approved design, ready for implementation planning
**Depends on:** RFC 0009 (auth provider layer), `docs/reservation-providers.md`
(trigger registry + booking provider seam)

## Background

The `atc` trigger action is shipped end to end — `TriggerKind.ATC`,
`AtcTriggerActionHandler`, the `BookingAdapter` seam, `RecGovBookingAdapter`,
and `HttpRecGovAtcExecutor` calling the companion's `POST /atc` — but it is
single-user. The companion (`companion/`) runs one persistent Chromium
profile: the operator logs in once (manually in the headed browser, or via
`POST /login` where credentials are held in memory for that attempt only) and
the profile directory *is* the durable credential. Credentials were never
provisioned through `secrets/`; the baked-in thing is the session, not the
password. Every ATC therefore lands in the one operator's rec.gov cart,
regardless of which user's watch fired.

Now that the site is multiuser (RFC 0009/0010: `app_user`, sessions,
watch ownership via `availability_watch.owner_user_id`), that model no longer
works. RFC 0009 explicitly deferred this: "multiuser there means a
browser-profile pool."

Two latent defects surfaced while reviewing the current state, both fixed by
this design:

- **`SecretCipher` is never constructed.** It reads
  `roadtrip.security.encryption-key`, but no config file defines a `security:`
  section and `ENCRYPTION_KEY` is not in `secrets/registry.yaml`. Storing any
  per-user secret (today: the Slack bot token) returns 503
  `encryption_unavailable` in every environment.
- **ATC results are delivered to no one.** `AtcTriggerActionHandler` reports
  outcomes via `WatchNotificationTargetResolver.resolveSlackTarget`, which is
  deliberately fail-closed on the owner's *personal* decrypted Slack token —
  never the global bot. With the cipher always null, the target is always
  null, so ATC succeeds or fails silently.

## Goals

1. A signed-in user can save their rec.gov credentials in Settings.
2. From Settings, the user can test login (including MFA) and verify their
   session end to end without placing a real cart hold.
3. When a watch with the `atc` trigger fires, the hold lands in the **watch
   owner's** rec.gov cart, and the owner is told the outcome (success or
   failure) even when they have no Slack configured.
4. The companion serves N users via a browser-profile pool while remaining
   unreachable from the public internet.

## Non-goals

- Checkout or payment automation. The action stops at a cart hold, as today.
- TOTP secret storage or any unattended MFA solve.
- Per-user credentials for other providers (Aspira, ReserveAmerica, …). The
  booking seam already isolates this; nothing here leaks rec.gov shape.
- Hosted alert providers, watch cadence overrides, or other RFC 0007 leftovers.
- Removing the stale plaintext root `.env` (separate cleanup task).

## Design

### 1. Prerequisite: provision the encryption key

Add `ENCRYPTION_KEY` to `secrets/registry.yaml` (consumer: backend; required in
prod; local degrades gracefully per the registry's contract — the
`MAPBOX_TOKEN` pattern), thread it through the sops env files and compose, and add a
`security:` section to `application.yaml`
(`encryption-key: ${ENCRYPTION_KEY:}`). This is its own PR: it un-breaks the
existing per-user Slack token storage independent of everything below.
`SecretRegistryDriftTest` covers registry/compose drift.

### 2. Data model

One migration (next V-number), following the V48 Slack pattern:

```sql
ALTER TABLE user_settings
  ADD COLUMN recgov_username        TEXT,
  ADD COLUMN recgov_password_cipher BYTEA,
  ADD COLUMN recgov_password_hint   TEXT;
```

- Username is stored plaintext deliberately: it is an email-shaped identifier,
  needed for display and re-login, and not a secret in the way the password is.
- The password is sealed with `SecretCipher` (AES-256-GCM); the hint is the
  last 4 characters for the masked `SecretField` display.
- No session-status column. "Configured" is derived from the columns; live
  session health is always asked of the companion, never persisted.

### 3. Companion: browser-profile pool

- `profile_id` (the roadtrip user id, treated as an opaque string) becomes a
  **required** parameter on `POST /login`, `POST /logout`, `POST /refresh`,
  `POST /atc`, `GET /screenshot`, and the new `POST /verify`. There are no
  existing `atc` watches, so no legacy fallback path is built: requests
  without `profile_id` are rejected.
- Each profile id maps to its own persistent Chromium user-data directory
  under the existing browser-session volume. Playwright persistent contexts
  are one browser process per profile directory, so resident profiles are a
  real memory cost — governed by a global concurrent-browser cap (env-driven,
  named-constant default).
- **Keep-warm:** profiles backing at least one active `atc` watch stay
  launched, and a backend keepalive job periodically calls `POST /refresh` for
  exactly those profiles, so a 3 a.m. firing never pays Chromium cold-start or
  re-login inside the seconds-critical window. Credential-only profiles with
  no armed watch are torn down when idle. **Armed profiles are exempt from
  the concurrency cap** — the cap governs on-demand launches (logins,
  verifies, cold ATCs); when armed profiles alone exceed it, the companion
  logs and health reports the overflow rather than evicting an armed profile.
- **Per-profile busy lock:** no two mutating operations run concurrently on
  one profile. A pending MFA challenge holds the lock until completed or
  expired. **Health/status reads are lock-free** — the settings status row
  must answer while a login is mid-flight, not hang behind it.
- **Two-phase MFA:** `POST /login` without a code returns `mfa_required` plus
  a challenge id (the pending browser page is held open); a second call with
  the code completes it. The challenge TTL is **minutes-scale** (named
  constant, ~5 min): rec.gov delivers codes by email/SMS and the user needs
  time to fetch one.
- **`POST /verify` (dry run):** confirm the profile's session by loading a
  logged-in-only page (the rec.gov account page) and reading
  `GET /api/cart/shoppingcart` from page context — exercising session,
  fingerprint cookie, and Akamai without needing a campsite target, and
  **never clicking Reserve**. No cart hold is ever placed by a test.
- **Failed-login backoff:** one in-memory marker per profile suppresses rapid
  repeated credential logins (fire-time retries are edge-triggered but a
  backoff guards against rec.gov lockouts).
- `GET /health` reports per-profile `recgov_auth` when passed `profile_id`,
  and stays reachable without the auth token from localhost only (the compose
  healthcheck).

### 4. Backend ↔ companion channel

The channel today is plain HTTP with no auth and will now carry passwords.

- New deployment secret `COMPANION_API_TOKEN` in `secrets/registry.yaml`
  (consumers: backend, companion). Companion middleware rejects requests
  without the shared-secret header on **every** route — including `GET /`,
  `/docs`, `/openapi.json`, `/screenshot` — except the localhost healthcheck
  exemption above.
- **Exposure invariant:** the companion never gets a public vhost. It has no
  compose `ports:` mapping and no Caddy route today, and that stays true. All
  user interaction is proxied through backend routes, which is what makes
  per-user isolation enforceable — the backend authenticates the user and only
  ever passes *their* `profile_id`. Operator debugging keeps using SSH
  port-forward / `docker exec`.
- Transport stays HTTP on the internal Docker network; TLS is not added.

### 5. Backend: credential storage and settings routes

- `RecGovCredentialService` under `service/settings/` owns storage (via
  `UserSettingsRepo` + `SecretCipher`) and orchestration of companion calls.
  A `CompanionSessionClient` under `client/companion/` (beside
  `HttpRecGovAtcExecutor`) owns the HTTP shapes for login/MFA/logout/verify/
  health/refresh, sending the auth token.
- Routes, all `RouteAccess.User`, mirroring the Slack test-route pattern in
  `SettingsRoutes.kt`:
  - `PUT /api/settings/recgov` — username + write-only password
    (`SecretField` semantics: null = unchanged).
  - `DELETE /api/settings/recgov` — clears the columns and best-effort
    destroys the companion profile. **Local delete succeeds even when the
    companion is down**; profile destruction is retried opportunistically.
    The response (and the FE confirm copy) reports how many active `atc`
    watches the deletion strands — those watches keep the kind and fail with
    a notification on future fires; nothing mutates them behind the user's
    back. (Revisable: auto-pausing stranded watches is the alternative if
    fire-time failure spam proves annoying.)
  - `POST /api/settings/recgov/login` — begin login with stored credentials;
    may return `mfa_required` + challenge id, or `captcha_required` (see
    Known limitations).
  - `POST /api/settings/recgov/login/mfa` — complete with the user's code.
  - `POST /api/settings/recgov/verify` — the dry run.
  - `GET /api/settings/recgov/status` — configured flag, username, password
    hint, and live session state from companion health. **Deliberately a
    separate endpoint** so `GET /api/settings` never blocks on companion
    latency or availability; when the companion is unreachable the status
    degrades to `companion_unavailable` rather than erroring.
- DTO rule as today: the password never appears in any response.

### 6. ATC fire path

- `AtcTriggerActionHandler` already holds the watch (with `owner_user_id`).
  Thread the owner through `AddToCartRequest` → the `RecGovBookingAdapter`
  payload (`profile_id`) → `HttpRecGovAtcExecutor` → companion `POST /atc`.
- Preflight checks that profile's session health. If dead, the executor asks
  the companion to re-login once with the stored (decrypted) credentials.
  If MFA or CAPTCHA blocks the unattended re-login, the ATC fails and the
  failure notification says so ("session expired — re-login in Settings").
- **ATC results gain an email target** (core requirement, not a
  nice-to-have — see Background). `sendAtcResult` resolves email the same way
  watch openings do: `user_settings.notification_email` falling back to the
  owner's login email. The owner-scoped Slack card still fires when the owner
  has a personal token + channel.
- Two users racing for the same site is expected marketplace behavior: first
  ATC wins, the second reports "no longer available."

### 7. Capability gating

- `atc` appears in `watch_capabilities.trigger_kinds` only when the resolved
  scope supports `BookingAction.ADD_TO_CART` **and** the requesting user has
  rec.gov credentials configured. This threads the principal into
  `WatchCapabilityService` (today pure campsite-scope) via
  `CampsiteAvailabilityService` and its route. For anonymous/magic-link
  readers of the availability API, `atc` is simply absent — never an error.
- `WatchTriggerCapabilityValidator` enforces the same rule at write time
  (existing `unsupported_trigger` error), uniformly for all `atc` watches —
  there are no existing `atc` watches to grandfather.
- Gating is on *configured*, not *proven working*: wrong credentials surface
  at test time (Settings) or fire time (failure notification), matching the
  Slack precedent.
- The FE's "empty capability set is permissive" rollout rule in
  `capabilitiesForEditor` stays; the write-time validator is authoritative.

### 8. Frontend / UI design

All new UI composes existing `@ui` primitives and the `features/account/`
patterns; no new primitives and no new CSS files (extend `account.css`).

**New Settings tab: "Booking"** (`features/account/BookingPanel.tsx`), in the
rail between Notifications and Account. It has a savable slice (username +
password), so it participates in the modal's Save button like Profile and
Notifications do, following the established convention exactly:
`BookingValues { recgov_username: string; recgov_password: string | null }`
with `bookingValuesOf` / `isBookingDirty` / `buildBookingPayload` exports,
wired into `SettingsModal`'s per-tab dirty gating and save switch, and keyed
by `dataUpdatedAt` so a save remounts fields from the server's answer
(including the new password hint) — same as the Slack token today.

Panel contents, top to bottom:

- **Username** — `SeededTextField`, type email.
- **Password** — `SecretField` (write-only; `null` = unchanged; masked
  `••••` + `recgov_password_hint` last-4). Help text notes what the
  credential is used for and that ATC stops at a cart hold.
- **Session status row** — driven by a dedicated query on
  `GET /api/settings/recgov/status` (separate from `useSettings` so opening
  the modal never blocks on the companion). States: *Not configured* /
  *Session active* / *Session expired — test login below* / *Companion
  unavailable — status unknown*. Rendered in the `TestStatusText` idiom
  (icon + short text, `role="status"`).
- **Test login** — secondary `Button`, disabled while any booking action is
  pending (one shared in-flight guard + one status slot, as
  `NotificationsPanel` does for its two tests). Uses **saved** credentials, so
  it is disabled while the panel is dirty ("Save first" help text) — testing
  what the server has, not what's in the form, because login is a companion
  side effect, unlike the Slack test which can take the form value.
- **MFA code step** — when login returns `mfa_required`, an inline code field
  appears below the button (conditionally rendered, so it must be
  `SeededTextField` per the uncontrolled-forms rule) with Submit and Cancel.
  The login flow is a small state machine in a pure module beside the
  component (`booking-login.ts`: idle → logging_in → mfa_pending(challengeId)
  → submitting → ok | failed(code)), unit-tested directly — the
  `matrix-rows.ts` pattern.
- **Verify session** — secondary `Button` for the dry run; result lands in
  the same status slot ("Session verified" / mapped error).

**Credential removal** lives in `AccountPanel`, not the Booking panel,
because Account is the established home for destructive account actions
(Disconnect Slack). "Remove rec.gov credentials" uses the existing
`ConfirmButton` primitive and reports through the modal notice banner.

**Plumbing:** extend `api/account-api.ts` (settings endpoint group) with the
booking calls; new hooks in `useSettings.ts` (`useSaveBooking`,
`useRecgovLogin`, `useRecgovMfa`, `useRecgovVerify`, `useRecgovStatus`,
`useRemoveRecgov`); `settings-errors.ts` gains `mfa_required`,
`mfa_invalid`, `captcha_required`, `companion_unavailable`, `login_failed`.
Buttons disable while in flight, never fields (LDS DOM-swap rule). New
colors, if any, come from `--rt-*` semantic roles. A `BookingPanel` story
joins the Storybook catalog with one story per state (unconfigured,
configured+active, MFA step, captcha error, companion down).

**Watch surfaces:**

- `WatchEditor` (availability-grid popover + AlertsPanel) stays
  capability-driven with one addition: when `booking_actions` includes
  `add_to_cart` but `trigger_kinds` lacks `atc` — the "scope supports it,
  user has no credentials" case the new gating creates — the ATC toggle
  renders **disabled** with help "Add rec.gov credentials in Settings", for
  discoverability, instead of disappearing. For a viewer who is not signed
  in (magic-link/anonymous), the same state reads "Sign in to enable
  add-to-cart" instead. The existing "Unavailable for this watch scope"
  help remains for scopes with no booking support. This splits
  `supportsAddToCart` in `lib/watch-windows.ts` (today an AND over both
  fields) into the two states the editor now distinguishes.
- The `/watches` page form (`TriggerSelector`) still does not offer ATC in
  v1 — it lacks per-watch capability context today. Parity is a follow-up,
  noted here so it is a decision rather than an accident.

### 9. Security and product risk

- **Threat model:** a rec.gov account holds saved payment methods, so
  DB-plus-key compromise means a bookable account takeover. Mitigations:
  AES-256-GCM at rest, key lives only in sops-managed deployment secrets
  (separate custody from the DB), password never in any API response or log,
  companion unreachable publicly, per-user profile isolation, and the
  fail-closed notification resolver unchanged.
- **ToS acknowledgment:** automating rec.gov cart holds sits in a gray area of
  recreation.gov's terms; this is an accepted product risk, noted here so the
  decision is on record.
- **Shared egress IP:** all users' sessions exit from the companion host's
  IP. Many ATCs across different accounts from one IP may look bot-like to
  Akamai. Accepted at current scale; a profile-per-proxy scheme is a
  possible future mitigation, out of scope.

### 10. Known limitations (v1)

- **CAPTCHA cannot be solved remotely.** The companion detects challenges,
  and the settings flow surfaces `captcha_required` with a clear message
  (retry often passes without a challenge). The remote user cannot see or
  click the headed browser; only an operator can. This is stated product
  behavior, not a bug.
- **Unattended re-login fails under MFA.** By design (interactive MFA only);
  the keep-warm/refresh job makes this rare, and the failure notification
  tells the owner how to recover.

## Delivery slicing

Each lands independently green. **Docs land in the slice that changes the
behavior they describe** — there is no trailing docs PR (that pattern is how
provider docs drifted before #688).

1. **Encryption key** — registry + sops + compose + `application.yaml`
   `security:` section. Fixes Slack token storage on its own.
   Docs: `secrets.md`.
2. **Companion pool + channel auth** — profile pool, busy locks, two-phase
   MFA, `/verify`, per-route token middleware, required `profile_id`.
   Docs: **new `docs/companion.md`** owning the companion contract (routes,
   profile pool, auth, exposure invariant) — today that contract is scattered
   across `README.md:276-300`, `installation.md`, and `glossary.md`, and the
   README documents a route that doesn't exist (`POST /recgov/atc`; actual is
   `POST /atc`). README/installation/glossary trim to pointers; `secrets.md`
   gains `COMPANION_API_TOKEN`.
3. **Settings storage + routes + FE panel** — migration, service, routes,
   Booking panel. Docs: `backend-architecture.md` (new settings
   service/client), `frontend-components.md` if the Booking panel adds shared
   pieces.
4. **ATC threading + gating + results email** — owner → profile plumbing,
   capability/validator changes, `sendAtcResult` email target, keepalive job.
   Docs: `reservation-providers.md` booking-seam + trigger-registry sections
   (drop "inert atc" phrasing, describe per-user fulfillment and the email
   result channel), `glossary.md` ATC/companion entries, `observability.md`
   for the keepalive job and per-profile session-health metrics.

## Testing

- **Companion (jest):** profile-pool isolation (two profiles never share a
  context), busy-lock contention, auth middleware (401 without token, health
  localhost exemption), two-phase MFA happy/expired paths, verify flow never
  clicks Reserve (assert on the click-spy), failed-login backoff.
- **Backend:** cipher round-trip once the key exists; settings routes
  (write-only password, delete-while-companion-down, status degradation);
  capability gating with/without credentials and for anonymous readers;
  validator rejection; executor profile threading and one-shot re-login;
  `sendAtcResult` email resolution.
- **FE (vitest):** `booking-login.ts` state machine directly; Booking panel
  states (unconfigured, configured, MFA step, captcha message, companion
  down) against a mocked settings API, asserting on roles/labels/text;
  `WatchEditor` disabled-toggle help variant; dirty/save gating for the new
  tab in `SettingsModal.test.tsx`.

## Resolved decisions

- Companion multi-tenancy: per-user profile pool (not credential swap, not
  single-operator v1).
- Custody: backend is the credential custodian; the companion persists no
  passwords, only Chromium profile state.
- Test action: dry-run verify only; no real cart hold from Settings.
- MFA: interactive in Settings; best-effort unattended re-login otherwise.
- Gating: `atc` offered only to users with credentials configured.
- No grandfathering: there are no existing `atc` watches; `profile_id` is
  required from day one.
