# Date-window campsite availability

Replace minimum-night query semantics with explicit date windows. Public
availability endpoints return facts for the requested date range. Stay-fit
logic is frontend presentation, except for watches where the backend must
persist and execute the monitored stay window.

## Decisions

- `end_date` is exclusive. `start_date=2026-07-04` and
  `end_date=2026-07-07` means the nights of July 4, 5, and 6.
- Public availability APIs accept `start_date` and `end_date`.
- Public availability APIs return only per-day availability inside
  `[start_date, end_date)`.
- Public availability APIs do not accept or return `min_nights`.
- Public availability APIs do not classify whether the whole stay fits.
- Watches persist `start_date` and `end_date` because polling needs the
  monitored stay window.
- The frontend owns stay visualization, such as contiguous run highlighting
  across the returned per-day/per-site data.

## Scope

Remove these concepts from live backend and frontend code:

- `min_nights` request fields, response fields, OpenAPI parameters, UI
  controls, localStorage keys, and tests.
- Backend same-site stay classification for ordinary availability queries.
- Stay-length `nights` fields in campsite availability query contracts.

Keep these concepts:

- Local route/trip "nights" math where it is only display or route-planning
  state.
- Provider adapter internals may derive a day count from
  `start_date`/`end_date` to call existing lower-level helpers during the
  migration, but this must not leak into public route contracts.
- Historical RFCs, old plans, and mock HTML do not need churn unless they
  are used by tests or served as live app pages.

## API Contract

### POI Availability

Current:

```
GET /api/poi/{poi_id}/availability?start=YYYY-MM-DD&days=7&min_nights=2
GET /api/campsite/availability/{poi_id}?start=YYYY-MM-DD&days=7&min_nights=2
```

New:

```
GET /api/poi/{poi_id}/availability?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
GET /api/campsite/availability/{poi_id}?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
```

Rules:

- `start_date` defaults to today.
- `end_date` defaults to `start_date + 7 days` for drawer-style queries.
- `end_date` must be after `start_date`.
- The date span must stay within existing availability window limits and
  provider booking horizons.
- The response `availability` array contains one element per date in
  `[start_date, end_date)`.
- The route-level `site_type` and `force` params stay unchanged.

### Reservable Availability

Current:

```
GET /api/reservable/{rid}/availability?start=YYYY-MM-DD&days=7&min_nights=2
```

New:

```
GET /api/reservable/{rid}/availability?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
```

Same validation and response-window rules as POI availability.

### Reservable Catalog Links

Current:

```
GET /api/poi/{id}/reservables?start=YYYY-MM-DD&min_nights=2
```

New:

```
GET /api/poi/{id}/reservables?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD
```

When both dates are present, reservation URLs use those exact dates. No
route accepts `min_nights` to build booking links.

### Bulk Availability

Current:

```
POST /api/campsite/availability/bulk
{ "ids": [1, 2], "start": "2026-07-04", "nights": 3 }
```

New:

```
POST /api/campsite/availability/bulk
{ "ids": [1, 2], "start_date": "2026-07-04", "end_date": "2026-07-07" }
```

The response keeps `available_dates`, but dates are limited to
`[start_date, end_date)`. The response echoes `start_date` and `end_date`.

## Watch Contract

Watches move from date list plus minimum-night length to one explicit stay
window.

Current:

```
{
  "target_dates": ["2026-07-04", "2026-07-05"],
  "min_nights": 2
}
```

New:

```
{
  "start_date": "2026-07-04",
  "end_date": "2026-07-06"
}
```

Rules:

- `end_date` is exclusive and must be after `start_date`.
- The poller fetches availability for `[start_date, end_date)`.
- A watch match means the monitored stay window is actionable according to
  the watch trigger logic.
- Heatmap rows still render one cell per night in the stay window, derived
  from the stored range.
- `target_dates` and `min_nights` are removed from watch create, update,
  list, detail, repo models, and job intents.

## Data Migration

Add a Flyway migration that changes `availability_watch`:

- Add `start_date DATE`.
- Add `end_date DATE`.
- Backfill existing rows:
  - `start_date = min(target_dates)`.
  - `end_date = max(target_dates) + min_nights`.
  - Existing multi-arrival watches become one bounding stay window. This can
    monitor more dates than the old sparse list, but it preserves every old
    monitored arrival inside the new range.
  - The migration must fail before schema alteration if any row has an empty
    or null `target_dates` array. The live create path requires non-empty
    target dates, so this is an integrity guard rather than an expected case.
- Make `start_date` and `end_date` `NOT NULL`.
- Add `CHECK (end_date > start_date)`.
- Drop `target_dates`.
- Drop `min_nights`.

Generated jOOQ bindings must be refreshed or adjusted according to the
repo's normal Gradle workflow.

## Backend Shape

- Introduce a shared date-window parser in routes or a small route helper:
  `startDate`, `endDate`, `spanDays`.
- Keep route code responsible for HTTP parsing and validation only.
- Keep provider requests typed. Rename request fields from
  `start`/`days`/`minNights` to `startDate`/`endDate` or
  `start`/`end` consistently across `BookingProvider`.
- Provider adapters fetch enough upstream data for the requested window and
  return per-day classification with single-day semantics.
- Remove rolling-window lookahead for normal availability. The backend no
  longer fetches trailing days just to decide multi-night fit.
- `ReservableAvailabilityFetchService` appends snapshots from the returned
  per-day response directly. It no longer refetches with `min_nights=1`.
- `AvailabilityJobIntent` stores `start_date` and `end_date`.

## Frontend Shape

- Drawer default query is the typical seven-day poll:
  `start_date=today`, `end_date=today+7`.
- Remove the min-nights chip row and `cg.minNights` localStorage.
- Keep the table/week view toggle.
- Any matrix run highlighting that remains is computed in frontend code from
  the returned per-day/per-site data. This is presentation only.
- Day/site detail labels show selected dates or ranges, not "N-night stay".
- Create-watch actions pass `start_date` and `end_date`.
- Admin watch pages render and edit the date range instead of target date
  lists and min nights.
- Reservable availability panels use `start_date` and `end_date` controls.

## Testing

Backend tests:

- POI availability route accepts `start_date`/`end_date` and rejects invalid
  ranges.
- POI availability route does not document or honor `min_nights`.
- Reservable availability route uses the requested exclusive date window.
- Reservable catalog booking links use exact start/end dates.
- Bulk availability request/response use `start_date`/`end_date`.
- Watch create/update/list/detail schemas use `start_date`/`end_date`.
- Watch migration backfills existing rows correctly.
- Job intent serialization uses `start_date`/`end_date`.

Frontend tests or browser checks:

- Drawer requests the seven-day date window by default.
- No visible Min nights control remains in live pages.
- Watch create/edit forms submit `start_date` and `end_date`.
- Reservable availability panels build the new URLs.

## Non-goals

- Redesigning the availability UI beyond removing the min-nights controls.
- Changing route-planner trip date UX except where it calls campsite
  availability endpoints.
- Cleaning historical RFC language in old docs and mocks.
