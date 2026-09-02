# Stack #698 / #699 / #700 — simplify to fix

Tracker for the adversarial review of the three stacked PRs. Every bug below
is fixed by removing or collapsing code, not by adding a branch next to the one
that broke. Rule of thumb for each item: the diff should be net negative or
neutral, and where a test was deleted to make the bug possible, the test comes
back.

Legend: `[ ]` open · `[~]` in progress · `[x]` landed · `[-]` dropped (say why)

Severity: **B** blocking, **C** confirmed lower-severity, **P** plausible only,
**Q** quality/altitude (no bug, but removes the surface bugs grow on).

---

## PR 1/3 — #698 companion service

### 1.1 One lease helper for both cross-namespace claims — **B**
Bugs: `store.js:173` store lock is created empty then filled; a reader in the
gap parses `''`, calls it stale, deletes the live lock, two writers race and one
profile's cookie jar is lost. `browser.js:154` owner lease is a truncating
`writeFileSync` every 5s; an unreadable lease reads as "free", so the other
side sweeps the SingletonLock of a live Chromium. Clock skew > 30s does the same
deterministically.

Simplification: the store lock and the profile-dir lease are the same thing
(wall-clock claim across pid namespaces) written two different ways. Replace
both with one module: `writeClaim(file, payload)` = write temp + rename, and
`readClaim(file)` where unreadable means **held**, never free. Delete
`lockIsStale`'s catch-returns-true, `readOwner`'s catch-returns-null, and the
raw `openSync('wx')` + `writeFileSync(fd)` sequence.
- [ ] `companion/src/store.js` `withStoreLock`, `lockIsStale`
- [ ] `companion/src/browser.js` `writeHeartbeat`, `readOwner`, `clearStaleLocks`
- [ ] Test: empty claim file is treated as held; second writer waits

### 1.2 Unattended MFA falls through to the normal failure path — **B**
Bug: `auth.js:231` answers `mfa_required` before `recordLoginFailure`, so an
MFA-gated account gets no backoff and every backend re-login re-submits the
password, which sends the user a new code each time.

Simplification: delete the early return. An unattended MFA prompt is a failed
unattended login. Let it reach the one `status.logged_in !== true` branch that
already records the failure, and let that branch carry `error: mfa_required`.
One exit for "not logged in" instead of three.
- [ ] `companion/src/server/routes/auth.js` `handleLogin`
- [ ] Test: unattended `mfa_required` sets `backoffUntil`

### 1.3 Five routes, one prologue — **Q**
`destroy.js:28` and four siblings inline the same token/profile/lock preamble;
`readRequestFields.raw` is never read. Extract one `profileRoute(handler)`
wrapper, delete `raw`.
- [ ] `companion/src/server/routes/*.js`

### 1.4 Companion loose ends — **C/Q**
- [ ] `profilePool.js:211` `closeState` nulls `state.context` before `close()`
      resolves, so a concurrent `context()` relaunches on a dir still closing.
      Await the close before clearing, or key the `once('close')` guard off
      the promise. Net zero lines.
- [ ] `server.js:194` unscoped `/health` reports `recgov_auth` frozen at
      `unchecked`. Delete the field from the unscoped answer rather than
      compute it.
- [ ] `server.js:223` per-profile `/health` re-parses the whole store and
      returns an unread `pool` snapshot. Delete the snapshot.
- [ ] `atc.js:63` two log lines for one event, one without the JSON envelope.
      Keep the enveloped one.
- [ ] `store.js:164` `Atomics.wait` + `fsyncSync` block the event loop. Only
      matters once 1.1 lands; revisit then, do not fix separately.
- [ ] `cart.js:162` bare `waitForTimeout(300)` → named constant (AGENTS.md).
- [ ] `cart.js:163` **P**: the deleted URL-param fallback in `enterDates`.
      Confirm with a headed run before restoring anything.
- [ ] `Makefile:15` still honours the removed `RECGOV_COMPANION_BROWSER_PROFILE`.
      Delete the line.
- [ ] `browser.js:77` 34-line comment block → three lines.

---

## PR 2/3 — #699 credentials + settings

