#!/usr/bin/env python3
"""Deploy's path gate has to cover every production input, derived not listed.

The gate in .github/workflows/deploy.yml decides whether a push reaches mini-ca.
A production input that isn't in it evaluates to deploy=false, and because that
green run becomes the next baseline, the change is stranded permanently rather
than shipped by a later push. So the list can't be hand-maintained — a
hand-maintained one is what left `companion/`, `watches.html`, and the root
`build.gradle.kts` out of it.

These tests derive the required set from what production actually consumes:
the `make run env=prod` Compose invocation (which files, which profiles), the
bind mounts and build contexts of the services those profiles select, and the
Gradle build files the fat jar is built from. Add a mount or a service to
Compose without teaching the gate about it and this fails.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DEPLOY_WORKFLOW = ROOT / ".github" / "workflows" / "deploy.yml"
MAKEFILE = ROOT / "Makefile"
# The make variable holding the production Compose command. Everything about
# "what prod runs" — which compose files, which profiles — is read out of it.
PROD_COMPOSE_VAR = "PROD_COMPOSE"

_ASSIGNMENT = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(?:\?=|:=|=)\s*(.*?)\s*$", re.MULTILINE)
_REFERENCE = re.compile(r"\$\(([A-Za-z_][A-Za-z0-9_]*)\)")


def make_variables(text: str) -> dict[str, str]:
    return {match[1]: match[2] for match in _ASSIGNMENT.finditer(text)}


def expand(value: str, variables: dict[str, str], depth: int = 10) -> str:
    """Substitute `$(NAME)` references, leaving unknown names and functions be."""
    for _ in range(depth):
        expanded = _REFERENCE.sub(
            lambda m: variables.get(m[1], m[0]),
            value,
        )
        if expanded == value:
            return expanded
        value = expanded
    return value


def prod_compose_command() -> str:
    text = MAKEFILE.read_text()
    variables = make_variables(text)
    if PROD_COMPOSE_VAR not in variables:
        raise AssertionError(f"{PROD_COMPOSE_VAR} is gone from the Makefile")
    return expand(variables[PROD_COMPOSE_VAR], variables)


def prod_compose_files() -> list[str]:
    """The `-f` files production Compose is invoked with."""
    return re.findall(r"-f\s+(\S+\.yml)", prod_compose_command())


def prod_profiles() -> set[str]:
    """The Compose profiles production enables."""
    return set(re.findall(r"--profile\s+(\S+)", prod_compose_command()))


def prod_services() -> dict[str, dict]:
    """Every service production starts, merged across its Compose files.

    A service with no `profiles:` key is always started, so it counts too.
    """
    merged: dict[str, dict] = {}
    for name in prod_compose_files():
        document = yaml.safe_load((ROOT / name).read_text()) or {}
        for service, spec in (document.get("services") or {}).items():
            merged.setdefault(service, {}).update(spec or {})
    profiles = prod_profiles()
    return {
        name: spec
        for name, spec in merged.items()
        if not spec.get("profiles") or profiles.intersection(spec["profiles"])
    }


def repo_inputs_of(service: dict) -> set[str]:
    """Repo paths a service consumes: its build context and its bind mounts.

    Named volumes, absolute host paths (/var/run/docker.sock) and
    `${VAR:-$HOME/...}` state directories aren't in the repo, so they can't be
    push-triggered and are skipped.
    """
    inputs = set()

    build = service.get("build")
    context = build.get("context") if isinstance(build, dict) else build
    if isinstance(context, str) and context.startswith("./"):
        inputs.add(context[2:])

    for volume in service.get("volumes") or []:
        source = volume.split(":", 1)[0] if isinstance(volume, str) else volume.get("source", "")
        if source.startswith("./"):
            inputs.add(source[2:])
    return inputs


def glob_to_regex(pattern: str) -> re.Pattern[str]:
    """The subset of picomatch semantics dorny/paths-filter is given here.

    `*` and `?` stay inside one path segment; `**` spans segments. Matching the
    filter's own patterns rather than re-listing them is the point: the test
    fails when the workflow stops covering a path, not when it's reworded.
    """
    out = []
    index = 0
    while index < len(pattern):
        if pattern.startswith("**", index):
            out.append(".*")
            index += 2
        elif pattern[index] == "*":
            out.append("[^/]*")
            index += 1
        elif pattern[index] == "?":
            out.append("[^/]")
            index += 1
        else:
            out.append(re.escape(pattern[index]))
            index += 1
    return re.compile(f"^{''.join(out)}$")


def deploy_filter_patterns() -> list[str]:
    """The `deploy:` globs from the workflow's dorny/paths-filter step."""
    workflow = yaml.safe_load(DEPLOY_WORKFLOW.read_text())
    for step in workflow["jobs"]["changes"]["steps"]:
        if str(step.get("uses", "")).startswith("dorny/paths-filter"):
            return list(yaml.safe_load(step["with"]["filters"])["deploy"])
    raise AssertionError("deploy.yml has no dorny/paths-filter step to check")


