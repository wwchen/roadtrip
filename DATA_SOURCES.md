# Data Sources

Catalog of the upstream feeds behind the POIs this app serves: what each one
is, what it costs to talk to, and what it is licensed under.

`backend/src/main/resources/poi-registry.yaml` is the source of truth for which
sources exist and how they chain into POI/campsite datasets — this file
describes them, it does not define them. To add one, follow
[docs/adding-a-data-source.md](docs/adding-a-data-source.md). For
reservation-vendor wire shapes (availability, booking refs, rate limits) see
[docs/reservation-providers.md](docs/reservation-providers.md) and the
per-vendor docs under `docs/reservation-providers/`.

## Summary

| Dataset | Upstream | Registry slugs | License | Key |
|---|---|---|---|---|
| Campflare campgrounds + campsites | Campflare bulk export API | `campflare-campgrounds-export`, `campflare-campsites-export` | Vendor API, contractual | `CAMPFLARE_API_KEY` |
| Federal campgrounds | RIDB `ridb.recreation.gov` | `recgov-campgrounds-raw` | US Gov public domain | `RIDB_API_KEY` |
| Federal campground ratings + cell coverage | rec.gov SPA aggregate endpoint | `recgov-campground-enrichment` | US Gov public domain | none |
| Federal campsites | rec.gov monthly availability endpoint | `recgov-campsites-raw` | US Gov public domain | none |
| Washington State Parks | Aspira NextGen + uscampgrounds.info geometry | `aspira-{maps,inventory,dictionaries}-wa`, `uscampgrounds` | Vendor API + CC-BY | none |
| BC provincial parks | Aspira NextGen + BC Parks Strapi | `aspira-{maps,inventory,dictionaries}-bc`, `bcparks-strapi` | Vendor API + OGL-BC | none |
| Parks Canada | Aspira NextGen + APCA ArcGIS layers | `aspira-{maps,inventory,dictionaries}-pc`, `apca-accommodation`, `apca-places` | Vendor API + OGL-Canada | none |
| Alberta provincial parks | ReserveAmerica `shop.albertaparks.ca` | `reserveamerica-abpp`, `reserveamerica-campsites-abpp` | Scraped consumer site | none |
| New York state parks | ReserveAmerica `newyorkstateparks.reserveamerica.com` | `reserveamerica-ny`, `reserveamerica-campsites-ny` | Scraped consumer site | none |
| California state parks | ReserveCalifornia (Tyler Technologies) | `reservecalifornia-catalog` | Scraped consumer API | none |
| Planet Fitness | OpenStreetMap via Overpass | `osm-pf` | ODbL | none |
| Tesla Superchargers | Tesla `find_us` bulk endpoint | `tesla-index`, `tesla-locations` | Vendor site, cached captures | `TESLA_COOKIES` (host-local) |

Every row is fetched by a thin Python script under `scripts/`, writes
envelope-wrapped raw captures under `data/raw/<slug>/`, and is replayed into
Postgres by a Kotlin ETL. Nothing in the table is fetched from the browser at
request time.

## Campgrounds

**Campflare export** — the broad commercial catalog. Bulk campground and
campsite exports behind an API key; the campsite export depends on the
campground export for its id list. Availability wire details:
[docs/reservation-providers/campflare.md](docs/reservation-providers/campflare.md).

**RIDB (federal)** — `https://ridb.recreation.gov/api/v1/facilities/...`, free
key. One capture covers every RIDB-publishing agency (NPS, USFS, BLM, USACE,
FWS, BOR, TVA, …); the per-facility agency lands in campground metadata at
transform time from `ORGANIZATION[0].OrgName`. RIDB carries media and
activities but no ratings or cell coverage.

**rec.gov enrichment (federal)** — `GET /api/ratingreview/aggregate?location_id=
<id>&location_type=Campground` fills the ratings/cell-coverage gap: a listing
rating plus per-carrier coverage on rec.gov's 0–4 scale (0 none … 4 excellent)
for Verizon/AT&T/T-Mobile/Sprint. No key; it is the endpoint the rec.gov SPA
itself uses. One request at a time behind a configurable `--min-gap`, with 429
backoff and `--resume` for partial backfills. `RecGovCampgroundsEtl` promotes
`rating_reviews` (`[avg, count]`) and `cell_coverage` (`{carrier: [avg, count]}`)
into `pois.properties`.

**rec.gov campsites (federal)** — the same monthly availability endpoint used
at request time, walked once per facility for the *catalog* half of the payload
(id, site, loop, campsite type, equipment). ~75 min for ~3000 facilities at the
1.5 s gap, so it runs independently of the campground refresh. See
[docs/reservation-providers/recgov.md](docs/reservation-providers/recgov.md).

**Aspira NextGen tenants** — Washington State Parks, BC Parks, and Parks
Canada all run on the same vendor platform, so one set of fetchers serves three
tenants (`washington.goingtocamp.com`, `camping.bcparks.ca`,
`reservation.pc.gc.ca`). Aspira supplies the park hierarchy and per-park site
inventory; a geometry-side feed supplies coordinates and public park metadata.
Wire details: [docs/reservation-providers/aspira.md](docs/reservation-providers/aspira.md).

- *uscampgrounds.info CSV* — `https://uscampgrounds.info/takeit.html`, ~11,000
  US campgrounds, CC-BY, refreshed ~monthly upstream. Geometry side of the WA
  join.
