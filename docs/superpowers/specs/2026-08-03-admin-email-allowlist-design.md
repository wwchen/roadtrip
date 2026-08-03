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

New subsection under the existing `auth` block, keyed by role, with each role's
emails as an **inline YAML array** committed to the config file:

```yaml
roadtrip:
  auth:
    role-emails:
      admin:
        - you@example.com
      # add more role keys here as roles are added to the Role enum, e.g.
      # editor:
      #   - editor@example.com
```

- Each key under `role-emails` is a `Role` wireValue (`admin` today). Its value
  is a YAML list of emails, written directly in the config file — no env var, no
  `${...}` placeholder, no deploy-time injection.
- **Inline array, not a comma-separated string.** This works with no new parsing
  code because the YAML flattener (`ApplicationProperties.flattenMap`) already
  collapses any YAML list into a comma-joined string
  (`is List<*> -> value.joinToString(",")`), and `ConfigSection.csvSet(name)`
  splits it back (trim, drop blanks → `Set<String>`). Emails never contain
  commas, so the array → `"a,b"` → set round-trip is lossless. `AuthConfig` then
  lowercases each entry for case-insensitive matching (`csvSet` does not
  lowercase).
- Unknown role keys (no matching `Role.parse`) are skipped, so a stale config
  key never crashes startup.
- A role key with an empty list contributes an empty set (no grants).
- The whole subsection absent ⇒ empty map ⇒ nobody is auto-granted.

`AuthConfig` gains one field: `val roleGrants: Map<Role, Set<String>>`,
populated in `AuthConfig.fromConfig` by enumerating the `role-emails`
subsection. It lives on `AuthConfig`, which is `null` when auth is disabled — so
in local / CI / sandbox (auth off) the allowlist is inert and the sandbox seed
SQL stays the local admin path.

### Where the value lives (committed config, no secrets, no env)

The email list is authorization *configuration*, not a credential — knowing the
list grants nothing, since gaining the role still requires controlling that
email's IdP account and completing a verified sign-in. It is written as a plain
literal in the config file and committed to git:

- No `secrets/registry.yaml` entry, no encryption, no `/run/secrets` mount — it
  is not a secret.
- No `docker-compose.yml` env line and no `SecretRegistryDriftTest` change: the
  drift test only inspects `${...}` placeholders, and an inline literal is not
  one. This keeps the change to the backend config + code only.
- Prod-specific lists belong in the `application-prod.yaml` overlay (merged over
  the base by `ApplicationProperties`), so a prod-only admin list does not leak
  into local/CI. The base `application.yaml` can carry an empty
  `role-emails:` block (or omit it) so nobody is granted by default.
- Changing the admin list is a config commit + redeploy — acceptable for a
  rarely-changing bootstrap list. To bootstrap the first admin: add your
  Clerk-verified email under `admin:` and deploy. The email the IdP verifies at
  sign-in must match an entry; that is the link between "the email in config"
  and "the person who becomes admin".

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
- `backend/src/main/resources/application.yaml` — empty/omitted `role-emails:`
  block under `auth` (nobody granted by default).
- `backend/src/main/resources/application-prod.yaml` — `role-emails:` block with
  the inline `admin:` list for prod (merged over the base overlay).
- `service/auth/UserProvisioningService.kt` — new `roleGrants` constructor
  param; converge resolution paths; grant each matching role when the verified
  email is allowlisted.
- `di/RouteModule.kt` — pass `authConfig.roleGrants` into
  `UserProvisioningService`.

No `docker-compose.yml`, `secrets/registry.yaml`, or `SecretRegistryDriftTest`
changes: the list is an inline committed literal, not a `${...}` placeholder or
a secret.

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
- `role-emails` parsing: an inline YAML array under a role key → `Set<String>`,
  with entries lowercased (exercises the array → comma-joined-string → `csvSet`
  round-trip via the flattener, plus whitespace trim / blank drop);
- unknown role key → skipped, no crash;
- empty list for a role key → empty set for that role;
- absent `role-emails` subsection → empty map;
- reading `role-emails` does not flip auth enabled/disabled.

## Out of scope

- Revocation via config (grant-only by decision).
- Any new role-gated routes or UI. This change only makes roles *grantable*;
  what admin unlocks is unchanged (`AvailabilityWatchController` owner-scope
  bypass today).
- Defining new `Role` enum values — the config is generic over whatever roles
  exist, but `ADMIN` is the only role today.
