# Add to Cart on Vercel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A signed-in Vercel user can store rec.gov credentials, sign the companion in (with MFA), verify, remove them, edit profile and theme, and hold a campsite from the availability grid — with the companion reached through a stateless Kotlin relay on mini-ca.

**Architecture:** Neon holds `user_booking_credentials` (AES-256-GCM). `apps/web` owns the key, decrypts per sign-in, and calls `/api/internal/booking/*` on mini-ca through the egress tunnel. Kotlin's relay holds nothing per user; it calls a profile-keyed core extracted from `RecGovBookingAdapter` and the existing `CompanionSessionClient`. The frontend does not change.

**Tech Stack:** Next.js 16 route handlers, Drizzle (`@roadtrip/db`), vitest, Node `crypto` (AES-256-GCM); Ktor, kotlinx.serialization, Kotest/JUnit route tests with `CompanionTestServer`; Caddy.

**Spec:** `docs/superpowers/specs/2026-11-10-add-to-cart-on-vercel-design.md`. Reference behaviour is the Kotlin code the spec names; where this plan and the Kotlin code disagree, the Kotlin code wins unless the spec says otherwise.

## Global Constraints

- Layering `route -> service -> repo` in both runtimes; Drizzle only under `apps/web/src/server/repo/`; `org.jooq` only where `LayeringGuardTest` allows.
- No inline magic constants: every timeout, TTL, status, code and path is a named `const`.
- No code comments, inline or doc (owner's rule). Rationale goes in commit messages.
- Response bodies are typed with the generated DTOs re-exported from `apps/web/src/server/contract.ts`; never edit `frontend/src/api/generated/api-types.ts`. A Kotlin DTO change runs `make api-types` and commits the diff.
- Every new Kotlin route gets an `ApiContract.kt` row with the status of every body it serves; its success body must be produced by a route test (`:backend:contractLedgerCheck`).
- `profile_id` sent by Vercel is `n:<app_user.id>`; Kotlin never derives one on the relay path.
- Passwords and request bodies are never logged on either side.
- Defaults: `BOOKING_RELAY_TIMEOUT` 90 s (mini-ca); `BOOKING_FRESHNESS_MAX_AGE` 5 min, `MFA_CHALLENGE_TTL` 5 min (Vercel).
- Commits stage explicit paths only (never `git add -A`); messages use `(type): desc` and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A user whose credentials row exists but whose Vercel deployment lacks the key: routes answer 503 `booking_unavailable`, the grid shows `no_credentials`, nothing throws. (Task 7, Task 9)
2. Two tabs pressing Add to cart on the same profile at once: the second gets the companion's `profile_busy` mapped to 409, not a 502. (Task 8)
3. An MFA code submitted after the cookie expired or from a different signed-in user: `mfa_challenge_expired` / `mfa_challenge_unknown`, and the cookie is cleared either way. (Task 7)
4. Saving the same username again does not wipe the companion profile; changing it does, and a failed wipe leaves the old row intact. (Task 7)
5. The relay answering non-JSON (Cloudflare's HTML 524 page after 100 s): Vercel maps it to 503 `booking_unavailable`, not a parse crash. (Task 5)

---

### Task 1: Profile-keyed hold core in Kotlin

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/RecGovProfileHold.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/RecGovBookingAdapter.kt` (preflight l.162-240, category map l.85-113, `addToCartViaCompanion` l.275-298)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/booking/RecGovProfileHoldTest.kt`; existing `RecGovBookingAdapterTest.kt` must stay green unchanged.

**Interfaces:**
- Produces:
  - `internal class RecGovProfileHold(companion: CompanionSessionPort, executor: RecGovAtcExecutor)`
  - `suspend fun hold(profileId: String, vendorSiteId: String, arrival: LocalDate, checkout: LocalDate): ProfileHoldOutcome`
  - `sealed interface ProfileHoldOutcome { data class Held(val cartUrl: String); data class Blocked(val code: String, val category: BookingFailureCategory, val detail: String?) }`
  - `fun categoryOf(code: String): BookingFailureCategory` (the moved map; unknown → `UPSTREAM`)
- Consumes: `CompanionSessionPort.health/refresh`, `RecGovAtcExecutor`.

- [ ] **Step 1: Write failing tests in `RecGovProfileHoldTest`** with a fake `CompanionSessionPort` and executor:
  - `active session holds without refresh` — health Active → executor called with `profile_id = "n:7"` verbatim → `Held(cartUrl)`.
  - `inactive session refreshes then holds` — health Inactive, refresh Ok → one refresh, then hold.
  - `refresh failure blocks with session expired` — `Blocked(code = RECGOV_SESSION_EXPIRED_ERROR, category = CALLER_ACTION)`; executor not called.
  - `companion code maps through the moved table` — executor returns `profile_busy` → `Blocked(category = RETRY_LATER)`; unknown code → `UPSTREAM`.
  - `cart_added false is not a hold` — executor ok with `cart_added = false` → `Blocked`.
- [ ] **Step 2: Run** `./gradlew :backend:test --tests '*RecGovProfileHoldTest'` — Expected: FAIL, class missing.
- [ ] **Step 3: Implement `RecGovProfileHold`** by moving the health/refresh preflight, the `/atc` call and the code→category map out of the adapter. It has no credentials source and no re-login: re-login stays in the adapter.
- [ ] **Step 4: Rewire `RecGovBookingAdapter`** to derive the profile (`profileIdFor`), run its unattended re-login branch when allowed, record `RecentAtcFires`, and delegate the rest to `RecGovProfileHold`. Construct it in `di/ServiceModule.kt:241-256` beside the adapter.
- [ ] **Step 5: Run** `./gradlew :backend:test --tests '*RecGovProfileHoldTest' --tests '*RecGovBookingAdapterTest' --tests '*TriggerActionHandlerTest' --tests '*BookingRoutesTest'` — Expected: PASS.
- [ ] **Step 6: Commit** `(refactor): a rec.gov hold keyed by profile, under the user-keyed adapter`.

### Task 2: The booking relay on mini-ca

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/internal/BookingRelayRoutes.kt`, `.../route/internal/BookingRelayController.kt`, `.../model/api/BookingRelayDto.kt`
- Modify: `.../config/BookingConfig.kt` (relay token, relay timeout), `backend/src/main/resources/application.yaml` (`roadtrip.booking.relay.token: ${BOOKING_EGRESS_TOKEN:}`, `timeout: ${BOOKING_RELAY_TIMEOUT:90s}`), `.../di/RouteModule.kt:176` (wire beside `availabilityEgressRoutes`), `.../model/api/ApiContract.kt` (seven rows), `secrets/registry.yaml` (`BOOKING_EGRESS_TOKEN`, consumers `[backend]`, `required_in: []`), `caddy/Caddyfile:45-51` (add `@booking { method POST; path /api/internal/booking/* }` → `backend:8765` on the egress host block), `frontend/src/api/generated/api-types.ts` via `make api-types`.
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/BookingRelayRoutesTest.kt` (pattern: `AvailabilityEgressRoutesTest.kt`, companion faked with `CompanionTestServer`).

**Interfaces:**
- Consumes: `RecGovProfileHold.hold`, `CompanionSessionPort` (`login`, `completeMfa`, `refresh`, `verify`, `health`, `logout`, `destroyProfile`).
- Produces (paths under `const val BOOKING_RELAY_PREFIX = "/api/internal/booking"`), all `POST`, bodies `@Serializable`:
  - `health`, `refresh`, `verify`, `signout`: `{profile_id}`
  - `login`: `{profile_id, username, password}`
  - `login/mfa`: `{profile_id, challenge_id, mfa_code}`
  - `atc`: `{profile_id, vendor_site_id, start_date, end_date}`
  - Response `BookingRelayResponseDto { ok: Boolean, code: String?, category: String?, detail: String?, cart_url: String?, challenge_id: String?, health: String?, signed_out: Boolean?, profile_destroyed: Boolean? }`; `category` ∈ `caller_action | retry_later | upstream`; `health` ∈ the `CompanionSessionHealth` names in snake case.
  - Statuses: 200 for every companion answer (blocked or not); 400 bad body; 401 missing/wrong bearer; 504 `relay_timeout` with `category: retry_later` and `cart_url` set to the rec.gov cart page on `atc`.

- [ ] **Step 1: Write failing route tests:**
  - `routes are absent when the token is unset` → 404 on every path.
  - `missing or wrong bearer is 401` for each path.
  - `atc passes the profile id through verbatim` — body `profile_id: "n:7"` → companion fake receives `n:7`; 200 `{ok: true, cart_url}`.
  - `login answers mfa_required with a challenge id` → 200 `{ok: false, code: "mfa_required", challenge_id}`.
  - `signout reports logout and destroy` → `{ok, signed_out, profile_destroyed}`; destroy failure → `ok: false`.
  - `a companion call past the relay timeout answers 504 relay_timeout` (timeout set to 50 ms in the test config, fake sleeps longer).
  - `request bodies are not logged` — capture the call logger; the password string never appears.
- [ ] **Step 2: Run** `./gradlew :backend:test --tests '*BookingRelayRoutesTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** routes (parse, bearer check with `MessageDigest.isEqual`, call controller, encode) and controller (one `withTimeout(relayTimeout)` per call, maps results to the envelope). `RouteAccess.Anonymous` like the egress route; the bearer is the check.
- [ ] **Step 4: Add the seven `ApiContract` rows**, run `make api-types`, then `./gradlew :backend:test --tests '*BookingRelayRoutesTest' :backend:contractLedgerCheck` — Expected: PASS.
- [ ] **Step 5: Add the registry entry and the Caddy matcher;** run `./secrets/manage.py check` and `docker run --rm -v "$PWD/caddy:/etc/caddy:ro" caddy:2 caddy validate --config /etc/caddy/Caddyfile` — Expected: both clean.
- [ ] **Step 6: Commit** `(feat): a stateless booking relay on mini-ca, dark until its token is set`.

### Task 3: Writer grants for booking credentials

**Files:**
- Create: `packages/db/migrations/0004_booking_credentials_writer.sql` (created with `npm run generate --workspace @roadtrip/db -- --custom --name booking_credentials_writer`, which also updates `migrations/meta/`)
- Test: `packages/db/src/webWriterGrants.test.ts`

**Interfaces:**
- Produces: `roadtrip_web_writer` has `SELECT, INSERT, UPDATE, DELETE` on `user_booking_credentials`; `roadtrip_web` gains nothing.

- [ ] **Step 1: Add failing tests:** `the writer can insert, update, read and delete a booking credentials row` and `the reader cannot read booking credentials` (expects `INSUFFICIENT_PRIVILEGE`).
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/db -- webWriterGrants` — Expected: FAIL.
- [ ] **Step 3: Write the migration** (`GRANT SELECT, INSERT, UPDATE, DELETE ON user_booking_credentials TO roadtrip_web_writer;`).
- [ ] **Step 4: Run** the same command plus `node --test scripts/schema-equivalence.test.mjs` — Expected: PASS.
- [ ] **Step 5: Commit** `(feat): the web writer can keep booking credentials`.

### Task 4: Booking config and the secret cipher in apps/web

**Files:**
- Create: `apps/web/src/server/crypto/secretCipher.ts`, `backend/src/test/resources/secret-cipher-vectors.json`
- Modify: `apps/web/src/server/config.ts`
- Test: `apps/web/src/server/crypto/secretCipher.test.ts`, `apps/web/src/server/config.test.ts`, `backend/src/test/kotlin/ca/floo/roadtrip/service/security/SecretCipherVectorsTest.kt`

**Interfaces:**
- Produces:
  - `config.booking: BookingConfig | null` — `{ relayUrl: URL; relayToken: string; access: EgressAccessCredentials; freshnessMaxAgeMs: number; mfaChallengeTtlMs: number }`, null unless `BOOKING_EGRESS_URL`, `BOOKING_EGRESS_TOKEN` and the existing Access pair are set; partial configuration throws `ConfigError` (as `signInFrom` does).
  - `config.bookingCipherKey: Buffer | null` — from `BOOKING_CREDENTIAL_ENCRYPTION_KEY`, base64 of exactly 32 bytes, else `ConfigError`.
  - `encryptSecret(key: Buffer, plaintext: string): Buffer` and `decryptSecret(key: Buffer, sealed: Buffer): string` — 12-byte random nonce ‖ ciphertext ‖ 16-byte tag, matching `SecretCipher.kt`.
  - `const BOOKING_RELAY_CLIENT_TIMEOUT_MS = 95_000`, `const BOOKING_ROUTE_MAX_DURATION_S = 120`.

- [ ] **Step 1: Kotlin side first.** Write `SecretCipherVectorsTest` that, for three fixed plaintexts (`""`, `"hunter2"`, a 64-char UTF-8 string with a non-ASCII char) and a fixed key, decrypts each vector in `secret-cipher-vectors.json`; generate the file once with `SecretCipher` and commit it. Run `./gradlew :backend:test --tests '*SecretCipherVectorsTest'` — Expected: PASS.
- [ ] **Step 2: Write failing TS tests:** `decrypts the Kotlin vectors` (reads `../../../../../backend/src/test/resources/secret-cipher-vectors.json`), `round trips`, `rejects a tampered tag`, `a short key is a ConfigError`, `partial booking config is a ConfigError`, `absent booking config is null`.
- [ ] **Step 3: Run** `npm test --workspace apps/web -- secretCipher config` — Expected: FAIL.
- [ ] **Step 4: Implement** with `node:crypto` `createCipheriv('aes-256-gcm', …)`.
- [ ] **Step 5: Run** the tests — Expected: PASS.
- [ ] **Step 6: Commit** `(feat): booking config and a credential cipher that reads Kotlin's format`.

### Task 5: Session user and the booking relay client

**Files:**
- Create: `apps/web/src/server/auth/currentUser.ts`, `apps/web/src/server/client/bookingRelay.ts`
- Modify: `apps/web/src/server/service/auth/meService.ts` (reuse the session→`app_user` lookup instead of duplicating it)
- Test: `apps/web/src/server/auth/currentUser.test.ts`, `apps/web/src/server/client/bookingRelay.test.ts`

**Interfaces:**
- Produces:
  - `currentAppUser(): Promise<AppUserRow | null>` and `requireAppUser(): Promise<AppUserRow | Response>` (the `Response` is `apiError(401, 'unauthenticated')`).
  - `profileIdFor(userId: bigint): string` → `` `n:${userId}` ``.
  - `relay(config: BookingConfig, path: BookingRelayPath, body: object): Promise<RelayResult>` where `BookingRelayPath = 'health' | 'login' | 'login/mfa' | 'refresh' | 'verify' | 'atc' | 'signout'` and `RelayResult = { ok: true; answer: BookingRelayResponseDto } | { ok: false; code: 'unreachable' | 'timeout' | 'relay_unauthorized' | 'relay_redirected' | 'relay_not_json' }`. 504 `relay_timeout` from mini-ca is an `ok: true` answer (it carries the envelope).

- [ ] **Step 1: Write failing tests** for the client against a local `http.createServer` fake: sends `Authorization: Bearer`, `CF-Access-Client-Id`/`-Secret`; 200 envelope → `ok: true`; 504 envelope → `ok: true`; 401 → `relay_unauthorized`; HTML body with status 524 → `relay_not_json`; redirect → `relay_redirected`; slow server past `BOOKING_RELAY_CLIENT_TIMEOUT_MS` (overridden small) → `timeout`. For `currentUser`: no session → null; session → the linked row; `profileIdFor(7n) === 'n:7'`.
- [ ] **Step 2: Run** `npm test --workspace apps/web -- bookingRelay currentUser` — Expected: FAIL.
- [ ] **Step 3: Implement** following `availabilityEgress.ts` (`redirect: 'manual'`, content-type check, `AbortSignal.timeout`).
- [ ] **Step 4: Run** — Expected: PASS; `npm test --workspace apps/web -- meService` still PASS.
- [ ] **Step 5: Commit** `(feat): the signed-in app user and a client for the booking relay`.

### Task 6: Settings read and profile update

**Files:**
- Create: `apps/web/src/server/repo/bookingCredentialsRepo.ts`, `apps/web/src/server/repo/userSettingsRepo.ts`, `apps/web/src/server/service/settings/settingsService.ts`, `apps/web/src/app/api/settings/route.ts`, `apps/web/src/app/api/settings/profile/route.ts`
- Modify: `apps/web/src/server/repo/appUserRepo.ts` (`updateProfile`), `apps/web/src/server/contract.ts` (re-export `SettingsResponseDto`, `UpdateProfileRequest`, `BookingSettingsDto`)
- Test: `*.test.ts` beside each, repos against `withPostgis`.

**Interfaces:**
- Produces:
  - `bookingCredentialsRepo`: `find(db, userId: bigint, provider: string): Promise<{ username: string; secretCipher: Buffer } | null>`, `upsert(db, userId, provider, username, secretCipher)`, `remove(db, userId, provider): Promise<boolean>`. `const RECGOV_PROVIDER = 'recgov'`.
  - `userSettingsRepo.notificationsFor(db, userId): Promise<NotificationsDto>` (absent row → all null, `slack_configured: false`).
  - `appUserRepo.updateProfile(db, userId, patch: { displayName?: string | null; theme?: UserTheme }): Promise<AppUserRow>`.
  - `settingsFor(user: AppUserRow): Promise<SettingsResponseDto>`; `updateProfile(user, request: UpdateProfileRequest): Promise<SettingsResponseDto | Response>`.

- [ ] **Step 1: Write failing tests:** `GET /api/settings` 401 anonymous; 200 with `booking.recgov_configured: false` and empty notifications for a new user; `recgov_username` once a row exists (password never in the body). `PUT /api/settings/profile`: null fields unchanged; bad theme → 400 with Kotlin's code (read `SettingsService.updateProfile` for the exact code and the display-name length rule); 200 returns the updated settings.
- [ ] **Step 2: Run** `npm test --workspace apps/web -- settings` — Expected: FAIL.
- [ ] **Step 3: Implement** repos (Drizzle, `appDb()`), service, routes (`dynamic = 'force-dynamic'`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `(feat): settings and profile on Vercel`.

### Task 7: Rec.gov credentials, sign-in and MFA

**Files:**
- Create: `apps/web/src/server/service/settings/recgovCredentialService.ts`, `apps/web/src/server/auth/mfaChallengeCookie.ts`, routes `apps/web/src/app/api/settings/recgov/{route.ts,login/route.ts,login/mfa/route.ts,verify/route.ts,status/route.ts}`
- Modify: `apps/web/src/server/contract.ts` (re-export `RecgovStatusDto`, `RecgovRemovedDto`, `RecgovLoginResponseDto`, `RecgovVerifyResponseDto`, `RecgovMfaRequest`, the save request type)
- Test: beside each; service tests use a fake `relay`.

**Interfaces:**
- Consumes: Task 4 cipher and config, Task 5 `relay`/`requireAppUser`/`profileIdFor`, Task 6 `bookingCredentialsRepo`.
- Produces: `save`, `remove`, `login`, `completeMfa`, `verify`, `status`, each `(user: AppUserRow, …) => Promise<Dto | Response>`, with bodies and status codes taken from `RecGovCredentialService.kt` and `RecgovSettingsRoutes.kt` (a blocked login is a 200 with a `status` and code; only "nothing stored" and "no key" are HTTP errors). `sealMfaChallenge(key, { challengeId, userId, expiresAt }): string`, `openMfaChallenge(key, value, userId, now): { challengeId } | { code: 'mfa_challenge_expired' | 'mfa_challenge_unknown' }`. Cookie `const MFA_COOKIE = '__Secure-recgov-mfa'` (a `__Host-` name forbids the path), attributes `HttpOnly; Secure; SameSite=Strict; Path=/api/settings/recgov/login; Max-Age=<ttl>`.

- [ ] **Step 1: Write failing tests:**
  - save: same username → no `signout`; new username → `signout` first; `signout` with `profile_destroyed: false` → refused with Kotlin's wipe-failed body and the old row unchanged; no key → 503 `booking_unavailable`.
  - remove: wipe ok → row deleted, `stranded_atc_watches: 0`; wipe fails or relay unreachable → refused, row kept.
  - login: `refresh` active → no password sent; inactive → `login` with the decrypted password; `mfa_required` → 200 body with that status and a `Set-Cookie` for the sealed challenge; `challenge_id` absent from the JSON body.
  - mfa: valid cookie → `login/mfa` with its `challenge_id`; expired → `mfa_challenge_expired`; other user's cookie or tampered → `mfa_challenge_unknown`; every outcome clears the cookie (`Max-Age=0`).
  - status: no row → `configured: false` and no relay call; row → `session` from `health`; relay unreachable → the unavailable session state Kotlin reports.
  - Every route: 401 anonymous.
- [ ] **Step 2: Run** `npm test --workspace apps/web -- recgov mfaChallengeCookie` — Expected: FAIL.
- [ ] **Step 3: Implement** service, cookie helper, routes (`export const maxDuration = BOOKING_ROUTE_MAX_DURATION_S` on login, mfa, verify, delete).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `(feat): rec.gov credentials, sign-in and MFA on Vercel`.

### Task 8: Add to cart

**Files:**
- Create: `apps/web/src/server/service/booking/bookingTarget.ts`, `apps/web/src/server/service/booking/addToCartService.ts`, `apps/web/src/app/api/booking/add-to-cart/route.ts`
- Modify: `apps/web/src/server/repo/campsiteCatalogRepo.ts` (campsite with its campground's booking refs, if not already exposed), `apps/web/src/server/contract.ts` (re-export `AddToCartRequestDto`, `AddToCartResponseDto`)
- Test: beside each.

**Interfaces:**
- Consumes: `bookingIdentities` from `packages/domain/src/booking-identities.ts`, `shippedTenantRegistry()` from `booking-tenants.ts`, `configuredEgress(AVAILABILITY_STORED_TIMEOUT_MS)` from `availabilityMatrixService.ts` (stored mode), Task 5 `relay`.
- Produces:
  - `recgovTargetFor(campsite: CampsiteRow, campground: CampgroundRow): { vendorSiteId: string; providerDisplay: string } | null` — the primary identity then aliases, as `AvailabilityBookingTargetResolver.declaredTarget`, rec.gov only, non-blank `vendorSiteId`.
  - `addToCart(user, request: AddToCartRequestDto): Promise<AddToCartResponseDto | Response>`.

- [ ] **Step 1: Write failing tests:**
  - `recgovTargetFor`: primary rec.gov identity; Campflare primary with a rec.gov alias; no rec.gov identity → null; blank ref → null.
  - gates in order: `end_date <= start_date` → 400 `invalid_window` (no DB read); no target → 422 `unsupported_target`; no row → 403 `credentials_required`; a stored cell `unavailable` with `observed_at` 4 min old → 409 `not_available`; the same cell 6 min old → passes; stored read fails → passes.
  - relay: `ok` → 200 `{status: 'completed', cart_url, provider: 'recgov', provider_display}`; category `caller_action` → 403, `retry_later` (incl. `profile_busy`) → 409, `upstream` → 502, each body `{error: code, detail, provider, provider_display}`; 504 `relay_timeout` → 409 with `cart_url`; relay `unreachable`/`relay_not_json` → 503 `booking_unavailable`.
  - 401 anonymous; `maxDuration === BOOKING_ROUTE_MAX_DURATION_S`.
- [ ] **Step 2: Run** `npm test --workspace apps/web -- bookingTarget addToCart add-to-cart` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `(feat): add to cart from the grid on Vercel`.

### Task 9: The grid offers Add to cart

**Files:**
- Modify: `apps/web/src/server/service/availability/availabilityMatrixService.ts:25,141` (replace `GATED_WATCH_CAPABILITIES` with a computed value), `apps/web/src/app/api/pois/[id]/campsites/availability/route.ts` (pass the reader; `Cache-Control: private, no-store`)
- Test: `availabilityMatrixService.test.ts`, the route's test.

**Interfaces:**
- Consumes: Task 5 `currentAppUser`, Task 6 `bookingCredentialsRepo.find`, Task 8 `recgovTargetFor`, `config.bookingCipherKey`.
- Produces: `addToCartCapability(campsites, reader: AppUserRow | null): AddToCartCapabilityDto` — order from `WatchCapabilityService.addToCartState`: `unsupported` unless every campsite has a rec.gov target; then `signed_out`; then `no_credentials` (no row, or no key); else `ready`. `provider: 'recgov'` and `provider_display` set unless `unsupported`. `trigger_kinds: []` always.

- [ ] **Step 1: Write failing tests** for each state, the order (an anonymous reader on a non-rec.gov matrix is `unsupported`, not `signed_out`), and the route's `Cache-Control: private, no-store` header.
- [ ] **Step 2: Run** `npm test --workspace apps/web -- availabilityMatrix availability/route` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `(feat): the grid offers add to cart to a credentialed reader`.

### Task 10: Whole-branch verification and the runbook

**Files:**
- Modify: `docs/companion.md` (a short "Reached from Vercel" section: relay paths, `n:` profiles, bearer, 90 s bound), `docs/sandbox-deploys.md` or `docs/secrets.md` (new variables: `BOOKING_EGRESS_TOKEN`, `BOOKING_EGRESS_URL`, `BOOKING_CREDENTIAL_ENCRYPTION_KEY`, `BOOKING_RELAY_TIMEOUT`, `BOOKING_FRESHNESS_MAX_AGE`, `MFA_CHALLENGE_TTL`).

- [ ] **Step 1: Run** `make test` — Expected: exit 0 (includes `make api-types` staleness, contract ledger, layering guard, apps/web and backend suites).
- [ ] **Step 2: Run** `npm run build --workspace apps/web` — Expected: success.
- [ ] **Step 3: Write the docs changes** above, each variable with where it is set and its default, copied from the spec's §6 table.
- [ ] **Step 4: Commit** `(docs): the booking relay and its configuration`.
- [ ] **Step 5: Open the PR** titled `(feat): add to cart on Vercel`, body listing the owner's rollout steps from the spec (migration, Caddy deploy, tokens, key, one manual hold). Get CI green; do not merge.
