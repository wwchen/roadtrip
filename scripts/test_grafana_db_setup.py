import os
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SETUP_SCRIPT = REPO_ROOT / "grafana" / "db" / "create-grafana-reader.sh"


class GrafanaDbSetupTest(unittest.TestCase):
    def run_setup(
        self,
        fake_psql_body: str,
    ) -> tuple[subprocess.CompletedProcess[str], str, str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            fake_psql = temp_path / "psql"
            call_log = temp_path / "calls.log"
            stdin_log = temp_path / "stdin.sql"
            fake_psql.write_text(textwrap.dedent(fake_psql_body), encoding="utf-8")
            fake_psql.chmod(fake_psql.stat().st_mode | stat.S_IXUSR)

            env = os.environ.copy()
            env.update(
                {
                    "CALL_LOG": str(call_log),
                    "STDIN_LOG": str(stdin_log),
                    "GRAFANA_DB_SCHEMA_WAIT_SECONDS": "0",
                    "GRAFANA_DB_REPAIR_DROP": "false",
                    "POSTGRES_HOST": "postgres-test",
                    "POSTGRES_DB": "roadtrip_test",
                    "POSTGRES_USER": "roadtrip_test",
                    "POSTGRES_PASSWORD": "roadtrip_test",
                    "PSQL_BIN": str(fake_psql),
                }
            )

            result = subprocess.run(
                ["/bin/sh", str(SETUP_SCRIPT)],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

            calls = call_log.read_text(encoding="utf-8") if call_log.exists() else ""
            stdin = stdin_log.read_text(encoding="utf-8") if stdin_log.exists() else ""
            return result, calls, stdin

    def test_waits_for_api_cache_before_running_grants(self) -> None:
        result, calls, stdin = self.run_setup(
            """
            #!/bin/sh
            printf '%s\\n' "$*" >> "$CALL_LOG"
            if printf '%s' "$*" | grep -q "to_regclass"; then
              echo "f"
              exit 0
            fi
            cat > "$STDIN_LOG"
            echo "main SQL should not run before app schema is ready" >&2
            exit 3
            """
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "Timed out waiting for backend migrations to create public.api_cache",
            result.stderr,
        )
        self.assertIn("to_regclass('public.api_cache')", calls)
        self.assertEqual("", stdin)

    def test_runs_existing_grant_sql_after_api_cache_exists(self) -> None:
        result, calls, sql = self.run_setup(
            """
            #!/bin/sh
            printf '%s\\n' "$*" >> "$CALL_LOG"
            if printf '%s' "$*" | grep -q "to_regclass"; then
              echo "t"
              exit 0
            fi
            cat > "$STDIN_LOG"
            exit 0
            """
        )

        self.assertEqual("", result.stderr)
        self.assertEqual(0, result.returncode)
        self.assertIn("-h postgres-test -U roadtrip_test -d roadtrip_test", calls)
        self.assertIn("-v ON_ERROR_STOP=1", calls)
        self.assertIn("CREATE OR REPLACE VIEW grafana_api_cache_metadata AS", sql)
        self.assertIn("FROM api_cache;", sql)
        self.assertIn("REVOKE SELECT ON api_cache", sql)


if __name__ == "__main__":
    unittest.main()
