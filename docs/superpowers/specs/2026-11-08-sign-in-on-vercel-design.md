# Sign-in on Vercel: Neon Auth behind apps/web

**Status:** design agreed with the owner on 2026-11-08; this document awaits review.

**Parent decision (owner, 2026-11-08):** sign-in moves to Next.js on Vercel. mini-ca and Kotlin end
up serving only the stateless availability checks. Users, sessions, settings and watches leave
mini-ca's Postgres. Existing mini-ca accounts, settings and watches are **not** carried over: Vercel
starts with no users.

## The move, in sub-projects

Each sub-project gets its own spec, plan and PRs. This document is sub-project 1.

1. **Sign-in on Vercel** (this spec): identity, sessions, an app user row in Neon, a real `/api/me`.
2. **Settings and notification targets in Neon**, including encrypted rec.gov credentials.
3. **Watches on Vercel**: CRUD in Neon, scheduled checks through the egress, transition detection,
   email and Slack alerts, the Slack interactivity callback.
4. **Add-to-cart** as a stateless egress call: Vercel sends the user's stored rec.gov credentials
   with each request, since the companion must run from mini-ca's residential IP.
5. **Retire Kotlin's user state**: drop users, sessions, watches, pollers and settings from Kotlin
   and mini-ca's Postgres. mini-ca's availability cache stays.

Sub-project 1 alone restores sign-in and an accurate `/api/me`. Watches, settings and add-to-cart
stay unavailable until sub-projects 2-4 ship, as they are today.

## Today

- Kotlin signs users in through Clerk (`clerk.roadtrip.floo.ca`, OIDC with PKCE) and through an
  embedded email-and-password flow (`/auth/password/begin`, `/auth/password/complete`). It sets a
  host-only, `HttpOnly`, `SameSite=Lax` session cookie on `roadtrip.floo.ca` and keeps sessions in
  mini-ca's `user_session` table. Admin is decided by `roadtrip.auth.role-emails.admin`.
- `roadtrip.floo.ca` is served by Vercel, which has no handler for `/auth/*`. Its `/api/me`
  answers a constant `{authenticated: false, auth_enabled: false, auth_embedded: false}`.
- The frontend (`frontend/src/api/auth-api.ts`) signs in by navigating to
  `/auth/login?return_to=…`, signs out by navigating to `/auth/logout`, and learns who the user is
  only from `GET /api/me` (`MeResponseDto`, whose `user.id` is a number). `AuthRow`,
  `WatchSignInGate`, `AlertsPanel`, `WatchEditor`, `AvailabilityWeek` and `DayDetail` all read it.
- Neon's schema is owned by `packages/db` (Drizzle; `npm run migrate --workspace @roadtrip/db`),
  which already defines the read-only `roadtrip_web` role in `0002_web_reader.sql`. No CI job applies
  migrations to Neon.
- Kotlin sends email through Resend.

## Decisions

- **D1 Neon Auth (Managed Better Auth) is the identity provider.** Users and sessions live in the
  `neon_auth` schema of Neon's `main` branch. Clerk is not used by Vercel.
- **D2 Sign-in methods:** email and password, Google, and magic link.
- **D3 Sign-in pages are Next.js pages with server actions** in `apps/web`, not Vite frontend
  pages and not Neon Auth's prebuilt UI components.
- **D4 The frontend's auth contract does not change.** `/auth/login?return_to=…`, `/auth/logout` and
  `GET /api/me` keep their shapes, so no frontend component changes in this sub-project.
- **D5 `app_user` gives every Neon Auth user the numeric id** the contract carries and later
  sub-projects hang settings and watches off.
- **D6 Previews have no sign-in.** Without the Neon Auth variables, `/api/me` answers
  `auth_enabled: false`, as previews already lack the egress.

## Design

### Neon Auth setup (owner and one-time)

