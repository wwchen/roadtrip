# Widen the live availability fetch so week-paging is served from the DB

## Problem

The live availability read path fetches **exactly the requested week** and caches
only those dates. Paging to the next week always lands on un-fetched dates, so
every "next week" is a fresh upstream call.

Trace (names as of the `AvailabilityService` / `AvailabilityLoader` rename):

```
GET /api/poi/{id}/reservables/availability?start_date=&end_date=
  → AvailabilityService.poiReservablesAvailability
    → ReservableAvailabilityComposer.availabilityFor
      → AvailabilityDateResolver.resolveWindow   // window = exactly the requested [start,end)
      → AvailabilityLoader.loadOrFetch           // coverage checked over that same window
        → ReservationProvider.catalogAvailability // fetches that same window
```

`AvailabilityLoader.loadOrFetch` reads current cells for **only the requested
window's dates** and requires full coverage of those dates:

- Week 1 `[07-05, 07-12)` → nothing recorded → miss → fetch, record 07-05…07-11.
- Week 2 `[07-12, 07-19)` → those dates never recorded → miss → fetch again.

`MAX_AVAILABILITY_DAYS = 60` is only a *clamp* on how wide a request may be, not a
prefetch floor. The DB cache (Aspira TTL 2h) only helps when you revisit the
**same** week. The poller already fetches a wide window per tick via
`resolvePollingWindow`, but that only runs for watched slots.

## Goal

On a live cache miss, fetch the **widest window the vendor allows** (anchored at
the requested week), record all of it, and return only the requested target
slice. Paging to an adjacent week within that fetched span becomes a pure DB
read.

This is the documented "widen data per upstream call" principle
(`docs/reservation-providers.md`) applied to the live read path, not just the
poller.

## Design

### 1. One wide-window formula, shared by poller and live read

Generalize the poller's `resolvePollingWindow` into an anchor-parameterized
window on `AvailabilityDateResolver`:

```
wideWindow(anchor, context, maxPollWindowDays, bookingHorizonDays):
    start = max(context.earliestDate, anchor)
    span  = minOf(maxPollWindowDays, bookingHorizonDays)
    if span <= 0: return null
    end   = min(context.earliestDate + bookingHorizonDays, start + span)
    return [start, end)
```

- **Poller** calls `wideWindow(anchor = earliestDate, …)` — identical to today's
  `resolvePollingWindow`; behavior unchanged.
- **Live read** calls `wideWindow(anchor = targetStart, …)`.

`resolvePollingWindow` becomes a thin call to `wideWindow(earliestDate, …)` (or is
replaced at its one call site). No third window policy: poller and live read now
share the same wide-window definition. `resolveWindow` (validated target window)
stays as-is.

### 2. Cap the requested window at the vendor's single-call max

The composer currently passes a hardcoded `maxDays = MAX_AVAILABILITY_DAYS (60)`
to `resolveWindow`. Change it to `maxDays = caps.maxPollWindowDays` (Aspira 30,
RecGov 60, ReserveAmerica 30, ReserveCalifornia 30). A request beyond that
returns the existing `400 WindowTooLong` (already carries `maxDays` for the FE).

This ties the request cap to the same per-vendor value the fetch window uses —
no new constant. Its purpose in this design: it guarantees
`targetEnd ≤ targetStart + maxPollWindowDays`, so the target window **always**
fits inside `wideWindow(targetStart)`. No straddle case, no `max(…, targetEnd)`
patch, no cross-method invariant.

`MAX_AVAILABILITY_DAYS` is removed from the composer. (The catalogless path's
`PROVIDER_WINDOW_MAX_DAYS` is out of scope — see below.)

### 3. Composer computes both windows, through the batcher seam

`ReservableAvailabilityComposer.availabilityFor` computes, per vendor group:

- **target window** = `resolveWindow(requested, maxDays = caps.maxPollWindowDays)`
  — validates and throws the user-facing date errors.
- **fetch window** = `wideWindow(anchor = target.start, …)`.

It passes the **target window** as the `AvailabilityLoader.Request` window (drives
coverage + response) and wires the **fetch window** into the
`catalogAvailability` fetch lambda.

