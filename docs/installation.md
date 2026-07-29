# Installation

Two kinds of machine run this stack, and they need different things:

- a **dev machine** — builds the backend, runs Tilt, drives the companion
  browser, runs the smoke suite;
- a **deploy host** (`mini-ca`) — runs the Compose stack and decrypts secrets,
  but never builds or tests.

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
./secrets/manage.py init
```

Follow [secrets.md](secrets.md#first-time-setup-on-a-new-host) from there.
Until your key is a recipient, `make run` and `tilt up` fail at the point they
try to decrypt — the stack does not come up on a partial secret set, because
`SecretsBootstrap` refuses to boot and lists everything missing.

## Deploy host

Needs Docker and the secrets toolchain. It does **not** need Tilt, Node, or
Playwright, and it needs no JDK — the backend image carries its own:

```sh
brew install docker sops age
```

`sops` is the one people miss. `age` alone is enough to *mint a key*, but every
stack command shells out to `sops` to decrypt the vault — without it `make run
env=prod` fails after `git pull` has already landed new code.

Then mint the host's identity:

```sh
./secrets/manage.py init
```

This writes `~/.config/sops/age/keys.txt` — the same path on every platform, so
runbooks stay copy-pasteable — and prints the public half. It is idempotent and
safe to re-run: it will not overwrite an existing identity, and if it finds a
key stranded in a directory sops doesn't search, it adopts it and says so.

That safety matters. A bare `age-keygen -o` on an existing file **replaces** the
identity, and a deploy host that loses its key can no longer decrypt the vault —
you'd be re-issuing credentials, not just re-running this. Use `init`, not
`age-keygen`.

Give that public key to someone who can already decrypt; they add it to
`secrets/.sops.yaml` and run `./secrets/manage.py rotate`. Until then this host
is not a recipient. Check with:

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
- **Python 3.10+** for `secrets/manage.py` and the host-side fetchers in
  `scripts/`. Some use `X | Y` type syntax that 3.9 rejects at import. macOS
  system `python3` is 3.9 — check with `python3 -V` if a fetcher dies on a
  `TypeError` about `|`.
- **Docker** with the Compose plugin (`docker compose`, not `docker-compose`).
- **sops** new enough to have `--filename-override`, which `manage.py import`
  uses to apply the `secrets/` creation rule to a dotenv living elsewhere.
  Anything Homebrew currently ships is fine; a distro package old enough to
  lack the flag fails loudly on import, not silently.
