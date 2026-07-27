"""Tests for scripts/manage_secrets.py.

The parsing, merging, and validation tests run anywhere. The round-trip tests
need the real `sops` and `age-keygen` binaries and skip without them, so CI
still exercises everything that doesn't require an encryption toolchain.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import manage_secrets as secrets_tool  # noqa: E402


HAS_SOPS = shutil.which("sops") is not None and shutil.which("age-keygen") is not None

# A value with `=`, `;`, `&` and a stray quote. Cookie headers are shaped like
# this, and they're what clever dotenv parsing is most likely to mangle.
NASTY_VALUE = 'ak_bmsc=A1B2==; _abck=xy"z; bm_sz=q=1&r=2'
COOKIE_KEY = "SOME_COOKIE_HEADER"


@contextmanager
def temp_root(**overrides):
    """Repoint the module's path constants at a throwaway directory."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "secrets").mkdir()
        original = {
            name: getattr(secrets_tool, name)
            for name in (
                "ROOT",
                "SECRETS_FILE",
                "ENV_FILE",
                "ENV_LOCAL_FILE",
                "ENV_EXAMPLE_FILE",
                "SOPS_CONFIG",
            )
        }
        secrets_tool.ROOT = root
        secrets_tool.SECRETS_FILE = root / "secrets" / "secrets.enc.env"
        secrets_tool.ENV_FILE = root / ".env"
        secrets_tool.ENV_LOCAL_FILE = root / ".env.local"
        secrets_tool.ENV_EXAMPLE_FILE = root / ".env.example"
        secrets_tool.SOPS_CONFIG = root / ".sops.yaml"
        secrets_tool.SOPS_CONFIG.write_text("creation_rules: []\n")
        for name, value in overrides.items():
            setattr(secrets_tool, name, value)
        try:
            yield root
        finally:
            for name, value in original.items():
                setattr(secrets_tool, name, value)


def args(**kwargs):
    return argparse.Namespace(**kwargs)


class ParseDotenvTest(unittest.TestCase):
    def test_preserves_values_containing_equals_and_quotes(self):
        entries = secrets_tool.parse_dotenv(f"{COOKIE_KEY}={NASTY_VALUE}\n")
        self.assertEqual([(COOKIE_KEY, f"{COOKIE_KEY}={NASTY_VALUE}")], entries)

    def test_skips_blanks_and_comments(self):
        text = "# a comment\n\nA=1\n   \n# B=2\nC=3\n"
        self.assertEqual(["A", "C"], [key for key, _ in secrets_tool.parse_dotenv(text)])

    def test_keeps_empty_values(self):
        self.assertEqual([("A", "A=")], secrets_tool.parse_dotenv("A=\n"))


class MergeDotenvTest(unittest.TestCase):
    def test_override_replaces_in_place_without_duplicating(self):
        base = [("A", "A=1"), ("B", "B=2")]
        merged = secrets_tool.merge_dotenv(base, [("B", "B=99")])
        self.assertEqual([("A", "A=1"), ("B", "B=99")], merged)

    def test_new_keys_are_appended(self):
        merged = secrets_tool.merge_dotenv([("A", "A=1")], [("Z", "Z=9")])
        self.assertEqual([("A", "A=1"), ("Z", "Z=9")], merged)

    def test_no_overrides_is_identity(self):
        base = [("A", "A=1"), ("B", "B=2")]
        self.assertEqual(base, secrets_tool.merge_dotenv(base, []))


class CheckTest(unittest.TestCase):
    def _write_vault(self, root, body):
        (root / "secrets" / "secrets.enc.env").write_text(body)

    def test_rejects_a_plaintext_value(self):
        with temp_root() as root:
            self._write_vault(
                root,
                "API_KEY=totally-in-the-clear\nsops_version=3.13.3\n",
            )
            (root / ".env.example").write_text("API_KEY=\n")
            self.assertEqual(1, secrets_tool.cmd_check(args()))

    def test_rejects_a_vault_with_no_sops_metadata(self):
        with temp_root() as root:
            self._write_vault(root, "API_KEY=ENC[AES256_GCM,data:x]\n")
            (root / ".env.example").write_text("API_KEY=\n")
            self.assertEqual(1, secrets_tool.cmd_check(args()))

    def test_rejects_placeholder_recipients(self):
        with temp_root() as root:
            self._write_vault(
                root, "API_KEY=ENC[AES256_GCM,data:x]\nsops_version=3.13.3\n"
            )
            (root / ".env.example").write_text("API_KEY=\n")
            (root / ".sops.yaml").write_text("- age1PLACEHOLDER_REPLACE_ME\n")
            self.assertEqual(1, secrets_tool.cmd_check(args()))

    def test_rejects_a_key_missing_from_the_example_file(self):
        with temp_root() as root:
            self._write_vault(
                root,
                "API_KEY=ENC[AES256_GCM,data:x]\n"
                "UNDOCUMENTED=ENC[AES256_GCM,data:y]\n"
                "sops_version=3.13.3\n",
            )
            (root / ".env.example").write_text("API_KEY=\n")
            self.assertEqual(1, secrets_tool.cmd_check(args()))

    def test_staged_mode_reads_the_index_not_the_worktree(self):
        """The hook must judge the blob entering history, not what's on disk."""
        with temp_root() as root:
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(
                ["git", "config", "user.email", "t@example.com"], cwd=root, check=True
            )
            subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
            (root / ".env.example").write_text("API_KEY=\n")

            # Stage plaintext, then "fix" only the worktree copy. Un-staged
            # mode would pass; staged mode must still fail.
            self._write_vault(root, "API_KEY=in-the-clear\nsops_version=3.13.3\n")
            subprocess.run(
                ["git", "add", "secrets/secrets.enc.env"], cwd=root, check=True
            )
            self._write_vault(
                root, "API_KEY=ENC[AES256_GCM,data:x]\nsops_version=3.13.3\n"
            )

            self.assertEqual(0, secrets_tool.cmd_check(args(staged=False)))
            self.assertEqual(1, secrets_tool.cmd_check(args(staged=True)))

    def test_accepts_a_well_formed_vault(self):
        with temp_root() as root:
            self._write_vault(
                root, "API_KEY=ENC[AES256_GCM,data:x]\nsops_version=3.13.3\n"
            )
            # Documented-but-unset is a warning, not a failure: most keys in
            # .env.example are optional knobs.
            (root / ".env.example").write_text("API_KEY=\nOPTIONAL_KNOB=\n")
            self.assertEqual(0, secrets_tool.cmd_check(args()))


