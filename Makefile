.PHONY: help run docker-run deploy deploy-local stop

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

deploy:
	ssh $(DEPLOY_HOST) -l $(DEPLOY_USER) 'export PATH=/usr/local/bin:$$PATH; cd $(DEPLOY_DIR) && git pull --ff-only && docker compose --profile tunnel up -d --build'

deploy-local:
	docker compose --profile tunnel up -d --build

stop:
	- docker compose --profile tunnel down
	- docker compose -f docker-compose.yml -f docker-compose.local.yml down
