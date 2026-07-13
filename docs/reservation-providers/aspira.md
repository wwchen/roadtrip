# Aspira NextGen API

Aspira NextGen (camis.com) is the booking platform behind:

| Tenant | Host | `AvailabilityProviderId` today | `pois.source` |
|---|---|---|---|
| Parks Canada | `reservation.pc.gc.ca` | `ASPIRA` | `aspira-pc-pins` |
| BC Provincial Parks | `camping.bcparks.ca` | `ASPIRA` | `aspira-bc-pins` |
| Washington State Parks | `washington.goingtocamp.com` | `ASPIRA` | `aspira-wa-pins` |

All tenants share the same SPA build and (so far as we've observed)
identical `/api/*` shapes; only the data, host, and tenant-specific
sentinel ID ranges differ. **Three tenants, one API surface.** The
provider id is vendor-shaped (`ASPIRA`); `pois.source` and `AspiraTenants`
select the concrete host/tenant.

## Wire shape overview

- **Base URL:** `https://{host}` — host is per-tenant.
- **Auth:** anonymous for read endpoints. The SPA sets an
  `__RequestVerificationToken` cookie on first load that the
  `/api/cart` mutating endpoints check; read endpoints we use
  (catalog + availability) do not.
- **Content-Type:** every endpoint returns JSON. `Accept: application/json`
  is honored. Do **not** send `Accept-Charset` — Aspira's WAF rejects
  requests carrying it (real browsers omit the header). This is the
  reason `AspiraAvailabilityClient` uses Java's `HttpClient` instead
  of Ktor: Ktor's `HttpPlainText` plugin auto-adds it.
- **Anti-bot:** Azure App Gateway WAF in front of the origin. Triggered
  by:
  - bare-curl User-Agent strings → 403
  - high request volume from a single egress IP → HTML CAPTCHA
    challenge returned with status 200 (looks like a successful
    response until you check the first byte)
  - Headless Chromium with default fingerprint → CAPTCHA
- **Rate limits:** undocumented. Empirically: ~1 request/second from a
  single IP is fine; ≥2/sec triggers the challenge within ~50
  requests. `AspiraAvailabilityClient` enforces a global mutex with a
  1.5s minimum gap. Same throttle in `scripts/fetch_aspira_*.py`.
- **Error semantics:** plain `4xx`/`5xx` for genuine failures.
  `200` with an HTML body is the WAF saying no — treat as a transient
  block, not a parse error.

## ID model

Every Aspira ID is a **negative 32-bit signed int** of the form
`-2147483648 + offset`. No relation between IDs across tenants
(Banff Tunnel Mountain in PC and Alta Lake in WA can have the same
`mapId`).

Three layers of identity:

- `transactionLocationId` — the **park** (e.g. "Battle Ground Lake
  State Park"). One per bookable destination. This is what
  `AspiraJoinByNameEtl` uses to identify a POI.
- `resourceLocationId` — usually equal to `transactionLocationId` for
  state parks; can differ in multi-resource configurations (e.g.
  marinas + campgrounds at the same park).
- `mapId` — a node in the leaf hierarchy: park → loop/area → leaf.
  Bookable leaves carry a `transactionLocationId`; intermediate nodes
  don't. `AspiraLeavesWalk` flattens this tree.
- `resourceId` — the **individual reservable site** (e.g. "C13 Phantom
  RV Pad"). Stored as our `reservables.vendor_id`. This is the key
  in `/api/resourcelocation/resources` (the catalog source of truth)
  and the key our `AspiraAvailabilityClient` looks up at request time
  in `/api/availability/map`'s `resourceAvailabilities` block.

`pois.provider_ref` is the POI identity/join ref, not necessarily the best
booking-grid deep link. Some Parks Canada campground POIs use a container
`mapId`, while their sites live under child grid maps. `AspiraJoinByNameEtl`
therefore stores `properties.upstream.booking_cta_provider_ref` when the
inventory exposes child `mapIds[]`; the POI drawer uses that for the primary
booking CTA and keeps `provider_ref` stable for joins and provider dispatch.

## Endpoint catalog

### `GET /api/maps`

The **leaf hierarchy for the entire tenant.** One call returns every
park's tree. We capture this once per tenant via
`scripts/fetch_aspira_maps.py` and walk it in `AspiraLeavesWalk` /
`AspiraLeavesEtl`.

```
GET https://{host}/api/maps
→ 200 application/json
[
  {
    "mapId": -2147483608,
    "transactionLocationId": -2147483648,
    "resourceLocationId": -2147483648,
    "title": "Tunnel Mountain - Village 2",
    "mapLinks": [
      {
        "childMapId": -2147483599,
        "transactionLocationId": -2147483648,
        "title": "C Section",
        ...
      },
      ...
    ]
  },
  ...
]
```

Used for: POI enumeration and parent-name labeling for nested loops.
`AspiraJoinByNameEtl` emits one campground POI per leaf that carries
**both** a `transactionLocationId` and a `resourceLocationId`. Leaves
with a `transactionLocationId` but a null `resourceLocationId` are
park-level container nodes (e.g. "Banff", "Camano Island", "Wells Gray"),
not bookable campgrounds — they are skipped so they don't duplicate the
park's already-correct campground POIs.

A leaf can also carry a `resourceLocationId` and still not be a campground:
`/api/maps` mounts activities (parking, guided hikes, shuttles, day-use
buses) as sibling leaves, and their names sometimes match park geometry.
Those are dropped by cross-referencing the inventory against the dictionary's
own `showResourceCapacityOnline` flag: each `resource_categories[]` entry sets
it `true` for overnight-stay categories (Campsite, Yurt, oTENTik, Backcountry
Site, …) and `false` for activity categories. A `resourceLocationId` whose
`/api/resourcelocation/resources` catalog is made up **entirely** of
`showResourceCapacityOnline: false` categories is dropped. This is data-driven,
not a curated list — a new activity category Aspira ships is caught
automatically, and a new stay category is kept automatically.

The filter is wired identically for all three tenants (inventory +
dictionaries as ETL inputs). Parks Canada sets the flag on its activity
categories, so PC drops them; Washington and BC currently mark every category
bookable, so nothing is dropped there — the ETL reflects what each tenant's
data actually shows. A `resourceLocationId` that mixes any bookable category
with a non-bookable one is kept (e.g. Fundy's "Headquarters" node, which
fronts 100+ real campsites). "Daily Fishing" is kept too: PC files it as a
`showResourceCapacityOnline: true` capacity booking, so the data says it is a
standard reservable.

### `GET /api/availability/map`

Per-day availability for one map node, optionally with per-resource
breakdown. This is the workhorse for both the FE drawer and the
alert poller.

```
GET https://{host}/api/availability/map
  ?mapId={int}
  &bookingCategoryId=0
  &startDate=YYYY-MM-DD
  &endDate=YYYY-MM-DD
  &isReserving=true
  &getDailyAvailability=true     # required for per-day breakdown
  &partySize=1
  &equipmentCategoryId=-32768    # "any equipment" sentinel
  &subEquipmentCategoryId=-32768

→ 200 application/json
{
  "mapId": -2147483608,
  "mapAvailabilities": [6,6,0,0,0],          // park rollup, one status per day
  "mapLinkAvailabilities": {
    "-2147483599": [1,1,0,1,0],              // per-loop, one status per day
    ...
  },
  "resourceAvailabilities": {
    "-2147482882": [{"availability":0}, {"availability":1}, ...],
    ...                                       // per-individual-site, one status per day
  }
}
```

Map and map-link status codes (see `AspiraStatus.kt`):

| Code | Meaning |
|---|---|
| 0 | unknown / no data |
| 1 | available |
| 2 | available |
| 3 | available |
| 5 | closed |
| 6 | available |
| 7 | available |
| unknown | unknown |

Resource rows use a different code family (see
`AspiraResourceAvailability.kt`): `0` is bookable, nonzero codes are not
bookable for the requested date/equipment search, and a missing availability
field is stored as an internal unknown sentinel.

Used for: drawer week grid, bulk score endpoint, alert poller. Called
at request time by `AspiraAvailabilityClient`; not captured by an
ETL fetcher. The reservable catalog comes from
`/api/resourcelocation/resources` instead — `resourceAvailabilities`
returns empty for parent leaves whose children carry the actual
sites, so it's an unreliable enumeration source.

### `GET /api/occupancy`

Stay-level booking search for one `resourceLocationId`. The endpoint
accepts a wider `[startDate, endDate]` span plus `nights=1`, but the
response is still flat: one `resourceOccupancy[]` row per resource, not
one row per resource per arrival date. That makes it useful for a
specific stay search, but not as the default source for our per-day
availability grid or availability-history writes.

```
GET https://{host}/api/occupancy
  ?resourceLocationId={int}
  &startDate=YYYY-MM-DD
  &endDate=YYYY-MM-DD
  &nights=1
  &bookingCategoryId=0
  &equipmentCategoryId=-32768
  &subEquipmentCategoryId=-32768

→ 200 application/json
{
  "resourceLocationId": -2147483558,
  "startDate": "2026-07-18T00:00:00",
  "endDate": "2026-07-21T00:00:00",
  "resourceOccupancy": [
    {"resourceId": -2147477470, "occupancy": 0, "filtered": false, "availability": 1}
  ],
  "mapOccupancy": [
    {"mapId": -2147483358, "availability": 2}
  ]
}
```

Used for: opt-in stay-level checks only. Normal catalog availability uses
`/api/availability/map` so callers get independent per-day observations.

### `GET /api/resourcelocation/resources`

**The named-site catalog.** This is the endpoint we were missing
before the 2026-06-15 probe. Returns every reservable site at a park
with its human-readable name, description, equipment compatibility,
capacity, and attributes.

```
GET https://{host}/api/resourcelocation/resources
  ?resourceLocationId={int}

→ 200 application/json
{
  "-2147482882": {
    "resourceId": -2147482882,
    "resourceLocationId": -2147483646,
    "resourceCategoryId": -2147483647,
    "feeScheduleId": -32755,
    "dateScheduleId": -2147483515,
    "resourceModel": 0,
    "localizedValues": [{
      "cultureName": "en-US",
      "name": "OFC13",                       // site short label
      "description": "C13 Phantom RV Pad"    // human description
    }],
    "allowedEquipment": [
      {"equipmentCategoryId": -32768, "subEquipmentCategoryId": -32764},
      {"equipmentCategoryId": -32768, "subEquipmentCategoryId": -32763},
      ...
    ],
    "maxCapacity": 8,
    "minCapacity": 1,
    "maxBoatLength": null,
    "maxBoatDraft": null,
    "slipWidth": null,
    "definedAttributes": [
      {"attributeDefinitionId": -32715, "attributeId": -2147464259, "value": 60, "values": []},
      {"attributeDefinitionId": -32714, "attributeId": -2147464258, "value": 11, "values": []},
      ...
    ],
    "photos": [],
    "mapIds": [-2147483464],                 // which leaf(s) this site belongs to
    "order": 690
  },
  ...
}
```

Field mapping to our reservable model:

| Aspira field | Our field | Notes |
|---|---|---|
| `resourceId` | `reservables.vendor_id` | Already stored; this is the join key |
| `localizedValues[0].name` | `reservables.name` | **Currently null — fix this** |
| `localizedValues[0].description` | (extend) `reservables.description` | Not yet a column; or stash in `raw` |
| `resourceCategoryId` | `reservables.site_type` (resolved) | Resolve via `/api/resourcecategory` |
| `allowedEquipment[]` | `raw.allowed_equipment` | Enrich through `/api/equipment` for human labels |
| `maxCapacity`, `minCapacity` | `raw.capacity` | Both useful for filters |
| `maxBoatLength`, `maxBoatDraft`, `slipWidth` | `raw.marina` | Marina/boat-only fields; null for camping |
| `definedAttributes[]` | `raw.attributes` | Enrich through `/api/attribute/filterable` for human labels |
| `mapIds[]` | `raw._parent_map_ids` | Already partially in our raw under `_parent_aspira_map_id` |

Used for: `AspiraResourcesEtl` reads this catalog and populates
`reservables.name`, `reservables.raw.description`,
`reservables.raw.allowed_equipment[]`, and
`reservables.raw.defined_attributes[]`. Captured by
`scripts/fetch_aspira_inventory.py` per-tenant under
`data_source: aspira-inventory-{tenant}` (see
`backend/src/main/resources/poi-registry.yaml`).

Capture cost: one request per park (per `resourceLocationId`). At our
1.5s throttle, ~3 minutes for ~120 PC parks; ~5 minutes for ~140 WA
parks.

### `GET /api/resourceLocation`

Tenant-wide list of all parks (`resourceLocation` rows). One call
returns every park's metadata: name, GPS, address, photos,
description.

```
GET https://{host}/api/resourceLocation
→ 200 application/json
[
  {
    "resourceLocationId": -2147483647,
    "transactionLocationId": -2147483647,
    "rootMapId": -2147483396,
    "timeZoneOffset": -420,
    "localizedValues": [{
      "cultureName": "en-US",
      "shortName": "Alta Lake",
      "fullName": "Alta Lake State Park",
      "description": "Alta Lake State Park is a 174-acre camping park...",
      "drivingDirections": "",
      "streetAddress": "1 B Otto Road",
      "city": "Pateros",
      "website": "https://parks.wa.gov/find-parks/state-parks/alta-lake-state-park"
    }],
    "gpsCoordinates": "48.03218, -119.9347",
    "region": "Washington",
    "regionCode": "98846",
    "country": "USA",
    "phoneNumber": "",
    "resourceCategoryIds": [-2147483648, -2147483647, -2147483643],
    "photos": [...]
  },
  ...
]
```

Used for: not currently. Today we get park name + GPS from external
geometry feeds (uscampgrounds, bcparks-strapi, apca-accommodation)
because Aspira's `/api/maps` carries booking IDs but no lat/lng. This
endpoint *does* carry GPS — we could simplify `AspiraJoinByNameEtl`
by reading from here instead of the external feeds, but that's a
separate refactor.

### `GET /api/equipment`

Equipment dictionary for the tenant. One call, small payload (~3KB).

```
GET https://{host}/api/equipment
→ 200 application/json
[
  {
    "equipmentCategoryId": -32768,
    "order": 1,
    "localizedValues": [{"cultureName": "en-US", "name": "Equipment"}],
    "subEquipmentCategories": [
      {"subEquipmentCategoryId": -32768, "order": 1, "localizedValues": [{"name": "1 Tent"}]},
      {"subEquipmentCategoryId": -32767, "order": 2, "localizedValues": [{"name": "2 Tents"}]},
      {"subEquipmentCategoryId": -32765, "order": 4, "localizedValues": [{"name": "1 Van/Camper"}]},
      {"subEquipmentCategoryId": -32764, "order": 5, "localizedValues": [{"name": "1 RV/Trailer up to 20'"}]},
      {"subEquipmentCategoryId": -32763, "order": 6, "localizedValues": [{"name": "1 RV/Trailer up to 25'"}]},
      {"subEquipmentCategoryId": -32759, "order": 7, "localizedValues": [{"name": "1 RV/Trailer up to 30'"}]},
      {"subEquipmentCategoryId": -32762, "order": 8, "localizedValues": [{"name": "1 RV/Trailer up to 35'"}]},
      ...
    ]
  },
  {
    "equipmentCategoryId": -32767,
    "localizedValues": [{"name": "Group"}],
    "subEquipmentCategories": [
      {"subEquipmentCategoryId": -32761, "localizedValues": [{"name": "Tents"}]},
      {"subEquipmentCategoryId": -32760, "localizedValues": [{"name": "Trailers"}]}
    ]
  }
]
```

Used for: resolving the `allowedEquipment` tuples in
`/api/resourcelocation/resources`. Captured by
`scripts/fetch_aspira_dictionaries.py` alongside resource categories
and attribute definitions; loaded as an in-memory ETL side input.
Capture once per tenant; cache indefinitely (changes are rare and ETL
re-runs pick them up).

## Sentinel and ID conventions

- **`-32768` ("any equipment")** in `equipmentCategoryId` /
  `subEquipmentCategoryId`. Used by the SPA when no equipment filter
  is set; we use the same when probing.
- **Equipment IDs are tenant-local but their *names* are stable.**
  PC and WA both use `-32768` for "1 Tent"-equivalent slots, but the
  exact ID-to-name mapping should be re-fetched per tenant.
- **`childMapId` vs `mapId`.** In the `/api/maps` tree, parent nodes
  have `mapId`; references to children inside `mapLinks[]` use
  `childMapId`. Same number space. `AspiraLeavesWalk` handles both
  shapes; new code should not duplicate that logic.

### `GET /api/maps?resourceLocationId={id}`

**The park-level interactive map graphic plus clickable hotspots.**
Driven by the FE's "Map view" of search results. Returns a PNG URL
and a polygon-overlay layout per loop / per individual site.

```
GET https://{host}/api/maps?resourceLocationId={resourceLocationId}
→ 200 application/json
[
  {
    "mapId": -2147483332,
    "transactionLocationId": -2147483646,
    "resourceLocationId": -2147483646,
    "title": "Battle Ground Lake — Sites 1-35",
    "mapImageUrls": {
      "en-US": "https://{host}/images/bada1453-2132-44ed-8cdb-70a01e653fd3.png"
    },
    "mapLinks": [
      {
        "childMapId": -2147483293,
        "resourceLocationId": -2147483538,
        "transactionLocationId": -2147483564,
        "localizations": [{"cultureName":"en-US","title":"Camano Island Campground"}],
        "localizationPoint": {"xCoordinate":534,"yCoordinate":295,"justification":0,"rValue":0,"gValue":0,"bValue":0},
        "areaPoints": [
          {"order":0,"xCoordinate":720,"yCoordinate":258},
          {"order":1,"xCoordinate":502,"yCoordinate":258},
          ...
        ],
        "fontSize": 14
      },
      ...
    ],
    "parentMap": null,
    "versionId": -2147364014
  },
  ...
]
```

Scope notes:

- **Top-level (no params):** returns 6 nodes — region maps for the
  whole tenant (NE/NW/SE/SW WA, etc.). Used by the "where am I"
  overview.
- **`?resourceLocationId={id}`:** returns ~6 nodes for a single park
  — usually the park-level map plus per-loop maps. Each carries its
  own `mapImageUrls` and its own polygon overlay.
- **Drilling deeper:** clicking a loop in the SPA resolves to a more
  detailed loop-level map with per-site hotspots. The same endpoint
  shape, scoped narrower.

Field mapping:

| Field | Use |
|---|---|
| `mapImageUrls["en-US"]` | The actual park map PNG. Stable URL, can be cached. ~30-200KB per image. |
| `mapLinks[].areaPoints[]` | Polygon (image-pixel coordinates) of the clickable region for one loop or site. Use to overlay our own availability colors. |
| `mapLinks[].localizationPoint` | Where to draw the label (image-pixel x/y). |
| `mapLinks[].childMapId` | Drilldown — fetch this mapId to get the next zoom level. |
| `mapLinks[].transactionLocationId` | Park (or sub-park) the link points at. |

**Coordinates are image-pixel, not lat/lng.** The map graphic is a
hand-drawn artistic rendering, not a satellite image. Polygons are
relative to the PNG's pixel space (typically ~800x600).

Used for: not currently. Promising for our drawer — we could render
the vendor's own map graphic with our availability heat overlay on
top, instead of (or alongside) MapLibre. Capture per
`resourceLocationId`; cheap (~6 small JSON nodes per park, plus the
PNGs once each).

### `GET /api/resourcecategory`

Site-type dictionary for the tenant. Resolves `resourceCategoryId`
into "Campsite" / "Cabin" / "Group Site" / "RV Hookup" labels.

```
GET https://{host}/api/resourcecategory
→ 200 application/json
[
  {
    "resourceCategoryId": -2147483648,
    "localizedValues": [{"cultureName":"en-US","name":"Campsite","description":""}],
    "resourceType": 0,
    "showResourceCapacityOnline": true
  },
  ...
]
```

WA returns 28 categories. Used for: populating
`reservables.site_type` from the `resourceCategoryId` on each
resource.

### `GET /api/attribute/filterable`

Site-level attribute dictionary. The user-facing subset of
`definedAttributes` — these are the filters the SPA shows in its
"Filters" panel (electrical, water, sewer, max length, shade, etc.).

```
GET https://{host}/api/attribute/filterable
→ 200 application/json
[
  {
    "attributeDefinitionId": -32721,
    "attributeType": 0,
    "minValue": 0,
    "maxValue": 42,
    "isFilterable": false,
    "isDisabled": false,
    "order": 80,
    "localizedValues": [{"cultureName":"en-US","displayName":"Picnic Tables"}]
  },
  ...
]
```

WA returns 62 attribute definitions. Used for: resolving the
`attributeDefinitionId` keys in the `definedAttributes` block of
`/api/resourcelocation/resources` into human labels. Captured by
`scripts/fetch_aspira_dictionaries.py` and loaded as an in-memory ETL
side input.

### `GET /api/capacitycategory/capacitycategories`

Capacity-category dictionary (e.g. "Adults", "Children", "Vehicles").
Powers the people-picker in the SPA's search bar.

```
GET https://{host}/api/capacitycategory/capacitycategories
→ 200 application/json
[
  {
    "capacityCategoryId": -32768,
    "name": "Total Party Size",
    "capacityCategoryType": 1,
    "isDisabled": true,
    "localizedValues": [{
      "cultureName": "en-US",
      "displayName": "Party Size",
      "description": "",
      "capacityUnitLabel": "",
      "capacityUnitLabelPlural": null
    }],
    "subCapacityCategories": []
  },
  ...
]
```

Lower priority. Used for: the future "match alert if 4 people fit
here" predicate, not anything we support today.

### `GET /api/dateschedule/resourcelocationid?resourceLocationId={id}`

**Operating + reservable date windows per park.** This is what tells
the FE "this park is closed Oct-Apr" vs "this park is open year
round". Unlocks "park closed" as a distinct heat-strip status from
"available, no openings".

```
GET https://{host}/api/dateschedule/resourcelocationid?resourceLocationId=-2147483646
→ 200 application/json
{
  "-2147483514": {
    "scheduleId": -2147483514,
    "resourceLocationId": -2147483646,
    "displayOnline": false,
    "reservableDates": [
      {"reservableDates":{"start":"2011-05-13T07:00:00Z","end":"2011-09-14T07:00:00Z"}, "goLiveDate":null, "goLiveTimeZone":"Pacific Standard Time"},
      {"reservableDates":{"start":"2025-05-15T07:00:00Z","end":"2025-09-15T07:00:00Z"}, ...},
      ...
    ],
    "operatingDates": [{"start":"1753-01-01T04:57:00Z","end":"9999-12-31T05:00:00Z"}],
    "holidayWeekends": [],
    "minStayOverrides": [],
    "maxStayOverrides": [],
    "allowedArrivalDepartureDays": [],
    "dateScheduleTransactionWindows": [
      {
        "transactionType": 0,                    // 0=reservation, 2=permit, 3=...
        "minimumReservationWindows": [...],
        "maximumReservationWindows": [...],     // booking horizon: how far out users can book
        "minimumPermitWindows": [...],
        "maximumPermitWindows": [...]
      },
      ...
    ]
  },
  ...
}
```

Field highlights:

- `reservableDates[]` — historical and future booking windows (one
  row per year). Lets us answer "is this park bookable on date X".
- `operatingDates[]` — when the park itself is operating
  (`1753-9999` is the SPA's "always open" sentinel).
- `dateScheduleTransactionWindows[].maximumReservationWindows[]` —
  the rolling booking horizon. Aspira's 9-month / 365-day horizon
  comes from here, not from a global constant. Per-tenant per-park,
  so a config-driven adapter could read the actual horizon instead
  of hardcoding `365`.

Used for: not currently. Promising for distinguishing "closed for
season" from "no availability" in the heat strip; lets the alert
poller skip slots outside `reservableDates[]` (saves upstream calls).

### `GET /api/parkalert/all`

Tenant-wide list of advisories: closures, restroom outages, fire
bans, etc. Shown as banners on the SPA.

```
GET https://{host}/api/parkalert/all
→ 200 application/json
[
  {
    "administrativeMessageUid": "1e9c88c2-...",
    "affectedResourceLocationIds": [-2147483633],
    "transactionDates": [{"start":"2024-09-18T07:00:00Z","end":"2026-06-30T07:00:00Z"}],
    "bookingDates":     [{"start":"2025-09-15T00:00:00","end":"2026-06-30T23:59:59.997"}],
    "localizedValues": [{
      "cultureName": "en-US",
      "messageTitle": "Campsite, Yurt, and Cabin Reservations Closed 9/16/25",
      "htmlMessageText": "<p>The campsites, yurts, and cabins at Cape Disappointment ...</p>"
    }]
  },
  ...
]
```

Field highlights:

- `affectedResourceLocationIds[]` — which parks the alert applies to.
- `bookingDates[]` — the booking-window the alert blocks. Crucial
  for alerts: "your alert at park X for next weekend won't fire
  because the park is closed".
- `transactionDates[]` — when the alert is shown to users.
- `htmlMessageText` — already-styled HTML (verdana, etc.). We'd
  strip styling before showing.

Used for: not currently. High-value for our alert poller — we can
suppress alerts for slots inside an `affectedResourceLocationIds` +
`bookingDates` window, and surface the alert text on the campground
card.

### `GET /api/transactionlocation`

Tenant-wide list of bookable destinations (parks + non-park entities
like "Scheduled Job Transaction Location"). One call returns 116
rows for WA. Each row carries timezone, region, contact info, and
`feeSchedulesByBookingCategory`.

Field highlights worth noting:

- `gpsCoordinates` — sometimes empty for non-park rows (jobs, admin
  locations). For real parks, falls back to the same value as
  `/api/resourceLocation`.
- `feeSchedulesByBookingCategory` — maps
  `bookingCategoryId` → `feeScheduleId`. Pricing is keyed by
  category × fee-schedule × resource-category × rate-category.

Used for: not currently. The full pricing graph would let us answer
"how much will this booking cost" (a common ask alongside
availability), but we'd need to also probe the `/api/feeschedule`
endpoint family — which returned 404 on every variant we tried, so
the SPA likely fetches fees on a per-cart basis, not per-resource.

## Lat/lng availability summary

| Source | Real-world lat/lng? |
|---|---|
| `/api/resourcelocation/resources` (per-site catalog) | **No.** Only `mapIds[]` (image-space references). |
| `/api/maps` / `/api/maps?resourceLocationId=…` | **No.** `xCoordinate`/`yCoordinate` are image-pixel coordinates relative to `mapImageUrls`, not WGS84. |
| `/api/resourceLocation` (tenant-wide park list) | **Yes**, on the park row: `gpsCoordinates: "48.03218, -119.9347"`. One coordinate per park, not per site. |
| `/api/transactionlocation` | Yes for parks, often empty for non-park rows. |

Bottom line: Aspira gives us per-park GPS but never per-site GPS.
Per-site geometry lives only in image-space relative to the vendor's
PNG. To pin individual sites on a real-world map we'd have to
either (a) georeference the PNG manually per park, or (b) match
sites against a third-party feed that has per-site coordinates
(uscampgrounds.info has some; rec.gov's RIDB has them for federal
sites). We already do (b) at the park level; per-site is unsolved.

## Open questions

These endpoints appeared in the network log but weren't fully
captured; lower priority than the ones above.

1. **`GET /api/attribute/getById?id={attributeId}`** — per-attribute
   detail. Probably redundant given `/api/attribute/filterable`
   returns the dictionary in one call; useful only if there are
   non-filterable attributes the bulk endpoint hides.
2. **`GET /api/reachableresources/resourcelocationid`** — unclear;
   possibly the subset of resources reachable from a given equipment
   filter. Probe to confirm.
3. **`/api/feeschedule/{id}` family** — every variant we tried
   returned 404. The SPA must fetch fees through the cart. Worth a
   targeted re-probe with cart context.
4. **`/api/photo/bannerimage?resourceLocationId={id}`** — returned
   204 No Content for Battle Ground Lake. The photos are inline on
   `/api/resourceLocation`'s `photos[]`; this endpoint may be a
   legacy or override.

When probing these, write the JSON shape into this doc next to the
endpoint and remove it from "Open questions".

## Capture commands (2026-06-15 baseline)

These are the literal commands used to capture the wire shapes above.
Re-run after a vendor SPA update (the SPA build hash in the home page
HTML is the freshness signal).

```bash
B=$HOME/.claude/skills/gstack/browse/dist/browse

# 1. Headed Chromium, real fingerprint, gets past the WAF.
$B connect

# 2. Land on the WA tenant homepage — it consents and loads main bundle.
$B network --clear
$B goto "https://washington.goingtocamp.com/"
sleep 3

# 3. Click the "I Consent" gate. This triggers the lazy-loaded chunks
#    that include the catalog endpoints.
$B click @e1
sleep 3

# 4. Enumerate the API surface as the page actually called it.
$B network 2>&1 | grep -oE '/api/[a-zA-Z0-9/_-]+' | sort -u

# 5. Probe each candidate from inside the page so cookies come along.
$B js "fetch('/api/resourcelocation/resources?resourceLocationId=-2147483646').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,4000))"
$B js "fetch('/api/resourceLocation').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,4000))"
$B js "fetch('/api/equipment').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,4000))"
$B js "fetch('/api/maps?resourceLocationId=-2147483646').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,4000))"
$B js "fetch('/api/resourcecategory').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,2000))"
$B js "fetch('/api/attribute/filterable').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,2000))"
$B js "fetch('/api/dateschedule/resourcelocationid?resourceLocationId=-2147483646').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,3000))"
$B js "fetch('/api/parkalert/all').then(r=>r.json()).then(d=>JSON.stringify(d).slice(0,3000))"

# 6. Done. Disconnect to drop back to headless mode.
$B disconnect
```

`resourceLocationId=-2147483646` is Battle Ground Lake State Park (WA).
Substitute any park's ID — the shape is identical across parks within
a tenant.

## See also

- [reservation-providers.md](../reservation-providers.md) — architecture
  contract: ports, capabilities, registry shape.
- `service/availability/provider/adapters/aspira/AspiraAvailabilityProvider.kt` — the
  current adapter (availability only).
- `service/etl/aspira/AspiraResourcesEtl.kt` — emits campsite rows,
  emits one campsite per inventory record; reads the `/api/maps`
  tree only to label each row's `loop` via a `mapIds[0]` lookup.
- `client/AspiraAvailabilityClient.kt` — the request-time HTTP
  client; pattern to copy for an inventory client.
- `scripts/fetch_aspira_inventory.py` — per-park inventory fetcher
  (one call per `resourceLocationId`, resumable).
- `.claude/skills/probe-vendor-api/SKILL.md` — methodology used to
  build this doc.
