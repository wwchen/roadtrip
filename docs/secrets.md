# Secrets

Runtime secrets live encrypted in the repo at `secrets/secrets.enc.env` and are
decrypted into a gitignored `.env` on demand. Encryption is
[SOPS](https://github.com/getsops/sops) with [age](https://github.com/FiloSottile/age)
recipients.

Nothing about how you run the stack changes. `make run` and `tilt up` refresh
`.env` from the vault before they do anything else, so it can't be stale, and
the deploy host picks up a secret change from the same `git pull` that brings
the code.

## Why this shape

- **Committed and encrypted.** One artifact, versioned with the code that reads
  it. `git log secrets/secrets.enc.env` is the audit trail, and the deploy
  host's secrets cannot drift from yours — there is nothing to sync by hand.
- **Keys plaintext, values encrypted.** A diff shows *which* secret changed
  without showing *what* it changed to, so PRs stay reviewable.
- **No new infrastructure and no network dependency.** Decryption is local and
  works offline. There is no server to run, no token to keep alive, and no
  vendor that can be down at boot.
- **`.env` stays the interface.** Docker Compose's `env_file`, the Tiltfile,
  and the host-side Python fetchers all read `.env` exactly as before.

The trade-off worth naming: ciphertext in git history is permanent. If an age
private key leaks, an attacker with a clone can decrypt every historical value,
not just the current one — so key compromise means rotating the *credentials*,
not just re-encrypting the file. See [Rotation](#rotation).

## Layout

| Path | Committed | Purpose |
| --- | --- | --- |
| `secrets/secrets.enc.env` | yes | The vault. Source of truth. Values age-encrypted. |
| `.sops.yaml` | yes | Which age public keys can decrypt the vault. |
| `.env` | no | Generated from the vault. What Compose/Tilt/fetchers read. |
| `.env.local` | no | Optional host-only overrides, merged over the vault. |
| `.env.example` | yes | The documented key list. Values are always blank. |

`.env` is regenerated, never edited — it carries a `# GENERATED` header, and
the tooling refuses to overwrite a `.env` that lacks it, so a hand-written file
is never silently destroyed.

`.env.local` is for values that are genuinely per-host and not secret — a
longer `PROMETHEUS_RETENTION` on the mini, say. It is merged **by key**, so the
generated `.env` never contains a duplicate key and no consumer's
first-wins/last-wins parsing decides the outcome.

## Commands

```bash
make secrets-init       # one-time per host: create an age key, print its public half
make secrets-import     # one-time per repo: encrypt an existing plaintext .env
make secrets            # decrypt the vault into .env (automatic on run/tilt)
make secrets-edit       # change a value in $EDITOR; re-encrypted on save
make secrets-check      # validate the vault; also runs in CI and pre-commit
make secrets-recipients # who can currently decrypt
make secrets-rotate     # re-encrypt for the current .sops.yaml recipient list
make secrets-force      # discard an unmanaged .env and regenerate
```

## First-time setup

Requires `sops` and `age` (`brew install sops age`, or `make install`).

### 1. Create your key

```bash
make secrets-init
```

This writes an age identity to `~/.config/sops/age/keys.txt` and prints its
public half. **The private key is not in the repo and cannot be recovered.**
Back it up somewhere you trust — a password manager entry is fine — or losing
this machine locks you out of every secret.

### 2. Do the same on `mini-ca`

The deploy host needs its own identity. It doesn't need this branch checked out
to make one — `age-keygen` is enough:

```bash
ssh mini@mini-ca 'mkdir -p ~/.config/sops/age && \
  test -f ~/.config/sops/age/keys.txt || age-keygen -o ~/.config/sops/age/keys.txt; \
  chmod 600 ~/.config/sops/age/keys.txt; \
  grep "public key" ~/.config/sops/age/keys.txt'
```

The `test -f` guard matters: `age-keygen -o` on an existing file would replace
the identity and lock the host out of the vault. Once the branch *is* deployed
there, `make secrets-init` does the same thing and is idempotent.

If `age` isn't installed on the host yet: `brew install sops age`.

### 3. Record both public keys

Replace the two placeholders in [`.sops.yaml`](../.sops.yaml) with the public
keys from steps 1 and 2, keeping the comment that names each holder.

**Every placeholder must go before you can import.** sops hands each entry to
age as a real recipient, and a placeholder isn't a valid public key — the
encryption fails outright. If you don't have a host's key yet, delete or
comment out its line and add the host later (step 2, then `make secrets-rotate`).

### 4. Encrypt the existing `.env`

From the machine that has the real, current `.env`:

```bash
make secrets-import
```

Run from a git worktree, this finds the main clone's `.env` automatically —
worktrees don't carry gitignored files. Point at any other file with
`make secrets-import SOURCE=/path/to/.env`.

Then verify the round trip before you trust it:

```bash
make secrets-check
make secrets-force      # regenerate .env from the vault
git diff --stat         # .env is gitignored; nothing should appear
```

### 5. Commit

```bash
git add .sops.yaml secrets/secrets.enc.env
git commit -m "chore: move secrets into the SOPS vault"
```

On the next deploy, `mini-ca`'s `git pull` brings the vault and `make run
env=prod` materializes `.env` from it. Its old hand-maintained `.env` will
still be there and unmanaged, so run `make secrets-force` on the host once to
hand ownership over to the vault.

## Everyday use

**Change a secret:**

```bash
make secrets-edit          # opens decrypted in $EDITOR, re-encrypts on save
git commit -am "chore: rotate the Mapbox token"
git push                   # deploy picks it up
```

**Add a new secret:** add the key to `.env.example` with a blank value and a
comment explaining what it's for, then `make secrets-edit` to set the real
value. `secrets-check` fails on a vault key that isn't documented in
`.env.example`, which keeps the two from drifting.

**Local override:** put it in `.env.local`. Never put a secret there — it isn't
encrypted, isn't backed up, and isn't shared.

## Adding a host or a person

1. On the new machine: `make secrets-init`, copy the printed public key.
2. Add it to the `age:` list in `.sops.yaml` with a comment naming the holder.
3. From a machine that can already decrypt: `make secrets-rotate`.
4. Commit `.sops.yaml` and `secrets/secrets.enc.env` together.

`make secrets-recipients` shows who the vault currently grants; if it disagrees
with `.sops.yaml`, someone forgot step 3.

## Rotation

Two different operations, often confused:

**Re-wrapping the vault** (`make secrets-rotate`) re-encrypts the data key for
the current recipient list. Use it when adding or removing a holder. It does
**not** protect against a removed holder who already has a clone: they can
still decrypt every historical commit of the file.

**Rotating a credential** means issuing a new value at the provider (Mapbox,
Auth0, Slack, …) and putting that new value in the vault. This is the only
thing that actually revokes access. Do it whenever:

- an age private key is lost, leaked, or was on a machine you no longer control;
- you remove someone from `.sops.yaml`;
- a credential is exposed by any other route.

Because the ciphertext is in git history permanently, "rotate the credential"
is the answer far more often than "re-encrypt the file."

## Recovery

**Lost your age key, another holder remains:** create a new key
(`make secrets-init`), have the other holder add it to `.sops.yaml` and run
`make secrets-rotate`. Then rotate the credentials — the lost key can still
decrypt history.

**Lost every age key:** the vault is unrecoverable by design. Re-issue every
credential at its provider, `make secrets-init` on each host, and
`make secrets-import` a freshly built `.env`. `.env.example` is the checklist
of what needs re-issuing — this is the reason it's kept complete.

## Guardrails

- **Pre-commit hook** blocks a staged plaintext `.env` or `.env.local`
  outright, and runs `secrets-check` on any staged vault. Enable with
  `make install-hooks` (`tilt up` and most `make` targets do it for you).
- **CI** runs `secrets-check` on every PR: the vault must be fully encrypted,
  no plaintext env file may be tracked, and every vault key must be documented
  in `.env.example`. CI holds no age key and cannot decrypt anything.
- **`.env` is written `0600`** and atomically replaced, so a partial write
  can't leave a truncated file behind.
- **`materialize` is idempotent** — an unchanged `.env` isn't rewritten, so
  Compose and Tilt don't churn on a fresh mtime.

## What is *not* in the vault

GitHub Actions secrets (`TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET`,
`DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `CODECOV_TOKEN`) stay in GitHub's own
secret store. They are credentials *for* the deploy pipeline rather than
runtime config for the app, and putting them in the vault would require giving
CI a decryption key — which is exactly what this design avoids.
