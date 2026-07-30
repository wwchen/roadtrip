import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def compose(name: str) -> dict:
    return yaml.safe_load((ROOT / name).read_text())


class DockerComposeRestartPolicyTest(unittest.TestCase):
    """The base file is what the deploy host runs, so its restart policies are
    production's reboot behaviour. The local override is where dev-only
    lifecycle rules (Tilt owns the container) belong."""

    def test_postgres_restarts_itself_in_the_base_file_prod_runs(self):
        postgres = compose("docker-compose.yml")["services"]["postgres"]

        # `no` here means a mini-ca reboot brings back every service except the
        # database, leaving the site down until someone deploys by hand.
        self.assertEqual("unless-stopped", postgres.get("restart"))

    def test_local_override_ties_postgres_to_whatever_brought_it_up(self):
        postgres = compose("docker-compose.local.yml")["services"]["postgres"]

        self.assertEqual("no", postgres.get("restart"))

    def test_local_override_opts_every_service_out_of_auto_restart(self):
        local = compose("docker-compose.local.yml")["services"]

        for name, service in local.items():
            with self.subTest(service=name):
                self.assertEqual(
                    "no",
                    service.get("restart"),
                    f"{name} is overridden locally but would still auto-restart",
                )


if __name__ == "__main__":
    unittest.main()
