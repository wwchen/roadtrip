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
        self.assertIn("pr.head.sha !== completedHeadSha", resolver)
        self.assertIn("sandbox-ci-sha:", resolver)
        self.assertIn("Sandbox is already live", resolver)

    def test_status_comment_is_the_persistent_activation_record(self) -> None:
        init_script = step("Init status comment")["with"]["script"]
        live_script = step("Status live")["with"]["script"]
        stopped_script = step("Status stopped")["with"]["script"]

        self.assertIn("sandbox-state:active", init_script)
        self.assertIn("sandbox-sha:", init_script)
        self.assertIn("sandbox-state:active", live_script)
        self.assertIn("sandbox-ci-sha:", live_script)
        self.assertIn("sandbox-state:inactive", stopped_script)

    def test_redeploy_targets_the_stable_pr_sandbox(self) -> None:
        host_step = step("Run on host (up)")
        self.assertIn("scripts/sandbox_up.sh \"$pr_number\"", host_step["run"])
        self.assertEqual("steps.pr.outputs.action == 'up'", host_step["if"])


if __name__ == "__main__":
    unittest.main()
