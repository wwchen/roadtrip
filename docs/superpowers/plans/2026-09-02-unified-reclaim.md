# Unified Reclaim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace three duplicate disk-reclaim implementations with one `scripts/reclaim.sh` used by prod deploys, the sandbox sweep, and a new local `make reclaim`.

**Architecture:** `reclaim.sh` is a standalone executable with `prune`, `check-disk`, and `report` commands and a `--scope=local|host` flag that selects retention defaults. `deploy.sh` calls it as a subprocess in place of its private `_require_free_disk`, `_prune_roadtrip_images`, and `_prune_data_volumes`. `sandbox-sweep.yml` streams it over SSH (`ssh host bash -s < scripts/reclaim.sh`), which removes its duplicated heredoc. A separate, unrelated change extends the worktree guardrail to check `sparsePaths` actually took effect.

**Tech Stack:** Bash 3.2 (macOS default — no associative arrays, no `${x,,}`), Docker CLI, Python 3 `unittest` for tests, GNU Make, Node 20 for the worktree checker.

**Spec:** `docs/superpowers/specs/2026-09-02-hygiene-unified-reclaim-design.md`

## Global Constraints

- Every destructive Docker call must carry `--filter "label=ca.floo.roadtrip.managed=true"`, except the anonymous-volume prune, which cannot filter by label and is gated behind `--include-anonymous`.
- `--include-anonymous` defaults **on** for `--scope=host`, **off** for `--scope=local`. This is what keeps a local reclaim away from the unrelated `teslamate_*` and `roadtripv2_*` stacks.
- `ROADTRIP_IMAGE_KEEP` defaults to **5** for `--scope=host` and **2** for `--scope=local`.
- `ROADTRIP_IMAGE_RETENTION` defaults to `336h`. `RECLAIM_FREE_TARGET_GB` defaults to `20`.
- Existing environment variable names must keep working: `ROADTRIP_IMAGE_RETENTION`, `ROADTRIP_IMAGE_KEEP`, `ROADTRIP_MIN_FREE_DISK_GB`, `RECLAIM_FREE_TARGET_GB`.
- `scripts/reclaim.sh` must be committed executable (`git update-index --chmod=+x`). `.github/actions/install-release` stages all of `scripts/` to the host, so no separate shipping step is needed.
- Do not change `scripts/deploy.sh:138-181` (`_hold_data_volume` / `_release_data_volume`). Task 3 has a test that pins this.
- Bash target is 3.2. Do not use `declare -A` or `readarray`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `scripts/reclaim.sh` (create) | The only implementation of disk reclaim and the disk floor check. |
| `scripts/test_reclaim.py` (create) | Behavioural tests driving `reclaim.sh` against a fake `docker` on `PATH`. |
| `scripts/deploy.sh` (modify) | Loses `_require_free_disk`, `_prune_roadtrip_images`, `_prune_data_volumes`; calls `reclaim.sh`. |
| `.github/workflows/sandbox-sweep.yml` (modify) | Loses its inline reclaim heredoc; streams `reclaim.sh`. |
| `Makefile` (modify) | Adds the `reclaim` target. |
| `scripts/check-worktree-sparse-paths.mjs` (modify) | Adds the "is it applied" assertion. |

---

### Task 1: `reclaim.sh` — scaffolding, scope defaults, and `check-disk`

**Files:**
- Create: `scripts/reclaim.sh`
- Create: `scripts/test_reclaim.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `scripts/reclaim.sh check-disk --label TEXT [--min-gb N] [--path PATH]`, exit 0 when free space is at or above the floor, exit 1 when below, exit 0 with a warning on `stderr` when `df` output cannot be read. Later tasks add `prune` and `report` to the same `case` dispatch.

- [ ] **Step 1: Write the failing test**

Create `scripts/test_reclaim.py`:

```python
#!/usr/bin/env python3
"""Behavioural coverage for scripts/reclaim.sh.

reclaim.sh is the single implementation of disk reclaim for prod deploys, the
sandbox sweep, and local development. The risk it carries is blast radius: it
runs on a host shared with unrelated Docker stacks, so every destructive call
has to stay inside the roadtrip label. These tests drive the real script
against a fake `docker` on PATH and assert on the exact argv it produces.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECLAIM = ROOT / "scripts" / "reclaim.sh"

FAKE_DOCKER = """#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_LOG"
case "$1" in
  image)
    case "$2" in
      ls) cat "$FAKE_IMAGE_LS" 2>/dev/null || true ;;
      inspect) echo "sha256:deadbeef" ;;
    esac
    ;;
