# apps/web on mini-ca Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/web` runs as a production container on mini-ca against mini-ca's Postgres, ready
for a Cloudflare tunnel rule to send it `roadtrip.floo.ca`'s read API.

**Architecture:** A `web` target in the root `Dockerfile` builds Next.js's standalone output. CI
publishes `web:<sha>` beside `backend:<sha>`; `deploy.sh` pulls it and starts a `web` Compose
service (own profile) that connects to `postgres:5432` as the read-only `roadtrip_web` role,
created the way `grafana_reader` is. Secrets arrive as files through the `_FILE` convention.

**Tech Stack:** Next.js 16 standalone, Node 22, Docker Compose, Flyway + Drizzle migrations,
SOPS secrets registry, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-04-web-on-mini-ca-design.md`

## Global Constraints

- Role name `roadtrip_web`; `default_transaction_read_only = on`; `statement_timeout = 10s`;
  `SELECT` on exactly `WEB_READ_TABLES` (`apps/web/src/server/repo/webReadGrants.ts`).
- Container: port 3000, no published host ports, `mem_limit: 512m`, profile `web` only.
- Image tag `ghcr.io/wwchen/roadtrip/web:<sha>`, built from the root `Dockerfile`, target `web`.
- No secret is a container environment variable: `DATABASE_PASSWORD_FILE` and
  `MAPBOX_TOKEN_FILE` point at `/run/secrets/web_db_password` and `/run/secrets/mapbox_token`.
- Tunnel path regex, exactly:
  `^/api/(pois(/(search|on-route|[0-9]+))?|campgrounds/(search|details)|geocode|route)$`
- Never `git add -A`; stage explicit paths. Write no code comments. No inline magic constants.
- Never remove containers by name filter; the smoke test removes its container by id.

## Review Focus

1. A path the tunnel regex sends to Next.js that `apps/web` does not serve, or a Kotlin-only path
   it captures (`/api/pois/{id}/campsites`, `/api/pois/availability/stays`): each must stay on
   Kotlin. Pinned by Task 6's regex test.
2. The standalone image missing a runtime file the dev build had (a workspace package, the
   tz-lookup data, `public/`): the container starts but a route 500s. Pinned by Task 3's smoke
   test, which requests search and a campground detail.
3. A database password containing URL-reserved characters (`@`, `:`, `/`): the URL must encode it.
   Pinned in Task 1.
4. `web` starting before Flyway has applied its grants on a fresh deploy: reads fail with
   permission errors. Pinned by Task 5's compose test (`depends_on: backend: service_healthy`).
5. A PR sandbox deploy starting `web` and pulling a tag it never built. Pinned by Task 5's compose
   test (profile `web` only, absent from the sandbox invocation).

---

### Task 1: Secrets as files in `apps/web` config

**Files:**
- Modify: `apps/web/src/server/config.ts`
- Test: `apps/web/src/server/config.test.ts`

**Interfaces:**
- Produces: `config.databaseUrl` (unchanged signature) returns `DATABASE_URL` with the password
  from `DATABASE_PASSWORD_FILE` set on it when that variable is present; `config.mapboxToken`
  (unchanged signature) reads `MAPBOX_TOKEN_FILE` when present. Errors are `ConfigError`.

- [ ] **Step 1: Write the failing tests** in `config.test.ts`, using a temp directory
  (`fs.mkdtempSync(os.tmpdir())`) for the files:
  - `'DATABASE_PASSWORD_FILE puts the file's password on DATABASE_URL, URL-encoded'`:
    `DATABASE_URL=postgres://roadtrip_web@postgres:5432/roadtrip`, file content `"p@ss:w/rd\n"` →
    `config.databaseUrl === 'postgres://roadtrip_web:p%40ss%3Aw%2Frd@postgres:5432/roadtrip'`.
  - `'MAPBOX_TOKEN_FILE supplies the Mapbox token'`: file `"pk.test\n"`, `MAPBOX_TOKEN` unset →
    `config.mapboxToken === 'pk.test'`.
  - `'a value and its _FILE together are a ConfigError'`: `MAPBOX_TOKEN=x` plus
    `MAPBOX_TOKEN_FILE` → throws `ConfigError` matching `/MAPBOX_TOKEN_FILE/`;
    `DATABASE_URL=postgres://u:p@h/d` plus `DATABASE_PASSWORD_FILE` → throws matching
    `/DATABASE_PASSWORD_FILE/`.
  - `'an unreadable or empty _FILE is a ConfigError naming the variable'`: a missing path and an
    empty file each throw matching `/DATABASE_PASSWORD_FILE/`.

