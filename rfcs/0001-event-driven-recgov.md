# RFC 0001: Event-driven recgov subsystem (token, availability, status)

- Status: Draft
- Author: William Chen
- Date: 2026-06-05
- Related: PR #17 (typed event scaffolding — first step)

## Decision log

Decisions agreed in design discussion. Each entry: **decision** — *rationale*. Add new entries to the bottom; do not edit historical entries (correct via a follow-up entry instead).

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-06-05 | **Backend owns the recgov token lifecycle** — companion stops doing its own refresh and reads via `GET /api/campsite/recgov/fresh-token`. | Single source of truth. Eliminates the parallel JS implementation in `companion/src/auth.js` that can drift from `RecgovAuth.kt`. |
| 2 | 2026-06-05 | **Trigger via events, not timers in managers.** Lifecycle managers (TokenManager, AvailabilityManager, StatusMonitor) subscribe; they don't run their own loops. | Triggers from a scheduler row vs. a UI click vs. a programmatic call all enter the same code path. Adding a new trigger source is one publish call. |
| 3 | 2026-06-05 | **Scheduler is dumb** — DB rows of `(name, event_type, payload, cadence_sec, enabled)`; one coroutine job per row that just publishes. | Keeps business logic out of scheduling. UI for managing cadence becomes simple CRUD on `schedules`. |
| 4 | 2026-06-05 | **Per-alert poll cadence** stored on `alerts.cadence_sec`, not in `schedules`. | Cadence is intrinsic to the alert; avoids a JOIN per scheduler tick. `schedules` table holds only system schedules. |
| 5 | 2026-06-05 | **Keep the existing global rate limiter** (`AvailabilityClient` 1.5s mutex). FIFO is acceptable; revisit when active alert count grows. | Per-alert cadences feed into the same throttle. Priority queue is over-engineered until the FIFO actually backs up — add a `PollDeferred` event when wait >5s as the trigger to revisit. |
| 6 | 2026-06-05 | **Coroutine-based scheduler**, not Quartz or `ScheduledExecutorService`. | Existing code is already coroutine/SuspendFn/Flow. Quartz brings JDBC tables and cron expressions we don't need. |
| 7 | 2026-06-05 | **Declarative cadences** (no persisted `next_fire_at`). Restart starts the cycle from now + jitter. | Simpler. We don't have schedules where missing one fire is catastrophic. Token refresh at 240s cadence will fire within seconds of a restart. |
| 8 | 2026-06-05 | **Typed events** via sealed `CampsiteEvent` interface, replacing stringly-typed `publish(type, data)`. | The event surface is growing meaningfully (15+ types). Compile-time mismatch detection. Dual-publish API in PR 1 lets us migrate one publisher at a time. |
| 9 | 2026-06-05 | **SSE wire format unchanged.** Wire-eligible events keep their historical names (`match`, `claimed`, `tick`, ...) via `sseType()`. | Frontend untouched. `tick` specifically is the connection heartbeat — breaking it would show phantom "disconnected" UI. |
| 10 | 2026-06-05 | **Companion fails closed** when backend unreachable — no auto-cart, log error. | Whole point of "backend owns the lifecycle." Stale token + Playwright session is worse than no action. SSE replay buffer + DB query backstop covers reconnect. |
| 11 | 2026-06-05 | **Race handling**: per-alert mutex `tryLock` + 30s `lastCheckedAt` debounce. | Lets a `UserPolledNow` arriving 200ms after a `PollDue` get cleanly skipped without dropping the user signal entirely. |
| 12 | 2026-06-05 | **Feature-flag PR 4** (`CAMPSITE_EVENT_DRIVEN=1`) for one release before defaulting on. | Replacing `Poller.kt` is the riskiest step. Old poller stays as a fallback during canary. |
| 13 | 2026-06-05 | **Tilt orchestrates the dev stack** (postgres → backend → companion). | Companion's dependency on backend is encoded in `Tiltfile` (`resource_deps=['backend']`), reinforcing decision #10 — companion can't even start until backend's `/api/health` is green. New endpoints introduced by this RFC must survive Tilt's live-reload restarts. |

## Summary