**Batcher seam change.** Today `CatalogAvailabilityBatcher`'s
`windowFor: (PoiDateContext, ReservationProviderCapabilities) -> ResolvedDateWindow?`
returns a single window that does triple duty: `countFetchGroups` null-checks it
for the poller's governor, `fetchByGroup` hands it to `provider.catalogAvailability`,
and the composer feeds it to the loader `Request`. This design splits fetch vs
target, so `windowFor` returns **both** as one nullable pair
(`AvailabilityWindows { target, fetch }`, null ⇒ group skipped, no upstream call):

- `countFetchGroups` keeps null-checking the pair — governor semantics and the
  "`countFetchGroups` and `fetchByGroup` never drift" contract are preserved,
  since both still key off the same `windowFor`.
- `fetchByGroup` passes `windows.fetch` to the fetch lambda (→
  `provider.catalogAvailability`) and records the fetch window.
- The composer builds the loader `Request` window from `windows.target`.

The **poller's** `windowFor` returns `target == fetch == wideWindow(earliestDate)`
(both the same wide window), so the poller path is behaviorally unchanged; only
the live composer sets `target != fetch`.

### 4. Loader records the fetched window, returns the target slice

`AvailabilityLoader.loadOrFetch` changes in exactly one place:

- Coverage check (pre-fetch) and response build stay over the **target** window —
  unchanged.
- `recordFetched` derives its record range from the returned
  `batch.startDate/endDate` (the fetch window) instead of the request window, so
  the full wide fetch — observed cells plus `UNKNOWN`-fill for omitted
  `(target, date)` pairs across the fetched window — lands in the interval table.

Next week's page within the same fetched span → target coverage satisfied from
the DB → cache hit, no upstream call.

The loader still owns *when* to fetch and record; it does not compute window
math. Window policy stays in `AvailabilityDateResolver`.

## Data flow / layering

Unchanged. Route → `AvailabilityService` → `ReservableAvailabilityComposer` →
`AvailabilityTargetResolver` → `ReservationProvider` adapter → upstream, with
`AvailabilityLoader` as the read-through cache over `AvailabilityRepo`. No new
layer crossings; the only new knowledge is that the composer now hands the loader
a *response* window and the provider a *fetch* window.

## Error handling

- Target-window validation unchanged, except `WindowTooLong.maxDays` is now the
  vendor's `maxPollWindowDays` rather than a flat 60.
- `wideWindow` never throws — it clamps to `earliestDate` and the booking horizon.
  On the live path `targetStart` is already validated `≥ earliestDate` and
  `≤ horizon`, so the span is always positive; the `null` return only matters for
  a zero-horizon vendor, which cannot reach this path.
- Provider errors continue to rethrow from the composer so the route maps them to
  `503` (retryable), matching current behavior.

## Testing

- **`AvailabilityDateResolver`**: `wideWindow` — earliest-anchor parity with the
  old `resolvePollingWindow`; target-anchor start; clamp to `earliestDate` for a
  past anchor; clamp to horizon; target always contained when
  `targetLen ≤ maxPollWindowDays`; `null` when span ≤ 0.
- **`ReservableAvailabilityComposer`**: request wider than `maxPollWindowDays` →
  `WindowTooLong`; the window handed to the provider is the wide one; the window
  handed to the loader is the target one.
- **`AvailabilityLoader`**: on a miss the record range covers the full fetched
  window (assert `UNKNOWN`-fill across the fetch span, not just the target); the
  response contains only target-window observations. **Regression test for the
  original bug**: after week-1 fetches its wide window, a week-2 request inside
  that span is a DB hit (no fetch lambda invocation).
- **Poller**: `resolvePollingWindow` / `wideWindow(earliestDate)` parity — poller
  fetch window unchanged.

## Scope

- **In**: cataloged path — `AvailabilityDateResolver.wideWindow`, the composer's
  two-window split + vendor-driven request cap, the loader's record-range change.
- **Out (known follow-up)**: the catalogless path
  (`AvailabilityServiceImpl.cataloglessProviderAvailability`) still fetches the
  bare requested window and bypasses the loader entirely. Widening + caching it is
  a separate change; called out here so it isn't mistaken for done.

## Compatibility

TTL semantics unchanged. The request cap drops from 60 to the vendor max (30 for
Aspira); the FE only ever requests 7-day weeks, so no real client is affected. No
schema or migration changes — the interval table already stores what a wide fetch
records.
