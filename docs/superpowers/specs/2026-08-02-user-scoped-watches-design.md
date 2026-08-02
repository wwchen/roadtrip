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

### Backend layering (routes → service → repo)

The caller identity flows down as domain values, not Ktor types (routes own the
`Principal`; services take plain `UserId`/flags).

**Routes** (`AvailabilityWatchRoutes.kt`):

- Flip all five endpoints from `.access(RouteAccess.Anonymous)` to
  `.access(RouteAccess.User)`. Anonymous → 401 via the existing interceptor; no
  handler code runs.
- Each handler reads `call.principal()` and passes a resolved `WatchCaller`
  (see below) to the controller. `Principal.User` and `Principal.System` both
  reach the handler under `RouteAccess.User`.

Introduce a small caller value the controller consumes so routes don't leak
`Principal` downward and the controller has one clear input:

```kotlin
// service/availability — the identity a watch operation runs as.
data class WatchCaller(val userId: UserId, val isAdmin: Boolean)
```

Routes map `principal()` → `WatchCaller`:
- `Principal.User(id, roles)` → `WatchCaller(id, isAdmin = Role.Admin in roles)`.
- `Principal.System` → treated as admin (operational). Not reachable from these
  routes today, but keeps the mapping total.
- `Principal.Anonymous` is unreachable (the guard already denied it).

**Controller** (`AvailabilityWatchController`): every method takes
`caller: WatchCaller`.

- `list`: passes `ownerUserId = if (caller.isAdmin) null else caller.userId`
  to the repo. `null` = admin-sees-all.
- `get`: fetch by id, then return `null` (→ 404) if the watch's owner ≠ caller
  and caller is not admin. **404, not 403** — don't leak that a watch exists.
- `create`: stamps `ownerUserId = caller.userId`.
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
  owner and admin succeed; `create` stamps caller; `list` passes `null` for
  admin and the id otherwise.
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
