# Grafana Postgres Dashboard Design

## Problem

The project has three standalone catalog/dashboard UIs:

- `/pois`
- `/reservables`
- `/availability`

Those pages mostly expose read-only database views that could be better served by Grafana querying Postgres directly. Before removing any UI or API surface, we want to add Grafana to the stack and make the SQL equivalents explicit.

## Goals

- Add Grafana to the Docker Compose stack using the official Grafana image.
- Keep Grafana dashboards and datasource provisioning in the repo.
- Move backend image build configuration to a root `Dockerfile`.
- Integrate Docker Compose with Tilt using `docker_compose`, `docker_build`, and `dc_resource`.
- Keep all existing app APIs and static pages during this first pass.
- Create object-centric dashboards for POI, reservable, watch/scheduler, ingest freshness, and provider cache investigation.
- Create Grafana dashboards that show which read-only APIs can later be replaced by direct SQL panels.

## Non-Goals

- Do not remove `/pois`, `/reservables`, or `/availability` in this pass.
- Do not remove any `/api/*` route in this pass.
- Do not build a custom Grafana image.
- Do not put multiple services into one container.
- Do not replace app flows that perform route search, map rendering, upstream availability fetches, or watch mutations.

## Existing Context

The backend is a Kotlin/Ktor app. It currently serves static pages from `Main.kt` and exposes typed API routes under `backend/src/main/kotlin/ca/floo/roadtrip/routes/`.

Current Docker shape:

- `docker-compose.yml` defines Postgres, backend, cookie-bot, and cloudflared.
- `backend/Dockerfile` builds only the backend runtime image from a prebuilt shadow jar.
- `docker-compose.local.yml` exposes Postgres locally.
- `Tiltfile` currently shells out through `local_resource` for Postgres and runs the backend on the host JVM.

The future shape should make Compose the source of truth for runtime services, with Tilt managing Compose resources and backend image rebuilds.

## Architecture

Use one root `Dockerfile` for images this repo actually builds. For now, that means only the backend image.

Use official vendor images directly for infrastructure:

- `postgis/postgis:16-3.4` for Postgres/PostGIS.
- `grafana/grafana:13.0.0` for Grafana.
- `cloudflare/cloudflared:latest` for the tunnel.

Runtime layout:

```text
docker-compose.yml
  postgres   -> official PostGIS image
  backend    -> roadtrip/backend image built from root Dockerfile target backend
  grafana    -> official Grafana image plus mounted repo config
  cloudflared-> official cloudflared image

Tiltfile
  local_resource('backend-jar') builds :backend:shadowJar
  docker_compose(['docker-compose.yml', 'docker-compose.local.yml'], profiles=['pois'])
  docker_build('roadtrip/backend', '.', dockerfile='Dockerfile', target='backend', only=['Dockerfile', 'backend/build/libs'])
  dc_resource('postgres')
  dc_resource('grafana-db-setup', resource_deps=['postgres'])
  dc_resource('backend', resource_deps=['postgres', 'backend-jar'])
  dc_resource('grafana', resource_deps=['postgres', 'grafana-db-setup'])
```

This gives us one repo-level Dockerfile without collapsing services into one runnable image.

## Dockerfile Design

Create `Dockerfile` at the repo root:

```dockerfile
FROM eclipse-temurin:21-jre AS backend

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-yaml curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

Retire `backend/Dockerfile` after Compose is updated to build the `backend` target from the root Dockerfile.

## Compose Design

Update the backend service to reference the image but not build it directly:

```yaml
backend:
  image: roadtrip/backend
  environment:
    - MAPBOX_TOKEN=${MAPBOX_TOKEN:-}
    - RIDB_API_KEY=${RIDB_API_KEY:-}
    - COOKIE_BOT_URL=${COOKIE_BOT_URL:-}
    - COOKIE_BOT_TOKEN=${COOKIE_BOT_TOKEN:-}
    - TESLA_COOKIES=${TESLA_COOKIES:-}
  volumes:
    - ./scripts:/app/static/scripts:ro
    - ./data:/app/static/data
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:$${PORT:-8765}/api/health >/dev/null"]
    interval: 5s
    timeout: 3s
    retries: 20
    start_period: 10s
```

This avoids split ownership in Tilt. Tilt's Docker Compose integration expects an image used by a Compose service to be built either by the Compose `build:` key or by Tilt's `docker_build()`, not both. Normal `make deploy` and GitHub deploy should build `roadtrip/backend` explicitly before `docker compose up`.

Add Grafana:

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

Update `docker-compose.local.yml`:

```yaml
grafana:
  ports:
    - "127.0.0.1:3000:3000"
```

For deploy, either expose Grafana through Cloudflare later or keep it private. This first pass should only guarantee local access through `127.0.0.1:3000`.

## Tilt Design

Replace shell-based Compose lifecycle management with Tilt's Compose integration:

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
    links=['http://127.0.0.1:8765'],
)
dc_resource(
    'grafana',
    resource_deps=['postgres', 'grafana-db-setup'],
    labels=['infra'],
    links=['http://127.0.0.1:3000'],
)
```

Tilt behavior:

- Backend source changes trigger `backend-jar`, which rebuilds the fat jar.
- Fat jar changes under `backend/build/libs` trigger `docker_build`.
- The Compose backend service restarts when the `roadtrip/backend` image changes.
- Grafana dashboard files are bind-mounted, so dashboard JSON changes do not need an image rebuild.
- Grafana datasource provisioning changes may need a Grafana container restart; Tilt can restart the `grafana` Compose service when that resource updates.

## Grafana Provisioning

Add these repo directories:

```text
grafana/
  provisioning/
    datasources/
      roadtrip-postgres.yml
    dashboards/
      roadtrip.yml
  dashboards/
    reservable-detail.json
    poi-detail.json
    watch-scheduler-health.json
    ingest-catalog-freshness.json
    provider-cache-audit.json
    api-sql-equivalence.json
```

Datasource provisioning:

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
      password: $GRAFANA_DB_PASSWORD
    jsonData:
      database: ${POSTGRES_DB}
      sslmode: disable
      postgresVersion: 1600
      timescaledb: false
      maxOpenConns: 10
      maxIdleConns: 2
      connMaxLifetime: 14400
```

Use a read-only database role for Grafana. Grafana's PostgreSQL datasource allows arbitrary SQL, so it should not use the app owner credentials.

Create or update the role through an idempotent Compose setup container, not Flyway. Roles and passwords are operational state, and Compose can pass `GRAFANA_DB_USER` / `GRAFANA_DB_PASSWORD` without baking secrets into a migration.

`grafana/db/create-grafana-reader.sh` should run `psql` as the app database owner and execute:

```sql
SELECT set_config('roadtrip.grafana_user', :'grafana_user', false);
SELECT set_config('roadtrip.grafana_password', :'grafana_password', false);

DO $$
DECLARE
  grafana_user text := current_setting('roadtrip.grafana_user');
  grafana_password text := current_setting('roadtrip.grafana_password');
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = grafana_user) THEN
    EXECUTE format(
      'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
      grafana_user,
      grafana_password
    );
  ELSE
    EXECUTE format(
      'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
      grafana_user,
      grafana_password
    );
  END IF;
END
$$;

GRANT CONNECT ON DATABASE :"postgres_db" TO :"grafana_user";
GRANT USAGE ON SCHEMA public TO :"grafana_user";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"grafana_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"grafana_user";
```

The script should call `psql` with `-v grafana_user="$GRAFANA_DB_USER"`, `-v grafana_password="$GRAFANA_DB_PASSWORD"`, and `-v postgres_db="$POSTGRES_DB"` so local defaults and production overrides use the same path.

## Dashboard Information Architecture

The primary Grafana experience should be object-centric, not API-centric:

- **Reservable Detail** answers "what do we know about this reservable, how fresh is it, what availability fetches touched it, and what raw upstream payload created it?"
- **POI Detail** answers "what is this place, and which reservable RIDs are linked to it?"
- **Watch & Scheduler Health** answers "what monitoring intent exists, what is due or stuck, and which runs are failing?"
- **Ingest & Catalog Freshness** answers "when did each source last fetch/import successfully, and which catalog rows are stale?"
- **Provider Cache / Raw Data Audit** answers "is a stale result caused by database state, provider cache state, or raw upstream payload shape?"
- **API / SQL Equivalence** remains a supporting dashboard for deciding which old read APIs can be removed later.

The first two dashboards are the primary replacement candidates for the standalone `/pois` and `/reservables` views. The next three dashboards expose active database tables that would otherwise remain hidden from Grafana while still explaining freshness and availability behavior. The API equivalence dashboard is temporary scaffolding: it should help decide what can be deleted later, not become the main operator workflow.

## Table Coverage

Active tables should be visible through Grafana as follows:

- `pois`: POI Detail, Reservable Detail linked POIs, Ingest & Catalog Freshness.
- `reservables`: Reservable Detail, POI Detail linked RIDs, Ingest & Catalog Freshness.
- `reservable_pois`: POI/reservable relationship panels in POI Detail and Reservable Detail.
- `availability_snapshot`: availability timelines, fetch audit, POI coverage, scheduler run output.
- `availability_watch`: Watch & Scheduler Health.
- `availability_job`: Watch & Scheduler Health and API / SQL Equivalence.
- `availability_job_run`: Watch & Scheduler Health, Reservable Detail fetch audit, API / SQL Equivalence.
- `ingest_runs`: Ingest & Catalog Freshness.
- `import_runs`: Ingest & Catalog Freshness, with joins from `pois.last_seen_run_id` and `reservables.last_seen_run_id`.
- `api_cache`: Provider Cache / Raw Data Audit and Reservable Detail freshness heuristics.

Dropped or obsolete tables should not get dashboards: `alerts`, `matches`, `settings`, `schedules`, `governing_body`, `booking_provider`, `reservable_availability_monitors`, and the pre-rename `reservable_availability_log`.

## Reservable Detail Dashboard

Dashboard file:

```text
grafana/dashboards/reservable-detail.json
```

Core variables:

- `reservable_rid`: query-backed selector of `type || ':' || vendor || ':' || vendor_id`.
- `days_back`: numeric interval for freshness/audit panels, default `14`.
- `target_date`: optional date selector for narrowing availability observations.

### Reservable Header

Shows the normalized catalog identity and data timestamps.

```sql
SELECT
  r.id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS rid,
  r.source,
  r.name,
  r.loop,
  r.site_type,
  r.created_at,
  r.updated_at,
  r.last_seen_run_id,
  r.deleted_at,
  now() - r.updated_at AS catalog_age
