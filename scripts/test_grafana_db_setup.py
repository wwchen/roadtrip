import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
GRANTS_MIGRATION = (
    REPO_ROOT
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "R__grafana_reader_grants.sql"
)
POSTGRES_INIT = REPO_ROOT / "postgres-init" / "10-grafana-reader.sh"
RETIRED_SETUP_SCRIPT = REPO_ROOT / "grafana" / "db" / "create-grafana-reader.sh"


class GrafanaDbSetupTest(unittest.TestCase):
    def test_retired_sidecar_setup_script_stays_removed(self) -> None:
        self.assertFalse(RETIRED_SETUP_SCRIPT.exists())

    def test_flyway_migration_owns_grafana_reader_grants(self) -> None:
        sql = GRANTS_MIGRATION.read_text(encoding="utf-8")

        self.assertIn("CREATE ROLE grafana_reader LOGIN", sql)
        self.assertIn("GRANT pg_read_all_stats TO grafana_reader", sql)
        self.assertIn("CREATE OR REPLACE VIEW grafana_api_cache_metadata AS", sql)
        self.assertIn("FROM api_cache;", sql)
        self.assertIn("GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader", sql)
        self.assertIn(
            "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_reader",
            sql,
        )
        self.assertIn("REVOKE SELECT ON api_cache FROM grafana_reader", sql)
        self.assertNotIn("PASSWORD", sql)

    def test_postgres_init_bootstraps_grafana_reader_password(self) -> None:
        script = POSTGRES_INIT.read_text(encoding="utf-8")

        self.assertIn(": \"${GRAFANA_DB_USER:=grafana_reader}\"", script)
        # The password comes from the mounted secret, not an env default: a
        # fallback would create the role with a password Grafana never uses and
        # surface much later as an opaque datasource auth error.
        self.assertIn(": \"${GRAFANA_DB_PASSWORD_FILE:?", script)
        self.assertNotIn("GRAFANA_DB_PASSWORD:=", script)
        self.assertIn('GRAFANA_DB_PASSWORD="$(cat "$GRAFANA_DB_PASSWORD_FILE")"', script)
        self.assertIn("--variable=grafana_user=\"$GRAFANA_DB_USER\"", script)
        self.assertIn("--variable=grafana_password=\"$GRAFANA_DB_PASSWORD\"", script)
        self.assertIn("PASSWORD %L", script)

    def test_grafana_datasource_reads_the_same_mounted_secret(self) -> None:
        # The role's password and the connection using it must come from one
        # definition, or the datasource authenticates with the wrong value.
        datasource = (
            POSTGRES_INIT.parents[1]
            / "grafana/provisioning/datasources/roadtrip-postgres.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("$__file{/run/secrets/grafana_db_password}", datasource)
        self.assertNotIn("$GRAFANA_DB_PASSWORD", datasource)


if __name__ == "__main__":
    unittest.main()
