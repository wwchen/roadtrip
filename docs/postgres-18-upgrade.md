# Upgrading Postgres 16 to 18

The repo pins one PostGIS build everywhere: `postgis/postgis:18-3.6` (PostgreSQL 18.6, PostGIS
3.6.4). `scripts/test_image_pins.py` fails the build if the compose files, `ci.yml`, the backend
Testcontainers image, `SharedTestDb` or `scripts/schema-reference.mjs` disagree.

Changing the tag does not upgrade a database. Every database that already holds data has to be
moved to 18 by hand, once. This page is that procedure for each place one lives.

## What changed in Postgres 18

`NOT NULL` is a named constraint from 18. A column renamed by a Flyway migration keeps the
constraint name it was created with (`availability_reservable_id_not_null`), where Drizzle and a
restore from a 16 dump use the default. `scripts/schema-reference.mjs` drops the name of a column
`NOT NULL` before comparing, and keeps the `NOT NULL` itself.

## What changed in the image

From 18, the official image keeps its data in `/var/lib/postgresql/18/docker` and declares
`/var/lib/postgresql` as its volume. The compose files now mount the host directory there.

The 18 entrypoint refuses to start, with `Error: in 18+, these Docker images are configured to
store database data in a format…`, when it finds:

- a 16 data directory anywhere under the mount, or
- anything mounted at the old `/var/lib/postgresql/data` path, even an empty one.

So an unmigrated database fails loudly. It is never silently replaced with an empty one.

The PostGIS image also installs `postgis`, `postgis_topology`, `fuzzystrmatch` and the tiger
geocoder into `POSTGRES_DB` during initdb. A dump restored into that database collides on
`CREATE SCHEMA tiger`, which is why the steps below drop it and restore with `--create`.

## Order

Neon cannot change a project's major version. Moving it means a new Neon project on 18 and a
data migration (below), and until that happens Vercel's `apps/web` runs on 16 while every test
runs on 18. Decide which comes first:

1. **Neon first, then merge.** No window where tests and production differ.
2. **Merge first, Neon later.** mini-ca and tests move now. `apps/web` on Vercel runs against a
   16 that nothing tests until Neon moves.

Merging deploys mini-ca automatically (`deploy.yml` runs after CI passes on `master`), so the
mini-ca steps below run around the merge.

## mini-ca

1. Find the data directory and the databases in it. The compose default is
   `~/.roadtrip-map/postgres`; confirm it rather than assume it.

   ```bash
   cd ~/.roadtrip/current
   docker inspect "$(docker compose --profile pois ps -q postgres)" \
     --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
   docker compose --profile pois exec -T postgres \
     psql -U roadtrip -d postgres -Atc "select datname from pg_database where not datistemplate"
   ```

   The steps below dump `roadtrip`. Dump any other database listed the same way.

2. Stop the writers, then dump while 16 is still running.

   ```bash
   docker compose --profile pois stop backend
   mkdir -p ~/pg16-backup
   docker compose --profile pois exec -T postgres pg_dumpall -U roadtrip --globals-only > ~/pg16-backup/globals.sql
   docker compose --profile pois exec -T postgres pg_dump -U roadtrip -d roadtrip -Fc > ~/pg16-backup/roadtrip.dump
   ```

   `backend` is the only writer in the deployed `pois` and `tunnel` profiles. If the `web`
   profile is running too, stop `web` as well.

3. Stop Postgres and move its data aside. Keep it until the new database has run for a while;
   it is the rollback.

   ```bash
   docker compose --profile pois stop postgres
   mv ~/.roadtrip-map/postgres ~/.roadtrip-map/postgres-16
   ```

4. Merge the PR and let `deploy.yml` deploy it. Postgres 18 initialises an empty directory and
   runs `postgres-init/`, which recreates the reader and writer roles from their secrets.

5. Replace the database the image created with the dump.

   ```bash
   cd ~/.roadtrip/current
   docker compose --profile pois stop backend
   docker compose --profile pois exec -T postgres dropdb -U roadtrip roadtrip
   docker compose --profile pois exec -T postgres \
     pg_restore -U roadtrip -d postgres --create --exit-on-error < ~/pg16-backup/roadtrip.dump
   docker compose --profile pois start backend
   ```

   `start` reuses the container the deploy created, with its secrets. Do not `up` by hand:
   `deploy.sh` layers the generated secrets file that a bare `docker compose up` leaves out.

   `globals.sql` is for reference: compare its roles with `\du`. A role that exists there
   and is not created by `postgres-init/` has to be created by hand before the restore, or
   `--exit-on-error` stops on its first `GRANT`.