FROM reservables r
WHERE r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring};
```

### Data Freshness

Shows catalog freshness, latest availability observation, and cache freshness as separate signals.

```sql
WITH selected AS (
  SELECT id
  FROM reservables
  WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring}
),
latest_snapshot AS (
  SELECT max(observed_at) AS latest_observed_at
  FROM availability_snapshot
  WHERE reservable_id = (SELECT id FROM selected)
)
SELECT
  r.updated_at AS catalog_updated_at,
  now() - r.updated_at AS catalog_age,
  latest_snapshot.latest_observed_at,
  now() - latest_snapshot.latest_observed_at AS availability_observation_age,
  r.last_seen_run_id
FROM reservables r
CROSS JOIN latest_snapshot
WHERE r.id = (SELECT id FROM selected);
```

Cache freshness should be a table because provider cache keys differ by upstream:

- rec.gov availability cache namespace: `recgov_availability`, key format `provider:campgroundId:month`.
- Aspira availability cache namespace: `aspira_availability`, key format `host:mapId:startDate:endDate`.
- Aspira occupancy cache namespace: `aspira_occupancy`, key format `host:resourceLocationId:startDate:endDate`.

```sql
WITH selected AS (
  SELECT
    split_part(type || ':' || vendor || ':' || vendor_id, ':', 3) AS vendor_id,
    provider_ref->>'mapId' AS map_id,
    provider_ref->>'resourceLocationId' AS resource_location_id
  FROM reservables
  WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring}
),
needles AS (
  SELECT value
  FROM selected
  CROSS JOIN LATERAL (
    VALUES (vendor_id), (map_id), (resource_location_id)
  ) AS raw(value)
  WHERE value IS NOT NULL AND value <> ''
)
SELECT
  namespace,
  cache_key,
  created_at,
  expires_at,
  now() - created_at AS age,
  expires_at - now() AS freshness_remaining,
  expires_at <= now() AS expired
FROM api_cache
WHERE namespace IN ('recgov_availability', 'aspira_availability', 'aspira_occupancy')
  AND EXISTS (
    SELECT 1
    FROM needles
    WHERE api_cache.cache_key ILIKE '%' || needles.value || '%'
  )
ORDER BY created_at DESC;
```

This cache panel is intentionally heuristic. The canonical availability audit is `availability_snapshot`; cache keys explain provider fetch freshness, but they are not normalized foreign keys.

### Availability Fetch Audit

Shows scheduled fetch runs that produced observations for the selected reservable. Runs with `run_id IS NULL` are ad-hoc request-time observations rather than scheduler runs.

```sql
SELECT
  s.observed_at,
  s.target_date,
  s.status AS observed_status,
  s.available,
  s.run_id,
  jr.job_id,
  jr.status AS run_status,
  jr.snapshot_count,
  jr.duration_ms,
  jr.error,
  jr.started_at,
  jr.completed_at
FROM availability_snapshot s
LEFT JOIN availability_job_run jr ON jr.id = s.run_id
WHERE s.reservable_id = (
  SELECT id
  FROM reservables
  WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring}
)
  AND s.observed_at >= now() - (${days_back} || ' days')::interval
  AND (${target_date:sqlstring} = '' OR s.target_date = NULLIF(${target_date:sqlstring}, '')::date)
ORDER BY s.observed_at DESC, s.target_date ASC, s.id DESC;
```

Add a compact status timeline panel for the same data:

```sql
SELECT
  s.observed_at AS time,
  s.target_date::text AS metric,
  CASE WHEN s.available THEN 1 ELSE 0 END AS value
FROM availability_snapshot s
WHERE s.reservable_id = (
  SELECT id
  FROM reservables
  WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring}
)
  AND s.observed_at >= now() - (${days_back} || ' days')::interval
ORDER BY s.observed_at ASC;
```

### Raw Reservable Data

Shows provider reference, normalized tags, and raw upstream catalog payload. This is the trust/debug panel for ETL output.

```sql
SELECT
  provider_ref,
  tags,
  raw
FROM reservables
WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring};
```

### Linked POIs

Shows every active POI linked to this reservable.

```sql
SELECT
  p.id AS poi_id,
  p.name,
  p.category,
  p.subcategory,
  p.source,
  p.source_id,
  p.region,
  ST_X(ST_Centroid(p.geom)) AS lng,
  ST_Y(ST_Centroid(p.geom)) AS lat
FROM reservable_pois rp
JOIN reservables r ON r.id = rp.reservable_id
JOIN pois p ON p.id = rp.poi_id
WHERE r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring}
  AND p.deleted_at IS NULL
ORDER BY p.name ASC, p.id ASC;
```

## POI Detail Dashboard

Dashboard file:

```text
grafana/dashboards/poi-detail.json
```

Core variables:

- `poi_id`: query-backed selector for active POIs.
- `rid_type`: optional filter, default `site`.
- `include_deleted_reservables`: boolean-like selector default `false`.

### POI Header

Shows the active POI row and upstream provider metadata.

```sql
SELECT
  id,
  source,
  source_id,
  category,
  subcategory,
  name,
  region,
  unit_name,
  reserve_url,
  phone,
  info_url,
  fetched_at,
  updated_at,
  provider_ref,
  properties AS raw_properties,
  ST_AsGeoJSON(geom)::jsonb AS geometry
