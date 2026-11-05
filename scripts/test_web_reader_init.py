import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "postgres-init" / "20-web-reader.sh"
SKIP_MESSAGE = "web-reader: no password mounted; skipping"
PASSWORD = "s3cret"

STUB_PSQL = """#!/usr/bin/env bash
printf '%s\\n' "$@" > "$PSQL_ARGS_LOG"
cat > "$PSQL_STDIN_LOG"
"""


class WebReaderInitTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        stub = self.dir / "psql"
        stub.write_text(STUB_PSQL)
        stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
        self.args_log = self.dir / "args.log"
        self.stdin_log = self.dir / "stdin.log"

    def run_script(self, **extra: str) -> subprocess.CompletedProcess:
        env = {
            "PATH": f"{self.dir}{os.pathsep}{os.environ['PATH']}",
            "POSTGRES_USER": "roadtrip",
            "POSTGRES_DB": "roadtrip",
            "PSQL_ARGS_LOG": str(self.args_log),
            "PSQL_STDIN_LOG": str(self.stdin_log),
            **extra,
        }
        return subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, check=False)

    def test_no_password_mounted_skips_without_touching_postgres(self) -> None:
        result = self.run_script()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(SKIP_MESSAGE, result.stdout)
        self.assertFalse(self.args_log.exists())

    def test_an_empty_password_file_skips_too(self) -> None:
        empty = self.dir / "empty"
        empty.write_text("")

        result = self.run_script(WEB_DB_PASSWORD_FILE=str(empty))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(SKIP_MESSAGE, result.stdout)
        self.assertFalse(self.args_log.exists())

    def test_a_password_creates_or_resets_the_role(self) -> None:
        password_file = self.dir / "web_db_password"
        password_file.write_text(PASSWORD)

        result = self.run_script(WEB_DB_PASSWORD_FILE=str(password_file))

        self.assertEqual(0, result.returncode, result.stderr)
        args = self.args_log.read_text().splitlines()
        self.assertIn("--set=ON_ERROR_STOP=1", args)
        self.assertIn("--variable=web_user=roadtrip_web", args)
        self.assertIn(f"--variable=web_password={PASSWORD}", args)
        stdin = self.stdin_log.read_text()
        self.assertIn("CREATE ROLE", stdin)
        self.assertIn("ALTER ROLE", stdin)


if __name__ == "__main__":
    unittest.main()
