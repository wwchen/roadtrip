# Add to cart on Vercel: rec.gov credentials in Neon, a stateless relay on mini-ca

**Status:** design agreed with the owner in conversation on 2026-11-10; this document awaits review.

**Parent specs:** `2026-10-27-kotlin-to-typescript-backend-design.md` (the program) and
`2026-11-08-sign-in-on-vercel-design.md` (sub-projects 2 and 4 of "The move"). This spec covers the
rec.gov-credential part of sub-project 2, the profile part of sub-project 2, and the button half of
sub-project 4.

## Goal

A signed-in user on `roadtrip.floo.ca` can store rec.gov credentials in Settings, sign the companion
in to rec.gov (with MFA), verify the session, remove the credentials, edit their display name and
theme, and hold a campsite in their rec.gov cart from the availability grid. The frontend does not
change.

Success: on production, a new Vercel user saves credentials, signs in, presses Add to cart on a
rec.gov-bookable cell, and gets a `cart_url` whose cart holds that site.

## Owner's decisions (2026-11-10)

- **D1 Credentials live in Neon; mini-ca stores nothing about the user.** The encrypted rec.gov
  password is in Neon's `user_booking_credentials`. Vercel decrypts it per call and sends it in the
  request payload when the companion must sign in.
- **D2 The companion keeps its per-profile browser session cache.** The cookie jar and profile
  directory per `profile_id` are a disposable cache, not a record: losing them costs a fresh login
  with the credentials from the payload. MFA is needed only when the cache is cold. Truly stateless
  logins (MFA on every hold) and shipping session cookies back to Neon were both rejected.
- **D3 Scope is credentials plus the grid button.** Watch-fired add-to-cart (the `atc` trigger), its
  Slack and email notices, and the keep-warm job come with watches (sub-project 3).
- **D4 A thin Kotlin relay on mini-ca carries the calls to the companion**, beside the availability
  egress. Hosting the relay in `packages/worker` was deferred because the worker has no HTTP runtime
  yet; routing the tunnel straight to the companion was rejected because it breaks C2 (the companion
  never gets a public vhost).
- **D5 `PUT /api/settings/profile` comes along.** The notification and Slack write routes wait for
  watches, which are what they serve.

## Today

- Kotlin serves the whole feature (reference code, `backend/src/main/kotlin/ca/floo/roadtrip/`):
  - `route/api/BookingRoutes.kt` — `POST /api/booking/add-to-cart`; status mapping at l.115-129.
  - `service/booking/BookingActionService.kt` — the gates: positive window, campsite to booking
    target, `canFulfil` (credentials stored), the fail-open `knownTaken` check
    (`AvailabilityRepo.freshlyUnavailableDates`, `booking.freshnessMaxAge`, default 5 minutes), then
    `adapter.addToCart` with `stopWhenTriggered=false`, `allowUnattendedRelogin=false`.
  - `service/booking/RecGovBookingAdapter.kt` — the only adapter; preflight (health, cookie refresh,
    credential re-login only when unattended re-login is allowed) and the code-to-category mapping
    at l.85-113.
  - `service/booking/AvailabilityBookingTargetResolver.kt` — a campsite is rec.gov-bookable through
    its primary identity or an alias (a Campflare-served site with a rec.gov alias books on rec.gov).
  - `route/api/settings/RecgovSettingsRoutes.kt` and `service/settings/RecGovCredentialService.kt` —
    save, remove, login, MFA, verify, status. The MFA `challenge_id` lives in Kotlin's memory.
  - `client/companion/CompanionSessionClient.kt` — the companion client (`CompanionSessionPort` and
    `RecGovAtcExecutor`); never throws except on cancellation.
  - `service/security/SecretCipher.kt` — AES-256-GCM, stored as nonce ‖ ciphertext+tag.
  - `service/availability/WatchCapabilityService.kt` — `add_to_cart.state`: `signed_out` (no
    roadtrip session), `unsupported`, `no_credentials`, `ready`.
- The companion (`companion/`, `docs/companion.md`) serves `/health`, `/login` (two-phase MFA),
  `/logout`, `/destroy`, `/refresh`, `/keep-warm`, `/verify`, `/atc`, keyed by `profile_id` and
  guarded by `x-companion-token`.
- `apps/web` has no booking or settings routes. `availabilityMatrixService.ts` always answers
  `add_to_cart: {state: 'unsupported'}`, so the grid never offers the button.
- Neon already has `user_booking_credentials` and `user_settings` (`packages/db/migrations/0000_init.sql`),
  empty. `roadtrip_web_writer` (`0003_web_writer.sql`) can write only `app_user` and `user_identity`.
