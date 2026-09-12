#!/usr/bin/env python3

import re
import unittest
from pathlib import Path, PurePosixPath

import yaml


ROOT = Path(__file__).resolve().parents[1]

AWAIT_IMAGES_ACTION = (
    ROOT / ".github" / "actions" / "await-images" / "action.yml"
).read_text()

# The shipped shell scripts address their siblings through ${SCRIPT_DIR} and
# the release root through ${REPO_ROOT}. On the deploy host both resolve
# inside the unpacked release, so a file the manifest omits is simply absent
# there.
SHELL_PATH_REFERENCE = re.compile(r"\$\{(SCRIPT_DIR|REPO_ROOT)\}/([A-Za-z0-9._/-]+)")
SHELL_REFERENCE_ROOTS = {"SCRIPT_DIR": "scripts", "REPO_ROOT": ""}


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
        # The job may be skipped on a PR that cannot change the image (docs
        # only), but every master push must still publish backend:<sha>,
        # because deploy pulls exactly that tag.
        self.assertIn("github.event_name == 'push' ||", app.get("if", ""))
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

    def test_the_release_ships_every_file_its_scripts_reach_for(self) -> None:
        # The manifest existing in the repo is only half the contract: the
        # deploy host runs these scripts out of the unpacked archive, so a
        # sibling the manifest forgets fails there and nowhere else. That is
        # exactly how `scripts/reclaim.sh` -- added to deploy.sh's preflight
        # but never to the manifest -- turned every prod deploy red for nine
        # days while CI stayed green.
        entries = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()
        shipped = set(entries)

        def is_shipped(path: str) -> bool:
            # An entry may be a directory (grafana, postgres-init), which
            # ships everything under it.
            parts = PurePosixPath(path).parts
            return any("/".join(parts[:depth]) in shipped for depth in range(1, len(parts) + 1))

        for entry in entries:
            source = ROOT / entry
            if source.suffix != ".sh":
                continue
            for variable, target in SHELL_PATH_REFERENCE.findall(source.read_text()):
                prefix = SHELL_REFERENCE_ROOTS[variable]
                referenced = f"{prefix}/{target}" if prefix else target
                # Directories the scripts create at runtime (the sandbox Caddy
                # snippet dir) and `..`-style path arithmetic name nothing the
                # release has to carry.
                if not (ROOT / referenced).is_file():
                    continue
                self.assertTrue(
                    is_shipped(referenced),
                    f"{entry} runs {referenced} on the deploy host, "
                    "but deploy/release-manifest.txt does not ship it",
                )

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

    def test_deploy_resolves_every_release_image_before_touching_the_host(self) -> None:
        # A workflow_dispatch deploy bypasses the `workflow_run` conclusion
        # guard, so nothing else establishes that CI has published the tags
        # the host is about to pull. Without this the failure lands on the
        # host as a containerd "not found", after the data image has been
        # pulled and a 30-minute volume guard parked.
        deploy = workflow("deploy.yml")
        job = deploy["jobs"]["deploy"]
        step_names = [step.get("name", "") for step in job["steps"]]
        await_step = step_by_name(job, "Await the release images")

        self.assertEqual("read", deploy["permissions"]["packages"])
        self.assertEqual("./.github/actions/await-images", await_step["uses"])
        self.assertLess(
            step_names.index("Await the release images"),
            step_names.index("Reach the deploy host"),
        )
        images = await_step["with"]["images"]
        for repository, sha in (
            ("backend", "env.DEPLOY_SHA"),
            ("recgov-companion", "env.DEPLOY_COMPANION_SHA"),
            ("data", "env.DEPLOY_DATA_SHA"),
        ):
            self.assertIn(f"/{repository}:${{{{ {sha} }}}}", images)

        # Pinning today's three would let a fourth image be added to the prod
        # stack and pulled on the host without ever being preflighted -- the
        # guard would rot open silently, into exactly the containerd "not
        # found" it exists to prevent. So derive the set deploy.sh actually
        # pulls and require the await list to cover it.
        pulled = set(
            re.findall(
                r'export ROADTRIP_[A-Z]+_IMAGE="ghcr\.io/[^/]+/[^/]+/([^:"]+):',
                (ROOT / "scripts" / "deploy.sh").read_text(),
            )
        )
        self.assertTrue(pulled, "found no prod image exports in deploy.sh")
        for repository in sorted(pulled):
            self.assertIn(
                f"/{repository}:",
                images,
                f"deploy.sh pulls {repository} on the host, but the deploy "
                "workflow does not preflight it",
            )

        # The deploy queue is serialized, so this job must not inherit the
        # sandbox's 20-minute patience.
        budget = int(await_step["with"]["max-attempts"]) * int(
            await_step["with"]["wait-seconds"]
        )
        self.assertLessEqual(budget, 300, "the image wait must stay well inside the job budget")

    def test_the_image_wait_is_shared_rather_than_copied(self) -> None:
        # Two call sites block on GHCR tags; the poll loop lives in one place
        # so a fix to either reaches both.
        sandbox_action = (ROOT / ".github" / "actions" / "sandbox" / "action.yml").read_text()
        deploy = (ROOT / ".github" / "workflows" / "deploy.yml").read_text()

        self.assertIn("docker manifest inspect", AWAIT_IMAGES_ACTION)
        for caller in (sandbox_action, deploy):
            self.assertIn("uses: ./.github/actions/await-images", caller)
            self.assertNotIn("docker manifest inspect", caller)

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
        # The GHCR login moved into the shared await-images action; the
        # sandbox reaches it through that action rather than inlining it.
        self.assertIn("uses: ./.github/actions/await-images", sandbox_action)
        self.assertIn("Log in to GHCR", AWAIT_IMAGES_ACTION)
        self.assertIn("Wait for GHCR images", sandbox_action)
        self.assertLess(
            sandbox_action.index("- name: Wait for GHCR images"),
            sandbox_action.index("- name: Start sandbox"),
        )
        self.assertIn("not creating one", sandbox_action)


if __name__ == "__main__":
    unittest.main()
