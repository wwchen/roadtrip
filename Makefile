.PHONY: help run docker-run deploy deploy-local stop check-pushed refresh-cookies refresh-cookies-local refresh-superchargers rebuild-superchargers pois-up pois-down pois-import pois-import-all pois-test pois-psql backend-build backend-run backend-shell qa install-hooks

SOURCE ?= uscampgrounds

PORT       ?= 8765
DEPLOY_HOST ?= mini-ca
DEPLOY_USER ?= mini
DEPLOY_DIR  ?= ~/workspace/roadtrip

help:
	@echo "Targets:"
	@echo "  make run              Build + run backend locally on 127.0.0.1:$(PORT) (serves static + /api)"
	@echo "  make docker-run       Build+run backend + postgres in Docker on 127.0.0.1:$(PORT)"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, build backend, docker compose up (backend+postgres+tunnel)"
	@echo "  make deploy-local     Build+run backend + postgres + cloudflared here (no ssh)"
	@echo "  make refresh-cookies  Push Tesla cookies from clipboard → $(DEPLOY_HOST) (Tailscale exit node recommended)"
	@echo "  make refresh-cookies-local  Mint cookies into THIS repo's .env (laptop-only egress)"
	@echo "  make rebuild-superchargers  Rebuild geojson from cache, no network (~30s)"
	@echo "  make refresh-superchargers  Full Tesla refresh: bulk feed + per-site (~25 min)"
	@echo "  make backend-build    Build the backend fat-jar via gradle shadowJar"
	@echo "  make backend-run      Build + run backend in Docker against local postgres"
	@echo "  make backend-shell    Exec into the running backend container"
	@echo "  make pois-up          Start Postgres+PostGIS on 127.0.0.1:5432"
	@echo "  make pois-down        Stop Postgres"
	@echo "  make pois-import      Run the Kotlin importer against local Postgres"
	@echo "  make pois-test        Run backend Testcontainers tests"
	@echo "  make pois-psql        psql shell into local Postgres"
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make install-hooks    Point this clone's git hooks at .githooks/ (one-time, per clone)"
	@echo "  make stop             Stop all compose services locally"

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

docker-run: backend-build
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d --build backend postgres
	@echo "http://127.0.0.1:$(PORT)"

check-pushed:
	@git fetch --quiet origin
	@ahead=$$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0); \
	 dirty=$$(git status --porcelain); \
	 if [ "$$ahead" -gt 0 ]; then echo "refusing: $$ahead local commit(s) not pushed to origin"; exit 1; fi; \
	 if [ -n "$$dirty" ]; then echo "refusing: working tree has uncommitted changes"; git status --short; exit 1; fi

deploy: check-pushed
	ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'cd $(DEPLOY_DIR) && git pull --ff-only && (cd backend && ./gradlew shadowJar) && docker compose --profile tunnel --profile pois up -d --build'

deploy-local: backend-build
	docker compose --profile tunnel --profile pois up -d --build

stop:
	- docker compose --profile tunnel --profile pois down
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

pois-import: pois-up
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data ./gradlew importer --args="$(SOURCE)"

pois-import-all: pois-up
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data ./gradlew importer --args="all"

pois-test:
	cd backend && ./gradlew test

pois-psql:
	docker exec -it roadtrip-map-postgres-1 psql -U roadtrip -d roadtrip

# Build the backend fat-jar on the host. jOOQ codegen runs Testcontainers
# against PostGIS, so this needs Docker available too. Output:
# backend/build/libs/roadtrip-backend-*-all.jar (~27 MB).
backend-build:
	cd backend && ./gradlew shadowJar

# Build + run the backend in Docker against local postgres on the compose
# network. backend's port 8765 is exposed to the host via docker-compose.local.yml.
backend-run: backend-build pois-up
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d --build backend
	@echo "backend ready on 127.0.0.1:8765"

backend-shell:
	docker exec -it roadtrip-map-backend-1 sh

# Local-only Playwright smoke. Hits the Kotlin backend on $(PORT) (serves
# static + all /api routes). Doesn't boot the stack — bring it up first
# (e.g. `make backend-run` or `make run`). Driven by Playwright JVM in the
# backend test suite; QA_BASE_URL gates the SmokeTest so `gradle test` alone
# stays fast and doesn't pull Chromium.
qa:
	cd backend && ./gradlew installPlaywrightBrowsers
	cd backend && QA_BASE_URL=http://127.0.0.1:$(PORT) ./gradlew test --tests ca.floo.roadtrip.SmokeTest --rerun -x generateJooq

# Point this clone's git at .githooks/ so .githooks/pre-commit runs ktlint on
# staged backend Kotlin files. Per-clone (core.hooksPath isn't tracked in the
# repo), so each contributor runs this once.
install-hooks:
	git config core.hooksPath .githooks
	@echo "git hooks installed (.githooks/pre-commit)"
