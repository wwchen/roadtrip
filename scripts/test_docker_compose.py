import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


class DockerComposeTest(unittest.TestCase):
    def test_backend_service_loads_local_dotenv_for_fetcher_secrets(self):
        compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
        backend = compose["services"]["backend"]

        self.assertIn(
            {"path": ".env", "required": False},
            backend.get("env_file", []),
        )


if __name__ == "__main__":
    unittest.main()
