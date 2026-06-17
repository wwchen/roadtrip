# Availability status enum design

## Context

Availability is now tracked at the lowest reservable unit: one reservable on
one date. The old `partial` status made sense when a campground/date was being
classified against multi-night stay fit, because a date could be open but fail
the requested consecutive-night window. That no longer belongs in the canonical
status vocabulary.

The current implementation stores `availability_snapshot.status` as checked
`TEXT` and passes availability statuses through Kotlin/API code as plain
strings. Rec.gov `Not Reservable` currently falls through to the same behavior
as `Reserved`, so POI `4611` / Rec.gov campground `234190` can show first-come
first-served site-days as full/reserved in the Roadtrip matrix.

## Goal

Make availability status a real enum across persistence, Kotlin domain code,
and the API contract, with these canonical states:

| Enum value | UI label | Meaning |
| --- | --- | --- |
| `first_come` | `FF` | First come first served / not reservable online |
| `reserved` | `R` | Reserved or otherwise unavailable to book online |
| `available` | `A` | Available to reserve/book online |
| `closed` | `C` | Provider explicitly says the reservable/date is closed, for any reason |
| `unknown` | `?` | No provider data for the reservable/date |

## Non-Goals

- Do not reintroduce stay-length or consecutive-night fit into the public
  availability response.
- Do not keep `partial` as a canonical value. Legacy `partial` rows are migrated
  into the new enum using the stored `available` boolean.
- Do not infer first-come-first-served for vendors that do not expose a distinct
  signal.

## Database Schema

Add a Postgres enum:

```sql
CREATE TYPE availability_status AS ENUM (
  'first_come',
  'reserved',
  'available',
  'closed',
  'unknown'
);
```

Then migrate `availability_snapshot.status` from `TEXT` to
`availability_status`. The migration must drop the existing text check
constraint before changing the type.

Legacy status mapping:

| Old status | New status |
| --- | --- |
| `available` | `available` |
| `booked` | `reserved` |
| `closed` | `closed` |
| `partial` and `available = true` | `available` |
| `partial` and `available = false` | `reserved` |

`availability_snapshot.available` remains for existing history/statistics. New
writes set it to `true` only when status is `available`; `first_come` is visible
to users but is not an online bookable opening. `unknown` writes set
`available` to `false`.

## Kotlin/API Model

Introduce a Kotlin domain enum, serialized as lowercase wire values:

```kotlin
enum class AvailabilityStatus {
    @SerialName("first_come")
    FirstCome,

    @SerialName("reserved")
    Reserved,

    @SerialName("available")
    Available,

    @SerialName("closed")
    Closed,

    @SerialName("unknown")
    Unknown,
}
```

`DayClassification.status` and `AvailabilityDayDto.status` use this enum
instead of `String`.

The public day response keeps the existing aggregate fields for compatibility:

```json
{
  "date": "2026-06-17",
  "status": "first_come",
  "available_count": 0,
  "total": 6,
  "available_reservable_ids": [],
  "reservable_statuses": {
    "site:recgov:25144": "first_come",
    "site:recgov:25555": "reserved",
    "site:recgov:25885": "unknown"
  }
}
```

`reservable_statuses` is required for POI-scoped responses because one date can
have mixed `FF`, `R`, `A`, `C`, and unknown rows. The existing
`available_reservable_ids` only identifies `A` rows and cannot distinguish
`FF`, `R`, `C`, or unknown rows from each other.

Aggregate day `status` is a scan-friendly rollup with this precedence:

1. `available` if any linked reservable is available.
2. `first_come` if no reservable is available and at least one is first come
   first served.
3. `reserved` if no reservable is available/first-come and at least one is
   reserved.
4. `closed` if every linked reservable has explicit provider-closed data.
5. `unknown` if no actionable status exists and at least one linked reservable
   has no provider data for the date.

`available_count` counts only `available` reservables. First-come sites are
visible in `reservable_statuses`, not counted as online availability.

## Provider Mapping

Rec.gov:

| Upstream value | Canonical status |
| --- | --- |
| `Not Reservable` | `first_come` |
| `Reserved` | `reserved` |
| `Available` | `available` |
| `Open` | `available` |
| `Closed` | `closed` |
| Missing date row | `unknown` |
| Unknown non-empty value | `reserved` |

Aspira:

| Aspira classification | Canonical status |
| --- | --- |
| available/limited/partial/mixed/mostly-booked codes with a resource present | `available` |
| unavailable | `closed` |
| no-data / missing resource-day | `unknown` |
| unknown code | `unknown` |

Aspira does not currently expose a first-come signal in the adapter, so it
never emits `first_come`.

## Frontend Rendering

The campground matrix uses `day.reservable_statuses[row.rid]` first. If that
field is absent, it falls back to the legacy `available_reservable_ids` +
aggregate status behavior so older mocked responses still render.

Cell labels:

| Status | Label | Existing class strategy |
| --- | --- | --- |
| `available` | `A` | use/rename available styling |
| `first_come` | `FF` | new distinct first-come styling |
| `reserved` | `R` | use/rename booked styling |
| `closed` | `C` | use closed styling |
| `unknown` | `?` | new neutral unknown styling |

Aria labels and titles spell out the full meaning: "available", "first come
first served", "reserved", "closed", and "unknown".

## Verification

Backend tests:

- Rec.gov classifier emits `first_come` and per-reservable status maps for
  `Not Reservable`.
- Rec.gov classifier emits `reserved`, `available`, and `closed` for the other
  upstream statuses.
- Rec.gov classifier emits `unknown` when a linked reservable has no row for a
  requested date.
- Legacy `partial` snapshot rows migrate according to `available`.
- `AvailabilitySnapshotRepo` writes the DB enum value and sets `available`
  only for `AvailabilityStatus.Available`.
- Aspira adapter maps no-data, missing resource-days, and unfamiliar status
  codes to `unknown`, and has no `partial`.

Frontend tests:

- Matrix fixture renders `A`, `FF`, `R`, `C`, and `?` across rows/dates.
- Matrix falls back correctly when `reservable_statuses` is absent.
- Legend uses `A`, `FF`, `R`, `C`, and `?`.

Live verification target:

- `https://roadtrip.floo.ca/?poi=4611` should show Lower Penstemon
  `Not Reservable` site-days as `FF`, while Recreation.gov campground
  `234190` continues to show the same dates as not reservable online.

## Rollout Notes

This is a breaking semantic change for clients that expect `booked` or
`partial`, but it is aligned with the app's current lowest-unit availability
model. The frontend and backend should ship in the same change so UI mocks,
smoke tests, and API snapshots agree on the enum.