def representative_file(path: Path) -> str:
    """A real repo-relative file at or under `path`.

    Directories are checked through a file they actually contain, so the
    assertion is about a path a push can really touch. Shallow on purpose:
    data/ is gigabytes.
    """
    while path.is_dir():
        entries = sorted(path.iterdir())
        files = [entry for entry in entries if entry.is_file()]
        directories = [entry for entry in entries if entry.is_dir()]
        if files:
            path = files[0]
        elif directories:
            path = directories[0]
        else:
            raise AssertionError(f"{path} is empty; nothing to check the filter with")
    return str(path.relative_to(ROOT))


class DeployPathFilterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.matchers = [glob_to_regex(pattern) for pattern in deploy_filter_patterns()]

    def assert_triggers_deploy(self, path: str, why: str) -> None:
        self.assertTrue(
            any(matcher.match(path) for matcher in self.matchers),
            f"{path} is a production input ({why}) but no deploy.yml filter matches it, "
            "so a push touching only it would never reach mini-ca",
        )

    def test_every_prod_compose_input_triggers_a_deploy(self) -> None:
        services = prod_services()
        self.assertIn("recgov-companion", services, "prod profiles no longer select the companion")

        checked = 0
        for name, service in sorted(services.items()):
            for repo_path in sorted(repo_inputs_of(service)):
                with self.subTest(service=name, path=repo_path):
                    self.assert_triggers_deploy(
                        representative_file(ROOT / repo_path),
                        f"{name} builds or bind-mounts ./{repo_path}",
                    )
                    checked += 1
        # A derivation that silently finds nothing would pass every assertion.
        self.assertGreater(checked, 8, "compose input derivation came up nearly empty")

    def test_the_prod_compose_files_themselves_trigger_a_deploy(self) -> None:
        files = prod_compose_files()
        self.assertIn("docker-compose.yml", files)
        for name in files:
            with self.subTest(file=name):
                self.assert_triggers_deploy(name, "prod Compose is invoked with it")

    def test_the_backend_image_build_inputs_trigger_a_deploy(self) -> None:
        # `make run env=prod`: ./gradlew :backend:buildFatJar, then
        # docker build --target backend . — so the image build context's own
        # files and the whole root Gradle build are deploy inputs.
        for name in ("Dockerfile", ".dockerignore", "Makefile"):
            with self.subTest(file=name):
                self.assertTrue((ROOT / name).is_file(), f"{name} moved")
                self.assert_triggers_deploy(name, "the prod image build reads it")

    def test_every_root_gradle_build_file_triggers_a_deploy(self) -> None:
        # The fat jar is built by the root Gradle build, so settings.gradle.kts
        # *and* build.gradle.kts (Kotlin/plugin versions) shape the deployed
        # artifact even when backend/ is untouched.
        build_files = sorted(path.name for path in ROOT.glob("*.gradle.kts"))
        self.assertIn("build.gradle.kts", build_files)
        self.assertIn("settings.gradle.kts", build_files)

        for name in [*build_files, "gradle.properties", "gradlew"]:
            with self.subTest(file=name):
                self.assert_triggers_deploy(name, "the fat jar is built from it")
        self.assert_triggers_deploy(
            representative_file(ROOT / "gradle"),
            "the Gradle wrapper and version catalog live there",
        )


class GlobSemanticsTest(unittest.TestCase):
    """The matcher above is the test's own dependency; a wrong one hides gaps."""

    def test_a_star_stays_within_one_segment(self) -> None:
        self.assertTrue(glob_to_regex("*.gradle.kts").match("build.gradle.kts"))
        self.assertFalse(glob_to_regex("*.gradle.kts").match("backend/build.gradle.kts"))

    def test_a_globstar_spans_segments(self) -> None:
        self.assertTrue(glob_to_regex("web/**").match("web/account/login.js"))
        self.assertTrue(glob_to_regex("companion/**").match("companion/src/server.js"))

    def test_a_prefix_is_not_a_match(self) -> None:
        self.assertFalse(glob_to_regex("index.html").match("index.html.bak"))
        self.assertFalse(glob_to_regex("data/**").match("database/x"))


if __name__ == "__main__":
    unittest.main()
