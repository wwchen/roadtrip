# Contributing

This is a solo/AI-assisted project (see [rfcs/0002-pr-process.md](rfcs/0002-pr-process.md)),
but the setup below is the real clone-to-first-PR path regardless of who's
driving.

## 1. Clone and install

```sh
git clone <repo-url>
cd roadtrip
make install
```

`make install` is idempotent — Homebrew is a no-op when packages are already
present, `npm install`/`playwright install` are no-ops on an unchanged
lockfile/cache, and hook wiring just rewrites `.git/config`. It gets you:

| | |
| --- | --- |
| `tilt docker openjdk node` | build + run the stack |
| `sops age` | decrypt the vault — see [docs/secrets.md](docs/secrets.md) |
| `companion/` npm deps + Chromium | Rec.gov companion and `make qa` |
| git hooks → `.githooks/` | ktlint/detekt pre-commit, secrets guard |

See [docs/installation.md](docs/installation.md) for the full dev-machine
vs. deploy-host split and version requirements (Java 25, Python 3.10+,
Docker with the Compose plugin, a `sops` recent enough to have
`--filename-override`).

## 2. Secrets bootstrap

Runtime secrets live encrypted in `secrets/` and are decrypted per-host with
`sops`/`age`. A fresh clone has no key yet:

```sh
./secrets/manage.py init
```

This mints this machine's age identity and prints its public key. `make run`
and `tilt up` will refuse to boot until that key is a recipient —
`SecretsBootstrap` lists everything missing rather than starting on a
partial secret set. Give the printed public key to someone who can already
decrypt the vault; they add it to `secrets/.sops.yaml` and run
`./secrets/manage.py rotate`. Full detail, including the first-time-on-a-new-host
walkthrough, is in [docs/secrets.md](docs/secrets.md) and
[docs/installation.md](docs/installation.md).

Check where you stand at any point:

```sh
./secrets/manage.py recipients   # are you a recipient yet?
./secrets/manage.py check        # registry, vaults, and generated output agree
```

### No-secrets escape hatch

If you're only working on design-system components and don't have (or don't
yet want) vault access, you don't need the backend or secrets at all. The
design-system gallery runs standalone against the real stylesheets and
component modules — see
[web/design-system/README.md](web/design-system/README.md#start-here-the-living-gallery):

```sh
python3 -m http.server 8766
open http://localhost:8766/web/design-system/gallery.html
```

## 3. Run the stack

```sh
tilt up          # full stack: Postgres/backend/Grafana/observability/Rec.gov companion
# or
make run         # backend on the host + Postgres in Docker (fastest backend-only loop)
```

`tilt up` is the easiest path for full-stack dev, including the observability
stack (Grafana/Loki/Tempo/Prometheus/Alloy). See the [README](README.md#local-dev)
for what each gets you and the Tilt UI's manual-trigger `data` cluster for POI
refresh.

## 4. Run the tests

Four independent suites cover the four parts of the codebase. Run whichever
are relevant to your change; CI runs the backend and secrets suites on every
PR.

```sh
./gradlew :backend:test                    # Kotlin/Ktor backend (JUnit + Kover)
node --test 'web/**/*.test.mjs'             # frontend map/UI modules
cd companion && npm test                   # Rec.gov companion (Node --test)
python3 -m unittest discover -s scripts     # Python fetchers, secrets tooling, Grafana config checks
```

Other useful checks before opening a PR:

```sh
./gradlew :backend:ktlintCheck   # Kotlin formatting
./gradlew :backend:detekt        # Kotlin static analysis
make qa                          # Playwright JVM smoke against a running stack (see SMOKE.md)
```

If you're touching campsite availability, watches, or a reservation-provider
adapter, read [docs/reservation-providers.md](docs/reservation-providers.md)
first — it's the architecture contract for that subsystem. Backend
route/service/repo layering rules are in [AGENTS.md](AGENTS.md) (the single
source of truth for those rules); frontend rules are in
[docs/frontend-components.md](docs/frontend-components.md).

## 5. Open a PR

Use the [PR template](.github/PULL_REQUEST_TEMPLATE.md) — it has a test-plan
checklist. Push your branch and open a PR against `master`; CI runs lint,
backend tests, and the smoke suite.

The real merge process, including the exact human-in-the-loop mechanics, is
documented in [rfcs/0002-pr-process.md](rfcs/0002-pr-process.md). The short
version: **branch protection on `master` requires an approving review, and
that approval only happens after the repo owner (`wwchen`) posts a literal
`lgtm` comment on the PR**, which triggers a separate GitHub App identity to
post the approval. Pushing more commits does not silently re-approve — a new
`lgtm` is needed after any change once review has started. There is no
self-merge and no auto-approval from CI passing alone.

## Docs index

- [README.md](README.md) — architecture overview, local dev, deploy.
- [docs/installation.md](docs/installation.md) — dev machine vs. deploy host setup.
- [docs/secrets.md](docs/secrets.md) — the `secrets/` vault, rotation, what's deliberately not in it.
- [docs/backend-architecture.md](docs/backend-architecture.md) — Kotlin/Ktor layering rules.
- [docs/frontend-components.md](docs/frontend-components.md) — frontend component/design-system rules.
- [docs/reservation-providers.md](docs/reservation-providers.md) — the availability-provider abstraction.
- [docs/adding-a-reservation-provider.md](docs/adding-a-reservation-provider.md) — step-by-step for a new provider.
- [docs/adding-a-data-source.md](docs/adding-a-data-source.md) — step-by-step for a new POI data source.
- [DATA_SOURCES.md](DATA_SOURCES.md) — per-category data source research and refresh plan.
- [SMOKE.md](SMOKE.md) — real-device smoke checklist.
- [web/design-system/README.md](web/design-system/README.md) — the design system and its living gallery.
- [rfcs/](rfcs/) — accepted architecture/process decisions, including [0002-pr-process.md](rfcs/0002-pr-process.md).