- Vercel reaches mini-ca through `roadtrip-egress.floo.ca` (Cloudflare tunnel, Cloudflare Access
  service token, then Caddy to `/api/internal/availability/fetch`, enabled by
  `AVAILABILITY_EGRESS_ENABLED`; Cloudflare Access is that route's only caller check), per
  `2026-11-05-availability-egress-design.md`.

## Design

### 1. Who holds what

| Piece | Holds | Change |
| --- | --- | --- |
| Neon | `user_booking_credentials (user_id, provider, username, secret_cipher, updated_at)` | Migration `0004_booking_credentials_writer.sql` grants `roadtrip_web_writer` `SELECT, INSERT, UPDATE, DELETE` on `user_booking_credentials`. The legacy `user_settings.recgov_*` columns are not written. |
| Vercel (`apps/web`) | `BOOKING_CREDENTIAL_ENCRYPTION_KEY` (base64 of 32 bytes) | Routes in §3, a cipher, a booking egress client, the MFA cookie (§4). |
| Kotlin on mini-ca | Nothing per user | `/api/internal/booking/*` relay and a profile-keyed core extracted from `RecGovBookingAdapter` (§2); one Caddy matcher. |
| Companion | Per-`profile_id` browser session cache only | None. |

The decrypted password exists only inside the Vercel function handling a login, and inside the
relay and companion for the length of that call. It is never logged, returned or stored outside
`secret_cipher`.

### 2. The relay

Kotlin registers `POST /api/internal/booking/{health,login,login/mfa,refresh,verify,atc,signout}` only
when `BOOKING_EGRESS_TOKEN` is set and a companion is configured. It reaches Kotlin by the same
route as the availability egress (tunnel hostname, Cloudflare Access, Caddy) and requires
`Authorization: Bearer <BOOKING_EGRESS_TOKEN>`, compared in constant time. The availability egress
relies on Cloudflare Access alone; the relay adds the bearer because it carries passwords, so a
misconfigured Access policy is not enough to reach it.

Every request body is a typed DTO carrying `profile_id`. Only `login` carries `username` and
`password`; `login/mfa` carries `challenge_id` and `mfa_code`; `atc` carries `vendor_site_id`,
`start_date`, `end_date`. Each handler calls the matching `CompanionSessionClient` method:

- `atc` runs the hold preflight with re-login off — health, then a cookie refresh if the session is
  not active, then the hold — so the button path never sends credentials and never logs in
  unattended, as today.
- `signout` is `logout` then `destroyProfile`, and reports each.

**`RecGovBookingAdapter` cannot be called as it stands.** It is keyed by a roadtrip user, not a
profile: `AddToCartRequest.ownerUserId` is a `Long`, `profileIdFor` derives the profile from it
(`RecGovBookingAdapter.kt:242`), `canFulfil` and the re-login path read mini-ca's own
`user_booking_credentials` through `RecGovCredentialService`, and it records `RecentAtcFires`. The
relay change therefore extracts a profile-keyed core from the adapter — preflight (health, refresh),
the `/atc` call and the code-to-category mapping (l.85-113), taking a `profile_id` string and no
credentials source — that both the adapter and the relay call. The adapter keeps its user-keyed
shell (credentials check, profile derivation, re-login, fires) for Kotlin's own watches until they
are retired. The relay passes `profile_id` through verbatim and never derives one.

Every response is one envelope: `{ok, code, category, detail, cart_url, challenge_id, health}`, with
fields absent where they do not apply. `code` is the companion's code unchanged; `category`
(`caller_action`, `retry_later`, `upstream`) comes from the extracted mapping, so the mapping is not
duplicated in TypeScript. Request bodies are excluded from Kotlin's request logging.

**Timeouts.** A proxied Cloudflare request fails with `524` after 100 seconds, and the companion's
run timeout is 180 seconds (`BookingConfig.kt:85`). The relay therefore bounds every companion call
at `BOOKING_RELAY_TIMEOUT` (default 90 seconds), Vercel's booking routes set `maxDuration` above it,
and the Vercel egress client waits slightly longer than the relay. A relay timeout on `atc` answers
`retry_later` with the detail that the site may already be in the cart, because the companion may
finish the hold after the caller has stopped waiting; the response carries the rec.gov cart URL so
the user can check. Kotlin's 180-second budget is not reachable through the tunnel; if real holds
need longer than 90 seconds (`roadtrip_booking_atc_duration_milliseconds` p95 in
`grafana/dashboards/recgov-atc.json` is the number to read before planning), the hold becomes
asynchronous and this section is reopened.

**Routing.** Internal routes live under `/api/internal/` (the availability egress is
`/api/internal/availability/fetch`), so the relay is `/api/internal/booking/*`. The egress host's
Caddy block allowlists one path today (`caddy/Caddyfile:45-51`); it gains a `POST
/api/internal/booking/*` matcher to `backend:8765`. `roadtrip.floo.ca` already answers 404 for
`/api/internal/*` (`Caddyfile:31-34`), so the relay is reachable only through the tunnel hostname.

The relay reads no table, so the later move to the worker is a port of the relay and the extracted
core with no data.

### 3. Vercel routes

All are `RouteAccess.User` equivalents (401 `unauthenticated` without a session), keep the Kotlin
path, body and DTO, and get an `ApiContract` row with every status they serve.

| Route | Behaviour |
| --- | --- |
| `GET /api/settings` | `SettingsResponseDto`: `profile` from `app_user` and Neon Auth; `notifications` from `user_settings` (absent row means all null, `slack_configured: false`); `booking` from `user_booking_credentials`. |
| `PUT /api/settings/profile` | `UpdateProfileRequest` (`display_name`, `theme`; null means unchanged) onto `app_user`. Validation and response as in `SettingsRoutes.kt`. |
| `PUT /api/settings/recgov` | Encrypt and upsert. If the stored username differs, relay `signout` first and refuse the save (as `RecgovProfileWipeFailed`) when the wipe fails. |
| `DELETE /api/settings/recgov` | Relay `signout`; refuse when the wipe fails; otherwise delete the row. `RecgovRemovedDto` with `stranded_atc_watches: 0`. |
| `POST /api/settings/recgov/login` | Relay `refresh`; if still not active, decrypt and relay `login`. `mfa_required` sets the MFA cookie (§4). |
| `POST /api/settings/recgov/login/mfa` | Read and clear the MFA cookie, relay `login/mfa`. |
| `POST /api/settings/recgov/verify` | Relay `verify`. |
| `GET /api/settings/recgov/status` | `RecgovStatusDto`: `configured` and `username` from Neon, `session` from relay `health` (not called without a credentials row). |
| `POST /api/booking/add-to-cart` | The gates of `BookingActionService` in its order, then relay `atc`. |

Layering follows `docs/backend-architecture.md`'s TypeScript equivalent in `apps/web`: route
handlers parse and map status, services orchestrate, `src/server/repo/*` owns Drizzle, and
`src/server/client/bookingEgress.ts` owns the relay HTTP call (redirects refused, non-JSON refused,
as `availabilityEgress.ts` does).

**Add-to-cart gates**, cheapest first, as Kotlin:

1. `end_date > start_date`, else 400 `invalid_window`.
2. The campsite resolves to a rec.gov booking target (primary identity or alias, as
   `AvailabilityBookingTargetResolver`), else 422 `unsupported_target`.
3. A credentials row exists, else 403 `credentials_required`.
4. Fresh evidence that a night is taken refuses with 409 `not_available`. The evidence is the
   stored availability on mini-ca: the add-to-cart service asks the availability egress for
   `mode: "stored"` over the window and refuses only on a cell observed unavailable within
   `BOOKING_FRESHNESS_MAX_AGE_SECONDS` (default 300). Any failure to read it passes the gate
   (fail-open, as `BookingActionService.kt:224-241`).
5. Relay `atc`.

**Status mapping**, from `BookingRoutes.kt:115-129` and the settings routes: refusals as above;
failures by category `caller_action` → 403, `retry_later` → 409, `upstream` → 502. The egress
unreachable, misconfigured, or answering 401 → 503 `booking_unavailable`. No
`BOOKING_CREDENTIAL_ENCRYPTION_KEY` → the credentials routes answer 503 `booking_unavailable`.

**The grid's state.** `availabilityMatrixService` computes `add_to_cart.state` from data Vercel holds
— no companion call — in `WatchCapabilityService.addToCartState`'s order: `unsupported` unless every
campsite in the matrix has a rec.gov booking target, then `signed_out` without a roadtrip session,
then `no_credentials` without a credentials row (or without the encryption key), otherwise `ready`.
`provider` and `provider_display` are set whenever the state is not `unsupported`. `trigger_kinds` stays empty until watches exist. Provider display names come from
`packages/domain`'s booking tenants, which `campgroundCta.ts` already uses. The matrix response now
varies by reader, so it is sent with `Cache-Control: private, no-store`; `force-dynamic` alone does
not say that to a shared cache.

### 4. The MFA challenge

The companion's `challenge_id` must survive between two Vercel invocations without reaching the
browser readable. Vercel seals `{challenge_id, user_id, expires_at}` with AES-256-GCM under
`BOOKING_CREDENTIAL_ENCRYPTION_KEY` into an `HttpOnly`, `Secure`, `SameSite=Strict` cookie scoped to
`/api/settings/recgov/login`, expiring with the companion's challenge (`MFA_CHALLENGE_TTL_SECONDS`, default
300). `login/mfa` rejects a cookie that is missing, expired, unreadable or bound to another user
with `mfa_challenge_expired` when expired and `mfa_challenge_unknown` otherwise (the codes in
`RecGovSessionCodes`), and always clears it.

