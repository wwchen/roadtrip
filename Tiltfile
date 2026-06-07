# Roadtrip Map dev stack.
#
# Three resources:
#   postgres  — Docker (PostGIS), idempotent `compose up -d postgres`.
#   backend   — Kotlin/Ktor on the host, hot-rebuilt on src changes.
#   companion — Node/Playwright on the host, restarted on src changes.
#
# Postgres lives in Docker because the schema needs PostGIS — keeping it out
# of host Homebrew avoids the brew-postgis install dance. Backend + companion
# run on the host so Tilt can watch their source trees and the JVM/Node
# processes stay attached for log streaming.

PORT = '8765'

# Pull MAPBOX_TOKEN from .env so the backend's /api/route endpoint can call
# Mapbox Directions. read_file with default='' returns empty when the file
# is missing, and the dotenv parser ignores blank/comment lines. Tokens
# never appear on the rendered command line — they go through serve_env.
def _load_dotenv(path):
    out = {}
    raw = str(read_file(path, default=''))
    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, _, v = line.partition('=')
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        if k and k not in out:
            out[k] = v
    return out

DOTENV = _load_dotenv('.env')

# --- postgres (Docker) -------------------------------------------------------
# Reuse the same compose file the rest of the project uses. `up -d` is
# idempotent (no-op when postgres is already running). Tilt won't manage
# Docker state directly — it just shells out and shows the result.

local_resource(
    'postgres',
    # Foreground `up` (no -d, no separate `logs -f`). Tilt owns the
    # docker-compose process and sends SIGTERM on shutdown, so the container
    # stops cleanly when Tilt exits. Volumes persist (no `down -v`), so DB
    # state is preserved between sessions; only the container is stopped.
    serve_cmd='docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up postgres',
    deps=['docker-compose.yml', 'docker-compose.local.yml'],
    readiness_probe=probe(
        period_secs=2,
        exec=exec_action(['docker', 'compose', 'exec', '-T', 'postgres', 'pg_isready', '-U', 'roadtrip', '-d', 'roadtrip']),
    ),
    labels=['infra'],
)

# --- backend (host JVM) ------------------------------------------------------
# `./gradlew run` keeps the daemon alive and recompiles on src change. We
# point ROADTRIP_STATIC_DIR at the repo root so static/data files get served
# from the working tree (no copy step). Health check uses /api/health, which
# returns 200 once Flyway has finished migrating.

local_resource(
    'backend',
    serve_cmd='cd backend && PORT=' + PORT + ' ROADTRIP_STATIC_DIR=$PWD/.. ' +
              'ROADTRIP_DB_URL=jdbc:postgresql://127.0.0.1:5432/roadtrip ' +
              'ROADTRIP_DB_USER=roadtrip ROADTRIP_DB_PASSWORD=roadtrip ' +
              './gradlew --console=plain run',
    serve_env={
        'JAVA_TOOL_OPTIONS': '-Dorg.gradle.daemon=true',
        # MAPBOX_TOKEN is read by /api/route. Empty when .env is missing —
        # endpoint then 503s, rest of the app is unaffected.
        'MAPBOX_TOKEN': DOTENV.get('MAPBOX_TOKEN', ''),
    },
    deps=[
        'backend/src/main',
        'backend/build.gradle.kts',
        'backend/settings.gradle.kts',
    ],
    ignore=[
        'backend/build',
        'backend/.gradle',
        'backend/src/main/resources/static',
    ],
    readiness_probe=probe(
        period_secs=3,
        initial_delay_secs=10,
        http_get=http_get_action(port=int(PORT), path='/api/health'),
    ),
    resource_deps=['postgres'],
    labels=['app'],
)

# --- companion (host Node) ---------------------------------------------------
# `cmd` runs the same npm + playwright install pair as `make install` does,
# but scoped to the companion (idempotent: `npm install` is a no-op when
# node_modules is fresh; `playwright install chromium` likewise skips when
# the browser is already on disk). Re-runs when package.json changes.
# `serve_cmd` then keeps the Node process attached for log streaming.

