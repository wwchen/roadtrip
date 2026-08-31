"""Tests for secrets/manage.py.

The registry, generator and validation tests run anywhere. Round-trip tests
need the real `sops` and `age-keygen` binaries and skip without them, so CI
still exercises everything that doesn't require an encryption toolchain.
"""

import argparse
import shutil
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "secrets"))

import manage  # noqa: E402

HAS_SOPS = shutil.which("sops") is not None and shutil.which("age-keygen") is not None

# A value with `=`, `;`, `&` and a stray quote. Cookie headers look like this,
# and they are what clever dotenv handling is most likely to mangle.
NASTY_VALUE = 'ak_bmsc=A1B2==; _abck=xy"z; bm_sz=q=1&r=2'


def args(**kwargs):
    return argparse.Namespace(**kwargs)


class RegistryParseTest(unittest.TestCase):
    def test_parses_the_real_registry(self):
        registry = manage.load_registry()
        self.assertGreater(len(registry), 5)
        self.assertIn("MAPBOX_TOKEN", registry)
        self.assertEqual(["backend"], registry["MAPBOX_TOKEN"].consumers)
        self.assertEqual(["prod"], registry["MAPBOX_TOKEN"].required_in)

    def test_inline_lists_and_empty_lists(self):
        parsed = manage.parse_registry(
            "A_KEY:\n"
            "  description: thing\n"
            "  consumers: [backend, host-tools]\n"
            "  required_in: []\n"
        )
        self.assertEqual(["backend", "host-tools"], parsed["A_KEY"].consumers)
        self.assertEqual([], parsed["A_KEY"].required_in)

    def test_comments_and_blank_lines_are_ignored(self):
        parsed = manage.parse_registry(
            "# leading comment\n\n"
            "A_KEY:\n"
            "  # why this exists\n"
            "  description: thing\n"
            "  consumers: [backend]\n"
            "  required_in: []\n"
        )
        self.assertEqual(["A_KEY"], list(parsed))

    def test_a_missing_field_is_an_error_not_a_default(self):
        # Silently defaulting `consumers` would generate a compose file that
        # gives the secret to nobody, which is a confusing way to fail.
        with self.assertRaises(manage.SecretsError):
            manage.parse_registry("A_KEY:\n  description: thing\n")

    def test_unparseable_line_raises_rather_than_being_skipped(self):
        with self.assertRaises(manage.SecretsError):
            manage.parse_registry("A_KEY:\n    deeply: nested\n")

    def test_host_tools_are_not_compose_services(self):
        # There is nowhere to mount a file outside a container, so host-tools
        # secrets must not appear in the generated compose file.
        parsed = manage.parse_registry(
            "A_KEY:\n"
            "  description: thing\n"
            "  consumers: [host-tools]\n"
            "  required_in: []\n"
        )
        self.assertEqual([], parsed["A_KEY"].services())


class GeneratorTest(unittest.TestCase):
    """The generator is what makes `consumers` behaviour rather than prose."""

    def _registry(self, text):
        return manage.parse_registry(text)

    def test_each_service_gets_exactly_its_declared_secrets(self):
        rendered = manage.render_compose(
            self._registry(
                "SHARED:\n"
                "  description: x\n"
                "  consumers: [backend, grafana]\n"
                "  required_in: []\n"
                "BACKEND_ONLY:\n"
                "  description: x\n"
                "  consumers: [backend]\n"
                "  required_in: []\n"
            )
        )
        backend = rendered.split("  backend:")[1].split("  grafana:")[0]
        self.assertIn("- shared", backend)
        self.assertIn("- backend_only", backend)
        grafana = rendered.split("  grafana:")[1].split("\nsecrets:")[0]
        self.assertIn("- shared", grafana)
        self.assertNotIn("backend_only", grafana)

    def test_secrets_are_sourced_from_the_environment_not_a_file(self):
        # `file:` would require decrypting to disk, which is the thing this
        # design exists to avoid.
        rendered = manage.render_compose(
            self._registry(
                "A_KEY:\n  description: x\n  consumers: [backend]\n  required_in: []\n"
            )
        )
        self.assertIn("  a_key:\n    environment: A_KEY", rendered)
        self.assertNotIn("file:", rendered)

    def test_output_is_deterministic(self):
        registry = manage.load_registry()
        self.assertEqual(manage.render_compose(registry), manage.render_compose(registry))

    def test_committed_file_matches_the_registry(self):
        # The same assertion CI makes; catches a registry edit committed
        # without regenerating.
        self.assertEqual(
            manage.GENERATED_COMPOSE.read_text(),
            manage.render_compose(manage.load_registry()),
            "docker-compose.secrets.yml is stale — run ./secrets/manage.py generate",
        )

    def test_check_flag_detects_a_hand_edited_generated_file(self):
        original = manage.GENERATED_COMPOSE.read_text()
        try:
            manage.GENERATED_COMPOSE.write_text(original + "\n# hand-edited\n")
            self.assertEqual(1, manage.cmd_generate(args(check=True)))
        finally:
            manage.GENERATED_COMPOSE.write_text(original)


