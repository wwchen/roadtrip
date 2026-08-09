#!/usr/bin/env python3
"""Regression tests for the PR sandbox lifecycle workflow."""

from __future__ import annotations

import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SANDBOX_WORKFLOW = ROOT / ".github" / "workflows" / "sandbox.yml"


def workflow() -> dict:
    # BaseLoader preserves the literal key "on" (YAML 1.1 SafeLoader treats it
    # as a boolean), which makes the event contract straightforward to assert.
    return yaml.load(SANDBOX_WORKFLOW.read_text(), Loader=yaml.BaseLoader)


def step(name: str) -> dict:
    for candidate in workflow()["jobs"]["sandbox"]["steps"]:
        if candidate.get("name") == name:
            return candidate
    raise AssertionError(f"sandbox workflow has no {name!r} step")


class SandboxWorkflowTest(unittest.TestCase):
    def test_active_sandboxes_update_after_ci_completes(self) -> None:
        triggers = workflow()["on"]
        self.assertIn("issue_comment", triggers)
        self.assertEqual(["CI"], triggers["workflow_run"]["workflows"])
        self.assertEqual(["completed"], triggers["workflow_run"]["types"])

        resolver = step("Resolve sandbox operation")["with"]["script"]
        self.assertIn("sandbox-state:active", resolver)
        self.assertIn("github-actions[bot]", resolver)
        self.assertIn("pr.head.sha !== runSha", resolver)
        self.assertIn("sandbox-ci-sha:", resolver)
        self.assertIn("Sandbox is already live", resolver)

    def test_only_successful_ci_runs_can_redeploy(self) -> None:
        # The automatic path skips the GHCR image wait on the premise that CI
        # already published backend:<head_sha>. A failed run may have died before
        # the publish, and the host would then silently fall back to an ancestor
        # image while reporting the new SHA.
        gate = workflow()["jobs"]["sandbox"]["if"]
        self.assertIn("github.event.workflow_run.conclusion == 'success'", gate)

    def test_teardown_never_records_an_active_sandbox(self) -> None:
        # A failed or timed-out teardown must not leave an `active` record: the
        # next CI completion would resurrect the sandbox the user stopped.
        for name in ("Init status comment", "Status deploying", "Status failed"):
            script = step(name)["with"]["script"]
            with self.subTest(step=name):
                self.assertIn("sandbox-state:inactive", script)
                self.assertIn("isStop", script)

    def test_automatic_updates_expire_with_the_host_reaper(self) -> None:
        # sandbox_reap.sh tears sandboxes down on the host without editing the PR
        # comment, so the activation record has to expire on the same clock or
        # every later CI completion re-creates what the reaper removed.
        resolve = step("Resolve sandbox operation")
        self.assertIn("SANDBOX_TTL_HOURS", resolve["env"]["SANDBOX_AUTO_UPDATE_TTL_HOURS"])
        script = resolve["with"]["script"]
        self.assertIn("sandbox-activated-at:", script)
        self.assertIn("auto-update TTL", script)
        # An automatic update inherits the stamp rather than renewing it.
        self.assertIn("activated_at", script)
        self.assertIn("ACTIVATED_AT", step("Init status comment")["env"])

    def test_pr_resolution_does_not_trust_arbitrary_ordering(self) -> None:
        script = step("Resolve sandbox operation")["with"]["script"]
        # Indexing straight into pull_requests can name a PR that does not own
        # the sandbox; candidates must be filtered by head SHA and then ordered.
        self.assertNotIn("prNumber = run.pull_requests", script)
        self.assertIn("candidate.head.sha === runSha", script)
        self.assertIn("candidates.sort", script)
        # The old merge_commit_sha branch was unreachable: the head-SHA check
        # below it always rejected such a match.
        self.assertNotIn("merge_commit_sha", script)

    def test_command_parsing_tolerates_whitespace(self) -> None:
        script = step("Resolve sandbox operation")["with"]["script"]
        self.assertIn(r"replace(/\s+/g, ' ')", script)

    def test_in_flight_updates_stand_down_when_stopped(self) -> None:
        # The concurrency key cannot span both event types, so a /sandbox stop
        # can overlap an automatic update; the late re-check is what stops the
        # two from stomping each other on the host.
        recheck = step("Re-check sandbox state")
        self.assertEqual(
            "steps.pr.outputs.action == 'up' && steps.pr.outputs.automatic == 'true'",
            recheck["if"].strip(),
        )
        self.assertIn("sandbox-state:inactive", recheck["with"]["script"])

        gated = "steps.recheck.outputs.proceed == 'true'"
        for name in ("Run on host (up)", "Status live", "Status deploying"):
            with self.subTest(step=name):
                self.assertIn(gated, step(name)["if"])

        # A stood-down run must not leave the comment reading "Updating…".
        self.assertIn(
            "steps.recheck.outputs.proceed == 'false'",
            step("Status stood down")["if"],
        )

    def test_concurrency_key_never_falls_back_to_a_sha(self) -> None:
        # Keying on head_sha put two events for the same PR in different groups.
        group = workflow()["concurrency"]["group"]
        self.assertIn("github.event.workflow_run.head_branch", group)
        self.assertNotIn("head_sha", group)

    def test_status_comment_is_the_persistent_activation_record(self) -> None:
        init_script = step("Init status comment")["with"]["script"]
        live_script = step("Status live")["with"]["script"]
        stopped_script = step("Status stopped")["with"]["script"]

        self.assertIn("sandbox-state:active", init_script)
        self.assertIn("sandbox-sha:", init_script)
        self.assertIn("sandbox-state:active", live_script)
        self.assertIn("sandbox-ci-sha:", live_script)
        self.assertIn("sandbox-activated-at:", live_script)
        self.assertIn("sandbox-state:inactive", stopped_script)

    def test_redeploy_targets_the_stable_pr_sandbox(self) -> None:
        host_step = step("Run on host (up)")
        self.assertIn("scripts/sandbox_up.sh \"$pr_number\"", host_step["run"])
        self.assertIn("steps.pr.outputs.action == 'up'", host_step["if"])


if __name__ == "__main__":
    unittest.main()