### 5. Profile ids

The companion's cache on mini-ca is keyed by Kotlin user ids. Neon numbers users from 1 again, so a
bare numeric id would hand a new Neon user a cache signed in to a Kotlin user's rec.gov account.
Vercel's `profile_id` is `n:<app_user.id>`, which the companion's pattern
(`/^[A-Za-z0-9][A-Za-z0-9._:-]*$/`) already accepts. Kotlin's existing profiles are untouched and
never addressed by Vercel.

### 6. Configuration

| Name | Where | Default | Meaning |
| --- | --- | --- | --- |
| `BOOKING_EGRESS_TOKEN` | mini-ca (`secrets/registry.yaml`, consumer `backend`) and Vercel | unset | Relay bearer. Unset on mini-ca: the relay does not exist. Unset on Vercel: booking routes answer 503. |
| `BOOKING_EGRESS_URL` | Vercel | unset | Base URL of the relay (the egress hostname). |
| `BOOKING_CREDENTIAL_ENCRYPTION_KEY` | Vercel only | unset | Credential and MFA-cookie key. |
| `BOOKING_FRESHNESS_MAX_AGE_SECONDS` | Vercel | 300 | Gate 4's window. |
| `MFA_CHALLENGE_TTL_SECONDS` | Vercel | 300 | MFA cookie lifetime. |
| `BOOKING_RELAY_TIMEOUT` | mini-ca | 90 seconds | Upper bound on one relayed companion call; under Cloudflare's 100-second proxy limit. |