esac
exit 0
"""


class ReclaimTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.log = self.tmp / "docker.log"
        self.image_ls = self.tmp / "images.txt"
        self.image_ls.write_text("")
        fake = self.bin / "docker"
        fake.write_text(FAKE_DOCKER)
        fake.chmod(0o755)
        self.addCleanup(self._tmp.cleanup)

    def run_reclaim(self, *args: str, **env: str) -> subprocess.CompletedProcess:
        environ = dict(os.environ)
        environ["PATH"] = f"{self.bin}{os.pathsep}{environ['PATH']}"
        environ["DOCKER_LOG"] = str(self.log)
        environ["FAKE_IMAGE_LS"] = str(self.image_ls)
        environ.update(env)
        return subprocess.run(
            ["bash", str(RECLAIM), *args],
            capture_output=True, text=True, check=False, env=environ,
        )

    def docker_calls(self) -> list:
        if not self.log.exists():
            return []
        return [line for line in self.log.read_text().splitlines() if line]


class CheckDiskTest(ReclaimTestCase):
    def test_passes_when_free_space_meets_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "0",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("disk check", done.stdout)

    def test_fails_when_free_space_is_under_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "999999999",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 1)
        self.assertIn("unit test", done.stderr)
        self.assertIn("deadlocks the Docker daemon", done.stderr)

    def test_unknown_command_exits_two(self) -> None:
        done = self.run_reclaim("nonsense")
        self.assertEqual(done.returncode, 2)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 scripts/test_reclaim.py -v`
Expected: FAIL — `scripts/reclaim.sh` does not exist, so `bash` exits 127.

- [ ] **Step 3: Write minimal implementation**

Create `scripts/reclaim.sh`:

```bash
#!/usr/bin/env bash
# The single implementation of disk reclaim for prod deploys, the sandbox
# sweep, and local development. Three copies of this logic used to exist and
# the local one was simply never written, which is how ~21GB accumulated on a
# developer machine with a healthy deploy host.
#
# The host is shared with unrelated Docker stacks, so every destructive call
# stays inside `ca.floo.roadtrip.managed=true`. The one exception is the
# anonymous-volume prune, which by definition cannot filter on a label; it is
# gated behind --include-anonymous, on for --scope=host and off for
# --scope=local.
set -euo pipefail

MANAGED_LABEL="ca.floo.roadtrip.managed=true"

: "${ROADTRIP_IMAGE_RETENTION:=336h}"
: "${RECLAIM_FREE_TARGET_GB:=20}"

# Rollback depth on the host; pure disk pressure locally. A laptop keeps two
# because Tilt rewrites a tag per build and four tags already sit inside a
# keep-5 window, which would free nothing.
HOST_IMAGE_KEEP=5
LOCAL_IMAGE_KEEP=2

SCOPE=host
DRY_RUN=0
INCLUDE_ANONYMOUS=""
MIN_GB=""
DISK_PATH="${HOME}"
LABEL="reclaim"

_usage() {
    cat >&2 <<'USAGE'
usage: reclaim.sh <command> [options]

commands:
  prune        label-scoped reclaim of containers, images, and volumes
  check-disk   exit non-zero when free space is under the floor
  report       print what prune would remove, change nothing

options:
  --scope local|host     default: host
  --dry-run
  --min-gb N
  --path PATH
  --label TEXT
  --include-anonymous
USAGE
}

_parse_args() {
    COMMAND="${1:-}"
    [[ -n "${COMMAND}" ]] || { _usage; exit 2; }
    shift
    while (( $# )); do
        case "$1" in
            --scope) SCOPE="$2"; shift 2 ;;
            --dry-run) DRY_RUN=1; shift ;;
            --min-gb) MIN_GB="$2"; shift 2 ;;
            --path) DISK_PATH="$2"; shift 2 ;;
            --label) LABEL="$2"; shift 2 ;;
            --include-anonymous) INCLUDE_ANONYMOUS=1; shift ;;
            *) echo "error: unknown option $1" >&2; _usage; exit 2 ;;
        esac
    done
    case "${SCOPE}" in
        host|local) ;;
        *) echo "error: --scope must be 'local' or 'host'" >&2; exit 2 ;;
    esac
}

_apply_scope_defaults() {
    if [[ "${SCOPE}" == host ]]; then
        : "${ROADTRIP_IMAGE_KEEP:=${HOST_IMAGE_KEEP}}"
        [[ -n "${INCLUDE_ANONYMOUS}" ]] || INCLUDE_ANONYMOUS=1
    else
        : "${ROADTRIP_IMAGE_KEEP:=${LOCAL_IMAGE_KEEP}}"
        [[ -n "${INCLUDE_ANONYMOUS}" ]] || INCLUDE_ANONYMOUS=0
    fi
    : "${MIN_GB:=${ROADTRIP_MIN_FREE_DISK_GB:-${RECLAIM_FREE_TARGET_GB}}}"
}

# A full disk does not fail a Docker call, it deadlocks the daemon: Docker
# wedges on its own ENOSPC and every later `docker` invocation blocks forever
# on a socket that accepts connections but never answers. That took prod down
# for 14h once.
cmd_check_disk() {
    local free_kb free_gb
    free_kb="$(df -Pk "${DISK_PATH}" | awk 'NR==2 {print $4}')"
    if [[ -z "${free_kb}" ]]; then
        echo "warning: could not read free space on ${DISK_PATH}; skipping ${LABEL} disk check" >&2
        return 0
    fi
    free_gb=$(( free_kb / 1024 / 1024 ))
    if (( free_gb < MIN_GB )); then
        echo "error: ${LABEL} needs ${MIN_GB}GB free on ${DISK_PATH}, found ${free_gb}GB" >&2
        echo "       a full disk deadlocks the Docker daemon; reclaim space before deploying" >&2
        echo "       (run 'scripts/reclaim.sh prune', or 'docker image prune -f' on the host)" >&2
        return 1
    fi
    echo "==> disk check: ${free_gb}GB free on ${DISK_PATH} (minimum ${MIN_GB}GB)"
}

main() {
    _parse_args "$@"
    _apply_scope_defaults
    case "${COMMAND}" in
        check-disk) cmd_check_disk ;;
        *) echo "error: unknown command ${COMMAND}" >&2; _usage; exit 2 ;;
    esac
}

main "$@"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 scripts/test_reclaim.py -v`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
chmod +x scripts/reclaim.sh
git add scripts/reclaim.sh scripts/test_reclaim.py
git update-index --chmod=+x scripts/reclaim.sh
git commit -m "feat(scripts): add reclaim.sh with the shared disk floor check"
```

---

### Task 2: `reclaim.sh prune` and `report`

**Files:**
- Modify: `scripts/reclaim.sh`
- Modify: `scripts/test_reclaim.py`

**Interfaces:**
- Consumes: `_parse_args`, `_apply_scope_defaults`, `MANAGED_LABEL`, `DRY_RUN`, `INCLUDE_ANONYMOUS`, `ROADTRIP_IMAGE_KEEP`, `ROADTRIP_IMAGE_RETENTION` from Task 1.
- Produces: `scripts/reclaim.sh prune [--scope ...] [--dry-run] [--include-anonymous]` and `scripts/reclaim.sh report`. Task 3 and Task 4 call these.

- [ ] **Step 1: Write the failing test**

Append to `scripts/test_reclaim.py`, above the `if __name__` block:

```python
FOUR_TAGS = "\n".join([
    "roadtrip/backend:tilt-aaaa",
    "roadtrip/backend:tilt-bbbb",
    "roadtrip/backend:latest",
    "roadtrip/backend:tilt-cccc",
]) + "\n"


class PruneTest(ReclaimTestCase):
    def test_local_scope_keeps_two_tags_per_repository(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("prune", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        removed = [c for c in self.docker_calls() if c.startswith("image rm ")]
        self.assertIn("image rm roadtrip/backend:latest", removed)
        self.assertIn("image rm roadtrip/backend:tilt-cccc", removed)
        self.assertNotIn("image rm roadtrip/backend:tilt-aaaa", removed)
        self.assertNotIn("image rm roadtrip/backend:tilt-bbbb", removed)

    def test_host_scope_keeps_five_so_four_tags_survive(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("prune", "--scope", "host")
        self.assertEqual(done.returncode, 0, done.stderr)
        removed = [c for c in self.docker_calls()
                   if c.startswith("image rm roadtrip/backend")]
        self.assertEqual(removed, [])

    def test_every_prune_call_is_label_scoped(self) -> None:
        self.run_reclaim("prune", "--scope", "local")
        prunes = [c for c in self.docker_calls() if " prune" in c]
        self.assertTrue(prunes)
        for call in prunes:
            self.assertIn("label=ca.floo.roadtrip.managed=true", call, call)

    def test_local_scope_never_prunes_anonymous_volumes(self) -> None:
        self.run_reclaim("prune", "--scope", "local")
        for call in self.docker_calls():
            if call.startswith("volume prune"):
                self.assertIn("label=", call, call)

    def test_host_scope_does_prune_anonymous_volumes(self) -> None:
        self.run_reclaim("prune", "--scope", "host")
        bare = [c for c in self.docker_calls()
                if c.startswith("volume prune") and "label=" not in c]
        self.assertTrue(bare, "host scope must reach anonymous volumes")

    def test_dry_run_makes_no_destructive_call(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        self.run_reclaim("prune", "--scope", "local", "--dry-run")
        for call in self.docker_calls():
            self.assertFalse(call.startswith("image rm"), call)
            self.assertNotIn(" prune", call)

    def test_report_changes_nothing(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        done = self.run_reclaim("report", "--scope", "local")
        self.assertEqual(done.returncode, 0, done.stderr)
        for call in self.docker_calls():
            self.assertFalse(call.startswith("image rm"), call)

    def test_image_keep_env_override_wins_over_scope_default(self) -> None:
        self.image_ls.write_text(FOUR_TAGS)
        self.run_reclaim("prune", "--scope", "local", ROADTRIP_IMAGE_KEEP="4")
        removed = [c for c in self.docker_calls()
                   if c.startswith("image rm roadtrip/backend")]
        self.assertEqual(removed, [])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 scripts/test_reclaim.py -v`
Expected: FAIL — `prune` and `report` hit the `*)` branch and exit 2.

- [ ] **Step 3: Write minimal implementation**

In `scripts/reclaim.sh`, add after the `HOST_IMAGE_KEEP`/`LOCAL_IMAGE_KEEP` block:

```bash
# Repository list rather than a label filter, because the keep-N window is a
# per-repository idea and `docker image ls` cannot express "labelled". The
# label filter still guards the dangling prune below.
ROADTRIP_REPOSITORIES="
ghcr.io/wwchen/roadtrip/backend
ghcr.io/wwchen/roadtrip/recgov-companion
ghcr.io/wwchen/roadtrip/data
roadtrip/backend
roadtrip/recgov-companion
ghcr.io/wwchen/roadtrip/deploy
"
# The deploy image is rebuilt per release and never rolled back to.
NEVER_KEEP_REPOSITORY="ghcr.io/wwchen/roadtrip/deploy"
```

Add before `main()`:

```bash
# Reads go straight to docker; only destructive calls route through here, so
# --dry-run still sees a real image list and reports real decisions.
_docker() {
    if (( DRY_RUN )); then
        echo "dry-run: docker $*"
        return 0
    fi
    docker "$@"
}

_prune_images() {
    local repository reference image_id index repository_keep
    local references

    echo "==> pruning unused Roadtrip images (keep ${ROADTRIP_IMAGE_KEEP} tags per active repository)"
    for repository in ${ROADTRIP_REPOSITORIES}; do
        repository_keep="${ROADTRIP_IMAGE_KEEP}"
        [[ "${repository}" == "${NEVER_KEEP_REPOSITORY}" ]] && repository_keep=0
        references=()
        while IFS= read -r reference; do
            [[ -n "${reference}" ]] && references+=("${reference}")
        done < <(
            docker image ls "${repository}" --format '{{.Repository}}:{{.Tag}}' \
                | awk '$0 !~ /:<none>$/ && !seen[$0]++'
        )
        (( ${#references[@]} )) || continue
        for index in "${!references[@]}"; do
            (( index < repository_keep )) && continue
            reference="${references[$index]}"
            image_id="$(docker image inspect --format '{{.Id}}' "${reference}" 2>/dev/null || true)"
            [[ -n "${image_id}" ]] || continue
            if [[ -z "$(docker ps -aq --filter "ancestor=${image_id}")" ]]; then
                _docker image rm "${reference}" >/dev/null 2>&1 || true
            fi
        done
    done

    _docker image prune -f \
        --filter "label=${MANAGED_LABEL}" \
        --filter "until=${ROADTRIP_IMAGE_RETENTION}" >/dev/null
}

# One roadtrip-data-<sha> volume per data tree SHA, which the image prune never
# touches. --all because these are named. Rollback depth is the image
# retention's job: deploy.sh repopulates a missing volume, so these are a
# cache, not the record.
_prune_volumes() {
    echo "==> pruning unused Roadtrip data volumes"
    _docker volume prune --force --all --filter "label=${MANAGED_LABEL}" | tail -1
    if (( INCLUDE_ANONYMOUS )); then
        echo "==> pruning anonymous volumes"
        _docker volume prune --force | tail -1
    fi
}

_prune_containers() {
    _docker container prune --force --filter "label=${MANAGED_LABEL}" | tail -1
}

cmd_prune() {
    _prune_containers
    _prune_images
    _prune_volumes
}

cmd_report() {
    DRY_RUN=1
    cmd_prune
    docker system df
}
```

Extend the dispatch in `main()`:

```bash
    case "${COMMAND}" in
        check-disk) cmd_check_disk ;;
        prune) cmd_prune ;;
        report) cmd_report ;;
        *) echo "error: unknown command ${COMMAND}" >&2; _usage; exit 2 ;;
    esac
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 scripts/test_reclaim.py -v`
Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add scripts/reclaim.sh scripts/test_reclaim.py
git commit -m "feat(scripts): add label-scoped prune and report to reclaim.sh"
```

---

### Task 3: Rewire `deploy.sh` onto `reclaim.sh`

**Files:**
- Modify: `scripts/deploy.sh` (delete `_require_free_disk`, `_prune_roadtrip_images`, `_prune_data_volumes`; update their 5 call sites)
- Modify: `scripts/test_reclaim.py`

**Interfaces:**
- Consumes: `reclaim.sh check-disk` and `reclaim.sh prune --scope=host` from Tasks 1-2, plus `SCRIPT_DIR` already defined at `scripts/deploy.sh:4`.
- Produces: no new interface. `deploy.sh`'s own subcommands are unchanged.

- [ ] **Step 1: Write the failing test**

Append to `scripts/test_reclaim.py`, above the `if __name__` block:

```python
class DeployIntegrationTest(unittest.TestCase):
    """deploy.sh must delegate reclaim, and must NOT lose the volume hold.

    The hold at _hold_data_volume exists because prod, sandbox, and the sweep
    sit in three different concurrency groups, so a prune can land in the
    window where a data volume exists but nothing mounts it yet. Moving the
    prune out of deploy.sh is exactly the change most likely to drop it.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.source = (ROOT / "scripts" / "deploy.sh").read_text()

    def test_private_reclaim_helpers_are_gone(self) -> None:
        for name in ("_require_free_disk", "_prune_roadtrip_images",
                     "_prune_data_volumes"):
            self.assertNotIn(f"{name}() {{", self.source,
                             f"{name} should have moved to reclaim.sh")

    def test_deploy_delegates_to_reclaim(self) -> None:
        # Asserting on the exact call, not the bare word "prune", which still
        # appears in the volume-hold comments and would pass either way.
        self.assertIn('RECLAIM="${SCRIPT_DIR}/reclaim.sh"', self.source)
        self.assertIn('"${RECLAIM}" check-disk --label "prod deploy"', self.source)
        self.assertIn('"${RECLAIM}" check-disk --label "sandbox deploy"', self.source)
        self.assertEqual(self.source.count('"${RECLAIM}" prune --scope host'), 2)

    def test_volume_hold_survives(self) -> None:
        self.assertIn("_hold_data_volume() {", self.source)
        self.assertIn("_release_data_volume() {", self.source)
        self.assertIn("DATA_VOLUME_GUARD_SECONDS", self.source)

    def test_no_bare_docker_prune_left_in_deploy(self) -> None:
        for line in self.source.splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            if "docker image prune" in stripped or "docker volume prune" in stripped:
                self.fail(f"deploy.sh still prunes directly: {stripped}")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 scripts/test_reclaim.py -v`
Expected: FAIL — all four `DeployIntegrationTest` cases fail; the helpers are still present.

- [ ] **Step 3: Write minimal implementation**

In `scripts/deploy.sh`:

1. Delete the `_require_free_disk()` function (around lines 56-76) and the `_prune_roadtrip_images()` and `_prune_data_volumes()` functions (around lines 214-266).

2. Add near the other path constants at the top, after line 5:

```bash
RECLAIM="${SCRIPT_DIR}/reclaim.sh"
```

3. Replace each `_require_free_disk "<label>"` call site with:

```bash
"${RECLAIM}" check-disk --label "<label>" --scope host || exit 1
```

There are two: `_require_free_disk "prod deploy"` (around line 384) and `_require_free_disk "sandbox deploy"` (around line 846).

4. Replace each adjacent pair of `_prune_roadtrip_images` / `_prune_data_volumes` calls with a single:

```bash
"${RECLAIM}" prune --scope host
```

There are two such pairs, around lines 444-445 and 815-816.

Leave `_hold_data_volume`, `_release_data_volume`, and `_ensure_data_volume` untouched.

- [ ] **Step 4: Run the tests**

Run: `python3 scripts/test_reclaim.py -v`
Expected: PASS — 15 tests.

Run: `python3 scripts/test_deploy_stale_cleanup.py -v`
Expected: PASS — unchanged, proving the refactor did not disturb the stale-deploy path.

Run: `bash -n scripts/deploy.sh`
Expected: no output (syntax clean).

- [ ] **Step 5: Commit**

```bash
git add scripts/deploy.sh scripts/test_reclaim.py
git commit -m "refactor(deploy): delegate disk reclaim to reclaim.sh"
```

---

### Task 4: Sandbox sweep and `make reclaim`

**Files:**
- Modify: `.github/workflows/sandbox-sweep.yml:47-84` (the `reclaim` job's script step)
- Modify: `Makefile` (`.PHONY` line 1, `help` target, new `reclaim` target)

**Interfaces:**
- Consumes: `reclaim.sh prune --scope host` and `--scope local` from Task 2.
- Produces: `make reclaim` and `make reclaim-report` for local use.

- [ ] **Step 1: Replace the sweep's inline heredoc**

In `.github/workflows/sandbox-sweep.yml`, replace the whole `Reclaim disk on the deploy host` step's `run:` body with:

```yaml
      - name: Reclaim disk on the deploy host
        env:
          RECLAIM_FREE_TARGET_GB: ${{ vars.RECLAIM_FREE_TARGET_GB || 20 }}
        run: |
          set -euo pipefail
          # Streamed rather than run from the release staging directory, so the
          # sweep keeps working even when no release has landed yet.
          ssh -o BatchMode=yes "$SANDBOX_HOST" \
            "RECLAIM_FREE_TARGET_GB='$RECLAIM_FREE_TARGET_GB' bash -s -- prune --scope host" \
            < scripts/reclaim.sh
          ssh -o BatchMode=yes "$SANDBOX_HOST" \
            "RECLAIM_FREE_TARGET_GB='$RECLAIM_FREE_TARGET_GB' bash -s -- check-disk --label 'deploy host' --scope host" \
            < scripts/reclaim.sh || \
            echo "::warning::deploy host is under the ${RECLAIM_FREE_TARGET_GB}GB free-space target; deploys will fail preflight until space is reclaimed"
