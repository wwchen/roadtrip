# Booking in Layers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Booking, rec.gov settings and profile settings in `apps/web` are split into route, mapper, controller, repository and wiring, the Kotlin relay answers one tagged result per operation, and agents working in `apps/web` load the Next.js rules.

**Architecture:** Controllers take plain values and ports and return discriminated unions on `kind`. HTTP mappers turn a request into a command and an outcome into an answer; a relay mapper is the only code that knows the relay's wire. `wiring/` alone reads `config`. An import guard fails the build when a file does another layer's work.

**Tech Stack:** Next.js 16.4.5 route handlers, TypeScript, vitest, Drizzle (`@roadtrip/db`), `node:test` for `scripts/`; Ktor, kotlinx.serialization, JUnit 5 route tests.

**Spec:** `docs/superpowers/specs/2026-11-14-booking-in-layers-design.md`. Read it first. Where this plan and the spec disagree, the spec wins except for the items under "Decided while planning".

**Status:** built on 2026-11-14. This plan is the record of what was asked of each task, not a description of the code. Where they differ, the code and the spec's two "Amended" sections are right. Counts in the steps (tests, items) are the ones expected when each task ran.

## Global Constraints

- **Four pull requests, each one commit, none merged by an agent.** Parts 1 to 4 below are the four. Work on a branch per part (`claude/web-agent-files`, `claude/grid-add-to-cart-sign-in`, `claude/booking-layers`, `claude/booking-relay-results`), each branched from the one before. Commit per task while working; the last task of a part squashes to one commit whose subject is the pull request title, then opens the pull request with its base set to `master` if the previous part has merged, else to the previous part's branch. Report the URL and stop.
- **The existing assertions are the oracle.** A moved test keeps its expected status, body and cookie. If one needs a different expectation for a reason not listed in the spec's "Behaviour that changes", stop and report it.
- **The public HTTP contract does not change** apart from the spec's four listed changes: response DTOs, error codes, statuses, the cookie's name, path and attributes, each route's `maxDuration`.
- No code comments, inline or doc. Rationale goes in the commit message.
- No inline magic constants: every status, code, detail string, path and duration is a named `const`. Detail strings move with their constants, unchanged.
- Never edit `frontend/src/api/generated/api-types.ts` by hand. A Kotlin DTO change runs `make api-types` and commits the diff.
- Stage explicit paths only (never `git add -A`). Commit and pull request titles are `(type): desc`. Commits end with `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`; pull request bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- The repository is public. Keep host names, project ids and firewall details out of commits and pull request bodies.
- Before writing Next.js code, read the relevant guide in `node_modules/next/dist/docs/` at the repo root (`next` is hoisted there).
- Commands: `npm run build` (repo root) after any change under `packages/`; `npm test --workspace @roadtrip/web -- <file filters>`; `npm run typecheck --workspace @roadtrip/web`; `node --test scripts/check-server-only-imports.test.mjs && node scripts/check-server-only-imports.mjs`; `./gradlew :backend:test --tests '*<Class>'`; `make test` for everything. Tests that open a database need a running Docker daemon.

## Decided while planning

Each refines the spec so the oracle holds. The spec records them under "Amended while planning", and item 7 in its "Order of work".

