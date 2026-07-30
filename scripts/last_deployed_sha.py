#!/usr/bin/env python3
"""Print the SHA of the last commit the Deploy workflow actually deployed.

The Deploy workflow diffs against the last *deployed* commit rather than the
push's parent, so that a runtime change whose CI failed isn't silently dropped
when the next (doc-only) push passes. That only works if the baseline is a
commit that really shipped.

"Newest Deploy run with conclusion=success" is not that commit. Deploy's own
path gate skips the `deploy` job on a doc-only push, and a workflow whose only
real job was skipped still concludes `success`. Trusting the run conclusion
would let such a run become the baseline, and the next push would diff against a
SHA that was never deployed — stranding the runtime change in between, which is
the exact failure the baseline logic exists to prevent.

So the evidence has to come from the job, not the run: walk completed Deploy runs
newest-first and take the first one whose `deploy` job concluded `success`.
Anything else (no runs, a renamed job, an API failure) prints nothing, which the
workflow reads as "no known baseline" and deploys — unknown state deploys rather
than skips.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from collections.abc import Callable, Iterable, Sequence

# The job whose success means "this run put a commit on the host". Renaming the
# job in deploy.yml without renaming it here yields no baseline, i.e. a deploy.
DEPLOY_JOB = "deploy"
WORKFLOW_FILE = "deploy.yml"
BRANCH = "master"
# One API call per candidate run, so this bounds the lookup's cost. A window
# this wide only gets walked when the recent history is all doc-only pushes.
CANDIDATE_RUNS = 50


def deploy_job_succeeded(jobs: Iterable[dict]) -> bool:
    """Whether this run's `deploy` job ran to completion successfully.

    A run whose `deploy` job was skipped, cancelled, or failed did not deploy,
    and neither did one with no `deploy` job at all.
    """
    for job in jobs:
        if job.get("name") == DEPLOY_JOB:
            return job.get("conclusion") == "success"
    return False


def select_deployed_sha(
    runs: Sequence[dict],
    jobs_for_run: Callable[[int], Iterable[dict]],
) -> str:
    """The head SHA of the newest run in `runs` that actually deployed.

    `runs` must be newest-first (the API returns them that way). Returns "" when
    no run in the window carries proof that its deploy job succeeded.
    """
    for run in runs:
        run_id = run.get("id")
        if run_id is None:
            continue
        if not deploy_job_succeeded(jobs_for_run(run_id)):
            continue
        sha = str(run.get("head_sha") or "")
        if sha:
            return sha
    return ""


def _gh_api(path: str, jq: str) -> object:
    out = subprocess.run(
        ["gh", "api", path, "--jq", jq],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return json.loads(out) if out else None


def main() -> int:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not repo:
        print("GITHUB_REPOSITORY is unset; no baseline", file=sys.stderr)
        return 0

    def jobs_for_run(run_id: int) -> Iterable[dict]:
        jobs = _gh_api(
            f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100",
            "[.jobs[] | {name, conclusion}]",
        )
        return jobs or []

    try:
        runs = _gh_api(
            f"repos/{repo}/actions/workflows/{WORKFLOW_FILE}/runs"
            f"?status=completed&branch={BRANCH}&per_page={CANDIDATE_RUNS}",
            "[.workflow_runs[] | {id, head_sha}]",
        )
        sha = select_deployed_sha(runs or [], jobs_for_run)
    except (subprocess.CalledProcessError, json.JSONDecodeError, OSError) as exc:
        # Deliberately not fatal: an unreadable history is "unknown baseline",
        # and unknown deploys. Failing here would block deploys on an API blip.
        print(f"could not read Deploy history ({exc}); no baseline", file=sys.stderr)
        return 0

    if sha:
        print(sha)
    return 0


if __name__ == "__main__":
    sys.exit(main())
