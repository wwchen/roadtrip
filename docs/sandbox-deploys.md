# Sandbox deploys

A sandbox is a throwaway live environment for a PR or branch: backend +
Postgres only, no observability, no companion, no Cloudflare tunnel of its
own. It is reachable at `https://sb-<name>.sandbox.roadtrip.floo.ca`.

Auth is disabled in every sandbox (no `AUTH_<vendor>_ISSUER` is passed, so the
active provider's issuer is blank, and `ROADTRIP_SANDBOX_ASSUME_USER=true`).
Instead the backend resolves every request to one of two seeded users selected
via a header:

- **Will** (id 90001) — admin role
- **Matt** (id 90002) — regular user (no role)

The build-info banner visible in the UI (`/api/build-info`) shows the
environment (`sandbox`), commit SHA, and branch so reviewers can confirm
they're looking at the right build.

## Triggering a sandbox

### Via PR comment (GitHub Actions)

Comment on a PR:

```
/sandbox        — spin up a sandbox for the PR's head SHA
/sandbox stop   — tear down the sandbox for this PR
```

Only **OWNER** or **COLLABORATOR** accounts on the repo can trigger the
workflow; comments from other identities are silently ignored before any
step runs.

The workflow (`sandbox.yml`) resolves the PR head SHA via the GitHub API,
waits for the GHCR image (`ghcr.io/wwchen/roadtrip/backend:<sha>`) to
appear, then SSHes to `mini@mini-ca` over Tailscale and runs
`scripts/sandbox_up.sh <pr_number>` (with `SANDBOX_SHA` set) or
`scripts/sandbox_down.sh pr<pr_number>`. On success it posts the sandbox
URL as a PR comment:

```
Sandbox live: https://sb-pr<N>.sandbox.roadtrip.floo.ca
SHA: <12-char sha>  ·  stop with /sandbox stop
```

The sandbox is named `pr<N>` (e.g. `pr532`) and is stable across
re-runs of the same PR.

Required secrets (same as `deploy.yml`): `DEPLOY_SSH_KEY`,
`DEPLOY_KNOWN_HOSTS`, `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`.
Optional repo variable: `SANDBOX_TUNNEL_ZONE` (falls back to
`sandbox.roadtrip.floo.ca`).

### Via CLI (on the deploy host)

```sh
make sandbox REF=<branch-or-pr-number>                   # name auto-derived
make sandbox REF=<branch-or-pr-number> NAME=<name>       # explicit name
make sandbox REF=<branch-or-pr-number> SHA=<sha>         # explicit image SHA
make sandbox-stop NAME=<name>
```

`make sandbox` expands to:

```sh
SANDBOX_SHA=<sha> scripts/sandbox_up.sh <ref> [name]
```

where `<sha>` defaults to the current `git rev-parse HEAD` if `SHA` is
not set, and `<ref>` defaults to the current branch if `REF` is not set.

`make sandbox-stop` calls `scripts/sandbox_down.sh <name>` directly;
`NAME` is required.

This path is gated by SSH access to the deploy host — no GitHub identity
check.

## Architecture

### Image

CI publishes `ghcr.io/wwchen/roadtrip/backend:<sha>` on every branch
build. The sandbox pulls the image by SHA; no image is ever built on the
deploy host.

### Network

A **containerized Caddy** (the `caddy` service in the base `roadtrip`
Compose project, profile `tunnel`, alongside `cloudflared`) holds a
wildcard virtual-host for `*.sandbox.roadtrip.floo.ca`. It runs
`caddy:2-alpine`, publishes **no host ports** (pihole owns 80/443 on the
deploy host), listens on `:80` inside the Docker network (each vhost uses an
`http://` site address; a scheme-less one would bind `:443`), and terminates
no TLS of its own (`auto_https off`).

`deploy.sh` writes a per-sandbox snippet:

```
sb-<name>.<zone> {
    reverse_proxy sb-<name>-backend:8765
}
```

into `SANDBOX_CADDY_DIR` (host `./caddy/sandboxes`, bind-mounted read-only
into the caddy container at `/etc/caddy/sandboxes`, imported by
`caddy/Caddyfile`) and activates it with
`docker exec <caddy container> caddy reload`. No host `caddy` binary is
required.

The `reverse_proxy` target is the backend's **network alias**
(`sb-<name>-backend`) on the shared `roadtrip-sandbox` Docker network, not
a host port — the caddy container cannot reach the host's
`127.0.0.1:<port>` loopback bind. Each sandbox backend joins
`roadtrip-sandbox` (declared `external: true` in
`docker-compose.sandbox.yml`; owned/created by the base project) with that
alias, so every backend — all literally named `backend` — is individually
addressable without collision.

Ports are still allocated from `SANDBOX_PORT_RANGE_START`–
`SANDBOX_PORT_RANGE_END` and the backend still binds `127.0.0.1:<port>:8765`,
but that host-loopback bind is used only by `deploy.sh`'s readiness probe
and the reaper — it is not the request path and is never reachable from
outside the host.

Cloudflare terminates TLS for `*.sandbox.roadtrip.floo.ca` and routes the
tunnel to the caddy container at `http://caddy:80`.

### Compose project isolation

Each sandbox runs as a separate Compose project `roadtrip-sb-<name>` with
its own named volume (`roadtrip-sb-<name>_postgres-data`). Projects never
share volumes with each other or with the production `roadtrip` project.

### Database

1. Flyway runs on backend start and migrates the schema.
2. If `SANDBOX_SNAPSHOT_PATH` points to a readable `pg_dump -Fc` archive,
   `deploy.sh` restores it with `pg_restore --no-owner --no-acl
   --exit-on-error` into the fresh database.
3. `scripts/sandbox_seed_users.sql` is applied unconditionally after the
   restore (or immediately after Flyway if there is no snapshot), inserting
   Will and Matt with `ON CONFLICT DO NOTHING`.

The snapshot is taken from the running production Postgres instance by
`scripts/sandbox_snapshot.sh` (see Scheduled jobs below).

### Backend environment

The backend container runs with:

| Variable | Value |
|---|---|
| `ROADTRIP_PROFILE` | `compose-local` (Docker-network-aware DB URL, no prod-secret validation) |
| `ROADTRIP_BUILD_ENV` | `sandbox` |
| `ROADTRIP_BUILD_SHA` | `<sha>` |
| `ROADTRIP_BUILD_BRANCH` | `<branch>` |
| `ROADTRIP_SANDBOX_ASSUME_USER` | `true` (auth bypass; no OIDC issuer is passed, so auth is off) |
| `OTEL_*_EXPORTER` | `none` (no Alloy collector in a sandbox) |

## Host configuration

All host-specific values are env vars read by `scripts/deploy.sh` and
`scripts/sandbox_down.sh`. Overriding them on the host (or in the SSH
command) re-targets the entire tier with no code change.

| Variable | Default | Notes |
|---|---|---|
| `SANDBOX_TUNNEL_ZONE` | `sandbox.roadtrip.floo.ca` | DNS zone; sandbox URLs become `sb-<name>.<zone>` |
| `SANDBOX_CADDY_DIR` | `<repo>/caddy/sandboxes` | Host side of the caddy container bind-mount; per-sandbox `.caddy` snippet files land here and are imported by `caddy/Caddyfile` |
| `SANDBOX_CADDY_CONFIG` | `/etc/caddy/Caddyfile` | Root Caddyfile path **inside the container**; passed to `caddy reload --config <path>` |
| `SANDBOX_CADDY_CONTAINER` | `roadtrip-caddy-1` | Container reloaded via `docker exec <name> caddy reload` — no host `caddy` binary needed |
| `SANDBOX_NETWORK` | `roadtrip-sandbox` | Shared Docker network the proxy and every sandbox backend join; must match the `name:` in `docker-compose.yml` |
| `SANDBOX_BACKEND_PORT` | `8765` | Backend in-container port the proxy forwards to |
| `SANDBOX_STATE_DIR` | `/var/lib/roadtrip-sandboxes` | Holds `<name>.meta` marker files consumed by the reaper |
| `SANDBOX_SNAPSHOT_PATH` | _(empty)_ | Path to a `pg_dump -Fc` archive; if blank or absent, sandboxes start with an empty Flyway-migrated schema. `scripts/sandbox_snapshot.sh` defaults this to `/var/lib/roadtrip-sandboxes/snapshot.dump` when the var is unset |
| `SANDBOX_PORT_RANGE_START` | `41000` | First port in the host-local range allocated to sandboxes |
| `SANDBOX_PORT_RANGE_END` | `41999` | Last port in the range |
| `SANDBOX_DB_PASSWORD` | `sandbox` | Throwaway Postgres password for the sandbox DB |
| `POSTGRES_HEALTH_RETRIES` | `30` | Seconds to wait for `pg_isready` before failing |
| `SANDBOX_TTL_HOURS` | `24` | Reaper: sandboxes older than this are torn down |

## Scheduled jobs

### Snapshot (nightly)

`scripts/sandbox_snapshot.sh` dumps the main production Postgres instance
to a `pg_dump -Fc` archive. New sandboxes restore from this archive so
reviewers see real (anonymised) data rather than an empty schema.

The script writes to a `.tmp` file and atomically renames it on success,
so a sandbox restore mid-dump always sees either the previous complete
archive or the new one — never a partial write.

Example cron entry (nightly at 02:00, logged to syslog):

```
0 2 * * * root /path/to/scripts/sandbox_snapshot.sh >> /var/log/sandbox-snapshot.log 2>&1
```

The script honours these tunables:

| Variable | Default |
|---|---|
| `SANDBOX_SNAPSHOT_PATH` | `/var/lib/roadtrip-sandboxes/snapshot.dump` |
| `SNAPSHOT_SOURCE_PROJECT` | `roadtrip` (the `name:` field in `docker-compose.yml`) |
| `SNAPSHOT_COMPOSE_FILE` | `<repo>/docker-compose.yml` |
| `SNAPSHOT_POSTGRES_USER` | `roadtrip` |
| `SNAPSHOT_POSTGRES_DB` | `roadtrip` |

### Reaper (hourly)

`scripts/sandbox_reap.sh` reads every `*.meta` marker in `SANDBOX_STATE_DIR`,
parses the `START_EPOCH` field, and calls `sandbox_down.sh` for any sandbox
whose age exceeds `SANDBOX_TTL_HOURS`. Fresh sandboxes are left running.
Malformed markers emit a warning and are skipped; the script exits non-zero
if any marker was malformed so cron/systemd can alert on it.

Example cron entry (hourly):

```
0 * * * * root /path/to/scripts/sandbox_reap.sh >> /var/log/sandbox-reap.log 2>&1
```

## Moving sandboxes to a dedicated host

All host coupling is in the env vars above plus the workflow's SSH target
(`mini@mini-ca`, set as `SANDBOX_HOST` in `sandbox.yml`). To move the
sandbox tier off the production box:

1. Provision the new host with Docker and `docker compose` (v2 plugin). No host `caddy` binary is needed — the proxy is the `caddy` container.
2. Log in to GHCR on the new host so `docker pull ghcr.io/wwchen/roadtrip/backend:<sha>` succeeds — the same `GHCR_TOKEN` / `GITHUB_TOKEN` already used by the deploy host works.
3. Bring up the base stack with the `tunnel` profile (`make run env=prod`, or at minimum `docker compose --profile tunnel --profile pois up -d caddy cloudflared`) so the `caddy` container and the `roadtrip-sandbox` network exist.
4. Route `*.sandbox.roadtrip.floo.ca` to the caddy container (`http://caddy:80`) through the Cloudflare tunnel running on the new host.
5. Update `SANDBOX_HOST` in `.github/workflows/sandbox.yml` to the new host and add its Tailscale address to `DEPLOY_KNOWN_HOSTS`.
6. Set the `SANDBOX_*` env vars in the host's environment (or export them before the SSH call) for any non-default values.

No script changes are required.

## First-time host setup

Checklist for a host that has not previously run sandboxes:

- [ ] **Base stack up with the `tunnel` profile.** The `caddy` container and the `roadtrip-sandbox` network both come from the base `roadtrip` project. Run `make run env=prod` (or `docker compose --profile tunnel --profile pois up -d caddy cloudflared`) once so they exist before the first `/sandbox`. No host `caddy` install and no `/etc/caddy` setup is required — the container carries `caddy/Caddyfile` and imports the bind-mounted `caddy/sandboxes/`.
- [ ] **Cloudflare tunnel route.** Add `*.sandbox.roadtrip.floo.ca → http://caddy:80` in Zero Trust → Networks → Tunnels → the tunnel's public hostname rules. (If `cloudflared` runs as a container in the same project, `caddy` resolves by service name.)
- [ ] **GHCR login.** Run `docker login ghcr.io` on the host with credentials that can pull from `ghcr.io/wwchen/roadtrip/backend`. A GitHub PAT with `read:packages` scope works; store it so `docker pull` runs unattended (e.g. `~/.docker/config.json`).
- [ ] **State and snapshot directories.** Create `SANDBOX_STATE_DIR` (`/var/lib/roadtrip-sandboxes` by default) and ensure it is writable by the user running the sandbox scripts.
- [ ] **Cron entries.** Add the snapshot (nightly) and reaper (hourly) cron entries from the Scheduled jobs section above, pointing to the scripts in the repo checkout.
- [ ] **`SANDBOX_SNAPSHOT_PATH` env var** (optional). Export it in the environment or set it in the cron entry if the default path is not desired.
