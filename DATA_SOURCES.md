# Data Sources

Summary of research for each POI category, with the chosen primary/fallback and a refresh plan. See `REQUIREMENTS.md` for context.

## Summary table

| Category | Primary source | Fallback | License | Refresh | Runtime access |
|---|---|---|---|---|---|
| Tesla Supercharger | supercharge.info `/service/supercharge/allSites` | Open Charge Map (operator=23) | Source-available, no formal license; community-consumed | Daily via GH Action (or direct browser — CORS enabled) | Prebuilt GeoJSON |
| Planet Fitness | Overpass (`brand=Planet Fitness`) | One-time scrape of pf.com locator | OSM ODbL | Weekly | Prebuilt GeoJSON |
| Campgrounds | RIDB (federal) + USCampgrounds.info (public) | Overpass `tourism=camp_site` | Public domain + CC-BY + ODbL | Weekly | Prebuilt GeoJSON |
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

No single complete source. Merge two public ones:

**RIDB (Recreation.gov) — federal**
- API: `https://ridb.recreation.gov/` (free key at https://ridb.recreation.gov/profile/apikeys)
- Full nightly JSON dump at `https://ridb.recreation.gov/download`
- ~4,000+ NPS/USFS/BLM/USACE campgrounds. Public domain.
- No CORS → prebuild.

**USCampgrounds.info CSV — public (state/county/private)**
- URL: `https://uscampgrounds.info/takeit.html`
- ~14,000 public campgrounds, US + Canada. CC-BY licensed.
- Updated ~monthly by the maintainer.

**Optional — Overpass `tourism=camp_site`** for gap-filling / dispersed camping.

**Plan:** weekly GH Action fetches RIDB + USCampgrounds, merges, dedupes by proximity (~100m) and name, writes `data/campgrounds.geojson`.

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
.github/workflows/refresh-data.yml   # scheduled cron
  ├─ scripts/fetch-superchargers.js  # daily  — supercharge.info allSites
  ├─ scripts/fetch-free-chargers.js  # daily  — NREL AFDC + OCM merge
  ├─ scripts/fetch-campgrounds.js    # weekly — RIDB + USCampgrounds.info
  ├─ scripts/fetch-planet-fitness.js # weekly — Overpass
  └─ scripts/fetch-state-parks.js    # monthly — PAD-US FeatureServer
commits any changed GeoJSON back to main → Pages redeploys.
```

This sidesteps:
- CORS (no runtime cross-origin calls — though supercharge.info, Overpass, OCM, NREL AFDC, PAD-US all support CORS and could be fetched live if we wanted)
- API keys in the browser (keys live in GH Action secrets)
- Rate limits (one fetch per source per day, not per pageload)
- Third-party uptime (site still works if a source is down — just stale)

**API keys needed (all free, store as GH Action secrets):**
- `NREL_API_KEY` — developer.nrel.gov (1000 req/hr)
- `OCM_API_KEY` — openchargemap.org
- `RIDB_API_KEY` — ridb.recreation.gov (DEMO_KEY does NOT work; real key required)
- supercharge.info, Overpass, PAD-US, USCampgrounds.info — no key needed

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
