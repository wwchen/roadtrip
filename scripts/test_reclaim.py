#!/usr/bin/env python3
"""Behavioural coverage for scripts/reclaim.sh.

reclaim.sh is the single implementation of disk reclaim for prod deploys, the
sandbox sweep, and local development. The risk it carries is blast radius: it
runs on a host shared with unrelated Docker stacks, so every destructive call
has to stay inside the roadtrip label. These tests drive the real script
against a fake `docker` on PATH and assert on the exact argv it produces.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECLAIM = ROOT / "scripts" / "reclaim.sh"

FAKE_DOCKER = """#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_LOG"
case "$1" in
  image)
    case "$2" in
      ls) [ "$3" = "roadtrip/backend" ] && cat "$FAKE_IMAGE_LS" 2>/dev/null; true ;;
      inspect) echo "sha256:deadbeef" ;;
    esac
    ;;
esac
exit 0
"""


class ReclaimTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.log = self.tmp / "docker.log"
        self.image_ls = self.tmp / "images.txt"
        self.image_ls.write_text("")
        fake = self.bin / "docker"
        fake.write_text(FAKE_DOCKER)
        fake.chmod(0o755)
        self.addCleanup(self._tmp.cleanup)

    def run_reclaim(self, *args: str, **env: str) -> subprocess.CompletedProcess:
        environ = dict(os.environ)
        environ["PATH"] = f"{self.bin}{os.pathsep}{environ['PATH']}"
        environ["DOCKER_LOG"] = str(self.log)
        environ["FAKE_IMAGE_LS"] = str(self.image_ls)
        environ.update(env)
        return subprocess.run(
            ["bash", str(RECLAIM), *args],
            capture_output=True, text=True, check=False, env=environ,
        )

    def docker_calls(self) -> list:
        if not self.log.exists():
            return []
        return [line for line in self.log.read_text().splitlines() if line]


class CheckDiskTest(ReclaimTestCase):
    def test_passes_when_free_space_meets_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "0",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("disk check", done.stdout)

    def test_fails_when_free_space_is_under_the_floor(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "999999999",
            "--path", str(self.tmp),
        )
        self.assertEqual(done.returncode, 1)
        self.assertIn("unit test", done.stderr)
        self.assertIn("deadlocks the Docker daemon", done.stderr)

    def test_unknown_command_exits_two(self) -> None:
        done = self.run_reclaim("nonsense")
        self.assertEqual(done.returncode, 2)

    def test_unreadable_path_warns_and_succeeds(self) -> None:
        done = self.run_reclaim(
            "check-disk", "--label", "unit test", "--min-gb", "1",
            "--path", "/nonexistent/path/xyz",
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("could not read free space", done.stderr)

    def test_option_without_a_value_is_a_usage_error(self) -> None:
        done = self.run_reclaim("check-disk", "--min-gb")
        self.assertEqual(done.returncode, 2)
        self.assertIn("requires a value", done.stderr)


if __name__ == "__main__":
    unittest.main()