- *BC Parks Strapi* — `https://bcparks.api.gov.bc.ca/api/protected-areas`,
  public, no key, OGL-BC. Park name, `bcparks.ca` URL, photos, activities, and
  facilities; ~486 parks have camping.
- *Parks Canada ArcGIS* — the public APCA FeatureServer layers
  (`vw_Accommodation_Hebergement_V2_FGP` filtered to `Accommodation_Type='Camping'`,
  and `vw_Places_Public_lieux_public_APCA` as polygon centroids).

**ReserveAmerica tenants** — Alberta provincial parks and New York state parks,
scraped from the consumer reservation sites (no key). The campsite roster comes
from the same `campsiteCalendar.do` page as availability, so catalog ids bind to
availability by construction. Details, including the decommissioned Active
developer API:
[docs/reservation-providers/reserveamerica.md](docs/reservation-providers/reserveamerica.md).

**ReserveCalifornia** — California state parks, discovered the way the public
SPA does (one `/search/place` request with `isSearchAllParks=true`, then
per-place facility and grid captures). Details:
[docs/reservation-providers/reservecalifornia.md](docs/reservation-providers/reservecalifornia.md).

## Planet Fitness

OpenStreetMap via Overpass (`https://overpass-api.de/api/interpreter`), query
`nwr["brand"="Planet Fitness"]` over a US bbox (Wikidata `Q7201095`). ODbL,
attribution required, CORS-friendly. Coverage is partial — roughly 1,400 of the
~2,600 real locations — because it only has what mappers have added. Planet
Fitness has no open API and its ToS forbids scraping its own locator, so the
gap stays.

## Tesla Superchargers

Tesla's own bulk `find_us` endpoint, captured by `scripts/fetch_tesla_index.py`
(index) and `scripts/fetch_tesla_locations.py` (detail pages) through
`scripts/tesla_client.py`. The endpoint sits behind Akamai: fetches use
curl-impersonate plus a browser-minted cookie, and `_abck` is bound to the
egress IP that minted it, so `TESLA_COOKIES` lives in `.env.local` per machine
rather than in the vault. Because fetches can be blocked, the registry keeps
the cached `data/raw/` captures and the ingest row is left disabled for fan-out
imports. The dump is global despite the `country=US` param; the Kotlin ETL
filters to North America at parse time.

## Refresh and import

```
make data-fetch [TARGET=<data_source slug>]   # scripts/poll_raw.py on the host
  → data/raw/<slug>/<UTC-ts>/…                 # envelope-wrapped raw captures
make data-import [TARGET=<poi_data name>]      # POST /api/admin/data/import
  → Postgres + PostGIS                         # Kotlin ETL chain per registry row
  → GET /api/pois?bbox=…                       # what the map reads
```

Fetch is a host-side step (network acquisition only); import is a backend step
(parse → transform → batched upsert). Splitting them is what lets an
unreachable upstream fail a fetch command instead of the boot. Secrets are
injected by `./secrets/manage.py exec`, never written to a plaintext `.env`.

Prebuilding rather than fetching live sidesteps CORS, keeps API keys out of the
browser, spends one upstream request per source per refresh instead of one per
pageload, and keeps the site working (just stale) when a source is down. The
only static files still served from `/data/` are map furniture such as
`us-states.geojson`.

**Keys** (all free; stored in the vault — see [docs/secrets.md](docs/secrets.md)):
`RIDB_API_KEY` for rec.gov RIDB, `CAMPFLARE_API_KEY` for the Campflare export.
Overpass, BC Parks, the APCA ArcGIS layers, uscampgrounds.info, the rec.gov SPA
endpoints, ReserveAmerica, and ReserveCalifornia need no key. `TESLA_COOKIES`
is deliberately host-local, not vaulted.

## Evaluated, not wired

Kept so the research isn't repeated, not because any of it is scheduled.

- **Free (non-Tesla) chargers** — NREL AFDC
  (`developer.nrel.gov/api/alt-fuel-stations/v1.geojson?fuel_type=ELEC`, free
  key, 1000 req/hr, US Gov public domain) as primary, Open Charge Map
  (`usagetypeid=1`, ODbL) as fallback. "Free" has to be inferred from the
  `ev_pricing` text. No fetcher, registry row, or map layer exists today.
- **State park polygons** — USGS PAD-US 4.0 (`Mang_Type='STAT'`,
  `Des_Tp IN ('SP','SRA')`, public domain, ArcGIS REST with CORS). A prebuilt
  `state-parks.geojson` used to be served from `/data/`; it was dropped with
  the legacy importer, and the map renders no park-polygon layer today.
- **Hipcamp** — no public API, Cloudflare-protected, ToS prohibits scraping.
  If it ever matters, deep-link to their map centered on the current view
  rather than embedding listings.
- **Overpass `tourism=camp_site`** — plausible gap-filler for dispersed
  camping. Never integrated.
- **supercharge.info** (`/service/supercharge/allSites`) — community-curated,
  CORS-enabled, richer status vocabulary (PLAN/CONSTRUCTION/PERMIT) than
  Tesla's own feed, but source-available with no formal license. Superseded by
  the Tesla capture above; still the best fallback if that one breaks.
- **Open Charge Map** (`operatorid=23`) — ODbL cross-check for Supercharger
  coverage; lags new openings by days to weeks.