FROM pois
WHERE id = ${poi_id};
```

### All Linked RIDs

This is the load-bearing POI-centric panel: every reservable RID attached to the place.

```sql
SELECT
  r.id AS reservable_id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS rid,
  r.type,
  r.vendor,
  r.vendor_id,
  r.name,
  r.loop,
  r.site_type,
  r.source,
  r.updated_at,
  r.deleted_at,
  max(s.observed_at) AS latest_availability_observed_at,
  count(s.id) FILTER (WHERE s.observed_at >= now() - interval '7 days') AS snapshots_last_7d,
  bool_or(s.available) FILTER (WHERE s.observed_at >= now() - interval '7 days') AS any_available_last_7d
FROM reservable_pois rp
JOIN reservables r ON r.id = rp.reservable_id
LEFT JOIN availability_snapshot s ON s.reservable_id = r.id
WHERE rp.poi_id = ${poi_id}
  AND (${rid_type:sqlstring} = '' OR r.type = ${rid_type:sqlstring})
  AND (
    ${include_deleted_reservables:sqlstring} = 'true'
    OR r.deleted_at IS NULL
  )
GROUP BY r.id
ORDER BY r.loop NULLS LAST, r.name NULLS LAST, rid ASC;
```

### POI Availability Coverage

Shows whether the POI has linked reservables, how many have recent observations, and how stale the newest observation is.

```sql
WITH linked AS (
  SELECT r.id
  FROM reservable_pois rp
  JOIN reservables r ON r.id = rp.reservable_id
  WHERE rp.poi_id = ${poi_id}
    AND r.deleted_at IS NULL
),
latest AS (
  SELECT
    reservable_id,
    max(observed_at) AS latest_observed_at
  FROM availability_snapshot
  WHERE reservable_id IN (SELECT id FROM linked)
  GROUP BY reservable_id
)
SELECT
  count(*) AS linked_reservables,
  count(latest.reservable_id) AS reservables_with_observations,
  max(latest.latest_observed_at) AS newest_observation,
  now() - max(latest.latest_observed_at) AS newest_observation_age
FROM linked
LEFT JOIN latest ON latest.reservable_id = linked.id;
```

### POI Raw Data

Shows POI-level raw properties and provider reference.

```sql
SELECT
  provider_ref,
  properties
FROM pois
WHERE id = ${poi_id};
```

## Watch & Scheduler Health Dashboard

Dashboard file:

```text
grafana/dashboards/watch-scheduler-health.json
```

Core variables:

- `watch_status`: optional filter for `active`, `paused`, or `done`.
- `job_status`: optional filter for `active`, `paused`, or `done`.
- `run_status`: optional filter for `started`, `completed`, or `failed`.
- `poi_id`: optional POI drill-down.
- `reservable_rid`: optional reservable drill-down.
- `days_back`: numeric interval for run history, default `14`.

This dashboard makes `availability_watch`, `availability_job`, and `availability_job_run` first-class. It should link to the POI and reservable detail dashboards when the selected watch can be resolved to a POI or RID.

### Queue Summary

Shows scheduler pressure and obviously stuck work.

```sql
SELECT
  count(*) FILTER (WHERE j.status = 'active') AS active_jobs,
  count(*) FILTER (WHERE j.status = 'paused') AS paused_jobs,
  count(*) FILTER (WHERE j.status = 'done') AS done_jobs,
  count(*) FILTER (
    WHERE j.status = 'active'
      AND j.next_run_at <= now()
      AND (j.claimed_until IS NULL OR j.claimed_until < now())
  ) AS due_now,
  count(*) FILTER (
    WHERE j.claimed_until IS NOT NULL
      AND j.claimed_until >= now()
  ) AS currently_claimed,
  count(*) FILTER (
    WHERE j.claimed_until IS NOT NULL
      AND j.claimed_until < now()
      AND j.last_run_at IS NULL
  ) AS expired_claim_without_run
FROM availability_job j;
```

### Watch And Job Table

Shows user intent beside scheduler state. POI-scoped watches may cover many reservables; reservable-scoped watches resolve directly to one RID.

```sql
SELECT
  w.id AS watch_id,
  CASE
    WHEN w.poi_id IS NOT NULL THEN 'poi'
    ELSE 'reservable'
  END AS scope,
  w.poi_id,
  p.name AS poi_name,
  w.reservable_id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid,
  w.reservable_filters,
  w.start_date,
  w.end_date,
  w.cadence_sec AS watch_cadence_sec,
  w.trigger_kinds,
  w.trigger_config,
  w.stop_when_triggered,
  w.status AS watch_status,
  j.id AS job_id,
  j.status AS job_status,
  j.next_run_at,
  j.claimed_until,
  j.last_run_at,
  j.updated_at AS job_updated_at
