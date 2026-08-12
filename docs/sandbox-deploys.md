# Sandbox deploys

A sandbox is a throwaway live environment for a PR or branch: backend +
Postgres only, no observability, no companion, no Cloudflare tunnel of its
own. It is reachable at `https://roadtrip-sb-<name>.floo.ca`.

The zone is `floo.ca` (one label under the apex) on purpose: the free
Cloudflare Universal SSL cert `*.floo.ca` covers sandbox hostnames. A deeper
zone like `*.sandbox.roadtrip.floo.ca` is a second-level wildcard the free
cert does NOT cover (TLS wildcards match a single label) and would need paid
Advanced Certificate Manager.

### Routing and exposure

Each sandbox is reached through the **main `roadtrip` Cloudflare tunnel** (the
retired `roadtrip sandbox` tunnel is unused). Three layers, provisioned
per-sandbox by `scripts/cloudflare_sandbox.sh` except where noted:

- **DNS** — a per-sandbox **explicit proxied CNAME** `roadtrip-sb-<name>.floo.ca
  → <main-tunnel-id>.cfargotunnel.com`, created on up and deleted on down.
  There is **no wildcard DNS**, so only sandboxes we created (plus prod records)
  resolve to the tunnel. (Cloudflare rejects partial-label wildcards like
  `roadtrip-sb-*` and won't auto-create DNS for wildcards anyway; per-host
  explicit records sidestep both.)
- **Tunnel ingress** — a **one-time, hand-added** rule `*.floo.ca →
  http://caddy:80`, ordered after the explicit `roadtrip.floo.ca` rules and
  before the `404` catch-all. Automation never mutates the tunnel config, so a
  sandbox op cannot break the prod site. `roadtrip.floo.ca` matches its own
  rules first; only the per-sandbox CNAMEs reach the wildcard.
- **Caddy** — filters to the `roadtrip-sb-<name>` vhost and reverse-proxies to
  the backend.
