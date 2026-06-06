.PHONY: help run deploy stop check-pushed refresh-cookies refresh-cookies-local refresh-superchargers rebuild-superchargers pois-up pois-down pois-import pois-refresh qa install install-hooks companion

PORT       ?= 8765
DEPLOY_HOST ?= mini-ca
DEPLOY_USER ?= mini
DEPLOY_DIR  ?= ~/workspace/roadtrip

help:
	@echo "Targets:"
	@echo "  make install          One-time host setup: brew deps + companion + git hooks"
	@echo "  make install-hooks    Point this clone's git hooks at .githooks/ (per-clone)"
	@echo "  make run              Build + run backend locally on 127.0.0.1:$(PORT) (serves static + /api)"
	@echo "  make companion        Run the campsite Playwright companion (against the local backend)"
	@echo "  make pois-up          Start Postgres+PostGIS on 127.0.0.1:5432"
	@echo "  make pois-down        Stop Postgres"
	@echo "  make pois-import      Import POI sources (interactive picker; SOURCE=uscampgrounds or SOURCE=all to skip the picker)"
	@echo "  make pois-refresh TARGET=<name>  POST to the backend admin API for one ingest target (RFC 0004)"
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make stop             Stop all compose services locally"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, build backend, docker compose up (backend+postgres+tunnel)"
	@echo "  make refresh-cookies  Push Tesla cookies from clipboard → $(DEPLOY_HOST) (Tailscale exit node recommended)"
	@echo "  make refresh-cookies-local  Mint cookies into THIS repo's .env (laptop-only egress)"
	@echo "  make refresh-superchargers  Full Tesla refresh: bulk feed + per-site (~25 min)"
	@echo "  make rebuild-superchargers  Rebuild geojson from cache, no network (~30s)"
	@echo ""
	@echo "Stack startup: \`tilt up\` (full dev) or \`make run\` (backend only)."

# Run the backend on the host, serving static + /api. Postgres still has to
# be reachable — start it with `make pois-up` first if you don't already
# have it running. The backend serves index.html, /web/*, /data/* (excluding
# pricing-cache, which is exposed only via /api/pricing/{slug}), plus all
# four /api/* routes.
run: pois-up
	cd backend && PORT=$(PORT) ROADTRIP_STATIC_DIR=$(PWD) \
	  ROADTRIP_DB_URL=jdbc:postgresql://127.0.0.1:5432/roadtrip \
	  ROADTRIP_DB_USER=roadtrip ROADTRIP_DB_PASSWORD=roadtrip \
	  ./gradlew run

companion:
	cd companion && BACKEND_URL=http://127.0.0.1:$(PORT) node --experimental-eventsource src/index.js

# One-time host setup for a fresh clone. Idempotent: brew is no-op when
# packages are present, npm install + playwright install are no-op when the
# lockfile and browser cache are unchanged, install-hooks just rewrites
# .git/config.
install: install-hooks
	brew install tilt docker openjdk node
	cd companion && npm install && npx playwright install chromium

check-pushed:
	@git fetch --quiet origin
	@ahead=$$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0); \
	 dirty=$$(git status --porcelain); \
	 if [ "$$ahead" -gt 0 ]; then echo "refusing: $$ahead local commit(s) not pushed to origin"; exit 1; fi; \
	 if [ -n "$$dirty" ]; then echo "refusing: working tree has uncommitted changes"; git status --short; exit 1; fi

deploy: check-pushed
	ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'cd $(DEPLOY_DIR) && git pull --ff-only && (cd backend && ./gradlew shadowJar) && docker compose --profile tunnel --profile pois up -d --build'

stop:
	- docker compose -f docker-compose.yml -f docker-compose.local.yml --profile pois down

# Build the offline-refresh image (curl-impersonate baked in). Needed before
# refresh-superchargers / rebuild-superchargers. Idempotent — if the image
# exists, this is a no-op.
refresh-image:
	docker build -t roadtrip-refresh:local -f scripts/Dockerfile.refresh scripts/

# Mint cookies from Safari on this laptop (must be Tailscale-egressing via
# the mini so Akamai binds _abck to the mini's IP), then push to the mini
# and restart the app container.
refresh-cookies:
	@scripts/refresh-cookies-remote.sh "$(DEPLOY_HOST)" "$(DEPLOY_USER)" "$(DEPLOY_DIR)"

