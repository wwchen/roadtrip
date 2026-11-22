import datetime as dt
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import fetch_tesla_locations as ftl  # noqa: E402
from _envelope import CAPTURE_TS_FORMAT, utc_ts  # noqa: E402

FRESHNESS_DAYS = 30


class IsFreshTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patcher = mock.patch.object(ftl, "LOCATIONS_DIR", Path(self.tmp.name))
        patcher.start()
        self.addCleanup(patcher.stop)

    def capture(self, slug: str, name: str) -> Path:
        path = Path(self.tmp.name) / slug / f"{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{}")
        return path

    def test_an_old_capture_is_stale_even_when_checkout_touched_its_mtime(self):
        path = self.capture("old", "2026-06-09T04-35-32Z")
        now = time.time()
        os.utime(path, (now, now))
        self.assertFalse(ftl.is_fresh("old", FRESHNESS_DAYS))

    def test_a_capture_named_now_is_fresh(self):
        self.capture("new", utc_ts())
        self.assertTrue(ftl.is_fresh("new", FRESHNESS_DAYS))

    def test_a_slug_with_no_capture_is_not_fresh(self):
        self.assertFalse(ftl.is_fresh("missing", FRESHNESS_DAYS))

    def test_the_newest_name_decides(self):
        self.capture("both", "2026-06-09T04-35-32Z")
        newest = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=1)).strftime(CAPTURE_TS_FORMAT)
        self.capture("both", newest)
        self.assertTrue(ftl.is_fresh("both", FRESHNESS_DAYS))



class BlockedBailoutTest(unittest.TestCase):
    def run_main(self, statuses: list[int]) -> tuple[int, int]:
        slugs = [f"slug{i}" for i in range(len(statuses))]
        responses = iter((status, {"data": {}} if status == 200 else "blocked") for status in statuses)
        with mock.patch.object(ftl.tesla_client, "load_env"), \
                mock.patch.object(ftl, "newest_index_capture", return_value=ftl.ROOT / "index.json"), \
                mock.patch.object(ftl, "na_supercharger_slugs", return_value=slugs), \
                mock.patch.object(ftl, "is_fresh", return_value=False), \
                mock.patch.object(ftl, "fetch_slug", side_effect=lambda _: next(responses)) as fetch, \
                mock.patch.object(ftl, "write_envelope"), \
                mock.patch.object(ftl.time, "sleep"), \
                mock.patch.object(ftl, "err"), \
                mock.patch.object(sys, "argv", ["fetch_tesla_locations.py"]):
            return ftl.main(), fetch.call_count

    def test_consecutive_403s_bail_like_429s(self):
        self.assertEqual(self.run_main([403, 403, 403, 200]), (ftl.EXIT_RATE_LIMITED, 3))

    def test_mixed_403_and_429_count_together(self):
        self.assertEqual(self.run_main([403, 429, 403, 200]), (ftl.EXIT_RATE_LIMITED, 3))

    def test_a_success_resets_the_count(self):
        self.assertEqual(self.run_main([403, 403, 200, 403, 403, 200]), (0, 6))

    def test_other_failures_do_not_bail(self):
        self.assertEqual(self.run_main([404, 504, 404, 504]), (0, 4))

if __name__ == "__main__":
    unittest.main()