### 2.1 A username change is a fresh save — **B**
Bug: `RecGovCredentialService.kt:193` `save()` only demands a password when no
cipher exists. Changing alice → bob with a blank password wipes alice's working
profile, then stores bob's username over alice's cipher. Every later re-login
submits the wrong pair and trips backoff.

Simplification: collapse the two conditions into one predicate.
`val fresh = stored?.recgovPasswordCipher == null || stored.recgovUsername != username`
and require a password when `fresh`. The wipe then only ever runs with a new
cipher in hand.
- [ ] `RecGovCredentialService.save`
- [ ] Test: username change without password is rejected, profile untouched

### 2.2 Remove is local-first, companion best-effort — **B**
Bug: `RecGovCredentialService.kt:255` `remove()` throws
`RecgovProfileWipeFailed` (502) when the companion is down **or** the profile
lock is held, including by the user's own abandoned MFA challenge for its full
5-minute TTL. Credentials cannot be removed or swapped. Meanwhile
`RecgovRemovedDto.profileDestroyed=false` and `SettingsModal.removedMessage()`
model an outcome that is now unreachable.

Simplification: pick one contract and delete the other half. Recommended:
remove always clears the row, and reports honestly what the companion did
(`companionSignedOut`, `profileDestroyed`). That deletes the throw on the
remove path and makes the existing DTO fields and frontend copy true again.
Companion side: `POST /destroy` must not `pool.acquire` behind a challenge's
lock; call `pool.destroyProfile` directly so its `dropChallenge` branch is
reachable and delete the acquire.
- [ ] `RecGovCredentialService.remove` / `wipeProfileOrRefuse` (keep the
      refuse posture for `save` only, or drop it there too under 2.1)
- [ ] `companion/src/server/routes/destroy.js`
- [ ] Test: remove with companion down returns 200, `profile_destroyed=false`
- [ ] Test: destroy while a challenge holds the lock succeeds

### 2.3 Login machine: one rule for `mfa_required` — **B**
Bug: `BookingPanel.tsx:159` a `profile_busy` answer to a code submission
dispatches `mfa_required` from `submitting`; `booking-login.ts:67` only accepts
it from `logging_in`, so the machine sticks in `submitting` with every button
(Cancel included) disabled.

Simplification: change the table, not the caller. `mfa_required` from any busy
state → `mfa_pending` (keep the existing `challengeId` when the event carries
none). Then delete the `PROFILE_BUSY_CODE && serverHasChallenge` special case
in `applyLogin`, and delete the dead `challengeId` / `pendingChallengeId`
plumbing (`booking-login.ts:46`) the review found unread.
- [ ] `frontend/src/features/account/booking-login.ts`
- [ ] `frontend/src/features/account/BookingPanel.tsx`
- [ ] Test: `submitting` + `mfa_required` → `mfa_pending`, Cancel enabled

### 2.4 Settings mutations: one invalidation, one refresh — **C**
- [ ] `useSettings.ts:112` save/remove invalidate only `['settings']`; grid
      capabilities stay stale. Invalidate the shared prefix both queries hang
      off instead of listing keys.
- [ ] `useSettings.ts:169` `refresh()` after login/mfa/verify is skipped when
      the call throws `HttpError`. Move it to `finally`.
- [ ] `BookingPanel.tsx:269` Verify not gated on `awaitingCode`. Reuse the
      same `busy || dirty || awaitingCode` guard `startLogin` has; one const.

### 2.5 Six handlers, one try/catch — **Q**
`RecgovSettingsRoutes.kt:62` repeats the SettingsError→status skeleton six
times. One `respondSettings { }` helper.
- [ ] `RecgovSettingsRoutes.kt`

### 2.6 One refresh, one error field — **C/Q**
- [ ] `RecGovCredentialService.kt:411` `reLogin` refreshes again after the
      adapter's `recover` already did. Delete the inner refresh; `reLogin`
      means credentials.
- [ ] `CompanionSessionClient.kt:300` `actionResult()` reads top-level
      `error`; the companion nests it under `recgov_auth`, so refresh/logout
      reasons are lost. Fix the companion to answer `error` at the top level
      on every route (it already does for login), and keep the client's single
      reader.
