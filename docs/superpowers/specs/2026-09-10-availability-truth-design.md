# Availability truth server-side

**Date:** 2026-09-10
**Status:** Design for phase 3 of the 2026-09-09 audit (finding 8, issue #737)
**Follows:** #744, #745, #746

## What the code does today

`GET /api/pois/{id}/campsites/availability` returns one envelope per campsite (`campsites: [AvailabilityResponseDto]`), each narrowed to that campsite's observations by `PoiAvailabilitySlice.perCampsiteEnvelopes`. The backend's `rollupStatus` (already `available > first_come > unknown > reserved`, `closed` only by unanimity) therefore runs over one value per day, and the browser fuses the streams again in `frontend/src/features/availability/fuse.ts` with its own copy of the precedence. `matrix-rows.ts` then re-decides each cell: an explicit status wins, else the day's id list, else the day's rollup, and a rolled-up `available` with an id list that lacks this site becomes `reserved`. `matrix-rows.ts` decides which cells can be watched by comparing the hyphenated CSS `kind` against `reserved`/`first-come`; `DayDetail.tsx` uses a second rule for the day-level alert. `lib/watch-windows.ts` infers `no-credentials` by subtracting `booking_actions` from `trigger_kinds`, which `WatchCapabilityService.canFulfilAddToCart` already computed. `useWatches.ts` sends `cadence_sec: 60` on every watch, so `resolveCadenceSec`'s POI and global rungs never apply. `AvailabilityWeek.tsx` caps the date picker at a flat 365 days while providers expose 180 (rec.gov), 183 (ReserveCalifornia), 270 (ReserveAmerica), or 365, so the picker offers dates the backend rejects with `beyond_booking_horizon`.

`POST /api/pois/availability/bulk` returns the same per-campsite envelopes plus `longest_run_nights` and a per-POI `error`. Nothing in `frontend/src`, `companion/`, or `scripts/` calls it today. The alert dispatcher reads the persisted cube, not the wire. Grafana re-derives the matrix in SQL.

## Design

The POI availability response carries the fused week; the browser renders it.

### Wire

```kotlin
@Serializable data class AvailabilityCellDto(val status: AvailabilityStatus, val watchable: Boolean)
@Serializable data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,          // the rollup, backend-owned
    val watchable: Boolean,                  // any cell watchable
    val cells: Map<Long, AvailabilityCellDto>, // campsite id → cell, ascending id
)
@Serializable data class AddToCartCapabilityDto(val state: AddToCartState) // ready | no_credentials | signed_out | unsupported
@Serializable data class AvailabilityWatchCapabilitiesDto(
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("booking_actions") val bookingActions: List<String>,
    @SerialName("add_to_cart") val addToCart: AddToCartCapabilityDto,
)
@Serializable data class PoiCampsitesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("latest_date") val latestDate: String? = null, // earliest + provider bookingHorizonDays; null when no provider claims it
    val state: AvailabilityWindowState,                    // success | empty | closed_for_season
    val season: JsonElement? = null,                       // the provider's block when closed_for_season
    val cache: AvailabilityCacheBlock? = null,             // the stalest block across the streams
    val days: List<AvailabilityDayDto>,
    @SerialName("watch_capabilities") val watchCapabilities: AvailabilityWatchCapabilitiesDto,
)
```

`available_campsite_ids` and `campsite_statuses` leave `AvailabilityDayDto`; `cells` replaces both. The per-campsite `campsites` list leaves the POI response. The bulk endpoint keeps its per-campsite envelopes (`AvailabilityResponseDto.availability: List<AvailabilityDayDto>`, one cell per day) and `longest_run_nights`; it has no consumer, and this phase does not redesign it. `AvailabilityStatus.PAST` stays declared and unproduced.

### Rules, all in `service/availability`

- **Rollup** (`AvailabilityResponseMapper.rollupStatus`, unchanged precedence) runs once per day over every campsite in the slice, so the day's `status` is the campground's status. A day with no observations for a campsite gets `unknown` for that cell.
- **Cell watchable** = the cell's status is `reserved` or `first_come`, the campsite's provider `supportsInternalPolling`, and the day is not before the window's earliest date. `AvailabilityStatus.watchable` names the first condition on the enum; `WatchCapabilityService` supplies the second per campsite. `day.watchable` is any cell watchable.
- **Window state**: `empty` when the slice has no campsites; `closed_for_season` when every campsite's stream classifies as `closed_for_season` (the existing `classifyWindowState` per stream), with the first non-null season block; else `success`. Bulk keeps its per-stream `state`.
- **Cache**: the stream with the greatest `age_seconds`, or null.
- **Add-to-cart state**: `unsupported` when the scope does not support `ADD_TO_CART`; else `signed_out` when there is no requester; else `no_credentials` when `canFulfilAddToCart` is false; else `ready`. `trigger_kinds` keeps its meaning (`atc` present only when `ready`).
- **Cadence**: the frontend stops sending `cadence_sec`; the request mapper already accepts null; the resolver's rungs apply. No backend change beyond a test proving a create without `cadence_sec` stores NULL.
- **Horizon**: `latest_date` = `AvailabilityDateResolver`'s `latestDate` for the slice's provider, and null when no registered provider claims the campground; the POI detail schema gains the same `latest_date` next to `earliest_date`.

### Frontend

- `api/availability-api.ts` mirrors the new response; `AvailabilityDay`, `AvailabilityCell`, `AddToCartState` types replace the fused types.
- Deleted: `fuse.ts` (rollup, `fuseDay`, `enumerateDates`, `oldestCacheBlock`, `fusePoiCampsitesAvailability`), `lib/day-fields.ts` id-list readers (`availableCount`/`campsiteCount` become one-line reads over `cells`), `matrix-rows.ts` `cellState`'s id-list branch, `availabilityIndex`, `isWatchableKind`/`WATCHABLE_KINDS`, `watch-windows.ts` `cartGate`/`supportsAddToCart`/`scopeSupportsAddToCart`/`DEFAULT_WATCH_CADENCE_SEC`, `AvailabilityWeek.tsx` `CALENDAR_MAX_DAYS_OUT`, `DayDetail.tsx`'s `canAlert` status rule.
- `SiteMatrix` cells read `cell.status` and `cell.watchable`; the day column reads `day.status`/`day.watchable`; `CellBookPopover` and the watch editor read `watch_capabilities.add_to_cart.state` (the `signed-out` copy branch stays keyed on that state, not on client-side auth arithmetic; the client's own `loading`/`failed` watch-list states stay client-side because they are client facts); the date picker's `maxDate` is `latest_date`; watch creation omits `cadence_sec`.
- `group.tsx`'s `CLOSED_STATUSES` reads day statuses from the same enum and is untouched.

## Risks

- **Wire break** on the POI availability response; the frontend ships in the same PR; no other consumer.
- **Rollup semantics are byte-identical** to the frontend's (same order, same unanimity rule), verified by a test that feeds the old `fuse.test.ts` cases to the backend.
- **Watch cadence changes for new watches**: they follow the POI override or the global default (300s) instead of 60s. Existing watches keep their stored 60. The PR says so.
- **Date picker tightens** to the provider's real horizon.
