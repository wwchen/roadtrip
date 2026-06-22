# ReserveCalifornia API

ReserveCalifornia is the booking surface for California State Parks:

| Tenant | Public host | API vendor evidence | `pois.source` |
|---|---|---|---|
| California State Parks | `reservecalifornia.com` | Tyler Technologies / US eDirect Recreation Dynamics | not implemented |

Observed on 2026-06-22. This is a vendor discovery note, not an implemented
adapter contract yet.

## Summary

ReserveCalifornia is not another ReserveAmerica / Active Network tenant. It is
a separate Tyler Technologies deployment. Evidence:

- The public SPA config points read traffic at
  `california-rdr.prod.cali.rd12.recreation-management.tylerapp.com`.
- The bootstrap API returns `USeDirect.RDClasses...` response types and tenant
  metadata for `installIdentity = "cali"`.
- SFGate reported Tyler Technologies operates the official reservation system
  under a 10-year contract:
  <https://www.sfgate.com/national-parks/article/campers-california-state-parks-junk-fees-20319828.php>.

Use `ReserveCalifornia` as the adapter name. The underlying vendor appears to
be Tyler, but the wire behavior, tenant id, and ID model are specific to this
California deployment.

## Public config and tenant bootstrap

The SPA serves a public config file:

```
GET https://reservecalifornia.com/config.json
```

Relevant fields:

```json
{
  "rdrApiUrl": "https://california-rdr.prod.cali.rd12.recreation-management.tylerapp.com/rdr/",
  "rdApiUrl": "https://rdapi.reservecalifornia.com/api/",
  "dateFormat": "MM/dd/yyyy",
  "apiDateFormat": "yyyy-MM-dd",
  "defaultParkListSort": "Distance",
  "defaultUnitSort": "availability"
}
```

Tenant bootstrap is also public:

```
GET https://rdapi.reservecalifornia.com/api/webaccesscustomer/load/enterprise?domainUrl=reservecalifornia.com
```

Relevant response fields:

```json
{
  "Response": 1,
  "Result": {
    "EnterpriseName": "California",
    "EnterpriseId": 1,
    "installIdentity": "cali",
    "WebStoreId": 111
  }
}
```

Read/search endpoints use the RDR API with:

```
tenantId: cali
Accept: application/json
```

RD API endpoints use the Tyler response envelope and, for the calls observed
here, should send:

```
InstallationsIdentity: cali
StoreId: 111
Content-Type: application/json
Accept: application/json
```

## Booking window

The effective booking window is public:

```
POST https://rdapi.reservecalifornia.com/api/webaccessfacility/futurebookingstartsendsdates
Content-Type: application/json
InstallationsIdentity: cali
StoreId: 111

{"customerClassificationId":0}
```

Observed response on 2026-06-22:

```json
{
  "Response": 1,
  "Result": {
    "FutureBookingStartDate": "2026-06-22T00:00:00",
    "FutureBookingEndDate": "2026-12-22T00:00:00",
    "IsShowFutureBookingCampsitesEndDate": false,
    "FutureBookingCampsitesEndDate": "0001-01-01T00:00:00"
  }
}
```

For adapter capabilities, use a six-month horizon. Do not reuse the
ReserveAmerica 270-day tenant default.

## ID model

There are at least three public ID systems:

| ID | Source | Meaning |
|---|---|---|
| `page_id` | `parks.ca.gov/AvailabilityInfo` | California State Parks content page id. Not a ReserveCalifornia place id. |
| `PlaceId` / `CityParkId` | RDR `fd/citypark` and `search/place` | Reservable park/place id. |
| `FacilityId` | RDR `search/place`, `fd/facilities/{id}`, `search/grid` | Bookable facility or campground area under a place. |
| `UnitId` | RDR `search/grid` | Individual reservable unit/site id. |

Example mapping:

- `parks.ca.gov` `page_id=464` is Ahjumawi Lava Springs SP. The
  availability page returned no campground availability for 2026-06-22, and
  `fd/citypark/namecontains/Ahjumawi` returned no ReserveCalifornia match.
- `Pfeiffer Big Sur SP` maps to `PlaceId=690`.
- `PlaceId=690` had facilities `611`, `612`, and `767`.
- Facility `612` is `Weyland Camp (sites 79-130)`.

## Endpoint catalog

### `GET /fd/citypark/namecontains/{query}`

Name search for ReserveCalifornia places.

```
GET {rdrApiUrl}/fd/citypark/namecontains/Pfeiffer%20Big%20Sur
tenantId: cali
```

Relevant response:

```json
[
  {
    "CityParkId": 690,
    "PlaceId": 690,
    "Name": "Pfeiffer Big Sur SP",
    "Latitude": 36.25305556,
    "Longitude": -121.7811111,
    "IsActive": true,
    "EntityType": "Park"
  }
]
```

Use this for discovery/backfill, not for hot availability calls.

### `POST /search/place`

Place search and facility rollup. This maps a `PlaceId` to facilities and
unit-type availability counts for a requested arrival date and stay length.

```
POST {rdrApiUrl}/search/place
tenantId: cali
Content-Type: application/json
```

Minimal observed request shape:

```json
{
  "PlaceId": 690,
  "Latitude": 36.25305556,
  "Longitude": -121.7811111,
  "Nights": 1,
  "CustomerId": 0,
  "StartDate": "2026-06-22",
  "UnitCategoryId": 0,
  "SleepingUnitId": 0,
  "MinVehicleLength": 0,
  "UnitTypesGroupIds": [],
  "AmenityIds": [],
  "Sort": "distance",
  "IsADA": false,
  "RestrictADA": false,
  "NearbyLimit": 100,
  "isSearchAllParks": false,
  "customerClassificationId": 0,
  "InSeasonOnly": true,
  "WebOnly": true,
  "NearbyCountLimit": 10,
  "NearbyOnlyAvailable": false,
  "CountNearby": true,
  "CountUnits": true,
  "HighlightedPlaceId": 0
}
```

For `PlaceId=690`, the response included facilities:

```json
{
  "SelectedPlace": {
    "PlaceId": 690,
    "Name": "Pfeiffer Big Sur SP",
    "Facilities": {
      "611": {
        "FacilityId": 611,
        "UnitTypes": {
          "4303": {"UnitTypeId": 4303, "Name": "Campsite", "AvailableCount": 0}
        }
      },
      "612": {
        "FacilityId": 612,
        "UnitTypes": {
          "4303": {"UnitTypeId": 4303, "Name": "Campsite", "AvailableCount": 0}
        }
      }
    }
  }
}
```

The field names are PascalCase. The `Facilities` object is keyed by facility
id.

### `GET /fd/facilities/{facilityId}`

Facility metadata.

```
GET {rdrApiUrl}/fd/facilities/612
tenantId: cali
```

Observed relevant fields:

```json
{
  "FacilityId": 612,
  "PlaceId": 690,
  "Name": "Weyland Camp (sites 79-130)",
  "FacilityTypeNew": 1,
  "FacilityBehaviourType": 0,
  "AllowWebBooking": true
}
```

The SPA uses `FacilityTypeNew == 2` for open-camping grids and
`FacilityBehaviourType == 2` for time-based grids. A first campground adapter
can start with standard facility grids only and mark the special cases
unsupported until probed.

### `POST /search/grid`

Per-unit, per-day availability for one facility.

```
POST {rdrApiUrl}/search/grid
tenantId: cali
Content-Type: application/json
```

Minimal observed request shape:

```json
{
  "FacilityId": 612,
  "UnitSort": "availability",
  "StartDate": "2026-12-15",
  "EndDate": "2026-12-21",
  "InSeasonOnly": true,
  "WebOnly": true,
  "MaxDate": "2026-12-22T00:00:00",
  "MinDate": "2026-06-22T00:00:00",
  "IsADA": false,
  "RestrictADA": false,
  "UnitCategoryId": 0,
  "SleepingUnitId": 0,
  "MinVehicleLength": 0,
  "UnitTypesGroupIds": [],
  "AmenityIds": [],
  "CustomerId": 0,
  "customerClassificationId": 0
}
```

Observed response summary for facility `612` on 2026-12-15 through
2026-12-21:

```json
{
  "Facility": {
    "FacilityId": 612,
    "Name": "Weyland Camp (sites 79-130)",
    "Units": {
      "bucket2.43793": {
        "UnitId": 43793,
        "Name": "Campsite #W079",
        "AvailableCount": 7,
        "IsWebViewable": true,
        "AllowWebBooking": true,
        "Slices": {
          "2026-12-15T00:00:00": {
            "Date": "2026-12-15T00:00:00",
            "MinStay": 1,
            "IsFree": true,
            "IsBlocked": false,
            "IsWalkin": false,
            "ReservationId": 0,
            "Lock": null
          }
        }
      }
    }
  }
}
```

Availability classification:

| ReserveCalifornia slice | Roadtrip status |
|---|---|
| `IsFree == true`, `IsBlocked == false`, `IsWalkin == false`, `ReservationId == 0`, `Lock == null` | `available` |
| `IsWalkin == true` | `first_come` |
| `IsBlocked == true` | `closed` |
| `ReservationId != 0` | `reserved` |
| Missing slice for an in-window date | `unknown` unless season dates prove closure |

The unit-level flags `IsWebViewable` and `AllowWebBooking` should also gate
online availability. If either is false, do not count the site as bookable even
when the daily slice looks free.

## Adapter design notes

Recommended `provider_ref` shape:

```json
{
  "place_id": 690,
  "facility_ids": [611, 612, 767]
}
```

Open questions before implementation:

- Whether to ETL all facilities per place up front or call `search/place` at
  availability time and cache the facility list.
- Whether a single POI should represent the whole `PlaceId` or one row per
  `FacilityId`. The current UI can render synthetic catalogless sites, but
  real labels and filters are better if `reservables` rows are imported.
- How to handle `FacilityTypeNew == 2` open-camping grids and
  `FacilityBehaviourType == 2` time-based grids.
- Rate limits and WAF behavior. Anonymous read calls worked during the probe,
  but no sustained-load test has been run.

Minimal v1 path:

1. Add `ReservationProviderId.RESERVECALIFORNIA`.
2. Add `ProviderRef.ReserveCalifornia(placeId: Long, facilityIds: List<Long>)`.
3. Add parser/writer support for `place_id` and `facility_ids`.
4. Add a `ReserveCaliforniaAvailabilityClient` with:
   - future booking window fetch;
   - `search/place` facility discovery;
   - `search/grid` per-facility availability fetch.
5. Add a provider that merges unit slices across facilities into
   `AvailabilityObservationBatch`.
6. Keep `supportsAlerts = false` until rate limits and snapshot behavior are
   validated.

No Flyway migration should be required if the adapter uses the existing
`provider_ref` JSONB column.
