import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).parent))
import fetch_reserveamerica_campsites as frc  # noqa: E402
from _envelope import LoadedSource  # noqa: E402
from fetch_reserveamerica import TENANTS  # noqa: E402

TS = "2026-11-22T16-49-49Z"
NY = next(t for t in TENANTS if t.contract == "NY")
FULL_PAGE = frc.SITE_PAGE_SIZE
SHORT_PAGE = 3


def page(rows: int):
    return 200, {}, {"content-type": "text/html"}, frc.SITE_ROW_MARKER * rows


class FetchTenantTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.prefix = Path(self.tmp.name) / "reserveamerica-campsites-ny"
        self.capture_dir = self.prefix / TS
        self.calls: list[tuple[str, int]] = []
        self.responses: dict[tuple[str, int], list] = {}
        source = LoadedSource(slug="reserveamerica-campsites-ny", name="NY", output_dir_prefix=self.prefix)
        for target, value in [
            ("load_source", mock.Mock(return_value=source)),
            ("make_session", mock.Mock()),
            ("enumerate_park_ids", mock.Mock(return_value=["1", "2"])),
            ("_request", mock.Mock(side_effect=self.request)),
            ("err", mock.Mock()),
        ]:
            patcher = mock.patch.object(frc, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        sleep = mock.patch.object(frc.time, "sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def request(self, _opener, url, *, referer, timeout):
        if "campsiteCalendar.do" not in url:
            return page(0)
        query = parse_qs(urlparse(url).query)
        key = (query["parkId"][0], int(query["startIdx"][0]))
        self.calls.append(key)
        outcome = self.responses.get(key, [page(SHORT_PAGE)]).pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def pages(self) -> list[str]:
        return sorted(p.stem for p in self.capture_dir.glob("*.json"))

    def test_a_page_that_times_out_once_is_retried(self):
        self.responses[("1", 0)] = [TimeoutError("read timed out"), page(SHORT_PAGE)]
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(self.calls, [("1", 0), ("1", 0), ("2", 0)])
        self.assertEqual(self.pages(), ["campsite-1-0", "campsite-2-0"])

    def test_a_page_that_fails_every_attempt_marks_the_capture_incomplete(self):
        self.responses[("1", 0)] = [TimeoutError("read timed out")] * frc.PAGE_ATTEMPTS
        self.assertEqual(frc.fetch_tenant(NY, TS), frc.EXIT_INCOMPLETE)
        self.assertEqual(self.calls.count(("1", 0)), frc.PAGE_ATTEMPTS)
        self.assertEqual(self.pages(), ["campsite-2-0"])

    def test_resuming_reads_pages_on_disk_and_fetches_only_the_rest(self):
        self.capture_dir.mkdir(parents=True)
        (self.capture_dir / "campsite-1-0.json").write_text(frc.SITE_ROW_MARKER * FULL_PAGE)
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(self.calls, [("1", FULL_PAGE), ("2", 0)])
        self.assertEqual(self.pages(), ["campsite-1-0", f"campsite-1-{FULL_PAGE}", "campsite-2-0"])


if __name__ == "__main__":
    unittest.main()
