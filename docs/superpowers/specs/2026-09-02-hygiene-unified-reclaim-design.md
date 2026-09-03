# Unified disk reclaim across dev, deploy, and sandbox

**Date:** 2026-09-02
**Status:** Approved design, not yet implemented

## Problem

A local checkout plus its Docker state measured ~35 GB. The deploy host and
sandbox fleet turned out to be the healthy parts; the bloat is local, and it
traces to two seams that were designed but never actually connected.

### `sparsePaths` has no effect

`.claude/settings.json` names 19 cone-mode directories and deliberately omits
`data/raw` (1.7 GB, 19,136 tracked files). `scripts/check-worktree-sparse-paths.mjs`
guards that list and passes. But no worktree has ever had the setting applied:

| Worktree | Created | vs #705 (2026-09-01 21:02) | `info/sparse-checkout` |
| --- | --- | --- | --- |
| `interesting-proskuriakova-a52f67` | 09-01 07:12 | before | absent |
| `eloquent-noether-4f8ca4` | 09-01 20:37 | before | absent |
| `relaxed-franklin-a41c0b` | 09-02 13:35 | after | absent |
| `code-cleanup-opportunities-b890fb` | 09-02 13:53 | after | absent |
| `pr-676-review-c485ce` | 09-02 13:56 | after | absent |

`core.sparseCheckout` is unset in all five. Three were created after #705
landed, so this is not legacy debt: #705 fixed the list's *syntax*, and the
list is still never applied. Each worktree therefore carries a full 1.7 GB
`data/raw` copy plus 282 MB of `node_modules`, ~2.0 GB each, 9.1 GB total.

The guardrail passed the whole time because it validates the config's shape
and never checks that the config changed anything.

### Locally built images carry no ownership label

`scripts/deploy.sh` prunes with `--filter label=ca.floo.roadtrip.managed=true`,
and `sandbox-sweep.yml` filters on the same label. That label is applied to
containers and volumes at run time; it is not baked into any image. Nothing
in the local build path applies it either — `docker_build()`'s `labels=`
argument in the `Tiltfile` is Tilt UI metadata, not Docker image labels.

Result: 12.4 GB of local images, including four tags each of
`roadtrip/recgov-companion` (~3.8 GB per tag) and `roadtrip/backend`
(~690 MB per tag), unreachable by the label-scoped prune that keeps the
deploy host clean.

### The same logic exists three times

| Concern | `scripts/deploy.sh` | `.github/workflows/sandbox-sweep.yml` | Local |
| --- | --- | --- | --- |
| Retention | `ROADTRIP_IMAGE_RETENTION:-336h`, `ROADTRIP_IMAGE_KEEP:-5` | `RECLAIM_FREE_TARGET_GB \|\| 20` | `defaultKeepStorage: 20GB` |
| Prune | `_prune_roadtrip_images`, `_prune_data_volumes` | inline heredoc duplicating both | none |
| Disk floor | `_require_free_disk` | `free_gb()` reimplemented | none |

Three implementations of one idea, and the third context was simply forgotten.

## Scope

**In scope.** One reclaim implementation shared by all three contexts; image
ownership labels; a local entry point; a guardrail that checks `sparsePaths`
is in effect rather than merely well-formed.

**Out of scope.** Sandbox TTL stays in `sandbox-sweep.yml`'s `plan` job. TTL
is sandbox age, a different axis from disk retention, and folding it in would
be false unification. The two scratchpad worktrees (`sdd`, `wt669`) were not
created by Claude Code and are not covered by the worktree guardrail.

## Design

### 1. Retention constants

Defaults live in `scripts/reclaim.sh` as `: "${NAME:=value}"`, overridable by
environment — code default, config override, per `AGENTS.md`. Existing names
`ROADTRIP_IMAGE_RETENTION` (336h) and `ROADTRIP_IMAGE_KEEP` (5) are preserved
so current overrides keep working. `RECLAIM_FREE_TARGET_GB` becomes the one
name for the disk floor; `sandbox-sweep.yml` continues to supply it from
`vars.RECLAIM_FREE_TARGET_GB`.