# Same flow but writes cookies into THIS repo's .env. Use when iterating on a
# script that runs in local Docker (e.g. fetch_tesla_superchargers.py). Cookies
# are bound to this laptop's egress IP so they only work locally — production
# still needs `make refresh-cookies`.
refresh-cookies-local:
	@scripts/refresh-cookies-local.sh

# Cache-first idempotent rebuild of data/tesla-superchargers.geojson. After a
# full run has populated data/pricing-cache/, this is fast (~30s for ~1300
# cache hits) and produces a deterministic geojson.
rebuild-superchargers: refresh-image
	docker run --rm --env-file .env \
	  -v "$(PWD)/data:/app/data" \
	  -v "$(PWD)/scripts:/app/scripts" \
	  -v "$(PWD)/.env:/app/.env:ro" \
	  roadtrip-refresh:local python3 /app/scripts/fetch_tesla_superchargers.py --no-fetch

# Full network refresh of Tesla data — both the bulk get-locations feed and
# every per-site get-charger-details. Network-bound (~1 req/sec, ~25 min).
# Re-running is safe: cache hits skip the network entirely. The Kotlin
# backend serves whatever this writes to data/pricing-cache/ — Tesla is
# never called from the user request path.
refresh-superchargers: refresh-image
	docker run --rm --env-file .env \
	  -v "$(PWD)/data:/app/data" \
	  -v "$(PWD)/scripts:/app/scripts" \
	  -v "$(PWD)/.env:/app/.env:ro" \
	  roadtrip-refresh:local python3 /app/scripts/fetch_tesla_superchargers.py

# Phase 2 backend: PostGIS Postgres on 127.0.0.1:5432, importer, tests.
pois-up:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d postgres
	@echo "postgres ready on 127.0.0.1:5432 (db=roadtrip user=roadtrip)"

pois-down:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois stop postgres

# Single import entry point. Interactive multi-select picker by default
# (fzf when available, falls back to bash `select`). To skip the picker —
# in CI, scripts, or muscle memory — pass SOURCE=…:
#   make pois-import                    # interactive picker
#   make pois-import SOURCE=uscampgrounds   # headless single source
#   make pois-import SOURCE=all             # headless every source
# See scripts/pois-import-picker.sh for the picker logic and the canonical
# list of source names.
pois-import: pois-up
ifdef SOURCE
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data ./gradlew importer --args="$(SOURCE)"
else
	@scripts/pois-import-picker.sh
endif

# RFC 0004 admin API entry point. Curls the backend's POST endpoint, which
# runs fetcher + importer phases under one target lock and records every
# phase to ingest_runs. Sync — exits with the run's pass/fail status. Set
# ADMIN_BASE to point at a remote deploy (defaults to 127.0.0.1:8765).
#   make pois-refresh TARGET=campgrounds
#   ADMIN_BASE=https://roadtrip.floo.ca make pois-refresh TARGET=planet-fitness
pois-refresh:
ifndef TARGET
	$(error TARGET is required. Example: make pois-refresh TARGET=campgrounds)
endif
	@scripts/admin-curl.sh ingest $(TARGET)

# Local-only Playwright smoke. Hits the Kotlin backend on $(PORT) (serves
# static + all /api routes). Doesn't boot the stack — bring it up first
# (e.g. `make run`). Driven by Playwright JVM in the backend test suite;
# QA_BASE_URL gates the SmokeTest so `gradle test` alone stays fast and
# doesn't pull Chromium.
qa:
	cd backend && ./gradlew installPlaywrightBrowsers
	cd backend && QA_BASE_URL=http://127.0.0.1:$(PORT) ./gradlew test --tests ca.floo.roadtrip.SmokeTest --tests ca.floo.campsite.CampsiteSmokeTest --rerun -x generateJooq

# Point this clone's git at .githooks/ so .githooks/pre-commit runs ktlint on
# staged backend Kotlin files. Per-clone (core.hooksPath isn't tracked in the
# repo), so each contributor runs this once.
install-hooks:
	git config core.hooksPath .githooks
	@echo "git hooks installed (.githooks/pre-commit)"
