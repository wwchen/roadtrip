# Aspira campsite photos — design

Issue: #758. Follows audit finding 2 (#745), which typed the campsite `photos` column and made `CampsiteDto.photo_url` its first entry, and carries the one part of the closed #721 that #745 left out.

## Problem

`AspiraCampsitesEtl` builds its `CampsiteUpsertCandidate` without `photos`, so every Aspira campsite (WA, BC, PC) persists an empty `photos` column and the drawer renders no image. The `/api/resourcelocation/resources` inventory object carries them:

```json
"photos": [
  { "photoUrlResult": { "url": "https://{host}/images/{uuid}.jpg", "avifUrl": "https://{host}/images/{uuid}.avif" }, "aspectType": 0 }
]
```

Measured on the newest capture per tenant (2026-06-16): WA 6,086 of 7,212 resources, BC 8,090 of 10,650, PC 8,338 of 11,860 carry at least one photo; every entry has `photoUrlResult.url`, absolute on the tenant host.

## Decisions

- **Read `photoUrlResult.url` only.** `CatalogPhoto` has one `url`; the JPEG is the universally renderable one. `avifUrl` and `aspectType` are not promoted. Nothing reads them, and adding fields to `CatalogPhoto` would touch every vendor for no consumer.
- **All photos, in source order.** `CampsiteDto.photo_url` takes the first; the rest are in the column for a future gallery, the same as Campflare and rec.gov.
- **Skip entries without a usable URL.** A missing `photoUrlResult`, a missing `url`, or a blank `url` drops that entry, never the row. A resource with no `photos` key or an empty array yields an empty list, as today.
- **No schema, wire, or frontend change.** The column and the DTO field exist; the frontend already renders `photo_url`. `sourcePayload` does not gain photos.
- **Doc:** the inventory example in `docs/reservation-providers/aspira.md` shows the real `photos` shape and the field table gains a `photos[]` row pointing at `campsites.photos`. The rest of that table is stale (it names a `reservables` table); rewriting it is out of scope and noted on the issue.

## Deploy

No migration. `make data-import` for the three Aspira rows fills the column. Until then Aspira drawers keep showing no photo, exactly as today.

## Verification

- Unit: a fixture with three photo entries (one good, one without `photoUrlResult`, one with a blank `url`) yields exactly one `CatalogPhoto`; the existing fixture without a `photos` key yields an empty list.
- Live: re-import WA, BC, PC on a `pg_dump` copy with the branch image; count campsites with a non-empty `photos` per tenant against the measurement above; `GET /api/pois/{id}/campsites` for an Aspira campground returns `photo_url` on the tenant host; `make qa` passes.