The Cloudflare Access service-token pair is the one the availability egress already sends.

## Testing

- **Vitest, `apps/web`:**
  - Cipher: round trip; fixed vectors produced by Kotlin's `SecretCipher` decrypt to the same
    plaintext, so the stored format is one format.
  - Each route against `withPostgis` with a fake booking egress client: every status in its
    contract row, the username-change wipe, refuse-on-failed-wipe for save and remove.
  - The four `add_to_cart` states in the matrix response.
  - MFA cookie: expiry, wrong user, tampering, cleared after use.
  - Gate 4 fail-open when the stored read fails.
- **Kotlin:** relay route tests against `CompanionTestServer`: bearer missing or wrong, the route
  absent when the token is unset, each endpoint's envelope, the `profile_id` passed through verbatim
  (`n:7` reaches the companion as `n:7`), the timeout answer, request bodies not logged. The
  existing `RecGovBookingAdapterTest` stays green over the extracted core.
- **Contract:** every new route has a row; `:backend:contractLedgerCheck` covers the relay's
  bodies.
- No automated check reaches live rec.gov; none does today.

## Rollout

1. Merge. Kotlin ships the relay dark (token unset); Vercel's booking routes answer 503 and the grid
   reports `no_credentials`.
2. Migration `0004` runs against Neon.
3. The owner deploys the Caddy matcher, sets `BOOKING_EGRESS_TOKEN` on mini-ca and deploys, then sets `BOOKING_EGRESS_TOKEN`,
   `BOOKING_EGRESS_URL` and `BOOKING_CREDENTIAL_ENCRYPTION_KEY` on Vercel Production.
4. One manual end to end on production: save, sign in (with MFA), verify, hold one night, check the
   rec.gov cart, remove.

## Non-goals

- Watch-fired add-to-cart, `atc` watches, `AtcResultNotice`, keep-warm, `RecentAtcFires`.
- Notification email and Slack settings routes.
- Moving the relay into `packages/worker`.
- Carrying Kotlin users' credentials or companion profiles over.
- Any vendor other than rec.gov.

## Risks

- **The password crosses Cloudflare in the clear.** TLS ends at Cloudflare's edge, which proxies the
  request into the tunnel, so the decrypted password is visible to Cloudflare on each sign-in (not on
  holds). Access plus a bearer keep anyone else out. This, and the key sitting beside the ciphertext's
  reader on Vercel (a Vercel compromise yields every stored password), are the accepted costs of D1
  against the 10-27 spec's worker-only key.
- **Remove depends on mini-ca.** Like Kotlin, remove refuses when the profile wipe fails, so while
  mini-ca or the relay is down a user cannot delete their stored password. Deleting the Neon row
  anyway would leave a signed-in rec.gov session on disk with no credential to account for it; the
  refusal is kept.
- **A cold cache on the button path fails as `recgov_not_authenticated`** rather than signing in unattended,
  as today. The user signs in again from Settings.
- **Stale Kotlin-era companion profiles stay on disk** until Kotlin's user state is retired
  (sub-project 5).
