#!/usr/bin/env python3
"""CI has to build every image `make run env=prod` builds.

The `docker-build` job exists so a broken image fails in CI instead of on
mini-ca, mid-deploy. It only half did: it built the backend target, while
production also builds the `recgov-companion` Compose service — so a broken
companion Dockerfile, a failing install layer, or a build-context change sailed
through CI and failed for the first time on the host.

These tests derive the image builds from the Makefile's prod recipe and assert
CI performs each of them, triggered by a filter that actually covers the build's
inputs. Add an image to the prod recipe and CI has to grow with it.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

from test_deploy_paths import (
    ROOT,
    expand,
    glob_to_regex,
    make_variables,
    prod_compose_files,
    representative_file,
)

CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"


def prod_run_recipe() -> str:
    """The `make run env=prod` recipe, with make variables expanded.

    This is the definition of "what production builds", so both assertions below
    read it rather than restating its commands.
    """
    text = Path(ROOT / "Makefile").read_text()
    body = re.search(r"^run:.*?^ifeq \(\$\(RUN_ENV\),prod\)\n(.*?)^else", text, re.M | re.S)
    if not body:
        raise AssertionError("the Makefile's `run:` prod branch has moved")
    return expand(body[1], make_variables(text))


def docker_build_targets() -> set[str]:
    """Dockerfile targets production builds with plain `docker build`."""
    return set(re.findall(r"docker build\b[^\n]*--target\s+(\S+)", prod_run_recipe()))


def compose_build_services() -> set[str]:
    """Compose services production builds with `docker compose build`."""
    return set(re.findall(r"docker compose\b[^\n]*\bbuild\s+([\w.-]+)", prod_run_recipe()))


def compose_service(name: str) -> dict:
    for compose_file in prod_compose_files():
        document = yaml.safe_load((ROOT / compose_file).read_text()) or {}
        service = (document.get("services") or {}).get(name)
        if service:
            return service
    raise AssertionError(f"{name} is built by the prod recipe but isn't a prod Compose service")


def ci_jobs() -> dict[str, dict]:
    return yaml.safe_load(CI_WORKFLOW.read_text())["jobs"]


def ci_filters() -> dict[str, list[str]]:
    for step in ci_jobs()["changes"]["steps"]:
        if str(step.get("uses", "")).startswith("dorny/paths-filter"):
            return yaml.safe_load(step["with"]["filters"])
    raise AssertionError("ci.yml has no dorny/paths-filter step")


def jobs_running(pattern: str) -> dict[str, dict]:
    """CI jobs with a step whose shell body matches `pattern`."""
    matcher = re.compile(pattern)
    return {
        name: job
        for name, job in ci_jobs().items()
        for step in job.get("steps") or []
        if matcher.search(str(step.get("run", "")))
    }


class ProdImagesAreBuiltInCiTest(unittest.TestCase):
    def assert_filter_covers(self, job_name: str, job: dict, path: str) -> None:
        """The job's path gate has to fire for a change to `path`.

        A build job that exists but never runs on the changes it validates is the
        same bug wearing a green check.
        """
        gate = str(job.get("if", ""))
        names = re.findall(r"needs\.changes\.outputs\.(\w+)", gate)
        self.assertTrue(names, f"{job_name} has no changes-filter gate: {gate!r}")

        filters = ci_filters()
        patterns = [pattern for name in names for pattern in filters.get(name, [])]
        self.assertTrue(
            any(glob_to_regex(pattern).match(path) for pattern in patterns),
            f"{job_name} builds an image from {path} but its filter "
            f"({', '.join(names)}) doesn't match it, so it won't run when that changes",
        )

    def test_ci_builds_every_docker_target_prod_builds(self) -> None:
        targets = docker_build_targets()
        self.assertEqual({"backend"}, targets, "prod's `docker build --target`s changed")

        for target in sorted(targets):
            jobs = jobs_running(rf"docker build\b.*--target {re.escape(target)}\b")
            with self.subTest(target=target):
                self.assertTrue(jobs, f"no CI job builds the {target} image target")
                for name, job in jobs.items():
                    self.assert_filter_covers(name, job, "Dockerfile")

    def test_ci_builds_every_compose_service_prod_builds(self) -> None:
        services = compose_build_services()
        self.assertEqual(
            {"recgov-companion"},
            services,
            "prod's `docker compose build` services changed",
        )

        for service in sorted(services):
            # Through Compose, like production: the service's build config
            # (context, image name) is part of what's being validated.
            jobs = jobs_running(rf"docker compose\b.*\bbuild\b.*\b{re.escape(service)}\b")
            with self.subTest(service=service):
                self.assertTrue(jobs, f"no CI job builds the {service} Compose service")

                build = compose_service(service).get("build")
                context = build.get("context") if isinstance(build, dict) else build
                self.assertTrue(
                    str(context).startswith("./"),
                    f"{service} has no repo build context to gate on",
                )
                inside_context = representative_file(ROOT / str(context)[2:])
                for name, job in jobs.items():
                    self.assert_filter_covers(name, job, inside_context)
                    # Compose owns the build, so a compose-only edit has to
                    # rebuild it too.
                    self.assert_filter_covers(name, job, "docker-compose.yml")


if __name__ == "__main__":
    unittest.main()
