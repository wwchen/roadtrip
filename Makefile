.PHONY: help run deploy stop check-pushed refresh-cookies refresh-cookies-local refresh-superchargers data-fetch data-import qa install install-hooks companion

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
	@echo "  make data-fetch       Fetch upstream POI data into data/ (TARGET=<name> for one)"
	@echo "  make data-import      Import data/ files into Postgres (TARGET=<name> for one)"
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make stop             Stop all compose services locally"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, build backend, docker compose up (backend+postgres+tunnel)"
	@echo "  make refresh-cookies  Push Tesla cookies from clipboard → $(DEPLOY_HOST) (Tailscale exit node recommended)"
	@echo "  make refresh-cookies-local  Mint cookies into THIS repo's .env (laptop-only egress)"
	@echo "  make refresh-superchargers  Full Tesla refresh: bulk index + per-slug detail (RFC 0007 raw captures)"
	@echo ""
	@echo "Stack startup: \`tilt up\` (full dev) or \`make run\` (backend only)."

# Run the backend on the host, serving static + /api. Brings up Postgres
# in Docker first (idempotent — `compose up -d` is a no-op if already
# running). The backend serves index.html, /web/*, /data/* (excluding
# pricing-cache, which is exposed only via /api/pricing/{slug}), plus all
# four /api/* routes.
run:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml --profile pois up -d postgres
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
# refresh-superchargers. Idempotent — if the image
# exists, this is a no-op.
refresh-image:
	docker build -t roadtrip-refresh:local -f scripts/Dockerfile.refresh scripts/

# Mint cookies from Safari on this laptop (must be Tailscale-egressing via
# the mini so Akamai binds _abck to the mini's IP), then push to the mini
# and restart the app container.
refresh-cookies:
	@scripts/refresh-cookies-remote.sh "$(DEPLOY_HOST)" "$(DEPLOY_USER)" "$(DEPLOY_DIR)"

# Same flow but writes cookies into THIS repo's .env. Use when iterating on a
# script that runs in local Docker (e.g. fetch_tesla_index.py). Cookies
# are bound to this laptop's egress IP so they only work locally — production
# still needs `make refresh-cookies`.
refresh-cookies-local:
	@scripts/refresh-cookies-local.sh

# Full network refresh of Tesla data — bulk get-locations + per-slug
# get-charger-details. Network-bound (~1 req/sec). Cache-first behavior
# lives in fetch_tesla_locations.py (--max-age-days controls freshness).
# RFC 0007: writes envelope-wrapped raw to data/raw/tesla-{index,locations}/.
refresh-superchargers: refresh-image
	docker run --rm --env-file .env \
	  -v "$(PWD)/data:/app/data" \
	  -v "$(PWD)/scripts:/app/scripts" \
	  -v "$(PWD)/.env:/app/.env:ro" \
	  roadtrip-refresh:local sh -c \
	  "python3 /app/scripts/fetch_tesla_index.py && python3 /app/scripts/fetch_tesla_locations.py"

# Two-step refresh through the backend's admin API (RFC 0004 / issue #44):
#   make data-fetch                       # all targets
#   make data-fetch TARGET=campgrounds    # one target
#   make data-import                      # all targets
#   make data-import TARGET=planet-fitness
#
# Backend must be running (e.g. `tilt up` or `make run`). Per-target mutex
# means a fetch and an import on the same target serialize. Override the
# host with ADMIN_BASE for remote deploys (e.g. ADMIN_BASE=https://… make data-fetch).
ADMIN_BASE ?= http://127.0.0.1:$(PORT)

data-fetch:
	curl --fail-with-body -sS --max-time 1800 -X POST $(ADMIN_BASE)/api/admin/data/fetch$(if $(TARGET),/$(TARGET))

data-import:
	curl --fail-with-body -sS --max-time 1800 -X POST $(ADMIN_BASE)/api/admin/data/import$(if $(TARGET),/$(TARGET))

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
