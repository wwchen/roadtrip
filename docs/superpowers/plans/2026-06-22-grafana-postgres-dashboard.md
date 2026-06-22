# Grafana Postgres Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Grafana to the local/deploy Docker stack using the official Grafana image, with provisioned Postgres datasource and object-centric dashboards for POIs, reservables, watches, ingest freshness, provider cache, and API-to-SQL equivalence.

**Architecture:** Compose remains the runtime service graph. The backend image is built from a new root `Dockerfile` and referenced as `roadtrip/backend`; Tilt owns local backend image rebuilds with `docker_build()` while Makefile/deploy build the same image explicitly before `docker compose up`. Grafana is not built: the official `grafana/grafana:13.0.0` image mounts datasource provisioning, dashboard provisioning, and dashboard JSON from the repo.

**Tech Stack:** Docker Compose, Tilt, official Grafana Docker image, Grafana file provisioning, Grafana PostgreSQL datasource, Postgres/PostGIS, Kotlin/Ktor backend fat jar.

**Reference spec:** `docs/superpowers/specs/2026-06-22-grafana-postgres-dashboard-design.md`

---

## File Map

**Create:**

- `Dockerfile` — root backend runtime image target named `backend`.
- `.dockerignore` — keep root Docker context small while preserving the backend fat jar.
- `grafana/db/create-grafana-reader.sh` — idempotent read-only database role setup.
- `grafana/provisioning/datasources/roadtrip-postgres.yml` — Grafana Postgres datasource provisioning.
- `grafana/provisioning/dashboards/roadtrip.yml` — Grafana file dashboard provider.
- `grafana/dashboards/poi-detail.json` — POI detail dashboard.
- `grafana/dashboards/reservable-detail.json` — reservable detail dashboard.
- `grafana/dashboards/watch-scheduler-health.json` — watch/job/run operational dashboard.
- `grafana/dashboards/ingest-catalog-freshness.json` — ingest/import/catalog freshness dashboard.
- `grafana/dashboards/provider-cache-audit.json` — provider cache and raw cache payload dashboard.
- `grafana/dashboards/api-sql-equivalence.json` — temporary dashboard mapping removable read APIs to SQL.

**Modify:**

- `docker-compose.yml` — backend uses `image: roadtrip/backend`; add `grafana-db-setup` and `grafana` official-image services.
- `docker-compose.local.yml` — expose local backend and Grafana ports.
- `Tiltfile` — replace Postgres/backend shell lifecycle with Compose resources; keep manual data resources.
- `Makefile` — add explicit backend image build path for deploy.
- `.github/workflows/deploy.yml` — mirror the Makefile deploy image build and trigger on Docker/Grafana changes.
- `README.md` — document Docker/Tilt/Grafana local dev shape.

**Delete:**

- `backend/Dockerfile` — replaced by root `Dockerfile`.

---

## Task 1: Move Backend Image To Root Dockerfile

**Files:**

- Create: `Dockerfile`
- Create: `.dockerignore`
- Delete: `backend/Dockerfile`

- [ ] **Step 1: Create the root backend Dockerfile**

Create `Dockerfile` at repo root with this exact content:

```dockerfile
# Backend Docker image. Runtime-only: build the fat jar on the host with
# `./gradlew :backend:shadowJar`, then build this image from the repo root.
FROM eclipse-temurin:21-jre AS backend

WORKDIR /app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 2: Create `.dockerignore`**

Create `.dockerignore` at repo root:

```dockerignore
.git
.gradle
.idea
.DS_Store

