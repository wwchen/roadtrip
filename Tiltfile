# Roadtrip Map dev stack.
#
# Compose owns the database, backend container, and Grafana services. Tilt
# watches the backend source, builds the fat jar, rebuilds the backend image,
# and keeps the host-side companion plus manual data resources available.

PORT = '8765'
COMPOSE_PROJECT = 'roadtrip'
COMPOSE = 'docker compose -p ' + COMPOSE_PROJECT + ' --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois'
COMPOSE_DEV_SERVICES = 'postgres backend grafana grafana-db-setup loki alloy'
COMPOSE_DOWN = COMPOSE + ' down --timeout 10 ' + COMPOSE_DEV_SERVICES
DETACHED_COMPOSE_DOWN = (
    "python3 -c 'import os, subprocess, sys; " +
    "os.setsid(); " +
    "subprocess.Popen(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)' " +
    COMPOSE_DOWN
)

# Keep .env values available for local manual resources and future host-side
# commands. read_file with default='' returns empty when the file is missing,
# and the dotenv parser ignores blank/comment lines.
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

# Tilt keeps Docker Compose resources running on Ctrl+C by default. Keep an
# attached local resource alive so Tilt terminates it on exit; the shell trap
# tears down dev containers while preserving bind-mounted and named volumes.
local_resource(
    'compose-cleanup',
    cmd='true',
    serve_cmd=(
        "cleanup() { trap - EXIT INT TERM HUP; " +
        'if [ -n "${sleep_pid:-}" ]; then kill "$sleep_pid" 2>/dev/null || true; wait "$sleep_pid" 2>/dev/null || true; fi; ' +
        DETACHED_COMPOSE_DOWN + "; }; " +
        "trap cleanup EXIT; " +
        'trap "cleanup; exit 0" INT TERM HUP; ' +
        'while true; do sleep 86400 & sleep_pid=$!; wait "$sleep_pid"; done'
    ),
    labels=['infra'],
)

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
    project_name=COMPOSE_PROJECT,
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
    # Auto-restart the backend the moment `backend-jar` produces a new fat jar.
    # Without this, a completed rebuild could leave the running container serving
    # a stale jar (e.g. never applying new Flyway migrations). live_update syncs
    # the freshly built jar into the container and restarts the JVM process, so a
    # source change deterministically reaches the running backend — and faster
    # than a full image rebuild + recreate.
    #
    # The jar name is version-pinned by shadowJar's archiveBaseName +
    # `version` in backend/build.gradle.kts (currently roadtrip-backend-0.1.0);
    # keep this path and the Dockerfile COPY in sync if the version bumps.
    live_update=[
        sync('backend/build/libs/roadtrip-backend-0.1.0-all.jar', '/app/app.jar'),
        restart_container(),
    ],
)

dc_resource('postgres', resource_deps=['compose-cleanup'], labels=['infra'])
dc_resource(
    'grafana-db-setup',
    resource_deps=['backend'],
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
dc_resource('loki', resource_deps=['compose-cleanup'], labels=['infra'])
dc_resource('alloy', resource_deps=['loki'], labels=['infra'])

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
# - 'data-fetch' / 'data-import' POST to the backend's RFC 0004 admin API.
#   Two-step refresh: fetch upstream into data/*.{json,geojson}, then import
#   into Postgres. Both fan out across every target; per-target mutex keeps
#   them ordered.

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
