.PHONY: help run data-fetch data-import reset-db qa install install-hooks _ensure-hooks companion recgov-companion recgov-login recgov-refresh recgov-atc grafana-export

PORT       ?= 8765
BACKEND_IMAGE ?= roadtrip/backend
RUN_ENV ?= $(or $(env),dev)
POSTGRES_DB ?= roadtrip
POSTGRES_USER ?= roadtrip
POSTGRES_PASSWORD ?= roadtrip
RECGOV_ATC_DOCKER_URL ?= http://recgov-companion:8770
RECGOV_ATC_LOCAL_URL ?= http://127.0.0.1:8770
RECGOV_COMPANION_BROWSER_PROFILE ?= $(HOME)/.campsite-companion/browser-session
RECGOV_COMPANION_PROFILE_ENV := COMPANION_BROWSER_PROFILE="$${COMPANION_BROWSER_PROFILE:-$${RECGOV_COMPANION_BROWSER_PROFILE:-$(RECGOV_COMPANION_BROWSER_PROFILE)}}"
PROD_COMPOSE_PROFILES ?= --profile tunnel --profile pois --profile recgov-companion
LOCAL_COMPOSE_PROFILES ?= --profile pois --profile recgov-companion
PROD_COMPOSE := docker compose $(PROD_COMPOSE_PROFILES)
LOCAL_COMPOSE := docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml $(LOCAL_COMPOSE_PROFILES)
OBSERVABILITY_SERVICES := grafana alloy tempo prometheus

help:
	@echo "Targets:"
	@echo "  make install          One-time host setup: brew deps + companion + git hooks"
	@echo "  make install-hooks    Point this clone's git hooks at .githooks/ (per-clone)"
	@echo "  make run              Run backend locally + Docker Rec.gov companion"
	@echo "  make run env=prod     Build backend/companion images + run production Compose profiles"
	@echo "  make companion        Run the Rec.gov companion HTTP executor on host"
	@echo "  make recgov-companion Run the Docker one-shot Rec.gov companion"
	@echo "  make recgov-login     Open companion Chromium and verify Recreation.gov login"
	@echo "  make recgov-refresh   Force-refresh the companion Recreation.gov session"
	@echo "  make recgov-atc       Run one Rec.gov add-to-cart attempt (PAYLOAD=/path/to/atc.json)"
	@echo "  make data-fetch       Fetch upstream data via admin API (TARGET=<data_source slug> for one)."
	@echo "  make data-import      Import data/ files into Postgres (TARGET=<row name> for one). Routes by YAML section (poi_data / reservable_data / poi_reservable_joiner)."
	@echo "  make reset-db         Drop/recreate the local schema and Flyway history for a full migration replay."
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make grafana-export   Snapshot UI-edited dashboards and apply shared links"
	@echo ""
	@echo "Stack startup: \`tilt up\` (full dev) or \`make run\` (backend only)."

# Plain `make run` runs the backend on the host for local dev. `make run
# env=prod` builds the container image and rolls out the production Compose
# stack on the deploy host.
run: _ensure-hooks
ifeq ($(RUN_ENV),prod)
	./gradlew :backend:shadowJar
	docker build -t $(BACKEND_IMAGE) --target backend .
	RECGOV_ATC_COMPANION_BASE_URL=$${RECGOV_ATC_COMPANION_BASE_URL:-$(RECGOV_ATC_DOCKER_URL)} $(PROD_COMPOSE) build recgov-companion
	# `up -d` recreates only what changed: the rebuilt backend (new image id)
	# and any service whose `.env`-sourced config moved. Postgres/Loki/Alloy
	# keep running, so a code deploy no longer bounces the database.
	RECGOV_ATC_COMPANION_BASE_URL=$${RECGOV_ATC_COMPANION_BASE_URL:-$(RECGOV_ATC_DOCKER_URL)} $(PROD_COMPOSE) up -d
	# Grafana, Alloy, Tempo, and Prometheus bind-mount config, so `up -d` won't
	# reload those files. Provisioned dashboards poll dashboard JSON, but
	# datasource, telemetry pipeline, trace, and metric config need restarts.
	$(PROD_COMPOSE) restart $(OBSERVABILITY_SERVICES)
else ifeq ($(RUN_ENV),dev)
	$(LOCAL_COMPOSE) up -d --build postgres recgov-companion
	RECGOV_ATC_COMPANION_BASE_URL=$${RECGOV_ATC_COMPANION_BASE_URL:-$(RECGOV_ATC_LOCAL_URL)} ROADTRIP_PROFILE=local ./gradlew :backend:run
else
	$(error unsupported env '$(RUN_ENV)'; use env=dev or env=prod)
endif

companion: _ensure-hooks
	cd companion && npm start

