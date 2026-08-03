# Role bootstrapping via email allowlist

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

We need a declarative, config-driven way to bootstrap the first admin (and
future admins) in prod, without hand-editing the database. The mechanism should
be generic over `Role`, not admin-specific — as new roles are added to the
enum, they should be grantable by the same config path with no new code.

## Approach

A **role → email allowlist** on `AuthConfig`: a `Map<Role, Set<String>>`. On
every successful sign-in, for each configured role whose email set contains the
identity's **verified** email, grant that role. Grant-only: removal from the
config does not revoke, and manual grants are never clobbered — the DB remains
the ultimate authority.

Rejected alternatives:
- **First-user-bootstraps** (`count == 0 → admin`): fragile in shared prod
  (whoever signs in first wins), does nothing for later admins, and is
  admin-specific rather than generic.
- **Authoritative reconcile** (config also revokes): would wipe any role granted
  manually via SQL on that user's next login. Too surprising; grant-only
  composes better with manual `grantRole`/`revokeRole`.
- **A flat `admin-emails` key**: works, but hardcodes the admin role into config
  shape and code. Rejected in favour of the generic role-keyed map below.

## Config

New subsection under the existing `auth` block, keyed by role:

```yaml
roadtrip:
  auth:
    role-emails:
      admin: "${AUTH_ADMIN_EMAILS:}"
      # add more role keys here as roles are added to the Role enum, e.g.
      # editor: "${AUTH_EDITOR_EMAILS:}"
```

- Each key under `role-emails` is a `Role` wireValue (`admin` today). Its value
  is a comma-separated email list, env-driven via `AUTH_<ROLE>_EMAILS` (matching
  the existing `AUTH_*` family, no `ROADTRIP` prefix).
