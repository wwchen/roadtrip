.PHONY: help run test data-fetch data-import reset-db qa install install-hooks _ensure-hooks recgov-companion recgov-login recgov-refresh recgov-atc grafana-export sandbox sandbox-stop frontend

PORT ?= 8765
RUN_ENV ?= $(or $(env),dev)
DEPLOY_SHA ?=
DEPLOY_BRANCH ?= master
DEPLOY_DATA_SHA ?=
DEPLOY_COMPANION_SHA ?=
RECGOV_ATC_LOCAL_URL ?= http://127.0.0.1:8770
RECGOV_COMPANION_BROWSER_PROFILE ?= $(HOME)/.campsite-companion/browser-session
RECGOV_COMPANION_PROFILE_ENV := COMPANION_BROWSER_PROFILE="$${COMPANION_BROWSER_PROFILE:-$${RECGOV_COMPANION_BROWSER_PROFILE:-$(RECGOV_COMPANION_BROWSER_PROFILE)}}"
LOCAL_COMPOSE_PROFILES ?= --profile pois --profile recgov-companion
# Every stack command runs under `manage.py exec`, which decrypts the vault in
# memory and execs the command with the values in its environment. Compose then
# turns them into /run/secrets file mounts (docker-compose.secrets.yml, itself
# generated from secrets/registry.yaml). No plaintext .env is ever written.
#
# All secrets tooling lives in ./secrets/manage.py; this file deliberately adds
# no secrets targets of its own.
SECRETS := ./secrets/manage.py
SECRETS_FILE := -f docker-compose.secrets.yml
LOCAL_COMPOSE := $(SECRETS) exec local -- docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml $(SECRETS_FILE) $(LOCAL_COMPOSE_PROFILES)

help:
	@echo "Targets:"
	@echo "  make install          One-time host setup: brew deps + companion + git hooks"
	@echo "  make install-hooks    Point this clone's git hooks at .githooks/ (per-clone)"
	@echo "  make run              Run backend locally + Docker Rec.gov companion"
	@echo "  make run env=prod     Pull the CI-built app image + run production Compose profiles"
	@echo "  make recgov-companion Run the Docker one-shot Rec.gov companion"
	@echo "  make recgov-login     Open companion Chromium and verify Recreation.gov login"
	@echo "  make recgov-refresh   Force-refresh the companion Recreation.gov session"
	@echo "  make recgov-atc       Run one Rec.gov add-to-cart attempt (PAYLOAD=/path/to/atc.json)"
	@echo "  make test             Run everything CI runs: backend + lint + frontend + companion + scripts + secrets/dashboards checks"
	@echo "  make data-fetch       Fetch upstream data on the host (TARGET=<data_source slug> for one)."
	@echo "  make data-import      Import data/ files into Postgres (TARGET=<row name> for one). Routes by YAML section (poi_data / campsite_data)."
	@echo "  make reset-db         Drop/recreate the local schema and Flyway history for a full migration replay."
	@echo "  make qa               Playwright smoke against local stack (requires backend up)"
	@echo "  make frontend         Build the React frontend into frontend/dist (tilt up does this too)"
	@echo "  make grafana-export   Snapshot UI-edited dashboards and apply shared links"
	@echo ""
	@echo "Stack startup: \`tilt up\` (full dev) or \`make run\` (host backend + Rec.gov companion)."

run: _ensure-hooks
ifeq ($(RUN_ENV),prod)
	@[ -n "$(DEPLOY_SHA)" ] || { echo "DEPLOY_SHA is required for env=prod" >&2; exit 2; }
	@[ -n "$(DEPLOY_DATA_SHA)" ] || { echo "DEPLOY_DATA_SHA is required for env=prod" >&2; exit 2; }
	@[ -n "$(DEPLOY_COMPANION_SHA)" ] || { echo "DEPLOY_COMPANION_SHA is required for env=prod" >&2; exit 2; }
	scripts/deploy.sh prod "$(DEPLOY_SHA)" "$(DEPLOY_DATA_SHA)" "$(DEPLOY_COMPANION_SHA)" "$(DEPLOY_BRANCH)"
