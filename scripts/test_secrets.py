"""Tests for secrets/manage.py.

The registry, generator and validation tests run anywhere. Round-trip tests
need the real `sops` and `age-keygen` binaries and skip without them, so CI
still exercises everything that doesn't require an encryption toolchain.
"""

import argparse
import contextlib
import io
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

# A syntactically valid age public key that is not a real recipient, for the
# tests that only exercise the .sops.yaml edit.
NEW_KEY = "age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p"


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


class RecipientEditTest(unittest.TestCase):
    """The .sops.yaml half of `enroll`, which needs no encryption toolchain."""

    def test_a_new_key_lands_in_the_block_with_its_holder_above_it(self):
        text = manage.SOPS_CONFIG.read_text()
        updated = manage.add_recipient(text, NEW_KEY, "qa@box — test rig")

        self.assertIn(f"          # qa@box — test rig\n          - {NEW_KEY}\n", updated)
        # The keys that were already there still are: enrollment adds a
        # recipient, it never rewrites the list.
        for line in text.splitlines():
            if manage.RECIPIENT_RE.match(line):
                self.assertIn(line, updated.splitlines())

    def test_the_result_is_still_a_list_this_tool_can_read_back(self):
        updated = manage.add_recipient(manage.SOPS_CONFIG.read_text(), NEW_KEY, "qa@box")
        found = [
            m.group(2) for m in (manage.RECIPIENT_RE.match(line) for line in updated.splitlines())
            if m
        ]
        self.assertIn(NEW_KEY, found)

    def test_a_key_already_listed_is_not_added_again(self):
        # Re-running enroll after a failed rotate must not double the entry.
        text = manage.SOPS_CONFIG.read_text()
        already = manage.RECIPIENT_RE.match(
            next(line for line in text.splitlines() if manage.RECIPIENT_RE.match(line))
        ).group(2)
        self.assertIsNone(manage.add_recipient(text, already, "someone"))

    def test_an_empty_age_block_gets_its_first_recipient(self):
        updated = manage.add_recipient(
            "creation_rules:\n  - path_regex: x\n    key_groups:\n      - age:\n",
            NEW_KEY,
            "first@host",
        )
        self.assertEqual(
            "creation_rules:\n  - path_regex: x\n    key_groups:\n      - age:\n"
            f"          # first@host\n          - {NEW_KEY}\n",
            updated,
        )

    def test_a_config_with_nowhere_to_put_the_key_is_an_error(self):
        # Better to refuse than to write a recipient sops will never read.
        with self.assertRaises(manage.SecretsError):
            manage.add_recipient("creation_rules: []\n", NEW_KEY, "nobody")

    def test_a_malformed_key_is_rejected_before_anything_is_touched(self):
        before = manage.SOPS_CONFIG.read_text()
        with self.assertRaises(manage.SecretsError):
            manage.enroll_recipient("age1-typo", "qa@box", "")
        self.assertEqual(before, manage.SOPS_CONFIG.read_text())

    def test_an_unlabelled_key_is_refused(self):
        # A recipient nobody can name is a recipient nobody can remove.
        with self.assertRaises(manage.SecretsError):
            manage.enroll_recipient(NEW_KEY, None, "")


class NextStepsTest(unittest.TestCase):
    """Every exit from `enroll` has to end in a command the reader can run."""

    HANDOFF = f'./secrets/manage.py enroll {NEW_KEY} --as "qa@box"'

    def steps(self, local, upstream):
        return manage.enrollment_next_steps(NEW_KEY, self.HANDOFF, local, upstream)

    def test_an_enrolled_host_is_pointed_at_the_stack_not_at_the_handshake(self):
        steps = self.steps([NEW_KEY], [NEW_KEY])
        self.assertIn("./secrets/manage.py check", "\n".join(steps))
        self.assertNotIn(self.HANDOFF, steps)

    def test_a_key_enrolled_upstream_asks_for_a_pull_not_another_handoff(self):
        # The dead end the two-machine handshake leaves: enrolled by someone
        # else's checkout, so the recipient list here is simply out of date.
        steps = self.steps([], [NEW_KEY])
        self.assertIn("  git pull", steps)
        self.assertNotIn(self.HANDOFF, steps)

    def test_an_unenrolled_host_gets_the_line_to_send_and_what_to_do_after(self):
        steps = self.steps([], [])
        self.assertIn(f"  {self.HANDOFF}", steps)
        self.assertIn("  git pull", steps)
        self.assertIn("  ./secrets/manage.py enroll   # re-run to confirm", steps)

    def test_an_unknown_upstream_is_treated_as_not_yet_enrolled(self):
        # No remote-tracking ref, or a detached HEAD. "Can't tell" must not
        # read as "done" — the handoff is still the next thing to do.
        self.assertEqual(self.steps([], None), self.steps([], []))


