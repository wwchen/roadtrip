.PHONY: help run docker-run deploy deploy-local stop check-pushed refresh-cookies refresh-cookies-local refresh-superchargers rebuild-superchargers pois-up pois-down pois-import pois-test pois-psql

PORT       ?= 8765
DEPLOY_HOST ?= mini-ca
DEPLOY_USER ?= mini
DEPLOY_DIR  ?= ~/workspace/roadtrip

help:
	@echo "Targets:"
	@echo "  make run              Run server.py directly on host (hot edit index.html)"
	@echo "  make docker-run       Build+run the Docker image locally on 127.0.0.1:$(PORT), no tunnel"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, docker compose up"
	@echo "  make deploy-local     Build+run app + cloudflared here (no ssh)"
	@echo "  make refresh-cookies  Push Tesla cookies from clipboard → $(DEPLOY_HOST) (Tailscale exit node recommended)"
	@echo "  make refresh-cookies-local  Mint cookies into THIS repo's .env (laptop-only egress)"
	@echo "  make rebuild-superchargers  Rebuild geojson from cache, no network (~30s)"
	@echo "  make refresh-superchargers  Full Tesla refresh: bulk feed + per-site (~25 min)"
	@echo "  make pois-up          Start Postgres+PostGIS on 127.0.0.1:5432 (Phase 2 backend)"
	@echo "  make pois-down        Stop Postgres"
	@echo "  make pois-import      Run the Kotlin importer against local Postgres"
	@echo "  make pois-test        Run backend Testcontainers tests"
	@echo "  make pois-psql        psql shell into local Postgres"
	@echo "  make stop             Stop all compose services locally"

run:
	PORT=$(PORT) python3 server.py

docker-run:
	CACHE_DIR=$(PWD)/data/pricing-cache docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml up -d --build app
	@echo "http://127.0.0.1:$(PORT)"

check-pushed:
	@git fetch --quiet origin
	@ahead=$$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0); \
	 dirty=$$(git status --porcelain); \
	 if [ "$$ahead" -gt 0 ]; then echo "refusing: $$ahead local commit(s) not pushed to origin"; exit 1; fi; \
	 if [ -n "$$dirty" ]; then echo "refusing: working tree has uncommitted changes"; git status --short; exit 1; fi

deploy: check-pushed
	ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'export PATH=/usr/local/bin:$$PATH; cd $(DEPLOY_DIR) && git pull --ff-only && docker compose --profile tunnel up -d --build'

deploy-local:
	docker compose --profile tunnel up -d --build

stop:
	- docker compose --profile tunnel down
	- docker compose -f docker-compose.yml -f docker-compose.local.yml down

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
# The pois profile is gated off the default `make docker-run` so this only
# fires when you explicitly bring it up.
pois-up:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d postgres
	@echo "postgres ready on 127.0.0.1:5432 (db=roadtrip user=roadtrip)"

pois-down:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois stop postgres

pois-import: pois-up
	cd backend && ROADTRIP_DATA_DIR=$(PWD)/data gradle importer --args="uscampgrounds"

pois-test:
	cd backend && gradle test

pois-psql:
	docker exec -it roadtrip-map-postgres-1 psql -U roadtrip -d roadtrip
