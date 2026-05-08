# Roadtrip Map

Personal web map for roadtripping a Tesla. Layers:
- Tesla Superchargers (supercharge.info, live) — with per-site pricing from tesla.com
- Planet Fitness (OSM)
- Campgrounds (USCampgrounds.info — federal / state / local)
- National & State Parks (USGS PAD-US polygons)

## Local dev

```sh
python3 server.py
# http://localhost:8765
```

Pricing requires Tesla cookies in `.env` — see `README_PRICING.md`.

## Refresh data

```sh
python3 scripts/fetch_planet_fitness.py   # Overpass — no key
python3 scripts/fetch_campgrounds.py       # 5 regional CSVs
python3 scripts/fetch_parks.py             # PAD-US FeatureServer
```

Re-run monthly or whenever you want fresher data.

## Deploy via Docker + Cloudflare tunnel

Mirrors the `campsite` project layout.

1. **Create a Cloudflare tunnel.** In Cloudflare Zero Trust → Networks → Tunnels → Create tunnel. Set the public hostname to route to `http://app:8765`. Copy the tunnel token.

2. **Add to `.env`:**
   ```
   TESLA_COOKIES=ak_bmsc=...; _abck=...; bm_sz=...; ...
   CLOUDFLARE_TUNNEL_TOKEN=eyJhIjoi...
   ```

3. **Bring up the stack:**
   ```sh
   docker compose up -d --build
   docker compose logs -f
   ```

   The `app` container serves the map on port 8765 (not exposed on the host by default — only reachable via the tunnel). `cloudflared` opens the tunnel to Cloudflare's edge.

4. **Pricing cache** persists in `$HOME/.roadtrip-map/pricing-cache` (override with `CACHE_DIR=…` in `.env`).

### Heads up: pricing cookies are IP-bound

Tesla's `_abck` cookie is pinned to the IP that received it. Cookies pasted from your laptop browser will work from the Docker host **only if the Docker host egresses from the same public IP** — i.e., same home network. If the Docker host is on a different network, grab cookies from a browser on that network instead (open tesla.com/findus from the remote host, copy Cookie header, put in its `.env`).

Cookies expire every day or so. When pricing starts returning 403s, re-paste cookies and `docker compose up -d` to pick them up.

## Architecture notes

- **No backend database.** Supercharger data is live from supercharge.info/service/supercharge/allSites; other layers are static GeoJSON committed to the repo.
- **Pricing proxy** — `server.py` proxies `/api/pricing/<locationSlug>` to `tesla.com/api/findus/get-charger-details`, caches 30 days. Uses `curl --http2` because Akamai fingerprints HTTP version.
- **Map** — MapLibre GL + OSM raster tiles. No vector tiles / no tile host.
