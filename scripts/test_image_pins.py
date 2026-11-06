#!/usr/bin/env python3

import json
import re
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]

COMPANION_DOCKERFILE = ROOT / "companion" / "Dockerfile"
COMPANION_PACKAGE_JSON = ROOT / "companion" / "package.json"
BASE_COMPOSE = ROOT / "docker-compose.yml"
SANDBOX_COMPOSE = ROOT / "docker-compose.sandbox.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"

PLAYWRIGHT_IMAGE = re.compile(
    r"^FROM\s+mcr\.microsoft\.com/playwright:v(?P<version>\d+\.\d+\.\d+)-\w+\s*$",
    re.MULTILINE,
)
POSTGIS_IMAGE = re.compile(r"postgis/postgis:(?P<tag>[\w.-]+)")

LOCKSTEP_MARKER = "LOCKSTEP"


class CompanionPlaywrightLockstepTest(unittest.TestCase):
    def test_the_image_ships_the_browsers_its_npm_package_asks_for(self) -> None:
        dockerfile = COMPANION_DOCKERFILE.read_text()
        package = json.loads(COMPANION_PACKAGE_JSON.read_text())

        pinned = PLAYWRIGHT_IMAGE.search(dockerfile)
        self.assertIsNotNone(
            pinned,
            f"{COMPANION_DOCKERFILE.name} must pin an exact "
            "mcr.microsoft.com/playwright:vX.Y.Z-<distro> tag, so the browser "
            "build the image carries is identifiable from the source tree",
        )
        self.assertEqual(
            package["dependencies"]["playwright"],
            pinned.group("version"),
            "the companion's playwright image tag and its exact package.json "
            "version must move together: the image ships the browser binaries "
            "and the npm package decides which build it looks for, so a "
            "mismatch fails at launchPersistentContext with a missing-executable "
            "error that surfaces as recgov_auth_check_exception on every /login "
            "and /verify",
        )

    def test_the_npm_version_is_exact_so_the_pair_can_be_compared(self) -> None:
        package = json.loads(COMPANION_PACKAGE_JSON.read_text())

        self.assertRegex(
            package["dependencies"]["playwright"],
            r"^\d+\.\d+\.\d+$",
            "a range (^ or ~) would let npm resolve a playwright that the "
            "pinned image has no browsers for, and would leave this guard "
            "nothing exact to compare the image tag against",
        )

    def test_the_dockerfile_still_says_why_the_tag_is_load_bearing(self) -> None:
        self.assertIn(
            LOCKSTEP_MARKER,
            COMPANION_DOCKERFILE.read_text(),
            "the tag reads like a routine pin; without the note, the next "
            "reader has no reason not to bump it alone",
        )


class PostgisPinAlignmentTest(unittest.TestCase):
    def test_every_environment_runs_one_postgis_build(self) -> None:
        base = yaml.safe_load(BASE_COMPOSE.read_text())
        sandbox = yaml.safe_load(SANDBOX_COMPOSE.read_text())

        deployed = base["services"]["postgres"]["image"]
        ci_tags = set(POSTGIS_IMAGE.findall(CI_WORKFLOW.read_text()))

        self.assertTrue(ci_tags, "ci.yml must run its tests against postgis")
        self.assertEqual(
            {deployed, sandbox["services"]["postgres"]["image"]},
            {f"postgis/postgis:{tag}" for tag in ci_tags} | {deployed},
            "the postgis tag in docker-compose.yml, docker-compose.sandbox.yml "
            "and .github/workflows/ci.yml must stay identical: dependabot's "
            "docker-compose ecosystem bumps the compose files but cannot see a "
            "workflow service image, so a bump otherwise leaves the test suite "
            "validating a database version production does not run",
        )


if __name__ == "__main__":
    unittest.main()
