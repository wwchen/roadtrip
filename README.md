# Roadtrip Map

Personal web map for roadtripping a Tesla. Live at [roadtrip.floo.ca](https://roadtrip.floo.ca).

## Layers

- **Tesla Superchargers** — supercharge.info, live fetch; per-site pricing from tesla.com (US + Canada)
- **Planet Fitness** — OSM Overpass
- **Campgrounds** — USCampgrounds.info (federal / state / local), with federal sites enriched from recreation.gov (photos, ratings, per-carrier cell coverage, containing unit name)
- **National & State Parks** — USGS PAD-US polygons
- **Basemap picker** — OpenFreeMap (Liberty/Bright/Positron), Carto (Voyager/Positron/Dark), OSM, plus an Esri satellite overlay
- **Geolocation** — standard geolocate control

## Local dev

```sh
make run                 # bare python3 server.py, http://127.0.0.1:8765
make docker-run          # local docker build, port-publishes 8765, no cloudflared
make deploy              # ssh to the mini, git pull, docker compose up
make deploy-local        # full stack (app + cloudflared) on this host
make refresh-cookies     # push Tesla cookies from clipboard → mini
```

Pricing requires Tesla cookies in `.env` — see `README_PRICING.md`. To refresh
cookies from a laptop that isn't on the mini's network, turn on the Tailscale
exit node pointing at the mini, then run `make refresh-cookies`.

## Refresh POI data

```sh
python3 scripts/fetch_planet_fitness.py   # Overpass — no key
python3 scripts/fetch_campgrounds.py      # USCampgrounds.info regional CSVs
python3 scripts/enrich_campgrounds.py     # rec.gov search + rating/review APIs
python3 scripts/fetch_parks.py            # PAD-US FeatureServer
```

`enrich_campgrounds.py` is resume-safe (skip features already marked
`enriched: true`). Pass `--refresh` to re-query everything; `--limit N` to
test on a small sample.

## Deploy via Docker + Cloudflare tunnel

1. **Create a Cloudflare tunnel.** Zero Trust → Networks → Tunnels → Create
   tunnel; set the public hostname to route to `http://app:8765`. Copy the
   tunnel token.

2. **`.env` on the host:**
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   CLOUDFLARE_TUNNEL_TOKEN=eyJhIjoi...
   ```

3. **Bring up the stack:**
   ```sh
   make deploy-local           # or make deploy to do it remotely via ssh
   ```

   The `app` container serves the map on port 8765 (not exposed on the host
   unless you use `docker-compose.local.yml`). `cloudflared` opens the
   tunnel to Cloudflare's edge. A `cookie-bot` service sits under a profile
   for future use (see cookie-bot/README notes in the code) — currently
   disabled because no aarch64 Chromium build passes Akamai's TLS fingerprint
   gate on the mini.

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

- **No backend database.** Supercharger data is live from
  supercharge.info/service/supercharge/allSites; other layers are static
  GeoJSON committed to the repo.
- **Pricing proxy** — `server.py` proxies `/api/pricing/<locationSlug>` to
  `tesla.com/api/findus/get-charger-details`, caches 30 days. Shells out to
  `curl-impersonate` (Safari/Chrome presets) because Akamai fingerprints TLS
  ClientHello + HTTP/2 SETTINGS — stock OpenSSL curl gets 403.
- **Map** — MapLibre GL, vector and raster basemaps, runtime style-swap.
  Overlay data is cached in memory and re-installed on every `style.load`
  so basemap swaps don't wipe POIs.