- Enable Neon Auth on Neon `main` (project `square-sky-33743589`).
- Trusted origin `https://roadtrip.floo.ca`. Localhost is allowed only on a Neon development branch.
- Email and password on, with email verification required before first sign-in.
- Magic link plugin on.
- Google OAuth with the owner's production Google OAuth client; redirect URI as Neon Auth gives it.
- Email provider: Resend over SMTP (`smtp.resend.com`), sender on the domain Kotlin already sends
  from.

### Vercel environment (Production only)

| Variable | Value |
|---|---|
| `NEON_AUTH_BASE_URL` | The Auth URL Neon shows for `main` |
| `NEON_AUTH_COOKIE_SECRET` | 32+ random bytes, base64 |
| `ADMIN_EMAILS` | Comma-separated; today `williamchen16@gmail.com` |
| `APP_DATABASE_URL` | Connection string for `roadtrip_web_writer` |

`apps/web/src/server/config.ts` reads them. Sign-in is enabled only when `NEON_AUTH_BASE_URL`,
`NEON_AUTH_COOKIE_SECRET` and `APP_DATABASE_URL` are all set; setting some but not all three is a
startup configuration error, the rule `AVAILABILITY_EGRESS_*` already follows. `ADMIN_EMAILS` is
optional; unset means no admins.

### Server: `apps/web`

- `src/server/auth/neonAuth.ts`: the single `createNeonAuth({ baseUrl, cookies: { secret } })`
  instance, or `null` when sign-in is disabled.
- `src/app/api/auth/[...path]/route.ts`: Neon Auth's `handler()`; 404 when sign-in is disabled.
- `src/server/auth/returnTo.ts`: `safeReturnTo(raw)` returns `raw` only when it is a same-site path
  (starts with a single `/`, no scheme, no `//`, no backslash); anything else is `/`.
- Pages and their server actions, under `src/app/auth/`:

| Path | Purpose | Actions |
|---|---|---|
| `/auth/sign-in` | Google button, email and password form, "email me a link" form | `signInWithPassword`, `sendMagicLink`, `signInWithGoogle` |
| `/auth/sign-up` | Email, name, password | `signUp` |
| `/auth/forgot` | Email | `requestPasswordReset` |
| `/auth/reset` | New password, token from the email link | `resetPassword` |

  Every page carries `return_to` through its forms and redirects to `safeReturnTo(return_to)` on
  success. All forms link to each other preserving `return_to`.
- The compatibility URLs the frontend calls:
  - `GET /auth/login?return_to=…` redirects (302) to `/auth/sign-in?return_to=…`.
  - `GET /auth/logout` signs out through Neon Auth and redirects (302) to `/`.
- Styling: the pages import `@lew-ds/lds/css` and the frontend's token stylesheets
  (`frontend/src/tokens/roadtrip-zion.css`, `roadtrip-zion-hues.css`, `tokens.css`,
  `roadtrip-zion-bridge.css`) and use LDS components for buttons and fields. No page-specific
  colours or spacing values.

### `GET /api/me`

`src/server/service/auth/meService.ts`, called by `src/app/api/me/route.ts`:

- Sign-in disabled: `{authenticated: false, auth_enabled: false, auth_embedded: false}` (today's
  answer).
- No session: `{authenticated: false, auth_enabled: true, auth_embedded: false}`.
- Session: upsert `app_user` on `auth_user_id`, then answer
  `{authenticated: true, auth_enabled: true, auth_embedded: false, user: {id, email, display_name,
  email_verified, roles, theme}}`. `roles` is `["admin"]` when the email is in `ADMIN_EMAILS`
  (compared case-insensitively), otherwise `[]`. `provider_label`, `auth_client_id`, `auth_domain`
  and `auth_realm` are omitted.
- Neon Auth unreachable or erroring: answer the no-session shape and log one line with the error
  code, so the map keeps working.

The upsert lives in `src/server/repo/appUserRepo.ts`, using `APP_DATABASE_URL`; the route does not
touch SQL.

### Data: `packages/db`

Migration `0003_app_user.sql`, with the matching Drizzle schema:

```sql
CREATE TABLE app_user (
  id bigserial PRIMARY KEY,
  auth_user_id text NOT NULL UNIQUE REFERENCES neon_auth."user"(id) ON DELETE CASCADE,
  theme text,
  created_at timestamptz NOT NULL DEFAULT now()
);
```

and a `roadtrip_web_writer` role (login, no superuser) granted `SELECT, INSERT, UPDATE` on
`app_user`, `USAGE` on its sequence and `SELECT` on `neon_auth."user"`. `roadtrip_web` stays
read-only and gains nothing. The writer role's password is set by the owner, as `roadtrip_web`'s is.

The migration references `neon_auth."user"`, so Neon Auth must be enabled on a branch before the
migration runs there.

### Applying migrations

A `neon-migrate` job in `.github/workflows/ci.yml` runs on pushes to `master` after the tests pass:
`npm run migrate --workspace @roadtrip/db` with `DATABASE_URL` from a `NEON_MIGRATE_URL` secret
(Neon owner role). Drizzle's journal makes a rerun a no-op. Vercel builds do not migrate.

### Kotlin

Unchanged in this sub-project. Its `/auth/*`, `/api/me` and session tables stay unreachable on
`roadtrip.floo.ca` and are removed in sub-project 5.

## Flows

- **Password sign-in:** the action calls `auth.signIn.email`. Success redirects to `return_to`. A
  wrong email or password shows one message, "Email or password is wrong". An unverified account
  shows "Check your email to verify your account", with a resend link.
- **Google:** the action starts Neon Auth social sign-in with `callbackURL` set to
  `safeReturnTo(return_to)`; a failed or cancelled sign-in lands back on `/auth/sign-in` with the
  same `return_to` and the generic error.
- **Magic link:** the action requests the link and the page always answers "If that address has an
  account, a sign-in link is on its way", whether or not the account exists. The link signs in and
  lands on `return_to`.
- **Sign-up:** the action calls `auth.signUp.email`; the page asks the user to verify by email; the
  verification link signs in and lands on `return_to`.
- **Forgot and reset:** forgot always answers the same message whatever the address; reset takes the
  token from the link and a new password, then signs in.

## Errors

- A `return_to` that is not a same-site path falls back to `/`.
- Upstream Neon Auth errors are logged by error code only, never with passwords, tokens or email
  addresses, and the user sees "Something went wrong. Try again."
- Throttling of sign-in attempts is Neon Auth's. This sub-project adds no rate limit.

## Testing

- **Unit (vitest, `apps/web`):** `safeReturnTo` for paths, absolute URLs, `//host`, backslashes and
  empty input; `meService` for disabled, anonymous, signed-in admin, signed-in non-admin and upstream
  failure, with a fake auth instance and a fake repo; each server action against a fake auth
  instance (success redirect, failure messages, `return_to` carried).
- **Routes:** `/api/me` in all five states; `/auth/login` and `/auth/logout` redirects;
  `/api/auth/*` is 404 when sign-in is disabled.
- **Config:** sign-in enabled only with all three variables; partial configuration is an error.
- **Migrations (`packages/db`):** applied to a fresh database that has a stub `neon_auth."user"`
  table, then applied again as a no-op; `roadtrip_web_writer` can upsert `app_user` and cannot write
  catalog tables; `roadtrip_web` cannot write `app_user`.
- **End to end, before shipping:** on a Neon development branch with Neon Auth enabled and a local
  `apps/web`: sign up, verify, sign out, sign in with password, sign in with a magic link, reset a
  password; `/api/me` shows the user and `AuthRow` shows them signed in. Google is checked on
  production once the owner's OAuth client is configured.

## Out of scope

- Settings, notification targets, watches, add-to-cart and the Slack callback (sub-projects 2-4).
- Migrating mini-ca's users or data (the owner chose to start fresh).
- Rate limiting sign-in beyond Neon Auth's own.
- Removing Kotlin's auth code (sub-project 5).
