# Roadtrip Map dev stack.
#
# Compose owns the database, backend container, and Grafana services. Tilt
# watches the backend source, builds the fat jar, rebuilds the backend image,
# and keeps the Dockerized Rec.gov companion plus manual data resources
# available.

PORT = '8765'
COMPOSE_PROJECT = 'roadtrip'
COMPOSE = 'docker compose -p ' + COMPOSE_PROJECT + ' --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.secrets.yml --profile pois --profile recgov-companion'
COMPOSE_INFRA_SERVICES = ['postgres', 'loki', 'tempo', 'prometheus', 'alloy']
COMPOSE_APP_SERVICES = ['backend', 'recgov-companion', 'grafana']
COMPOSE_DEV_SERVICES = ' '.join(COMPOSE_INFRA_SERVICES + COMPOSE_APP_SERVICES)
COMPOSE_DOWN = COMPOSE + ' down --timeout 10 ' + COMPOSE_DEV_SERVICES
DETACHED_COMPOSE_DOWN = (
    "python3 -c 'import os, subprocess, sys; " +
    "os.setsid(); " +
    "subprocess.Popen(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)' " +
    COMPOSE_DOWN
)

# Load secrets into Tilt's own environment.
#
# Tilt invokes `docker compose` itself, so it can't be wrapped in
# `manage.py exec` the way the Makefile targets are. os.putenv does propagate
# to the subprocesses Tilt spawns, which is enough: Compose reads these to
# resolve docker-compose.secrets.yml's `environment:` sources and mounts them
# into containers as /run/secrets files.
#
# quiet=True matters — without it Tilt would echo the decrypted values into its
# log. The values cross a pipe in memory and are never written to disk.
def _load_secrets():
    raw = str(local('./secrets/manage.py export local', quiet=True, echo_off=True))
    names = []
    for line in raw.splitlines():
        line = line.strip()
        if not line or '=' not in line:
            continue
        k, _, v = line.partition('=')
        os.putenv(k, v)
        names.append(k)
    return names

SECRET_NAMES = _load_secrets()
print('secrets: loaded %d for the local environment' % len(SECRET_NAMES))

# Point this clone's git at .githooks/ so the committed pre-commit (ktlint) and
# pre-push (backend tests) hooks fire. core.hooksPath isn't tracked in the repo,
# so a fresh clone starts with them inactive; wiring it into the primary dev
# entry point means nobody has to remember `make install-hooks`. Idempotent and
# quiet: only rewrites config when it isn't already pointed at .githooks.
local(
    '[ "$(git config core.hooksPath 2>/dev/null)" = ".githooks" ] || ' +
    'git config core.hooksPath .githooks',
    quiet=True,
)

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
    cmd='./gradlew --console=plain :backend:buildFatJar',
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

# The React frontend (frontend/). Built to frontend/dist, which the backend
# compose service bind-mounts and serves — so a rebuild needs no image rebuild
# and no container restart, just a browser refresh.
#
# Split in two so a source edit does not re-resolve dependencies: `npm ci` is
# keyed on the lockfile, the build on the sources. `npm run build` is
# `tsc --noEmit && vite build`, so a type error fails this resource and shows up
# in the Tilt UI instead of silently serving a stale bundle.
local_resource(
    'frontend-deps',
    cmd='cd frontend && npm ci',
    deps=[
        'frontend/package.json',
        'frontend/package-lock.json',
    ],
    labels=['build'],
)

local_resource(
    'frontend-dist',
    cmd='cd frontend && npm run build',
    resource_deps=['frontend-deps'],
    deps=[
        # package.json/lock are here as well as on frontend-deps: resource_deps
        # only orders, it does not re-trigger, so without these a dependency bump
        # would reinstall and leave the bundle stale.
        'frontend/package.json',
        'frontend/package-lock.json',
        'frontend/src',
        'frontend/vendor',
        'frontend/index.html',
        'frontend/availability.html',
        'frontend/watches.html',
        'frontend/vite.config.ts',
        'frontend/tsconfig.json',
    ],
    ignore=[
        'frontend/dist',
        'frontend/node_modules',
    ],
    labels=['build'],
)