```

Leave the `plan` and `stop` jobs untouched — sandbox TTL is a different axis and stays where it is.

- [ ] **Step 2: Add the Makefile target**

Add `reclaim` and `reclaim-report` to the `.PHONY` list on line 1, add these lines to the `help` target after the `grafana-export` line:

```make
	@echo "  make reclaim          Reclaim local Docker disk (roadtrip-labelled resources only)"
	@echo "  make reclaim-report   Show what make reclaim would remove, change nothing"
```

and add the targets at the end of the file:

```make
# Local scope keeps 2 tags per repo and never touches anonymous volumes, so
# unrelated stacks sharing this machine are out of range.
reclaim:
	@scripts/reclaim.sh prune --scope local

reclaim-report:
	@scripts/reclaim.sh report --scope local
```

- [ ] **Step 3: Verify**

Run: `make reclaim-report`
Expected: prints the images it would remove and a `docker system df` table, removes nothing.

Run: `python3 scripts/test_docker_compose.py -v`
Expected: PASS — unchanged.

Confirm the workflow still parses:

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/sandbox-sweep.yml'))"`
Expected: no output.

- [ ] **Step 4: Run reclaim for real and record the result**

Run: `make reclaim`
Expected: removes the stale `tilt-*` tags beyond the newest two per repository.

