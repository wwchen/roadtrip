# Sign-in on Vercel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `roadtrip.floo.ca` signs users in with Neon Auth (email and password, Google, magic link) and `GET /api/me` answers the signed-in user from Neon.

**Architecture:** `apps/web` mounts Neon Auth's proxy at `/api/auth/*` and renders sign-in, sign-up, forgot and reset pages whose forms post to server actions. The actions and `/api/me` talk to Neon Auth only through a narrow `AuthPort`, so tests use a fake. `/api/me` links each Neon Auth user to the existing `app_user` through `user_identity`, written by a new `roadtrip_web_writer` role.

**Tech Stack:** Next.js 16.4 (App Router, server actions), React 19.3, `@neon/auth` (Managed Better Auth), Drizzle 0.45 with `postgres` 3.4, vitest 4, LDS (`@lew-ds/lds`, `@lew-ds/lds-react`).

**Spec:** `docs/superpowers/specs/2026-11-08-sign-in-on-vercel-design.md`

## Global Constraints

- No code comments, inline or doc comments (owner rule; rationale goes in commit messages).
- No inline magic constants: every literal at a call site is a named `const`.
- Layering: routes and pages → services/actions → repos. SQL and Drizzle queries only in `apps/web/src/server/repo/`. Routes never touch a repo.
- Every route module and auth page exports `dynamic = 'force-dynamic'` and is listed in `apps/web/src/app/routes.test.ts`.
- Identity link values: `provider = 'neon-auth'`, `subject` = the Neon Auth user id.
- Sign-in is enabled only when `NEON_AUTH_BASE_URL`, `NEON_AUTH_COOKIE_SECRET` and `APP_DATABASE_URL` are all set; setting some but not all is a `ConfigError`. `ADMIN_EMAILS` is optional.
- User-facing copy, verbatim: "Email or password is wrong" · "Check your email to verify your account" · "If that address has an account, a sign-in link is on its way" · "If that address has an account, a reset link is on its way" · "Something went wrong. Try again."
- Never log passwords, tokens or email addresses; log Neon Auth failures by `error.code` only.
- Commit subjects use `(type): description`; stage explicit paths only.

## Review Focus

1. **One person, two methods, one account.** Signing in with Google and later with a password for the same verified email must land on the same `app_user.id`, not hit the `app_user_email_lower_uq` unique index and 500. (Task 3)
2. **Two tabs on first sign-in.** Concurrent first `/api/me` calls for a new user must both answer that user, not a unique-violation 500. (Task 3)
3. **Hostile `return_to`.** `//evil.example`, `/\evil.example`, `https://evil.example`, `javascript:alert(1)`, `%2F%2Fevil.example` and `""` all fall back to `/`. (Task 5)
4. **Email case.** `ADMIN_EMAILS=Will@Example.com` grants admin to `will@example.com`; an unverified sign-up whose email matches an existing account does not take that account over. (Tasks 3, 4)
5. **Neon Auth unreachable.** `/api/me` answers anonymous (200) and the sign-in page shows "Something went wrong. Try again." rather than a 500. (Tasks 4, 6)

---

### Task 1: The writer role in Neon's schema

**Files:**
- Create: `packages/db/migrations/0003_web_writer.sql`, `packages/db/migrations/meta/0003_snapshot.json` (generated)
- Modify: `packages/db/migrations/meta/_journal.json` (generated)
- Test: `packages/db/src/webWriterGrants.test.ts`

**Interfaces:**
- Produces: Postgres role `roadtrip_web_writer` with `CONNECT`, `USAGE ON SCHEMA public`, `SELECT, INSERT, UPDATE ON app_user, user_identity`, `USAGE ON SEQUENCE app_user_id_seq, user_identity_id_seq`, `statement_timeout = '10s'`.

- [ ] **Step 1: Write the failing test** `packages/db/src/webWriterGrants.test.ts`, using the `withPostgis` harness exactly as `client.test.ts` does. It migrates a fresh database twice with `migrateDb` and asserts:
  - `the writer role exists after migrating, and a second migrate is a no-op`: `pg_roles` has `roadtrip_web_writer`, and the second `migrateDb` resolves.
  - `the writer can insert and update app_user and user_identity`: `SET ROLE roadtrip_web_writer`, then insert an `app_user` and a `user_identity` row and update `app_user.display_name`; all succeed.
  - `the writer cannot write the catalog`: `SET ROLE roadtrip_web_writer` then `INSERT INTO pois …` rejects with SQLSTATE `42501`.
  - `the reader still cannot write app_user`: `SET ROLE roadtrip_web` then `INSERT INTO app_user …` rejects with `42501` or `25006` (read-only transaction).