- **Comma-separated strings, not YAML arrays.** `ConfigSection` is backed by a
  flat `Map<String, String>` and the YAML flattener (`ApplicationProperties`)
  does not descend into list *elements* — so a list-of-objects
  (`- role: admin\n  email: ...`) is not representable. A role-keyed map of CSV
  strings is. Comma-separated env values are the house convention (see
  `ReadPathProviderConfig`'s `enabled-data-providers`).
- Each value is parsed with the existing `ConfigSection.csvSet(name)` helper
  (split on `,`, trim, drop blanks → `Set<String>`), then lowercased in
  `AuthConfig` for case-insensitive email matching (`csvSet` does not lowercase).
- Unknown role keys (no matching `Role.parse`) are skipped, so a stale config
  key never crashes startup.
- A role key with an empty/unset value contributes an empty set (no grants).
- The whole subsection unset ⇒ empty map ⇒ nobody is auto-granted.

`AuthConfig` gains one field: `val roleGrants: Map<Role, Set<String>>`,
populated in `AuthConfig.fromConfig` by enumerating the `role-emails`
subsection. It lives on `AuthConfig`, which is `null` when auth is disabled — so
in local / CI / sandbox (auth off) the allowlist is inert and the sandbox seed
SQL stays the local admin path.

### Where the value comes from in prod (non-secret config, not a secret)

`AUTH_<ROLE>_EMAILS` is authorization *configuration*, not a credential —
knowing the list grants nothing, since gaining the role still requires
controlling that email's IdP account and completing a verified sign-in. It
therefore follows the same path as `AUTH_PROVIDER`, **not** the secret path:

- Declared as a plain env var in the backend `environment:` block of
  `docker-compose.yml`, with an empty default:
  `- AUTH_ADMIN_EMAILS=${AUTH_ADMIN_EMAILS:-}` (mirrors the existing
  `AUTH_PROVIDER=${AUTH_PROVIDER:-clerk}` line).
- Added to `nonSecretPlaceholders` in `SecretRegistryDriftTest`. Without this
  the drift test fails: every `${...}` placeholder in `application.yaml` must be
  either a registered secret or an explicit non-secret.
- **Not** encrypted, **not** in `secrets/registry.yaml`, **not** mounted under
  `/run/secrets`. (This corrects the earlier draft, which wrongly routed it
  through the secret registry.)
- The actual value at deploy time is supplied by the operator in the deploy
  shell/host env, exactly like the RFC 0009 rollback flip
  (`AUTH_PROVIDER=auth0 make run env=prod`). To bootstrap the first admin:
  `AUTH_ADMIN_EMAILS=you@example.com` on the deploy that ships this change.
- The email verified by the IdP (Clerk) at sign-in must match an entry; that is
  the natural link between "the email I set here" and "the person who becomes
  admin".

### Enumerating the subsection

`ConfigSection` exposes `absoluteKeys()` and `relativeKey(absoluteKey)`. To read
the role keys under `auth.role-emails`, take the `role-emails` subsection, map
its absolute keys through `relativeKey` to get the immediate child names
(`admin`, ...), `Role.parse` each (skipping unknowns), and read each value via
`csvSet`. This is the only new config-reading pattern; encapsulate it in a small
private helper in `AuthConfig`.

## Semantics: grant-only, every sign-in

On every successful sign-in — including returning identities — after the user
is resolved:

```
val email = claims.email?.lowercase()
if (claims.isEmailVerified && email != null)
    for ((role, emails) in roleGrants)
        if (email in emails) userRepo.grantRole(userId, role)
```

- `grantRole` is already idempotent (`onConflictDoNothing`), so repeat logins
  are no-ops.
- **Every sign-in**, not just first provision: adding an email to a role's list
  makes that user gain the role on their next login, with no DB surgery.
- Removal from the config does **not** revoke. Revoking a role is a deliberate
  manual/`revokeRole` action.

### Why "verified" gates the grant

This mirrors the account-linking security rule already enforced in
`UserProvisioningService`: an unverified email claim never confers account
authority. Granting a role off an unverified address would let anyone who can
sign up with an admin's email inherit that role — the same attack the linking
code already defends against (see the `createUser` doc comment). An unverified
email in the allowlist therefore grants nothing.

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
resolved `userId`, then apply the role check once before returning — inside the
same transaction, using the `userRepo` already built there. The
returning-identity path must flow through the same tail (it currently returns
early), so a user added to a role's allowlist after their account exists is
granted on their next login.

`UserProvisioningService` gains one constructor parameter:
`roleGrants: Map<Role, Set<String>>`, wired from `authConfig.roleGrants` in
`RouteModule` where `UserProvisioningService(ctx)` is constructed.

The role check needs the verified email and its verification status. The
returning-identity path already has `claims` (with `email` and
`isEmailVerified`) in scope, and the link/create paths resolve `email` from
claims — so the check reads `claims.email` + `claims.isEmailVerified` directly,
no repo re-read.

## Components touched

- `config/AuthConfig.kt` — new `roleGrants: Map<Role, Set<String>>` field; in
  `fromConfig`, enumerate the `role-emails` subsection (via `absoluteKeys` /
  `relativeKey`), `Role.parse` each key (skip unknowns), read each value via
  `csvSet`, lowercase entries.
- `backend/src/main/resources/application.yaml` — `role-emails:` block under
  `auth` with `admin: "${AUTH_ADMIN_EMAILS:}"`.
- `service/auth/UserProvisioningService.kt` — new `roleGrants` constructor
  param; converge resolution paths; grant each matching role when the verified
  email is allowlisted.
- `di/RouteModule.kt` — pass `authConfig.roleGrants` into
  `UserProvisioningService`.
- `docker-compose.yml` — add `- AUTH_ADMIN_EMAILS=${AUTH_ADMIN_EMAILS:-}` to the
  backend `environment:` block (non-secret, alongside `AUTH_PROVIDER`).
- `backend/src/test/kotlin/ca/floo/roadtrip/config/SecretRegistryDriftTest.kt` —
  add `AUTH_ADMIN_EMAILS` to `nonSecretPlaceholders`. (Not `secrets/registry.yaml`
  — this is config, not a credential.)

## Testing

`UserProvisioningServiceTest`:
- verified email in the `admin` set → `ADMIN` granted;
- verified email not in any set → no role;
- **unverified** email in the `admin` set → no role (security case);
- returning identity (short-circuit path) with email in the `admin` set →
  granted (proves every-sign-in reconcile, not first-provision-only);
- empty `roleGrants` → never grants;
- grant is idempotent across repeat sign-ins (no error, role present once);
- a `roleGrants` map with two roles grants both when the email is in both sets
  (proves the mechanism is generic, not admin-hardcoded).

`AuthConfigTest`:
- `role-emails` parsing: role key → CSV emails, comma-split, surrounding
  whitespace trimmed, blanks dropped, case normalized to lowercase;
- unknown role key → skipped, no crash;
- absent/blank value for a role key → empty set for that role;
- absent `role-emails` subsection → empty map;
- reading `role-emails` does not flip auth enabled/disabled.

## Out of scope

- Revocation via config (grant-only by decision).
- Any new role-gated routes or UI. This change only makes roles *grantable*;
  what admin unlocks is unchanged (`AvailabilityWatchController` owner-scope
  bypass today).
- Defining new `Role` enum values — the config is generic over whatever roles
  exist, but `ADMIN` is the only role today.