- [ ] **Step 2: Run them and watch them fail**

Run: `npx vitest run src/server/config.test.ts` (in `apps/web`)
Expected: the four new tests FAIL (no `_FILE` support); existing tests pass.

- [ ] **Step 3: Implement** in `config.ts`: constants `DATABASE_PASSWORD_FILE_VARIABLE`,
  `MAPBOX_TOKEN_FILE_VARIABLE`; a `fileSecretFrom(variable: string): string | undefined` that
  reads the file with `readFileSync`, trims trailing newlines, and throws `ConfigError` for an
  unreadable or empty file. Set the password with `new URL(raw)` and `url.password = …`, which
  percent-encodes. A password already in `DATABASE_URL` plus the file is a `ConfigError`.

- [ ] **Step 4: Run the config tests and the whole web suite**

Run: `npx vitest run src/server/config.test.ts && npm run test --workspace @roadtrip/web`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/server/config.ts apps/web/src/server/config.test.ts
git commit -m "feat(web): read the database password and Mapbox token from files"
```

### Task 2: The `roadtrip_web` role on mini-ca's Postgres

**Files:**
- Create: `backend/src/main/resources/db/migration/R__web_reader_grants.sql`
- Create: `packages/db/migrations/0002_web_reader.sql` (via
  `npx drizzle-kit generate --custom --name=web_reader` in `packages/db`, which also writes the
  journal entry and snapshot)
- Create: `postgres-init/20-web-reader.sh`
- Create: `scripts/test_web_reader_init.py`
- Modify: `apps/web/src/server/repo/webReadGrants.test.ts`

**Interfaces:**
- Consumes: `WEB_READ_TABLES`, `WEB_READ_ROLE` from `webReadGrants.ts`.
- Produces: a role `roadtrip_web` that Flyway (Kotlin boot) and Drizzle migrate both create
  without a password and grant; `postgres-init/20-web-reader.sh`, run with
  `WEB_DB_PASSWORD_FILE`, `POSTGRES_USER`, `POSTGRES_DB` set, creates it or resets its password.

- [ ] **Step 1: Write the failing tests** in `webReadGrants.test.ts`:
  - Replace the hand-written role setup: `beforeAll` executes the text of
    `R__web_reader_grants.sql` (`sql.raw`), then `ALTER ROLE roadtrip_web PASSWORD '<test>'`, and
    connects as `roadtrip_web`. The existing `'every read the app makes succeeds…'` test now runs
    against the real migration.
  - `'the Flyway migration grants SELECT on exactly WEB_READ_TABLES'`: parse
    `/GRANT SELECT ON ([^;]+) TO roadtrip_web/` from the file; the sorted list equals sorted
    `WEB_READ_TABLES`.
  - `'the Drizzle mirror runs the same statements as the Flyway migration'`: both files, with
    `--` comments, `--> statement-breakpoint` and whitespace runs removed, are equal.
  - `'the role is read-only with a 10s statement timeout'`: as the reader,
    `SHOW default_transaction_read_only` is `on`, `SHOW statement_timeout` is `10s`, and an
    `INSERT INTO pois …` rejects with `/read-only transaction/`.

- [ ] **Step 2: Run them and watch them fail**

Run: `npx vitest run src/server/repo/webReadGrants.test.ts` (in `apps/web`)
Expected: FAIL: the migration file does not exist.

- [ ] **Step 3: Write `R__web_reader_grants.sql`**, modelled on `R__grafana_reader_grants.sql`:
  create `roadtrip_web` `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS` if
  absent (no password); `ALTER ROLE roadtrip_web SET default_transaction_read_only = on` and
  `SET statement_timeout = '10s'`; `GRANT CONNECT` on the current database (through `format`, as
  grafana's does), `GRANT USAGE ON SCHEMA public`, and one
  `GRANT SELECT ON <WEB_READ_TABLES joined by ", "> TO roadtrip_web;`. Then
  `0002_web_reader.sql` with the same statements, split by `--> statement-breakpoint`.

- [ ] **Step 4: Run the grants test**

Run: `npx vitest run src/server/repo/webReadGrants.test.ts`
Expected: PASS, including the existing doc check.

- [ ] **Step 5: Write the init-script tests** in `scripts/test_web_reader_init.py` (unittest),
  with a stub `psql` on `PATH` that records its arguments and stdin to a file:
  - `test_no_password_mounted_skips_without_touching_postgres`: `WEB_DB_PASSWORD_FILE` unset →
    exit 0, stdout contains `web-reader: no password mounted; skipping`, stub never called.
  - `test_a_password_creates_or_resets_the_role`: a password file → exit 0, stub called with
    `--set=ON_ERROR_STOP=1`, `--variable=web_user=roadtrip_web`,
    `--variable=web_password=<value>`, and stdin contains `CREATE ROLE` and `ALTER ROLE`.

Run: `python3 -m unittest scripts.test_web_reader_init -v`
Expected: FAIL: the script does not exist.

- [ ] **Step 6: Write `postgres-init/20-web-reader.sh`**, modelled on `10-grafana-reader.sh`
  (`set -euo pipefail`, psql variables, `DO $$ … CREATE ROLE … ELSE ALTER ROLE … PASSWORD`), with
  `WEB_DB_USER` defaulting to `roadtrip_web`, and the skip branch when `WEB_DB_PASSWORD_FILE` is
  unset or its file is empty. `chmod +x`.

- [ ] **Step 7: Run the script tests and the schema gates**

Run: `python3 -m unittest scripts.test_web_reader_init -v && npm run test --workspace @roadtrip/db && node --test scripts/schema-equivalence.test.mjs`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/R__web_reader_grants.sql packages/db/migrations/0002_web_reader.sql packages/db/migrations/meta/_journal.json packages/db/migrations/meta/0002_snapshot.json postgres-init/20-web-reader.sh scripts/test_web_reader_init.py apps/web/src/server/repo/webReadGrants.test.ts
git commit -m "feat(db): roadtrip_web, the read-only role apps/web connects as"
```