- [ ] `WatchTriggerCapabilityValidator.kt:66` and `AtcTriggerActionHandler.kt:42`
      each SELECT `user_settings` twice per call. Read once, pass down.

### 2.7 Altitude — **Q**
- [ ] `WatchCapabilityService.kt:83` `canFulfilAddToCart` is a rec.gov-only
      gate in a provider-agnostic service. Push it behind
      `BookingProvider.canFulfil(owner)` so the service asks the provider.
- [ ] `V53__user_recgov_credentials.sql:20` per-provider columns on
      `user_settings`. Do **not** edit the applied migration; note for a V54
      that moves to a `user_provider_credentials` table if a second vendor
      lands.
- [ ] `RecGovKeepaliveJob.kt:37` 25/21-line comment blocks → trim.

---

## PR 3/3 — #700 ATC + observability

### 3.1 Delete `HttpRecGovAtcExecutor`; one companion transport — **B**
Bugs: `CompanionSessionClient.kt:358` and `HttpRecGovAtcExecutor.kt:65` both
`catch (e: Exception)`, which swallows `CancellationException`. On shutdown the
keepalive sweep issues one doomed request per remaining profile and bumps the
`unavailable` metric each time; a cancelled fire is emailed to the owner as a
companion failure. `ProviderUpstreamErrors.kt:46` already documents the trap.

Simplification: `HttpRecGovAtcExecutor` re-implements the session client's
request builder, token header, JSON parsing, success range and constants.
Delete the file. Add `addToCart(payload)` to `CompanionSessionClient` as one
more `post("/atc")`. Fix `send()` once: rethrow `CancellationException`, catch
`IOException` / `HttpTimeoutException` only. ~120 lines gone, one place to be
wrong.
- [x] Delete `client/companion/HttpRecGovAtcExecutor.kt` (+ its test and DI
      wiring). `CompanionChannel` now hands out one client through both ports.
- [x] `CompanionSessionClient.send` exception filter: rethrow
      `CancellationException`, catch `IOException` only.
- [x] `CompanionSessionClient.addToCart`
- [x] Test: cancellation propagates out of `send` (checked that it fails
      against the old `catch (e: Exception)`)
- [x] Found while landing this: the two `runCatching` callers on the fire path
      re-swallowed what `send` now rethrows — `RecGovBookingAdapter:170` and
      `AtcTriggerActionHandler:80` — so both moved to one new
      `support/runCatchingCancellable`.

### 3.2 Adapter classifies, route maps categories — **B**
Bugs: `RecGovBookingAdapter.kt:145` rewrites every recovery failure
(`not_configured`, `profile_busy`, `login_backoff`, `browser_cap_reached`,
`companion_unavailable`, `mfa_required`) to `recgov_session_expired` and emails
"re-login in Settings" to a user who may have just removed their credentials.
`BookingRoutes.kt:19` imports `RecGovSessionCodes` and classifies raw vendor
codes (a leaky-abstraction violation per AGENTS.md); `settings-errors.ts`
re-does the same classification; `recgov_not_authenticated` falls through to
502 (`BookingRoutes.kt:74`).

Simplification: the adapter is the only layer that knows companion codes. It
maps them once into three `BookingActionCodes` categories
(`CALLER_ACTION`, `RETRY_LATER`, `UPSTREAM`) with the raw code in `detail`.
Delete `PreflightBlocker`'s constant-substitution, the two code sets in
`BookingRoutes`, the `RecGovSessionCodes` import, and the duplicate table in
`settings-errors.ts`. Route becomes a three-arm `when`.
- [x] `RecGovBookingAdapter.reLogin` passes the companion's real code and detail
      through; one `failureCategories` table in the adapter classifies them.
- [x] `BookingRoutes.kt` three-category status map. Both code sets and the
      `RecGovSessionCodes` import are gone; `AddToCartResult.Failed` and
      `AddToCartOutcome.Failed` carry a `BookingFailureCategory`.
