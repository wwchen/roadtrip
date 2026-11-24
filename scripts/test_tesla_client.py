import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import tesla_client  # noqa: E402


class LoadEnvTest(unittest.TestCase):
    def load(self, text: str) -> dict[str, str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / ".env.local"
            path.write_text(text)
            with mock.patch.object(tesla_client, "ENV_LOCAL_PATH", path), \
                    mock.patch.dict(os.environ, {}, clear=True):
                tesla_client.load_env()
                return dict(os.environ)

    def test_single_quotes_are_stripped(self):
        self.assertEqual(self.load("TESLA_COOKIES='a=1; b=2'\n"), {"TESLA_COOKIES": "a=1; b=2"})

    def test_double_quotes_are_stripped(self):
        self.assertEqual(self.load('TESLA_CURL="curl_chrome152_macos"\n'), {"TESLA_CURL": "curl_chrome152_macos"})

    def test_unquoted_values_keep_inner_equals(self):
        self.assertEqual(self.load("TESLA_COOKIES=a=1; b=2==\n"), {"TESLA_COOKIES": "a=1; b=2=="})

    def test_mismatched_or_lone_quotes_are_kept(self):
        self.assertEqual(self.load("A='x\"\nB='\n"), {"A": "\'x\"", "B": "\'"})

    def test_comments_and_blank_lines_are_skipped(self):
        self.assertEqual(self.load("# TESLA_COOKIES='x'\n\nTESLA_CURL=c\n"), {"TESLA_CURL": "c"})

    def test_an_existing_env_value_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / ".env.local"
            path.write_text("TESLA_CURL='from_file'\n")
            with mock.patch.object(tesla_client, "ENV_LOCAL_PATH", path), \
                    mock.patch.dict(os.environ, {"TESLA_CURL": "from_env"}, clear=True):
                tesla_client.load_env()
                self.assertEqual(os.environ["TESLA_CURL"], "from_env")


if __name__ == "__main__":
    unittest.main()
