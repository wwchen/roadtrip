# Glossary

The project vocabulary, in dependency order — later terms build on earlier
ones. Each entry names the source of truth in code.

## Catalog

- **POI** — a point on the map. One row in `pois` (`poi_type`, PostGIS
  `geom`); what `/api/pois` serves for the visible bounding box and what the
  frontend renders as pins. A POI knows nothing about vendors — it links to a
  catalog row through a join table (`poi_campgrounds`,
  `poi_tesla_superchargers`, `poi_planet_fitness_locations`).
- **Campground** — a canonical catalog row in `campgrounds`: name, kind
  (federal/state/…), location/management metadata, and its provider identity
  (`data_provider(_ref)`, `booking_provider(_ref)`). One campground backs one
  campground POI.
- **Campsite** — a bookable child site of a campground (`campsites`:
  `campground_id`, `name`, `kind`, `loop_name`, amenity/capacity columns, and
  its own `data_provider(_ref)` / `booking_provider(_ref)`). Availability is
  stored per campsite per date (`availability.campsite_id` + `target_date`).
- **Reservable** — historical, and worth knowing only because older prose uses
  it. RFC 0008 introduced a generic `reservables` table keyed by
  `(type, vendor, vendor_id)` so permits and tickets could later slot in
  beside campsites. `V38__canonical_catalog.sql` dropped that table: campsites
  became the concrete catalog type and every `reservable_id` column was renamed
  `campsite_id`. In RFC 0008 and pre-V38 comments, read "reservable" as
  "campsite".
- **data_provider vs booking_provider** — two independent provider axes on a
  catalog row (`model/domain/provider/DataProvider.kt`,
  `BookingProvider.kt`). `data_provider` says where the catalog *data* came
  from (which feed/ETL: recgov, aspira, bcparks-strapi, campflare, …);
  `booking_provider` says which reservation system availability and booking go
  through (recgov, aspira, reserveamerica, reservecalifornia, campflare). They
  can differ: e.g. a BC Parks campground is cataloged from the Strapi feed
  (`data_provider = bcparks-strapi`) but booked through Aspira
  (`booking_provider = aspira`). Each has a `_ref` column carrying the
  provider-native id.

## Ingestion (registry vocabulary)

Source of truth: `backend/src/main/resources/poi-registry.yaml`, loaded by
`PoiRegistry` at boot. See [adding-a-data-source.md](adding-a-data-source.md).

- **slug** — a kebab-case identifier you pick for a `data_sources:` row (a
  fetcher invocation) or an `etls:` entry (a terminal ETL). All slugs share
  one namespace and must be unique; the terminal etl slug also becomes the
  `source` label on imported rows.
- **target** — what you address a run at. Fetch targets are `data_sources`
  slugs (`make data-fetch TARGET=<slug>`); import targets are `poi_data:` /
  `campsite_data:` row display names (`POST /api/admin/data/import/<name>`).
- **ETL** — the Kotlin import job for one registry row: parse the newest raw
  envelopes → transform to upsert candidates → batched upsert through the
  owning repo. Lives under `service/etl/vendors/<vendor>/`.
- **adapter** — the ETL class a YAML row names in its `adapter:` field (e.g.
  `AspiraCampgroundsEtl`). `ProductionTerminalEtlRegistry.kt` maps each
  enabled row to an instance of that class, passing the row's slug and `args:`.

## Availability and watches

- **Watch** — a row of pure user intent (`availability_watch` plus its
  `availability_watch_target` rows): "tell me when something opens across these
  targets — a whole POI, or one specific campsite — anywhere in the half-open
  window `[start_date, end_date)`, and when it does, fire these
  `trigger_kinds`". `campsite_filters` narrows a POI-scoped watch;
  `cadence_sec` is an optional per-watch override, not a required field
  (`ResolveCadence.kt` falls through watch → POI override → 300s default, and
  a poller takes the tightest cadence across its live watches). Status is
  active/paused/done; `stop_when_triggered` makes it one-shot. Managed via
  `/api/watches` and the `/watches` page.
- **Slot** — the unit of polling interest: a `(poi_id, target_date)` pair. A
  slot is polled if and only if at least one active watch covers it; polling
  starts with the first covering watch, stops at zero, and stops
  unconditionally when the date passes. See "Polling is watch-driven" in
  [reservation-providers.md](reservation-providers.md).
- **Poller** — the schedulable unit that actually fetches
  (`availability_poller`): one row per `(provider, parent_ref)` — i.e. per
  upstream campground — with `next_run_at` and claim/lease columns. Many
  watches coalesce onto one poller (`availability_watch_poller`), so two users
  watching the same campground share one upstream call. Executed by
  `service/scheduler/jobs/AvailabilityPollExecutor.kt`.
- **Governor** — the per-vendor rate-limit token bucket
  (`service/ratelimit/VendorRateLimiter.kt`, Postgres-backed) that caps how
  many upstream fetch groups all pollers together may issue against one
  vendor. Cadence says when a poller *wants* to run; the governor says whether
  the vendor budget *lets* it.
- **coverage_fresh vs governor_starved** — the two reasons a poll cycle
  issues no upstream call (`PollSkipReason` in
  `observability/RoadtripMetrics.kt`, recorded by
  `AvailabilityPollExecutor`): `coverage_fresh` = every fetch group already
  has data fresher than the poller's cadence, so fetching would be wasted;
  `governor_starved` = the vendor governor had no tokens, so the cycle
  rescheduled (15s retry). The metric distinguishes "nothing needed fetching"
  from "the poller is wedged".
- **Trigger kind** — what a watch does when a matching opening appears
  (`service/availability/TriggerKind.kt`): `slack_notify`, `email_notify`, or
  `atc`.
- **ATC** — add-to-cart: the `atc` trigger asks the companion to place the
  opening in a real recreation.gov shopping cart (a genuine hold). Rec.gov
  only; other providers' watches can only notify.
- **Companion** — the Node 22.9+ Playwright HTTP service in `companion/`. It
  drives real, persistently-logged-in Chromium profiles because Akamai blocks
  datacenter IPs and headless browsers; the backend never touches a browser —
  it polls public availability APIs and POSTs ATC payloads to the companion
  (`POST /atc`). One Chromium profile per user, keyed by a required
  `profile_id`. See [companion.md](companion.md) for the full contract.