### Task 3: The standalone image and its smoke test

**Files:**
- Modify: `Dockerfile` (new targets `web-build`, `web`)
- Modify: `.dockerignore`
- Modify: `apps/web/next.config.ts`, `apps/web/src/next-config.test.ts`
- Create: `apps/web/src/server/webImage.smoke.test.ts`

**Interfaces:**
- Consumes: Task 1's `DATABASE_PASSWORD_FILE`; Task 2's `R__web_reader_grants.sql`;
  `startCatalogFixture()`, `NEAR_NAME`, `NEAR_POI_ID`, `ENABLED_PROVIDER` from
  `src/server/testing/catalogFixture.ts`.
- Produces: `docker buildx build --target web` yields an image that serves on `PORT` (default
  3000); build args `BUILD_ENV`, `BUILD_SHA`, `BUILD_BRANCH`. The smoke test runs only when
  `WEB_IMAGE` names an image.

- [ ] **Step 1: Write the failing tests.** In `next-config.test.ts`:
  `'the build emits a standalone server'` → `config.output === 'standalone'`. In
  `webImage.smoke.test.ts`, `describe.skipIf(!process.env.WEB_IMAGE)`:
  - `beforeAll`: start the catalog fixture; apply `R__web_reader_grants.sql` and set a password;
    write it to a temp dir as `web_db_password`; `docker run -d --add-host=host.docker.internal:host-gateway -p 127.0.0.1:<free port>:3000 -v <dir>:/run/secrets:ro -e DATABASE_URL=postgres://roadtrip_web@host.docker.internal:<fixture port>/<fixture db> -e DATABASE_PASSWORD_FILE=/run/secrets/web_db_password -e ENABLED_DATA_PROVIDERS=<ENABLED_PROVIDER> $WEB_IMAGE`,
    keeping the container id; poll `/api/health` until 200, up to `SMOKE_STARTUP_MS = 60_000`.
  - `'the image answers place search from the database'`: `/api/pois/search?q=Near` → 200,
    results include `NEAR_NAME`.
  - `'the image serves a campground detail'`: `/api/pois/<NEAR_POI_ID>` → 200, `type: 'Feature'`.
  - `afterAll`: `docker rm -f <container id>`, then stop the fixture.