- [ ] **Step 2: Run it and see it fail.** Run `npm run test --workspace @roadtrip/db -- webWriterGrants`. Expected: FAIL, role `roadtrip_web_writer` does not exist.
- [ ] **Step 3: Create the migration.** Run `npx drizzle-kit generate --custom --name web_writer` in `packages/db`, then write the SQL in the style of `0002_web_reader.sql`: an idempotent `DO $$ … CREATE ROLE … $$`, `ALTER ROLE … SET statement_timeout = '10s'`, a `format('GRANT CONNECT ON DATABASE %I …', current_database())` block, then the grants above, separated by `--> statement-breakpoint`.
- [ ] **Step 4: Run the tests and see them pass.** Run `npm run test --workspace @roadtrip/db` and `npm run typecheck --workspace @roadtrip/db`. Expected: PASS, including the existing suites.
- [ ] **Step 5: Commit** `packages/db/migrations/0003_web_writer.sql packages/db/migrations/meta packages/db/src/webWriterGrants.test.ts` as `(feat): Neon gets a writer role for app users`.

### Task 2: Sign-in configuration

**Files:**
- Modify: `apps/web/src/server/config.ts`
- Test: `apps/web/src/server/config.test.ts`

**Interfaces:**
- Produces:
  ```ts
  export interface SignInConfig {
    neonAuthBaseUrl: URL;
    cookieSecret: string;
    appDatabaseUrl: string;
    adminEmails: ReadonlySet<string>;
  }
  config.signIn: SignInConfig | null
  ```
  `adminEmails` holds lower-cased, trimmed entries.

- [ ] **Step 1: Write the failing tests** in `config.test.ts`, stubbing env like the existing egress tests:
  - `sign-in is off when none of its variables are set` → `config.signIn` is `null`.
  - `sign-in is on with all three variables` → `neonAuthBaseUrl.href` equals the stubbed URL, `cookieSecret` and `appDatabaseUrl` match, `adminEmails` is empty.
  - `a partial sign-in configuration is an error` → for each one of the three left unset, reading `config.signIn` throws `ConfigError` naming the missing variables.
  - `ADMIN_EMAILS is trimmed and lower-cased` → `' Will@Example.com ,b@x.io'` gives `new Set(['will@example.com', 'b@x.io'])`.
  - `a cookie secret shorter than 32 characters is an error` → `ConfigError` on `NEON_AUTH_COOKIE_SECRET`.
  - `NEON_AUTH_BASE_URL must be absolute` → `ConfigError`.
- [ ] **Step 2: Run them and see them fail.** Run `npx vitest run src/server/config.test.ts` in `apps/web`. Expected: FAIL, `signIn` undefined.
- [ ] **Step 3: Implement `signInFrom(): SignInConfig | null` and the `signIn` getter**, following `availabilityEgressFrom` (variable-name constants, `secretFrom` for the two secrets so the `_FILE` variants work, `MIN_COOKIE_SECRET_LENGTH = 32`).
- [ ] **Step 4: Run them and see them pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** as `(feat): web reads its sign-in settings`.

### Task 3: The app user repo

**Files:**
- Create: `apps/web/src/server/appDb.ts`, `apps/web/src/server/repo/appUserRepo.ts`
- Test: `apps/web/src/server/repo/appUserRepo.test.ts`

**Interfaces:**
- Consumes: `config.signIn.appDatabaseUrl` (Task 2); `appUser`, `userIdentity` from `@roadtrip/db`.
- Produces:
  ```ts
  appDb(): Db
  export const NEON_AUTH_PROVIDER = 'neon-auth';
  export interface IdentityClaims { subject: string; email: string; emailVerified: boolean; displayName: string | null }
  export interface AppUserRow { id: bigint; email: string; emailVerified: boolean; displayName: string | null; theme: UserTheme }
  export function linkedAppUser(db: Db, claims: IdentityClaims): Promise<AppUserRow>
  export class EmailTakenError extends Error {}
  ```
  `appDb()` opens a lazily cached `createDb(config.signIn.appDatabaseUrl, { prepare: config.databasePrepare })` without the read-only connection setting; it throws `ConfigError` when sign-in is off.

