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
tilt up                  # Compose stack (Postgres/backend/Grafana/observability/Rec.gov companion)
make run                 # Kotlin/Ktor backend on http://127.0.0.1:8765 (serves static + /api)
make run env=prod        # on the deploy host: build image + docker compose up
make data-fetch TARGET=tesla-locations  # Tesla index + pricing detail raw captures
```

`tilt up` is the easiest path for full-stack dev: Tilt uses Docker Compose
for Postgres, the backend container, Grafana, Loki, Tempo, Prometheus, Alloy,
and the Rec.gov companion HTTP executor. The backend still serves the app on
<http://127.0.0.1:8765>. Grafana is available at <http://127.0.0.1:3000>,
Tempo at <http://127.0.0.1:3200>, Prometheus at
<http://127.0.0.1:9090>, and Alloy at <http://127.0.0.1:12345>. Local
Compose enables anonymous editor access and provisioned dashboard UI saves so
dashboards can be adjusted in the Grafana UI; those saves live in the local
`grafana-data` volume and are not written back to
`grafana/dashboards/*.json`.
The backend container runs with the OpenTelemetry Java agent. Ktor, JDBC, and
JVM telemetry goes to Alloy over OTLP, Alloy forwards traces to Tempo and
metrics to Prometheus, and existing JSON logs still go through Docker stdout to
Loki. Trace/span IDs are injected into Logback MDC, so Grafana can correlate
logs and traces.
Provisioned dashboards include a catalog explorer
(`/d/roadtrip-catalog-explorer/roadtrip-catalog-explorer`) that covers POIs,
campsites, and snapshot-backed availability, plus status overview, POI detail,
Campground detail, Tesla Supercharger detail/stats, Campsite detail/stats, DB
stats, Roadtrip Metrics (`/d/roadtrip-metrics/roadtrip-metrics`),
watch/scheduler health, and API/SQL equivalence.
Tilt UI is at <http://localhost:10350>.

Plain `make run` remains the fastest backend-only loop: it starts Postgres in
Docker and runs the Kotlin/Ktor backend on the host with Gradle. Production
deploys use `make run env=prod`, which builds the backend image and recreates
the production Compose stack.

The Tilt UI also has a `data` cluster of manual-trigger background workers
(none auto-run on `tilt up`) for POI refresh. Tesla Supercharger pricing
isn't surfaced there. Refresh Tesla cookies from a terminal when needed, then
run `make data-fetch TARGET=tesla-locations`.

POI data refresh is a two-step flow. Fetch runs the registry Python fetchers on
the host; import goes through the backend admin API and writes Postgres rows.
Tilt exposes both as manual buttons under the `data` cluster:

```sh
make data-fetch                       # spawn fetchers → data/raw/<source>/<ts>.json
make data-fetch TARGET=campflare-campgrounds-export    # one data_source slug
make data-fetch TARGET=tesla-locations                 # runs tesla-index first
make data-import                      # data/raw/ → Postgres rows via the ETL
make data-import TARGET='Planet Fitness'               # one poi_data/campsite_data row
make data-import TARGET='Tesla Superchargers'          # import Tesla pricing/details
```

`data-fetch` runs the Python fetchers declared under `data_sources:`;
`data-import` runs the Kotlin ETL pipeline (parse → validate → transform
→ upsert). Import runs are recorded in `ingest_runs`. Skipping
`data-fetch` is fine — the ETL runs against the newest capture already
on disk.

Fetch targets are `data_sources.slug` values in
`backend/src/main/resources/poi-registry.yaml`. Import targets are the
display names from `poi_data:`, `campsite_data:`, and
`campsite_parent_joiner:` rows. Adding a vendor means appending registry
rows, writing the fetcher if needed, and wiring the Kotlin ETL adapter.

> Note: `refresh-tesla-cookies` is **Tesla-only**. Recreation.gov auth is
> owned by the companion's logged-in Chromium profile. Two unrelated systems
> that both happen to use the word "cookies."

First time only:

```sh
make install        # Homebrew deps + companion (npm + playwright) + git hooks
```

Tesla pricing is imported into the Supercharger catalog from
`data/raw/tesla-locations/`; the backend never calls Tesla from the user request
path. To refresh it, run `make data-fetch TARGET=tesla-locations` and then
`make data-import TARGET='Tesla Superchargers'`. See `README_PRICING.md` for
cookie details.

## Refresh POI data

Use **`make data-fetch` then `make data-import`**. Fetchers run on the
host first, then the backend admin API imports the newest raw captures
into Postgres. Tilt buttons trigger the same split path. For an
interactive source picker, run `python3 scripts/poll_raw.py` directly.

### Pipeline shape

Every fetcher is thin: hit upstream, wrap the response in a uniform
envelope, write to `data/raw/<source>/<ts>.json`. No transform, no
merge — those happen in the Kotlin ETL (parse → validate → transform →
upsert) when `data-import` runs.

Envelope shape:

```json
{
  "fetcher":         "fetch_aspira_maps",
  "fetcher_version": "2",
  "fetched_at":      "2026-06-07T21:07:39Z",
  "request":  { "url": "...", "method": "GET", "headers": {...} },
  "response": { "status": 200, "headers": {...} },
  "poller_run_id":   null,
  "payload":         <verbatim upstream JSON|string>
}
```

Source registry lives at `backend/src/main/resources/poi-registry.yaml`.
Run `make data-fetch TARGET=--list` for the current set; abridged:

| Source                | Upstream                                | Output dir |
|-----------------------|-----------------------------------------|------------|
| `osm-pf`              | OSM Overpass — Planet Fitness           | `data/raw/osm-pf/<ts>.json` |
| `uscampgrounds`       | uscampgrounds.info regional CSVs        | `data/raw/uscampgrounds/<ts>/{west,…}.json` |
| `bcparks-strapi`      | bcparks.api.gov.bc.ca (paginated)       | `data/raw/bcparks-strapi/<ts>/page-NNN.json` |
| `apca-{accommodation,places}` | Parks Canada ArcGIS feeds        | `data/raw/apca-accommodation/<ts>/...`, `data/raw/apca-places/<ts>/...` |
| `aspira-maps-{pc,bc,wa}` | Aspira `/api/maps` (one row per host) | `data/raw/aspira-maps-pc/<ts>.json`, `…-bc`, `…-wa` |
| `recgov-campgrounds`  | RIDB /facilities (all RIDB agencies)    | `data/raw/recgov-campgrounds/<ts>/page-NNN.json` |
| `recgov-campground-enrichment` | Recreation.gov rating/cell aggregate API | `data/raw/recgov-campground-enrichment/<ts>/facility-<id>.json` |
| `reserveamerica-{abpp,ny}` | Active Network ReserveAmerica (Alberta, New York) | `data/raw/reserveamerica-<contract>/<ts>/{directory-*,park-*}.json` |
| `reservecalifornia-catalog` | ReserveCalifornia Search All + place/facility/grid API | `data/raw/reservecalifornia-catalog/<ts>/{website-settings,search-all,place-*,facility-*,grid-*}.json` |
| `tesla-index`         | tesla.com get-locations (curl-impersonate) | `data/raw/tesla-index/<ts>.json` |
| `tesla-locations`     | tesla.com per-slug, cache-aware (~30d)  | `data/raw/tesla-locations/<slug>/<ts>.json` |

`backend/src/main/resources/poi-registry.yaml` is the source of truth for governing bodies
(NPS, USFS, BC Parks, Alberta Parks, …), POI data sources, and tenant
args. Reservation-provider dispatch uses `pois.source` plus `provider_ref`
JSON rather than a provider FK.

**Raw cache.** `data/raw/` is gitignored — captures are append-only on
the host running the poller. Crawling Aspira/Tesla is expensive (Azure
WAF, curl-impersonate + cookie injection); replaying raw is free.
Recovery on a fresh checkout: re-run the fetchers, or run
`scripts/_migrate_tesla_cache.py` to bootstrap Tesla from the legacy
`data/pricing-cache/` if it's still around.

**Curated repo data** (no fetch step) lives at `data/curated/`:
`parks-canada-{bc,ab}.json`, `alberta-provincial.json`. The Kotlin
importer reads these directly. New POIs should come from a poller, not
from new entries here — these files are tech debt to retire as fetchers
land for each gap (Alberta now covered by the ReserveAmerica fetcher).

### `/api/docs` — interactive API browser

Swagger UI at `/api/docs`, OpenAPI 3.1 spec at `/api/docs/openapi.json`.
Built from the live routing tree at boot, so the doc reflects whatever's
mounted. To document a new route, use the normal `io.ktor.server.routing`
route function and chain `describeApi`:

```kotlin
get("/api/foo") { /* handler */ }
    .describeApi("group", "One-line description")
