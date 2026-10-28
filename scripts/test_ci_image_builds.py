#!/usr/bin/env python3

import re
import subprocess
import tempfile
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


COMPANION_KEY_SCRIPT = "scripts/companion-image-key.sh"

SCHEMA_EQUIVALENCE_TEST = "scripts/schema-equivalence.test.mjs"
SCHEMA_REFERENCE_TEST = "scripts/schema-reference.test.mjs"
BUILD_WORKSPACES = "scripts/build-workspaces.mjs"
PACKAGE_TYPECHECKS = (
    "npm run typecheck --workspace @roadtrip/domain",
    "npm run typecheck --workspace @roadtrip/db",
)
FLYWAY_MIGRATIONS = "backend/src/main/resources/db/migration"


def companion_key_inputs() -> list[str]:
    declared = re.search(
        r"COMPANION_IMAGE_INPUTS=\(([^)]*)\)", (ROOT / COMPANION_KEY_SCRIPT).read_text()
    )
    assert declared, "COMPANION_IMAGE_INPUTS not found"
    return declared.group(1).split()


def make_recipe(target: str) -> list[str]:
    lines = (ROOT / "Makefile").read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith(f"{target}:"))
    recipe = []
    for line in lines[start + 1 :]:
        if line and not line.startswith("\t"):
            break
        command = line.strip()
        if command and not command.startswith("#"):
            recipe.append(command)
    return recipe


