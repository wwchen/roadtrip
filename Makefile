.PHONY: help run deploy stop check-pushed fetch-tesla-supercharger-pricing data-fetch data-import poll-raw qa install install-hooks companion

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
	@echo "  make poll-raw         Pick a fetcher via fzf, run it, print the raw-cache path (RFC 0007). SOURCE=<name> or --all to skip the picker."
	@echo "  make data-fetch       Fetch upstream data via admin API (TARGET=<data_source slug> for one). Wraps the same fetchers as poll-raw."
	@echo "  make data-import      Import data/ files into Postgres (TARGET=<row name> for one). Routes by YAML section (poi_data / reservable_data / poi_reservable_joiner)."
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make stop             Stop all compose services locally"
	@echo "  make deploy           SSH to $(DEPLOY_HOST), git pull, build backend, docker compose up (backend+postgres+tunnel)"
	@echo "  make fetch-tesla-supercharger-pricing  Mint Tesla cookies → smoke-test → bulk index + per-slug detail (interactive, loops on 403/429)"
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

# End-to-end Tesla Supercharger pricing fetch: build the curl-impersonate
# image (no-op if cached), mint cookies, smoke-test, walk bulk index +
# per-slug detail. Loops on 403/429. Akamai fingerprints stock OpenSSL
# curl, so the image bundles curl-impersonate-chrome — that's why this is
# a Docker'd flow at all.
fetch-tesla-supercharger-pricing:
	@scripts/fetch-tesla-supercharger-pricing.sh

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

# poi_data names like `Federal Campgrounds` contain spaces; wrap the URL
# in single quotes and url-encode the path segment so curl gets one arg.
# python3 is the simplest portable url-encoder; falls back to the bare
# value when TARGET is unset.
data-fetch:
	curl --fail-with-body -sS --max-time 1800 -X POST '$(ADMIN_BASE)/api/admin/data/fetch$(if $(TARGET),/$(shell python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=''))" "$(TARGET)"))'

data-import:
	curl --fail-with-body -sS --max-time 1800 -X POST '$(ADMIN_BASE)/api/admin/data/import$(if $(TARGET),/$(shell python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=''))" "$(TARGET)"))'

# RFC 0007 raw poller. One entry point for every thin fetcher; uses fzf
# to pick a source unless SOURCE=<name> or SOURCE=--all is set. Prints
# the data/raw/<source>/<ts>.json path each fetcher writes.
#
#   make poll-raw                  # fzf picker (interactive)
#   make poll-raw SOURCE=osm-pf    # one source
#   make poll-raw SOURCE=--all     # everything in registry order
#   make poll-raw SOURCE=--list    # JSON registry, no fetch
poll-raw:
	@python3 scripts/poll_raw.py $(SOURCE)

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
