# First run

What a healthy first `tilt up` looks like, and why the map starts empty.
Prerequisites: `make install`, a vault-recipient age key, and a running Docker
daemon — see [installation.md](installation.md) and
[CONTRIBUTING.md](../CONTRIBUTING.md).

## What a healthy `tilt up` looks like

1. Tilt first decrypts the vault in memory and logs
   `secrets: loaded N for the local environment`. If your age key is not yet a
   recipient, this is where it stops — `SecretsBootstrap` refuses to boot on a
   partial secret set and lists everything missing. Fix per
   [secrets.md](secrets.md), don't work around it.
2. The Tilt UI (<http://localhost:10350>) fills in: infra services
   (`postgres`, `loki`, `tempo`, `prometheus`, `alloy`), then app services
   (`backend`, `recgov-companion`, `grafana`), plus Tilt's own resources
   (`backend-jar`, `compose-cleanup`) and a manual-trigger `data` cluster
   (`data-fetch`, `data-import`) that does **not** auto-run.
3. `backend-jar` runs `./gradlew :backend:buildFatJar` — the first build is
   by far the slowest step (Gradle downloads, jOOQ codegen).
4. The backend container boots, Flyway migrates the empty database, and the
   app answers on <http://127.0.0.1:8765>. Grafana waits for the backend to be
   healthy before connecting as `grafana_reader`, then serves
   <http://127.0.0.1:3000>.

## `/api/health` vs `/api/health/ready`

Two probes, two questions (`route/api/health/HealthRoutes.kt`):

- **`/api/health`** is liveness: it proves only that the Ktor app booted and
  can answer. It deliberately does not touch Postgres — a DB outage should not
  get a perfectly good container restarted.
- **`/api/health/ready`** is readiness: it probes dependencies and answers
  `503` with a per-dependency report when they are down. This is what the
  deploy gate and anything routing traffic should check.

So during bring-up: `health` OK + `ready` 503 means the app is up but Postgres
isn't reachable yet (or migrations are still running).

## The map starts empty — that is not a bug

First boot migrates an **empty database**. There are no POIs, so the map
renders basemap only and `/api/pois` returns nothing. Pins appear only after
the two-step data refresh:

```sh
make data-fetch     # host-side fetchers → data/raw/<source>/<ts>.json
make data-import    # backend admin API imports data/raw/ → Postgres rows
```

The Tilt UI exposes the same two steps as manual buttons under the `data`
cluster — nothing in that cluster runs on `tilt up`. If `data/raw/` is already
populated from an earlier checkout, you can skip `data-fetch`; import always
reads the newest capture on disk. Import progress and failures are recorded in
`ingest_runs` (`GET /api/admin/data/status` for a per-target summary).

## Where the database lives, and resetting it

Local Postgres data is a bind mount at `$HOME/.roadtrip-map/postgres`
(override with `POSTGRES_DATA`; see the Makefile and `docker-compose.yml`).
It survives `tilt` restarts and `docker compose down`.

The scripts in `postgres-init/` (currently `10-grafana-reader.sh`, which
creates the read-only `grafana_reader` role) are standard
`/docker-entrypoint-initdb.d` scripts: **they run only when Postgres
initializes an empty data directory**. If you change them — or Grafana can't
log in because the role predates a password change — a restart is not enough;
you need a fresh data dir:

```sh
make reset-db
```

This removes the `postgres` and `backend` containers, deletes
`$HOME/.roadtrip-map/postgres`, and restarts the stack: Postgres re-initializes
from scratch (re-running `postgres-init/`), and Flyway re-migrates on backend
boot, including `R__grafana_reader_grants.sql` which re-grants
`grafana_reader`. Then re-run `make data-import` to repopulate the map.