def path_filters() -> dict:
    jobs = workflow("ci.yml")["jobs"]
    return yaml.safe_load(
        next(step for step in jobs["changes"]["steps"] if step.get("id") == "filter")[
            "with"
        ]["filters"]
    )


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

        data_commands = job_commands(jobs["data-image"])
        self.assertIn("git rev-parse HEAD:data", data_commands)
        self.assertIn("docker push", data_commands)

        companion_commands = job_commands(jobs["companion-image"])
        self.assertIn(COMPANION_KEY_SCRIPT, companion_commands)
        self.assertNotIn("git rev-parse HEAD:companion", companion_commands)
        self.assertIn("docker push", companion_commands)

    def test_companion_image_is_keyed_on_everything_its_build_reads(self) -> None:
        deploy_commands = job_commands(workflow("deploy.yml")["jobs"]["deploy"])
        self.assertIn(COMPANION_KEY_SCRIPT, deploy_commands)
        self.assertNotIn("git rev-parse HEAD:companion", deploy_commands)

        inputs = companion_key_inputs()
        copied = {
            PurePosixPath(source).parts[0]
            for line in (ROOT / "companion" / "Dockerfile").read_text().splitlines()
            if line.startswith("COPY ") and "--from" not in line
            for source in line.split()[1:-1]
        }
        self.assertTrue(copied)
        self.assertTrue(copied <= set(inputs), f"{copied - set(inputs)} copied but not in the key")

        steps = workflow("ci.yml")["jobs"]["changes"]["steps"]
        filters = yaml.safe_load(
            next(step for step in steps if step.get("id") == "filter")["with"]["filters"]
        )
        for path in inputs:
            watched = f"{path}/**" if (ROOT / path).is_dir() else path
            self.assertIn(watched, filters["companion_image"], f"{path} does not rebuild the image")
        self.assertIn(
            COMPANION_KEY_SCRIPT,
            filters["companion_image"],
            "editing the key script moves the tag but would build nothing",
        )

    def test_the_guard_on_the_companion_key_runs_on_every_change(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        guard = jobs["image-key-guard"]

        self.assertNotIn("if", guard)
        self.assertNotIn("needs", guard)
        self.assertIn("scripts.test_ci_image_builds", job_commands(guard))
        self.assertIn("image-key-guard", jobs["ci-passed"]["needs"])

    def test_the_schema_equivalence_gate_watches_both_schema_sources(self) -> None:
        jobs = workflow("ci.yml")["jobs"]
        gate = jobs["schema-equivalence"]

        filters = yaml.safe_load(
            next(
                step for step in jobs["changes"]["steps"] if step.get("id") == "filter"
            )["with"]["filters"]
        )
        watched = filters["schema_equivalence"]
        for path in (
            FLYWAY_MIGRATIONS,
            "packages",
            "scripts/schema-equivalence.mjs",
            SCHEMA_EQUIVALENCE_TEST,
        ):
            self.assertTrue(
                any(pattern.startswith(path) for pattern in watched),
                f"{path} does not run the schema equivalence gate",
            )

        self.assertIn("schema_equivalence", jobs["changes"]["outputs"])
        self.assertIn("schema_equivalence", gate["if"])
        self.assertIn("npm run build", job_commands(gate))
        self.assertIn(SCHEMA_EQUIVALENCE_TEST, job_commands(gate))
        self.assertIn(SCHEMA_REFERENCE_TEST, job_commands(gate))
        self.assertIn("schema-equivalence", jobs["ci-passed"]["needs"])
        self.assertTrue(
            any(pattern.startswith(SCHEMA_REFERENCE_TEST) for pattern in watched),
            f"{SCHEMA_REFERENCE_TEST} does not run the schema equivalence gate",
        )

    def test_make_test_runs_the_gate_after_the_build_it_consumes(self) -> None:
        recipe = make_recipe("test")

        def position(command: str) -> int:
            self.assertIn(command, recipe)
            return recipe.index(command)

        build = position("npm run build")
        gate = position(f"node --test {SCHEMA_EQUIVALENCE_TEST}")
        self.assertLess(build, gate, "the gate imports packages/db/dist, so the build comes first")
        self.assertLess(build, position(f"node --test {SCHEMA_REFERENCE_TEST}"))
        for command in PACKAGE_TYPECHECKS:
            self.assertLess(build, position(command))

    def test_package_typechecks_run_in_ci(self) -> None:
        commands = job_commands(workflow("ci.yml")["jobs"]["package-tests"])
        for command in PACKAGE_TYPECHECKS:
            self.assertIn(command, commands)
        self.assertLess(commands.index("npm run build"), commands.index(PACKAGE_TYPECHECKS[0]))

    def test_every_job_that_builds_watches_the_build_driver(self) -> None:
        filters = path_filters()
        for name in ("smoke", "frontend", "schema_equivalence", "package_tests"):
            self.assertIn(BUILD_WORKSPACES, filters[name], f"{name} does not rerun on {BUILD_WORKSPACES}")

    def test_companion_key_moves_with_each_input_and_only_those(self) -> None:
        inputs = companion_key_inputs()

        def git(repo: Path, *args: str) -> str:
            return subprocess.run(
                ["git", *args], cwd=repo, check=True, capture_output=True, text=True
            ).stdout.strip()

        def run_key(cwd: Path) -> subprocess.CompletedProcess:
            return subprocess.run(
                ["bash", str(ROOT / COMPANION_KEY_SCRIPT)],
                cwd=cwd, capture_output=True, text=True,
            )

        def key(cwd: Path) -> str:
            result = run_key(cwd)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout.strip()

        def edit(repo: Path, path: str) -> None:
            target = repo / (path if "." in path else f"{path}/file.txt")
            target.parent.mkdir(exist_ok=True)
            target.write_text(target.read_text() + "x" if target.exists() else "x")
            git(repo, "add", "-A")
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", path)

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git(repo, "init", "-q")
            for path in (*inputs, "frontend"):
                edit(repo, path)
            baseline = key(repo)
            self.assertRegex(baseline, r"^[0-9a-f]{40}$")

            edit(repo, "frontend")
            self.assertEqual(baseline, key(repo))
            self.assertEqual(baseline, key(repo / "frontend"))

            seen = {baseline}
            for path in inputs:
                edit(repo, path)
                moved = key(repo)
                self.assertNotIn(moved, seen, f"editing {path} did not move the key")
                seen.add(moved)

            git(repo, "rm", "-q", "package-lock.json")
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "rename")
            missing = run_key(repo)
            self.assertNotEqual(0, missing.returncode)
            self.assertEqual("", missing.stdout.strip())
            self.assertIn("expected 3 tree entries", missing.stderr)

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

    def test_a_manual_deploy_must_still_establish_that_ci_passed(self) -> None:
        # The `workflow_run` trigger carries the CI-succeeded precondition;
        # workflow_dispatch bypasses it. Awaiting the images does not stand in
        # for it, because the image jobs do not depend on the tests -- so a
        # dispatch of a red commit finds every tag present.
        deploy = workflow("deploy.yml")
        job = deploy["jobs"]["deploy"]
        triggers = workflow_triggers(deploy)
        gate = step_by_name(job, "Require a green CI run for this commit")
        step_names = [step.get("name", "") for step in job["steps"]]

        self.assertIn("force", triggers["workflow_dispatch"]["inputs"])
        self.assertIn("workflow_dispatch", gate["if"])
        self.assertIn("!inputs.force", gate["if"])
        # Both halves: the run concluded success, and the ref is production's.
        self.assertIn("conclusion", gate["run"])
        self.assertIn("refs/heads/$DEPLOY_BRANCH", gate["run"])
        # Before anything reaches the host or the registry.
        for later in ("Await the release images", "Reach the deploy host"):
            self.assertLess(step_names.index(gate["name"]), step_names.index(later))

    def test_the_production_branch_is_named_once(self) -> None:
        # deploy.sh stamped a `master` literal into build-info and the health
        # gate asserted another `master` literal against it, so the check
        # compared a constant with itself and could not fail.
        deploy = workflow("deploy.yml")
        body = (ROOT / ".github" / "workflows" / "deploy.yml").read_text()

        self.assertEqual("master", deploy["env"]["DEPLOY_BRANCH"])
        # The `on:` block cannot read env, so that one literal stays.
        self.assertEqual(
            1,
            len([line for line in body.splitlines()
                 if "master" in line and "#" not in line and "branches:" not in line]),
            "the production branch should be defined once, as DEPLOY_BRANCH",
        )

    def test_smoke_does_not_serialize_behind_the_unit_tests(self) -> None:
        # Smoke rebuilds the fat jar and frontend itself, so the edge bought
        # nothing but a spared runner -- at the cost of the two jobs' durations
        # summing on every push before deploy could start.
        ci = workflow("ci.yml")
        smoke = ci["jobs"]["smoke"]
        aggregate = ci["jobs"]["ci-passed"]

        self.assertNotIn("backend-tests", smoke["needs"])
        self.assertNotIn("backend-tests", smoke["if"])
        # The safety property lives here, and still covers both.
        for job in ("smoke", "backend-tests"):
            self.assertIn(job, aggregate["needs"])

    def test_the_deploy_does_not_restart_what_it_just_recreated(self) -> None:
        # Compose resolves the observability bind mounts against the release
        # directory, which is keyed by SHA, so `up -d` has already recreated
        # them; restarting again only resets the health start periods that
        # `--wait` and the telemetry poll then wait through.
        deploy_script = (ROOT / "scripts" / "deploy.sh").read_text()

        self.assertNotIn("restart grafana", deploy_script)

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
