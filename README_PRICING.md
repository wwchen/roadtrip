# Pricing setup

Supercharger pricing is part of the `Tesla Superchargers` catalog import. The
fetch phase writes envelope-wrapped raw captures under `data/raw/tesla-index/`
and `data/raw/tesla-locations/<slug>/`; the import phase side-loads those
captures and stores `pricebooks` on the supercharger rows served by `/api/pois`.
The Kotlin backend never calls Tesla from the user request path.

## Refresh Tesla pricing

```sh
./scripts/refresh-tesla-cookies.sh        # only when cookies are missing or expired
make data-fetch TARGET=tesla-locations    # runs tesla-index first
make data-import TARGET='Tesla Superchargers'
```

`refresh-tesla-cookies.sh` prompts for a browser cURL paste from
`tesla.com/findus` and writes `TESLA_COOKIES=...` to `.env`. `make data-fetch`
then runs the registry fetchers through `scripts/poll_raw.py`; the
`tesla-locations` row depends on `tesla-index`, so the bulk index capture is
refreshed before the per-slug detail walk.

## When Cookies Expire

Akamai cookies last on the order of a day and are IP-bound. Cookies pasted from
your laptop browser work from the Docker host only when the host egresses from
the same public IP. If pricing fetches return 403 or 429, refresh
`TESLA_COOKIES` from a browser on the same network as the fetch host, then rerun
`make data-fetch TARGET=tesla-locations`.

## What Gets Cached

The fetchers write raw upstream envelopes:

- `data/raw/tesla-index/<ts>.json`
- `data/raw/tesla-locations/<slug>/<ts>.json`

The `Tesla Superchargers` import reads those files into Postgres. The web UI
receives pricing inline on supercharger feature properties; there is no separate
Tesla pricing request on the serving path.