class MaterializeGuardTest(unittest.TestCase):
    def test_refuses_to_clobber_an_unmanaged_env(self):
        # Through main(), so this also covers SecretsError -> exit code 1.
        with temp_root() as root:
            (root / ".env").write_text("HANDWRITTEN=1\n")
            with open(os.devnull, "w") as devnull:
                stderr, sys.stderr = sys.stderr, devnull
                try:
                    self.assertEqual(1, secrets_tool.main(["materialize"]))
                finally:
                    sys.stderr = stderr
            self.assertEqual("HANDWRITTEN=1\n", (root / ".env").read_text())


@unittest.skipUnless(HAS_SOPS, "requires sops and age-keygen")
class RoundTripTest(unittest.TestCase):
    """End-to-end through real sops: import -> materialize -> check."""

    def _setup(self, root, plaintext):
        key_file = root / "age-keys.txt"
        subprocess.run(
            ["age-keygen", "-o", str(key_file)], check=True, capture_output=True
        )
        public_key = next(
            line.split(":", 1)[1].strip()
            for line in key_file.read_text().splitlines()
            if line.startswith("# public key:")
        )
        (root / ".sops.yaml").write_text(
            "creation_rules:\n"
            "  - path_regex: secrets/.*\\.enc\\.env$\n"
            "    key_groups:\n"
            "      - age:\n"
            f"          - {public_key}\n"
        )
        os.environ["SOPS_AGE_KEY_FILE"] = str(key_file)
        source = root / "plain.env"
        source.write_text(plaintext)
        return source

    def test_values_survive_encryption_byte_for_byte(self):
        with temp_root() as root:
            source = self._setup(
                root, f"{COOKIE_KEY}={NASTY_VALUE}\nPOSTGRES_PASSWORD=hunter2\n"
            )
            (root / ".env.example").write_text(
                f"{COOKIE_KEY}=\nPOSTGRES_PASSWORD=\n"
            )

            self.assertEqual(
                0, secrets_tool.cmd_import(args(source=str(source), force=False))
            )
            vault = (root / "secrets" / "secrets.enc.env").read_text()
            self.assertNotIn(NASTY_VALUE, vault)
            self.assertNotIn("hunter2", vault)
            # Keys stay readable so diffs are reviewable.
            self.assertIn(f"{COOKIE_KEY}=ENC[", vault)

            self.assertEqual(
                0, secrets_tool.cmd_materialize(args(force=False, verbose=False))
            )
            env = (root / ".env").read_text()
            self.assertIn(f"{COOKIE_KEY}={NASTY_VALUE}\n", env)
            self.assertIn("POSTGRES_PASSWORD=hunter2\n", env)
            self.assertEqual(0, secrets_tool.cmd_check(args()))

    def test_env_local_overrides_without_duplicating_keys(self):
        with temp_root() as root:
            source = self._setup(root, "RETENTION=14d\nSECRET=s3cret\n")
            (root / ".env.example").write_text("RETENTION=\nSECRET=\n")
            secrets_tool.cmd_import(args(source=str(source), force=False))
            (root / ".env.local").write_text("RETENTION=90d\nHOST_ONLY=yes\n")

            secrets_tool.cmd_materialize(args(force=False, verbose=False))
            entries = secrets_tool.parse_dotenv((root / ".env").read_text())
            keys = [key for key, _ in entries]

            self.assertEqual(len(keys), len(set(keys)), "generated .env has duplicates")
            self.assertEqual(dict(entries)["RETENTION"], "RETENTION=90d")
            self.assertIn("HOST_ONLY", keys)
            self.assertEqual(dict(entries)["SECRET"], "SECRET=s3cret")

    def test_materialize_leaves_an_unchanged_env_untouched(self):
        with temp_root() as root:
            source = self._setup(root, "SECRET=s3cret\n")
            (root / ".env.example").write_text("SECRET=\n")
            secrets_tool.cmd_import(args(source=str(source), force=False))

            secrets_tool.cmd_materialize(args(force=False, verbose=False))
            before = (root / ".env").stat().st_mtime_ns
            secrets_tool.cmd_materialize(args(force=False, verbose=False))

            # Compose and Tilt watch .env; a fresh mtime every run would churn
            # the stack for no reason.
            self.assertEqual(before, (root / ".env").stat().st_mtime_ns)

    def test_generated_env_is_owner_readable_only(self):
        with temp_root() as root:
            source = self._setup(root, "SECRET=s3cret\n")
            (root / ".env.example").write_text("SECRET=\n")
            secrets_tool.cmd_import(args(source=str(source), force=False))
            secrets_tool.cmd_materialize(args(force=False, verbose=False))

            mode = (root / ".env").stat().st_mode & 0o777
            self.assertEqual(secrets_tool.ENV_FILE_MODE, mode)


if __name__ == "__main__":
    unittest.main()
