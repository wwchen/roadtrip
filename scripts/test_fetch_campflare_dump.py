import gzip
import importlib.util
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import LoadedSource


def load_fetcher():
    path = Path(__file__).with_name("fetch_campflare_dump.py")
    spec = importlib.util.spec_from_file_location("fetch_campflare_dump", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class FetchCampflareDumpTest(unittest.TestCase):
    def test_gzip_jsonl_records_are_written_as_chunked_redacted_envelopes(self):
        fetcher = load_fetcher()
        with tempfile.TemporaryDirectory() as tmp:
            source = LoadedSource(
                slug="campflare-campgrounds",
                name="Campflare Campgrounds",
                output_dir_prefix=Path(tmp) / "campflare-campgrounds",
                args={},
            )
            compressed = gzip.compress(
                b'{"id":"cg-1","name":"One"}\n{"id":"cg-2","name":"Two"}\n{"id":"cg-3","name":"Three"}\n'
            )

            written = fetcher.write_dump_envelopes(
                source=source,
                kind="campgrounds",
                dump_url="https://api.campflare.com/dumps/test/campgrounds.json.gz",
                compressed_body=compressed,
                response_headers={"content-type": "application/gzip"},
                authorization_header="secret-token",
                chunk_size=2,
                ts="2026-07-08T00-00-00Z",
            )

            self.assertEqual(2, len(written))
            first = json.loads(written[0].read_text())
            second = json.loads(written[1].read_text())
            self.assertEqual("part-000001", first["part"])
            self.assertEqual(["cg-1", "cg-2"], [r["id"] for r in first["payload"]])
            self.assertEqual(["cg-3"], [r["id"] for r in second["payload"]])
            self.assertEqual("<redacted>", first["request"]["headers"]["Authorization"])

    def test_token_loader_prefers_env_without_printing_secret(self):
        fetcher = load_fetcher()
        with tempfile.TemporaryDirectory() as tmp:
            env_file = Path(tmp) / ".env"
            env_file.write_text("CAMPFLARE_API_KEY=from-file\n")
            token = fetcher.resolve_api_key({"CAMPFLARE_API_KEY": "from-env"}, [env_file])
            self.assertEqual("from-env", token)

    def test_default_token_files_are_limited_to_dotenv(self):
        fetcher = load_fetcher()
        self.assertEqual((Path(".env"),), fetcher.DEFAULT_ENV_FILES)

    def test_dump_download_uses_public_url_without_authorization_header(self):
        fetcher = load_fetcher()
        calls = []

        def fake_http_get_bytes(url, headers, timeout):
            calls.append((url, headers, timeout))
            if url.endswith("/dumps/latest"):
                return (
                    200,
                    {"content-type": "application/json"},
                    json.dumps(
                        {
                            "campgrounds": {
                                "url": "https://api.campflare.com/dumps/test/campgrounds.json.gz"
                            }
                        }
                    ).encode("utf-8"),
                )
            return 200, {"content-type": "application/gzip"}, b"gzip-bytes"

        with tempfile.TemporaryDirectory() as tmp:
            source = LoadedSource(
                slug="campflare-campgrounds-export",
                name="Campflare Campgrounds",
                output_dir_prefix=Path(tmp),
                args={},
            )
            with (
                mock.patch.object(fetcher, "http_get_bytes", side_effect=fake_http_get_bytes),
                mock.patch.object(fetcher, "resolve_api_key", return_value="secret-token"),
                mock.patch.object(fetcher, "load_source", return_value=source),
                mock.patch.object(fetcher, "write_dump_envelopes", return_value=[Path(tmp) / "part-000001.json"]),
                mock.patch.object(
                    fetcher.sys,
                    "argv",
                    [
                        "fetch_campflare_dump.py",
                        "--slug",
                        "campflare-campgrounds-export",
                        "--kind",
                        "campgrounds",
                    ],
                ),
            ):
                self.assertEqual(0, fetcher.main())

        self.assertEqual({"Authorization": "secret-token"}, calls[0][1])
        self.assertEqual({}, calls[1][1])


if __name__ == "__main__":
    unittest.main()