Move the recreation.gov token-refresh, campsite-availability polling, and recgov-health status concerns from their current ad-hoc, time-based, request-driven implementations to a unified event-driven architecture. The backend becomes the single source of truth for these lifecycles. Triggers come from either a **scheduler** (declarative cadence rows in Postgres) or **user actions** (HTTP routes that publish events). Lifecycle managers subscribe to a typed event bus and run the work; they do not own timers.

## Motivation

The campsite subsystem currently has three concerns wired up in different ways, each with concrete failure modes:

### 1. Token refresh
- Storage is already backend-owned (`settings` table, keys `recgov_token` / `recgov_refresh_creds`), and `RecgovAuth.refreshAccessToken` exists in Kotlin.
- But **nothing automatically refreshes the JWT.** Refresh only happens when:
  - The user clicks "Refresh Token" or "Test Chrome" in the settings UI.
  - The companion's pre-ATC check (`cart.js:setupAuthPage`) detects `<5min` to expiry and calls its **own** parallel JS implementation (`auth.js:refreshRecgovSession`).
- Failure mode: any call path that needs the token (cart-extend, claim, status) silently fails with 401 between explicit refreshes. The companion has its own copy of the refresh logic, which can drift from the backend's.

### 2. Availability polling
- Single global `poll_interval` setting (default 60s) runs every active alert through `Poller.kt` on a fixed cadence.
- No per-alert cadence: a wide-open campground 6 months out is polled at the same rate as a sniped near-term Yosemite slot.
- No demand-driven polling: opening an alert detail page in the UI does nothing extra.
- Failure mode: under-polling near-term alerts means missed sniping windows; over-polling far-out alerts wastes the global rec.gov rate-limit budget.

### 3. Status (`/api/campsite/status`)
- Probes recreation.gov **on every request**, cached 60s in-memory.
- UI has to know to ask. A failed poll or failed token refresh elsewhere doesn't update status — the user sees stale state until they explicitly hit the route.

### Concrete scenario this fixes
A user creates a near-term alert and walks away from the UI. Today: poll fires every 60s, and if the JWT happens to be near expiry when a match needs to be claimed via cart/extend, the call 401s. The user has no idea — status still says OK from a 60s-old probe. Tomorrow: the per-alert cadence is 15s for near-term alerts. The token-refresh schedule fires every 240s, well ahead of expiry. If anything fails, `RecgovDegraded` updates `/status` immediately and the UI's header indicator goes red.

## Goals

1. **Single source of truth for token lifecycle.** The backend owns the recgov JWT; the companion stops carrying its own refresh logic and local token cache.
2. **Per-alert poll cadence with a global rate limiter.** Alerts can be polled at different rates; the existing `AvailabilityClient` 1.5s mutex remains the global throttle.
3. **Demand-driven polling.** User actions (opening alert detail, "check now") publish events that managers consume just like scheduled triggers.
4. **Failure visibility.** When polls or token refreshes fail, status updates automatically and the UI sees it via SSE.
5. **No new long-running infrastructure.** Stay in-process with coroutines. No Quartz, no Redis pub/sub, no separate worker process.

## Non-goals

- Multi-tenant / multi-user. This is still a single-user system.
- Distributed scheduling. One backend instance owns the schedule. If we ever need HA, we'll revisit.
- Replacing the existing `EventBus` SSE wire format. The frontend's existing `match` / `claimed` / `tick` event names stay.
- Rewriting the matcher or `AvailabilityClient` HTTP code. Those stay as-is.

## Architecture

```
schedules table (DB)              EventBus (typed)              Lifecycle Managers
──────────────────             ───────────────────              ──────────────────
id, name, event_type,    ───→  publish(CampsiteEvent)  ───→    TokenManager
payload, cadence_sec,                                           AvailabilityManager
enabled                        Subscribers:                     StatusMonitor
                                 - SSE /events
alerts.cadence_sec             - lifecycle managers            (no timers, react to events)
(per-alert poll cadence)
                                       ▲
                                       │
                              user actions (HTTP routes)
                              publish UserRefreshToken,
                              UserPolledNow, etc.
```

### Three core principles