```

Routes without a doc block still appear in the spec (untitled). The page is
public — paths and summaries only, no secrets.

### Admin API surface

| Verb | Path | Returns |
|------|------|---------|
| POST | `/api/admin/data/import[/{target}]` | sync; runs the Kotlin importer phase(s). 200 on success/noop, 500 on phase failure |
| GET  | `/api/admin/data/runs[?target=…]` | last 50 parent runs |
| GET  | `/api/admin/data/runs/{id}` | parent + ordered phase rows |
| GET  | `/api/admin/data/status` | per-target last completed status + age |

Without a `{target}`, import fans out across every known import target
sequentially.

**Auth boundary:** Cloudflare Zero Trust path rule on `/api/admin/*` — same
tunnel that already fronts the deploy. Workload is idempotent +
non-sensitive (refresh trigger + status read). No in-app token. Locally the
routes are reachable on `127.0.0.1:8765` directly. **If you ever expose dev
to the public internet (port-forward, ngrok, etc.), bind admin routes to
loopback only first.**

The admin import API only runs on hosts where `data/` is writable. In the
Compose stack, the backend container mounts `./data` read-write at
`/app/static/data`; host-side fetchers write raw captures into the checkout
before import.

## Deploy via Docker + Cloudflare tunnel

1. **Create a Cloudflare tunnel.** Zero Trust → Networks → Tunnels → Create
   tunnel; set the public hostname to route to `http://backend:8765`. Copy the
   tunnel token. The tunnel's public hostname routing is managed in Cloudflare;
   Compose only starts `cloudflared` with the token.

2. **`.env` on the deploy host:** Docker Compose reads runtime config from the
   checkout's `.env` when GitHub Deploy or a manual deploy runs
   `make run env=prod`:
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   CLOUDFLARE_TUNNEL_TOKEN=eyJhIjoi...
   POSTGRES_PASSWORD=<strong password>
   GRAFANA_ADMIN_PASSWORD=<strong password>
   GRAFANA_DB_PASSWORD=<strong password>
   ```

   Grafana state is stored in the Compose-managed named volume
   `grafana-data` (Docker prefixes it with the Compose project name);
   Tempo and Prometheus use `tempo-data` and `prometheus-data` for local trace
   and metric retention. Dashboard JSON and datasource provisioning stay
   bind-mounted from `grafana/`.
   Dashboard JSON reconciles on Grafana's provisioning poll
   (`updateIntervalSeconds` is >10 so Grafana polls the files rather than
   relying on inotify, which doesn't cross the bind mount). Deploy also
   restarts Grafana so datasource/config changes reload and dashboards refresh
   immediately rather than on the next poll.
   Provisioned dashboard UI saves are disabled by default on deploy hosts; set
   `GRAFANA_DASHBOARD_ALLOW_UI_UPDATES=true` only when you intentionally want
   Grafana to persist UI edits in its database.

3. **Bring up the stack:** on the deploy host, `make run env=prod` builds the
   backend image and rolls it out with `docker compose up -d` (Postgres and the
   other long-running services stay up). The deploy is wired to GHA
   (push to master → .github/workflows/deploy.yml), so you usually don't run
   this by hand. The older `make deploy` SSH wrapper has been removed.

   The `backend` container serves the map on port 8765 (not exposed to the
   public host — cloudflared talks to it on the compose network). A
   `cookie-bot` service sits under a profile for future use (see
   cookie-bot/README notes in the code) — currently disabled because no
   aarch64 Chromium build passes Akamai's TLS fingerprint gate on the mini.

4. **Tesla pricing captures** are refreshed through the normal data pipeline:
   `make data-fetch TARGET=tesla-locations`, then
   `make data-import TARGET='Tesla Superchargers'`.

### Adding GitHub-backed deploy secrets

Use the helper when adding a new secret runtime env var. It updates
`.env.example`, adds the workflow mapping that can sync the deploy host's
`.env`, and prints the matching `gh secret set` command:

```sh
scripts/add-secret-env.sh NEW_API_KEY --comment "Used by the backend Foo client."
gh secret set NEW_API_KEY
# or, when NEW_API_KEY is already set in your shell:
gh secret set NEW_API_KEY --body "$NEW_API_KEY"
```

To let Deploy write the production `.env` from GitHub secrets, set the repo
variable `SYNC_DEPLOY_ENV_FROM_GITHUB=true`. Leave it unset to keep using the
deploy host's checked-out `.env` file. For environment-scoped secrets, also set
`environment: production` on the deploy job and run the helper with
`--github-env production`.

### Heads up: pricing cookies are IP-bound

Tesla's `_abck` cookie is pinned to the IP that received it. Cookies pasted
from your laptop browser will work from the Docker host **only if the Docker
host egresses from the same public IP** — i.e., same home network. If the
Docker host is elsewhere, either grab cookies from a browser *on* that
network, or have your laptop egress through the host's IP via Tailscale
exit node before running `make data-fetch TARGET=tesla-locations`.
Production hosts mint their own cookies out-of-band.

Cookies expire every day or so. When pricing starts returning 403 or 429,
run `./scripts/refresh-tesla-cookies.sh`, then re-run
`make data-fetch TARGET=tesla-locations`.

## Architecture notes

- **Backend.** Kotlin/Ktor + Netty serves the entire site: `/` →
  `index.html`, `/web/*` and `/data/*` → static, plus `/api/pois` and
  `/api/health`. Postgres+PostGIS holds the imported POI data; Supercharger
  geometry is live from supercharge.info/service/supercharge/allSites.
- **Campsite alert sub-app.** A separate recreation.gov polling/booking tool
  is mounted at `/campsite/` (UI served from the JAR's classpath at
  `backend/src/main/resources/static/campsite/`) with its own API surface
  under `/api/campsite/*` (alerts, matches, settings, status, events SSE,
  poll, companion, campgrounds/search). Shares the same Postgres instance;
  Flyway migrates both schemas on startup.
- **Tesla pricing enrichment.** `make data-fetch TARGET=tesla-locations`
  fetches `tesla-index` first, then per-slug Tesla location captures. The
  `Tesla Superchargers` import side-loads those raw captures and stores
  `pricebooks` on the supercharger rows served by `/api/pois`.
- **Map** — MapLibre GL, vector and raster basemaps, runtime style-swap.
  Overlay data is cached in memory and re-installed on every `style.load`
  so basemap swaps don't wipe POIs.

## Campsite alert tool (`/campsite/`)

The campsite sub-app polls recreation.gov for matching availability against
operator-defined alerts and (optionally) auto-claims matches by adding them
to a real recreation.gov shopping cart. **The cart-add path requires a
separate companion process or container** — recreation.gov sits behind Akamai, which
flags datacenter IPs and headless Chromium, so a real Chromium running on
the operator's machine is the only thing that lands cart adds reliably. The
backend never touches a browser; it only polls the public availability API,
then calls the companion's one-shot executor.

- **`companion/`** — Node 22.9+ Playwright HTTP executor. It exposes
  `POST /recgov/atc`, drives Chromium to add the site to the operator's
  rec.gov cart, and returns a terminal JSON success/failure response to the
  backend.
  ```sh
  cd companion
  npm install
  npm start
  ```
- **Recreation.gov login** happens in the companion's persistent Chromium
  profile. Run the companion headed, log in to recreation.gov in that window
  once, and the companion manages `localStorage.recaccount` and refreshes in
  that same browser context. The backend never receives the Recreation.gov JWT;
  it only sends ATC payloads to the companion and records terminal
  success/failure. The companion exposes `GET /login` as an operator form for
  a Recreation.gov username, password, and optional MFA code. The submitted
  credentials are used only for that request and are not stored in the backend,
  companion config, or environment. To test the real browser auth integration,
  run:
  ```sh
  cd companion
  npm run recgov:login
  npm run recgov:refresh   # force the real Recreation.gov refresh endpoint
  # or from repo root:
  make recgov-login
  make recgov-refresh
  ```
  `recgov:login` exits `0` after `REC_GOV_AUTH_OK`. `recgov:refresh` exits
  `0` after `REC_GOV_AUTH_REFRESH_OK` only after the browser session has
  successfully refreshed through Recreation.gov. If the stored access token is
  still valid but its `refresh_id` has gone stale, `recgov:refresh` clears that
  stale browser `recaccount` and prompts for a fresh headed login. Both commands
  return non-zero after `REC_GOV_AUTH_FAILED` / `REC_GOV_AUTH_ERROR`. The Make
  targets set `COMPANION_BROWSER_PROFILE` from
  `RECGOV_COMPANION_BROWSER_PROFILE`, falling back to
  `$HOME/.campsite-companion/browser-session`, which is the same host path the
  Docker companion mounts.
- **One-shot Rec.gov ATC** runs the same browser add-to-cart code as the HTTP
  executor. This can place a real hold in the operator's Recreation.gov cart,
  so use a real payload only when that side effect is intended.
  ```json
  {
    "payload": {
      "watch_id": 12,
      "start_date": "2026-07-15",
      "end_date": "2026-07-16",
      "openings": [
        {
          "label": "116",
          "date": "2026-07-15",
          "booking_url": "https://www.recreation.gov/camping/campsites/300?startDate=2026-07-15&endDate=2026-07-16",
          "campground_id": 232447,
          "campsite_id": 131925,
          "vendor_id": "300"
        }
      ]
    }
  }
  ```
  ```sh
  cd companion
  npm run --silent recgov:atc -- --payload-file /tmp/recgov-atc.json
  # or from repo root:
  make recgov-atc PAYLOAD=/tmp/recgov-atc.json
  ```
  Browser logs go to stderr. Stdout is one JSON result, suitable for a backend
  process caller; exit `0` means `cart_added=true`, exit `1` means the browser
  ran but did not confirm a cart hold, and exit `2` means invalid input.
- **Docker Rec.gov ATC executor** runs the companion HTTP executor as a Compose
  service. Start the service with the `recgov-companion` profile; the backend
  companion URL and timeout live in `backend/src/main/resources/application*.yml`.
  ```sh
  RECGOV_COMPANION_BROWSER_PROFILE=$HOME/.campsite-companion/browser-session

  docker compose --profile pois --profile recgov-companion up -d recgov-companion backend
  ```
  The mounted browser profile is the same persistent profile used by
  `recgov:login`. Open `http://localhost:8770/login` to submit Recreation.gov
  credentials to the companion container for a one-request login. The companion
  container name is under the `roadtrip-*`
  Compose project, so Alloy's Docker log discovery ships its stdout/stderr to
  Loki; the backend also records the terminal ATC result and sends Slack for
  direct success/failure.
- **Slack notifications** are optional. Create a Slack app with the
  `chat:write` scope, install it to the workspace, and paste the bot
  token (`xoxb-…`) plus a channel name (`#camping-alerts`) or channel ID
  into Settings → Slack. The backend posts via `chat.postMessage`.
- **Without the companion**, alerts still fire and Slack still posts —
  every "Auto-add to cart" toggle and the "Test browser session" /
  "Test credentials" buttons in Settings just no-op (`SettingsRoutes`
  returns `not_implemented` for the Chromium-dependent endpoints).