local_resource(
    'companion',
    cmd='cd companion && npm install && npx playwright install chromium',
    serve_cmd='cd companion && node --experimental-eventsource src/index.js',
    serve_env={'BACKEND_URL': 'http://127.0.0.1:' + PORT},
    deps=['companion/src', 'companion/package.json'],
    ignore=['companion/node_modules'],
    resource_deps=['backend'],
    labels=['app'],
)

# --- UI shortcut -------------------------------------------------------------
# Tilt's web UI surfaces this as a clickable link.

local_resource(
    'open-app',
    cmd='echo http://127.0.0.1:' + PORT,
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    labels=['links'],
    links=['http://127.0.0.1:' + PORT, 'http://127.0.0.1:' + PORT + '/campsite'],
)

# --- background workers (manual-trigger) -------------------------------------
# Data-refresh and import jobs. None of these run on `tilt up`; click the row
# in the Tilt UI to fire one. Tilt shows last-run timestamp + status + log
# tail per resource — much friendlier than remembering Make targets in a
# separate shell.
#
# Notes:
# - 'refresh-image' builds the Dockerized Python runtime that the supercharger
#   refreshers use. It's a one-shot prereq; subsequent runs are a no-op until
#   scripts/Dockerfile.refresh changes.
# - 'refresh-cookies-local' mints Tesla cookies into THIS repo's .env. The
#   prod equivalent ('make refresh-cookies', remote-host) intentionally is
#   not surfaced here — it's a deploy-machine concern.
# - 'data-fetch' / 'data-import' POST to the backend's RFC 0004 admin API.
#   Two-step refresh: fetch upstream into data/*.{json,geojson}, then import
#   into Postgres. Both fan out across every target; per-target mutex keeps
#   them ordered.

local_resource(
    'refresh-image',
    cmd='make refresh-image',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    labels=['data'],
)

local_resource(
    'refresh-superchargers',
    cmd='make refresh-superchargers',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    resource_deps=['refresh-image'],
    labels=['data'],
)

local_resource(
    'rebuild-superchargers',
    cmd='make rebuild-superchargers',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    resource_deps=['refresh-image'],
    labels=['data'],
)

local_resource(
    # Tesla-specific cookie refresh, NOT recreation.gov. Recgov auth lives in
    # the backend's TokenManager (see RFC 0001 / PR #22). This row mints fresh
    # _abck cookies for the Tesla supercharger scraper into this repo's .env.
    'refresh-tesla-cookies',
    cmd='make refresh-cookies-local',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    labels=['data'],
)

# --- Data refresh (RFC 0004 / issue #44) -------------------------------------
# Two buttons. data-fetch pulls upstream JSON/GeoJSON into data/<target>.*
# (Python scripts run as subprocesses by the backend's admin API). data-import
# loads those files into Postgres via the Kotlin importer. Both fan out across
# every known target sequentially. Per-target mutex serializes fetch + import
# on the same target.
#
# First-time stack bring-up: `tilt up` → DB migrates → click data-fetch (or
# skip if data/ is already populated) → click data-import. Routine refresh:
# click data-fetch then data-import.
#
# `--fail-with-body` makes curl exit non-zero on 4xx/5xx but still print the
# JSON body (so a failed_phase shows up in the resource pane). 30-min timeout
# covers the campgrounds enricher worst case (~10 min today).

local_resource(
    'data-fetch',
    cmd='curl --fail-with-body -sS --max-time 1800 -X POST http://127.0.0.1:' + PORT + '/api/admin/data/fetch',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    resource_deps=['backend'],
    labels=['data'],
)

local_resource(
    'data-import',
    cmd='curl --fail-with-body -sS --max-time 1800 -X POST http://127.0.0.1:' + PORT + '/api/admin/data/import',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    resource_deps=['backend'],
    labels=['data'],
)