- **Access** — because sandboxes disable app auth and resolve every request to
  a seeded **admin** user (below), the hosts sit behind Cloudflare Access. This
  is a **single static, human-configured** self-hosted application whose domain
  is the wildcard `roadtrip-sb-*.floo.ca` (Access apps accept partial-label
  wildcards even though DNS/tunnel rules don't). Its policy restricts entry to
  the chosen identity providers (Google/GitHub). Automation never creates or
  edits Access apps — a static policy set once by a human can't be misconfigured
  open by a script, and the wildcard app already covers every sandbox, so a new
  host is gated the instant its DNS resolves. An unauthenticated request gets a
  302 to the Access login, never the backend.

TLS: the zone is `floo.ca` (one label under the apex) so the free Cloudflare
`*.floo.ca` cert covers sandbox hostnames; a deeper zone like
`*.sandbox.roadtrip.floo.ca` is a second-level wildcard the free cert does NOT
cover and would need paid Advanced Certificate Manager.

Before publishing DNS, `cf_sandbox_up` **verifies the Access gate exists**: it
looks up the wildcard Access app covering the host and confirms it has at least
one `allow` policy (read-only). If that check fails — app missing, mistyped, or
no allow policy — it refuses to create DNS rather than expose an ungated
seed-admin backend. `CF_SKIP_ACCESS_CHECK=1` overrides (discouraged). A DNS or
gate failure on a token-configured host is **fatal**: the deploy aborts instead
of printing "Sandbox is live" for a URL that can't resolve.

Host config for the provisioning scripts lives in
`/var/lib/roadtrip-sandboxes/cloudflare.env` (`CF_TUNNEL_ID`; `CF_ACCOUNT_ID`
and `CF_ZONE_NAME` are auto-resolved from the zone but may be set explicitly)
plus the API token file `cloudflare_api_token` — which needs **`Zone:DNS:Edit`**
(manage the CNAME) and **`Access: Apps: Read`** (verify the gate; it never edits
Access). Without the token file, DNS provisioning is a logged no-op — the
sandbox still comes up host-locally, just not publicly reachable.

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
waits for the application and Git-tree-addressed data images to appear in GHCR,
then SSHes to `mini@mini-ca` over Tailscale and installs the PR's
release archive (no Git checkout), and runs
`scripts/deploy.sh sandbox-up pr<N> pr<N>` (with `SANDBOX_SHA` set) or
`scripts/deploy.sh sandbox-down pr<N>`, both via the shared sandbox action
described below. On success it posts the sandbox URL as a PR comment:

```
Sandbox live: https://roadtrip-sb-pr<N>.floo.ca
SHA: <12-char sha>  ·  stop with /sandbox stop
```

The sandbox is named `pr<N>` (e.g. `pr532`) and is stable across
re-runs of the same PR.

### The sandbox action

Every path that touches a sandbox goes through one composite action,
`.github/actions/sandbox`, so there is a single definition of what starting and
stopping mean:

| Input | Default | Meaning |
|---|---|---|
| `operation` | — | `start` or `stop` |
| `slug` | — | Sandbox slug, e.g. `pr532` |
| `branch` | `''` | Branch for the build-info banner; falls back to the slug (start) |
| `pr-number` | `''` | PR to report status on; blank posts nothing |
| `reason` | `''` | Why it was stopped, shown in the status comment |
| `require-existing` | `false` | Skip instead of tearing down when there is no marker (stop) |
| `ts-oauth-client-id` / `ts-oauth-secret` | — | Tailscale OAuth credentials |
| `ssh-private-key` / `ssh-known-hosts` | — | Deploy SSH credentials |
| `github-token` | `''` | Token for status comments |

The deploy host, state directory, tunnel zone, and the tailnet tag and version
are constants inside the action, not inputs. The credentials have to be inputs:
a composite action cannot read the `secrets` context, so whatever it needs must
be handed to it by the calling workflow.

The action owns everything that touches the sandbox: joining the tailnet,
configuring SSH, running `deploy.sh`, and posting the PR status comment as work
progresses. Workflows are left responsible only for deciding *what* to do and
checking out the tree. It outputs `url` and, for stop, `torn-down`.

There is no `sha` input. For `start` the caller has already checked out the
commit being deployed, so the action reads both the commit SHA and the `data/`
tree SHA from that checkout rather than having them passed back in alongside it.
The only thing it cannot recover from a detached checkout is the branch name,
which is why `branch` is an input and `sha` is not.

The slug is authoritative: it is passed to `deploy.sh sandbox-up` as both ref and
explicit name, so the sandbox name is never re-derived and always matches the
hostname the status comment advertises. The operation must be `start` or `stop`
and the slug must satisfy `deploy.sh`'s own name rule, both checked before
anything reaches the host.

`torn-down` lets callers tell "there was nothing to do" apart from a genuine
failure — the remote signals absence with a dedicated exit code, so real errors
still fail the step, and a stop that found nothing leaves the PR comment alone.

### Via the Actions UI

`sandbox.yml` also has a `workflow_dispatch` with an **operation** dropdown
(`start` / `stop`), a **slug**, and an optional **branch** that defaults to the
branch the run is launched from. Use it for sandboxes that are not tied to a PR
— no status comment is posted, and the URL lands in the run summary.

### Sweep (every 30 minutes)

`sandbox-sweep.yml` is the safety net for anything the close path missed:
teardowns that failed, PRs closed before that job existed, and sandboxes on PRs
that simply stay open. It lists every marker on the host and tears down any
sandbox that

- belongs to a PR that is closed or no longer exists, or
- has outlived `SANDBOX_TTL_HOURS` (repo variable, default **2**), or
- has a marker whose `START_EPOCH` cannot be read — itself a sign of a stuck
  sandbox.

Sandboxes created by name via `make sandbox` are subject to the TTL but have no
PR to check, and no comment is posted for them.

The `plan` job decides; a matrix `stop` job runs the shared stop step once per
doomed sandbox, serialised (`max-parallel: 1`) because teardown prunes images on
the shared host, and with `fail-fast: false` so one bad sandbox cannot strand the
rest. When a `pr<N>` sandbox is reaped its status comment is rewritten with the
reason.

Because it runs in Actions rather than host cron, it needs no host setup. Run it
on demand with:

```sh
gh workflow run sandbox-sweep.yml
```

Note that scheduled and dispatchable workflows are only picked up from the
default branch, so the sweep starts working once this is merged to `master`.

Required secrets (same as `deploy.yml`): `DEPLOY_SSH_KEY`,
`DEPLOY_KNOWN_HOSTS`, `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`.
Optional repo variable: `SANDBOX_TUNNEL_ZONE` (falls back to `floo.ca`).

### Via CLI (on the deploy host)

```sh
make sandbox REF=<branch-or-pr-number>                   # name auto-derived
make sandbox REF=<branch-or-pr-number> NAME=<name>       # explicit name
make sandbox REF=<branch-or-pr-number> SHA=<sha>         # explicit image SHA
make sandbox-stop NAME=<name>
```

`make sandbox` expands to:

```sh
SANDBOX_SHA=<sha> scripts/deploy.sh sandbox-up <ref> [name]
```

where `<sha>` defaults to the current `git rev-parse HEAD` if `SHA` is
not set, and `<ref>` defaults to the current branch if `REF` is not set.

`make sandbox-stop` calls `scripts/deploy.sh sandbox-down <name>` directly;
`NAME` is required.

This path is gated by SSH access to the deploy host — no GitHub identity
check.

## Architecture

### Images

CI publishes `ghcr.io/wwchen/roadtrip/backend:<commit-sha>` for every PR and
master commit. It contains the backend JAR and compiled React application.
Compose, provisioning, encrypted secrets, and deployment scripts travel in a
small commit-pinned release archive installed by the shared workflow action.
`ghcr.io/wwchen/roadtrip/data:<data-tree-sha>` contains the Git-tracked dataset.
The host initializes a same-SHA Docker volume from it; sandboxes share that
volume read-only. Nothing is built or checked out on the deploy host.

### Network

A **containerized Caddy** (the `caddy` service in the base `roadtrip`
Compose project, profile `tunnel`, alongside `cloudflared`) holds
per-sandbox virtual-hosts for `roadtrip-sb-<name>.floo.ca`. It runs
`caddy:2-alpine`, publishes **no host ports** (pihole owns 80/443 on the
deploy host), listens on `:80` inside the Docker network (each vhost uses an
`http://` site address; a scheme-less one would bind `:443`), and terminates
no TLS of its own (`auto_https off`).

`deploy.sh` writes a per-sandbox snippet:

```
http://roadtrip-sb-<name>.<zone> {
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

Cloudflare terminates TLS for `roadtrip-sb-*.floo.ca` (via the free
`*.floo.ca` cert) and routes the
tunnel to the caddy container at `http://caddy:80`.

### Compose project isolation

Each sandbox runs as a separate Compose project `roadtrip-sb-<name>` with
its own named volume (`roadtrip-sb-<name>_postgres-data`). Database volumes are
never shared. Sandboxes do share the immutable, read-only
`roadtrip-data-<data-tree-sha>` dataset volume with production.

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

### The React pages

Every page is React now, served from `frontend/dist` on the same URL it has always
had (`/`, `/availability`, `/watches`) — so a sandbox shows a reviewer the same app
production runs, with no second URL to visit. `ROADTRIP_SANDBOX_PREVIEW_PAGES` and
the `/preview/*` URLs it switched on are gone: the map was the last page they
carried, and Phase 4e graduated it. `migratedPages` in `StaticSiteRoutes.kt` is the
list.

The compiled `frontend/dist` is part of the SHA-tagged application image. The
sandbox does not bind-mount or rebuild it, so the browser, backend JAR, and build
provenance always come from the same CI artifact.

## Host configuration

All host-specific values are env vars read by `scripts/deploy.sh`. Overriding
them on the host (or in the SSH command) re-targets the entire tier with no code
change.

| Variable | Default | Notes |
|---|---|---|
| `SANDBOX_TUNNEL_ZONE` | `floo.ca` | DNS zone; sandbox URLs become `<prefix><name>.<zone>`. Kept one label under the apex so the free `*.floo.ca` cert covers it |
| `SANDBOX_HOST_PREFIX` | `roadtrip-sb-` | Hostname prefix; full host is `<prefix><name>.<zone>`. Must match the tunnel's `roadtrip-sb-*` public-hostname rule |
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
| `SANDBOX_TTL_HOURS` | `2` | Sweep: sandboxes older than this are torn down. Set as a repo variable, not on the host |

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
0 2 * * * mini $HOME/.roadtrip/current/scripts/sandbox_snapshot.sh >> $HOME/.roadtrip/sandbox-snapshot.log 2>&1
```

The script honours these tunables:

| Variable | Default |
|---|---|
| `SANDBOX_SNAPSHOT_PATH` | `/var/lib/roadtrip-sandboxes/snapshot.dump` |
| `SNAPSHOT_SOURCE_PROJECT` | `roadtrip` (the `name:` field in `docker-compose.yml`) |
| `SNAPSHOT_COMPOSE_FILE` | `<repo>/docker-compose.yml` |
| `SNAPSHOT_POSTGRES_USER` | `roadtrip` |
| `SNAPSHOT_POSTGRES_DB` | `roadtrip` |

## Moving sandboxes to a dedicated host

All host coupling is in the env vars above plus the workflow's SSH target
(`mini@mini-ca`, set as `SANDBOX_HOST` in `sandbox.yml`). To move the
sandbox tier off the production box:

1. Provision the new host with Docker and `docker compose` (v2 plugin). No host `caddy` binary is needed — the proxy is the `caddy` container.
2. Log in to GHCR on the new host so `docker pull ghcr.io/wwchen/roadtrip/backend:<sha>` succeeds — the same `GHCR_TOKEN` / `GITHUB_TOKEN` already used by the deploy host works.
3. Bring up the base stack with the `tunnel` profile (`make run env=prod`, or at minimum `docker compose --profile tunnel --profile pois up -d caddy cloudflared`) so the `caddy` container and the `roadtrip-sandbox` network exist.
4. Route sandboxes through the new host's tunnel: the one-time `*.floo.ca → http://caddy:80` ingress rule, the one-time wildcard Access app for `roadtrip-sb-*.floo.ca`, and the CF API token + `cloudflare.env` (with that host's tunnel id). Per-sandbox DNS is then automatic. See First-time host setup.
5. Update `SANDBOX_HOST` in `.github/workflows/sandbox.yml` to the new host and add its Tailscale address to `DEPLOY_KNOWN_HOSTS`.
6. Set the `SANDBOX_*` env vars in the host's environment (or export them before the SSH call) for any non-default values.

No script changes are required.

## First-time host setup

Checklist for a host that has not previously run sandboxes:

- [ ] **Base stack up with the `tunnel` profile.** The `caddy` container and the `roadtrip-sandbox` network both come from the base `roadtrip` project. Run `make run env=prod` (or `docker compose --profile tunnel --profile pois up -d caddy cloudflared`) once so they exist before the first `/sandbox`. No host `caddy` install and no `/etc/caddy` setup is required — the container carries `caddy/Caddyfile` and imports the bind-mounted `caddy/sandboxes/`.
- [ ] **One-time tunnel ingress rule.** On the main `roadtrip` tunnel, add a public-hostname rule subdomain `*` (→ `*.floo.ca`) → `http://caddy:80`, ordered after the explicit `roadtrip.floo.ca` rules and before the `404` catch-all. This is the ONLY tunnel edit; per-sandbox automation never touches it. (Applying via API: `PUT .../cfd_tunnel/<id>/configurations` with the full ingress list — insert the one rule, preserve the rest.)
- [ ] **CF API token + config.** Place a token with **`Zone:DNS:Edit`** and **`Access: Apps: Read`** for `floo.ca`/the account at `/var/lib/roadtrip-sandboxes/cloudflare_api_token` (chmod 600), and write `/var/lib/roadtrip-sandboxes/cloudflare.env` with `CF_TUNNEL_ID` (main tunnel). Per-sandbox DNS is then created/deleted automatically; `cf_sandbox_up` uses the Access read to verify the gate before publishing DNS. No wildcard DNS record is needed — each sandbox gets its own explicit CNAME.
- [ ] **One-time Access app.** In Zero Trust → Access → Applications, create ONE self-hosted app with domain `roadtrip-sb-*.floo.ca` and a policy that **allows only your identity providers** (Google/GitHub) — e.g. an Allow policy including your email(s) or a Groups/IdP selector. This one wildcard app gates every sandbox; automation never touches it. NOTE: an app with `allowed_idps` but an empty `policies` array does not admit anyone — you must add at least one Allow policy.
- [ ] **Identity provider.** A Google and/or GitHub IdP configured in Zero Trust → Settings → Authentication, referenced by the Access app above.
- [ ] **GHCR login.** Run `docker login ghcr.io` on the host with credentials that can pull from `ghcr.io/wwchen/roadtrip/backend`. A GitHub PAT with `read:packages` scope works; store it so `docker pull` runs unattended (e.g. `~/.docker/config.json`).
- [ ] **State and snapshot directories.** Create `SANDBOX_STATE_DIR` (`/var/lib/roadtrip-sandboxes` by default) and ensure it is writable by the user running the sandbox scripts.
- [ ] **Cron entries.** Add the snapshot (nightly) cron entry from the Scheduled jobs section above, pointing to the script in the repo checkout. No reaper entry is needed — `sandbox-sweep.yml` enforces the TTL from GitHub Actions.
- [ ] **`SANDBOX_SNAPSHOT_PATH` env var** (optional). Export it in the environment or set it in the cron entry if the default path is not desired.