- [ ] **Step 2: Run them and watch the config test fail**

Run: `npx vitest run src/next-config.test.ts` (in `apps/web`)
Expected: FAIL: `output` is undefined. (The smoke test is skipped without `WEB_IMAGE`.)

- [ ] **Step 3: Implement.** `output: 'standalone'` in `next.config.ts`. In `Dockerfile`:
  `web-build` (`node:22-alpine`, following the existing `frontend` stage's pattern of copying only
  what the build reads: `package.json`, `package-lock.json`, `tsconfig.base.json`, `scripts/`,
  `packages/domain/`, `packages/db/`, `frontend/`, `apps/web/`, `data/us-states.geojson`; then
  `npm ci --workspace @roadtrip/web --workspace roadtrip-frontend --no-audit --no-fund`,
  `ARG BUILD_ENV BUILD_SHA BUILD_BRANCH`, and `npm run build` for `@roadtrip/domain`,
  `@roadtrip/db`, `roadtrip-frontend`, `@roadtrip/web` in that order) and `web` (`node:22-alpine`,
  `WORKDIR /app`, copies `apps/web/.next/standalone/` to `/app`, `apps/web/.next/static` to
  `/app/apps/web/.next/static`, `apps/web/public` to `/app/apps/web/public`; `USER node`;
  `ENV PORT=3000 HOSTNAME=0.0.0.0 NODE_ENV=production`; `CMD ["node", "apps/web/server.js"]`).
  Add `apps/*/node_modules`, `apps/web/.next`, `apps/web/public` to `.dockerignore`.

- [ ] **Step 4: Build the image and run the smoke test**