@unittest.skipUnless(HAS_SOPS, "requires sops and age-keygen")
class EnrollTest(unittest.TestCase):
    """The whole handshake: edit, re-wrap, and the enrolled host can decrypt."""

    def _isolate(self, tmp):
        secrets_dir = Path(tmp) / "secrets"
        secrets_dir.mkdir()
        keys = {}
        for who in ("existing", "joiner", "stranger"):
            key_file = Path(tmp) / f"{who}.txt"
            subprocess.run(["age-keygen", "-o", str(key_file)], check=True, capture_output=True)
            public = next(
                line.split(":", 1)[1].strip()
                for line in key_file.read_text().splitlines()
                if line.startswith(manage.AGE_PUBLIC_KEY_COMMENT)
            )
            keys[who] = (key_file, public)
        (secrets_dir / ".sops.yaml").write_text(
            "creation_rules:\n"
            "  - path_regex: .*\\.enc\\.env$\n"
            "    key_groups:\n"
            "      - age:\n"
            "          # existing — the host running enroll\n"
            f"          - {keys['existing'][1]}\n"
        )
        return secrets_dir, keys

    def _patch(self, secrets_dir, key_file):
        return unittest.mock.patch.multiple(
            manage,
            SECRETS_DIR=secrets_dir,
            SOPS_CONFIG=secrets_dir / ".sops.yaml",
            AGE_KEY_FILE=key_file,
        )

    def _enroll(self, key, name="joiner@host", note=""):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = manage.enroll_recipient(key, name, note)
        return code, out.getvalue()

    def test_the_enrolled_host_can_actually_decrypt_afterwards(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": NASTY_VALUE})
                self._enroll(keys["joiner"][1])

            # The claim that matters is not what .sops.yaml says, but whether
            # the joiner's key opens the ciphertext that is on disk.
            with self._patch(secrets_dir, keys["joiner"][0]):
                self.assertEqual(NASTY_VALUE, manage.read_vault("common")["SLACK_BOT_TOKEN"])

    def test_every_vault_is_re_wrapped_not_just_the_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                for env_name in (manage.COMMON_ENV, *manage.ENVIRONMENTS):
                    manage.write_vault(env_name, {"SLACK_BOT_TOKEN": "v"})
                self._enroll(keys["joiner"][1])

                for env_name in (manage.COMMON_ENV, *manage.ENVIRONMENTS):
                    path = manage.vault_path(env_name)
                    self.assertIn(keys["joiner"][1], manage.vault_recipients(path), path.name)

    def test_enrolling_twice_leaves_one_entry(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": "v"})
                self._enroll(keys["joiner"][1])
                self._enroll(keys["joiner"][1])

                config = (secrets_dir / ".sops.yaml").read_text()
                self.assertEqual(1, config.count(keys["joiner"][1]))

    def test_the_enrolling_side_says_what_to_tell_the_other_machine(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": "v"})
                _, output = self._enroll(keys["joiner"][1], "joiner@host")

            self.assertIn("git commit", output)
            self.assertIn("git push", output)
            self.assertIn("Then tell joiner@host to run:", output)
            self.assertIn("git pull && ./secrets/manage.py enroll", output)

    def test_re_running_on_the_enrolled_machine_reports_it_is_done(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": "v"})
                self._enroll(keys["joiner"][1])

            # Patched out because it symlinks into the real ~/.config.
            with self._patch(secrets_dir, keys["joiner"][0]), \
                    unittest.mock.patch.object(manage, "link_native_key_path", lambda: None), \
                    contextlib.redirect_stdout(io.StringIO()) as out:
                manage.enroll_this_host()

            self.assertIn("already a recipient", out.getvalue())

    def test_a_note_becomes_part_of_the_holder_comment(self):
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": "v"})
                self._enroll(keys["joiner"][1], "mini-ca", "deploy host")

                self.assertIn("# mini-ca — deploy host", (secrets_dir / ".sops.yaml").read_text())

    def test_a_host_that_cannot_decrypt_is_refused_before_editing(self):
        # sops would fail on the re-wrap anyway, but only after .sops.yaml had
        # already changed — a half-enrolled state someone has to clean up.
        with tempfile.TemporaryDirectory() as tmp:
            secrets_dir, keys = self._isolate(tmp)
            with self._patch(secrets_dir, keys["existing"][0]):
                manage.write_vault("common", {"SLACK_BOT_TOKEN": "v"})
            before = (secrets_dir / ".sops.yaml").read_text()

            with self._patch(secrets_dir, keys["stranger"][0]):
                with self.assertRaises(manage.SecretsError):
                    self._enroll(keys["joiner"][1])

            self.assertEqual(before, (secrets_dir / ".sops.yaml").read_text())


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

    def test_every_declared_recipient_can_actually_open_the_vaults(self):
        # The drift `enroll` exists to prevent: a .sops.yaml entry added without
        # a re-wrap reads as enrolled while the ciphertext says otherwise. Needs
        # no age key — recipients are metadata, not encrypted content.
        declared = {
            manage.RECIPIENT_RE.match(line).group(2)
            for line in manage.SOPS_CONFIG.read_text().splitlines()
            if manage.RECIPIENT_RE.match(line)
        }
        for name in (manage.COMMON_ENV, *manage.ENVIRONMENTS):
            path = manage.vault_path(name)
            missing = declared - set(manage.vault_recipients(path))
            self.assertEqual(
                set(), missing,
                f"{path.name} is not wrapped for {missing} — run ./secrets/manage.py rotate",
            )

    def test_no_plaintext_env_is_tracked(self):
        tracked = subprocess.run(
            ["git", "ls-files", ".env", ".env.local"],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual("", tracked.stdout.strip())


if __name__ == "__main__":
    unittest.main()