FROM availability_watch w
LEFT JOIN availability_job j ON j.watch_id = w.id
LEFT JOIN pois p ON p.id = w.poi_id
LEFT JOIN reservables r ON r.id = w.reservable_id
WHERE (${watch_status:sqlstring} = '' OR w.status = ${watch_status:sqlstring})
  AND (${job_status:sqlstring} = '' OR j.status = ${job_status:sqlstring})
  AND (${poi_id:sqlstring} = '' OR w.poi_id = NULLIF(${poi_id:sqlstring}, '')::bigint)
  AND (
    ${reservable_rid:sqlstring} = ''
    OR r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring}
  )
ORDER BY
  j.next_run_at ASC NULLS LAST,
  w.created_at DESC,
  w.id DESC;
```

### Recent Runs

Shows status, failure details, and duration by scheduler run. This is the audit trail that explains missing or stale `availability_snapshot` rows.

```sql
SELECT
  jr.id AS run_id,
  jr.job_id,
  j.watch_id,
  jr.status,
  jr.snapshot_count,
  jr.duration_ms,
  jr.error,
  jr.started_at,
  jr.completed_at,
  w.poi_id,
  p.name AS poi_name,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid
FROM availability_job_run jr
JOIN availability_job j ON j.id = jr.job_id
JOIN availability_watch w ON w.id = j.watch_id
LEFT JOIN pois p ON p.id = w.poi_id
LEFT JOIN reservables r ON r.id = w.reservable_id
WHERE jr.started_at >= now() - (${days_back} || ' days')::interval
  AND (${run_status:sqlstring} = '' OR jr.status = ${run_status:sqlstring})
ORDER BY jr.started_at DESC, jr.id DESC
LIMIT 200;
```

### Snapshots Produced By Runs

Shows whether completed runs produced useful snapshot rows.

```sql
SELECT
  jr.id AS run_id,
  jr.job_id,
  jr.status AS run_status,
  jr.snapshot_count AS recorded_snapshot_count,
  count(s.id) AS actual_snapshot_rows,
  min(s.target_date) AS first_target_date,
  max(s.target_date) AS last_target_date,
  min(s.observed_at) AS first_observed_at,
  max(s.observed_at) AS last_observed_at
FROM availability_job_run jr
LEFT JOIN availability_snapshot s ON s.run_id = jr.id
WHERE jr.started_at >= now() - (${days_back} || ' days')::interval
GROUP BY jr.id
ORDER BY jr.started_at DESC, jr.id DESC
LIMIT 200;
```

## Ingest & Catalog Freshness Dashboard

Dashboard file:

```text
grafana/dashboards/ingest-catalog-freshness.json
```

Core variables:

- `target`: optional ingest target filter.
- `source`: optional catalog source filter.
- `days_back`: numeric interval for run history, default `30`.

This dashboard makes `ingest_runs` and `import_runs` visible. It should answer whether stale POI or reservable data is caused by failed fetch/import work, old successful imports, or catalog rows that have not been seen recently.

### Latest Ingest By Target

Shows the latest parent run for each ingest target and phase kind.

```sql
WITH ranked AS (
  SELECT
    ir.*,
    row_number() OVER (
      PARTITION BY ir.target, ir.phase
      ORDER BY ir.started_at DESC, ir.id DESC
    ) AS rn
  FROM ingest_runs ir
  WHERE ir.phase_kind = 'target'
    AND (${target:sqlstring} = '' OR ir.target = ${target:sqlstring})
)
SELECT
  target,
  phase,
  status,
  started_at,
  completed_at,
  completed_at - started_at AS duration,
  counts,
  notes,
  triggered_by
FROM ranked
WHERE rn = 1
ORDER BY target ASC, phase ASC;
```

### Failed Or Stuck Ingest Phases

Shows failed, aborted, or long-running phase rows with the stderr/failure tail in `notes`.

```sql
SELECT
  child.id,
  child.parent_run_id,
  parent.target,
  parent.phase AS run_kind,
  child.phase,
  child.phase_kind,
  child.status,
  child.started_at,
  child.completed_at,
  child.completed_at - child.started_at AS duration,
  child.exit_code,
  child.counts,
  child.notes
FROM ingest_runs child
LEFT JOIN ingest_runs parent ON parent.id = child.parent_run_id
WHERE child.started_at >= now() - (${days_back} || ' days')::interval
  AND child.phase_kind <> 'target'
  AND (
    child.status IN ('failed', 'aborted')
    OR (child.status = 'started' AND child.started_at < now() - interval '30 minutes')
  )
  AND (${target:sqlstring} = '' OR parent.target = ${target:sqlstring})
ORDER BY child.started_at DESC, child.id DESC;
```

### Import Runs

Shows the lower-level importer audit rows that POIs and reservables reference through `last_seen_run_id`.

```sql
SELECT
  id,
  source,
  status,
  started_at,
  completed_at,
  completed_at - started_at AS duration,
  seen_count,
  notes
FROM import_runs
WHERE started_at >= now() - (${days_back} || ' days')::interval
  AND (${source:sqlstring} = '' OR source = ${source:sqlstring})
ORDER BY started_at DESC, id DESC
LIMIT 200;
```

### Catalog Rows By Freshness

Shows stale POI and reservable rows side by side.

```sql
SELECT
  'poi' AS row_type,
  p.id,
  p.source,
  p.name,
  p.updated_at,
  p.fetched_at,
  p.deleted_at,
  p.last_seen_run_id,
  p.last_poller_run_id,
  now() - p.updated_at AS updated_age
