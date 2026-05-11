# Data Sources

Summary of research for each POI category, with the chosen primary/fallback and a refresh plan. See `REQUIREMENTS.md` for context.

## Summary table

| Category | Primary source | Fallback | License | Refresh | Runtime access |
|---|---|---|---|---|---|
| Tesla Supercharger | supercharge.info `/service/supercharge/allSites` | Open Charge Map (operator=23) | Source-available, no formal license; community-consumed | Daily via GH Action (or direct browser — CORS enabled) | Prebuilt GeoJSON |
| Planet Fitness | Overpass (`brand=Planet Fitness`) | One-time scrape of pf.com locator | OSM ODbL | Weekly | Prebuilt GeoJSON |
| Campgrounds | USCampgrounds.info + recreation.gov API enrichment | Overpass `tourism=camp_site` | CC-BY + ODbL (rec.gov public domain) | Weekly | Prebuilt GeoJSON |
| Free chargers | NREL AFDC (filtered) | Open Charge Map (`usagetypeid=1`) | US Gov public domain + ODbL | Daily via GH Action | Prebuilt GeoJSON |
| State parks | USGS PAD-US 4.0 (filter state-managed) | OSM `protect_class` | Public domain | Annual | Prebuilt GeoJSON |
| Hipcamp | (none — skip for POC) | Manually-curated GeoJSON | n/a | Manual | Prebuilt GeoJSON |

---

## 1. Tesla Superchargers

**Primary — supercharge.info**
- URL: `https://supercharge.info/service/supercharge/allSites` (full world dump, ~6MB JSON)
- Changelog: `https://supercharge.info/service/supercharge/changes` (paginated)
- Atom feed: `https://supercharge.info/service/supercharge/feed/atom.xml`
- Source: community-curated (like OSM for Superchargers). Humans submit edits via forum.supercharge.info; editors vet. Cross-references Tesla's `locationId`, PlugShare `plugshareId`, and OSM `osmId` on each record.
- Coverage verified: 10,492 sites worldwide, **3,883 US** (3,077 OPEN, 460 PLAN, 146 CONSTRUCTION, 118 PERMIT, 50 CLOSED_PERM, rest EXPANDING/VOTING/CLOSED_TEMP). Includes sites ahead of Tesla's own find-us page.
- Fields: `gps.{latitude,longitude}`, `address.{street,city,state,zip,countryId}`, `stallCount`, `powerKilowatt`, `stalls.{v2,v3,v4,other,accessible,trailerFriendly}`, `plugs.{tpc,nacs,ccs1,ccs2,gbt,multi}`, `status` (OPEN/PLAN/CONSTRUCTION/PERMIT/VOTING/CLOSED_*), `dateOpened`, `solarCanopy`, `battery`, `otherEVs`, `facilityName`, `elevationMeters`, `plugshareId`, `osmId`.
- **CORS enabled** (`Access-Control-Allow-Origin: *`) — can fetch directly from the browser. Still recommend prebuilding daily so the app works offline and doesn't depend on their uptime.
- License: no LICENSE file on their GitHub org. Source-available, not formally open. Personal/hobby consumption is customary — multiple public projects consume these endpoints. Use respectfully; don't redistribute the raw dump.
- Filter for US roadtripping: `countryId == 100` (USA). Optionally hide `CLOSED_PERM`; keep PLAN/CONSTRUCTION/PERMIT for forward-planning since openings happen fast.

**Fallback — Open Charge Map**
- URL: `https://api.openchargemap.io/v3/poi/?countrycode=US&operatorid=23&maxresults=10000`
- Key: free signup at openchargemap.org. CORS enabled. Format: JSON/GeoJSON. License: ODbL (attribute OCM).
- Lags new openings by days–weeks; useful as a cross-check and as a backup if supercharge.info ever goes down.

**Rejected — Tesla `find_us` JSON**
- Endpoint: `https://www.tesla.com/cms/data/find_us`. Verified 403 from non-browser IPs (Akamai blocks). ToS-gray. supercharge.info is strictly better for this use case (richer schema, includes planned/construction sites, reliable access).

## 2. Planet Fitness

PF has no open API and their ToS forbids scraping. Two pragmatic options:

**Primary — OpenStreetMap via Overpass**
- Query: `nwr["brand"="Planet Fitness"]` within US bbox. (Wikidata ID for Planet Fitness is **Q7201095**; earlier research had the wrong ID.)
- Endpoint: `https://overpass-api.de/api/interpreter`
- Coverage verified: **1,408 elements** in the continental US bbox (960 nodes + 446 ways + 2 relations), ~54% of the ~2,600 real PF locations. Tags include address, phone, website, opening_hours when populated.
- License: ODbL, fine for personal use with attribution.
- CORS-friendly.

**Fallback — one-time scrape of planetfitness.com locator**
- Their site hits an internal JSON endpoint (`/api/planetfitness/locations/search` or similar) taking ZIP + radius. Iterate a national ZIP grid, save to `data/planet-fitness.geojson`. Refresh every few months.
- Keep the scraped file local; don't publish the raw dataset.

**Plan:** start with Overpass. Given the verified 54% coverage, the locator scrape will likely be needed for full value — add it after the POC proves out.

## 3. Campgrounds (general)

Two-stage pipeline: USCampgrounds.info gives us the seed list (name, GPS,
category), then a recreation.gov enricher attaches rec.gov-specific metadata
to the federal subset.

**Base — USCampgrounds.info CSV**
- URL: `https://uscampgrounds.info/takeit.html`
- ~14,000 campgrounds, US + Canada (federal, state, county/local, private).
- CC-BY licensed, updated ~monthly by the maintainer.
- Fetcher: `scripts/fetch_campgrounds.py`.

**Enrichment — recreation.gov (federal only)**
- `scripts/enrich_campgrounds.py` queries two public-browser endpoints per federal campground:
  - `GET /api/search?lat=..&lng=..&radius=2&entity_type=campground&inventory_type=camping`
    — rec.gov's map search. Geographic match is far more reliable than name
    matching. Returns `entity_id`, `parent_name` (e.g. "Gifford Pinchot
    National Forest"), `preview_image_url`, `activities`, `average_rating`,
    `number_of_ratings`, `aggregate_cell_coverage`, and more.
  - `GET /api/ratingreview/aggregate?location_id=<id>&location_type=Campground`
    — per-carrier cell coverage on rec.gov's 0–4 scale (0 none, 1 major
    issues, 2 some, 3 good, 4 excellent) for Verizon/AT&T/T-Mobile/Sprint.
- No API key required. These are the same endpoints the rec.gov SPA uses.
- Rate-limited: stays at 4 concurrent with 429 exponential backoff; the
  script is resume-safe via an `enriched: true` flag on each feature.
- Writes these fields into `campgrounds.geojson` properties:
  `recgov_id`, `parent_name`, `parent_type`, `photo_url`, `activities`,
  `rating_reviews` (`[avg, count]`), `cell_coverage` (`{carrier: [avg, count]}`).

**Optional — Overpass `tourism=camp_site`** for gap-filling / dispersed
camping. Not yet integrated.

**Plan:** weekly GH Action runs both scripts in sequence. Enrichment is
idempotent (only re-queries features missing `enriched: true`) so reruns
only hit rec.gov for new or never-matched campgrounds.

**Note on RIDB:** the public Recreation Information Database API
(`https://ridb.recreation.gov/api/v1/facilities/...`, free key required) was
evaluated and can return media + activities per facility, but the SPA-backing
endpoints above return the same data *plus* rating/cell coverage in one
response without a key. RIDB remains a useful fallback if the SPA endpoints
ever tighten access.

## 4. Free chargers (non-Tesla)

**Primary — NREL AFDC**
- URL: `https://developer.nrel.gov/api/alt-fuel-stations/v1.geojson?fuel_type=ELEC&...`
- Key: free, 1000 req/hr.
- Public domain (US Gov).
- "Free" is inferred from `ev_pricing` text — filter client-side: null or matches `/^(free|\$?0(\.00)?|no fee)/i`. Also include known host-pays networks (ChargePoint hosts, Volta, etc.) at your discretion.
- CORS enabled, but we'll still prebuild so no API key lives in the frontend.

**Fallback — Open Charge Map**
- Same endpoint as Supercharger, with `usagetypeid=1` (Public — Free).
- CC-BY-SA, CORS-friendly.

**Plan:** daily GH Action fetches both, filters free, dedupes by lat/lng ~50m, writes `data/free-chargers.geojson`.

