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


class BackendAuthProviderPassthroughTest(unittest.TestCase):
    """RFC 0009's rollback is `AUTH_PROVIDER=auth0`. That only works if the
    selector actually reaches the backend process — application.yaml reads it,
    but a container env var is only visible if compose passes it in. Regression
    guard: an earlier revision defaulted the provider without this passthrough,
    so `AUTH_PROVIDER=auth0 make run env=prod` silently kept the default."""

    def _backend_environment(self) -> list[str]:
        backend = compose("docker-compose.yml")["services"]["backend"]
        env = backend["environment"]
        # environment may be a list ("K=V") or a mapping; normalise to list form.
        if isinstance(env, dict):
            return [f"{k}={v}" for k, v in env.items()]
        return list(env)

    def test_backend_passes_auth_provider_through_to_the_container(self):
        env = self._backend_environment()
        auth_provider = [e for e in env if e.split("=", 1)[0] == "AUTH_PROVIDER"]

        self.assertEqual(
            1,
            len(auth_provider),
            "backend service must declare AUTH_PROVIDER so the RFC 0009 rollback "
            "selector reaches the process; found: " + repr(auth_provider),
        )
        # Must interpolate from the host env, defaulting to clerk — matching
        # application.yaml's `provider: \"${AUTH_PROVIDER:clerk}\"`. A hardcoded
        # value would ignore an operator's rollback flip.
        self.assertEqual(
            "AUTH_PROVIDER=${AUTH_PROVIDER:-clerk}",
            auth_provider[0],
            "AUTH_PROVIDER must pass through from the host env with a clerk default",
        )


class SandboxAuthConfigTest(unittest.TestCase):
    def test_sandbox_deploy_uses_dev_auth_secrets(self):
        deploy_script = (ROOT / "scripts" / "deploy.sh").read_text()
        makefile = (ROOT / "Makefile").read_text()
        release_manifest = (ROOT / "deploy" / "release-manifest.txt").read_text().splitlines()

        self.assertIn('SANDBOX_SECRETS_ENV="local"', deploy_script)
        self.assertIn('exec "${SANDBOX_SECRETS_ENV}" -- docker compose', deploy_script)
        self.assertNotIn("exec prod -- docker compose", deploy_script)
        self.assertIn("SANDBOX_BRANCH=", makefile)
        self.assertIn("secrets/local.enc.env", release_manifest)

    def test_sandbox_backend_uses_real_auth_flow(self):
        backend = compose("docker-compose.sandbox.yml")["services"]["backend"]
        env = backend["environment"]

        self.assertIn("ROADTRIP_PROFILE=prod", env)
        self.assertIn("ROADTRIP_WEB_ROOT_URL=${ROADTRIP_WEB_ROOT_URL:?ROADTRIP_WEB_ROOT_URL is required}", env)
        self.assertIn("AUTH_PROVIDER=clerk", env)
        self.assertFalse(
            any(e.startswith("ROADTRIP_SANDBOX_ASSUME_USER=") for e in env),
            "sandbox must not enable the removed assume-user auth bypass",
        )

    def test_sandbox_compose_does_not_use_fallback_interpolation(self):
        sandbox_compose = (ROOT / "docker-compose.sandbox.yml").read_text()

        self.assertNotIn(":-", sandbox_compose)

    def test_sandbox_backend_receives_same_secret_mounts_as_prod_backend(self):
        sandbox = compose("docker-compose.sandbox.yml")["services"]["backend"]
        prod_secrets = compose("docker-compose.secrets.yml")["services"]["backend"]["secrets"]

        self.assertEqual(
            sorted(prod_secrets),
            sorted(sandbox["secrets"]),
            "sandbox backend secret mounts must stay aligned with the generated prod backend mounts",
        )

    def test_sandbox_public_hosts_come_from_fixed_slots(self):
        deploy_script = (ROOT / "scripts" / "deploy.sh").read_text()
        sandbox_action = (ROOT / ".github" / "actions" / "sandbox" / "action.yml").read_text()
        sandbox_docs = (ROOT / "docs" / "sandbox-deploys.md").read_text()

        self.assertIn("SANDBOX_SLOT_IDS=(1 2 3 4 5)", deploy_script)
        self.assertIn("SLOT=%s", deploy_script)
        self.assertIn('local SANDBOX_SHA', deploy_script)
        self.assertNotIn('export SANDBOX_SHA="${SANDBOX_TEARDOWN_COMPOSE_SHA}"', deploy_script)
        self.assertIn("steps.start.outputs.url", sandbox_action)
        self.assertNotIn("url=https://roadtrip-sb-${SLUG}.floo.ca", sandbox_action)
        for slot in range(1, 6):
            self.assertIn(f"https://roadtrip-sb-{slot}.floo.ca/auth/callback", sandbox_docs)


class ImmutableApplicationImageTest(unittest.TestCase):
    def test_deploy_uses_images_and_versioned_data_while_local_uses_bind_mounts(self):
        base = compose("docker-compose.yml")
        sandbox = compose("docker-compose.sandbox.yml")
        local = compose("docker-compose.local.yml")
        backend = base["services"]["backend"]
        mount = "./frontend/dist:/app/static/frontend/dist:ro"
        base_volumes = backend.get("volumes", [])
        sandbox_volumes = sandbox["services"]["backend"].get("volumes", [])
        local_volumes = local["services"]["backend"].get("volumes", [])

        self.assertEqual("${ROADTRIP_BACKEND_IMAGE:-roadtrip/backend}", backend["image"])
        self.assertNotIn(mount, base_volumes)
        self.assertNotIn(mount, sandbox_volumes)
        self.assertIn(mount, local_volumes)
        self.assertIn("roadtrip-data:/app/static/data:ro", base_volumes)
        self.assertEqual(
            "${ROADTRIP_DATA_VOLUME:-roadtrip-data}",
            base["volumes"]["roadtrip-data"]["name"],
        )
        self.assertIn("roadtrip-data:/app/static/data:ro", sandbox_volumes)
        self.assertIn("./data:/app/static/data", local_volumes)
        self.assertNotIn("build", base["services"]["recgov-companion"])
        self.assertEqual("./companion", local["services"]["recgov-companion"]["build"]["context"])


if __name__ == "__main__":
    unittest.main()