FROM pois p
WHERE (${source:sqlstring} = '' OR p.source = ${source:sqlstring})

UNION ALL

SELECT
  'reservable' AS row_type,
  r.id,
  r.source,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS name,
  r.updated_at,
  NULL::timestamptz AS fetched_at,
  r.deleted_at,
  r.last_seen_run_id,
  NULL::bigint AS last_poller_run_id,
  now() - r.updated_at AS updated_age
FROM reservables r
WHERE (${source:sqlstring} = '' OR r.source = ${source:sqlstring})
ORDER BY updated_age DESC NULLS LAST
LIMIT 300;
```

## Provider Cache / Raw Data Audit Dashboard

Dashboard file:

```text
grafana/dashboards/provider-cache-audit.json
```

Core variables:

- `namespace`: optional cache namespace filter.
- `cache_key_search`: optional text search.
- `include_expired`: boolean-like selector default `false`.

This dashboard makes `api_cache` easier to inspect without hiding raw provider payloads. It should be used when a POI or reservable dashboard shows stale or surprising state and the operator needs to understand whether the provider cache is involved.

### Cache Namespace Summary

Shows cache volume, expiration, and payload size by namespace.

```sql
SELECT
  namespace,
  count(*) AS rows,
  count(*) FILTER (WHERE expires_at <= now()) AS expired_rows,
  min(created_at) AS oldest_created_at,
  max(created_at) AS newest_created_at,
  min(expires_at) AS earliest_expires_at,
  max(expires_at) AS latest_expires_at,
  pg_size_pretty(sum(pg_column_size(payload))::bigint) AS payload_size
FROM api_cache
WHERE (${namespace:sqlstring} = '' OR namespace = ${namespace:sqlstring})
GROUP BY namespace
ORDER BY namespace ASC;
```

### Cache Rows

Shows raw cache rows with age and expiration state.

```sql
SELECT
  namespace,
  cache_key,
  created_at,
  expires_at,
  now() - created_at AS age,
  expires_at - now() AS freshness_remaining,
  expires_at <= now() AS expired,
  pg_column_size(payload) AS payload_bytes,
  payload
FROM api_cache
WHERE (${namespace:sqlstring} = '' OR namespace = ${namespace:sqlstring})
  AND (${cache_key_search:sqlstring} = '' OR cache_key ILIKE '%' || ${cache_key_search:sqlstring} || '%')
  AND (
    ${include_expired:sqlstring} = 'true'
    OR expires_at > now()
  )
ORDER BY created_at DESC
LIMIT 200;
```

### Cache Rows Related To A Reservable

This duplicates the Reservable Detail cache panel as a broader audit view. It remains heuristic because `api_cache.cache_key` is not a normalized foreign key to `reservables`.

```sql
WITH selected AS (
  SELECT
    vendor_id,
    provider_ref->>'mapId' AS map_id,
    provider_ref->>'resourceLocationId' AS resource_location_id
  FROM reservables
  WHERE type || ':' || vendor || ':' || vendor_id = ${reservable_rid:sqlstring}
),
needles AS (
  SELECT value
  FROM selected
  CROSS JOIN LATERAL (
    VALUES (vendor_id), (map_id), (resource_location_id)
  ) AS raw(value)
  WHERE value IS NOT NULL AND value <> ''
)
SELECT
  c.namespace,
  c.cache_key,
  c.created_at,
  c.expires_at,
  now() - c.created_at AS age,
  c.expires_at <= now() AS expired,
  c.payload
FROM api_cache c
WHERE ${reservable_rid:sqlstring} <> ''
  AND EXISTS (
    SELECT 1
    FROM needles
    WHERE c.cache_key ILIKE '%' || needles.value || '%'
  )
ORDER BY c.created_at DESC
LIMIT 100;
```

## API To SQL Equivalence

The API / SQL equivalence dashboard should compare the read-only APIs against direct SQL. These panels make removal decisions observable, but they are secondary to the reservable and POI detail dashboards.

### `/api/availability/jobs`

API behavior:

- Optional `status`
- Optional `watch_id`
- `limit`, `offset`
- Ordered by `created_at DESC, id DESC`

SQL:

```sql
SELECT
  id,
  watch_id,
  cadence_sec,
  status,
  next_run_at,
  claimed_until,
  last_run_at,
  created_at,
  updated_at
FROM availability_job
WHERE (${status:sqlstring} = '' OR status = ${status:sqlstring})
  AND (${watch_id:sqlstring} = '' OR watch_id = NULLIF(${watch_id:sqlstring}, '')::bigint)
ORDER BY created_at DESC, id DESC
LIMIT 100;
```

### `/api/availability/jobs/summary`

API behavior:

- Counts active, paused, done, due now, and claimed jobs.

SQL:

```sql
SELECT
  count(*) FILTER (WHERE status = 'active') AS active,
  count(*) FILTER (WHERE status = 'paused') AS paused,
  count(*) FILTER (WHERE status = 'done') AS done,
  count(*) FILTER (
    WHERE status = 'active'
      AND next_run_at <= now()
      AND (claimed_until IS NULL OR claimed_until < now())
  ) AS due_now,
  count(*) FILTER (
    WHERE claimed_until IS NOT NULL
      AND claimed_until >= now()
  ) AS claimed