- [-] `frontend/src/.../settings-errors.ts` drop the vendor-code table —
      **dropped, deliberately.** That table is *copy*, not classification: nine
      distinct sentences, several of which record earlier fixes ("three distinct
      misses that all used to read as `cart_not_added`"). Collapsing it to three
      category messages would lose real user-facing precision. The category
      decides the status; the code still travels in the body and still decides
      the sentence, so the file needs no change at all.
- [x] Test: a refused recovery reports its own code and category
      (`mfa_required`, `recgov_not_configured`, `browser_cap_reached`,
      `profile_busy`, `companion_unavailable`), not a blanket expiry.
- [x] The end-to-end email test asserted the *wrong* copy — an MFA-blocked owner
      was mailed "session expired — re-login in Settings". Now asserts the
      companion's own reason reaches the mail.

### 3.3 Busy is a retry, not an expiry — **B** (same site as 3.2)
`RecGovBookingAdapter` health preflight is lock-free and no longer detects
`profile_busy`; when the keepalive holds the lock at fire time the POST gets
409, nobody retries, the hold is lost. The 30s `fireTimeout` is also shorter
than a companion refresh (launch + 20s nav + 3 attempts) and the companion never
aborts on client disconnect.

Simplification: no retry loop in the adapter. Keepalive and fire contend for
the same per-profile lock; make the keepalive **skip** a profile whose `atc`
watch fired in the last N seconds, and let a `RETRY_LATER` fire outcome from
3.2 leave the watch armed for the next poll edge (it already re-fires on the
next opening). Raise `fireTimeout` above the companion's refresh ceiling and
derive it from the companion config rather than a second constant.
- [x] `RecGovKeepaliveJob` skip-recently-fired. One in-memory `RecentAtcFires`,
      written by the adapter just before it drives the browser and read by the
      sweep; the profile stays *armed*, only its refresh is skipped. No column
      and no migration — the keepalive is documented as not load-bearing, so a
      restart that forgets a fire costs one avoidable refresh.
- [x] `RecGovAtcConfig.fireTimeout` derived: `companionTimeout / 3` (60s at the
      180s default, up from a flat 30s that sat below the companion's own
      refresh ceiling). An explicit `fire-timeout` still wins.
- [x] "A `RETRY_LATER` fire leaves the watch armed" needed no change: the
      failure branch already returns `false`, so `stopWhenTriggered` never
      quiesces and the next poll edge re-fires.

### 3.4 Only RESERVED is evidence — **B**
Bug: `AvailabilityRepo.kt:214` `freshlyUnavailableDates` filters on
`!available`, which is also true for `UNKNOWN` and `CLOSED`. Four providers
write `UNKNOWN` for dates absent from the vendor response, so a cross-provider
campground refuses add-to-cart with "somebody took the site".

Simplification: stop reinterpreting the derived boolean. Filter on
`status == RESERVED`. If `readCurrent` does not surface `status`, surface it
and delete the `available` projection from that read path.
- [ ] `AvailabilityRepo.freshlyUnavailableDates`
- [ ] Test: fresh `UNKNOWN` cell does not refuse

### 3.5 Restore "unsupported openings stay inert" — **B**
Bug: `AtcTriggerActionHandler.kt:58` now emails **and** Slacks a failure on
every open/close/reopen edge when no opening resolves to a target, regardless
of notify flags, and returns `false` so `stopWhenTriggered` never quiesces the
watch. The test that guarded this was deleted in `4ed2b74e`.

Simplification: delete the `reportResult` block in the empty-`pending` branch,
keep the warn log and the `NO_TARGET` metric, restore the deleted test. The
owner is already told at write time by `WatchTriggerCapabilityValidator`; a
target that later drifts is an operator signal (metric), not owner mail.
- [ ] `AtcTriggerActionHandler.fire`
- [ ] Restore test "AtcTriggerActionHandler leaves unsupported openings inert"

### 3.6 Keepalive: armed owners only — **C**
Bug: `RecGovKeepaliveJob.kt:187` probes every credentialed user sequentially
(up to 30s each) **before** truncating to `maxProfiles`.

Simplification: delete `hasSessionWorthKeeping` and the credentialed tail
entirely. Keep warm exactly the distinct owners of active `atc` watches. A
credentialed user with no armed watch pays a cold start on their first fire,
which is the documented non-load-bearing case. Removes a method, a companion
round-trip per user, and the truncation log.
- [ ] `RecGovKeepaliveJob.keepWarmProfileIds`
- [ ] `RecGovKeepaliveJob:76` loop scaffolding copied from `WatchReaper` →
      one `PeriodicSweep` base, or reuse `WatchReaper`'s.

### 3.7 Stranded ATC watches — **C**
Bug: `WatchTriggerCapabilityValidator.kt:88` a watch stranded by credential
removal cannot be edited or resumed (the write-time gate refuses it), and the
Slack resume path does not catch the exception.

Simplification: delete the "stranded" concept. `remove()` pauses the owner's
active `atc` watches (one repo update, already have `countByTriggerKind`), the
DTO drops `strandedAtcWatches`, and the fire-path "fail loudly" branch for
missing credentials goes with it. Editing a paused watch that adds nothing new
passes the gate.
- [ ] `RecGovCredentialService.remove`
- [ ] `RecgovRemovedDto`, `SettingsModal.removedMessage()`
- [ ] `WatchTriggerCapabilityValidator` gate applies to *adding* `atc` only

### 3.8 Shared constants and helpers — **Q**
- [ ] `"completed"` declared in `AtcTriggerActionHandler:252`,
      `SlackNotificationService:22`, `EmailContentAtcResultRenderer:17` →
      use `BookingActionStatus.COMPLETED` (`BookingActionDto.kt:11`).
- [ ] `elapsedMsSince` (`AtcTriggerActionHandler:172`) is the third
      elapsed-ms helper and the only one without `coerceAtLeast(0)`. One
      helper next to `CatalogAvailabilityBatcher.elapsedMs`.
- [ ] `EmailContentAtcResultRenderer.kt:124` `textField` duplicates
      `CompanionJson.stringValue`. Delete.
- [ ] `BookingRoutes.kt:151` `requireBookingUser` is a third `requireUser`;
      `ERROR_INVALID_BODY` redeclared; `NOT_AVAILABLE` arm dead after 3.2.
- [ ] `AvailabilityBookingTargetResolver.kt:81` `declaredTarget` passes the
      raw ref column as `vendorSiteId`. Parse once at the resolver seam.
- [ ] `BookingActionService.kt:175` hardcoded `RECGOV_CART_URL` and vendor copy
      in the generic service; move to the adapter.

### 3.9 Frontend grid — **C/Q**
- [ ] `SiteMatrix.tsx:725` a `held` cell is never cleared by refetch. Derive
      `held` from the cart-action result *and* the latest cell status rather
      than storing it.
- [ ] `SiteMatrix.tsx:774` `CellSpinner` / `CartChipSpinner` byte-identical.
      One component.
- [ ] `CellBookPopover.tsx:108` both actions drop focus to `body`. Return
      focus to the trigger cell in one `onClose`.
- [ ] `WatchEditor.tsx:84` pending `/api/me` reads as signed out. Three-state
      (`loading | user | null`), render nothing while loading.
- [ ] `cart-action.ts:20` `CartAction.failed` and `held.cartUrl` never read.
      Delete.

### 3.10 Observability — **C**
- [ ] `grafana/provisioning/alerting/roadtrip.yml:457` "ATC preflight over
      budget" thresholds end-to-end latency at 30s, so healthy holds alert.
      Either alert on the preflight span only or raise the threshold to the
      fire timeout from 3.3. One source for the number.

---

## Order of work

1. 3.1 → 3.2 → 3.3 (one transport, one code map; everything downstream
   simplifies once codes are categories).
2. 1.1, 1.2 (companion races and backoff; independent of the backend work).
3. 2.1, 2.2, 2.3 (credential lifecycle; 2.2 depends on the companion
   `destroy` change).
4. 3.4, 3.5, 3.6, 3.7.
5. Q items, batched per PR so each PR's diff still reads as one change.

Each item lands on its own PR in the stack (`pr1-…`, `pr2-…`,
`pr3-atc-observability`) and merges forward, so the stack stays reviewable.