1. **The scheduler is dumb.** It knows nothing about tokens, polling, or campsites. A `schedules` row says "every N seconds, fire `event_type` with `payload_json`." The scheduler manages one coroutine job per row.
2. **Triggers are interchangeable.** A `PollDue(alertId=7)` event from the scheduler and a `UserPolledNow(alertId=7)` event from a UI click both arrive at the same subscriber (`AvailabilityManager`). Mutex + 30s debounce prevents double-firing. Adding a new trigger source is a publish call — no manager changes required.
3. **Per-alert cadence, global rate.** Each alert has its own `cadence_sec`. They all funnel through the existing `AvailabilityClient` 1.5s mutex, so the global rec.gov rate budget stays protected. FIFO is acceptable until alert count grows; we'll add a `PollDeferred` event when mutex wait exceeds 5s as an early warning.

### Component responsibilities

#### Scheduler (`scheduler/Scheduler.kt`)
- Reads `schedules` (system schedules: token refresh, lease sweep, companion sweep, liveness tick) and `alerts WHERE status='active'` (per-alert poll cadences).
- One coroutine job per row. Job body: `delay(jitter); while (isActive) { bus.publish(event); delay(cadence) }`.
- Startup jitter `0..cadence` per job avoids a thundering herd on deploy.
- `start()`, `stop()`, `reload()`, plus targeted `upsertAlert(id)` / `removeAlert(id)`.
- Reload is **explicit** — settings/alert routes call `scheduler.reload()` (or finer) on mutation. No DB watcher.

#### TokenManager (`auth/TokenManager.kt`)
- `suspend fun getFreshToken(): String?` — mutex'd. If `<5min` to expiry, refresh; else return cached.
- `suspend fun refreshNow(): Result` — explicit refresh, returns success/failure.
- `fun peek(): String?` — non-blocking, returns cached token without refresh.
- Subscribes to `TokenRefreshDue`, `UserRefreshToken`. Calls `getFreshToken()` internally and publishes `TokenRefreshed` / `TokenRefreshFailed`.
- Internal `consumer.token_requested` calls don't go through the bus (they call `getFreshToken()` directly with `peek()` semantics).

#### AvailabilityManager (`availability/AvailabilityManager.kt`)
- Replaces `Poller.kt` body.
- Subscribes to `PollDue(alertId)`, `UserPolledNow(alertId?)`, `UserViewedAlert(alertId)`.
- Per-alert `Mutex` with `tryLock` — scheduled and user-triggered polls coexist without double-firing.
- Per-alert `lastCheckedAt` for 30s debounce — a user click right after a scheduled poll is a no-op.
- Owns `AvailabilityClient` and `Matcher`. Publishes `PollStart`, `MatchFound`, `PollDone(alertId, success)`.

#### StatusMonitor (`monitoring/StatusMonitor.kt`)
- Subscribes to `PollDone(success=false)` and `TokenRefreshFailed`. Triggers a recreation.gov reachability probe (debounced: max once per 30s).
- Holds last-known state in `AtomicReference<RecgovStatus>`.
- Publishes `RecgovDegraded` / `RecgovRecovered`.
- `GET /api/campsite/status` reads the AtomicReference — **no probe in the request path**. Bootstrap probe on startup so first request isn't blank.

#### Companion (`companion/src/`)
- Becomes a thin backend client. Deletes `refreshRecgovSession`, `buildRecaccountFromToken`, `jwtFingerprint`, and the `recgov_token` / `recgov_refresh_creds` keys from its local store.
- New call: `GET /api/campsite/recgov/fresh-token` returns the recaccount-shaped JSON (`{access_token, expiration, account: {account_id, email, ...}}`) for Playwright injection.
- **Fails closed** when backend is unreachable: no auto-cart, log error. Matches still surface via SSE replay buffer when companion reconnects (existing 256-event window). DB query of `matches WHERE claimed_by IS NULL` already exists as a backstop.

### Event hierarchy

`CampsiteEvent` is a sealed Kotlin interface. Each variant carries `sseType()` (null = internal-only) and `sseData()` (JSON string for the wire). Wire-eligible names match today's frontend (`match`, `claimed`, `tick`, ...).