- [ ] **Step 1: Write the failing tests** in `appUserRepo.test.ts` with `startCatalogFixture()` and its `writeDb`:
  - `a new identity creates one app_user and one user_identity` → the returned row has the claims' email, verified flag and display name, and theme `system`; one row in each table, with identity `(neon-auth, subject)`.
  - `the same identity again returns the same id and refreshes the profile` → a second call with `displayName: 'New'` and `emailVerified: true` returns the same `id` with the new values.
  - `a second verified identity with the same email, in any case, joins the existing user` → subject `b` with `Will@Example.com` after subject `a` with `will@example.com` returns `a`'s id; two `user_identity` rows.
  - `an unverified identity never joins an existing user by email` → it rejects with `EmailTakenError`; `app_user` still has one row.
  - `concurrent first calls for one identity both answer the same user` → `Promise.all` of 5 calls with the same claims gives 5 equal ids, one `app_user` row and one `user_identity` row.
- [ ] **Step 2: Run them and see them fail.** Run `npx vitest run src/server/repo/appUserRepo.test.ts`. Expected: FAIL, module not found.
- [ ] **Step 3: Implement `linkedAppUser`** in one transaction: select through `user_identity` join; otherwise lock-free insert-or-reuse. Insert `app_user` with `ON CONFLICT DO NOTHING`; when it inserts nothing, read the row by `lower(email)`, joining it only if `claims.emailVerified`, else throw `EmailTakenError`. Then insert `user_identity` with `ON CONFLICT (provider, subject) DO NOTHING` and re-read through the join. Update `email_verified`, `display_name` and `updated_at` only when they differ. Export `class EmailTakenError extends Error`.
- [ ] **Step 4: Run them and see them pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** as `(feat): a Neon Auth user is linked to one app_user`.

### Task 4: The auth port, Neon Auth adapter and `/api/me`

**Files:**
- Create: `apps/web/src/server/auth/authPort.ts`, `apps/web/src/server/auth/neonAuth.ts`, `apps/web/src/server/service/auth/meService.ts`, `apps/web/src/app/api/auth/[...path]/route.ts`
- Modify: `apps/web/package.json` (add `@neon/auth`), `apps/web/src/app/api/me/route.ts`, `apps/web/src/app/routes.test.ts`
- Test: `apps/web/src/server/service/auth/meService.test.ts`, `apps/web/src/app/api/me/route.test.ts`, `apps/web/src/app/api/auth/[...path]/route.test.ts`

**Interfaces:**
- Consumes: `config.signIn` (Task 2); `linkedAppUser`, `EmailTakenError`, `appDb` (Task 3).
- Produces:
  ```ts
  export interface AuthUser { id: string; email: string; name: string | null; emailVerified: boolean }
  export type AuthResult = { ok: true } | { ok: false; code: string };
  export type RedirectResult = { ok: true; url: string } | { ok: false; code: string };
  export interface AuthPort {
    currentUser(): Promise<AuthUser | null>;
    signInWithPassword(email: string, password: string): Promise<AuthResult>;
    signInWithGoogle(callbackUrl: string, errorCallbackUrl: string): Promise<RedirectResult>;
    resendVerification(email: string, callbackUrl: string): Promise<AuthResult>;
    sendMagicLink(email: string, callbackUrl: string): Promise<AuthResult>;
    signUp(email: string, password: string, name: string, callbackUrl: string): Promise<AuthResult>;
    requestPasswordReset(email: string, redirectTo: string): Promise<AuthResult>;
    resetPassword(token: string, newPassword: string): Promise<AuthResult>;
    signOut(): Promise<void>;
  }
  export const EMAIL_NOT_VERIFIED = 'EMAIL_NOT_VERIFIED';
  neonAuth(): ReturnType<typeof createNeonAuth> | null
  authPort(): AuthPort | null
  meFor(port: AuthPort | null, users: (claims: IdentityClaims) => Promise<AppUserRow>, adminEmails: ReadonlySet<string>): Promise<MeResponseDto>
  ```
  `currentUser()` throws when Neon Auth cannot be reached (`error.code` starting `NETWORK_`). The adapter maps `auth.signIn.email`, `auth.signIn.social({ provider: 'google', callbackURL, errorCallbackURL })`, `auth.sendVerificationEmail({ email, callbackURL })`, `auth.signIn.magicLink`, `auth.signUp.email` (with `callbackURL`), `auth.requestPasswordReset({ email, redirectTo })`, `auth.resetPassword({ newPassword, token })` and `auth.signOut`. First check those names against the installed `@neon/auth` types and adjust the adapter, never the port.

