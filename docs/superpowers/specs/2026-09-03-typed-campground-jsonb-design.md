# Typed campground JSONB columns: normalize on write, one shape on read

**Date:** 2026-09-03
**Status:** Proposed design, not yet implemented
**Follows:** the ETL cleanup that introduced `CampgroundLocation`, `CampgroundLink`,
`CampgroundPhoto`, `CampgroundManagement`, `CampgroundContact` and the
`CampgroundJsonb` encoder in `service/etl/framework`.

## Problem

Five `campgrounds` JSONB columns (`location`, `links`, `photos`, `management`,
`contact`) have a shape that is agreed on by convention in four places:

| Place | What it knows |
| --- | --- |
| Vendor ETLs | Build the shape. Five vendors now go through `CampgroundJsonb`; Campflare still passes its upstream JSON through verbatim. |
| `CampgroundRepo` | Binds `JsonElement` to `?::jsonb` and parses text back to `JsonElement`. Knows nothing about keys. |
| `PoiServingRepo` SQL | Reads `management->>'agency'` and `location->>'region'`. |
| `CampgroundService` | Reads `latitude`, `longitude`, `region`, `country`, `elevation`, `agency`, `url`, and carries per-vendor preference lists (`url` / `large_url` / `medium_url` / `small_url` / `original_url`; `phone` / `primary_phone`; `email` / `primary_email`) because Campflare rows were never normalized. |

The frontend then reads a further superset (`management.agency_name`,
`links[].label`, `address.address.state_code`, ...) because it cannot know
which vendor wrote the row.

The cost is that every new vendor, and every new field, has to be threaded
through all four places by hand, and a vendor that ships a new key silently
serves `null` on the drawer until someone adds it to a preference list.

## Design

**Normalize on write.** `CampgroundUpsertCandidate` carries typed values for
the five columns. Vendor ETLs construct the domain types; Campflare maps its
upstream keys into them in the ETL, which is the layer that knows the vendor.
`CampgroundRepo` is the only encoder.

**One shape on read.** `Campground` (the row model) carries the same typed
values, decoded by `CampgroundRepo`. `CampgroundService` reads properties, and
the preference lists are deleted. The API DTO fields stay `JsonElement` so the
frontend contract is unchanged; the service encodes the typed values with the
same codec.

**Legacy rows.** A Flyway migration rewrites existing rows into the canonical
keys once, so the strict decoder never meets a Campflare-shaped row. No
re-import is required. `source_payload` keeps every upstream key, so the
migration drops nothing that the drawer's "Upstream data" accordion shows.

### Domain types

All in `model/domain/`, one per file, `@Serializable`. Nullable fields default
to `null` and are omitted from JSON.

```kotlin
data class CampgroundLocation(
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val country: String? = null,
    val elevation: Double? = null,
    val address: Address? = null,          // existing model/domain/Address, made @Serializable
)
data class CampgroundLink(val url: String, val title: String? = null)
data class CampgroundPhoto(val url: String)
data class CampgroundManagement(val agency: String, val website: String? = null)
data class CampgroundContact(val phone: String? = null, val email: String? = null)
```

Key names are the ones already stored by every non-Campflare vendor, so the
`PoiServingRepo` SQL and the frontend's first-choice keys keep working with no
change.

### Codec

`model/domain/CampgroundColumnJson.kt`: one `Json { explicitNulls = false;
ignoreUnknownKeys = true }` plus `encode`/`decode` helpers used by
`CampgroundRepo` (write and read) and `CampgroundService` (API DTO). It
replaces `service/etl/framework/CampgroundJsonb.kt`, which is deleted.

### Campflare normalization

In `service/etl/vendors/campflare/`, pure functions from the raw upstream
`JsonObject` to the domain types. Precedence mirrors what the read path does
today so nothing that renders now stops rendering:

| Column | Upstream keys, in precedence order |
| --- | --- |
| `photos[].url` | `url`, `large_url`, `medium_url`, `small_url`, `original_url` |
| `links[]` | `url` or `href`; `title`, `label` or `name` |
| `management` | `agency_name` or `agency` -> `agency`; `agency_website`, `website_url`, `website` or `url` -> `website` |
| `contact` | `phone` or `primary_phone`; `email` or `primary_email` |
| `location.address` | `street`, `street1`, `address_line`; `city`; `state`, `state_code`; `postcode`, `postal_code`, `zipcode`; `country`, `country_code` |
| `location.elevation` | `elevation` |

A photo or link without a usable URL is dropped. Management without an agency
is `null`. Contact with neither phone nor email is `null`.

### Migration

`V55__normalize_campground_jsonb.sql` applies the same table to stored rows
using `jsonb_build_object` / `jsonb_strip_nulls` / `jsonb_array_elements`.
It is idempotent: canonical rows map to themselves.

### Out of scope

- `campsites.photos` shares the photo shape but has one writer per vendor
  ETL and its own repo. Same treatment, separate change.
- `default_campsite_schedule`, `amenities`, `alerts`, `price`,
  `cell_service`, `connections`, `metadata`: vendor-specific bags today. They
  stay `JsonElement` until a second consumer needs a shape.
- Deriving `location.region` for Campflare from `address.state`. Today it is
  `null`; the migration and the ETL keep it `null` so this change stays a
  refactor. Worth doing next, as a one-line ETL change plus a re-import.

## Risks

- **Strict decode on read.** A row whose `location` is `{}` (the column
  default) would fail to decode. The repo maps `{}` to `null` explicitly;
  anything else malformed throws, which is the correct signal after the
  migration.
- **Migration drops unknown keys.** Bounded by `source_payload`. The plan
  includes a dry-run count query to run against production before deploy.
- **Frontend fallbacks.** The frontend keeps its tolerant readers; they are
  now redundant, not wrong. Trimming them is a follow-up once production rows
  are all canonical.