6. Check it: row counts on the largest tables, `select extversion from pg_extension where
   extname = 'postgis'` (3.6.4), and the site's health gate.

**Rollback.** Before step 5, revert the merge and redeploy; the 16 data is untouched. After it,
stop Postgres, move `postgres-16` back to `postgres`, and redeploy the previous release.

If the deploy in step 4 runs before step 3, Postgres 18 refuses to start on the 16 data, the
health gate fails and `deploy.sh` rolls back to the last good release. Nothing is lost; do
steps 3 to 5 and deploy again.

## Sandboxes

A sandbox keeps its `postgres-data` volume across redeploys of the same slot, and a volume made
by 16 makes Postgres 18 refuse to start. Tear down sandboxes that existed before the merge
(`scripts/deploy.sh sandbox-down <name>`); new ones start on an empty volume. The snapshot
archive at `SANDBOX_SNAPSHOT_PATH` still restores: an 18 `pg_restore` reads archives made by 16.

## Local dev

Postgres fails to start with the 18 error above until the old directory is gone. `make
reset-db` deletes it and starts fresh; re-import the data afterwards. To keep the data, use the
mini-ca steps with the local compose instead.

## Neon

The project holds more than tables. Sign-in is Neon Auth, which lives in the project, and
Vercel's Neon integration owns the production branch, the per-PR preview branches,
`DATABASE_URL_UNPOOLED` and `NEON_AUTH_BASE_URL`. A new project means a new Neon Auth endpoint,
new signing keys and a re-linked integration. Neon's PG18 ships PostGIS 3.6.4, the image's
version; its PG16 ships 3.3.10.

Building the new project:

1. Create it on Postgres 18 in `aws-us-east-2`, with a `main` branch, database `roadtrip` and
   owner `roadtrip`, and the old compute settings (0.25 CU, no suspend).
2. Provision Neon Auth (Better Auth) on `roadtrip`, then copy the old settings: trusted origins,
   magic link, email and password, Google, the organization plugin. The SMTP password is not
   readable through the API; enter it in the console.
3. Create `roadtrip_web` (with `default_transaction_read_only = on`), `roadtrip_web_writer` (both
   with `statement_timeout = 10s`) and `grafana_reader` in SQL, as `roadtrip`, without passwords.
   The Neon role API would make them admins. Their table grants come with the dump.
4. Dump everything but `neon_auth` with an 18 `pg_dump -Fc -N neon_auth`, and the user data of
   Neon Auth with `pg_dump -Fc -a` of `neon_auth.user`, `account`, `organization`, `member` and
   `invitation`. Leave `session`, `verification`, `jwks` and `project_config`: Neon owns them
   and the new project has its own.
5. Restore the main dump from a `pg_restore -l` list without the entries owned by `cloud_admin`
   and the `COMMENT ON EXTENSION postgis`, which a non-superuser cannot apply; `CREATE
   EXTENSION` recreates them. Then restore the auth dump with `-a`.

The first copy took 69 minutes to dump at 0.25 CU. The tables that change in production are
small (the user tables and Neon Auth); the catalog changes only when the importer runs.

Cutting over:

1. Set passwords for `roadtrip_web` and `roadtrip_web_writer` in the console.
2. Re-copy the user tables and the Neon Auth user data, so nothing written since the first
   copy is lost. If the importer has run since, re-copy the catalog too.
3. Re-link the Vercel integration to the new project, which replaces `DATABASE_URL_UNPOOLED`,
   `NEON_AUTH_BASE_URL` and the preview branches.
4. Point the hand-set `DATABASE_URL` (reads, `roadtrip_web`) and `APP_DATABASE_URL` (writes,
   `roadtrip_web_writer`) on Vercel, and the `NEON_MIGRATE_URL` secret `neon-migrate.yml` uses,
   at the new project. Redeploy. Every signed-in user is signed out once: the old sessions
   belong to the old Neon Auth.
5. Keep the old project until the new one has served traffic. Rolling back is pointing the
   variables and the integration back.
