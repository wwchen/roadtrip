# Admin bootstrapping via email allowlist

**Date:** 2026-08-03
**Status:** Design approved, pending implementation

## Problem

There is no production path to make someone an admin. `Role.ADMIN` is a
DB-backed role (`user_role.role = 'admin'`), written only by
`UserRepo.grantRole`. In prod, `grantRole` is never called: on sign-in
`UserProvisioningService.provision` creates/links users but grants no roles,
and no IdP claim, config, or env value confers admin. The only place admin is
seeded anywhere is the local sandbox SQL (`scripts/sandbox_seed_users.sql`,
user `90001`), which applies only when auth is disabled. So a real auth-on
deployment can only gain an admin via manual SQL against Postgres.

We need a declarative, revocable, config-driven way to bootstrap the first
admin (and future admins) in prod, without hand-editing the database.

## Approach

An **email allowlist** on `AuthConfig`. On every successful sign-in, if the
identity's **verified** email is in the allowlist, grant the `ADMIN` role.
Grant-only: removal from the list does not revoke, and manual grants are never
clobbered — the DB remains the ultimate authority.

Rejected alternatives:
- **First-user-bootstraps** (`count == 0 → admin`): fragile in shared prod
  (whoever signs in first wins), and does nothing for later admins.
- **Authoritative reconcile** (list also revokes): would wipe any admin granted
  manually via SQL on that user's next login. Too surprising; grant-only
  composes better with manual `grantRole`/`revokeRole`.

## Config

New key under the existing `auth` block:

```yaml
roadtrip:
  auth:
    admin-emails: "${AUTH_ADMIN_EMAILS:}"
```

- Env var `AUTH_ADMIN_EMAILS`, matching the existing `AUTH_*` family (no
  `ROADTRIP` prefix).
- Comma-separated. Parsed into a normalized `Set<String>`: split on `,`, trim,
  drop blanks, lowercase.
- Unset/empty ⇒ empty set ⇒ nobody is auto-granted.
- Lives on `AuthConfig`, which is `null` when auth is disabled. So in local /
  CI / sandbox (auth off) the allowlist is inert and the sandbox seed SQL stays
  the local admin path.

`AuthConfig` gains one field: `val adminEmails: Set<String>`, populated in
`AuthConfig.fromConfig` from the `admin-emails` value (empty set when
absent/blank).

## Semantics: grant-only, every sign-in

On every successful sign-in — including returning identities — after the user
is resolved:

```
if (identity email is verified && email.lowercase() in adminEmails)
    userRepo.grantRole(userId, Role.ADMIN)
```

- `grantRole` is already idempotent (`onConflictDoNothing`), so repeat logins
  are no-ops.
- **Every sign-in**, not just first provision: adding an email to the list
  makes that user admin on their next login, with no DB surgery.
- Removal from the list does **not** revoke. Revoking admin is a deliberate
  manual/`revokeRole` action.

### Why "verified" gates the grant

This mirrors the account-linking security rule already enforced in
`UserProvisioningService`: an unverified email claim never confers account
authority. Granting admin off an unverified address would let anyone who can
sign up with an admin's email inherit admin — the same attack the linking code
already defends against (see the `createUser` doc comment). An unverified email
in the allowlist therefore grants nothing.

## Placement

The grant belongs in `UserProvisioningService.provision`, which the class
documents as the home for account-linking *policy*; the repos only store what
they are told.

`provision` currently has three resolution paths inside its transaction:
1. returning identity (same provider+subject) — short-circuits with an early
   `return@transactionResult`;
2. link to an existing user (by upstream identity or verified email);
3. create a new user.

To reconcile on **every** sign-in, restructure so all three paths converge on a
resolved `userId`, then apply the admin check once before returning — inside
the same transaction, using the `userRepo` already built there. The
returning-identity path must flow through the same tail (it currently returns
early), so a user added to the allowlist after their account exists is granted
on their next login.

`UserProvisioningService` gains one constructor parameter:
`adminEmails: Set<String>`, wired from `authConfig.adminEmails` in
`RouteModule` where `UserProvisioningService(ctx)` is constructed.

The admin check needs the verified email and its verification status. The
returning-identity path already has `claims` (with `email` and
`isEmailVerified`) in scope, and the link/create paths resolve `email` from
claims — so the check reads `claims.email` + `claims.isEmailVerified` directly,
no repo re-read.

## Components touched

- `config/AuthConfig.kt` — new `adminEmails: Set<String>` field; parse
  `admin-emails` in `fromConfig` (comma-split, trim, drop-blank, lowercase).
- `backend/src/main/resources/application.yaml` — `admin-emails:
  "${AUTH_ADMIN_EMAILS:}"` under `auth`.
- `service/auth/UserProvisioningService.kt` — new `adminEmails` constructor
  param; converge resolution paths; grant `ADMIN` when verified email is
  allowlisted.
- `di/RouteModule.kt` — pass `authConfig.adminEmails` into
  `UserProvisioningService`.
- `secrets/registry.yaml` — register `AUTH_ADMIN_EMAILS` if the drift test
  requires it (confirm during implementation).

## Testing

`UserProvisioningServiceTest`:
- verified email in list → `ADMIN` granted;
- verified email not in list → no role;
- **unverified** email in list → no role (security case);
- returning identity (short-circuit path) with email in list → granted (proves
  every-sign-in reconcile, not first-provision-only);
- empty allowlist → never grants;
- grant is idempotent across repeat sign-ins (no error, role present once).

`AuthConfigTest`:
- `admin-emails` parsing: comma-split, surrounding whitespace trimmed, blanks
  dropped, case normalized to lowercase;
- absent/blank `admin-emails` → empty set;
- absent value does not flip auth enabled/disabled.

## Out of scope

- Revocation via config (grant-only by decision).
- Any new admin-gated routes or UI. This change only makes the role
  *grantable*; what admin unlocks is unchanged
  (`AvailabilityWatchController` owner-scope bypass today).
- Roles beyond `ADMIN`.
