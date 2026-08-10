#!/usr/bin/env python3

import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def workflow(name: str) -> dict:
    return yaml.safe_load((ROOT / ".github" / "workflows" / name).read_text())


def job_commands(job: dict) -> str:
    return "\n".join(str(step.get("run", "")) for step in job.get("steps", []))


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
        for name in ("deploy.yml", "sandbox.yml"):
            text = (ROOT / ".github" / "workflows" / name).read_text()
            self.assertIn("uses: ./.github/actions/install-release", text)

        for retired in ("sandbox_up.sh", "sandbox_down.sh", "ensure_data_volume.sh"):
            self.assertFalse((ROOT / "scripts" / retired).exists())

        entries = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()
        self.assertTrue(entries)
        for entry in entries:
            self.assertTrue((ROOT / entry).exists(), entry)


if __name__ == "__main__":
    unittest.main()
