# Recreation.gov

Wire details for the federal `recreation.gov` adapter. This doc owns the vendor
wire shapes; `../reservation-providers.md` owns the architecture contract.

## Summary

Rec.gov exposes two surfaces we use, and they serve different phases:

- **Consumer SPA API** (`www.recreation.gov/api/...`) — no key. The monthly
  availability endpoint serves both live availability **and** the campsite
  catalog (same payload), so catalog `vendor_id` is byte-for-byte the id the
  availability adapter keys on.
- **RIDB** (`ridb.recreation.gov`, free key) — the facility (campground)
  catalog. Fetched offline by `scripts/fetch_recgov.py`; see
  `../../DATA_SOURCES.md`.

## ID model

- **POI / campground** `booking_provider_ref` = the rec.gov `FacilityID` string,
  parsed as `BookingProviderRef.RecGov(facilityId)`. A campground whose ref is
  any other variant is rejected with `AvailabilityProviderError.WrongRefType`.
- **Campsite** `vendor_id` is the rec.gov campsite id (the key in the
  `campsites` object of the month payload). The adapter resolves it via
  `bookingProviderRef` when `booking_provider = recgov`, else falls back to the
  serialized `dataProviderRef`.

## Endpoint catalog

### `GET /api/camps/availability/campground/{facilityId}/month` — availability + roster

```
https://www.recreation.gov/api/camps/availability/campground/{facilityId}/month
    ?start_date=YYYY-MM-01T00%3A00%3A00.000Z
```

JSON. `start_date` must be the first of a month; a request window spanning
several months fans out into one call per month (issued concurrently) and the
results are merged by campsite id, day by day.

```json
{
  "campsites": {
    "64082": {
      "site": "A012",
      "loop": "LOOP A",
      "campsite_type": "STANDARD NONELECTRIC",
      "max_num_people": 8,
      "equipment_types": ["TENT", "RV"],
      "availabilities": { "2026-07-04T00:00:00Z": "Available" }
    }
  }
}
```

Parsed into `client/recgov/Campsite.kt`. Availability keys are timestamps;
the adapter truncates them to the `YYYY-MM-DD` day.

Status mapping (`classifyRecgovStatus`):

| rec.gov | Internal |
|---|---|
| `Available`, `Open` | `AVAILABLE` |
| `Reserved` | `RESERVED` |
| `Closed` | `CLOSED` |
| `Not Reservable` | `FIRST_COME` |
| `null`, empty | `UNKNOWN` |
| anything else | `RESERVED` |

The earliest bookable day at or after the window start is reported as
`AvailabilitySeasonBlock.reopensOn`, which is how a closed-for-the-season
campground still tells the UI when it comes back.

### Booking URLs

`RecGovBookingUrl` owns the scheme and is shared with the Campflare adapter
(Campflare can report rec.gov-backed sites):

```
https://www.recreation.gov/camping/campsites/{vendorId}?startDate=…&endDate=…
https://www.recreation.gov/camping/campgrounds/{facilityId}?startDate=…&endDate=…
```

The adapter returns the campsite form as a `reservationUrlTemplate`; the caller
fills the date window.

## Rate limits and error contract

- One outbound call at a time, with a **1.5 s floor** between calls
  (`HttpRecgovAvailabilityClient.DEFAULT_MIN_GAP_MS`), enforced by a mutex
  shared across all callers in the process.
- A **429 surfaces immediately** as `RecGovException(httpStatus = 429)`, which
  the adapter maps to `AvailabilityProviderError.RateLimited` (rendered as
  `rate_limited`). It is deliberately **not** slept on inside the client: the
  failover fetcher would rather stamp a rec.gov cooldown
  (`ProviderCooldownTracker`) and try the next candidate immediately than hold
  the calling poll hostage on a retry ladder.
- Transport failures (connect/DNS/socket) retry twice with exponential delay
  inside the Ktor client and, if still failing, classify as
  `UpstreamUnreachable` rather than a vendor 5xx. Other non-2xx statuses become
  `UpstreamUnavailable` (`upstream_5xx`).
- The offline fetchers are separately rate-limited and honour `Retry-After`;
  they are not on the request path.

## Catalog status

Campgrounds come from RIDB (`recgov-campgrounds-raw`) plus a ratings/cell
coverage enrichment capture; campsites come from
`scripts/fetch_recgov_campsites.py`, which walks the same monthly endpoint per
facility and keeps only the catalog half of the payload (availability at
request time is never served from a capture).

## Adapter design notes

- `RecGovAvailabilityProvider` capabilities: `supportsInternalPolling = true`,
  `bookingHorizonDays = 180`, `maxPollWindowDays = 60` (one month-shaped fetch
  shape per tick, so a poll never fans out into ungoverned sub-calls).
- `catalogAvailability` narrows the merged payload to the linked catalog rows
  and stamps `campsiteId`; `availability` (no catalog rows) reports every
  campsite the payload carries with a null `campsiteId`.
- Enablement is config-gated by `roadtrip.read-path.enabled-availability-providers`.
  The adapter is always registered; when the list omits `recgov` it declines
  rec.gov refs and resolution continues through other candidates. `recgov` is
  commented out of that list in the default `application.yaml`.
- Add-to-cart is a separate seam: `RecGovBookingAdapter` over the companion
  service (`roadtrip.booking.recgov-atc.companion-base-url`), not this adapter.
