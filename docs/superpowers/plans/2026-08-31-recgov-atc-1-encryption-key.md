# Recgov ATC Slice 1: Encryption Key Provisioning — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provision `ENCRYPTION_KEY` end to end so `SecretCipher` is constructed and per-user secret storage (Slack token today, rec.gov password next) stops answering 503 `encryption_unavailable`.

**Architecture:** The backend already has the full read path — `AppConfig` calls `SecretsConfig.fromConfig(roadtrip.section("security"))`, which returns null on absent/blank (graceful "secret storage disabled"), and `ServiceModule` builds `SecretCipher` from it. Nothing defines the `security:` yaml section or registers the secret, so the config is always null. This slice adds the registry entry and the yaml section; no Kotlin changes.

**Tech Stack:** sops/age secrets vault (`secrets/manage.py`), Ktor YAML config, Kotlin backend tests (Gradle).

**Spec:** `docs/superpowers/specs/2026-08-31-per-user-recgov-atc-design.md` (section "1. Prerequisite: provision the encryption key")

## Global Constraints

- Key format: base64 of exactly 32 bytes (`SecretsConfig` enforces with `require`).
- `required_in: [local, prod]` per the spec — absence fails boot in both.
- Never put a secret value in any committed file; values go only into the sops vault via `manage.py set` (operator action).
- Repo rule: no inline magic constants; none are needed here.
- All commands run from the repo root.

---

### Task 1: Register the secret and regenerate the compose overlay

**Files:**
- Modify: `secrets/registry.yaml` (append after the `RIDB_API_KEY` entry)
- Regenerate: `docker-compose.secrets.yml` (via `./secrets/manage.py generate` — never hand-edit)

**Interfaces:**
- Produces: registry entry `ENCRYPTION_KEY` with `consumers: [backend]`, which Task 2's yaml placeholder must match by exact name.

- [ ] **Step 1: Append the registry entry**

Add to the end of `secrets/registry.yaml`:

```yaml
ENCRYPTION_KEY:
  description: AES-256-GCM key (base64 of 32 bytes) sealing per-user secrets in user_settings — the Slack bot token today, the rec.gov password next. Absent, secret storage is disabled and settings that need it answer 503.
  consumers: [backend]
  required_in: [local, prod]
```

- [ ] **Step 2: Regenerate and validate**

Run:
```bash
./secrets/manage.py generate && ./secrets/manage.py check
```
Expected: `docker-compose.secrets.yml` gains the `ENCRYPTION_KEY` wiring for the backend service; check exits 0. Commit the regenerated file — CI runs `generate --check` and fails on a stale copy.

- [ ] **Step 3: Run the drift test to verify it fails (this is the failing test)**

Run:
```bash
cd backend && ./gradlew test --tests 'ca.floo.roadtrip.config.SecretRegistryDriftTest'
```
Expected: FAIL — `every registered backend secret is actually read` reports `ENCRYPTION_KEY` as registered with `consumers: [backend]` but read by no `application*.yaml` placeholder. (The other direction still passes.)

- [ ] **Step 4: Commit**

```bash
git add secrets/registry.yaml docker-compose.secrets.yml
git commit -m "feat(secrets): register ENCRYPTION_KEY for per-user secret storage"
```
(Committing with the drift test red is fine mid-slice; Task 2 is the green step and lands in the same PR.)

### Task 2: Wire the `security:` config section

**Files:**
- Modify: `backend/src/main/resources/application.yaml` (inside the top-level `roadtrip:` map, as a sibling of the existing `booking:` / `auth:` sections)

**Interfaces:**
- Consumes: the `ENCRYPTION_KEY` name from Task 1 — the `${...}` placeholder must match it exactly (the drift test compares these two lists).
- Produces: `roadtrip.security.encryption-key`, already read by `SecretsConfig.fromConfig` via `AppConfig.kt`'s existing `roadtrip.section("security")` call. No Kotlin edits.

- [ ] **Step 1: Add the section**

Insert as a sibling of `booking:` (directly after the `companion-timeout: 180s` line, at the same indent level as `booking:`):

```yaml
  security:
    # AES-256-GCM key for per-user secret storage (SecretCipher), base64 of
    # 32 bytes. Absent or blank disables secret storage: SecretsConfig returns
    # null and settings that need the cipher answer 503.
    encryption-key: ${ENCRYPTION_KEY:}
```

Do not add the section to `application-prod.yaml` or `application-local.yaml` — the base file's placeholder covers every environment, and duplicating it would create two more drift-test placeholder sites to keep in sync for no behavior change.

- [ ] **Step 2: Run the drift test to verify it passes**

Run:
```bash
cd backend && ./gradlew test --tests 'ca.floo.roadtrip.config.SecretRegistryDriftTest'
```
Expected: PASS in both directions.

- [ ] **Step 3: Run the backend config and settings test packages**

Run:
```bash
cd backend && ./gradlew test --tests 'ca.floo.roadtrip.config.*' --tests 'ca.floo.roadtrip.service.settings.*' --tests 'ca.floo.roadtrip.service.security.*'
```
Expected: PASS. (`SecretsConfig` blank→null behavior and `SecretCipher` round-trip are already covered by existing tests; this slice must not change their outcomes.)

- [ ] **Step 4: Document the rotation caveat**

Append to the end of `docs/secrets.md`:

```markdown
## ENCRYPTION_KEY rotation

`ENCRYPTION_KEY` seals per-user secrets in `user_settings` (`*_cipher`
columns). Rotating it orphans every existing blob: decryption fails
per-user and degrades gracefully (no Slack alert / no stored rec.gov
password) rather than throwing, and affected users must re-enter their
stored secrets. Rotate deliberately, not routinely.
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.yaml docs/secrets.md
git commit -m "feat(config): wire roadtrip.security.encryption-key from ENCRYPTION_KEY"
```

### Task 3: Operator key generation (USER ACTION — not automatable)

**Files:** none in git — values live only in the encrypted vault.

The vault is age-encrypted; setting values requires the operator's age key and an interactive prompt. The implementing agent must NOT attempt this — surface it as a required follow-up instead:

```bash
openssl rand -base64 32 | ./secrets/manage.py set ENCRYPTION_KEY
```

(or run `./secrets/manage.py set ENCRYPTION_KEY` interactively and paste an `openssl rand -base64 32` value; set it for both local and prod overlays per the prompts — `required_in: [local, prod]` means `make run` fails boot until this is done.)

Rotation note for the operator: rotating this key orphans every existing `*_cipher` blob (they fail to decrypt and degrade per-user, they do not throw), so rotation means users re-enter stored secrets.