## 5. State parks

**USGS PAD-US 4.0**
- Download: https://www.usgs.gov/programs/gap-analysis-project/science/pad-us-data-download
- ArcGIS REST: https://gis1.usgs.gov/arcgis/rest/services/padus4/
- Filter: `Mang_Type = 'STAT'`, `Des_Tp IN ('SP','SRA')` (State Park, State Recreation Area).
- Public domain. CORS enabled on the ArcGIS REST service.
- Authoritative, all 50 states, one dataset.
- Polygons → may render as boundaries + a centroid marker for clickability.

**Plan:** one-time fetch → `data/state-parks.geojson`. Manual refresh annually on new PAD-US releases.

## 6. Hipcamp

Skip for the POC.
- No public API. Cloudflare-protected. ToS prohibits scraping.
- Sitemap + JSON-LD on listing pages exists but not worth the ToS risk for a personal map.
- **UI treatment:** add a "Search on Hipcamp" link that deep-links to their map centered on the current view, rather than embedding their listings.
- **Optional manual override:** curate a tiny `data/hipcamp-favorites.geojson` of sites you've personally bookmarked, if that's useful.

---

## Refresh architecture

```
.github/workflows/refresh-data.yml     # scheduled cron (not yet set up)
  ├─ superchargers (daily)             # live fetch from browser; nothing prebuilt
  ├─ scripts/fetch_campgrounds.py      # weekly — USCampgrounds.info seed
  ├─ scripts/enrich_campgrounds.py     # weekly — rec.gov search + rating/review APIs
  ├─ scripts/fetch_planet_fitness.py   # weekly — Overpass
  └─ scripts/fetch_parks.py            # monthly — PAD-US FeatureServer
commits any changed GeoJSON back to main → deploy server pulls.
```

This sidesteps:
- CORS (no runtime cross-origin calls — though supercharge.info, Overpass, OCM, NREL AFDC, PAD-US all support CORS and could be fetched live if we wanted)
- API keys in the browser (keys live in GH Action secrets)
- Rate limits (one fetch per source per day, not per pageload)
- Third-party uptime (site still works if a source is down — just stale)

**API keys needed (all free, store as GH Action secrets when refresh runs move to CI):**
- `NREL_API_KEY` — developer.nrel.gov (1000 req/hr) — for a future free-chargers fetcher
- `OCM_API_KEY` — openchargemap.org — optional fallback
- supercharge.info, Overpass, PAD-US, USCampgrounds.info, recreation.gov (search + ratingreview) — no key needed

Tesla pricing proxy (server.py) requires a separately-managed session cookie
in `.env`; see README_PRICING.md.

## Open decisions (from REQUIREMENTS.md)

- **Map library**: Leaflet (easier, raster tiles) vs MapLibre GL (vector, nicer). Recommend **MapLibre + Protomaps** — one `.pmtiles` file can be hosted on GH Pages, no tile-server dependency, works offline by design.
- **Clustering**: `supercluster` or the built-in clustering in whichever library.
- **Routing**: use OSRM public demo (`router.project-osrm.org`) for the POC; self-host only if needed.

## Next step
Build a minimal POC: static page + MapLibre + one category loaded from GeoJSON (probably Superchargers, since it's the headline feature and supercharge.info's endpoint is verified working). Prove the refresh pipeline end-to-end on that one category before adding the rest.

## Samples verified (2026-05-07)
Sanity-checked each source from CLI; raw samples in `samples/`:
- ✅ supercharge.info allSites — 3,883 US sites returned
- ✅ Overpass PF (`brand=Planet Fitness`) — 1,408 US elements
- ✅ Overpass Tesla chargers, Overpass campsites
- ✅ NREL AFDC (DEMO_KEY) — CA returned 21,051 ELEC stations, rich `ev_pricing`/`ev_connector_types`/`ev_network` fields
- ✅ USCampgrounds.info regional CSVs (WestCamp.csv = 3,926 rows)
- ✅ PAD-US FeatureServer — CA state parks filter `Mang_Type='STAT' AND Des_Tp='SP'` returns polygons
- ❌ Tesla `find_us` — 403 from CLI (Akamai block), rejected
- ❌ OCM without key — 403 (free key needed, expected)
- ❌ RIDB with DEMO_KEY — 401 (real free key needed)
