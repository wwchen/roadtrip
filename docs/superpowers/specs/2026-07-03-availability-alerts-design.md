# Availability alerts (PR 6): Slack notify on cube edge

Date: 2026-07-03
Status: proposed
Related: `docs/superpowers/specs/2026-07-03-poll-target-coalescing-design.md`
(this is that spec's **PR 6: alerts**), `docs/reservation-providers.md`,
`rfcs/0007-availability-search-and-alerts.md`

## Problem

Watches already store notification intent — `trigger_kinds TEXT[]`,
`trigger_config JSONB`, `stop_when_triggered BOOLEAN` — and the API
validates/persists them. But **nothing consumes them.** The poller fetches,
writes the cube, and returns; a watch that matches never notifies anyone. The
`SlackNotifier` that did this in the legacy campsite module (196 lines, full
`chat.postMessage` + Block Kit) was deleted with that module in #253, along
with the `campsite_settings` table that held its `slack_token` / `slack_channel`.
So today: intent is captured, the substrate to detect a match exists, but the
match→notify edge is unimplemented and there is no home for a Slack token.

This spec closes that gap for the **`slack_notify`** kind only. `atc`
(cart automation) is explicitly out of product scope
(`reservation-providers.md`: "Reservation providers do not model cart
automation, payment, or booking") and stays inert stored config.

## What already exists (the substrate — do not rebuild)

PRs 1–5 of the coalescing plan are merged. The alert rides on them:

- **The edge is already computed, per poll, in the poller.**
  `AvailabilityPollExecutor.writeCube` upserts each observed cell through a CTE
  that compares prior vs new status and returns a `changed` flag, then appends
  an `availability_snapshot` transition row **only** for changed cells. A
  `reserved → available` flip is detected there and nowhere else.
- **The covering watches are already loaded.** `handle()` opens with
  `pollers.liveWatchesForPoller(poller.id)` — the live watches on this
  `(provider, parent_ref)`.
- **`AvailabilityStatus.isOnlineBookable`** distinguishes a bookable status
  from `first_come` / `reserved` / `closed` / `past` / `unknown`.
- **Per-watch reservable resolution** is `WatchScopeResolver.resolve(watch)`
  (iterates `watch.targets`, applies `reservable_filters`).

So the poller already holds *both halves* of an alert decision (the
transitions this tick, and the watches that care) in one place. The alert is a
consumer of that, not new machinery.

## Design rules

1. **The edge is the trigger.** A cell transition to an online-bookable status,
   inside a live watch's sub-cube, *is* the alert event. No polling of
   snapshots, no separate scan — reuse the `changed` set `writeCube` already
   computes. This keeps alerting 1:1 with the poller tick.
2. **Notification never breaks polling.** Slack dispatch is best-effort: any
   failure (bad token, non-`ok`, network) is logged and swallowed. A watch
   match must never fail the run or stop the poll loop.
3. **Config-driven token, no DB.** The bot token and default channel are env
   config, read once at boot alongside `MAPBOX_TOKEN` and the cache TTLs. No
   settings table, no admin route, no migration.
4. **One live kind, unknowns inert.** The dispatcher acts on `slack_notify`
   and ignores every other kind. No trigger-kind registry and no API
   validation change — `atc` remains storable and simply never dispatches,
   exactly as today.

## Config

`AppConfig` gains a nullable `SlackConfig`:

```kotlin
data class SlackConfig(
    val botToken: String,
    val defaultChannel: String,
)
// AppConfig.fromEnv: read SLACK_BOT_TOKEN + SLACK_ALERT_CHANNEL.
// Both present and non-blank  -> SlackConfig
// either blank/absent         -> null  (notifier disabled: log once, no-op)
```

Disabled is a first-class state, not an error: the poller runs identically with
Slack off (mirrors the legacy notifier's "Slack not configured — skipping"
behavior). This is the "place for the Slack token" the product README already
documents (bot token `xoxb-…` + channel), rehomed from the deleted
`campsite_settings` table to env config.

## Components

### `clients/slack/SlackNotifier.kt` (outbound HTTP only)

A trimmed revival of the deleted notifier. Lives in `clients/` per the layer
rules (outbound network, no DB, no routes).

```kotlin
class SlackNotifier(
    private val config: SlackConfig,
    private val client: HttpClient = HttpClient(CIO) { engine { requestTimeout = 8_000 } },
) {
    /** POSTs one chat.postMessage. Returns true on Slack `ok:true`.
     *  Logs and returns false on any failure — never throws. */
    suspend fun notify(channel: String, text: String): Boolean
}
```

- **Text-only** message (no Block Kit builder — that was the bulk of the old
  file). Body serialized from the revived `SlackPostMessageDto(channel, text)`.
- Posts to `https://slack.com/api/chat.postMessage` with
  `Authorization: Bearer <botToken>`; parses `ok`; on `ok:false` logs the Slack
  `error` and returns false.
- Message content: the site(s) + date(s) that opened (site label from the
  reservable's name / composite id, loop, date), plus a booking deep link **when
  the reservable's provider supplies one**. The link comes from
  `ReservationProvider.bookingUrl(rid, date)` — the URL scheme is vendor-specific
  and lives in the adapter (rec.gov implements it; other adapters default to
  null → a plain line). The dispatcher never hardcodes a vendor URL; it asks the
  resolved provider via `AvailabilityTargetResolver`.
- The message also deep-links two Grafana dashboards: the firing watch's
  drill-down (`/d/reservable-watch-drill?var-watch_id=<id>`) and the
  availability-cube matrix for each POI the openings sit under
  (`/d/availability-cell-matrix?var-poi_id=<id>`). The host comes from
  `GrafanaConfig.rootUrl` (env `GRAFANA_ROOT_URL`, the same var + default the
  Grafana container uses for `GF_SERVER_ROOT_URL` — `localhost:3000/dash` local,
  `roadtrip.floo.ca/dash` prod), so backend and Grafana can't disagree on host.

### `service/availability/WatchAlertDispatcher.kt` (business logic)

The match→notify use case. Stateless; depends on `SlackNotifier` (nullable when
disabled), `WatchScopeResolver`, and `AvailabilityWatchRepo` (to set `DONE`).

```kotlin
class WatchAlertDispatcher(
    private val notifier: SlackNotifier?,          // null => disabled
    private val scopeResolver: WatchScopeResolver,
    private val watches: AvailabilityWatchRepo,
) {
    /** Called once per poller run, after the cube write. `transitions` are the
     *  cells that flipped to an online-bookable status this tick. */
    suspend fun dispatch(
        liveWatches: List<AvailabilityWatchRepo.Watch>,
        transitions: List<CellTransition>,   // (reservableId, targetDate, status)
    )
}
```

Per live watch:
1. `covered = transitions` where `reservableId ∈ scopeResolver.resolve(watch).ids`
   **and** `targetDate ∈ [watch.startDate, watch.endDate]`.
2. If `covered` is empty → skip.
3. If `SLACK_NOTIFY_KIND ("slack_notify") ∈ watch.triggerKinds` and
   `notifier != null` → build the message from `covered`, resolve channel =
   `watch.triggerConfig["channel"]` (string) ?? `config.defaultChannel`, and
   `fired = notifier.notify(channel, text)`.
4. If `watch.stopWhenTriggered` and `fired == true` → set watch `status = DONE`
   via `watches.update` (stops re-firing next cadence tick).

A watch stops **only after a Slack post actually succeeded** — a failed post
(bad token, non-`ok`, network) or a disabled notifier leaves the watch live
rather than silencing a watch we could not notify on. Note the edge model's
consequence (see Risks): a failed post is **not** auto-retried, because the
cell is now `available` and a later poll sees no *transition*. Leaving the watch
live means it fires again only on the **next genuine flip**, not on the missed
one. Unknown kinds are not matched to any dispatcher branch — no-op by omission.

### `writeCube` return-value change

`writeCube` currently returns `Int` (transition count for `run.snapshot_count`).
Change it to also surface the transitions it already computed:

```kotlin
data class CellTransition(val reservableId: Long, val targetDate: LocalDate, val status: AvailabilityStatus)
// writeCube returns List<CellTransition> for the changed && isOnlineBookable cells;
// run.snapshot_count = (all changed cells) is computed as before, independently.
```

`snapshot_count` still counts *all* transitions (bookable or not); the
dispatcher only receives the online-bookable subset. Both derive from the same
`changed` set — no second diff.

**First-observation semantics (decided):** `changed` is true when the prior
status was `null` (first-ever observation of a cell). We **alert on it** — a
site that is already open when a watch is created should surface immediately.
Edge = `changed && status.isOnlineBookable`, with no "prior was a real
non-bookable status" refinement (less code, better product behavior).

## Executor / wiring changes

`AvailabilityPollExecutor`:
- Constructor gains `private val alertDispatcher: WatchAlertDispatcher`.
- `writeCube` returns `List<CellTransition>` (see above); `handle()` aggregates
  the per-group transitions.
- After the existing `markElapsedAsPast`, and only on a **non-failed** run, call
  `alertDispatcher.dispatch(liveWatches, transitions)` inside a
  `runCatching { … }.onFailure { log.warn(...) }` so a dispatch error is a
  logged non-event, never a run failure or backoff trigger.

`Main.kt`:
- Build `SlackNotifier(appConfig.slack)` when `appConfig.slack != null`, else
  pass `null`.
- Build `WatchAlertDispatcher(notifier, scopeResolver, watchRepo)` and inject
  it into `AvailabilityPollExecutor`.

No `Scheduler` change, no migration, no route change.

## Data model

Unchanged. Reuses:
- `availability_watch.trigger_kinds` — `slack_notify` activates the path.
- `availability_watch.trigger_config` — optional `{"channel": "#..."}` per-watch
  channel override; absent → default channel.
- `availability_watch.stop_when_triggered` — one-shot stop.

## Dedup

Two layers, both existing:
- **Edge (free).** The cell holds status across polls; a `reserved→available`
  flip fires once and does not re-fire while the cell stays `available`. Only a
  new flip (it went back to `reserved`, then opened again) re-alerts.
- **`stop_when_triggered`.** Hard stop — the watch goes `DONE` on first fire and
  is no longer live, so its poller stops covering it once all its watches retire.

## Testing

- **`SlackNotifier`** — WireMock (or fake `HttpClient` engine): `ok:true` →
  true; `ok:false` → false + logged; non-2xx → false, no throw; disabled config
  never constructs a notifier.
- **`WatchAlertDispatcher`** — pure unit tests with a fake notifier:
  transition covered by a `slack_notify` watch → `notify` called with resolved
  channel; `trigger_config.channel` override honored; transition outside the
  window or reservable set → no call; watch without `slack_notify` → no call;
  `stop_when_triggered` + successful post → watch set `DONE`;
  `stop_when_triggered` + failed post → watch stays live (not `DONE`);
  notifier `null` (disabled) → no call, no crash, watch stays live.
- **Executor** — extend `AvailabilityPollExecutorTest`: seed a cell as
  `reserved`, poll observing `available` with a covering `slack_notify` watch →
  dispatcher invoked with that transition; a dispatch throw does not fail the
  run.

## Out of scope

- `atc` / cart automation (product-excluded).
- Browser push, email, any non-Slack channel.
- Block Kit / rich Slack formatting (text-only v1).
- Booking deep links for providers other than rec.gov. The port method
  (`ReservationProvider.bookingUrl`) exists and rec.gov implements it; other
  adapters default to null (plain line) until each vendor's URL scheme is added
  in its own adapter.
- A settings UI / runtime token rotation (env config only; redeploy to change).
- Rate-limiting or digesting alerts (edge dedup + `stop_when_triggered` are the
  only throttles in v1).

## Risks / open questions

- **Cold-start burst.** A new watch on a never-polled, currently-open
  campground alerts on the first poll for every open matching site (accepted —
  see first-observation decision). `stop_when_triggered` bounds it to one
  message if the user wants a single ping.
- **At-most-once delivery — a failed post is a missed edge.** Because the alert
  fires off the cube *transition* and the cell then holds `available`, a Slack
  failure at fire time is not auto-retried; the watch only re-alerts on the next
  genuine flip. v1 accepts this (edge simplicity > delivery guarantee); a
  retry/outbox is future work if it bites.
- **Channel typo → silent drop.** A bad `trigger_config.channel` yields a Slack
  `ok:false`; logged, not surfaced to the user. Acceptable for v1; a "test
  alert" affordance is future work.
- **One default channel.** All non-overridden watches post to the single
  `SLACK_ALERT_CHANNEL`. Per-watch override covers the escape hatch; multi-user
  routing is out of scope.
