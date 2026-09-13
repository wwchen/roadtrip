#!/usr/bin/env python3
"""Behavioural coverage for deploy.sh's sandbox-up EXIT handler.

A failed bring-up used to leave its slot, database volume and DNS record behind,
so the retry then failed the host disk check.
"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy.sh"

OWNER = "pr762"
FAILURE_RC = 7


def _extract(name: str, source: str) -> str:
    match = re.search(rf"^{re.escape(name)}\(\) \{{$", source, re.MULTILINE)
    if match is None:
        raise AssertionError(f"{name} not found in deploy.sh")
    end = source.index("\n}\n", match.start())
    return source[match.start(): end + 3]


class SandboxUpFailureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.handler = _extract("_sandbox_up_exit", DEPLOY.read_text())

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        self.calls = self.dir / "calls"
        fake_deploy = self.dir / "deploy.sh"
        fake_deploy.write_text(
            '#!/usr/bin/env bash\n'
            'echo "deploy.sh $*" >> "$CALLS"\n'
            'exit "${FAKE_TEARDOWN_RC:-0}"\n'
        )
        fake_deploy.chmod(0o755)
        lib = self.dir / "lib.sh"
        lib.write_text(
            '_sandbox_compose() { echo "compose $*" >> "$CALLS"; echo "backend log line"; }\n'
            + self.handler
        )
        self.lib = lib

    def run_up(self, rc: int, **extra: str) -> subprocess.CompletedProcess:
        script = (
            'set -euo pipefail; source "$LIB"; '
            'trap _sandbox_up_exit EXIT; '
            f'exit {rc}'
        )
        env = {
            **os.environ,
            "LIB": str(self.lib),
            "CALLS": str(self.calls),
            "SCRIPT_DIR": str(self.dir),
            "COMPOSE_PROJECT": "roadtrip-sb-1",
            "COMPOSE_FILE": "docker-compose.sandbox.yml",
            "SANDBOX_OWNER": OWNER,
            "SANDBOX_KEEP_ON_FAILURE": "false",
            "SANDBOX_FAILURE_LOG_LINES": "50",
            **extra,
        }
        return subprocess.run(
            ["/bin/bash", "-c", script], env=env, capture_output=True, text=True, check=False
        )

    def recorded(self) -> list[str]:
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def test_success_leaves_the_sandbox_alone(self) -> None:
        result = self.run_up(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.recorded(), [])

    def test_failure_prints_backend_logs_and_tears_down(self) -> None:
        result = self.run_up(FAILURE_RC)
        self.assertEqual(result.returncode, FAILURE_RC, result.stderr)
        calls = self.recorded()
        self.assertIn("backend log line", result.stderr)
        self.assertTrue(any(c.startswith("compose ") and "logs" in c and "backend" in c for c in calls), calls)
        self.assertIn(f"deploy.sh sandbox-down {OWNER}", calls)

    def test_keep_on_failure_skips_teardown(self) -> None:
        result = self.run_up(FAILURE_RC, SANDBOX_KEEP_ON_FAILURE="true")
        self.assertEqual(result.returncode, FAILURE_RC, result.stderr)
        self.assertNotIn(f"deploy.sh sandbox-down {OWNER}", self.recorded())

    def test_failed_teardown_keeps_the_original_exit_code(self) -> None:
        result = self.run_up(FAILURE_RC, FAKE_TEARDOWN_RC="1")
        self.assertEqual(result.returncode, FAILURE_RC, result.stderr)
        self.assertIn(f"teardown of {OWNER} failed", result.stderr)


if __name__ == "__main__":
    unittest.main()
