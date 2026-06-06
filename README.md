# Roadtrip Map

Personal web map for roadtripping a Tesla. Live at [roadtrip.floo.ca](https://roadtrip.floo.ca).

## Layers

- **Tesla Superchargers** — supercharge.info, live fetch; per-site pricing from tesla.com (US + Canada)
- **Planet Fitness** — OSM Overpass
- **Campgrounds** — USCampgrounds.info (federal/state/local, US) + BC Parks (BC provincial) + hand-curated Parks Canada (BC national parks), with US federal sites enriched from recreation.gov (photos, ratings, per-carrier cell coverage, containing unit name)
- **National & State Parks** — USGS PAD-US polygons
- **Basemap picker** — OpenFreeMap (Liberty/Bright/Positron), Carto (Voyager/Positron/Dark), OSM, plus an Esri satellite overlay
- **Geolocation** — standard geolocate control

## Local dev

```sh
tilt up                  # full dev stack (postgres in Docker, backend + companion on host)
make run                 # Kotlin/Ktor backend on http://127.0.0.1:8765 (serves static + /api)
make companion           # campsite Playwright companion against the local backend
make deploy              # ssh to the mini, git pull, docker compose up
make refresh-cookies     # push Tesla cookies from clipboard → mini (offline refresh worker only)
```

Local dev runs the backend on the host (Gradle), with only Postgres in
Docker. The backend's `Dockerfile` is still used by `make deploy` to build
the container that runs on mini-ca behind cloudflared, but it's no longer
part of the laptop dev loop.

`tilt up` is the easiest path for full-stack dev: Tilt brings up Docker
Postgres (idempotent `compose up -d`), runs the backend with Gradle on the
host so Kotlin recompiles are fast, and runs the campsite companion as a
host Node process so Playwright can drive a real Chromium. Tilt UI is at
<http://localhost:10350>.

The Tilt UI also has a `data` cluster of manual-trigger background workers
(none auto-run on `tilt up`): `refresh-superchargers` / `rebuild-superchargers`
for Tesla pricing, `refresh-tesla-cookies` to mint fresh `_abck` cookies for
the Tesla scraper into `.env`, and `refresh-image` (one-shot prereq for the
supercharger refreshers). Click the row, watch logs in the right pane.

POI data refresh goes through the backend's admin API (RFC 0004). Two-step
flow, two Tilt buttons under the `data` cluster, two make targets:

```sh
make data-fetch                       # web → data/<target>.{json,geojson}
make data-fetch TARGET=campgrounds    # one target only
make data-import                      # data/ → Postgres rows via Importer
make data-import TARGET=planet-fitness
```

`data-fetch` runs the Python fetchers (`scripts/fetch_*.py`,
`scripts/enrich_campgrounds.py`); `data-import` runs the Kotlin importer.
Each phase is recorded in `ingest_runs`; per-target mutex serializes a
fetch and an import on the same target. Skipping `data-fetch` (e.g.
because `data/` is already populated) is fine — `data-import` runs
independently.

Targets: `planet-fitness`, `state-parks`, `national-parks`, `campgrounds`
(composite — uscampgrounds + bc-parks + parks-canada + recgov-enrichment),
`parks-canada-curated` (curated JSON, no fetch step), `alberta-provincial`
(curated JSON, no fetch step), `tesla-pricing` (cache rebuild, no import
step).

> Note: `refresh-tesla-cookies` is **Tesla-only**. Recreation.gov auth is
> backend-owned via `TokenManager` (RFC 0001 / PR #22) — paste a fresh cURL
> in the campsite Settings UI and the backend handles refresh on its own
> cadence. Two unrelated systems that both happen to use the word "cookies."

First time only:

```sh
make install        # Homebrew deps + companion (npm + playwright) + git hooks
```

Pricing is served from the on-disk cache (`data/pricing-cache/`). Tesla is
never called from the user request path — the backend just reads cached JSON
and 404s with `{"error":"not_cached"}` for sites that haven't been crawled.
To populate/refresh the cache, run `make refresh-superchargers` (full run,
~25 min) or `make rebuild-superchargers` (cache-only, ~30s). That worker
needs Tesla cookies in `.env` — see `README_PRICING.md` and
`make refresh-cookies` / `make refresh-cookies-local`.

## Refresh POI data

Use `make data-fetch` then `make data-import` (or the matching Tilt buttons).
The underlying Python fetchers can still be invoked directly when you want
to inspect the on-disk artifact without importing:

```sh
python3 scripts/fetch_planet_fitness.py   # Overpass — no key
python3 scripts/fetch_campgrounds.py      # USCampgrounds.info regional CSVs (US)
python3 scripts/fetch_bc_parks.py         # BC Parks Strapi API (BC provincial, merges into campgrounds.geojson)
python3 scripts/fetch_parks_canada.py     # Hand-curated Parks Canada BC national-park campgrounds
python3 scripts/enrich_campgrounds.py     # rec.gov search + rating/review APIs (US federal)
python3 scripts/fetch_parks.py            # PAD-US FeatureServer
```

`enrich_campgrounds.py` is resume-safe (skip features already marked
`enriched: true`). Pass `--refresh` to re-query everything; `--limit N` to
test on a small sample.

### `/api/docs` — interactive API browser

Swagger UI at `/api/docs`, OpenAPI 3.1 spec at `/api/docs/openapi.json`.
Built from the live routing tree at boot, so the doc reflects whatever's
mounted (issue #47). To document a new route, replace the `io.ktor.server.routing.{get,post}`
import with `io.github.smiley4.ktorswaggerui.dsl.routing.{get,post}` and pass
a doc block:

```kotlin
get("/api/foo", {
    tags = listOf("group")
    summary = "One-line description"
}) { /* handler */ }
```

Routes without a doc block still appear in the spec (untitled). The page is
public — paths and summaries only, no secrets.

### Admin API surface (RFC 0004)

| Verb | Path | Returns |
|------|------|---------|
| POST | `/api/admin/data/fetch[/{target}]` | sync; runs Python fetcher phase(s). 200 on success, 500 + `failed_phase` on phase failure |
| POST | `/api/admin/data/import[/{target}]` | sync; runs the Kotlin importer phase(s). 200 on success/noop, 500 on phase failure |
| GET  | `/api/admin/data/runs[?target=…]` | last 50 parent runs |
| GET  | `/api/admin/data/runs/{id}` | parent + ordered phase rows |
| GET  | `/api/admin/data/health` | per-target last completed status + age |

Without a `{target}`, fetch and import fan out across every known target
sequentially. Per-target mutex means a fetch and an import on the same
target serialize.

**Auth boundary:** Cloudflare Zero Trust path rule on `/api/admin/*` — same
tunnel that already fronts the deploy. Workload is idempotent +
non-sensitive (refresh trigger + status read). No in-app token. Locally the
routes are reachable on `127.0.0.1:8765` directly. **If you ever expose dev
to the public internet (port-forward, ngrok, etc.), bind admin routes to
loopback only first.**

The admin API only runs on hosts where `data/` is writable. The deploy
container's `/app/static/data` is mounted **read-only** by design — POI
refresh runs on the deploy host filesystem before/around `docker compose up`,
not inside the container.

## Deploy via Docker + Cloudflare tunnel

1. **Create a Cloudflare tunnel.** Zero Trust → Networks → Tunnels → Create
   tunnel; set the public hostname to route to `http://app:8765`. Copy the
   tunnel token.

2. **`.env` on the host:**
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   CLOUDFLARE_TUNNEL_TOKEN=eyJhIjoi...
   ```

3. **Bring up the stack:** `make deploy` (ssh's to the mini, git pull, build,
   `docker compose up`). The deploy is also wired to GHA (push to master →
   .github/workflows/deploy.yml), so you usually don't run this by hand.

   The `backend` container serves the map on port 8765 (not exposed to the
   public host — cloudflared talks to it on the compose network). A
   `cookie-bot` service sits under a profile for future use (see
   cookie-bot/README notes in the code) — currently disabled because no
   aarch64 Chromium build passes Akamai's TLS fingerprint gate on the mini.

4. **Pricing cache** persists in `$HOME/.roadtrip-map/pricing-cache`
   (override with `CACHE_DIR=…` in `.env`).

### Heads up: pricing cookies are IP-bound

Tesla's `_abck` cookie is pinned to the IP that received it. Cookies pasted
from your laptop browser will work from the Docker host **only if the Docker
host egresses from the same public IP** — i.e., same home network. If the
Docker host is elsewhere, either grab cookies from a browser *on* that
network, or have your laptop egress through the host's IP via Tailscale
exit node, then `make refresh-cookies`.

Cookies expire every day or so. When pricing starts returning 403 or 429,
re-run `make refresh-cookies`.

## Architecture notes

- **Backend.** Kotlin/Ktor + Netty serves the entire site: `/` →
  `index.html`, `/web/*` and `/data/*` → static (with `/data/pricing-cache/*`
  excluded so it's only reachable through `/api/pricing/{slug}`), plus
  `/api/pois`, `/api/pricing/{slug}`, `/api/health`. Postgres+PostGIS holds
  the imported POI data; Supercharger geometry is live from
  supercharge.info/service/supercharge/allSites.
- **Campsite alert sub-app.** A separate recreation.gov polling/booking tool
  is mounted at `/campsite/` (UI served from the JAR's classpath at
  `backend/src/main/resources/static/campsite/`) with its own API surface
  under `/api/campsite/*` (alerts, matches, settings, status, events SSE,
  poll, companion, campgrounds/search). Shares the same Postgres instance;
  Flyway migrates both schemas on startup.
- **Pricing cache.** `/api/pricing/{slug}` is read-only against
  `data/pricing-cache/{slug}.json`. Misses return 404 with
  `{"error":"not_cached"}`. Cache is populated offline by
  `scripts/fetch_tesla_superchargers.py` (run via `make refresh-superchargers`),
  which shells out to `curl-impersonate` because Akamai fingerprints TLS
  ClientHello + HTTP/2 SETTINGS — stock OpenSSL curl gets 403.
- **Map** — MapLibre GL, vector and raster basemaps, runtime style-swap.
  Overlay data is cached in memory and re-installed on every `style.load`
  so basemap swaps don't wipe POIs.

## Campsite alert tool (`/campsite/`)

The campsite sub-app polls recreation.gov for matching availability against
operator-defined alerts and (optionally) auto-claims matches by adding them
to a real recreation.gov shopping cart. **The cart-add path requires a
separate companion process** — recreation.gov sits behind Akamai, which
flags datacenter IPs and headless Chromium, so a real Chromium running on
the operator's machine is the only thing that lands cart adds reliably. The
backend never touches a browser; it only polls the public availability API,
emits SSE `match` events, and tracks lease state.

- **`companion/`** — Node 20+ Playwright client. Subscribes to the backend
  SSE stream at `/api/campsite/events`, claims matches via
  `POST /api/campsite/matches/{id}/claim`, drives Chromium to add the site
  to the operator's rec.gov cart, reports the result, then PATCHes the
  cart-extend endpoint every 5 minutes to hold the reservation. Heartbeats
  to `/api/campsite/companion/heartbeat` every 30 s.
  ```sh
  cd companion
  npm install
  BACKEND_URL=http://127.0.0.1:8765 \
    RECGOV_RECACCOUNT='{"refresh_id":"…","account_id":"…"}' \
    node --experimental-eventsource src/index.js
  ```
- **`RECGOV_RECACCOUNT`** seeds the companion's persisted refresh token on
  first run (subsequent runs reuse the DB-backed token). To get it: log in
  on recreation.gov in your browser, open DevTools console, run
  `localStorage.getItem('recaccount')`, and paste the JSON blob into the
  env var (or into Settings → Recreation.gov in the `/campsite/` UI, which
  writes it to the `campsite_settings` table via the same path).
- **Slack notifications** are optional. Create a Slack app with the
  `chat:write` scope, install it to the workspace, and paste the bot
  token (`xoxb-…`) plus a channel name (`#camping-alerts`) or channel ID
  into Settings → Slack. The backend posts via `chat.postMessage`.
- **Without the companion**, alerts still fire and Slack still posts —
  every "Auto-add to cart" toggle and the "Test browser session" /
  "Test credentials" buttons in Settings just no-op (`SettingsRoutes`
  returns `not_implemented` for the Chromium-dependent endpoints).