class ComposeAgreementTest(unittest.TestCase):
    """The generated file and docker-compose.yml must describe the same world."""

    def test_every_generated_service_exists_in_the_base_compose_file(self):
        compose = (ROOT / "docker-compose.yml").read_text()
        for service in {
            svc for s in manage.load_registry().values() for svc in s.services()
        }:
            self.assertIn(
                f"\n  {service}:", compose, f"registry names a service compose lacks: {service}"
            )

    def test_no_service_still_mounts_the_whole_env_file(self):
        # env_file: .env handed every secret to every listed service; the
        # per-service secrets: lists replace it.
        self.assertNotIn("path: .env", (ROOT / "docker-compose.yml").read_text())


class SandboxComposeDriftTest(unittest.TestCase):
    """docker-compose.sandbox.yml is hand-maintained; the registry is not.

    The sandbox runs with ROADTRIP_PROFILE=prod, so a backend secret missing
    from its mounts fails at boot with MissingSecretsException instead of in
    CI. This nearly shipped with ENCRYPTION_KEY; these tests make `check`
    catch it.
    """

    def _sandbox(self, service_secrets, toplevel):
        lines = ["services:", "  backend:", "    image: x", "    secrets:"]
        lines += [f"      - {name}" for name in service_secrets]
        lines += ["", "secrets:"]
        for name, env in toplevel.items():
            lines += [f"  {name}:", f"    environment: {env}"]
        return "\n".join(lines) + "\n"

    def _registry(self, *names):
        return manage.parse_registry(
            "".join(
                f"{name}:\n  description: x\n  consumers: [backend]\n  required_in: []\n"
                for name in names
            )
        )

    def test_parses_service_list_and_toplevel_map(self):
        mounts, toplevel = manage.parse_sandbox_compose(
            self._sandbox(["a_key", "b_key"], {"a_key": "A_KEY", "b_key": "B_KEY"})
        )
        self.assertEqual({"a_key", "b_key"}, mounts)
        self.assertEqual({"a_key": "A_KEY", "b_key": "B_KEY"}, toplevel)

    def test_parses_the_real_sandbox_compose_file(self):
        mounts, toplevel = manage.parse_sandbox_compose(
            (ROOT / "docker-compose.sandbox.yml").read_text()
        )
        self.assertIn("mapbox_token", mounts)
        self.assertEqual("MAPBOX_TOKEN", toplevel["mapbox_token"])

    def test_missing_backend_secret_is_an_error(self):
        errors = manage.sandbox_drift_errors(
            self._registry("A_KEY", "B_KEY"),
            self._sandbox(["a_key"], {"a_key": "A_KEY"}),
        )
        self.assertEqual(1, len(errors))
        self.assertIn("B_KEY", errors[0])

    def test_unregistered_mount_is_an_error(self):
        # A secret removed from the registry must not linger in the sandbox
        # file, where its unset source variable would fail compose startup.
        errors = manage.sandbox_drift_errors(
            self._registry("A_KEY"),
            self._sandbox(["a_key", "ghost"], {"a_key": "A_KEY", "ghost": "GHOST"}),
        )
        self.assertTrue(any("ghost" in e for e in errors))

    def test_toplevel_entry_must_source_the_matching_variable(self):
        errors = manage.sandbox_drift_errors(
            self._registry("A_KEY"),
            self._sandbox(["a_key"], {"a_key": "WRONG_VAR"}),
        )
        self.assertTrue(any("A_KEY" in e for e in errors))

    def test_mount_without_toplevel_entry_is_an_error(self):
        errors = manage.sandbox_drift_errors(
            self._registry("A_KEY"), self._sandbox(["a_key"], {})
        )
        self.assertTrue(any("a_key" in e for e in errors))

    def test_non_backend_secrets_are_not_expected_in_the_sandbox(self):
        registry = manage.parse_registry(
            "A_KEY:\n  description: x\n  consumers: [backend]\n  required_in: []\n"
            "G_KEY:\n  description: x\n  consumers: [grafana]\n  required_in: []\n"
        )
        errors = manage.sandbox_drift_errors(
            registry, self._sandbox(["a_key"], {"a_key": "A_KEY"})
        )
        self.assertEqual([], errors)

    def test_the_committed_sandbox_file_agrees_with_the_registry(self):
        # The same assertion `check` makes in CI.
        errors = manage.sandbox_drift_errors(
            manage.load_registry(), (ROOT / "docker-compose.sandbox.yml").read_text()
        )
        self.assertEqual([], errors)

    def test_check_reports_sandbox_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            stale = Path(tmp) / "docker-compose.sandbox.yml"
            stale.write_text(self._sandbox(["mapbox_token"], {"mapbox_token": "MAPBOX_TOKEN"}))
            with unittest.mock.patch.object(manage, "SANDBOX_COMPOSE", stale):
                self.assertEqual(1, manage.cmd_check(args(staged=False)))


