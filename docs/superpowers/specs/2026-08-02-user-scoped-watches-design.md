# User-scoped availability watches

**Date:** 2026-08-02
**Status:** Approved (design)

## Problem

Availability watches ("availability alerts") are global and anonymous. Any
visitor — signed in or not — sees and can mutate every watch in the system.
`web/topbar/alerts.js` documents this in its own header: "Watches are global
(no auth), so this reflects everyone's watches." Now that auth exists (Clerk,
commit `3f764261`), a watch should belong to the user who created it:

- An unauthenticated visitor should not see the alerts panel at all.
- A signed-in user should see and manage only their own watches.
- An admin should see all watches (operational visibility).

## Current state (as mapped)

- **No owner column.** `availability_watch` has no `user_id`/owner. Scope lives
  in `availability_watch_target` (POI/campsite only). Existing watches are
  ownerless.
- **Routes are anonymous.** All five `/api/watches` endpoints are
  `.access(RouteAccess.Anonymous)` in
  `backend/.../route/api/availability/AvailabilityWatchRoutes.kt`.
- **Auth machinery exists.** `Principal` (`Anonymous`/`User(userId, roles)`/
  `System`) is resolved on every request into a call attribute; read via
  `ApplicationCall.principal()`. `RouteAccess.User` already returns 401 for
  anonymous callers through the `.access()` interceptor. `app_user`,
  `user_role` (role `admin`), and `/api/me` all exist.
- **Two frontend surfaces** render watches: the topbar panel
  (`web/topbar/alerts.js`, the screenshot) and the dedicated page
  (`web/watches/`). Both call `web/api/watches-api.js`. Neither gates on auth.
- **Poller** runs `active` watches independent of any user; notification
  destinations live in each watch's `trigger_config`.

## Decisions

1. **Legacy watches: delete.** The owner column is `NOT NULL`; existing
   ownerless watches are deleted in the migration.
2. **Anonymous API: 401.** All five routes flip to `RouteAccess.User`.
3. **Scope: both surfaces + admins see all by default.**

## Design

### Data model

New migration `V49__watch_owner.sql`:

```sql
-- Existing watches are ownerless and cannot be assigned; drop them.
-- Deleting availability_watch rows cascades to availability_watch_target
-- (and the V30 prune trigger is moot once the parent is gone).
DELETE FROM availability_watch;

ALTER TABLE availability_watch
  ADD COLUMN owner_user_id BIGINT NOT NULL REFERENCES app_user(id);

CREATE INDEX availability_watch_owner_idx ON availability_watch (owner_user_id);
```

- Verify the `availability_watch_target` FK to `availability_watch` is
  `ON DELETE CASCADE` before relying on the cascade; if it is not, delete
  targets (and pollers) explicitly first. Confirm against `V29`/`V30` during
  implementation.
- jOOQ regen picks up `OWNER_USER_ID` automatically — `availability_watch` is
  already in the `database.includes` allowlist.

### A proper domain `User`, not a loose caller tuple

Owner-scoping needs "who is this and are they an admin". Rather than pass loose
`(userId, isAdmin)` params around, thread a first-class domain user object. One
already exists in all but name — it's just misplaced and under-used:

- **`Principal.User(userId, roles)`** is the *request auth identity* ("who is
  making this call"), ambient per request. It is correctly thin and stays thin —
  it is an auth/request concept, **not** the account record. Not touched by this
  work.
- **`UserRepo.User(id, email, isEmailVerified, displayName, status, roles, …)`**
  is the *account entity*. This is the "User with id, isAdmin, name, email"
  object we want — but it is currently a repo-private nested type with no
  `isAdmin` convenience.

**Refactor:** promote that record to a canonical domain model
`backend/.../model/domain/auth/User.kt`:

```kotlin
data class User(
    val id: UserId,
    val email: String,
    val displayName: String?,
    val isEmailVerified: Boolean,
    val status: UserStatus,
    val roles: Set<Role>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val isAdmin: Boolean get() = Role.Admin in roles
}
```

`UserRepo` returns this same type (moves the existing nested `User` up a layer;
no new parallel type). Blast radius is small — the type is referenced by ~5
files (`UserSettingsService`, a few tests, sandbox routes); they get an import
change. This is a contained cleanup, not a cross-cutting refactor: it does **not**
enrich `Principal.User` or touch the existing settings/`me` re-fetch pattern —
watches simply follows that established pattern with a proper object.

### Backend layering (routes → service → repo)

Ktor types stay in `route/`; the controller takes the domain `Principal.User`
(services already do this, e.g. `UserSettingsService.read(Principal.User)`),
resolves the domain `User` from the repo, and scopes on it.

**Routes** (`AvailabilityWatchRoutes.kt`):

- Flip all five endpoints from `.access(RouteAccess.Anonymous)` to
  `.access(RouteAccess.User)`. Anonymous → 401 via the existing interceptor; no
  handler code runs.
- Each handler reads `call.principal()` and passes the `Principal.User` down.
  Under `RouteAccess.User` the principal is always `Principal.User` (or
  `System`); `Anonymous` was already refused by the guard.

**Controller** (`AvailabilityWatchController`): every method takes
`principal: Principal.User` and resolves the account once via
`userRepo.findById(principal.userId)` (the same pattern `UserSettingsService`
uses), giving a domain `User` with `id`/`isAdmin`.

- `list`: passes `ownerUserId = if (user.isAdmin) null else user.id` to the
  repo. `null` = admin-sees-all.
- `get`: fetch by id, then return `null` (→ 404) if the watch's owner ≠ `user.id`
  and `!user.isAdmin`. **404, not 403** — don't leak that a watch exists.
- `create`: stamps `ownerUserId = user.id`.
- `update`/`delete`: ownership assertion identical to `get` — a non-owner
  non-admin gets `NotFound` (→ 404), never mutating another user's watch.

**Service** (`AvailabilityWatchService`): `create(...)` gains an
`ownerUserId: UserId` param, threaded into `CreateInput`. Ownership *checks*
live in the controller (it already loads the row for update/delete); the service
keeps doing orchestration.

**Repo** (`AvailabilityWatchRepo`):

- `Watch` domain data class gains `ownerUserId: Long` (mapped in `fromRecord`).
- `CreateInput` gains `ownerUserId: Long`; `create()` sets
  `AVAILABILITY_WATCH.OWNER_USER_ID`.
- `scopeConditions(...)` gains `ownerUserId: Long?`: when non-null, add
  `AVAILABILITY_WATCH.OWNER_USER_ID.eq(ownerUserId)`; when null (admin), add
  nothing. `list()` and `count()` take and forward it.
- All SQL stays in the repo. No route/controller touches jOOQ.

**Poller: unchanged.** It selects `active` watches regardless of owner; owner is
purely a visibility/access concern here. The non-null owner column does not
affect poller reads. No notification-routing change in this PR.

### Frontend (both surfaces)

`HttpError` already carries `.status`, and `listWatches` rejects on non-2xx, so
a 401 is detectable in the existing `catch`.

- **`web/topbar/alerts.js`** — in `refresh()`, on a caught error with
  `status === 401` (signed out), hide the whole panel: set `watches = []`,
  `rootEl.hidden = true`, and return without rendering. On a successful authed
  fetch, unhide and render as today. Non-401 errors keep the current
  warn-and-degrade behavior.
- **`web/watches/watches-page.js`** — on 401, render a "Sign in to manage your
  alerts" empty state instead of the table/form.
- **Auth-change refresh.** `auth.js` `refresh()` dispatches a `document`-level
  `CustomEvent('roadtrip:auth-changed')` after it renders (both sign-in and
  sign-out paths reach `render`). `alerts.js` and `watches-page.js` listen for
  it and re-run their `refresh()` so the panel appears/disappears without a page
  reload. This is the one small cross-module seam added; it reuses the existing
  self-contained-module pattern (custom events, like `watches-changed`).
- **`web/api/watches-api.js`** — no signature change; requests already send the
  same-origin session cookie via `http.js`.

### Testing

- **Migration**: applying `V49` on a DB with existing watches deletes them and
  adds the non-null column (covered by the migration test harness).
- **Repo**: owner filter (own only), `ownerUserId = null` returns all,
  `create` persists owner, `fromRecord` maps it.
- **Controller/service**: non-owner `get`/`update`/`delete` → `NotFound`;
  owner and admin (`user.isAdmin`) succeed; `create` stamps the resolved user's
  id; `list` passes `null` for admin and the id otherwise. Existing tests that
  reference `UserRepo.User` compile against the promoted domain `User`.
- **Routes**: anonymous → 401 on all five endpoints; authed owner → 2xx.
- **Frontend**: `alerts.js` hides on 401 and renders when authed;
  `roadtrip:auth-changed` triggers a re-`refresh()`; watches page shows the
  signed-out empty state on 401.

### Out of scope

- Migrating notification routing to per-user settings (`user_settings` already
  exists; separate work).
- Sharing/transferring watches between users.
- Admin UI toggle to switch between "my watches" and "all" — admins see all by
  default; a toggle can come later.
