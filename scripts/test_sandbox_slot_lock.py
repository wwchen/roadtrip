#!/usr/bin/env python3
"""Behavioural coverage for deploy.sh's sandbox slot lock.

A holder killed mid-flight used to leak the lock permanently: it recorded no
owner and nothing could reclaim it, so every teardown and every sweep run failed
for two days on 2026-08-18. These tests drive the real functions out of
deploy.sh so that regression cannot return unnoticed.
"""

import math
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"

# Kept small so the suite stays quick; the poll interval in deploy.sh is 1s.
GRACE_SECONDS = 1
WAIT_SECONDS = 3
CONCURRENT_ACQUIRERS = 5
FRESH_FRACTION_CEILING = 0.3
FRESH_FRACTION_POLL_SECONDS = 0.01
FRESH_LOCK_OFFSET_SECONDS = 0.05
STALE_DECISION_DELAY_SECONDS = 2
SECOND_ACQUIRER_HEAD_START_SECONDS = 0.3
HOLD_SECONDS = 3
RACE_WAIT_SECONDS = 15
GATE_STALE_SECONDS = 10
RACY_MKDIR_WINDOW_SECONDS = 0.2
RACY_MKDIR = """#!/usr/bin/env bash
for target in "$@"; do :; done
if [[ "$1" == "-p" ]]; then exec "$REAL_MKDIR" "$@"; fi
if [[ -e "$target" ]]; then echo "mkdir: cannot create directory '$target': File exists" >&2; exit 1; fi
sleep "$RACY_MKDIR_WINDOW"
exec "$REAL_MKDIR" -p "$target"
"""


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
                _extract("_sandbox_lock_gate_enter", source),
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

    def test_a_lock_younger_than_the_grace_window_survives_a_second_boundary(self) -> None:
        """Whole-second mtimes must not make a fresh lock look a second older."""
        self.lock.mkdir(parents=True)
        while time.time() % 1 > FRESH_FRACTION_CEILING:
            time.sleep(FRESH_FRACTION_POLL_SECONDS)
        just_before_this_second = math.floor(time.time()) - FRESH_LOCK_OFFSET_SECONDS
        os.utime(self.lock, (just_before_this_second, just_before_this_second))

        result = self.acquire(ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS="0")

        self.assertEqual(1, result.returncode)
        self.assertNotIn("reclaiming", result.stdout)
        self.assertTrue(self.lock.exists())

    def test_a_live_gate_is_waited_on_not_cleared(self) -> None:
        gate = self.state / ".slot-lock.gate"
        gate.write_text("1\n")

        result = self.acquire(ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS="1")

        self.assertEqual(1, result.returncode)
        self.assertIn("timed out waiting for sandbox slot lock", result.stderr)
        self.assertTrue(gate.exists())
        self.assertFalse(self.lock.exists())

    def test_a_gate_left_by_a_killed_acquirer_is_cleared(self) -> None:
        gate = self.state / ".slot-lock.gate"
        gate.write_text("1\n")
        old = int(gate.stat().st_mtime) - (GATE_STALE_SECONDS + 60)
        os.utime(gate, (old, old))

        result = self.acquire(ROADTRIP_SANDBOX_LOCK_GATE_STALE_SECONDS=str(GATE_STALE_SECONDS))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("clearing a sandbox slot lock gate abandoned", result.stdout)
        self.assertFalse(gate.exists())

    def test_a_stale_reclaim_decision_never_steals_a_lock_taken_since(self) -> None:
        """B judges the stale lock reclaimable, then stalls; C reclaims it and takes a fresh one."""
        self.lock.mkdir(parents=True)
        (self.lock / "pid").write_text(f"{self.dead_pid()}\n")
        self.age_lock()
        trace = self.state / "trace"
        trace.write_text("")
        holder = textwrap.dedent(
            """\
            set -euo pipefail
            source "$LIB"
            {prelude}
            _sandbox_lock_acquire >/dev/null
            echo "ENTER $$" >> "$TRACE"
            sleep "$HOLD"
            echo "EXIT $$" >> "$TRACE"
            _sandbox_lock_release
            """
        )
        stalled = self.state / "stalled.sh"
        stalled.write_text(
            holder.format(
                prelude='kill() { if [[ "$1" == "-0" ]]; then sleep "$STALL"; fi; builtin kill "$@"; }'
            )
        )
        prompt = self.state / "prompt.sh"
        prompt.write_text(holder.format(prelude=""))
        env = self.env(
            TRACE=str(trace),
            STALL=str(STALE_DECISION_DELAY_SECONDS),
            HOLD=str(HOLD_SECONDS),
            ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS=str(RACE_WAIT_SECONDS),
        )

        stalled_run = subprocess.Popen(
            ["/bin/bash", str(stalled)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        time.sleep(SECOND_ACQUIRER_HEAD_START_SECONDS)
        prompt_run = subprocess.Popen(
            ["/bin/bash", str(prompt)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        for process in (stalled_run, prompt_run):
            self.assertEqual(0, process.wait(), process.stderr.read().decode())

        depth = 0
        for token in trace.read_text().split():
            if token == "ENTER":
                depth += 1
                self.assertLessEqual(depth, 1, "two acquirers held the slot lock at once")
            elif token == "EXIT":
                depth -= 1

    def test_acquirers_never_overlap_when_mkdir_is_not_atomic(self) -> None:
        """uutils mkdir (Ubuntu 26.04) can report success to two racers for one path."""
        bin_dir = self.state / "bin"
        bin_dir.mkdir()
        racy = bin_dir / "mkdir"
        racy.write_text(RACY_MKDIR)
        racy.chmod(0o755)
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
        env = self.env(
            TRACE=str(trace),
            PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
            REAL_MKDIR=shutil.which("mkdir") or "/bin/mkdir",
            RACY_MKDIR_WINDOW=str(RACY_MKDIR_WINDOW_SECONDS),
            ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS=str(CONCURRENT_ACQUIRERS * 5),
        )

        running = [
            subprocess.Popen(["/bin/bash", str(worker)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            for _ in range(CONCURRENT_ACQUIRERS)
        ]
        failures = [process.stderr.read().decode() for process in running if process.wait() != 0]
        self.assertEqual([], failures)

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
