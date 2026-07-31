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
vs. deploy-host split and version requirements (Java 25, Node 22.9+,
Python 3.9+, Docker with the Compose plugin *and a running daemon*, a `sops`
recent enough to have `--filename-override`).

## 2. Secrets bootstrap

Runtime secrets live encrypted in `secrets/` and are decrypted per-host with
`sops`/`age`. A fresh clone has no key yet:

```sh
./secrets/manage.py enroll
```

This mints this machine's age identity and prints its public key, along with
the one command the other side has to run. `make run` and `tilt up` will refuse
to boot until that key is a recipient — `SecretsBootstrap` lists everything
missing rather than starting on a partial secret set. Send the printed command
to someone who can already decrypt the vault; running it adds your key and
re-wraps the vaults in one step. Full detail, including the enrollment
walkthrough, is in [docs/secrets.md](docs/secrets.md) and
[docs/installation.md](docs/installation.md).

Check where you stand at any point:

```sh
./secrets/manage.py recipients   # are you a recipient yet?
./secrets/manage.py check        # registry, vaults, and generated output agree
```

### Working without vault access

To be clear about the boundary: **running the backend (`make run`, `tilt up`)
requires vault access** — there is no mock-secrets mode. A vault recipient has
to run `./secrets/manage.py enroll <your key> --as "you@yourbox"`; until then
the stack will not boot.

Plenty of work needs no vault at all, though:

- **Design-system components.** The gallery runs standalone against the real
  stylesheets and component modules — see
  [web/design-system/README.md](web/design-system/README.md#start-here-the-living-gallery):

  ```sh
  python3 -m http.server 8766
  open http://localhost:8766/web/design-system/gallery.html
  ```

- **Frontend unit tests.** `web/` has no package.json and no install step; the
  suites import the browser sources directly:

  ```sh
  node --test $(find web -name '*.test.mjs')
  ```

- **Python script + tooling tests** (fetchers, secrets tooling, CI-shape
  checks — the tests never decrypt anything):

  ```sh
  python3 -m unittest discover -s scripts -p 'test_*.py'
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

One target runs everything CI runs:

```sh
make test
```

That covers, in order (mirroring `.github/workflows/ci.yml`):

- `./gradlew :backend:test` — Kotlin/Ktor backend (JUnit + Kover; needs a
  running Docker daemon for Testcontainers)
- `./gradlew :backend:ktlintCheck` + `:backend:detekt` — Kotlin formatting and
  static analysis
- `./gradlew :detekt-rules:test` — the repo's custom detekt rules
- `node --test` over every `web/**/*.test.mjs` — frontend map/UI modules
  (discovery is asserted, so zero found files fails loudly)
- `cd companion && npm test` — Rec.gov companion (Node `--test`; needs
  `companion/` deps, which `make install` provides)
- `python3 -m unittest discover -s scripts -p 'test_*.py'` — Python fetchers,
  secrets tooling, CI-shape checks
- `python3 secrets/manage.py generate --check` + `check` — secrets registry
  and generated compose file agree
- `python3 scripts/validate_grafana_dashboards.py` — dashboard provisioning

During iteration, run just the suite your change touches (each line above
works standalone). Beyond `make test`, `make qa` runs the Playwright JVM smoke
against a running stack (see [SMOKE.md](SMOKE.md)).

### Git hooks

`make install` (or any dev target) points this clone's hooks at `.githooks/`:

- **pre-commit** — refuses to commit a plaintext `.env`, blocks commits on a
  branch whose PR already merged/closed, validates the secrets registry when
  `secrets/` is touched, and runs ktlint + detekt when backend Kotlin is
  staged.
- **pre-push** — runs `./gradlew :backend:test` before a push whose range
  touches backend source or Gradle build inputs; pushes that only change
  web/docs/data skip it automatically. For the rare "yes I know, just push
  it" case: `SKIP_PREPUSH=1 git push` (use sparingly — CI still runs the
  tests).

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
- [docs/glossary.md](docs/glossary.md) — the project vocabulary: watch, poller, slot, companion, ATC, governor, …
- [docs/first-run.md](docs/first-run.md) — what a healthy first `tilt up` looks like; why the map starts empty.
- [docs/installation.md](docs/installation.md) — dev machine vs. deploy host setup.
- [docs/secrets.md](docs/secrets.md) — the `secrets/` vault, rotation, what's deliberately not in it.
- [docs/backend-architecture.md](docs/backend-architecture.md) — Kotlin/Ktor layering rules.
- [docs/frontend-components.md](docs/frontend-components.md) — frontend component/design-system rules.
- [docs/touch-scroll-interactions.md](docs/touch-scroll-interactions.md) — touch/scroll interaction rules for the map UI.
- [docs/observability.md](docs/observability.md) — the Grafana/Loki/Tempo/Prometheus/Alloy stack.
- [docs/reservation-providers.md](docs/reservation-providers.md) — the availability-provider abstraction.
- [docs/reservation-providers/](docs/reservation-providers/) — per-vendor integration notes (Aspira, Campflare, ReserveAmerica, ReserveCalifornia).
- [docs/adding-a-reservation-provider.md](docs/adding-a-reservation-provider.md) — step-by-step for a new provider.
- [docs/adding-a-data-source.md](docs/adding-a-data-source.md) — step-by-step for a new POI data source.
- [DATA_SOURCES.md](DATA_SOURCES.md) — per-category data source research and refresh plan.
- [SMOKE.md](SMOKE.md) — real-device smoke checklist.
- [web/design-system/README.md](web/design-system/README.md) — the design system and its living gallery.
- [rfcs/](rfcs/) — accepted architecture/process decisions, including [0002-pr-process.md](rfcs/0002-pr-process.md).