| Category | Events |
|---|---|
| Scheduled | `PollDue(alertId)`, `TokenRefreshDue`, `LeaseSweepDue`, `CompanionSweepDue`, `LivenessTick` |
| User-initiated | `UserPolledNow(alertId?)`, `UserViewedAlert(alertId)`, `UserRefreshToken` |
| Outcomes | `MatchFound`, `PollStart`, `PollDone`, `Claimed`, `Result`, `LeaseExpired`, `CompanionOffline`, `CompanionOnline`, `TokenRefreshed`, `TokenRefreshFailed`, `RecgovDegraded`, `RecgovRecovered` |

Internal-only events (`PollDue`, `UserPolledNow`, `TokenRefreshDue`, ...) skip the SSE wire.

### Database schema

```sql
ALTER TABLE alerts ADD COLUMN cadence_sec INT NOT NULL DEFAULT 60 CHECK (cadence_sec >= 5);
UPDATE alerts SET cadence_sec = COALESCE((SELECT value FROM settings WHERE key='poll_interval')::int, 60);

CREATE TABLE schedules (
  id           BIGSERIAL PRIMARY KEY,
  name         TEXT UNIQUE NOT NULL,
  event_type   TEXT NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}',
  cadence_sec  INT NOT NULL CHECK (cadence_sec >= 1),
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schedules (name, event_type, cadence_sec) VALUES
  ('token_refresh',    'TokenRefreshDue',    240),
  ('lease_sweep',      'LeaseSweepDue',        5),
  ('companion_sweep',  'CompanionSweepDue',    5),
  ('liveness_tick',    'LivenessTick',        10);
```

`alerts.cadence_sec` is denormalized onto the alert intentionally — cadence is intrinsic to the alert and avoids a JOIN per scheduler tick. The `schedules` table is for **system** schedules only.

After data migration, `settings.poll_interval` is dropped and the global cadence UI is replaced with per-alert cadence inputs.

## Migration plan

7 PRs, each independently shippable. The riskiest is PR 4 (replacing `Poller.kt`), which ships behind a `CAMPSITE_EVENT_DRIVEN=1` feature flag for one release before defaulting on.

