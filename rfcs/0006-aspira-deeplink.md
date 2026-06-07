---
title: Booking deeplink for Aspira-platform parks (Parks Canada, WA State Parks, BC Parks)
authors:
  - William Chen
created: 2026-06-07
last_updated: 2026-06-07
rfc_pr: TBD
status: Draft
---

# Proposal: Booking deeplink for Aspira-platform parks

## Summary

Replace the current hardcoded `https://reservation.pc.gc.ca` Reserve link
on Parks Canada campgrounds with a real deeplink to the campground's
booking page on Aspira's NextGen platform. The same pattern works for
several other systems on the same vendor (Washington State Parks via
`washington.goingtocamp.com`, BC Discover Camping, etc.), so the fix is
factored as one URL builder + a per-source `aspira` config block.

## Vendor research

The booking platform behind `reservation.pc.gc.ca` is **Aspira's NextGen**
(formerly Active Network → Going to Camp). Identification:

- The URL's querystring uses `transactionLocationId`, `mapId`,
  `resourceLocationId`, `searchTabGroupId`, `bookingCategoryId`,
  `peopleCapacityCategoryCounts` — all Aspira NextGen conventions.
- All IDs are large negative ints (Aspira's internal sentinel +
  database-id convention).
- Stack runs on Azure (`x-ms-request-id` header).
- Same querystring shape on `washington.goingtocamp.com`,
  `discovercamping.ca` (BC Parks), `ontarioparks.reserveamerica.com`
  (older variant).

**Public API (no auth, no session):**

```
GET https://reservation.pc.gc.ca/api/maps    → 175 KB JSON
GET https://washington.goingtocamp.com/api/maps  → similar shape
```

Returns a hierarchical map of every bookable area. Each top-level node
is a park (Banff, Yoho, Glacier, …) with:

```json
{
  "mapId": -2147483630,
  "mapType": 2,                              // 1=region, 2=park, ...
  "transactionLocationId": -2147483648,
  "localizedValues": [
    {"cultureName": "en-CA", "title": "Banff"},
    {"cultureName": "fr-CA", "title": "Banff"}
  ],
  "mapLinks": [
    {
      "childMapId": -2147483509,             // sub-area: campground/loop
      "resourceLocationId": -2147483614,
      "transactionLocationId": -2147483635,
      "localizations": [{"cultureName":"en-CA","title":"#3 - 24, O1"}]
    },
    ...
  ]
}
```

## Deeplink URL shape

The user-facing booking URL:

```
https://reservation.pc.gc.ca/create-booking/results
  ?transactionLocationId={tx}
  &resourceLocationId={resLoc}                    -- "NULL" string for park-level
  &mapId={mapId}
  &searchTabGroupId=0
  &bookingCategoryId=0
  &startDate={YYYY-MM-DD}
  &endDate={YYYY-MM-DD}
  &nights=1
  &isReserving=true
  &equipmentId=-32768                              -- Aspira sentinel for "any"
  &subEquipmentId=-32768                           -- "any sub-equipment"
  &peopleCapacityCategoryCounts=[[-32767,null,1,null]]   -- "1 person, any category"
  &searchTime={ISO-8601}
  &flexibleSearch=[false,false,null,1]
  &view=list
  &filterData={"-32756":"[[1],0,0,0]"}             -- default filters
```

Most params are inert defaults — only `transactionLocationId`, `mapId`,
and the dates per-trip. `startDate`/`endDate` can default to today/+1
day; the user can change them on the booking page.

## Goals

1. Reserve link on a Parks Canada campground in the drawer goes to the
   real Aspira booking page for that campground (or the park containing
   it), pre-filled with sensible defaults.
2. Same builder works for WA State Parks campgrounds (already on the
   same platform) and any future Aspira-platform target.
3. The URL builder is fed by data already in the curated parks-canada
   JSON files; no per-trip API call required from the frontend.

## Non-goals

- Real-time availability inside the drawer. The booking page handles
  that; we just deeplink there.
- Showing Aspira's session-cookie-bound state. The deeplink works
  unauthenticated; users sign in on the destination if booking.
- Auto-discovering `mapId` from upstream by name-matching. Each
  campground's IDs are recorded in the curated JSON file once, then
  cached forever (Aspira IDs are stable across years).

## Proposal

### 1. Augment curated `data/parks-canada-{bc,ab}.json`

Each campground entry gets two new fields:

```json
{
  "name": "Tunnel Mountain Village I",
  "park": "Banff",
  ...
  "aspira": {
    "transactionLocationId": -2147483648,
    "mapId": -2147483630
  }
}
```

`aspira` is namespaced so future records for non-Aspira systems
(e.g. recreation.gov for US federal — already handled separately
via `recgov_id`) don't collide.

