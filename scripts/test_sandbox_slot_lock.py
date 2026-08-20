#!/usr/bin/env python3
"""Behavioural coverage for deploy.sh's sandbox slot lock.

A holder killed mid-flight used to leak the lock permanently: it recorded no
owner and nothing could reclaim it, so every teardown and every sweep run failed
for two days on 2026-08-18. These tests drive the real functions out of
deploy.sh so that regression cannot return unnoticed.
"""

import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"

# Kept small so the suite stays quick; the poll interval in deploy.sh is 1s.
GRACE_SECONDS = 1
WAIT_SECONDS = 3
CONCURRENT_ACQUIRERS = 5


def _extract(name: str, source: str) -> str:
    """Return one shell function definition, brace-to-brace at column zero."""
    match = re.search(rf"^{re.escape(name)}\(\) \{{$", source, re.MULTILINE)
    if match is None:
        raise AssertionError(f"{name} not found in deploy.sh")
    end = source.index("\n}\n", match.start())
    return source[match.start(): end + 3]


class SandboxSlotLockTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        source = DEPLOY.read_text()
        cls.lib = "\n".join(
            [
                '_sandbox_lock_dir=""',
                _extract("_sandbox_lock_release", source),
                _extract("_lock_mtime_epoch", source),
                _extract("_sandbox_lock_steal", source),
                _extract("_sandbox_lock_acquire", source),
            ]
        )

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.state = Path(self._tmp.name)
        self.lock = self.state / ".slot-lock"
        self.lib_path = self.state / "lib.sh"
        self.lib_path.write_text(self.lib)
        self.addCleanup(self._tmp.cleanup)

    def env(self, **extra: str) -> dict:
        return {
            **os.environ,
            "SANDBOX_STATE_DIR": str(self.state),
            "ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS": str(WAIT_SECONDS),
            "ROADTRIP_SANDBOX_LOCK_OWNER_GRACE_SECONDS": str(GRACE_SECONDS),
            "LIB": str(self.lib_path),
            **extra,
        }

    def acquire(self, **extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["/bin/bash", "-c", 'set -euo pipefail; source "$LIB"; _sandbox_lock_acquire'],
            env=self.env(**extra),
            capture_output=True,
            text=True,
            check=False,
        )

    def age_lock(self) -> None:
        """Backdate the lock past the grace window without sleeping."""
        old = int(self.lock.stat().st_mtime) - (GRACE_SECONDS + 60)
        os.utime(self.lock, (old, old))

    def dead_pid(self) -> int:
        done = subprocess.run(["/bin/bash", "-c", "echo $$"], capture_output=True, text=True)
        return int(done.stdout.strip())

    def test_mtime_helper_returns_an_epoch_on_this_platform(self) -> None:
        """Guards the reclaim gate against a stat that is not portable.

        GNU stat accepts `-f` and prints filesystem junk with a zero exit, so an
        implementation that trusts the exit status silently reports no mtime —
        which disables recovery entirely and lets the leak back in.
        """
        self.lock.mkdir(parents=True)

        result = subprocess.run(
            ["/bin/bash", "-c",
             'set -euo pipefail; source "$LIB"; _lock_mtime_epoch "$SANDBOX_STATE_DIR/.slot-lock"'],
            env=self.env(), capture_output=True, text=True, check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertRegex(result.stdout.strip(), r"^\d+$")
        self.assertAlmostEqual(
            int(self.lock.stat().st_mtime), int(result.stdout.strip()), delta=2
        )

    def test_reclaims_a_lock_left_behind_with_no_owner(self) -> None:
        """The 2026-08-18 case: killed before it could record an owner."""
        self.lock.mkdir(parents=True)
        self.age_lock()

        result = self.acquire()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("reclaiming ownerless sandbox slot lock", result.stdout)

    def test_reclaims_a_lock_whose_owner_is_gone(self) -> None:
        self.lock.mkdir(parents=True)
        (self.lock / "pid").write_text(f"{self.dead_pid()}\n")
        self.age_lock()

        result = self.acquire()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("abandoned by PID", result.stdout)

    def test_never_steals_a_lock_whose_owner_is_alive(self) -> None:
        self.lock.mkdir(parents=True)
        (self.lock / "pid").write_text(f"{os.getpid()}\n")
        self.age_lock()

        result = self.acquire()

        self.assertEqual(1, result.returncode)
        self.assertIn("timed out waiting for sandbox slot lock", result.stderr)
        self.assertEqual(f"{os.getpid()}\n", (self.lock / "pid").read_text())

    def test_a_lock_younger_than_the_grace_window_is_left_alone(self) -> None:
        """A holder between its mkdir and its write must not be reclaimed.

        Waits less than the grace window, so the lock cannot age into being
        reclaimable while this caller is still polling.
        """
        self.lock.mkdir(parents=True)

        result = self.acquire(
            ROADTRIP_SANDBOX_LOCK_OWNER_GRACE_SECONDS="30",
            ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS="1",
        )

        self.assertEqual(1, result.returncode)
        self.assertNotIn("reclaiming", result.stdout)
        self.assertTrue(self.lock.exists())

    def test_concurrent_acquirers_never_overlap(self) -> None:
        """Reclaim must not hand the same lock to two callers at once."""
        self.lock.mkdir(parents=True)
        (self.lock / "pid").write_text(f"{self.dead_pid()}\n")
        self.age_lock()
        trace = self.state / "trace"
        trace.write_text("")
        worker = self.state / "worker.sh"
        worker.write_text(
            textwrap.dedent(
                """\
                set -euo pipefail
                source "$LIB"
                _sandbox_lock_acquire >/dev/null
                echo "ENTER $$" >> "$TRACE"
                sleep 0.05
                echo "EXIT $$" >> "$TRACE"
                _sandbox_lock_release
                """
            )
        )
        env = self.env(TRACE=str(trace))
        # A generous wait: acquirers queue behind each other at a 1s poll.
        env["ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS"] = str(CONCURRENT_ACQUIRERS * 5)

        running = [
            subprocess.Popen(
                ["/bin/bash", str(worker)],
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            for _ in range(CONCURRENT_ACQUIRERS)
        ]
        for process in running:
            self.assertEqual(0, process.wait(), process.stderr.read().decode())

        depth = 0
        entries = 0
        for token in trace.read_text().split():
            if token == "ENTER":
                depth += 1
                entries += 1
                self.assertLessEqual(depth, 1, "two acquirers held the slot lock at once")
            elif token == "EXIT":
                depth -= 1
        self.assertEqual(CONCURRENT_ACQUIRERS, entries)

    def test_release_leaves_a_lock_it_no_longer_owns(self) -> None:
        """After a reclaim the original holder must not delete the new lock."""
        self.lock.mkdir(parents=True)
        (self.lock / "pid").write_text("999999999\n")

        result = subprocess.run(
            [
                "/bin/bash",
                "-c",
                'set -euo pipefail; source "$LIB"; '
                '_sandbox_lock_dir="$SANDBOX_STATE_DIR/.slot-lock"; _sandbox_lock_release',
            ],
            env=self.env(),
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.lock.exists(), "released a lock owned by another process")


if __name__ == "__main__":
    unittest.main()