- [ ] **Step 1: Write the failing tests.**
  - `meService.test.ts`, with a fake `AuthPort` and a fake `users` function:
    - `no port means sign-in is disabled` → `{ authenticated: false, auth_enabled: false, auth_embedded: false }`.
    - `no session is anonymous with sign-in enabled` → `{ authenticated: false, auth_enabled: true, auth_embedded: false }`.
    - `a signed-in admin` → `adminEmails = new Set(['will@example.com'])` with session email `Will@Example.com` gives `user.roles` `['admin']`, `user.id` the repo's id as a `number`, plus `email`, `display_name`, `email_verified`, `theme`.
    - `a signed-in non-admin has no roles` → `roles: []`.
    - `an unreachable Neon Auth answers anonymous` → `currentUser` throws; the result equals the no-session shape.
    - `an email taken by another account answers anonymous` → `users` rejects with `EmailTakenError`; the result equals the no-session shape.
  - `api/me/route.test.ts`: mock `@/server/auth/neonAuth` (`authPort` returns `null`) and keep today's disabled assertion, and add a mocked signed-in case asserting status 200 and `authenticated: true`.
  - `api/auth/[...path]/route.test.ts`: with `neonAuth` mocked to `null`, `GET` and `POST` answer 404 `{ error: 'not_found' }`.
- [ ] **Step 2: Run them and see them fail.** Run `npx vitest run src/server/service/auth src/app/api/me src/app/api/auth`. Expected: FAIL.
- [ ] **Step 3: Implement.** Run `npm install @neon/auth --workspace apps/web` and pin the exact version it resolves. Then:
  - `neonAuth()` caches one `createNeonAuth({ baseUrl, cookies: { secret, sameSite: 'lax' } })`. Lax, because the Google, magic-link and reset callbacks are cross-site top-level navigations.
  - `authPort()` wraps it.
  - `meFor` logs one line `me: <code>` on failures.
  - The `/api/auth` route delegates to `neonAuth().handler()`, or answers 404 when sign-in is off.
  - `/api/me` calls `meFor(authPort(), (claims) => linkedAppUser(appDb(), claims), config.signIn?.adminEmails ?? new Set())`.
  - Add `/api/auth/[...path]` to `routes.test.ts`.
- [ ] **Step 4: Run them and see them pass.** Run `npx vitest run` in `apps/web` and `npm run typecheck --workspace apps/web`. Expected: PASS.
- [ ] **Step 5: Commit** as `(feat): /api/me answers the Neon Auth user`.

### Task 5: `return_to`, `/auth/login` and `/auth/logout`

**Files:**
- Create: `apps/web/src/server/auth/returnTo.ts`, `apps/web/src/app/auth/login/route.ts`, `apps/web/src/app/auth/logout/route.ts`
- Modify: `apps/web/src/app/routes.test.ts`
- Test: `apps/web/src/server/auth/returnTo.test.ts`, `apps/web/src/app/auth/login/route.test.ts`, `apps/web/src/app/auth/logout/route.test.ts`

**Interfaces:**
- Consumes: `authPort()` (Task 4).
- Produces: `export const RETURN_TO_PARAM = 'return_to'; export const HOME_PATH = '/'; export function safeReturnTo(raw: string | null | undefined): string; export function withReturnTo(path: string, returnTo: string): string`.

- [ ] **Step 1: Write the failing tests.**
  - `returnTo.test.ts`: `test.each` keeps `/`, `/watches?action=new#form` and `/availability?tab=runs`, and maps `//evil.example`, `/\evil.example`, `https://evil.example/`, `javascript:alert(1)`, `%2F%2Fevil.example`, `''`, `null` and `undefined` to `/`. Also `withReturnTo('/auth/sign-in', '/watches?x=1')` equals `/auth/sign-in?return_to=%2Fwatches%3Fx%3D1`.
  - `login/route.test.ts`: `GET /auth/login?return_to=/watches` gives 302 with `location` `/auth/sign-in?return_to=%2Fwatches`; `return_to=//evil.example` gives `/auth/sign-in?return_to=%2F`.
  - `logout/route.test.ts`: with a fake port, `GET /auth/logout` calls `signOut` once and gives 302 to `/`; with `authPort()` `null`, it still gives 302 to `/`.