Run: `docker buildx build --target web -t roadtrip/web --load . && cd apps/web && WEB_IMAGE=roadtrip/web npx vitest run src/server/webImage.smoke.test.ts src/next-config.test.ts`
Expected: PASS, three tests. Then `npm run test --workspace @roadtrip/web` → PASS with the smoke
file skipped.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore apps/web/next.config.ts apps/web/src/next-config.test.ts apps/web/src/server/webImage.smoke.test.ts
git commit -m "feat(web): a standalone production image, smoke-tested against PostGIS"
```

### Task 4: CI builds, smoke-tests and publishes `web:<sha>`

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `scripts/test_ci_image_builds.py`

**Interfaces:**
- Consumes: Task 3's `--target web` and `WEB_IMAGE`.
- Produces: `ghcr.io/<repo>/web:<sha>` on every push to master (Task 5's deploy awaits it).

- [ ] **Step 1: Write the failing tests** in `test_ci_image_builds.py`:
  - extend `test_ci_publishes_the_three_immutable_images` (rename to
    `test_ci_publishes_every_immutable_image`): `docker-build`'s commands contain `--target web`
    and `docker push "$WEB_IMAGE:${SHA}"`, and its `if` contains
    `needs.changes.outputs.web == 'true'`.
  - `test_web_tests_smoke_the_web_image`: `web-tests`' commands contain `--target web` and
    `WEB_IMAGE=roadtrip/web`.
  - `test_the_web_filter_watches_the_image_inputs`: the `web` path filter lists `Dockerfile` and
    `.dockerignore`.

Run: `python3 -m unittest scripts.test_ci_image_builds -v`
Expected: the three FAIL.

- [ ] **Step 2: Implement** in `ci.yml`:
  - `web` filter gains `'Dockerfile'` and `'.dockerignore'`.
  - `web-tests`, after "Build @roadtrip/web": `docker/setup-buildx-action` (same pin as
    `docker-build`), "Build web image" (`docker buildx build --target web -t roadtrip/web --load`
    with `--cache-from`/`--cache-to type=gha,scope=web-image`), "Smoke-test web image"
    (`WEB_IMAGE=roadtrip/web npx vitest run src/server/webImage.smoke.test.ts`, working directory
    `apps/web`).
  - `docker-build`: `if` gains `|| needs.changes.outputs.web == 'true'`; after the backend image,
    "Build web image" with `--build-arg BUILD_ENV=prod --build-arg BUILD_SHA=$SHA --build-arg BUILD_BRANCH=<head ref or ref name>`,
    and "Tag and push web image by SHA" (`WEB_IMAGE="ghcr.io/${{ github.repository }}/web"`,
    same login condition as the backend push).

- [ ] **Step 3: Run the CI tests**

Run: `python3 -m unittest scripts.test_ci_image_builds -v`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml scripts/test_ci_image_builds.py
git commit -m "ci: build, smoke-test and publish web:<sha>"
```

### Task 5: The `web` service, its secrets, and the deploy

**Files:**
- Modify: `docker-compose.yml` (new `web` service; `postgres` gains `WEB_DB_PASSWORD_FILE`)
- Modify: `secrets/registry.yaml`; regenerate `docker-compose.secrets.yml`
- Modify: `scripts/deploy.sh`, `.github/workflows/deploy.yml`
- Modify: `scripts/test_ci_image_builds.py`

**Interfaces:**
- Consumes: Task 2's init script; Task 4's published image.
- Produces: a production deploy that starts `web` after a healthy `backend`.

- [ ] **Step 1: Write the failing tests** in `test_ci_image_builds.py`:
  - `test_web_runs_only_in_production`: in `docker-compose.yml`, `web.profiles == ['web']`,
    `web.depends_on.backend.condition == 'service_healthy'`, no `ports`; `deploy.sh`'s
    production compose array contains `--profile web`; the sandbox invocation does not.
  - `test_production_only_pulls_images`: now asserts `pull backend web recgov-companion`.
  - `test_deploy_creates_the_web_reader_before_web_starts`: `deploy.sh` runs
    `exec -T postgres bash /docker-entrypoint-initdb.d/20-web-reader.sh` after
    `up -d --force-recreate backend` and before the bare `up -d`.
  - `test_deploy_resolves_every_release_image_before_touching_the_host` already derives the set
    from `deploy.sh`'s exports, so adding `ROADTRIP_WEB_IMAGE` makes it fail until `deploy.yml`
    awaits `web:${{ env.DEPLOY_SHA }}`.

Run: `python3 -m unittest scripts.test_ci_image_builds -v`
Expected: the new tests FAIL.

