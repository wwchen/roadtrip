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
| `sops age` | decrypt secrets into `.env` — see [secrets.md](secrets.md) |
| `companion/` npm deps + Chromium | Rec.gov companion and `make qa` |
| git hooks → `.githooks/` | ktlint/detekt pre-commit, secrets guard |

Then create this machine's age identity and get it added to the vault:

```sh
make secrets-init
```

Follow [secrets.md](secrets.md#first-time-setup) from there. Until your key is
a recipient, `make run` and `tilt up` will start but the stack has no
credentials — most of the app works, upstream fetches don't.

## Deploy host

Needs Docker, a JRE for nothing at all (the image carries its own), and the
secrets toolchain. It does **not** need Tilt, Node, or Playwright:

```sh
brew install docker sops age
```

`sops` is the one people miss. `age` alone is enough to *mint a key*, but
`make run env=prod` calls `sops` to decrypt the vault — without it the deploy
fails at `_ensure-secrets`, after `git pull` has already landed new code.

Then mint the host's identity:

```sh
KEYS="$HOME/Library/Application Support/sops/age/keys.txt"   # macOS host
mkdir -p "$(dirname "$KEYS")"
test -f "$KEYS" || age-keygen -o "$KEYS"
chmod 600 "$KEYS"
grep "public key" "$KEYS"
```

That path is macOS-specific — sops uses Go's `os.UserConfigDir()`, which is
`~/Library/Application Support` on Darwin and `~/.config` on Linux. On a Linux
deploy host, substitute accordingly, or just run `make secrets-init` once the
branch is checked out and let it pick.

The `test -f` guard is not optional. A bare `age-keygen -o` on an existing file
**replaces** the identity, and a deploy host that loses its key can no longer
decrypt the vault — you'd be re-issuing credentials, not just re-running this.

Give that public key to someone who can already decrypt; they add it to
`.sops.yaml` and run `make secrets-rotate`. Once the branch is checked out on
the host, `make secrets-init` does the same thing and is idempotent.

Deploy-specific setup beyond this — Cloudflare tunnel, Compose profiles,
Grafana volumes — is in the [README](../README.md#deploy-via-docker--cloudflare-tunnel).

## Verifying

```sh
make secrets-check      # vault is well-formed and fully encrypted
make secrets            # decrypt into .env
tilt up                 # dev machine
make run env=prod       # deploy host
```

## Requirements

Homebrew installs current versions of everything above, so this only matters
when you're not using it:

- **Java 25** — `backend/build.gradle.kts` targets it; the Gradle wrapper
  handles the rest.
- **Python 3.10+** for the host-side fetchers in `scripts/`. Some use `X | Y`
  type syntax that 3.9 rejects at import. macOS system `python3` is 3.9 —
  check with `python3 -V` if a fetcher dies on a `TypeError` about `|`.
- **Docker** with the Compose plugin (`docker compose`, not `docker-compose`).
- **sops** new enough to have `--filename-override`, which `make secrets-import`
  uses to apply the `secrets/` creation rule to a `.env` living elsewhere.
  Anything Homebrew currently ships is fine; a distro package old enough to
  lack the flag fails loudly on import, not silently.