docker_compose(
    ['docker-compose.yml', 'docker-compose.local.yml', 'docker-compose.secrets.yml'],
    project_name=COMPOSE_PROJECT,
    profiles=['pois', 'recgov-companion'],
)

# When `backend-jar` produces a new fat jar, Tilt rebuilds this image and
# recreates the backend compose service, restarting the JVM so the new jar
# (and any new Flyway migrations) actually takes effect.
#
# No live_update here on purpose. In-place `sync` alone can't work — the JVM
# loads its classes from the jar at startup, so replacing the file under a
# running process changes nothing — and the process-restart mechanisms don't
# apply to Docker Compose: `restart_container()` is deprecated and a no-op, and
# the restart_process extension's entrypoint wrapper is rejected for compose
# resources ("entrypoint not supported for Docker Compose"). A full rebuild +
# recreate is the reliable path, and it's cheap here: only the jar COPY layer
# changes (the JRE + apt base layers are cached), so the rebuild is a fast
# layer swap, not a from-scratch image build.
#
# The jar name is configured through Ktor's `fatJar` extension in
# backend/build.gradle.kts; keep this path and the Dockerfile COPY in sync if
# the version bumps.
docker_build(
    'roadtrip/backend',
    '.',
    dockerfile='Dockerfile',
    target='backend',
    only=[
        'Dockerfile',
        'backend/build/libs',
        # The Dockerfile's `frontend` stage builds frontend/dist from source
        # (COPY --from=frontend, needed even though target=backend, since
        # that COPY makes the frontend stage a build dependency). Mirror
        # frontend-dist's own deps list above so the build context actually
        # contains what the Dockerfile COPYs.
        'frontend/package.json',
        'frontend/package-lock.json',
        'frontend/vendor',
        'frontend/src',
        'frontend/index.html',
        'frontend/availability.html',
        'frontend/watches.html',
        'frontend/vite.config.ts',
        'frontend/tsconfig.json',
    ],
)

docker_build(
    'roadtrip/recgov-companion',
    'companion',
    dockerfile='companion/Dockerfile',
    only=[
        'Dockerfile',
        'package.json',
        'package-lock.json',
        'src',
    ],
)

for service in ['postgres', 'loki', 'tempo', 'prometheus']:
    dc_resource(service, resource_deps=['compose-cleanup'], labels=['infra'])

dc_resource('alloy', resource_deps=['loki', 'tempo', 'prometheus'], labels=['infra'])
dc_resource(
    'backend',
    resource_deps=['postgres', 'alloy', 'backend-jar', 'frontend-dist'],
    labels=['app'],
    links=['http://127.0.0.1:' + PORT],
)
dc_resource(
    'recgov-companion',
    resource_deps=['alloy'],
    labels=['app'],
    links=['http://127.0.0.1:8770'],
)
dc_resource(
    'grafana',
    # R__grafana_reader_grants.sql runs during backend's Flyway migrate; wait
    # for backend to be healthy before Grafana tries to connect as grafana_reader.
    resource_deps=['postgres', 'backend'],
    labels=['infra'],
    links=['http://127.0.0.1:3000'],
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
# - 'data-fetch' runs the host-side registry fetchers and writes data/raw/.
# - 'data-import' POSTs to the backend admin API so the Kotlin ETL imports the
#   newest raw captures into Postgres.

# --- Data refresh (RFC 0004 / issue #44) -------------------------------------
# Two buttons. data-fetch pulls upstream JSON/GeoJSON into data/raw/<target>*
# by running scripts/poll_raw.py on the host. data-import loads those files into
# Postgres via the backend's Kotlin importer and records import rows in
# ingest_runs.
#
# First-time stack bring-up: `tilt up` → DB migrates → click data-fetch (or
# skip if data/ is already populated) → click data-import. Routine refresh:
# click data-fetch then data-import.
#
local_resource(
    'data-fetch',
    cmd='python3 scripts/poll_raw.py --all',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    labels=['data'],
)

# `--fail-with-body` makes curl exit non-zero on 4xx/5xx but still print the
# JSON body, so a failed_phase shows up in the resource pane.
local_resource(
    'data-import',
    cmd='curl --fail-with-body -sS --max-time 1800 -X POST http://127.0.0.1:' + PORT + '/api/admin/data/import',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    resource_deps=['backend'],
    labels=['data'],
)
