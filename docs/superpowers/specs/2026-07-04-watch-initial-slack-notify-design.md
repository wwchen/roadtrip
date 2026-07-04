# Initial Slack notification on watch create/update

## Problem

The availability poller is **edge-triggered**: a cell alerts only when its
status changes (`AvailabilityCellRepo.upsertObservations` sets
`changed = oldStatus == null || oldStatus != new`). Consequence: if a site is
**already bookable** at the moment a watch is created, the cube already holds
`available`, the next poll sees no edge, and the watch never fires — the user
waits forever for a transition that already happened.

## Goal

When a `slack_notify` watch is **created or updated**, send one immediate
"first message" reflecting the current state of its window, so the user always
gets acknowledgement and never misses already-open availability. The ongoing
edge-triggered poller path is unchanged and handles all subsequent flow.

## Behavior

`WatchAlertDispatcher.dispatchInitial(watch)` — fired fire-and-forget after a
create/update commits. Gated to: notifier configured, `slack_notify` in
`triggerKinds`, a channel resolvable. Fires on any lifecycle change — created,
updated, or paused.

A **paused/done** watch posts a lifecycle status message ("⏸ Paused watching …",
"✅ Done watching …") and stops — no availability lookup, never a trigger.

An **active** watch reads the current cube face for its reservables × window via
`AvailabilityHeatmapRepo.loadHeatmap`, then:

- **Some cells bookable** → the normal openings alert (reuse the existing
  message builder). This is a real trigger, so it honors `stopWhenTriggered`
  (post succeeds → watch goes `DONE`), identical to the poller path.
- **Cells known, none bookable** → informational: "watching, nothing open right
  now". Not a trigger — never marks `DONE`.
- **No cells (cold POI, cube empty)** → informational: "availability not checked
  yet" (unknown state). The immediate poll that `create()` schedules
  (`tighterCadencePull = now`) will observe the window; its first observation is
  itself an edge, so the real first opening still fires through the poller.

No double-fire: a warm-and-open cube fires here (no future edge exists); a cold
cube stays silent here and fires from the poll's first-observation edge.

## Design

- **Reuse, not duplicate.** Extract per-watch "resolve channel → post openings →
  honor `stopWhenTriggered`" so both the transition loop and `dispatchInitial`
  share it. The openings message builder is untouched; the initial path maps
  bookable `LatestCell`s to the same shape it already consumes.
- **New dependency:** `WatchAlertDispatcher` gains `AvailabilityHeatmapRepo`
  (the current-cube reader), injected in `Main` and in test builders.
- **Wiring / timing:** the create and PATCH routes launch
  `dispatchInitial(watch)` on the app's `schedulerScope` after the mutation
  commits — outside the DB transaction, best-effort, never blocking or failing
  the HTTP response (the notifier already swallows errors and has an 8s timeout).
  All gating lives inside `dispatchInitial`, so the route call is unconditional.

## Testing

Integration tests against the test cube (seed cells via
`AvailabilityCellRepo.upsertObservations`, no live Slack via
`RecordingSlackNotifier`):

- already-available window → posts the openings alert
- cube known, none bookable → posts the informational "nothing open" message
- empty cube → posts the "unknown / not checked yet" message
- `stopWhenTriggered` + already-available → watch goes `DONE`
- `stopWhenTriggered` + empty cube → watch stays `ACTIVE` (unknown is not a trigger)
- watch without `slack_notify` → no post