class HostToolsDriftTest(unittest.TestCase):
    def test_credentials_read_by_scripts_are_registered(self):
        """A fetcher reading an unregistered credential has no way to receive it."""
        registry = manage.load_registry()
        pattern = r"""os\.environ(?:\.get\(|\[)["']([A-Z][A-Z0-9_]*)["']"""
        import re

        unregistered = set()
        for path in (ROOT / "scripts").glob("*.py"):
            if path.name.startswith("test_"):
                continue
            for name in re.findall(pattern, path.read_text()):
                looks_secret = any(
                    part in name for part in ("KEY", "TOKEN", "SECRET", "PASSWORD")
                )
                if looks_secret and name not in registry and name not in KNOWN_NON_VAULT:
                    unregistered.add(f"{name} ({path.name})")
        self.assertEqual(set(), unregistered)


# Credentials deliberately outside the vault, with the reason.
KNOWN_NON_VAULT = {
    # IP-bound and minted manually per machine into .env.local; sharing
    # one encrypted copy would give every host a cookie only one can use.
    "TESLA_COOKIES",
    # Grafana's admin password is GRAFANA_ADMIN_PASSWORD in the registry; this
    # script's own flag name differs.
    "GRAFANA_PASSWORD",
}


@unittest.skipUnless(HAS_SOPS, "requires sops and age-keygen")
class RoundTripTest(unittest.TestCase):
    def _isolate(self, tmp):
        """Point the module at a throwaway secrets dir with its own age key."""
        secrets_dir = Path(tmp) / "secrets"
        secrets_dir.mkdir()
        key_file = Path(tmp) / "keys.txt"
        subprocess.run(["age-keygen", "-o", str(key_file)], check=True, capture_output=True)
        public = next(
            line.split(":", 1)[1].strip()
            for line in key_file.read_text().splitlines()
            if line.startswith("# public key:")
        )
        (secrets_dir / ".sops.yaml").write_text(
            "creation_rules:\n"
            "  - path_regex: .*\\.enc\\.env$\n"
            "    key_groups:\n"
            "      - age:\n"
            f"          - {public}\n"
        )
        return secrets_dir, key_file

    def test_values_survive_encryption_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, key_file = self._isolate(tmp)
            with unittest.mock.patch.multiple(
                manage,
                SECRETS_DIR=secrets_dir,
                SOPS_CONFIG=secrets_dir / ".sops.yaml",
                AGE_KEY_FILE=key_file,
            ):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": NASTY_VALUE})
                raw = (secrets_dir / "common.enc.env").read_text()

                self.assertNotIn(NASTY_VALUE, raw)
                # Keys stay readable so diffs are reviewable.
                self.assertIn("SLACK_BOT_TOKEN=ENC[", raw)
                self.assertEqual(NASTY_VALUE, manage.read_vault("common")["SLACK_BOT_TOKEN"])

    def test_overlay_wins_over_common(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, key_file = self._isolate(tmp)
            with unittest.mock.patch.multiple(
                manage,
                SECRETS_DIR=secrets_dir,
                SOPS_CONFIG=secrets_dir / ".sops.yaml",
                AGE_KEY_FILE=key_file,
            ):
                manage.write_vault("common", {"A": "from-common", "B": "shared"})
                manage.write_vault("prod", {"A": "from-prod"})

                merged = manage.load_merged("prod")
                self.assertEqual("from-prod", merged["A"])
                self.assertEqual("shared", merged["B"])

    def test_empty_overlay_is_still_a_valid_vault(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, key_file = self._isolate(tmp)
            with unittest.mock.patch.multiple(
                manage,
                SECRETS_DIR=secrets_dir,
                SOPS_CONFIG=secrets_dir / ".sops.yaml",
                AGE_KEY_FILE=key_file,
            ):
                manage.write_vault("local", {})
                self.assertEqual({}, manage.read_vault("local"))


class VaultStateTest(unittest.TestCase):
    def test_committed_vaults_are_encrypted(self):
        # Runs without an age key: encryption is verifiable from the ciphertext
        # structure alone, which is why CI can check it.
        for name in (manage.COMMON_ENV, *manage.ENVIRONMENTS):
            path = manage.vault_path(name)
            self.assertTrue(path.exists(), f"{path.name} missing")
            text = path.read_text()
            self.assertIn("sops_version", text, f"{path.name} is not encrypted")
            for key, value in manage.parse_dotenv(text).items():
                if value:
                    self.assertTrue(
                        value.startswith("ENC["), f"{key} is plaintext in {path.name}"
                    )

    def test_no_plaintext_env_is_tracked(self):
        tracked = subprocess.run(
            ["git", "ls-files", ".env", ".env.local"],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual("", tracked.stdout.strip())


if __name__ == "__main__":
    unittest.main()