backend/.gradle
backend/build/*
!backend/build/libs/
!backend/build/libs/roadtrip-backend-*-all.jar

companion/node_modules
cookie-bot/node_modules

data/raw
data/pricing-cache

docs/superpowers/plans
docs/superpowers/specs
```

- [ ] **Step 3: Build the fat jar**

Run:

```bash
./gradlew :backend:shadowJar
```

Expected: `BUILD SUCCESSFUL` and at least one file matching `backend/build/libs/roadtrip-backend-*-all.jar`.

- [ ] **Step 4: Build the backend image from the root Dockerfile**

Run:

```bash
docker build -t roadtrip/backend --target backend .
```

Expected: Docker build succeeds and the final image is tagged `roadtrip/backend`.

- [ ] **Step 5: Delete the old backend Dockerfile**

Remove `backend/Dockerfile`. Do not leave two backend Dockerfiles in the repo.

- [ ] **Step 6: Commit**

Run:

```bash
git add Dockerfile .dockerignore backend/Dockerfile
git commit -m "build: move backend image to root dockerfile"
```

---

## Task 2: Update Compose, Deploy, And Local Docs

**Files:**

- Modify: `docker-compose.yml`
- Modify: `docker-compose.local.yml`
- Modify: `Makefile`
- Modify: `.github/workflows/deploy.yml`
- Modify: `README.md`

- [ ] **Step 1: Update backend Compose image ownership**

In `docker-compose.yml`, replace:

```yaml
  backend:
    profiles: [pois]
    build: ./backend
```

with:

```yaml
  backend:
    profiles: [pois]
    image: roadtrip/backend
```

Keep the existing `restart`, `hostname`, `environment`, `volumes`, and `depends_on` fields.

- [ ] **Step 2: Add Grafana DB role setup service**

In `docker-compose.yml`, add this service after `postgres` and before `backend`:

```yaml
  grafana-db-setup:
    profiles: [pois]
    image: postgis/postgis:16-3.4
    restart: "no"
    environment:
      - POSTGRES_DB=${POSTGRES_DB:-roadtrip}
      - POSTGRES_USER=${POSTGRES_USER:-roadtrip}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-roadtrip}
      - GRAFANA_DB_USER=${GRAFANA_DB_USER:-grafana_reader}
      - GRAFANA_DB_PASSWORD=${GRAFANA_DB_PASSWORD:-roadtrip}
    depends_on:
      postgres:
        condition: service_healthy
    command:
      - /bin/sh
      - -c
      - /docker-entrypoint-initdb.d/create-grafana-reader.sh
    volumes:
      - ./grafana/db/create-grafana-reader.sh:/docker-entrypoint-initdb.d/create-grafana-reader.sh:ro
```

- [ ] **Step 3: Add Grafana service**

In `docker-compose.yml`, add this service after `backend`:

```yaml
  grafana:
    profiles: [pois]
    image: grafana/grafana:13.0.0
    restart: unless-stopped
    hostname: grafana
    environment:
      - GF_SECURITY_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}
      - GF_USERS_ALLOW_SIGN_UP=false
      - POSTGRES_DB=${POSTGRES_DB:-roadtrip}
      - GRAFANA_DB_USER=${GRAFANA_DB_USER:-grafana_reader}
      - GRAFANA_DB_PASSWORD=${GRAFANA_DB_PASSWORD:-roadtrip}
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - ${GRAFANA_DATA:-$HOME/.roadtrip-map/grafana}:/var/lib/grafana
    depends_on:
      grafana-db-setup:
        condition: service_completed_successfully
```

- [ ] **Step 4: Default optional backend env vars**

In `docker-compose.yml`, change optional env lines so Compose config does not warn when local secrets are unset:

```yaml
      - MAPBOX_TOKEN=${MAPBOX_TOKEN:-}
```

If `COOKIE_BOT_TOKEN` or `CLOUDFLARE_TUNNEL_TOKEN` emit config warnings during verification, default them the same way:

```yaml
      - COOKIE_BOT_TOKEN=${COOKIE_BOT_TOKEN:-}
```

```yaml
    command: tunnel --no-autoupdate --config /etc/cloudflared/config.yml run --token ${CLOUDFLARE_TUNNEL_TOKEN:-}
```

- [ ] **Step 5: Expose local backend and Grafana ports**

Update `docker-compose.local.yml` comments so they no longer claim the backend only runs on the host. Add local ports:

```yaml
services:
  postgres:
    ports:
      - "127.0.0.1:5432:5432"

  backend:
    ports:
      - "127.0.0.1:8765:8765"

  grafana:
    ports:
      - "127.0.0.1:3000:3000"
```

- [ ] **Step 6: Update Makefile deploy build**

Add the image variable near the other variables:

```make
BACKEND_IMAGE ?= roadtrip/backend
```

Update the `deploy` target command from:

```make
ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'cd $(DEPLOY_DIR) && git pull --ff-only && ./gradlew :backend:shadowJar && docker compose --profile tunnel --profile pois up -d --build'
```

to:

```make
ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'cd $(DEPLOY_DIR) && git pull --ff-only && ./gradlew :backend:shadowJar && docker build -t $(BACKEND_IMAGE) --target backend . && docker compose --profile tunnel --profile pois up -d'
```

- [ ] **Step 7: Update GitHub deploy workflow**

In `.github/workflows/deploy.yml`, add these path triggers:

```yaml
      - 'Dockerfile'
      - '.dockerignore'
      - 'grafana/**'
```

Update the deploy SSH command from:

```bash
"cd $DEPLOY_DIR && git pull --ff-only && ./gradlew :backend:shadowJar && docker compose --profile tunnel --profile pois up -d --build"
```

to:

```bash
"cd $DEPLOY_DIR && git pull --ff-only && ./gradlew :backend:shadowJar && docker build -t roadtrip/backend --target backend . && docker compose --profile tunnel --profile pois up -d"
```

- [ ] **Step 8: Update README local-dev wording**

In `README.md`, replace the paragraph that says Tilt runs only Postgres in Docker and backend on the host with:

```markdown
`tilt up` is the easiest path for full-stack dev: Tilt uses Docker Compose
for Postgres, the backend container, and Grafana, then runs the campsite
companion as a host Node process so Playwright can drive a real Chromium.
The backend still serves the app on <http://127.0.0.1:8765>. Grafana is
available at <http://127.0.0.1:3000> with local defaults `admin` / `admin`.
Tilt UI is at <http://localhost:10350>.

`make run` remains the fastest backend-only loop: it starts Postgres in
Docker and runs the Kotlin/Ktor backend on the host with Gradle.
```

- [ ] **Step 9: Verify Compose config**

Run:

```bash
docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois config >/tmp/roadtrip-compose.yml
```

Expected: command exits 0. Inspect `/tmp/roadtrip-compose.yml` and confirm:

- `backend.image` is `roadtrip/backend`
- `backend` has no `build:` key
- `grafana.image` is `grafana/grafana:13.0.0`
- local ports include `127.0.0.1:8765:8765` and `127.0.0.1:3000:3000`

- [ ] **Step 10: Commit**

Run:

```bash
git add docker-compose.yml docker-compose.local.yml Makefile .github/workflows/deploy.yml README.md
git commit -m "build: wire compose to backend image and grafana"
```

---

## Task 3: Add Grafana Provisioning And Read-Only DB Role

**Files:**

- Create: `grafana/db/create-grafana-reader.sh`
- Create: `grafana/provisioning/datasources/roadtrip-postgres.yml`
- Create: `grafana/provisioning/dashboards/roadtrip.yml`

- [ ] **Step 1: Create DB role setup script**

Create `grafana/db/create-grafana-reader.sh`:

```sh
#!/usr/bin/env sh
set -eu

: "${POSTGRES_DB:=roadtrip}"
: "${POSTGRES_USER:=roadtrip}"
: "${POSTGRES_PASSWORD:=roadtrip}"
: "${GRAFANA_DB_USER:=grafana_reader}"
: "${GRAFANA_DB_PASSWORD:=roadtrip}"

export PGPASSWORD="$POSTGRES_PASSWORD"

psql \
  -h postgres \
  -U "$POSTGRES_USER" \
  -d "$POSTGRES_DB" \
  -v ON_ERROR_STOP=1 \
  -v grafana_user="$GRAFANA_DB_USER" \
  -v grafana_password="$GRAFANA_DB_PASSWORD" \
  -v postgres_db="$POSTGRES_DB" <<'SQL'
SELECT set_config('roadtrip.grafana_user', :'grafana_user', false);
SELECT set_config('roadtrip.grafana_password', :'grafana_password', false);

DO $$
DECLARE
  grafana_user text := current_setting('roadtrip.grafana_user');
  grafana_password text := current_setting('roadtrip.grafana_password');
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = grafana_user) THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', grafana_user, grafana_password);
  ELSE
    EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', grafana_user, grafana_password);
  END IF;
END
$$;

GRANT CONNECT ON DATABASE :"postgres_db" TO :"grafana_user";
GRANT USAGE ON SCHEMA public TO :"grafana_user";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"grafana_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"grafana_user";
SQL
```

- [ ] **Step 2: Make the script executable**

Run:

```bash
chmod +x grafana/db/create-grafana-reader.sh
```

- [ ] **Step 3: Create datasource provisioning**

Create `grafana/provisioning/datasources/roadtrip-postgres.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Roadtrip Postgres
    uid: roadtrip-postgres
    type: postgres
    access: proxy
    url: postgres:5432
    user: ${GRAFANA_DB_USER}
    secureJsonData:
      password: ${GRAFANA_DB_PASSWORD}
    jsonData:
      database: ${POSTGRES_DB}
      sslmode: disable
      postgresVersion: 1600
      timescaledb: false
      maxOpenConns: 10
      maxIdleConns: 2
      connMaxLifetime: 14400
```

- [ ] **Step 4: Create dashboard provider provisioning**

Create `grafana/provisioning/dashboards/roadtrip.yml`:

```yaml
apiVersion: 1

providers:
  - name: Roadtrip
    orgId: 1
    folder: Roadtrip
    folderUid: roadtrip
    type: file
    disableDeletion: false
    editable: true
    updateIntervalSeconds: 10
    allowUiUpdates: false
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 5: Validate shell and YAML through Compose config**

Run:

```bash
sh -n grafana/db/create-grafana-reader.sh
docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois config >/tmp/roadtrip-compose.yml
```

Expected: both commands exit 0.

- [ ] **Step 6: Commit**

Run:

```bash
git add grafana/db/create-grafana-reader.sh grafana/provisioning
git commit -m "feat: provision grafana postgres datasource"
```

---

## Task 4: Replace Tilt Shell Stack With Compose Integration

**Files:**

- Modify: `Tiltfile`

- [ ] **Step 1: Preserve dotenv helper and port constant**

Keep the existing `PORT = '8765'`, `_load_dotenv(path)`, and `DOTENV = _load_dotenv('.env')` definitions. They are still useful for local manual data resources and future host-side commands.

- [ ] **Step 2: Add backend jar build resource**

Add this resource before `docker_compose(...)`:

```python
local_resource(
    'backend-jar',
    cmd='./gradlew --console=plain :backend:shadowJar',
    deps=[
        'backend/src/main',
        'backend/build.gradle.kts',
        'settings.gradle.kts',
        'gradle.properties',
        'gradle/wrapper/gradle-wrapper.properties',
    ],
    ignore=[
        '.gradle',
        'backend/.gradle',
        'backend/build/classes',
        'backend/build/generated',
        'backend/build/reports',
        'backend/build/test-results',
        'backend/build/tmp',
    ],
    labels=['build'],
)
```

- [ ] **Step 3: Add Compose and backend image build declarations**

Add this after `backend-jar`:

```python
docker_compose(
    ['docker-compose.yml', 'docker-compose.local.yml'],
    profiles=['pois'],
)

docker_build(
    'roadtrip/backend',
    '.',
    dockerfile='Dockerfile',
    target='backend',
    only=[
        'Dockerfile',
        'backend/build/libs',
    ],
)
```

This keeps source changes flowing through `backend-jar`, then the changed fat jar triggers the Docker image rebuild.

- [ ] **Step 4: Configure Compose resources**

Replace the old `local_resource('postgres', ...)` and `local_resource('backend', ...)` blocks with:

```python
dc_resource('postgres', labels=['infra'])
dc_resource(
    'grafana-db-setup',
    resource_deps=['postgres'],
    labels=['infra'],
)
dc_resource(
    'backend',
    resource_deps=['postgres', 'backend-jar'],
    labels=['app'],
    links=['http://127.0.0.1:' + PORT],
)
dc_resource(
    'grafana',
    resource_deps=['postgres', 'grafana-db-setup'],
    labels=['infra'],
    links=['http://127.0.0.1:3000'],
)
```

- [ ] **Step 5: Keep host companion and manual data resources**

Keep these existing resources, with their dependencies unchanged except that `backend` now means the Compose backend resource:

- `companion`
- `open-app`
- `data-fetch`
- `data-import`

- [ ] **Step 6: Validate Tiltfile syntax**

Run:

```bash
tilt doctor
```

Expected: Tilt is installed and reports environment diagnostics without a Tiltfile syntax error.

Then run a non-destructive resource listing:

```bash
tilt get uiresources
```

Expected when Tilt is not running: either an empty result or a clear "Tilt is not running" style message. A Starlark parse failure means the `Tiltfile` must be fixed before committing.

- [ ] **Step 7: Commit**

Run:

```bash
git add Tiltfile
git commit -m "dev: run docker compose stack from tilt"
```

---

## Task 5: Add POI And Reservable Dashboards

**Files:**

- Create: `grafana/dashboards/poi-detail.json`
- Create: `grafana/dashboards/reservable-detail.json`

- [ ] **Step 1: Create `poi-detail.json`**

Create a Grafana dashboard JSON file with these properties:

```json
{
  "uid": "roadtrip-poi-detail",
  "title": "Roadtrip / POI Detail",
  "tags": ["roadtrip", "poi"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "",
  "templating": {
    "list": [
      {
        "name": "poi_id",
        "type": "query",
        "datasource": { "type": "postgres", "uid": "roadtrip-postgres" },
        "query": "SELECT id::text || ' - ' || name AS __text, id AS __value FROM pois WHERE deleted_at IS NULL ORDER BY name ASC LIMIT 500",
        "refresh": 1
      },
      {
        "name": "rid_type",
        "type": "custom",
        "query": ",site",
        "current": { "text": "site", "value": "site" }
      },
      {
        "name": "include_deleted_reservables",
        "type": "custom",
        "query": "false,true",
        "current": { "text": "false", "value": "false" }
      }
    ]
  }
}
```

Add table panels using the exact SQL from the spec sections:

- `POI Header`
- `All Linked RIDs`
- `POI Availability Coverage`
- `POI Raw Data`

Each panel target must use:

```json
{
  "datasource": { "type": "postgres", "uid": "roadtrip-postgres" },
  "format": "table",
  "rawQuery": true,
  "rawSql": "SELECT 1 AS ok"
}
```

Replace the example `rawSql` value with the full SQL from the matching spec section before saving the dashboard file.

- [ ] **Step 2: Create `reservable-detail.json`**

Create a Grafana dashboard JSON file with these properties:

```json
{
  "uid": "roadtrip-reservable-detail",
  "title": "Roadtrip / Reservable Detail",
  "tags": ["roadtrip", "reservable"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "",
  "templating": {
    "list": [
      {
        "name": "reservable_rid",
        "type": "query",
        "datasource": { "type": "postgres", "uid": "roadtrip-postgres" },
        "query": "SELECT type || ':' || vendor || ':' || vendor_id AS __text, type || ':' || vendor || ':' || vendor_id AS __value FROM reservables WHERE deleted_at IS NULL ORDER BY type ASC, vendor ASC, vendor_id ASC LIMIT 1000",
        "refresh": 1
      },
      {
        "name": "days_back",
        "type": "custom",
        "query": "7,14,30,90",
        "current": { "text": "14", "value": "14" }
      },
      {
        "name": "target_date",
        "type": "textbox",
        "query": "",
        "current": { "text": "", "value": "" }
      }
    ]
  }
}
```

Add panels using the exact SQL from the spec sections:

- `Reservable Header`
- `Data Freshness`
- cache freshness table under `Data Freshness`
- `Availability Fetch Audit`
- status timeline panel under `Availability Fetch Audit`
- `Raw Reservable Data`
- `Linked POIs`

For the status timeline panel, set `"format": "time_series"`; all other panels use `"format": "table"`.

- [ ] **Step 3: Validate dashboard JSON**

Run:

```bash
jq empty grafana/dashboards/poi-detail.json grafana/dashboards/reservable-detail.json
jq -r '.uid + " :: " + .title' grafana/dashboards/poi-detail.json grafana/dashboards/reservable-detail.json
```

Expected output includes:

```text
roadtrip-poi-detail :: Roadtrip / POI Detail
roadtrip-reservable-detail :: Roadtrip / Reservable Detail
```

- [ ] **Step 4: Commit**

Run:

```bash
git add grafana/dashboards/poi-detail.json grafana/dashboards/reservable-detail.json
git commit -m "feat: add poi and reservable grafana dashboards"
```

---

## Task 6: Add Operational And API Equivalence Dashboards

**Files:**

- Create: `grafana/dashboards/watch-scheduler-health.json`
- Create: `grafana/dashboards/ingest-catalog-freshness.json`
- Create: `grafana/dashboards/provider-cache-audit.json`
- Create: `grafana/dashboards/api-sql-equivalence.json`

- [ ] **Step 1: Create Watch & Scheduler Health dashboard**

Create `grafana/dashboards/watch-scheduler-health.json` with:

- `uid`: `roadtrip-watch-scheduler-health`
- `title`: `Roadtrip / Watch & Scheduler Health`
- `tags`: `["roadtrip", "availability", "scheduler"]`
- variables: `watch_status`, `job_status`, `run_status`, `poi_id`, `reservable_rid`, `days_back`
- panels using exact SQL from the spec sections:
  - `Queue Summary`
  - `Watch And Job Table`
  - `Recent Runs`
  - `Snapshots Produced By Runs`

Use `format: "table"` for each panel.

- [ ] **Step 2: Create Ingest & Catalog Freshness dashboard**

Create `grafana/dashboards/ingest-catalog-freshness.json` with:

- `uid`: `roadtrip-ingest-catalog-freshness`
- `title`: `Roadtrip / Ingest & Catalog Freshness`
- `tags`: `["roadtrip", "ingest", "freshness"]`
- variables: `target`, `source`, `days_back`
- panels using exact SQL from the spec sections:
  - `Latest Ingest By Target`
  - `Failed Or Stuck Ingest Phases`
  - `Import Runs`
  - `Catalog Rows By Freshness`

Use `format: "table"` for each panel.

- [ ] **Step 3: Create Provider Cache / Raw Data Audit dashboard**

Create `grafana/dashboards/provider-cache-audit.json` with:

- `uid`: `roadtrip-provider-cache-audit`
- `title`: `Roadtrip / Provider Cache Audit`
- `tags`: `["roadtrip", "cache", "provider"]`
- variables: `namespace`, `cache_key_search`, `include_expired`, `reservable_rid`
- panels using exact SQL from the spec sections:
  - `Cache Namespace Summary`
  - `Cache Rows`
  - `Cache Rows Related To A Reservable`

Use `format: "table"` for each panel.

- [ ] **Step 4: Create API / SQL Equivalence dashboard**

Create `grafana/dashboards/api-sql-equivalence.json` with:

- `uid`: `roadtrip-api-sql-equivalence`
- `title`: `Roadtrip / API SQL Equivalence`
- `tags`: `["roadtrip", "api", "sql-equivalence"]`
- variables: `status`, `watch_id`, `job_id`, `run_id`, `run_status`, `since`, `reservable_rid`, `window_hours`, `vendor`, `site_type`, `name`, `poi_query`
- panels using exact SQL from the spec sections:
  - `/api/availability/jobs`
  - `/api/availability/jobs/summary`
  - `/api/availability/jobs/{id}/runs`
  - `/api/availability/runs`
  - `/api/availability/snapshots` by reservable
  - `/api/availability/snapshots` by run
  - `/api/availability/snapshots/summary`
  - `/api/reservables`
  - `/api/reservable/{rid}`
  - `/api/pois/search`

Use `format: "table"` for each panel.

- [ ] **Step 5: Validate all dashboard JSON**

Run:

```bash
jq empty grafana/dashboards/*.json
jq -r '.uid + " :: " + .title' grafana/dashboards/*.json
```

Expected output includes all six dashboard UIDs:

```text
roadtrip-api-sql-equivalence :: Roadtrip / API SQL Equivalence
roadtrip-ingest-catalog-freshness :: Roadtrip / Ingest & Catalog Freshness
roadtrip-poi-detail :: Roadtrip / POI Detail
roadtrip-provider-cache-audit :: Roadtrip / Provider Cache Audit
roadtrip-reservable-detail :: Roadtrip / Reservable Detail
roadtrip-watch-scheduler-health :: Roadtrip / Watch & Scheduler Health
```

- [ ] **Step 6: Commit**

Run:

```bash
git add grafana/dashboards
git commit -m "feat: add operational grafana dashboards"
```

---

## Task 7: End-To-End Stack Verification

**Files:**

- No planned source edits. If verification exposes a bug, fix the smallest owning file and commit with a focused message.

- [ ] **Step 1: Verify backend image build**

Run:

```bash
./gradlew :backend:shadowJar
docker build -t roadtrip/backend --target backend .
```

Expected: both commands succeed.

- [ ] **Step 2: Verify Compose config**

Run:

```bash
docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois config >/tmp/roadtrip-compose.yml
```

Expected: command exits 0.

- [ ] **Step 3: Start local Compose stack**

Run:

```bash
docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d postgres grafana backend
```

Expected:

- `postgres` becomes healthy.
- `grafana-db-setup` completes successfully.
- `backend` starts and serves `http://127.0.0.1:8765/api/health`.
- `grafana` serves `http://127.0.0.1:3000/api/health`.

- [ ] **Step 4: Probe backend and Grafana**

Run:

```bash
curl -sS http://127.0.0.1:8765/api/health
curl -sS http://127.0.0.1:3000/api/health
curl -sS -u admin:admin http://127.0.0.1:3000/api/datasources
curl -sS -u admin:admin http://127.0.0.1:3000/api/search?folderIds=0
```

Expected:

- Backend health returns JSON with healthy status.
- Grafana health returns JSON with database status.
- Datasources response includes `"uid":"roadtrip-postgres"`.
- Search response includes the six provisioned dashboards.

- [ ] **Step 5: Verify Grafana can query Postgres**

Run:

```bash
curl -sS -u admin:admin \
  -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:3000/api/ds/query \
  --data '{"queries":[{"refId":"A","datasource":{"uid":"roadtrip-postgres"},"rawSql":"SELECT 1 AS ok","format":"table"}],"from":"now-5m","to":"now"}'
```

Expected: response includes `ok` and value `1`.

- [ ] **Step 6: Verify Tilt startup path**

Run:

```bash
tilt up
```

Expected in the Tilt UI:

- `backend-jar` builds successfully.
- `postgres` is healthy.
- `backend` starts after `postgres` and `backend-jar`.
- `grafana-db-setup` completes after `postgres`.
- `grafana` starts after `grafana-db-setup`.
- Links appear for `http://127.0.0.1:8765` and `http://127.0.0.1:3000`.

Stop Tilt cleanly after verification.

- [ ] **Step 7: Run backend regression tests**

Run:

```bash
./gradlew :backend:test -x :backend:generateJooq
```

Expected: tests pass. If this fails because generated jOOQ classes are stale, run the full command instead:

```bash
./gradlew :backend:test
```

- [ ] **Step 8: Final status check**

Run:

```bash
git status --short
```

Expected: clean worktree after all verification fixes are committed.
