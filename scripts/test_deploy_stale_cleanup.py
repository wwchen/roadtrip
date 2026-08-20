#!/usr/bin/env python3
"""Behavioural coverage for deploy.sh's stale-deploy cleanup.

Deploys stranded by a wedged daemon hold an SSH session and a half-applied
Compose state, so they are cleared on the way in. `pkill -P` reached only direct
children, which left the docker CLI and its plugins alive and still holding the
daemon; these tests pin the whole-tree behaviour and, just as importantly, that
the kill stays inside the pattern it was given.
"""

import os
import re
import signal
import subprocess
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"

PROCESS_TIMEOUT_SECONDS = 10


def _extract(name: str, source: str) -> str:
    match = re.search(rf"^{re.escape(name)}\(\) \{{$", source, re.MULTILINE)
    if match is None:
        raise AssertionError(f"{name} not found in deploy.sh")
    end = source.index("\n}\n", match.start())
    return source[match.start(): end + 3]


def _running(pid: int) -> bool:
    done = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)],
                          capture_output=True, text=True, check=False)
    return done.returncode == 0 and not done.stdout.strip().startswith("Z")


class StaleDeployCleanupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        source = DEPLOY.read_text()
        cls.lib = "\n".join(
            [
                "STALE_DEPLOY_SECONDS=2400",
                'STALE_DEPLOY_PATTERN="deploy\\.sh (prod|sandbox-up|sandbox-down)"',
                _extract("_clear_stale_deploys", source),
                _extract("_kill_process_tree", source),
            ]
        )

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.lib_path = self.tmp / "lib.sh"
        self.lib_path.write_text(self.lib)
        self.spawned = []
        self.popens = []
        self.addCleanup(self._reap)
        self.addCleanup(self._tmp.cleanup)

    def _reap(self) -> None:
        for pid in self.spawned:
            try:
                os.kill(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
        for process in self.popens:
            try:
                process.wait(timeout=PROCESS_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=PROCESS_TIMEOUT_SECONDS)

    def _spawn_tree(self, marker: str) -> list:
        """Start root -> child -> grandchild, all tagged with `marker`."""
        script = textwrap.dedent(
            f"""\
            printf '%s\\n' "$$" > "{self.tmp}/{marker}.root"
            bash -c 'printf "%s\\n" "$$" > "{self.tmp}/{marker}.child"
                     sleep 300 &
                     printf "%s\\n" "$!" > "{self.tmp}/{marker}.grandchild"
                     wait' &
            wait
            """
        )
        process = subprocess.Popen(["/bin/bash", "-c", script, marker], start_new_session=True)
        self.spawned.append(process.pid)
        self.popens.append(process)
        pids = []
        for suffix in ("root", "child", "grandchild"):
            path = self.tmp / f"{marker}.{suffix}"
            deadline = time.monotonic() + PROCESS_TIMEOUT_SECONDS
            while time.monotonic() < deadline and not path.exists():
                time.sleep(0.02)
            self.assertTrue(path.exists(), f"{marker}.{suffix} never started")
            pids.append(int(path.read_text().strip()))
        self.spawned.extend(pids)
        return pids

    def _clear(self, pattern: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["/bin/bash", "-c", 'set -euo pipefail; source "$LIB"; _clear_stale_deploys'],
            env={
                **os.environ,
                "LIB": str(self.lib_path),
                "ROADTRIP_STALE_DEPLOY_PATTERN": pattern,
                # Everything matching the pattern is treated as abandoned.
                "ROADTRIP_STALE_DEPLOY_SECONDS": "-1",
            },
            capture_output=True,
            text=True,
            check=False,
        )

    def _assert_gone(self, pids: list) -> None:
        deadline = time.monotonic() + PROCESS_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if not any(_running(pid) for pid in pids):
                return
            time.sleep(0.05)
        still = [pid for pid in pids if _running(pid)]
        self.fail(f"still running: {still}")

    def test_clears_the_whole_process_tree_not_just_direct_children(self) -> None:
        marker = "roadtrip-stale-fixture-tree"
        pids = self._spawn_tree(marker)

        result = self._clear(marker)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("stale deploy process tree", result.stdout)
        # The grandchild is the case pkill -P missed: in production it is the
        # docker CLI's own helper, which kept the daemon busy after the kill.
        self._assert_gone(pids)

    def test_leaves_processes_outside_the_pattern_alone(self) -> None:
        target = self._spawn_tree("roadtrip-stale-fixture-target")
        bystander = self._spawn_tree("roadtrip-stale-fixture-bystander")

        self._clear("roadtrip-stale-fixture-target")

        self._assert_gone(target)
        for pid in bystander:
            self.assertTrue(_running(pid), f"killed a process outside the pattern: {pid}")

    def test_no_matches_is_a_silent_no_op(self) -> None:
        result = self._clear("roadtrip-stale-fixture-nothing-matches-this")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout.strip())

    def test_never_kills_itself(self) -> None:
        """A pattern matching the clearing shell must not take it down."""
        result = subprocess.run(
            ["/bin/bash", "-c",
             'set -euo pipefail; source "$LIB"; _clear_stale_deploys; echo SURVIVED'],
            env={
                **os.environ,
                "LIB": str(self.lib_path),
                "ROADTRIP_STALE_DEPLOY_PATTERN": "bash",
                "ROADTRIP_STALE_DEPLOY_SECONDS": "999999999",
            },
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertIn("SURVIVED", result.stdout)


if __name__ == "__main__":
    unittest.main()
