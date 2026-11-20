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

1. Create a new Neon project on Postgres 18 in the same region (`aws-us-east-2`). Neon's PG18
   ships PostGIS 3.6.4, the same as the image; its PG16 ships 3.3.10.
2. `CREATE EXTENSION postgis` in the new database, then dump the old project and restore it with
   an 18 `pg_dump` and `pg_restore` (Neon refuses a restore from an older `pg_restore`). Neon's
   guide is "Migrate data from another Neon project".
3. Point `roadtrip-web`'s `DATABASE_URL` (reads) and `APP_DATABASE_URL` (app writes) on Vercel,
   and the `NEON_MIGRATE_URL` secret that
   `neon-migrate.yml` uses at the new project, redeploy, and keep the old project until the new
   one has served traffic.