Run: `docker system df`
Expected: image total below the 12.4 GB starting point.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/sandbox-sweep.yml Makefile
git commit -m "feat(make): add make reclaim and drop the sweep's duplicate prune"
```

---

### Task 5: Make the worktree guardrail check that `sparsePaths` is applied

**Files:**
- Modify: `scripts/check-worktree-sparse-paths.mjs`

**Interfaces:**
- Consumes: nothing from earlier tasks. This is git hygiene, not Docker, and shares no code with `reclaim.sh`.
- Produces: a non-zero exit when a worktree under `.claude/worktrees` has no sparse-checkout applied.

**Note for the implementer:** this check is expected to FAIL on the current repository. That is the deliverable — five worktrees carry a full 1.7 GB `data/raw` copy because the setting has never taken effect. Do not "fix" the failure by weakening the check.

- [ ] **Step 1: Add the assertion**

In `scripts/check-worktree-sparse-paths.mjs`, add after the existing `stale` computation:

```javascript
// The list above only proves the config is well-formed. It was well-formed for
// weeks while no worktree ever had it applied, so every worktree carried the
// 1.7 GB data/raw copy the list exists to exclude. Check the effect, not the
// spelling.
const WORKTREE_ROOT = '.claude/worktrees';
const unapplied = [];
let worktreeDirs = [];
try {
  worktreeDirs = readdirSync(resolve(ROOT, WORKTREE_ROOT), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
} catch {
  // No worktrees on this checkout; nothing to verify.
}

for (const name of worktreeDirs) {
  const worktree = resolve(ROOT, WORKTREE_ROOT, name);
  let enabled = '';
  try {
    enabled = execFileSync('git', ['-C', worktree, 'config', 'core.sparseCheckout'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch {
    enabled = '';
  }
  if (enabled !== 'true') unapplied.push(name);
}

if (unapplied.length) {
  console.error(
    `\nWorktrees with worktree.sparsePaths never applied — each carries a full copy of\n` +
      `${[...EXCLUDED.keys()].join(', ')} that sparsePaths exists to exclude. Claude Code\n` +
      'owns applying this setting at worktree creation; a passing config check does not\n' +
      'mean it took effect:\n' +
      unapplied.map((d) => `  ${WORKTREE_ROOT}/${d}`).join('\n'),
  );
}
```

Extend the import on line 2 and the final exit condition:

```javascript
import { readFileSync, readdirSync } from 'node:fs';
```

```javascript
if (malformed.length || unknown.length || omitted.length || stale.length || unapplied.length)
  process.exit(1);
```

- [ ] **Step 2: Run the checker**

Run: `node scripts/check-worktree-sparse-paths.mjs`
Expected: FAIL, listing all five worktrees under `.claude/worktrees` as unapplied. This is the correct result.

- [ ] **Step 3: Verify it passes when there are no worktrees**

Run: `git -C /tmp init -q reclaim-probe && cd /tmp/reclaim-probe` is **not** needed — instead confirm the `catch` path by temporarily renaming:

```bash
mv .claude/worktrees .claude/worktrees.bak
node scripts/check-worktree-sparse-paths.mjs
mv .claude/worktrees.bak .claude/worktrees
```

Expected: the middle command prints the existing `worktree sparsePaths ok` line and exits 0.

- [ ] **Step 4: Commit**

```bash
git add scripts/check-worktree-sparse-paths.mjs
git commit -m "fix(scripts): assert worktree sparsePaths is applied, not just valid"
```

---

## Follow-up, not in this plan

- **The five existing worktrees.** Task 5 makes the leak visible; it does not reclaim the 8.5 GB. Decide per worktree whether to retrofit sparse-checkout (`git -C <worktree> sparse-checkout set --cone -- <paths from settings.json>`) or retire it. They hold unmerged branches, so this is a human call.
- **Upstream.** Claude Code is not applying `worktree.sparsePaths` at worktree creation. Worth filing; the guardrail can only report it.