- [ ] **Step 2: Run them and see them fail.** Run `npx vitest run src/server/auth src/app/auth`. Expected: FAIL.
- [ ] **Step 3: Implement.** `safeReturnTo` decodes once, then accepts only values starting with exactly one `/` and containing no `\` or control characters. The routes build relative `Location` headers. Add both routes to `routes.test.ts`.
- [ ] **Step 4: Run them and see them pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** as `(feat): the frontend's sign-in and sign-out URLs work on Vercel`.

### Task 6: Sign-in, sign-up, forgot and reset pages

**Files:**
- Create:
  - `apps/web/src/app/auth/layout.tsx`, `apps/web/src/app/auth/auth.css`
  - `apps/web/src/app/auth/actions.ts`, `apps/web/src/app/auth/copy.ts`
  - `apps/web/src/app/auth/sign-in/page.tsx`, `apps/web/src/app/auth/sign-up/page.tsx`, `apps/web/src/app/auth/forgot/page.tsx`, `apps/web/src/app/auth/reset/page.tsx`
- Modify: `apps/web/package.json` (add `@lew-ds/lds` and `@lew-ds/lds-react` at the versions `frontend/package.json` pins), `apps/web/src/app/routes.test.ts`
- Test: `apps/web/src/app/auth/actions.test.ts`

**Interfaces:**
- Consumes: `AuthPort`, `EMAIL_NOT_VERIFIED` (Task 4); `safeReturnTo`, `withReturnTo`, `RETURN_TO_PARAM` (Task 5).
- Produces:
  - `export type FormState = { message: string | null; canResendVerification: boolean }`.
  - Each action has the signature `(port: AuthPort | null, form: FormData) => Promise<FormState>`. The actions are `signInWithPasswordAction`, `signInWithGoogleAction`, `sendMagicLinkAction`, `signUpAction`, `requestPasswordResetAction`, `resetPasswordAction` and `resendVerificationAction`.
  - The pages bind each action to `authPort()` with `'use server'` wrappers in `actions.ts` and call `redirect(...)` on success.
  - The copy constants live in `copy.ts`, with the strings from Global Constraints.

- [ ] **Step 1: Write the failing tests** in `actions.test.ts` with a fake port; `redirect` is mocked to throw a tagged error carrying the URL:
  - `password sign-in redirects to a safe return_to` → `return_to=/watches` redirects to `/watches`; `return_to=//evil.example` redirects to `/`.
  - `a wrong password shows one message` → port `{ ok: false, code: 'INVALID_EMAIL_OR_PASSWORD' }` gives `"Email or password is wrong"`.
  - `an unverified account is told to verify` → `EMAIL_NOT_VERIFIED` gives `"Check your email to verify your account"` and `canResendVerification: true`.
  - `Google redirects to the provider URL with the return path as callback` → the port receives callback `/watches` and error callback `/auth/sign-in?return_to=%2Fwatches&error=retry`, and the action redirects to the returned URL.
  - `resending verification answers the verify message` → `resendVerificationAction` with `{ ok: true }` gives `"Check your email to verify your account"`; the port receives the safe callback.
  - `a magic link answers the same whether or not the account exists` → `{ ok: true }` and `{ ok: false, code: 'USER_NOT_FOUND' }` both give `"If that address has an account, a sign-in link is on its way"`.
  - `forgot answers the same whether or not the account exists` → both give `"If that address has an account, a reset link is on its way"`; the port receives `redirectTo` `/auth/reset?return_to=…`.
  - `reset with a good token signs in and redirects` → redirects to the safe `return_to`.
  - `sign-up asks the user to verify` → `{ ok: true }` gives `"Check your email to verify your account"`; the port receives the safe callback.
  - `an unreachable Neon Auth shows the retry message` → the port rejects; the message is `"Something went wrong. Try again."`, and `console.warn` is called once with `auth:` and the code, never the email.
  - `sign-in disabled` → port `null` gives the retry message for every action.
