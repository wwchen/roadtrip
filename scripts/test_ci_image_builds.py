#!/usr/bin/env python3

import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def workflow(name: str) -> dict:
    return yaml.safe_load((ROOT / ".github" / "workflows" / name).read_text())


def workflow_triggers(config: dict) -> dict:
    # PyYAML still treats unquoted `on` as a YAML 1.1 boolean.
    return config.get("on") or config.get(True)


def job_commands(job: dict) -> str:
    return "\n".join(str(step.get("run", "")) for step in job.get("steps", []))


def step_by_name(job: dict, name: str) -> dict:
    for step in job.get("steps", []):
        if step.get("name") == name:
            return step
    raise AssertionError(f"missing step {name!r}")


class DeploymentContractTest(unittest.TestCase):
    def test_ci_publishes_the_three_immutable_images(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        app = jobs["docker-build"]
        self.assertNotIn("if", app)
        self.assertIn('docker push "$IMAGE:${SHA}"', job_commands(app))

        for job_name, tree in (("companion-image", "companion"), ("data-image", "data")):
            commands = job_commands(jobs[job_name])
            self.assertIn(f"git rev-parse HEAD:{tree}", commands)
            self.assertIn("docker push", commands)

    def test_production_only_pulls_images(self) -> None:
        deploy = (ROOT / "scripts" / "deploy.sh").read_text()
        self.assertNotIn("docker build", deploy)
        self.assertNotIn("docker compose build", deploy)
        self.assertIn("pull backend recgov-companion", deploy)

    def test_prod_and_sandbox_share_one_runtime_release(self) -> None:
        # Prod installs the release straight from its workflow. The sandbox path
        # does it inside the sandbox action, which has to join the tailnet first.
        for path in (
            ROOT / ".github" / "workflows" / "deploy.yml",
            ROOT / ".github" / "actions" / "sandbox" / "action.yml",
        ):
            self.assertIn(
                "uses: ./.github/actions/install-release", path.read_text(), str(path)
            )

        for retired in ("sandbox_up.sh", "sandbox_down.sh", "ensure_data_volume.sh"):
            self.assertFalse((ROOT / "scripts" / retired).exists())

        entries = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()
        self.assertTrue(entries)
        for entry in entries:
            self.assertTrue((ROOT / entry).exists(), entry)

    def test_tilt_builds_the_backend_without_the_production_frontend_stage(self) -> None:
        dockerfile = (ROOT / "Dockerfile").read_text()
        tiltfile = (ROOT / "Tiltfile").read_text()

        self.assertIn("FROM backend-base AS backend-local", dockerfile)
        self.assertIn("FROM backend-base AS backend", dockerfile)
        self.assertIn("target='backend-local'", tiltfile)

    def test_sandbox_redeploys_active_pr_sandbox_on_new_commits(self) -> None:
        sandbox = workflow("sandbox.yml")
        triggers = workflow_triggers(sandbox)
        sandbox_job = sandbox["jobs"]["sandbox"]
        resolve_script = step_by_name(sandbox_job, "Resolve request")["with"]["script"]

        self.assertEqual(["closed", "synchronize"], triggers["pull_request"]["types"])
        self.assertNotIn("teardown-on-close", sandbox["jobs"])
        self.assertIn("github.event.action == 'synchronize'", sandbox_job["if"])
        self.assertIn("github.event.action == 'closed'", sandbox_job["if"])
        self.assertIn("context.eventName === 'pull_request'", resolve_script)
        self.assertIn("context.payload.action === 'closed'", resolve_script)
        self.assertIn("requireExisting: 'true'", resolve_script)
        self.assertIn("reason: 'this PR closed'", resolve_script)
        self.assertIn("sandbox-status:pr${pr.number}", resolve_script)
        self.assertIn("SANDBOX_TORN_DOWN_HEADING", resolve_script)
        self.assertIn("SANDBOX_TEARING_DOWN_HEADING", resolve_script)
        self.assertIn("SANDBOX_TEARDOWN_FAILED_HEADING", resolve_script)
        self.assertIn("setRequest({ operation: 'skip'", resolve_script)
        self.assertIn("operation: 'start'", resolve_script)

    def test_sandbox_status_comment_updates_before_image_wait(self) -> None:
        sandbox = workflow("sandbox.yml")
        sweep = workflow("sandbox-sweep.yml")
        sandbox_action = (ROOT / ".github" / "actions" / "sandbox" / "action.yml").read_text()
        sandbox_job = sandbox["jobs"]["sandbox"]
        step_names = [step["name"] for step in sandbox_job["steps"]]
        status_step = step_by_name(sandbox_job, "Status workflow started")
        status_script = status_step["with"]["script"]

        self.assertEqual("write", sandbox["permissions"]["issues"])
        self.assertEqual("write", sweep["permissions"]["issues"])
        self.assertLess(
            step_names.index("Status workflow started"),
            step_names.index("Check out commit to deploy"),
        )
        self.assertLess(
            step_names.index("Status workflow started"),
            step_names.index("Sandbox"),
        )
        self.assertIn("workflow started", status_script)
        self.assertIn("SANDBOX_TRIGGER", status_step["env"])
        self.assertNotIn("SANDBOX_PR_NUMBER", status_step["env"])
        self.assertNotIn("SANDBOX_PR_NUMBER", sandbox_action)
        self.assertIn("Log in to GHCR", sandbox_action)
        self.assertIn("Wait for GHCR images", sandbox_action)
        self.assertLess(
            sandbox_action.index("- name: Wait for GHCR images"),
            sandbox_action.index("- name: Start sandbox"),
        )
        self.assertIn("not creating one", sandbox_action)


if __name__ == "__main__":
    unittest.main()
