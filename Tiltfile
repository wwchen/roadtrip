# Roadtrip Map dev stack.
#
# Three resources:
#   postgres  — Docker (PostGIS), reused via the existing `make pois-up` flow.
#   backend   — Kotlin/Ktor on the host, hot-rebuilt on src changes.
#   companion — Node/Playwright on the host, restarted on src changes.
#
# Postgres lives in Docker because the schema needs PostGIS — keeping it out
# of host Homebrew avoids the brew-postgis install dance. Backend + companion
# run on the host so Tilt can watch their source trees and the JVM/Node
# processes stay attached for log streaming.

PORT = '8765'

# --- postgres (Docker) -------------------------------------------------------
# Reuse the same compose file the rest of the project uses. `make pois-up` is
# idempotent (compose up -d), `pois-down` stops it. Tilt won't manage Docker
# state directly — it just shells out and shows the result.

local_resource(
    'postgres',
    cmd='make pois-up',
    serve_cmd='docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois logs -f postgres',
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
    serve_env={'JAVA_TOOL_OPTIONS': '-Dorg.gradle.daemon=true'},
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
# `cmd` runs `make install-companion` (idempotent: `npm install` is a no-op
# when node_modules is fresh; `playwright install chromium` likewise skips
# when the browser is already on disk). Re-runs when package.json changes.
# `serve_cmd` then keeps the Node process attached for log streaming.

local_resource(
    'companion',
    cmd='make install-companion',
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
# - 'pois-import' / 'pois-import-all' run the Kotlin importer against local
#   Postgres. Upstream Python fetchers (scripts/fetch_*.py) generate the
#   data/*.json the importer reads — those are still Make-only because they
#   run rarely and require source-specific cookies/keys.

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

# POI imports stay shell-only — `make pois-import` is interactive (fzf
# multi-select picker), and Tilt resource panes don't have a TTY to drive
# fzf or bash `select`. Run from a terminal: `make pois-import` for the
# picker, `make pois-import SOURCE=all` to skip it.