1. `GatewayFault` gains `unanswered`: the web app's own 95 s wait ran out. Today's client code is `timeout`.
2. `CredentialVault.status` has a third state, `locked`: a row exists and no key is configured. Today a rename still works in that state while settings read "not configured"; two states cannot say both.
3. `SessionHealth.check_failed` carries `detail?` as well as `code?`, as the status route reports today.
4. `/api/me` answers anonymous for a disabled account. It does not go through `currentAppUser()`, and without this it would say "signed in" while every route says 401.
5. `USER_THEMES`, `UserTheme`, `USER_STATUSES` and `UserStatus` move to `@roadtrip/domain`; `@roadtrip/db` re-exports them. A model file may import only `@roadtrip/domain`, and the user's theme is part of the model.
6. `HttpAnswer.cookie` is the rendered `Set-Cookie` value, so only `mapper/http/recgovHttp.ts` touches the cookie module.
7. Pull request 4 also changes the re-export list in `apps/web/src/server/contract.ts`. The check on the layers is: nothing else under `apps/web/src` changes except files under `server/mapper/relay/` (the mapper, its test, and the answer fixtures the client's test reads).
8. There is no `ProfileUpdateOutcome`. A bad profile body is a parse failure in the mapper, so the controller returns the `SettingsView`.
9. On today's wire a refusal with no `category` reads as `upstream`, as the status table does today. Only a refusal with no `code` is malformed.
10. (Found while building Task 9.) A cookie the web app finds missing or expired is its own `LoginOutcome`, `challenge_gone { reason }`, not a `failed` outcome. The two challenge codes can also come from the relay, and only the web app's own finding carries the detail text today; one variant could not say which it was. Task 9's rules and answer table read accordingly: `submitMfa` returns `challenge_gone` for `none` and `expired`; the mapper answers it with the code, its detail and a cleared cookie; a `failed` outcome carries a detail only when the refusal has one.

## Review Focus

1. **A credentials row with no key configured.** Settings read "not configured", a save with a password is 503 before any profile wipe, a rename without a password still works. (Tasks 7, 9)
2. **An account disabled mid-session.** Every route in the slice answers 401 with `no-store`, `/api/me` answers anonymous, the grid answers `signed_out`. (Task 3)
3. **A 200 from the relay whose JSON is not a result** (`{}`, a tag without its field). Add to cart is 503, sign-in is `companion_unavailable` and keeps the MFA cookie, status is `companion_unavailable`, save and remove are `recgov_profile_wipe_failed`. Nothing throws. (Tasks 5, 8, 9)
4. **An MFA cookie that outlives the key.** Status says `mfa_pending: false`; an MFA submit answers `mfa_challenge_unknown` and clears the cookie. (Task 9)
5. **A relay `expires_at` that is unparseable or far in the future.** The cookie's `Max-Age` is the configured ceiling, never negative and never `NaN`. (Task 9)

## Shared types

Created by Tasks 3, 5 and 7; every later task consumes them. Paths are under `apps/web/src/server`.

```ts
// service/auth/model.ts                                              (Task 3)
import type { UserTheme } from '@roadtrip/domain';
export interface UserProfile { id: bigint; email: string; emailVerified: boolean; displayName: string | null; theme: UserTheme }
export interface ActiveUser extends UserProfile { status: 'active' }

// service/booking/model.ts                                           (Task 5)
export const RECGOV_PROVIDER = 'recgov';
export type RefusalCategory = 'caller_action' | 'retry_later' | 'upstream';
export interface Refusal { category: RefusalCategory; code: string; detail?: string }
export type GatewayFault = 'not_configured' | 'unanswered' | 'unreachable' | 'unauthorized' | 'redirected' | 'malformed' | 'rejected';
export interface StayWindow { startDate: string; endDate: string }
export function stayWindow(startDate: string, endDate: string): StayWindow | null;   // null unless endDate > startDate
export interface BookableCampsite { /* moved unchanged from repo/campsiteCatalogRepo.ts */ }
type Unavailable = { kind: 'unavailable'; fault: GatewayFault };
export type HoldResult = { kind: 'held'; cartUrl: string } | { kind: 'blocked'; refusal: Refusal } | { kind: 'timed_out'; refusal: Refusal; cartUrl?: string } | Unavailable;
export type LoginResult = { kind: 'signed_in' } | { kind: 'mfa_required'; challengeId: string; expiresAt?: string } | { kind: 'failed'; refusal: Refusal } | { kind: 'timed_out'; refusal: Refusal } | Unavailable;
export type ActionResult = { kind: 'ok' } | { kind: 'failed'; refusal: Refusal } | { kind: 'timed_out'; refusal: Refusal } | Unavailable;
export type SessionHealth = { kind: 'active' } | { kind: 'never_logged_in' } | { kind: 'inactive'; code?: string } | { kind: 'check_failed'; code?: string; detail?: string } | { kind: 'unavailable'; code?: string; detail?: string };
export type SignOutResult = { kind: 'wiped'; signedOut: boolean } | { kind: 'failed'; signedOut: boolean; refusal: Refusal } | { kind: 'timed_out'; refusal: Refusal } | Unavailable;
export type VaultStatus = { kind: 'none' } | { kind: 'stored'; username: string } | { kind: 'locked'; username: string };
export type OpenedVault = { kind: 'unavailable' } | { kind: 'none' } | { kind: 'opened'; username: string; password: string };
export interface AddToCartCommand { campsiteId: bigint; window: StayWindow }
export type AddToCartOutcome =
  | { kind: 'held'; cartUrl: string; providerDisplay: string }
  | { kind: 'unsupported_target' }
  | { kind: 'credentials_required'; providerDisplay: string }
  | { kind: 'not_available'; providerDisplay: string }
  | { kind: 'refused'; refusal: Refusal; providerDisplay: string; cartUrl?: string }
  | { kind: 'unavailable'; providerDisplay: string };
export type ReaderStanding = 'sign_in_off' | 'signed_out' | 'no_credentials' | 'ready';
export type AddToCartCapability = { state: 'unsupported' } | { state: 'signed_out' | 'no_credentials' | 'ready'; providerDisplay: string };

// service/booking/ports.ts                                           (Task 5)
export interface BookingGateway {
  hold(userId: bigint, vendorSiteId: string, window: StayWindow): Promise<HoldResult>;
  signIn(userId: bigint, username: string, password: string): Promise<LoginResult>;
  submitMfa(userId: bigint, challengeId: string, code: string): Promise<LoginResult>;
  refresh(userId: bigint): Promise<ActionResult>;
  verify(userId: bigint): Promise<ActionResult>;
  health(userId: bigint): Promise<SessionHealth>;
  signOut(userId: bigint): Promise<SignOutResult>;
}
export interface CredentialVault {
  status(userId: bigint): Promise<VaultStatus>;
  open(userId: bigint): Promise<OpenedVault>;
  store(userId: bigint, username: string, password: string): Promise<'stored' | 'unavailable'>;
  rename(userId: bigint, username: string): Promise<void>;
  remove(userId: bigint): Promise<boolean>;
}
export interface CampsiteCatalog { bookableCampsite(campsiteId: bigint): Promise<BookableCampsite | null> }
export interface StoredAvailability { takenIn(campsite: BookableCampsite, window: StayWindow): Promise<boolean> }
export interface Clock { now(): number }
export interface BookingPorts { gateway: BookingGateway; vault: CredentialVault; catalog: CampsiteCatalog; stored: StoredAvailability; tenants: TenantRegistry }

// service/settings/model.ts                                          (Task 7)
export const MFA_CHALLENGE_UNKNOWN = 'mfa_challenge_unknown';
export const MFA_CHALLENGE_EXPIRED = 'mfa_challenge_expired';
export interface MfaChallenge { challengeId: string; userId: bigint; expiresAt: number }
export type PendingChallenge = { kind: 'open'; challengeId: string } | { kind: 'none' } | { kind: 'expired' };
export type ChallengeChange = 'keep' | 'end' | { issue: MfaChallenge };
export type BookingSettings = { configured: false } | { configured: true; username: string };
export interface CredentialsCommand { username: string; password: string | null }
export type SaveCredentialsOutcome = { kind: 'saved'; settings: BookingSettings } | { kind: 'password_required' } | { kind: 'vault_unavailable' } | { kind: 'wipe_failed' };
export type RemoveCredentialsOutcome = { kind: 'removed'; removed: boolean; signedOut: boolean; profileDestroyed: boolean } | { kind: 'wipe_failed' };
export type LoginOutcome =
  | { kind: 'signed_in'; challenge: 'end' }
  | { kind: 'mfa_required'; challengeId: string; expiresAt?: string; challenge: { issue: MfaChallenge } }
  | { kind: 'failed'; code: string; detail?: string; challenge: 'keep' | 'end' }
  | { kind: 'challenge_gone'; reason: 'none' | 'expired' }
  | { kind: 'unavailable'; challenge: 'keep' | 'end' }
  | { kind: 'vault_unavailable' } | { kind: 'not_configured' };
export type VerifyOutcome = { kind: 'verified' } | { kind: 'failed'; code: string; detail?: string } | { kind: 'unavailable' } | { kind: 'vault_unavailable' } | { kind: 'not_configured' };
export type RecgovSession = 'active' | 'not_logged_in' | 'expired' | 'check_failed' | 'companion_unavailable';
export type RecgovStatus = { configured: false } | { configured: true; username: string; session: RecgovSession; detail?: string; mfaPending: boolean };
export interface NotificationSettings { notificationEmail: string; slackChannel: string | null; slackConfigured: boolean; slackTokenHint: string | null }
export interface ProfilePatch { displayName?: string; theme?: UserTheme }
export interface SettingsView { profile: UserProfile & { roles: string[] }; notifications: NotificationSettings; booking: BookingSettings }

// service/settings/ports.ts                                          (Task 12; first written into the model in Task 7)
export interface RecgovPorts { gateway: BookingGateway; vault: CredentialVault; clock: Clock; mfaChallengeTtlMs: number }
export interface SettingsPorts { vault: CredentialVault; adminEmails: ReadonlySet<string>; notifications(userId: bigint, loginEmail: string): Promise<NotificationSettings>; saveProfile(userId: bigint, patch: ProfilePatch): Promise<UserProfile> }
```

---

## Part 1: `(chore): agents working in apps/web read the installed Next.js docs`

### Task 1: The Next.js agent files

**Files:**
- Modify: `apps/web/.gitignore` (drop line 3, `/AGENTS.md`); `AGENTS.md` (one line after line 12)
- Create: `apps/web/AGENTS.md`, `apps/web/CLAUDE.md`

- [ ] **Step 1: Write `apps/web/AGENTS.md`** with exactly this, then a trailing newline:

```markdown
# apps/web

Project-wide rules are in the repo root `AGENTS.md`. This file adds what is specific to the Next.js app.

`next` is hoisted to the repo root in this workspace. The bundled documentation the block below points to is at `../../node_modules/next/dist/docs/` from this directory, not in a `node_modules/` beside this file.
```

- [ ] **Step 2: Append the managed block with the installed generator**, from the repo root:
  `node -e "require('next/dist/server/lib/generate-agent-files.js').writeAgentFiles('apps/web')"`. Do not type the block.
- [ ] **Step 3: Verify the block is current.** Run `node -e "process.exit(require('next/dist/server/lib/generate-agent-files.js').hasCurrentAgentRules('apps/web') ? 0 : 1)"` — Expected: exit 0.
- [ ] **Step 4: Write `apps/web/CLAUDE.md`** as the single line `@AGENTS.md`. Remove `/AGENTS.md` from `apps/web/.gitignore`. In the root `AGENTS.md`, after the reservation-providers line, add: ``Before changes under `apps/web`, read `apps/web/AGENTS.md`.``
- [ ] **Step 5: Verify.** `git status --short` lists exactly `apps/web/.gitignore`, `apps/web/AGENTS.md`, `apps/web/CLAUDE.md`, `AGENTS.md`. `ls apps/web/../../node_modules/next/dist/docs/index.md` succeeds. `node scripts/check-server-only-imports.mjs` — Expected: `Client boundary OK`.
- [ ] **Step 6: Commit with the part's title and open the pull request.** The body says what `next dev` does with this file, that the file was ignored since #831, and that the block is regenerated by `next dev` after a `next` upgrade.

---

## Part 2: `(fix): the grid offers add to cart only where sign-in is on`

### Task 2: Sign-in off means no add-to-cart offer

**Files:**
- Create: `apps/web/src/server/auth/signInConfigured.ts`, `apps/web/src/server/auth/signInConfigured.test.ts`
- Modify: `apps/web/src/server/service/auth/meService.ts:58-67`, `apps/web/src/server/service/booking/addToCartCapability.ts`, `apps/web/src/server/service/availability/availabilityMatrixService.ts:204-211`
- Test: `apps/web/src/server/service/booking/addToCartCapability.test.ts`

**Interfaces:**
- Produces: `signInSetup(): { port: AuthPort; adminEmails: ReadonlySet<string> } | null` (reads `config.signIn` then `authPort()`; throws `ConfigError` on a partial setup, as those two do today); `signInConfigured(): boolean` (`signInSetup() !== null`, and `false` on `ConfigError`); `addToCartCapability(campsites, campground, reader, credentialed, tenants, signInOn: boolean)`.

- [ ] **Step 1: Write the failing tests.**
  - `addToCartCapability.test.ts`: `with sign-in off the matrix is unsupported, before signed_out` — `addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, null, false, tenants, false)` equals `{ state: 'unsupported' }`. Pass `true` as the sixth argument in the five existing tests.
  - `signInConfigured.test.ts`, with the `vi.resetModules()` and `vi.stubEnv` pattern of `auth/neonAuth.test.ts`: `no sign-in settings means sign-in is not configured` (all three variables unset → `false`); `a partial sign-in setup is not configured, and does not throw` (`NEON_AUTH_BASE_URL` set, the other two unset → `false`).
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- addToCartCapability signInConfigured` — Expected: FAIL.
- [ ] **Step 3: Implement.** `currentMe` calls `signInSetup()` inside its existing `try`, keeping its `ConfigError` log, and passes `setup?.port ?? null` and `setup?.adminEmails ?? NO_ADMINS` to `meFor`. `readerCapabilities` passes `signInConfigured()` as the sixth argument.
- [ ] **Step 4: Run** `npm test --workspace @roadtrip/web -- addToCartCapability signInConfigured meService availabilityMatrixService` and `npm run typecheck --workspace @roadtrip/web` — Expected: PASS. `meService.test.ts` passes unchanged.
- [ ] **Step 5: Commit with the part's title and open the pull request.** The body says production answers `signed_out` to anonymous readers today while sign-in is off.

---

## Part 3: `(refactor): booking and rec.gov settings in layers`

### Task 3: An active user, and a disabled account is signed out

**Files:**
- Create: `packages/domain/src/user.ts`, `apps/web/src/server/service/auth/model.ts`
- Modify: `packages/domain/src/index.ts`, `packages/db/src/schema/auth.ts:19-24`, `apps/web/src/server/repo/appUserRepo.ts`, `apps/web/src/server/auth/currentUser.ts`, `apps/web/src/server/service/auth/meService.ts`
- Test: `apps/web/src/server/auth/currentUser.test.ts`, `service/auth/meService.test.ts`, `repo/appUserRepo.test.ts`

**Interfaces:**
- Produces: `USER_THEMES`, `UserTheme`, `USER_STATUSES`, `UserStatus` from `@roadtrip/domain` (and still from `@roadtrip/db`); `AppUserRow.status: UserStatus`; `appUserFor(port, users): Promise<ActiveUser | null>`; `currentAppUser(): Promise<ActiveUser | null>`; the types in "Shared types".

- [ ] **Step 1: Write the failing tests.**
  - `currentUser.test.ts`: `a disabled account is no user` — `users` resolves `{ ...ROW, status: 'disabled' }` → `null`.
  - `meService.test.ts`: `a disabled account answers anonymous` — `usersReturning({ status: 'disabled' })` → `ANONYMOUS`.
  - `appUserRepo.test.ts`: `a disabled account is read with its status` — set `status = 'disabled'` on the row, then `linkedAppUser` returns `status: 'disabled'`; a new account returns `status: 'active'`.
  - Add `status: 'active'` to every `AppUserRow` fixture: `grep -rln AppUserRow apps/web/src --include='*.test.ts'` lists the files.
- [ ] **Step 2: Run** `npm run build && npm test --workspace @roadtrip/web -- currentUser meService appUserRepo` — Expected: FAIL.
- [ ] **Step 3: Implement.** Move the four names to `packages/domain/src/user.ts`; `schema/auth.ts` imports them and re-exports. `userColumns` selects `status`. `appUserFor` returns `null` unless `status === 'active'`. `meFor` returns `ANONYMOUS` for a row that is not active.
- [ ] **Step 4: Run** `npm run build && npm test --workspace @roadtrip/web -- currentUser meService appUserRepo`, `npm test --workspace @roadtrip/db` and `npm run typecheck --workspace @roadtrip/web` — Expected: PASS.
- [ ] **Step 5: Commit** `(refactor): an active user is a type, and a disabled account is signed out`.

### Task 4: The guard reads a rule table

**Files:**
- Modify: `scripts/check-server-only-imports.mjs`, `scripts/check-server-only-imports.test.mjs`

**Interfaces:**
- Produces: `export const LAYER_RULES` (starts `[]`; later tasks append); `export async function findLayerViolations(repoRoot, rules = LAYER_RULES): Promise<string[]>`; `export async function findEnvReadViolations(repoRoot): Promise<string[]>`. `main()` runs all three checks and fails on any.
- A rule is `{ name, from, forbid }` or `{ name, from, only }`. Each entry of `from`, `forbid` and `only` is a path under `apps/web/src` (a trailing `/` means the directory, otherwise one module with or without its extension), a package name (anything not starting with `server/` or `app/`; subpaths match through `isPackageSpecifier`), or a `RegExp` tested against the resolved path under `apps/web/src`.
- Violation lines: `apps/web/src/<file>:<line>: imports <specifier> (<name> may not import <entry>)`; for an `only` rule `(<name> may import only <list>)`; for the env rule `apps/web/src/<file>:<line>: reads process.env`.

- [ ] **Step 1: Write failing tests** with the file's `fixture(files)` builder:
  - `a forbidden directory import is reported with its rule name`
  - `a type-only import counts` (`import type { X } from '@/server/contract'`)
  - `an alias specifier and a relative one reach the same target`
  - `a forbidden package and its subpaths are reported` (`next/headers`, `drizzle-orm`)
  - `an only rule reports anything outside its list`
  - `a RegExp entry matches by resolved path` (`/Controller\.ts$/`)
  - `test files are exempt` (`*.test.ts` under a `from` directory)
  - `process.env is reported outside server/config.ts, and not in config.ts or a test`
  - `the shipped table passes on this repository` (`findLayerViolations(REPO_ROOT)` and `findEnvReadViolations(REPO_ROOT)` are both `[]`)
- [ ] **Step 2: Run** `node --test scripts/check-server-only-imports.test.mjs` — Expected: FAIL.
- [ ] **Step 3: Implement**, reusing `walk`, `readModules`, `specifierTarget` and `resolveSource`.
- [ ] **Step 4: Run** `node --test scripts/check-server-only-imports.test.mjs && node scripts/check-server-only-imports.mjs` — Expected: PASS, 30 tests.
- [ ] **Step 5: Commit** `(ci): the import guard reads a table of layer rules`.

### Task 5: The booking gateway: model, ports, relay mapper, client

**Files:**
- Create: `apps/web/src/server/service/booking/model.ts`, `service/booking/ports.ts`, `mapper/relay/bookingRelayWire.ts`, `mapper/relay/bookingRelayWire.test.ts`
- Modify: `apps/web/src/server/client/bookingRelay.ts`, `client/bookingRelay.test.ts`, `repo/campsiteCatalogRepo.ts` (`BookableCampsite` now imported from the model and re-exported), `repo/bookingCredentialsRepo.ts` (`RECGOV_PROVIDER` now imported from the model and re-exported), `auth/currentUser.test.ts` (drop the `profileIdFor` test), `scripts/check-server-only-imports.mjs` (rules)

**Interfaces:**
- Produces, in `mapper/relay/bookingRelayWire.ts`:
  - `type RelayExchange = { kind: 'answered'; status: number; body: unknown } | { kind: 'fault'; fault: GatewayFault }`
  - `profileRequest(userId: bigint): BookingRelayProfileRequest`, `loginRequest(userId, username, password): BookingRelayLoginRequest`, `mfaRequest(userId, challengeId, code): BookingRelayMfaRequest`, `atcRequest(userId, vendorSiteId, window: StayWindow): BookingRelayAtcRequest`. The profile id is `n:<userId>` and is rendered nowhere else.
  - `decodeHold`, `decodeLogin`, `decodeAction`, `decodeHealth`, `decodeSignOut`, each `(exchange: RelayExchange) => <its result type>`.
- Produces, in `client/bookingRelay.ts`: `postRelay(config: BookingConfig, path: BookingRelayPath, body: object, timeoutMs = BOOKING_RELAY_CLIENT_TIMEOUT_MS): Promise<RelayExchange>`; `bookingRelayGateway(config: BookingConfig): BookingGateway`; `unconfiguredBookingGateway: BookingGateway`. The old `relay` and `RelayResult` stay until Task 12.
- `postRelay` answers `answered` for any response whose body parses as JSON, with its status. Faults: abort by timeout → `unanswered`; any other throw → `unreachable`; 3xx → `redirected`; 401 → `unauthorized`; a body that is not JSON → `malformed`. It logs each fault once: `console.warn('booking gateway:', fault)`. The gateway logs a decoder's `malformed` or `rejected` the same way.
- Decoding today's flat envelope:

| Operation | Rule |
|---|---|
| all | A fault passes through as `unavailable { fault }` (health: `unavailable {}`). A body that is not an object with a boolean `ok` is `malformed` when the status is 2xx, else `rejected`. A refusal needs a string `code`, else `malformed`; its category is the envelope's when it is one of the three, else `upstream`. |
| hold | `ok` with `cart_url` → `held`; `ok` without → `malformed`; status 504 → `timed_out { refusal, cartUrl? }`; else `blocked`. |
| login, mfa | `ok` → `signed_in`; code `mfa_required` with `challenge_id` → `mfa_required` (without → `malformed`); status 504 → `timed_out`; else `failed`. |
| refresh, verify | `ok` → `ok`; status 504 → `timed_out`; else `failed`. |
| health | `health` of `active`, `never_logged_in`, `inactive { code }`, `check_failed { code, detail }`, `unavailable { code, detail }`; a missing or unknown `health` → `unavailable { code, detail }` from the envelope. |
| signout | `profile_destroyed === true` → `wiped { signedOut: signed_out === true }`; status 504 → `timed_out`; else `failed { signedOut, refusal }`. |

- [ ] **Step 1: Write failing tests.**
  - `bookingRelayWire.test.ts`: `a profile request names the user with the n: prefix` (`profileRequest(7n)` equals `{ profile_id: 'n:7' }`); `an atc request carries the site and the window` (equals `{ profile_id: 'n:7', vendor_site_id: '300', start_date: '2026-12-10', end_date: '2026-12-11' }`); `a login and an mfa request carry their fields`; one `test.each` per decoder over every row of the table above, including `{}` at 200 → `malformed`, `{ error: 'bad_request' }` at 400 → `rejected`, `{ ok: true }` on hold → `malformed`, `{ ok: false }` with no code → `malformed`, `{ ok: false, code: 'x', category: 'nonsense' }` → category `upstream`, and a 504 `{ ok: false, code: 'relay_timeout', category: 'retry_later', detail: 'd', cart_url: 'u' }` → `timed_out` carrying the refusal and `cartUrl: 'u'`.
  - `bookingRelay.test.ts`: keep the seven tests and the fetch assertions, calling `postRelay`. Expected results become: success → `{ kind: 'answered', status: 200, body }`; the 504 envelope → `{ kind: 'answered', status: 504, body }`; 401 → fault `unauthorized`; the HTML 524 page → `malformed`; a 200 `{ hello: 'world' }` → `answered` (its test is renamed `a JSON body of any shape is handed to the decoder`); 302 → `redirected`; `TimeoutError` → `unanswered`; `TypeError` → `unreachable`. Add `a fault is logged once with its kind` and `the unconfigured gateway answers not_configured for every operation, and unavailable for health`.
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- bookingRelay` — Expected: FAIL.
- [ ] **Step 3: Implement** the model, the ports, the mapper and the client.
- [ ] **Step 4: Add guard rules:** `model` (`from` the three `model.ts` files and `ports.ts`; `only` `@roadtrip/domain` and those four files); `relay mapper` (`from` `server/mapper/relay/`; `forbid` `server/repo/`, `server/wiring/`); `client` (`from` `server/client/bookingRelay.ts`; `forbid` `server/repo/`, `/Controller\.ts$/`).
- [ ] **Step 5: Run** `npm test --workspace @roadtrip/web -- bookingRelay currentUser bookableCampsite`, the typecheck and the guard — Expected: PASS.
- [ ] **Step 6: Commit** `(refactor): a booking gateway with named results over today's relay`.

### Task 6: The seam every route uses

**Files:**
- Create: `apps/web/src/server/mapper/http/httpAnswer.ts`, `mapper/http/signedIn.ts`, `mapper/http/signedIn.test.ts`

**Interfaces:**
- Produces: `interface HttpAnswer<T> { status: number; body: T | ApiErrorSchema; cookie?: string }`; `STATUS_OK` 200, `STATUS_BAD_REQUEST` 400, `STATUS_UNAUTHORIZED` 401, `STATUS_FORBIDDEN` 403, `STATUS_CONFLICT` 409, `STATUS_UNPROCESSABLE` 422, `STATUS_BAD_GATEWAY` 502, `STATUS_SERVICE_UNAVAILABLE` 503; `okAnswer<T>(body: T, cookie?: string): HttpAnswer<T>`; `errorAnswer(status: number, code: string, detail?: string): HttpAnswer<never>`; `type Parsed<T> = { kind: 'ok'; value: T } | { kind: 'invalid'; answer: HttpAnswer<never> }`.
- Produces: `signedIn(handler: (input: { user: ActiveUser; body: unknown; request: Request | undefined }) => Promise<HttpAnswer<unknown>>): (request?: Request) => Promise<Response>`. It answers 401 `{ error: 'unauthenticated' }` when `currentAppUser()` is `null`. Every response carries `cache-control: private, no-store`; `cookie` becomes one `set-cookie` header.

- [ ] **Step 1: Write failing tests** in `signedIn.test.ts`, mocking `@/server/auth/currentUser` to supply `currentAppUser`:
  - `an anonymous caller gets 401 unauthenticated, never cached, and the handler is not called`
  - `the answer's status, body and cookie reach the response, never cached`
  - `a JSON body reaches the handler parsed, and an unreadable one as undefined`
  - `a call with no request still runs the handler`
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- signedIn` — Expected: FAIL.
- [ ] **Step 3: Implement**, reading the body with `jsonBody` from `server/requestBody.ts`.
- [ ] **Step 4: Add the guard rule** `http mapper` (`from` `server/mapper/http/`; `forbid` `server/repo/`, `server/client/`, `server/db`, `server/appDb`, `drizzle-orm`, `@roadtrip/db`, `server/wiring/`). Run the tests, typecheck and guard — Expected: PASS.
- [ ] **Step 5: Commit** `(refactor): one wrapper turns an answer into a response`.

### Task 7: The credential vault, the identity row mapper, and settings rows

**Files:**
- Create: `apps/web/src/server/service/settings/model.ts`, `repo/credentialVault.ts`, `mapper/row/bookingIdentityRow.ts`, `mapper/row/bookingIdentityRow.test.ts`
- Modify: `repo/campsiteCatalogRepo.ts` (four hand decodes at l.57-59, l.105-107, l.145-147, l.151-153), `repo/userSettingsRepo.ts`, `repo/appUserRepo.ts` (`ProfilePatch` from the model, re-exported), `auth/mfaChallengeCookie.ts` (`MfaChallenge` and the two codes from the model, re-exported), `service/settings/settingsService.ts` (builds its `NotificationsDto` from the row inline; Task 10 deletes the file)
- Test: `repo/bookingCredentialsRepo.test.ts`

**Interfaces:**
- Produces: `credentialVault(db: Db, key: Buffer | null): CredentialVault`. `status` is `locked` when a row exists and `key` is `null`; `open` is `unavailable` when `key` is `null`, before it looks for a row; `store` is `'unavailable'` when `key` is `null` and writes nothing.
- Produces: `bookingIdentityOf(row: Record<string, unknown>, decoder: ColumnDecoder, prefix = ''): BookingIdentityRow`, reading `<prefix>booking_provider`, `<prefix>booking_provider_ref`, `<prefix>booking_aliases`.
- Produces: `notificationsFor(db, userId, loginEmail): Promise<NotificationSettings>`.

- [ ] **Step 1: Write failing tests.**
  - `bookingCredentialsRepo.test.ts`: `the vault reads none, then stored after a store, and opens the password it sealed`; `without a key a stored row is locked, opens as unavailable, and cannot store`; `rename keeps the sealed password, and remove says whether there was a row`. The two `notificationsFor` tests keep their facts in the row's shape: `{ notificationEmail: 'camper@example.com', slackChannel: null, slackConfigured: false, slackTokenHint: null }` and `{ notificationEmail: 'alerts@example.com', slackChannel: '#camp', slackConfigured: true, slackTokenHint: 'abcd' }`.
  - `bookingIdentityRow.test.ts`: `reads the three columns`; `reads them under a prefix` (`cg_`); `missing or malformed aliases read as none`.
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- bookingCredentialsRepo bookingIdentityRow` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Add guard rules:** `row mapper` (`from` `server/mapper/row/`; `forbid` `server/contract`); `repository` (`from` `server/repo/credentialVault.ts`, `server/repo/bookingCredentialsRepo.ts`, `server/repo/userSettingsRepo.ts`; `forbid` `server/contract`, `/Controller\.ts$/`, `server/mapper/http/`, `server/client/`, `server/wiring/`). Run `npm test --workspace @roadtrip/web -- bookingCredentialsRepo bookingIdentityRow bookableCampsite mfaChallengeCookie settingsService recgov`, typecheck and guard — Expected: PASS.
- [ ] **Step 5: Commit** `(refactor): a credential vault, and rows that are not wire shapes`.

### Task 8: Add to cart in layers

**Files:**
- Create: `service/booking/addToCartController.ts`, `service/booking/addToCartController.test.ts`, `mapper/http/addToCartHttp.ts`, `mapper/http/addToCartHttp.test.ts`, `client/storedAvailability.ts`, `client/storedAvailability.test.ts`, `wiring/bookingPorts.ts`
- Modify: `apps/web/src/app/api/booking/add-to-cart/route.ts`, `route.test.ts`
- Delete: `service/booking/addToCartService.ts`, `service/booking/addToCartService.test.ts`

**Interfaces:**
- Consumes: Tasks 3, 5, 6, 7.
- Produces: `addToCart(user: ActiveUser, command: AddToCartCommand, ports: BookingPorts): Promise<AddToCartOutcome>`, in this order: catalog and `recgovTargetFor` (else `unsupported_target`); vault status `stored` (else `credentials_required`); `stored.takenIn` (then `not_available`, and no hold); `gateway.hold`. `held` → `held`; `blocked` and `timed_out` → `refused`, keeping `cartUrl`; `unavailable` → `unavailable`.
- Produces: `parseAddToCart(body: unknown): Parsed<AddToCartCommand>` (`invalid_body`, then `invalid_window` when `stayWindow` is `null`); `addToCartAnswer(outcome: AddToCartOutcome): HttpAnswer<AddToCartResponseDto>`.
- Produces: `storedAvailability(egress: EgressFetch | null, clock: Clock, freshnessMaxAgeMs: number): StoredAvailability`, holding today's `knownTaken` rule.
- Produces: `bookingPorts(): BookingPorts` in `wiring/bookingPorts.ts`, holding `DEFAULT_FRESHNESS_MAX_AGE_MS`. Gateway: `bookingRelayGateway(config.booking)`, or `unconfiguredBookingGateway` when `config.booking` is `null`. Vault: `credentialVault(appDb(), config.bookingCipherKey)`. Catalog: `findBookableCampsite(await serverDb(), id)`. Stored: `storedAvailability(configuredEgress(AVAILABILITY_STORED_TIMEOUT_MS), { now: Date.now }, freshness)`. Tenants: `shippedTenantRegistry()`. `wiring/recgovPorts.ts` and `wiring/settingsPorts.ts` build their gateway and vault the same way.
- The route becomes the declaration in the spec's section 2, with `maxDuration = 120`, returning `command.answer` for an invalid parse.

| Outcome | Status | Body |
|---|---|---|
| `held` | 200 | `{ status: 'completed', cart_url, provider: 'recgov', provider_display }` |
| `unsupported_target` | 422 | `{ error: 'unsupported_target' }` |
| `credentials_required` | 403 | `{ error: 'credentials_required', provider, provider_display }` |
| `not_available` | 409 | `{ error: 'not_available', provider, provider_display }` |
| `refused` | 403 `caller_action`, 409 `retry_later`, 502 `upstream` | `{ error: refusal.code, detail?, provider, provider_display }` |
| `unavailable` | 503 | `{ error: 'booking_unavailable', provider, provider_display }` |

- [ ] **Step 1: Move the sixteen cases of `addToCartService.test.ts`**, expectations unchanged, into tests that run `parseAddToCart`, `addToCart` with fake ports, then `addToCartAnswer`. The old relay fixtures become gateway results:

| Old `relay` fixture | Fake `gateway.hold` resolves |
|---|---|
| `HELD` | `{ kind: 'held', cartUrl: 'https://www.recreation.gov/cart' }` |
| `{ ok: false, code: 'profile_busy', category, detail: 'd' }` | `{ kind: 'blocked', refusal: { category, code: 'profile_busy', detail: 'd' } }` |
| status 504, `relay_timeout`, `retry_later` | `{ kind: 'timed_out', refusal: { category: 'retry_later', code: 'relay_timeout' } }` |
| `{ ok: false, code: 'relay_not_json' }` | `{ kind: 'unavailable', fault: 'malformed' }` |
| `relay: null` | `unconfiguredBookingGateway` |

  `relay` called with `('atc', {...})` becomes `gateway.hold` called with `(7n, '300', { startDate: '2026-12-10', endDate: '2026-12-11' })`. `hasCredentials: false` becomes a vault whose status is `none`. The three stored-cell cases drive the real `storedAvailability` over a fake egress, so their cells and their expectations stand; `storedAvailability.test.ts` holds the cell-level cases (`stale, available, other-site and checkout-night cells are not evidence`, `a failed stored read is not evidence`, `no egress configured is not evidence`).
- [ ] **Step 2: Add the Review Focus cases:** `a locked vault is 403 credentials_required` (status `locked`); `a hold result of unavailable for any fault is 503 booking_unavailable` (`test.each` over the seven faults).
- [ ] **Step 3: Rewrite `route.test.ts`** mocking `@/server/auth/currentUser` (`currentAppUser`) and `@/server/wiring/bookingPorts`. Keep `the route allows the relay its full timeout` and `an anonymous caller gets 401 and nothing is held`; add `the answer is never cached`. Delete `the service answer is the response`.
- [ ] **Step 4: Run** `npm test --workspace @roadtrip/web -- addToCart storedAvailability add-to-cart` — Expected: FAIL, modules missing.
- [ ] **Step 5: Implement**, then delete the service and its test.
- [ ] **Step 6: Add guard rules:** `controller` (`from` `server/service/booking/addToCartController.ts`, `server/service/booking/bookingTarget.ts`; `forbid` `server/contract`, `server/config`, `server/db`, `server/appDb`, `drizzle-orm`, `server/client/`, `server/repo/`, `server/mapper/`, `server/wiring/`, `next`); `route` (`from` `app/api/booking/`; `forbid` `server/repo/`, `server/client/`). Add `server/client/storedAvailability.ts` to the `client` rule. Run the tests, typecheck and guard — Expected: PASS.
- [ ] **Step 7: Commit** `(refactor): add to cart is a parse, a controller and an answer`.

### Task 9: Rec.gov credentials, sign-in and MFA in layers

**Files:**
- Create: `service/settings/recgovCredentialController.ts`, `service/settings/recgovCredentialController.test.ts`, `mapper/http/recgovHttp.ts`, `mapper/http/recgovHttp.test.ts`, `wiring/recgovPorts.ts`
- Modify: the five route files under `apps/web/src/app/api/settings/recgov/` and `routes.test.ts`
- Delete: `service/settings/recgovCredentialService.ts`, its test, `apps/web/src/server/serviceResponse.ts`

**Interfaces:**
- Produces, in the controller: `saveCredentials(user, command: CredentialsCommand, ports: RecgovPorts): Promise<SaveCredentialsOutcome>`; `removeCredentials(user, ports): Promise<RemoveCredentialsOutcome>`; `signIn(user, ports): Promise<LoginOutcome>`; `submitMfa(user, code: string, pending: PendingChallenge, ports): Promise<LoginOutcome>`; `verifySession(user, ports): Promise<VerifyOutcome>`; `recgovStatus(user, pending: PendingChallenge, ports): Promise<RecgovStatus>`.
- Produces, in the mapper: `interface ChallengeCodec { open(cookie: string | undefined, userId: bigint): PendingChallenge; seal(challenge: MfaChallenge): string | null }`; `challengeCodec(key: Buffer | null, clock: Clock): ChallengeCodec` (no key: `open` is `none`, `seal` is `null`; `seal` returns the whole `Set-Cookie` value with `Max-Age` of `max(0, round((expiresAt - now) / 1000))`); `pendingChallenge(request: Request | undefined, user: ActiveUser, codec): PendingChallenge`; `parseCredentials(body): Parsed<CredentialsCommand>`; `parseMfaCode(body): Parsed<string>`; `saveAnswer`, `removeAnswer`, `verifyAnswer`, `statusAnswer`, and `loginAnswer(outcome: LoginOutcome, codec: ChallengeCodec)`.
- Produces: `recgovPorts(): RecgovPorts & { challenges: ChallengeCodec }` in `wiring/recgovPorts.ts`, holding `DEFAULT_MFA_CHALLENGE_TTL_MS`.
- Controller rules, each as the service decides today:
  - **save:** `none` and no password → `password_required`. `locked` and a password → `vault_unavailable`, before any wipe. A row whose username differs → `gateway.signOut`; `unavailable { not_configured }` or `wiped` goes on, anything else is `wipe_failed`. Then `store` (`'unavailable'` → `vault_unavailable`) or `rename`. The answer's settings come from a fresh `status`: `stored` → configured.
  - **remove:** the same wipe rule, then `vault.remove`. `signedOut` and `profileDestroyed` are both `false` when the gateway is not configured.
  - **signIn:** `vault.open` `unavailable` → `vault_unavailable`, `none` → `not_configured`. `gateway.refresh`: `unavailable { not_configured }` → `unavailable` keeping the challenge; `ok` → `signed_in`; anything else goes on to `gateway.signIn`.
  - **a `LoginResult`:** `signed_in` → `end`. `mfa_required` → issue a challenge expiring at the earlier of the relay's `expiresAt` (when it parses) and `now + mfaChallengeTtlMs`. `failed` → `end` when the code is `mfa_invalid`, `login_failed`, `captcha_required`, `mfa_challenge_unknown` or `mfa_challenge_expired`, else `keep`. `timed_out` → `failed` with the refusal's code, `keep`. `unavailable` → `unavailable`, `keep`.
  - **submitMfa:** `none` → `challenge_gone` with reason `none`; `expired` → `challenge_gone` with reason `expired`; neither calls the gateway, and the mapper answers each with its code, its detail and a cleared cookie. `unavailable { not_configured }` from `gateway.submitMfa` → `unavailable`, `end`.
  - **verifySession:** the same vault rule as signIn; `ok` → `verified`; `failed` and `timed_out` → `failed` with the refusal's code and detail; `unavailable` → `unavailable`.
  - **recgovStatus:** vault status other than `stored` → `{ configured: false }`. Session: `active` → `active`; `never_logged_in` → `not_logged_in`; `inactive` → `expired` with detail `code ?? 'recgov_session_lapsed'`; `check_failed` → `check_failed` with detail `code ?? detail`; `unavailable` → `companion_unavailable` with detail `code ?? detail`. `mfaPending` is `pending.kind === 'open'`.

| Outcome | Status | Body | Cookie |
|---|---|---|---|
| save `saved` | 200 | `{ recgov_configured: true, recgov_username }` or `{ recgov_configured: false }` | |
| `password_required` | 400 | `invalid_field`, `FIRST_PASSWORD_DETAIL` | |
| `vault_unavailable` (any route) | 503 | `encryption_unavailable`, `ENCRYPTION_DETAIL` | |
| `not_configured` (login, verify) | 409 | `recgov_not_configured`, `NOT_CONFIGURED_DETAIL` | |
| `wipe_failed` | 502 | `recgov_profile_wipe_failed`, `WIPE_FAILED_DETAIL` | |
| remove `removed` | 200 | `{ removed, stranded_atc_watches: 0, companion_signed_out, profile_destroyed }` | |
| login `signed_in` | 200 | `{ status: 'ok' }` | clear |
| login `mfa_required` | 200 | `{ status: 'mfa_required', challenge_id, expires_at? }` | `codec.seal`, or none |
| login `failed` | 200 | `{ status: 'failed', error: code, detail? }`; the two challenge codes take `NO_CHALLENGE_DETAIL` and `EXPIRED_CHALLENGE_DETAIL` | clear on `end` |
| login `unavailable` | 200 | `{ status: 'failed', error: 'companion_unavailable' }` | clear on `end` |
| verify | 200 | `{ ok: true }`; `{ ok: false, error: code, detail? }`; `{ ok: false, error: 'companion_unavailable' }` | |
| status | 200 | `{ configured: false, session: 'not_configured', mfa_pending: false }` or `{ configured: true, username, session, detail?, mfa_pending }` | |

  Parse failures are 400: `invalid_body` with `INVALID_BODY_DETAIL`, or `invalid_field` with `BLANK_USERNAME_DETAIL`.

- [ ] **Step 1: Move the twenty-eight cases of `recgovCredentialService.test.ts`**, expectations unchanged, into tests that run the parser, the controller with a fake gateway and an in-memory fake vault (`memoryVault(row: { username: string; password: string } | null, keyed = true)`), then the answer function with a real `challengeCodec(KEY, clock)`. Fixture translation:

| Old `relay` fixture | Fake gateway result |
|---|---|
| `answer({ code: 'destroy_failed', profile_destroyed: false })` | `signOut` → `{ kind: 'failed', signedOut: false, refusal: { category: 'upstream', code: 'destroy_failed' } }` |
| signout answer with `signed_out: true, profile_destroyed: true` | `signOut` → `{ kind: 'wiped', signedOut: true }` |
| `{ ok: false, code: 'unreachable' }` | `{ kind: 'unavailable', fault: 'unreachable' }` (health: `{ kind: 'unavailable' }`) |
| `relay: null` | `unconfiguredBookingGateway` |
| refresh `ok` / code `recgov_refresh_failed` | `{ kind: 'ok' }` / `{ kind: 'failed', refusal }` |
| `mfa_required`, `ch-9`, `2026-12-01T00:02:00Z` | `{ kind: 'mfa_required', challengeId: 'ch-9', expiresAt: '2026-12-01T00:02:00Z' }` |
| `profile_busy` `retry_later`; `mfa_invalid` `caller_action` | `{ kind: 'failed', refusal }` |
| status 504 `relay_timeout` | `{ kind: 'timed_out', refusal: { category: 'retry_later', code: 'relay_timeout' } }` |
| `health: 'inactive'`; `health: 'check_failed', code: 'x'` | `{ kind: 'inactive' }`; `{ kind: 'check_failed', code: 'x' }` |

  Call assertions become `gateway.signOut(7n)`, `gateway.refresh(7n)`, `gateway.signIn(7n, 'rec@example.com', 'hunter2')`, `gateway.submitMfa(7n, 'ch-9', '123456')`. `cookie: 'clear'` becomes the answer's `cookie` equal to `mfaClearCookie()`; `{ maxAgeSeconds: 120 }` becomes a `cookie` containing `Max-Age=120`; `undefined` stays `undefined`. "The stored cipher decrypts to hunter2" becomes the fake vault holding `hunter2`.
- [ ] **Step 2: Add the Review Focus cases:**
  - `a save with a password against a locked vault is 503 and wipes nothing`
  - `a rename against a locked vault succeeds and still reads not configured`
  - `a relay expires_at that does not parse, or lies past the ceiling, gives Max-Age 300` (`'garbage'`, `'2099-01-01T00:00:00Z'`)
  - `with no key, a cookie opens as none: status says mfa_pending false and a submit answers mfa_challenge_unknown and clears it`
  - `a malformed answer on sign-in is companion_unavailable and keeps the cookie; on sign-out it is recgov_profile_wipe_failed`
- [ ] **Step 3: Rewrite `routes.test.ts`** mocking `@/server/auth/currentUser` and `@/server/wiring/recgovPorts` with fake ports and a real codec. Keep the 401, cookie-attribute, cookie-in-and-clear, status `cache-control` and both `maxDuration` tests, driving them through fake gateway results. Delete `a service refusal keeps its status and body`.
- [ ] **Step 4: Run** `npm test --workspace @roadtrip/web -- recgov` — Expected: FAIL.
- [ ] **Step 5: Implement.** Each route keeps its `maxDuration` (login 200, the rest 120) and becomes one `signedIn(...)` call. `recgovHttp.ts` is the only importer of `auth/mfaChallengeCookie.ts`. Delete the service, its test and `serviceResponse.ts`.
- [ ] **Step 6: Add guard rules:** add `/^server\/service\/settings\/.*Controller\.ts$/` to the `controller` rule's `from`; add `app/api/settings/` to the `route` rule. Run tests, typecheck and guard — Expected: PASS.
- [ ] **Step 7: Commit** `(refactor): rec.gov credentials and sign-in are controllers behind mappers`.

### Task 10: Profile settings in layers

**Files:**
- Create: `service/settings/settingsController.ts`, `service/settings/settingsController.test.ts`, `mapper/http/settingsHttp.ts`, `mapper/http/settingsHttp.test.ts`, `wiring/settingsPorts.ts`
- Modify: `apps/web/src/app/api/settings/route.ts`, `route.test.ts`, `profile/route.ts`, `profile/route.test.ts`, `auth/currentUser.ts` (drop `rolesFor`, `profileIdFor`, `requireAppUser` and their constants)
- Delete: `service/settings/settingsService.ts`, its test

**Interfaces:**
- Produces: `settingsView(user: ActiveUser, ports: SettingsPorts): Promise<SettingsView>`; `updateProfile(user: ActiveUser, patch: ProfilePatch, ports: SettingsPorts): Promise<SettingsView>`. Booking is configured only when the vault status is `stored`. `roles` is `['admin']` for a verified email in `adminEmails`, compared lower-case.
- Produces: `parseProfilePatch(body): Parsed<ProfilePatch>` (400 `invalid_body` or `invalid_field` with today's three detail strings); `settingsAnswer(view: SettingsView): HttpAnswer<SettingsResponseDto>`; `notificationsDto(row: NotificationSettings): NotificationsDto` (a `null` field is omitted); `settingsPorts(): SettingsPorts`.

- [ ] **Step 1: Move the nine cases of `settingsService.test.ts`**, expectations unchanged, into tests of parse, controller with fake ports, answer. `cipherKey: null` with a row becomes a vault status of `locked`. The fake `notifications` resolves the row shape; the expected body is still `{ notification_email: 'camper@example.com', slack_configured: false }`.
- [ ] **Step 2: Add to `settingsHttp.test.ts`:** `a notifications row with a channel and a hint shows both, and null fields are left out` (the two bodies asserted in `bookingCredentialsRepo.test.ts` before Task 7).
- [ ] **Step 3: Rewrite the two route tests** mocking `@/server/auth/currentUser` and `@/server/wiring/settingsPorts`. Keep the 401s, `a signed-in caller gets their settings, never cached`, `a refused field is 400 invalid_field with its detail` (now through the real parser, body `{ theme: 'neon' }`) and `an accepted update answers the settings`. Add `the 400 is never cached`.
- [ ] **Step 4: Run** `npm test --workspace @roadtrip/web -- settings` — Expected: FAIL. Implement; delete the service and its test. Run tests, typecheck, guard — Expected: PASS.
- [ ] **Step 5: Commit** `(refactor): profile settings are a controller behind a mapper`.

### Task 11: The grid's capability comes through wiring

**Files:**
- Create: `mapper/http/capabilityHttp.ts`, `mapper/http/capabilityHttp.test.ts`, `wiring/readerStanding.ts`
- Modify: `service/booking/addToCartCapability.ts`, its test, `service/availability/availabilityMatrixService.ts:204-211`

**Interfaces:**
- Produces: `addToCartCapability(campsites: readonly BookingIdentitySource[], campground: BookingIdentitySource, reader: ReaderStanding, tenants: TenantRegistry): AddToCartCapability` (`sign_in_off` → `unsupported`, as is an empty or mixed matrix); `capabilityDto(capability: AddToCartCapability): AddToCartCapabilityDto` (adds `provider: 'recgov'` and `provider_display`); `readerStanding(ports: ReaderPorts): Promise<ReaderStanding>` (`ports.signInConfigured()`, then `ports.currentUser()`, then `ports.vault.status`: only `stored` is `ready`), with `ReaderPorts` in `service/booking/ports.ts` and the live ports in `wiring/readerStanding.ts`.

- [ ] **Step 1: Re-point the six capability tests**, running `capabilityDto(addToCartCapability(...))` so each expected DTO stands. `(READER, true)` becomes `'ready'`, `(READER, false)` `'no_credentials'`, `(null, false)` `'signed_out'`, sign-in off `'sign_in_off'`.
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- addToCartCapability capabilityHttp` — Expected: FAIL. Implement. `readerCapabilities` keeps its `try` and its log and no longer imports `currentAppUser`, `config`, `appDb` or the credentials repository for this.
- [ ] **Step 3: Add guard rules:** `server/service/booking/addToCartCapability.ts` joins the `controller` rule; a rule `availability matrix` (`from` `server/service/availability/availabilityMatrixService.ts`; `forbid` `server/repo/bookingCredentialsRepo`). Run `npm test --workspace @roadtrip/web -- addToCartCapability capabilityHttp availabilityMatrixService`, typecheck, guard — Expected: PASS.
- [ ] **Step 4: Commit** `(refactor): the grid asks wiring where the reader stands`.

### Task 12: Finish the layers pull request

**Files:**
- Modify: `apps/web/src/server/client/bookingRelay.ts` (delete `relay`, `RelayResult`, `RelayFailureCode`, `isEnvelope` and the six `RELAY_*_CODE` constants), `apps/web/AGENTS.md`, `scripts/check-server-only-imports.test.mjs`

- [ ] **Step 1: Delete the leftovers** and run `grep -rn "requireAppUser\|profileIdFor\|serviceResponse\|RelayResult\|liveAddToCartDeps\|liveRecgovDeps" apps/web/src` — Expected: no output.
- [ ] **Step 2: Add the design principles to `apps/web/AGENTS.md`**, above the managed block: the fenced block in the spec's section 8, verbatim. Re-run Task 1's Step 3 — Expected: exit 0.
- [ ] **Step 3: Add a guard test** `the shipped table names every rule the spec lists`: `LAYER_RULES.map((rule) => rule.name).sort()` equals `['availability matrix', 'client', 'controller', 'http mapper', 'model', 'relay mapper', 'repository', 'route', 'row mapper']`.
- [ ] **Step 4: Run** `make test` and `npm run build --workspace @roadtrip/web` — Expected: exit 0.
- [ ] **Step 5: Squash to one commit with the part's title and open the pull request.** The body lists the four behaviour changes, says no Kotlin and no contract file changed (`git diff --stat master -- backend frontend/src/api` is empty), and names the seven items under "Decided while planning".

---

## Part 4: `(refactor): the booking relay names its results`

Paths in Task 13 are under `backend/src/main/kotlin/ca/floo/roadtrip`.

### Task 13: One result per operation on the Kotlin relay

**Files:**
- Modify: `model/api/BookingRelayDtos.kt`, `service/booking/BookingRelayController.kt`, `route/internal/BookingRelayRoutes.kt`, `model/api/ApiContract.kt:360-366, 616-627`, `frontend/src/api/generated/api-types.ts` (through `make api-types` only)
- Create: `route/internal/BookingRelayWire.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/BookingRelayRoutesTest.kt`

**Interfaces:**
- Produces DTOs, all `@Serializable`, enum entries carrying `@SerialName` of their lower-case names, multi-word fields carrying `@SerialName` in snake case:
  - `enum BookingRelayFailureCategory { CALLER_ACTION, RETRY_LATER, UPSTREAM }`; `BookingRelayFailureDto(code: String, category: BookingRelayFailureCategory, detail: String? = null)`
  - `enum BookingRelayHoldOutcome { HELD, BLOCKED, TIMED_OUT }`; `BookingRelayHoldDto(outcome, cartUrl: String? = null, failure: BookingRelayFailureDto? = null)`
  - `enum BookingRelayLoginOutcome { SIGNED_IN, MFA_REQUIRED, FAILED, TIMED_OUT }`; `BookingRelayLoginDto(outcome, challengeId: String? = null, expiresAt: String? = null, failure: BookingRelayFailureDto? = null)`
  - `enum BookingRelayActionOutcome { OK, FAILED, TIMED_OUT }`; `BookingRelayActionDto(outcome, failure: BookingRelayFailureDto? = null)`
  - `enum BookingRelaySession { ACTIVE, NEVER_LOGGED_IN, INACTIVE, CHECK_FAILED, UNAVAILABLE }`; `BookingRelayHealthDto(session, code: String? = null, detail: String? = null)`
  - `enum BookingRelaySignOutOutcome { WIPED, FAILED, TIMED_OUT }`; `BookingRelaySignOutDto(outcome, signedOut: Boolean, profileDestroyed: Boolean, failure: BookingRelayFailureDto? = null)`
  - `BookingRelayResponseDto` is deleted.
- Produces, in the controller: `internal sealed interface RelayAnswer<out T> { data class Answered<T>(val value: T) : RelayAnswer<T>; data object TimedOut : RelayAnswer<Nothing> }`; `internal data class ProfileWipe(val signedOut: Boolean, val destroyed: CompanionActionResult)`; `health(profileId): RelayAnswer<CompanionSessionHealth>`; `refresh` and `verify(profileId): RelayAnswer<CompanionActionResult>`; `login(profileId, username, password)` and `completeMfa(profileId, challengeId, code): RelayAnswer<CompanionLoginResult>`; `signout(profileId): RelayAnswer<ProfileWipe>`; `atc(profileId, vendorSiteId, arrival: LocalDate, checkout: LocalDate): RelayAnswer<ProfileHoldOutcome>`. The controller imports no DTO.
- Produces, in `BookingRelayWire.kt`: `internal fun RelayAnswer<ProfileHoldOutcome>.toHoldDto()`, `RelayAnswer<CompanionLoginResult>.toLoginDto()`, `RelayAnswer<CompanionActionResult>.toActionDto()`, `RelayAnswer<CompanionSessionHealth>.toHealthDto()`, `RelayAnswer<ProfileWipe>.toSignOutDto()`, each an exhaustive `when`. It owns `RELAY_TIMEOUT_CODE`, `RELAY_TIMEOUT_DETAIL` and the category mapping.
- Mapping: a failure's category is `recGovFailureCategory(code)`, or the outcome's own for `Blocked`. `TimedOut` is `failure(relay_timeout, RETRY_LATER, RELAY_TIMEOUT_DETAIL)`; on `atc` it also carries `cartUrl = RECGOV_CART_URL`; on sign-out `signedOut = false, profileDestroyed = false`; on health it is `session = UNAVAILABLE, code = relay_timeout` with no detail. `Unavailable(detail)` is `UNAVAILABLE` with `code = companion_unavailable`. `MfaRequired` carries `challengeId` and `expiresAt` and no failure.
- Routes answer 200 for `Answered` and 504 for `TimedOut`; 400 and 401 are unchanged. `bookingRelayEndpoint(segment, request, result: KClass<*>)` names the result at 200 and 504.

- [ ] **Step 1: Re-point the nine tests that read fields** onto per-operation decoders (`hold()`, `login()`, `action()`, `health()`, `signOut()`):
  - 5: `outcome == HELD`, `cartUrl == RECGOV_CART_URL`
  - 6: `outcome == BLOCKED`, `failure.code == "profile_busy"`, `failure.category == RETRY_LATER`
  - 7: `outcome == BLOCKED`, `failure.category == CALLER_ACTION`
  - 8: `outcome == MFA_REQUIRED`, `challengeId`, `expiresAt`, `failure == null`
  - 9: `outcome == SIGNED_IN`
  - 10: `session == INACTIVE`, `code == SESSION_LAPSED`
  - 11: refresh `outcome == OK`; verify `outcome == FAILED`, `failure.detail == "no session"`
  - 12: `outcome == WIPED`, `signedOut == false`, `profileDestroyed == true`
  - 13: `outcome == FAILED`, `profileDestroyed == false`, `failure.code == "profile_destroy_failed"`
  - 14: atc 504 `outcome == TIMED_OUT`, `failure.code == "relay_timeout"`, `failure.category == RETRY_LATER`, `cartUrl == RECGOV_CART_URL`; health 504 `session == UNAVAILABLE`, `code == "relay_timeout"`
  - The test count stays fourteen.
- [ ] **Step 2: Run** `./gradlew :backend:test --tests '*BookingRelayRoutesTest'` — Expected: FAIL to compile.
- [ ] **Step 3: Implement** the DTOs, the controller, the wire file, the routes and the seven contract rows.
- [ ] **Step 4: Run** `make api-types`, then `./gradlew :backend:test --tests '*BookingRelayRoutesTest' --tests '*RecGovProfileHoldTest' :backend:checkApiTypes :backend:contractLedgerCheck :backend:ktlintCheck :backend:detekt` — Expected: PASS.
- [ ] **Step 5: Commit** `(refactor): the relay answers one tagged result per operation`.

### Task 14: The relay mapper reads the new wire

**Files:**
- Modify: `apps/web/src/server/contract.ts` (replace `BookingRelayResponseDto` with the five result DTOs, `BookingRelayFailureDto` and the enum aliases), `apps/web/src/server/mapper/relay/bookingRelayWire.ts`, `bookingRelayWire.test.ts`

**Interfaces:**
- Consumes: the generated `BookingRelayHoldDto`, `BookingRelayLoginDto`, `BookingRelayActionDto`, `BookingRelayHealthDto`, `BookingRelaySignOutDto`.
- Produces: the same five `decode*` signatures. A decoder checks the tag and the fields that tag requires: `held` needs `cart_url`; `blocked`, `failed` and `timed_out` need `failure` with a string `code` and one of the three categories; `mfa_required` needs `challenge_id`; sign-out needs both booleans. Anything else is `malformed` at 2xx and 504, `rejected` at any other status.

- [ ] **Step 1: Rewrite the decoder cases** in `bookingRelayWire.test.ts`: one row per tag of each DTO, each tag without its required field, an unknown tag, an unknown category, `{}`. The request-builder tests do not change.
- [ ] **Step 2: Run** `npm test --workspace @roadtrip/web -- bookingRelayWire` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify the layers held.** `git diff --stat claude/booking-layers -- apps/web/src` lists only `server/contract.ts` and files under `server/mapper/relay/` (the mapper, its test, and its answer fixtures, which the client's test reads). If any other file needed a change, stop and report which and why.
- [ ] **Step 5: Run** `make test` — Expected: exit 0.
- [ ] **Step 6: Squash to one commit with the part's title and open the pull request.** The body says mini-ca has never served the old wire, and that this should merge before the booking variables are set on Vercel.