- [ ] **Step 2: Run them and see them fail.** Run `npx vitest run src/app/auth/actions.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement the actions, then the pages.**
  - Each page is a server component that reads `searchParams`, exports `dynamic = 'force-dynamic'`, and renders an LDS form with a hidden `return_to` field. Pages that need `useActionState` render small client forms.
  - The sign-in page holds the Google button, the email-and-password form, the magic-link form, and links to sign-up and forgot. All carry `return_to` through `withReturnTo`.
  - The sign-in page shows the retry message when `searchParams.error` is `retry`, and a "Send it again" form bound to `resendVerificationAction` when `canResendVerification` is true.
  - The reset page reads `token` from `searchParams`.
  - Every failed action logs `auth: <code>` once.
  - `layout.tsx` imports `@lew-ds/lds/css` and the frontend's token stylesheets, in the order `frontend/src/ui/styles.css` documents. It wraps pages in the `theme-roadtrip-zion` class.
  - `auth.css` holds only layout (centring and width) built from `--rt-*` tokens.
  - Add the four pages to `routes.test.ts`.
- [ ] **Step 4: Run them and see them pass.** Run `npx vitest run` and `npm run typecheck --workspace apps/web`, then `npm run build` from the repo root. Expected: PASS, and the build lists the four `/auth/*` pages as dynamic.
- [ ] **Step 5: Commit** as `(feat): sign-in, sign-up and password reset pages on Vercel`.

### Task 7: Migrations applied on master, and docs

**Files:**
- Modify: `.github/workflows/ci.yml`, `docs/web-app.md`, `scripts/test_ci_image_builds.py`

**Interfaces:**
- Consumes: Task 1's migration; GitHub secret `NEON_MIGRATE_URL`.

- [ ] **Step 1: Write the failing test** in `scripts/test_ci_image_builds.py`: `test_neon_migrations_run_on_master_only`. It asserts that `ci.yml` has a `neon-migrate` job with an `if` restricting it to `push` on `refs/heads/master`, `needs` containing `ci-passed`, and a step running `npm run migrate --workspace @roadtrip/db` with `DATABASE_URL: ${{ secrets.NEON_MIGRATE_URL }}`.
- [ ] **Step 2: Run it and see it fail.** Run `python3 -m unittest scripts/test_ci_image_builds.py`. Expected: FAIL.
- [ ] **Step 3: Add the job.** It checks out, sets up Node like `web-tests` does, runs `npm ci`, then runs the migrate step. Add a "Sign-in on Vercel" section to `docs/web-app.md` that covers:
  - the four Vercel variables;
  - the owner's one-time Neon Auth setup (trusted origin, methods, Resend SMTP, the Google client, and the writer role's password);
  - the `neon-migrate` job;
  - the rule that previews have no sign-in.
- [ ] **Step 4: Run it and see it pass.** Run `python3 -m unittest scripts/test_ci_image_builds.py` and `actionlint .github/workflows/ci.yml`. Expected: PASS, no findings.
- [ ] **Step 5: Commit** as `(ci): Neon migrations apply on master`.

### Task 8: End-to-end check on a Neon development branch

Owner prerequisites: Neon Auth enabled on a development branch of `square-sky-33743589`, with localhost as a trusted origin and Resend SMTP configured.

- [ ] **Step 1: Prepare the branch.** Apply migrations to the development branch with `DATABASE_URL=<branch owner URL> npm run migrate --workspace @roadtrip/db`, and set the writer role's password there.
- [ ] **Step 2: Start the app.** Run `apps/web` locally with `NEON_AUTH_BASE_URL`, `NEON_AUTH_COOKIE_SECRET`, `APP_DATABASE_URL` and `ADMIN_EMAILS` pointing at the branch.
- [ ] **Step 3: Walk the flows in the browser:**
  1. Sign up, then verify.
  2. Check `/api/me` shows `authenticated: true` with an admin role for an `ADMIN_EMAILS` address.
  3. Sign out through `/auth/logout`.
  4. Sign in with the password.
  5. Sign in with a magic link.
  6. Reset the password.
  7. Open `/auth/login?return_to=/availability` and confirm it lands on `/availability` after sign-in.
  8. Check that `AuthRow` on `/` shows the user.
- [ ] **Step 4: Record what passed** in the PR description. Google is checked on production after the owner configures the OAuth client.
