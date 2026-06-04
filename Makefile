.PHONY: help run docker-run deploy deploy-local stop check-pushed refresh-cookies refresh-cookies-local refresh-superchargers rebuild-superchargers pois-up pois-down pois-import pois-import-all pois-test pois-psql backend-build backend-run backend-shell qa

SOURCE ?= uscampgrounds

PORT       ?= 8765
DEPLOY_HOST ?= mini-ca
DEPLOY_USER ?= mini
DEPLOY_DIR  ?= ~/workspace/roadtrip

help:
	@echo "Targets:"
	@echo "  make run              Run server.py directly on host (hot edit index.html)"
	@echo "  make docker-run       Build+run app + postgres + backend locally on 127.0.0.1:$(PORT)"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, build backend, docker compose up (3 services + tunnel)"
	@echo "  make deploy-local     Build+run all services + cloudflared here (no ssh)"
	@echo "  make refresh-cookies  Push Tesla cookies from clipboard → $(DEPLOY_HOST) (Tailscale exit node recommended)"
	@echo "  make refresh-cookies-local  Mint cookies into THIS repo's .env (laptop-only egress)"
	@echo "  make rebuild-superchargers  Rebuild geojson from cache, no network (~30s)"
	@echo "  make refresh-superchargers  Full Tesla refresh: bulk feed + per-site (~25 min)"
	@echo "  make backend-build    Build the backend fat-jar via gradle shadowJar"
	@echo "  make backend-run      Build + run backend in Docker against local postgres"
	@echo "  make backend-shell    Exec into the running backend container"
	@echo "  make pois-up          Start Postgres+PostGIS on 127.0.0.1:5432 (Phase 2 backend)"
	@echo "  make pois-down        Stop Postgres"
	@echo "  make pois-import      Run the Kotlin importer against local Postgres"
	@echo "  make pois-test        Run backend Testcontainers tests"
	@echo "  make pois-psql        psql shell into local Postgres"
	@echo "  make qa               Playwright smoke against local stack (requires server.py + backend up)"
	@echo "  make stop             Stop all compose services locally"

run:
	PORT=$(PORT) python3 server.py

docker-run: backend-build
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d --build app backend postgres
	@echo "http://127.0.0.1:$(PORT)"
	@echo "backend (host port-forwarded only via docker-compose.local.yml): backend → 127.0.0.1:8080"

check-pushed:
	@git fetch --quiet origin
	@ahead=$$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0); \
	 dirty=$$(git status --porcelain); \
	 if [ "$$ahead" -gt 0 ]; then echo "refusing: $$ahead local commit(s) not pushed to origin"; exit 1; fi; \
	 if [ -n "$$dirty" ]; then echo "refusing: working tree has uncommitted changes"; git status --short; exit 1; fi

deploy: check-pushed
	ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'export PATH=/usr/local/bin:/opt/homebrew/bin:$$PATH; cd $(DEPLOY_DIR) && git pull --ff-only && (cd backend && gradle shadowJar) && docker compose --profile tunnel --profile pois up -d --build'

deploy-local: backend-build
	docker compose --profile tunnel --profile pois up -d --build

stop:
	- docker compose --profile tunnel --profile pois down
	- docker compose -f docker-compose.yml -f docker-compose.local.yml --profile pois down

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
rebuild-superchargers:
	docker run --rm --env-file .env \
	  -v "$(PWD)/data:/app/data" \
	  -v "$(PWD)/scripts:/app/scripts" \
	  -v "$(PWD)/server.py:/app/server.py" \
	  roadtrip-map:local python3 /app/scripts/fetch_tesla_superchargers.py --no-fetch

# Full network refresh of Tesla data — both the bulk get-locations feed and
# every per-site get-charger-details. Network-bound (~1 req/sec, ~25 min).
# Re-running is safe: cache hits skip the network entirely. Tomorrow you can
# just `make refresh-superchargers` and walk away.
refresh-superchargers:
	docker run --rm --env-file .env \
	  -v "$(PWD)/data:/app/data" \
	  -v "$(PWD)/scripts:/app/scripts" \
	  -v "$(PWD)/server.py:/app/server.py" \
	  roadtrip-map:local python3 /app/scripts/fetch_tesla_superchargers.py

# Phase 2 backend: PostGIS Postgres on 127.0.0.1:5432, importer, tests.
pois-up:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d postgres
	@echo "postgres ready on 127.0.0.1:5432 (db=roadtrip user=roadtrip)"

pois-down:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois stop postgres

pois-import: pois-up
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data gradle importer --args="$(SOURCE)"

pois-import-all: pois-up
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data gradle importer --args="all"

pois-test:
	cd backend && gradle test

pois-psql:
	docker exec -it roadtrip-map-postgres-1 psql -U roadtrip -d roadtrip

# Build the backend fat-jar on the host. jOOQ codegen runs Testcontainers
# against PostGIS, so this needs Docker available too. Output:
# backend/build/libs/roadtrip-backend-*-all.jar (~27 MB).
backend-build:
	cd backend && gradle shadowJar

# Build + run the backend in Docker against local postgres on the compose
# network. backend's port 8080 is exposed to the host via docker-compose.local.yml.
backend-run: backend-build pois-up
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d --build backend
	@echo "backend ready on 127.0.0.1:8080"

backend-shell:
	docker exec -it roadtrip-map-backend-1 sh

# Local-only Playwright smoke. Hits server.py on $(PORT) which proxies /api/pois
# to the Kotlin backend on 8080. Doesn't boot the stack — bring it up first
# (e.g. `make backend-run` + `make run` in another shell).
qa:
	cd qa && npm install --no-audit --no-fund
	cd qa && npx playwright install chromium
	cd qa && QA_BASE_URL=http://127.0.0.1:$(PORT) npm test