- [ ] **Step 2: Implement.**
  - `docker-compose.yml` `web`: per the spec's component 3, with
    `ENABLED_DATA_PROVIDERS=${ENABLED_DATA_PROVIDERS:-campflare,bcparks-strapi,aspira}` and a
    healthcheck `["CMD", "node", "-e", "fetch('http://127.0.0.1:3000/api/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]`.
    `postgres` gains `WEB_DB_PASSWORD_FILE=/run/secrets/web_db_password`.
  - `secrets/registry.yaml`: `WEB_DB_PASSWORD` (description, `consumers: [postgres, web]`,
    `required_in: [prod]`); `MAPBOX_TOKEN` consumers `[backend, web]`. Run
    `./secrets/manage.py generate`.
  - `deploy.sh`: `export ROADTRIP_WEB_IMAGE="ghcr.io/wwchen/roadtrip/web:${app_sha}"`;
    `--profile web` in the production compose array; `pull backend web recgov-companion`; the
    init-script `exec` right after `up -d --force-recreate backend`.
  - `deploy.yml`: `ghcr.io/${{ github.repository }}/web:${{ env.DEPLOY_SHA }}` in the await list.

- [ ] **Step 3: Run the tests and render the stacks**

Run: `python3 -m unittest discover -s scripts -p 'test_*.py' -v`
Expected: PASS.

Run: `docker compose -f docker-compose.yml -f docker-compose.secrets.yml --profile pois --profile web config --quiet` with `WEB_DB_PASSWORD` unset.
Expected: exit 0. If Compose rejects the unset secret source, record a ruling and add the owner
step "also set `WEB_DB_PASSWORD` for `local`" to Task 6's doc and the PR.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml docker-compose.secrets.yml secrets/registry.yaml scripts/deploy.sh .github/workflows/deploy.yml scripts/test_ci_image_builds.py
git commit -m "feat(deploy): run apps/web on mini-ca as the web service"
```

### Task 6: The tunnel rule, pinned and documented

**Files:**
- Create: `apps/web/src/server/tunnelRule.ts`, `apps/web/src/server/tunnelRule.test.ts`
- Modify: `docs/web-app.md`

**Interfaces:**
- Produces: `export const MINI_CA_TUNNEL_PATH: string` (the Global Constraints regex, verbatim).

- [ ] **Step 1: Write the failing tests** in `tunnelRule.test.ts`:
  - `'the rule sends every read route apps/web serves'`: `new RegExp(MINI_CA_TUNNEL_PATH)` matches
    `/api/pois`, `/api/pois/search`, `/api/pois/on-route`, `/api/pois/11042`,
    `/api/campgrounds/search`, `/api/campgrounds/details`, `/api/geocode`, `/api/route`.
  - `'the rule leaves every Kotlin-only route on Kotlin'`: it does not match
    `/api/pois/11042/campsites`, `/api/pois/11042/campsites/availability`,
    `/api/pois/availability/stays`, `/api/pois/abc`, `/api/me`, `/api/watches`,
    `/api/build-info`, `/api/health`, `/`, `/poi`, `/assets/index.js`.
  - `'the rule covers exactly the app routes it names'`: every `route.ts` under
    `src/app/api/` whose path is in the first list exists (guards against routing to a missing
    handler).
  - `'the documented rule is this one'`: `docs/web-app.md` contains `MINI_CA_TUNNEL_PATH`.

Run: `npx vitest run src/server/tunnelRule.test.ts` (in `apps/web`)
Expected: FAIL: the module does not exist.

- [ ] **Step 2: Implement** `tunnelRule.ts` with the constant, and in `docs/web-app.md` a
  "Production on mini-ca" section: what runs where, the tunnel rule (hostname, path, service,
  placed above the existing `roadtrip.floo.ca` entry), the rollout and rollback from the spec,
  and the owner actions (`./secrets/manage.py set prod WEB_DB_PASSWORD` before merge; add the rule
  after the in-host check).

- [ ] **Step 3: Run the web suite**

Run: `npm run test --workspace @roadtrip/web && npm run typecheck --workspace @roadtrip/web`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/web/src/server/tunnelRule.ts apps/web/src/server/tunnelRule.test.ts docs/web-app.md
git commit -m "docs(web): the roadtrip.floo.ca tunnel rule, pinned by a test"
```