FROM availability_job;
```

### `/api/availability/jobs/{id}/runs`

API behavior:

- Runs for one job, newest first.

SQL:

```sql
SELECT
  id,
  job_id,
  status,
  snapshot_count,
  duration_ms,
  error,
  started_at,
  completed_at
FROM availability_job_run
WHERE job_id = ${job_id}
ORDER BY started_at DESC, id DESC
LIMIT 100;
```

### `/api/availability/runs`

API behavior:

- Optional `status`
- Optional `job_id`
- Optional `since`
- Ordered by `started_at DESC, id DESC`

SQL:

```sql
SELECT
  id,
  job_id,
  status,
  snapshot_count,
  duration_ms,
  error,
  started_at,
  completed_at
FROM availability_job_run
WHERE (${run_status:sqlstring} = '' OR status = ${run_status:sqlstring})
  AND (${job_id:sqlstring} = '' OR job_id = NULLIF(${job_id:sqlstring}, '')::bigint)
  AND (${since:sqlstring} = '' OR started_at >= NULLIF(${since:sqlstring}, '')::timestamptz)
ORDER BY started_at DESC, id DESC
LIMIT 100;
```

### `/api/availability/snapshots`

API behavior:

- Query either by reservable RID or run ID.
- By reservable: newest by `target_date`, `observed_at`, `id`.
- By run: ordered by `target_date ASC`.

SQL by reservable:

```sql
SELECT
  s.id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid,
  s.run_id,
  s.target_date,
  s.observed_at,
  s.status,
  s.available,
  s.day_payload
FROM availability_snapshot s
JOIN reservables r ON r.id = s.reservable_id
WHERE r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring}
ORDER BY s.target_date DESC, s.observed_at DESC, s.id DESC
LIMIT 200;
```

SQL by run:

```sql
SELECT
  s.id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid,
  s.run_id,
  s.target_date,
  s.observed_at,
  s.status,
  s.available,
  s.day_payload
FROM availability_snapshot s
LEFT JOIN reservables r ON r.id = s.reservable_id
WHERE s.run_id = ${run_id}
ORDER BY s.target_date ASC
LIMIT 500;
```

### `/api/availability/snapshots/summary`

API behavior:

- For a reservable RID, summarize per target date over a window.
- Computes total snapshots, last open timestamp, current open state, current or last open window, median open window, and flips over the last 24 hours.

SQL for the first-pass Grafana panel:

```sql
WITH scoped AS (
  SELECT
    s.target_date,
    s.observed_at,
    s.available,
    lag(s.available) OVER (
      PARTITION BY s.target_date
      ORDER BY s.observed_at ASC, s.id ASC
    ) AS prev_available
  FROM availability_snapshot s
  JOIN reservables r ON r.id = s.reservable_id
  WHERE r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring}
    AND s.observed_at >= now() - (${window_hours} || ' hours')::interval
)
SELECT
  target_date,
  count(*) AS total_snapshots,
  max(observed_at) FILTER (WHERE available) AS last_open_at,
  (array_agg(available ORDER BY observed_at DESC))[1] AS is_currently_open,
  count(*) FILTER (
    WHERE observed_at >= now() - interval '24 hours'
      AND prev_available = false
      AND available = true
  ) AS flips_last_24h
FROM scoped
GROUP BY target_date
ORDER BY target_date ASC;
```

The Kotlin API computes median open-window duration and current-or-last-open-window duration in application code. Grafana can either omit those in the first pass or add a more complex SQL panel later. This is a useful distinction: some API wrappers are direct SQL, while some contain derived application logic worth preserving or moving into SQL views before deletion.

### `/api/reservables`

API behavior:

- Search active reservables across fields.
- Joins linked active POI IDs in the response.

SQL:

```sql
SELECT
  r.id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS rid,
  r.type,
  r.vendor,
  r.vendor_id,
  r.name,
  r.loop,
  r.site_type,
  array_remove(array_agg(p.id ORDER BY p.id), NULL) AS poi_ids,
  r.tags,
  r.raw
FROM reservables r
LEFT JOIN reservable_pois rp ON rp.reservable_id = r.id
LEFT JOIN pois p ON p.id = rp.poi_id AND p.deleted_at IS NULL
WHERE r.deleted_at IS NULL
  AND (${vendor:sqlstring} = '' OR r.vendor = ${vendor:sqlstring})
  AND (${site_type:sqlstring} = '' OR r.site_type = ${site_type:sqlstring})
  AND (${name:sqlstring} = '' OR r.name = ${name:sqlstring})
GROUP BY r.id
ORDER BY r.type ASC, r.vendor ASC, r.vendor_id ASC
LIMIT 100;
```

### `/api/reservable/{rid}`

API behavior:

- Detail for one composite reservable ID.
- Includes active linked POI IDs.

SQL:

```sql
SELECT
  r.id,
  r.type || ':' || r.vendor || ':' || r.vendor_id AS rid,
  r.type,
  r.vendor,
  r.vendor_id,
  r.name,
  r.loop,
  r.site_type,
  array_remove(array_agg(p.id ORDER BY p.id), NULL) AS poi_ids,
  r.provider_ref,
  r.tags,
  r.raw
