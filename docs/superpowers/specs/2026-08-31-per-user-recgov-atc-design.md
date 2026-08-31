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
prod and local), thread it through the sops env files and compose, and add a
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
  no armed watch are torn down when idle.
- **Per-profile busy lock:** no two operations run concurrently on one
  profile. A pending MFA challenge holds the lock until completed or expired.
- **Two-phase MFA:** `POST /login` without a code returns `mfa_required` plus
  a short-TTL challenge id (the pending browser page is held open); a second
  call with the code completes it.
- **`POST /verify` (dry run):** confirm the profile's session by loading a
  logged-in-only page and navigating a real booking URL **without clicking
  Reserve**. No cart hold is ever placed by a test.
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

### 8. Frontend

- New "Booking" panel in `SettingsModal`: username input, `SecretField`
  password, Save / Test login / Verify session actions, an MFA code step, and
  a status line driven by `GET /api/settings/recgov/status`.
- `WatchEditor` needs no structural change — it is already capability-driven.
  Add a help-text variant for "scope supports ATC but you have no
  credentials": "Add rec.gov credentials in Settings."
- Error mapping extends `settings-errors.ts` (`mfa_required`,
  `captcha_required`, `companion_unavailable`, `login_failed`).

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

Each lands independently green:

1. **Encryption key** — registry + sops + compose + `application.yaml`
   `security:` section. Fixes Slack token storage on its own.
2. **Companion pool + channel auth** — profile pool, busy locks, two-phase
   MFA, `/verify`, per-route token middleware, required `profile_id`.
   Includes the doc fix: companion route is `POST /atc`, not
   `POST /recgov/atc` (README.md, glossary.md).
3. **Settings storage + routes + FE panel** — migration, service, routes,
   Booking panel.
4. **ATC threading + gating + results email** — owner → profile plumbing,
   capability/validator changes, `sendAtcResult` email target, keepalive job.
5. **Docs** — `reservation-providers.md` booking-seam and trigger-registry
   sections, `secrets.md`, `installation.md`.

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
- **FE (vitest):** Booking panel states (unconfigured, configured, MFA step,
  captcha message, companion down) against a mocked settings API.

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