No new config file. The script is the single source of truth.

### 2. `scripts/reclaim.sh`

```
scripts/reclaim.sh <command> [options]

commands:
  prune        label-scoped reclaim of containers, images, volumes
  check-disk   exit non-zero when free space is under the floor
  report       print what prune would remove, change nothing

options:
  --scope local|host     default: host
  --dry-run
  --min-gb N
  --path PATH
  --label TEXT           human label used in messages
  --include-anonymous    also prune unlabelled anonymous volumes
```

Every destructive filter carries `label=ca.floo.roadtrip.managed=true`. The
one unlabelled operation — the sweep's bare `docker volume prune --force`,
which by definition cannot filter on a label — is gated behind
`--include-anonymous`, which defaults **on for `--scope=host`** and **off for
`--scope=local`**. That default is what keeps a local reclaim away from the
unrelated `teslamate_*` and `roadtripv2_*` stacks sharing the machine.

Callers:

- `scripts/deploy.sh` replaces `_require_free_disk` with
  `reclaim.sh check-disk`, and `_prune_roadtrip_images` / `_prune_data_volumes`
  with `reclaim.sh prune --scope=host`.
- `sandbox-sweep.yml`'s `reclaim` job replaces its heredoc with
  `ssh "$SANDBOX_HOST" bash -s -- prune --scope=host < scripts/reclaim.sh`,
  so the host needs nothing pre-installed.
- `make reclaim` runs `reclaim.sh prune --scope=local`.

**Constraint that must survive the refactor.** `scripts/deploy.sh:140-171`
holds a data volume open against a concurrent prune, because the sweep and a
deploy are in different concurrency groups and a prune can land inside the
window where the volume exists but no container refers to it. Moving the
prune must not move it out from under that hold. This is the subtlest part of
the change and needs a test of its own.

### 3. Image ownership labels

Add `LABEL ca.floo.roadtrip.managed=true` to the backend, companion, and data
Dockerfiles. Labelling at the image definition means every builder — Tilt,
`deploy.sh`, CI — produces reclaimable images without each build path having
to remember. This is what makes `--scope=local` able to reach the stale Tilt
tags at all.

### 4. Worktree hygiene

Local only, and a different mechanism from the Docker work: git, no label
contract, no shared code with `reclaim.sh`. It shares a section here because
it is where the disk actually went.

`scripts/check-worktree-sparse-paths.mjs` gains a second assertion: for each
worktree under `.claude/worktrees`, `core.sparseCheckout` is `true` and
`info/sparse-checkout` matches `settings.json`. On the evidence above this
check fails today, which is the correct outcome — it converts a silent 8.5 GB
leak into a visible failure.

The guardrail can only report. Claude Code owns applying `sparsePaths`, so
the fix is upstream and worth filing; the check exists so the gap cannot go
unnoticed again.

`make reclaim` reports stale worktrees (merged or gone branches) but
**defaults to dry-run for anything touching a worktree**, since worktrees can
hold unmerged work. Removal stays an explicit, separate action.

## Testing

- `scripts/test_reclaim.py`, following the existing pytest pattern in
  `scripts/test_deploy_stale_cleanup.py` and `scripts/test_docker_compose.py`.
  Cases: label filter present on every destructive call; `--include-anonymous`
  off under `--scope=local`; keep-N-tags-per-repo; `check-disk` exit codes;
  `--dry-run` changes nothing.
- A regression test for the volume-hold window described in §2.
- New cases in the sparse-path checker covering an applied worktree, an
  unapplied one, and one whose `info/sparse-checkout` has drifted from
  `settings.json`.

## Risks

- **Over-broad prune.** Mitigated by the label contract and the scope-dependent
  `--include-anonymous` default; `report` exists so the blast radius can be
  inspected before trusting it.
- **Regressing the deploy/sweep race.** The most likely way this change breaks
  something that currently works. Covered by a dedicated test.
- **The guardrail fails immediately on merge.** Intended, but it means the
  worktree section should land with a decision already made about the existing
  five worktrees — retrofit sparse-checkout onto them, or retire them.