FROM reservables r
LEFT JOIN reservable_pois rp ON rp.reservable_id = r.id
LEFT JOIN pois p ON p.id = rp.poi_id AND p.deleted_at IS NULL
WHERE r.deleted_at IS NULL
  AND r.type || ':' || r.vendor || ':' || r.vendor_id = ${reservable_rid:sqlstring}
GROUP BY r.id;
```

### `/api/pois/search`

API behavior:

- App topbar text search.
- Used outside the standalone `/pois` page.
- Should remain for the app.

Grafana comparison SQL:

```sql
SELECT
  id,
  name,
  category,
  region,
  ST_X(geom) AS lng,
  ST_Y(geom) AS lat
FROM pois
WHERE deleted_at IS NULL
  AND name ILIKE '%' || ${poi_query:sqlstring} || '%'
ORDER BY
  (name ILIKE ${poi_query:sqlstring} || '%') DESC,
  length(name) ASC,
  name ASC
LIMIT 25;
```

## API Removal Classification

Likely removable after Grafana proves equivalent:

- `GET /api/availability/jobs`
- `GET /api/availability/jobs/summary`
- `GET /api/availability/jobs/{id}/runs`
- `GET /api/availability/runs`
- `GET /api/availability/snapshots`
- `GET /api/reservables`
- `GET /api/reservable/{rid}`

Needs more analysis before removal:

- `GET /api/availability/snapshots/summary` because part of the response is derived in Kotlin.
- `GET /api/reservable/{rid}/availability` because it performs a live provider availability lookup, not just a Postgres query.

Should remain:

- `POST /api/pois`
- `GET /api/pois/{id}`
- `GET /api/pois/search`
- `POST /api/pois/on-route`
- `GET /api/poi/{id}/reservables`
- `GET /api/poi/{id}/reservables/availability`
- `POST /api/availability/bulk`
- `/api/availability/watches*`
- `/api/route`
- `/api/geocode`
- `/api/admin/data/*`
- `/api/health`

## Testing Strategy

Configuration checks:

- Run `docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois config`.
- Confirm Compose resolves the root Dockerfile backend target and the official Grafana image.

Backend image check:

- Run `./gradlew :backend:shadowJar`.
- Run `docker build -t roadtrip/backend --target backend .`.

Grafana health check:

- Start `postgres` and `grafana`.
- Verify `http://127.0.0.1:3000/api/health` returns healthy JSON.
- Verify Grafana can query Postgres through the provisioned datasource.
- Verify `reservable-detail`, `poi-detail`, `watch-scheduler-health`, `ingest-catalog-freshness`, `provider-cache-audit`, and `api-sql-equivalence` dashboards load with their query variables populated.

Tilt check:

- Run `tilt up`.
- Confirm Tilt shows separate resources for `postgres`, `backend`, and `grafana`.
- Confirm backend image changes trigger a rebuild/restart.
- Confirm dashboard JSON changes update through the bind mount without rebuilding an image.

Regression checks:

- Existing backend route tests should still pass because no APIs are removed.
- Existing smoke tests may need URL expectations updated only if Tilt now runs backend as a Compose service on the same port.

## Rollout Plan

1. Add root Dockerfile target for backend.
2. Update Compose backend service to use `image: roadtrip/backend`.
3. Add Grafana service with official image and mounted provisioning/dashboards.
4. Add Grafana read-only database role setup service.
5. Update Tiltfile to use `docker_compose`, `docker_build`, and `dc_resource`.
6. Update Makefile and GitHub deploy to run `docker build -t roadtrip/backend --target backend .` after `:backend:shadowJar` and before `docker compose up`.
7. Add the POI detail, reservable detail, watch/scheduler health, ingest/catalog freshness, provider cache audit, and API-to-SQL equivalence dashboards.
8. Validate local Compose and Tilt startup.
9. Defer static UI and API removal to a later PR after the dashboard proves coverage.

## Risks

- Grafana provisioning can fail silently if environment variables are missing. Use explicit defaults locally and document production overrides.
- Grafana datasource changes often need a restart. Keep dashboards bind-mounted, but expect datasource YAML edits to restart the Grafana service.
- Moving Tilt backend execution from host JVM to Compose backend changes the dev loop. It improves Docker parity but can be slower than host `:backend:run`.
- Compose no longer owns the backend build when Tilt is in use. Keep Makefile and deploy workflow explicit: build the fat jar, build `roadtrip/backend`, then run Compose.
- SQL panels can drift from Kotlin API behavior. This first pass should present equivalence, not immediately delete routes.
- The read-only role setup service needs enough database privilege to create or alter a role. In this stack, the Compose-managed app database owner is expected to have that privilege.

## References

- Grafana Docker image docs: https://grafana.com/docs/grafana/latest/setup-grafana/configure-docker/
- Grafana provisioning docs: https://grafana.com/docs/grafana/latest/administration/provisioning/
- Grafana PostgreSQL datasource docs: https://grafana.com/docs/grafana/latest/datasources/postgres/configure/
- Tilt dependent images and `docker_build`: https://docs.tilt.dev/dependent_images.html
- Tilt Docker Compose integration: https://docs.tilt.dev/docker_compose.html