else ifeq ($(RUN_ENV),dev)
	$(MAKE) frontend
	$(LOCAL_COMPOSE) up -d --build postgres recgov-companion
	ROADTRIP_PROFILE=local $(SECRETS) exec local -- ./gradlew :backend:run
else
	$(error unsupported env '$(RUN_ENV)'; use env=dev or env=prod)
endif

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

# One-time host setup for a fresh clone. Brew and the browser install reuse
# existing packages; npm ci recreates the companion tree from the lockfile.
install: install-hooks
	brew install tilt docker openjdk node sops age ruff
	cd companion && npm ci && npx playwright install chromium

# Everything CI runs (see .github/workflows/ci.yml), locally and in one shot:
# backend tests, detekt-rule tests, ktlint + detekt, frontend gates, companion
# tests, script/secrets-tooling tests, the secrets-registry drift check, and
# Grafana dashboard validation. Backend tests need a running Docker daemon
# (Testcontainers). One Gradle invocation shares configuration and compilation
# across the backend checks that CI runs in separate jobs.
test: _ensure-hooks
	./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :detekt-rules:test
	# npm run build includes the TypeScript check before bundling.
	cd frontend && npm ci && npm run lint && npm run test && npm run build && npm run build-storybook
	node scripts/check-color-tokens.mjs
	node scripts/check-css-blocks.mjs
	node scripts/check-token-usage.mjs
	cd companion && npm ci --ignore-scripts && npm test
	ruff check --isolated --target-version py39 --select E4,E7,E9,F,B,UP secrets/ scripts/
	python3 -m unittest discover -s scripts -p 'test_*.py'
	python3 secrets/manage.py generate --check
	python3 secrets/manage.py check
	python3 scripts/validate_grafana_dashboards.py

# Two-step refresh:
#   make data-fetch                       # all targets
#   make data-fetch TARGET=campflare-campgrounds-export    # one source
#   make data-import                      # all targets
#   make data-import TARGET='Planet Fitness'
#
# Fetch runs the repo's Python fetchers on the host and writes data/raw/.
# Backend must be running only for import (e.g. `tilt up` or `make run`).
# Override the host with ADMIN_BASE for remote imports.
ADMIN_BASE ?= http://127.0.0.1:$(PORT)

# poi_data names like `Federal Campgrounds` contain spaces; wrap the URL
# in single quotes and url-encode the path segment so curl gets one arg.
# python3 is the simplest portable url-encoder.
data-fetch:
	$(SECRETS) exec local -- python3 scripts/poll_raw.py $(if $(TARGET),$(TARGET),--all)

data-import:
	curl --fail-with-body -sS --max-time 1800 -X POST '$(ADMIN_BASE)/api/admin/data/import$(if $(TARGET),/$(shell python3 -c "import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=''))" "$(TARGET)"))'

# Hard reset: nuke the postgres data volume and restart the stack.
# Postgres re-initializes from scratch; Flyway re-migrates on backend boot
# (including R__grafana_reader_grants.sql which re-grants grafana_reader).
POSTGRES_DATA ?= $(HOME)/.roadtrip-map/postgres
reset-db:
	$(LOCAL_COMPOSE) rm -sf postgres backend
	rm -rf $(POSTGRES_DATA)
	$(LOCAL_COMPOSE) up -d --build postgres recgov-companion backend

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
frontend:
	cd frontend && npm ci && npm run build

grafana-export:
	./scripts/export_grafana_dashboards.py
	./scripts/sync_grafana_dashboard_links.py

sandbox:
	SANDBOX_SHA=$(or $(SHA),$(shell git rev-parse HEAD)) scripts/deploy.sh sandbox-up $(or $(REF),$(shell git rev-parse --abbrev-ref HEAD)) $(NAME)

sandbox-stop:
	scripts/deploy.sh sandbox-down $(NAME)