recgov-companion: _ensure-hooks
	$(LOCAL_COMPOSE) up -d --build recgov-companion
	@echo "Rec.gov companion listening on $(RECGOV_ATC_LOCAL_URL)"

recgov-login: _ensure-hooks
	cd companion && $(RECGOV_COMPANION_PROFILE_ENV) npm run recgov:login

recgov-refresh: _ensure-hooks
	cd companion && $(RECGOV_COMPANION_PROFILE_ENV) npm run recgov:refresh

recgov-atc: _ensure-hooks
	@if [ -z "$(PAYLOAD)" ]; then echo "Usage: make recgov-atc PAYLOAD=/path/to/atc.json"; exit 2; fi
	cd companion && $(RECGOV_COMPANION_PROFILE_ENV) npm run --silent recgov:atc -- --payload-file "$(PAYLOAD)"

# One-time host setup for a fresh clone. Idempotent: brew is no-op when
# packages are present, npm install + playwright install are no-op when the
# lockfile and browser cache are unchanged, install-hooks just rewrites
# .git/config.
install: install-hooks
	brew install tilt docker openjdk node
	cd companion && npm install && npx playwright install chromium

# Two-step refresh through the backend's admin API (RFC 0004 / issue #44):
#   make data-fetch                       # all targets
#   make data-fetch TARGET=campgrounds    # one target
#   make data-import                      # all targets
#   make data-import TARGET=planet-fitness
#
# Backend must be running (e.g. `tilt up` or `make run`). Per-target mutex
# means a fetch and an import on the same target serialize. Override the
# host with ADMIN_BASE for remote deploys (e.g. ADMIN_BASE=https://… make data-fetch).
ADMIN_PORT ?= 8766
ADMIN_BASE ?= http://127.0.0.1:$(ADMIN_PORT)

# poi_data names like `Federal Campgrounds` contain spaces; wrap the URL
# in single quotes and url-encode the path segment so curl gets one arg.
# python3 is the simplest portable url-encoder; falls back to the bare
# value when TARGET is unset.
data-fetch:
	curl --fail-with-body -sS --max-time 1800 -X POST '$(ADMIN_BASE)/api/admin/data/fetch$(if $(TARGET),/$(shell python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=''))" "$(TARGET)"))'

data-import:
	curl --fail-with-body -sS --max-time 1800 -X POST '$(ADMIN_BASE)/api/admin/data/import$(if $(TARGET),/$(shell python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=''))" "$(TARGET)"))'

# Hard reset: nuke the postgres data volume and restart the stack.
# Postgres re-initializes from scratch; Flyway re-migrates on backend boot
# (including R__grafana_reader_grants.sql which re-grants grafana_reader).
POSTGRES_DATA ?= $(HOME)/.roadtrip-map/postgres
DC := $(LOCAL_COMPOSE)
reset-db:
	$(DC) rm -sf postgres backend
	rm -rf $(POSTGRES_DATA)
	$(DC) up -d --build postgres recgov-companion backend

# Local-only Playwright smoke. Hits the Kotlin backend on $(PORT) (serves
# static + all /api routes). Doesn't boot the stack — bring it up first
# (e.g. `make run`). Runs the dedicated `smokeTest` source set (Playwright JVM);
# QA_BASE_URL gates SmokeTest so it skips when the server isn't up.
qa: _ensure-hooks
	./gradlew :backend:installPlaywrightBrowsers
	QA_BASE_URL=http://127.0.0.1:$(PORT) ./gradlew :backend:smokeTest --rerun -x :backend:generateJooq

# Point this clone's git at .githooks/ so .githooks/pre-commit runs ktlint on
# staged backend Kotlin files (and pre-push runs backend tests). Per-clone
# (core.hooksPath isn't tracked in the repo). Common dev targets depend on
# _ensure-hooks and `tilt up` sets it too, so this is rarely needed by hand.
install-hooks:
	git config core.hooksPath .githooks
	@echo "git hooks installed (.githooks/pre-commit)"

# Idempotent, quiet auto-install used as a prerequisite of the dev targets so a
# fresh clone can't skip the hooks. Only rewrites config when it isn't already
# pointed at .githooks; never fails the build outside a git work tree.
_ensure-hooks:
	@[ "$$(git config core.hooksPath 2>/dev/null)" = ".githooks" ] || \
	  { git config core.hooksPath .githooks 2>/dev/null && echo "git hooks installed (.githooks/)"; } || true

# Snapshot Grafana dashboards from the running container into
# grafana/dashboards/*.json, then apply shared dashboard navigation links.
# Workflow: edit in the UI (allowUiUpdates=true in dev), then
# `make grafana-export` before committing. UID, password, and UID list
# overridable via env vars; see scripts/export_grafana_dashboards.py.
grafana-export:
	./scripts/export_grafana_dashboards.py
	./scripts/sync_grafana_dashboard_links.py
