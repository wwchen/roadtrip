# Installation

Two kinds of machine run this stack, and they need different things:

- a **dev machine** — builds the backend, runs Tilt, drives the companion
  browser, runs the smoke suite;
- a **deploy host** (`mini-ca`) — builds the backend jar and images, runs the
  Compose stack, and decrypts secrets, but never runs Tilt, tests, or the
  companion browser directly.

Installing the dev toolchain on the deploy host works but drags in Tilt,
Playwright, and a Chromium download it will never use.

## Dev machine

```sh
make install
```

Idempotent — brew is a no-op when packages are present, `npm install` and
`playwright install` are no-ops on an unchanged lockfile and browser cache,
and hook wiring just rewrites `.git/config`. What it does:

| | |
| --- | --- |
| `tilt docker openjdk node` | build + run the stack |
| `sops age` | decrypt the vault — see [secrets.md](secrets.md) |
| `companion/` npm deps + Chromium | Rec.gov companion and `make qa` |
| git hooks → `.githooks/` | ktlint/detekt pre-commit, secrets guard |

Then create this machine's age identity and get it added to the vault:

```sh
./secrets/manage.py enroll
```

It prints your public key and the single command someone who can already
decrypt has to run to enroll it. Follow
[secrets.md](secrets.md#enrolling-a-machine) from there. Until your key is a
recipient, `make run` and `tilt up` fail at the point they
try to decrypt — the stack does not come up on a partial secret set, because
`SecretsBootstrap` refuses to boot and lists everything missing.

## Deploy host

Needs Docker, a JDK, and the secrets toolchain. It does **not** need Tilt,
Node, or Playwright. It *does* need a JDK: `make run env=prod` runs
`./gradlew :backend:buildFatJar` on the deploy host before building the
backend image (the image only wraps the jar it is handed — it does not build
it):

```sh
brew install docker openjdk sops age
```

`sops` is the one people miss. `age` alone is enough to *mint a key*, but every
stack command shells out to `sops` to decrypt the vault — without it `make run
env=prod` fails after `git pull` has already landed new code.

Then mint the host's identity:

```sh
./secrets/manage.py enroll
```

This writes `~/.config/sops/age/keys.txt` — the same path on every platform, so
runbooks stay copy-pasteable — and prints the public half. It is idempotent and
safe to re-run: it will not overwrite an existing identity, and if it finds a
key stranded in a directory sops doesn't search, it adopts it and says so.

That safety matters. A bare `age-keygen -o` on an existing file **replaces** the
identity, and a deploy host that loses its key can no longer decrypt the vault —
you'd be re-issuing credentials, not just re-running this. Use `enroll`, not
`age-keygen`.

`enroll` also prints the command to finish the job. Run it on a machine that
can already decrypt:

```sh
./secrets/manage.py enroll age1… --as "mini-ca" --note "deploy host"
```

That adds the recipient, re-wraps every vault, and verifies the key landed;
commit `secrets/.sops.yaml` and the vaults together. Until that lands, this
host is not a recipient. Check with:

```sh
./secrets/manage.py recipients
```

Deploy-specific setup beyond this — Cloudflare tunnel, Compose profiles,
Grafana volumes — is in the [README](../README.md#deploy-via-docker--cloudflare-tunnel).

## Verifying

```sh
./secrets/manage.py check     # registry, vaults and generated output agree
./secrets/manage.py ls        # what exists and where it's set (never values)
tilt up                       # dev machine
make run env=prod             # deploy host
```

`check` is what the pre-commit hook runs against staged blobs, so a clean
`check` here is the same bar CI applies.

## Requirements

Homebrew installs current versions of everything above, so this only matters
when you're not using it:

- **Java 25** — `backend/build.gradle.kts` sets `jvmToolchain(25)`; the Gradle
  wrapper handles the rest.
- **Node 22.9+** — the companion pins `"node": ">=22.9.0"` in
  `companion/package.json`, and the `web/` unit tests run under Node 22 in CI
  (`node --test`; no package.json — the suites import the browser sources
  directly).
- **Python 3.9+** for `secrets/manage.py` and the host-side fetchers in
  `scripts/`. 3.9 is a real floor, not caution: macOS Command Line Tools ships
  3.9, these fetchers run on dev machines, and CI's ruff lints with
  `--target-version py39` to keep the tree 3.9-compatible.
- **Docker** with the Compose plugin (`docker compose`, not `docker-compose`)
  — and a **running Docker daemon** (Docker Desktop, OrbStack, or colima).
  `brew install docker` installs only the CLI, not a daemon. Tilt, Compose,
  the backend tests (Testcontainers spins up a PostGIS Postgres), and jOOQ
  codegen all need the daemon up.
- **sops** new enough to have `--filename-override`, which `manage.py import`
  uses to apply the `secrets/` creation rule to a dotenv living elsewhere.
  Anything Homebrew currently ships is fine; a distro package old enough to
  lack the flag fails loudly on import, not silently.