Sourcing the IDs: a one-time script that walks `/api/maps` from
`reservation.pc.gc.ca` and maps each `mapType=2` (park) entry to its
`mapId` + ancestor `transactionLocationId`. Fuzzy-matched against the
curated park names. Run once, results committed.

For sub-park granularity (specific campground within a park, e.g.
"Tunnel Mountain Village I" within Banff), the `mapLink.childMapId` is
the per-campground value. The curated files already encode each
campground individually, so this is a tighter match than park-level.

### 2. New `web/aspira.js` URL builder

```js
// 25 LOC: builds the deeplink given an aspira block + optional dates.
export function buildAspiraDeeplink({ host, transactionLocationId, mapId, resourceLocationId = 'NULL', startDate, endDate }) {
  const today = new Date().toISOString().slice(0, 10);
  const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
  const params = new URLSearchParams({
    transactionLocationId: String(transactionLocationId),
    resourceLocationId: String(resourceLocationId),
    mapId: String(mapId),
    searchTabGroupId: '0',
    bookingCategoryId: '0',
    startDate: startDate || today,
    endDate: endDate || tomorrow,
    nights: '1',
    isReserving: 'true',
    equipmentId: '-32768',
    subEquipmentId: '-32768',
    peopleCapacityCategoryCounts: '[[-32767,null,1,null]]',
    searchTime: new Date().toISOString().replace(/\.\d+/, '.000').replace(/Z$/, ''),
    flexibleSearch: '[false,false,null,1]',
    view: 'list',
    filterData: '{"-32756":"[[1],0,0,0]"}',
  });
  return `https://${host}/create-booking/results?${params}`;
}
```

### 3. `web/campground-card.js` swap

```diff
} else if (p.parks_canada_url && p.reservable) {
-  // The parks.canada.ca pages are informational; reservation.pc.gc.ca is
-  // the actual booking site. Deeplink to the latter when reservable.
-  url = 'https://reservation.pc.gc.ca';
+  if (p.aspira?.mapId != null) {
+    url = buildAspiraDeeplink({
+      host: 'reservation.pc.gc.ca',
+      transactionLocationId: p.aspira.transactionLocationId,
+      mapId: p.aspira.mapId,
+    });
+  } else {
+    // Fallback: the bare booking site. User will have to navigate.
+    url = 'https://reservation.pc.gc.ca';
+  }
   label = 'Reserve on parks.canada.ca';
```

### 4. (Future) Same shape for WA State Parks

When we add WA state-park campgrounds to the data, they get the same
`aspira` field with `host: 'washington.goingtocamp.com'`. The builder
is host-agnostic.

## Test plan

- Generate the ID lookup script, run it once locally, commit the
  augmented JSON.
- Open a campground in Banff in the dev environment → click Reserve →
  lands on the real Aspira page with Banff selected.
- Repeat for one BC park (Yoho), one Atlantic park (Fundy), confirm
  the IDs survive across regions.
- Smoke-test the URL builder with unit tests (deterministic startDate/
  endDate inputs).

## Open questions

1. **Where should the ID lookup live — at fetch time or curation time?**
   Today the parks-canada files are hand-curated (per
   `scripts/fetch_parks_canada.py`'s comment "no authoritative points
   dataset"). The Aspira `/api/maps` endpoint *is* authoritative for
   the IDs, so it's natural to fetch and merge them. Probably easiest
   as a one-shot helper script, then re-run if Aspira ever renumbers.
2. **Park-level vs campground-level deeplink.** `mapType=2` IDs deeplink
   to the park page (which lists every campground inside). The
   `mapLink.childMapId` IDs deeplink directly to a campground. Per-
   campground is sharper but requires more name-matching against
   `mapLink.localizations[].title`. Start with park-level; refine if
   the user complains.
3. **Date defaults.** Today/+1 vs trip-aware dates. The drawer doesn't
   currently know trip dates, so today/+1 is a fine default — the user
   sees the booking page and adjusts.

## Decision log

| # | Date | Decision | Rationale |
|---|------|----------|-----------|
| 1 | 2026-06-07 | Vendor identified as Aspira NextGen. | Confirmed by URL token names, internal-int IDs, Azure backend, and matching shape across reservation.pc.gc.ca + washington.goingtocamp.com. |
| 2 | 2026-06-07 | Use the public `/api/maps` endpoint to source per-campground IDs. | No auth, no session, ~175 KB; covers every bookable area including sub-park campgrounds. Works for any Aspira-platform target. |
| 3 | 2026-06-07 | Augment curated JSON with `aspira` block at curation time, not at request time. | IDs are stable; runtime lookup would burn an upstream call per drawer open. One-time script writes once, served forever. |
| 4 | 2026-06-07 | Park-level deeplink first; per-campground refinement later. | Simpler ID lookup. The booking page lists every campground in the park, so the user is one click from the right one. |
