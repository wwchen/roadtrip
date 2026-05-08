.PHONY: help run docker-run deploy deploy-local stop check-pushed

PORT       ?= 8765
DEPLOY_HOST ?= mini-ca
DEPLOY_USER ?= mini
DEPLOY_DIR  ?= ~/workspace/roadtrip

help:
	@echo "Targets:"
	@echo "  make run           Run server.py directly on host (hot edit index.html)"
	@echo "  make docker-run    Build+run the Docker image locally on 127.0.0.1:$(PORT), no tunnel"
	@echo "  make deploy        SSH to $(DEPLOY_HOST), git pull, docker compose up"
	@echo "  make deploy-local  Build+run app + cloudflared here (no ssh)"
	@echo "  make stop          Stop all compose services locally"

run:
	PORT=$(PORT) python3 server.py

docker-run:
	docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.local.yml up -d --build app
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
