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
# Playwright pulls Chromium (~150MB) on first install. We don't manage that
# here — first-time setup is `make install-companion`. After that, Tilt
# restarts the companion whenever src changes.

local_resource(
    'companion',
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
