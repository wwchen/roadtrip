# Pricing setup

Supercharger pricing is served read-only from `data/pricing-cache/{slug}.json`.
The cache is populated **offline** by `scripts/fetch_tesla_index.py` +
`scripts/fetch_tesla_locations.py` (run via
`make fetch-tesla-supercharger-pricing`, which mints cookies, smoke-tests
them, and walks the full fetch; or pick a single fetcher interactively
with `make poll-raw`). The Kotlin backend never calls Tesla on the
request path. Misses on `/api/pricing/{slug}` return HTTP 404 with
`{"error":"not_cached"}`.

> Captures also land in envelope-wrapped form under
> `data/raw/tesla-locations/<slug>/<ts>.json`. `/api/pricing/{slug}`
> currently serves the legacy `data/pricing-cache/<slug>.json` files
> until the Kotlin ETL takes over. `scripts/_migrate_tesla_cache.py`
> mirrors the legacy cache into the new layout (idempotent, no upstream
> calls) when bootstrapping a new host.

The offline refresh worker hits `tesla.com/api/findus/get-charger-details`
through `curl-impersonate` (Akamai fingerprints TLS ClientHello + HTTP/2
SETTINGS) and needs a valid `_abck` cookie tied to the calling IP. Cookies
live in `.env` as `TESLA_COOKIES=…`.

## One-time setup (or refresh when expired)

```sh
make refresh-tesla-cookies   # mint cookies into THIS repo's .env (laptop-only egress)
```

Mints cookies from Safari on the laptop, smoke-tests them, and writes
`.env` here for iterating on the fetcher script in local Docker.
Production hosts mint their own cookies out-of-band — there's no longer
a one-shot remote-push target in this repo.

## When cookies expire

Akamai cookies last on the order of a day; they're also IP-bound.
If a refresh run starts returning 403/429, re-run `make refresh-tesla-cookies`.
(The user-facing site is unaffected — it serves the existing cache; only the
*next* refresh is blocked.)

## What gets cached

Each site's response (including the `availabilityProfile` congestion histogram and
the `effectivePricebooks` rate schedule) goes to `data/pricing-cache/<slug>.json`.
Delete a file or the whole directory to force a refetch.