| PR | Scope |
|---|---|
| 1 | Typed event scaffolding (sealed `CampsiteEvent`, dual-publish `EventBus`) — **#17 open** |
| 2 | `V3` migration + `Scheduler` component. Move lease/companion sweeps + liveness tick from `Campsite.kt` to event subscribers. Don't touch Poller yet. |
| 3 | `TokenManager` + `GET /api/campsite/recgov/fresh-token`. Routes that read `settings.recgov_token` directly switch to `tokenManager.peek()`. |
| 4 | `AvailabilityManager` (replaces `Poller.kt`). Per-alert `cadence_sec`. Behind feature flag. |
| 5 | `StatusMonitor`. `/status` reads cached state, no probe in request path. |
| 6 | Companion thin client. Delete local refresh logic; call `/recgov/fresh-token`. Ship ≥24h after PR 3 deploys. |
| 7 | Drop the legacy `publish(String, String)` overload from `EventBus`. |

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| **Race: `PollDue(7)` and `UserPolledNow(7)` arrive within ms** | Per-alert mutex `tryLock` (mirrors today's `pollMutex.tryLock` in `Poller.kt:71`). User click after recent scheduled poll → 30s debounce skips. |
| **Rate limiter under load with per-alert cadences** | Existing 1.5s FIFO mutex is fine for current alert count. Add `PollDeferred` event when mutex wait >5s as an early warning. Priority queue is over-engineered until proven needed. |
| **Scheduler thundering herd on restart** | Startup jitter `delay(0..cadence)` per job. |
| **Companion offline missing matches** | 256-event SSE replay buffer handles short outages. DB fallback (`matches WHERE claimed_by IS NULL`) already exists for longer outages. Companion fails closed on backend unreachability — no rogue ATC with stale token. |
| **`tick` SSE name preservation** | `LivenessTick.sseType()` returns `"tick"` and stays on ~10s cadence. Frontend uses `tick` as a connection heartbeat — breaking it would show phantom "disconnected" UI. |
| **Typed-vs-string transition breaking existing publishers** | Dual-publish API in PR 1: `publish(type, data)` is preserved as a `Legacy` shim. PRs 2–6 migrate one publisher at a time. PR 7 removes the shim. |
| **PR 4 breaks polling in prod** | Feature flag `CAMPSITE_EVENT_DRIVEN=1` for one release. Old `Poller.kt` is the fallback. Default-on after one release of clean canary. |
| **Token refresh failures cascading into all features** | `TokenManager.peek()` is non-blocking — routes that don't strictly need a fresh token (e.g., `/status` `loggedIn` flag) keep working. `getFreshToken()` is for paths that must succeed (cart/extend, claim). |

## Alternatives considered

### A. Quartz scheduler
Industry-standard Java scheduler with cron expressions, persistent state, misfire handling.
- **Rejected:** brings JDBC tables, separate config, and conceptual overhead. We don't need cron expressions. Coroutine-based loops fit the existing codebase style and are 50 lines of code.

### B. ScheduledExecutorService
Plain JDK scheduling.
- **Rejected:** more ceremony than coroutines, doesn't compose naturally with the rest of the suspend-fn / Flow code.

### C. Time-based managers (no scheduler indirection)
Each manager owns its own timer. `TokenManager` has its own coroutine that wakes every 240s; `AvailabilityManager` walks `alerts` every tick.
- **Rejected:** loses interchangeability of triggers. User actions and scheduled triggers go through different code paths. UI tweaking cadence requires manager-specific config rather than uniform `schedules` CRUD. Adding a fourth concern (e.g., notification-channel health probes) requires a new bespoke timer instead of just inserting a `schedules` row.

### D. Persistent scheduler state (durable `next_fire_at`)
Save next-fire timestamps to the DB so a 5-minute schedule doesn't drift on restart.
- **Rejected for now:** declarative cadences with startup jitter are simpler. We don't have schedules where missing one fire is catastrophic. Token refresh at 240s cadence will fire within seconds of restart; the worst case is a brief 401 window on a poll that races a deploy. Revisit if we add genuinely sparse schedules (e.g., daily reports).

### E. JSON-string events forever (no sealed type)
Keep `publish(type: String, data: String)`. Subscribers parse JSON.
- **Rejected:** with the event surface growing meaningfully (15+ types), typed events catch publish/subscribe mismatches at compile time. The migration-friendly dual-publish API in PR 1 lets us move incrementally without losing the existing string callers.

## Verification

Per-PR smoke tests in the migration plan. End-to-end after PR 4:

1. Create two alerts: alert A with `cadence_sec=30`, alert B with `cadence_sec=120`.
2. Wait 60s. Verify two `PollDue(A)` events and zero `PollDue(B)` events in logs.
3. Open alert B's detail page in the UI. Verify a `UserViewedAlert(B)` event and an immediate poll for B.
4. Click "check now" on alert A immediately after a scheduled poll. Verify the debounce fires (no extra rec.gov call) but the event is published.
5. Disable network to `recreation.gov`. Verify `PollDone(success=false)` → `RecgovDegraded` → `/status` updates within 30s.
6. Re-enable network. Verify `RecgovRecovered` → `/status` clears.
7. Insert a row `INSERT INTO schedules (name, event_type, cadence_sec) VALUES ('test', 'TokenRefreshDue', 5)` and call `scheduler.reload()`. Verify token refresh cycle fires every 5s (use a logged refresh count).

## Open questions

1. **Schedule CRUD UI:** does the user want a dedicated "Schedules" page in the settings UI, or just expose individual cadence fields (token refresh interval, alert cadence) in the existing settings page? Initial implementation will keep it simple — token-refresh cadence as a single field, per-alert cadence on the alert form.
2. **Cadence decay for far-out dates:** PR 4 ships with a single `cadence_sec` per alert. A future enhancement could decay cadence based on how far out the search window is (e.g., dates >30d out poll every 5min, <7d every 30s). Out of scope for this RFC; revisit if it becomes a felt need.
3. **Authentication on `/recgov/fresh-token`:** companion shares localhost with backend. Same trust boundary as existing `/companion/heartbeat`. If we ever expose backend over the network without the cloudflared tunnel ACL, this endpoint needs auth.
