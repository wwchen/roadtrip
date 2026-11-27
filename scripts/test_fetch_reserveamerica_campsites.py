import json
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
PAGE = frc.SITE_PAGE_SIZE
SMALL_PARK = PAGE + 5
LARGE_PARK = 2 * PAGE + 3


def sites(park: str, count: int) -> list[str]:
    return [f"{park}{n:04d}" for n in range(count)]


def page_html(start_idx: int, total: int, site_ids: list[str]) -> str:
    links = "".join(
        f"<div class='siteListLabel'><a href='/camping/x/r/campsiteDetails.do?contractCode=NY&amp;siteId={s}&amp;parkId=1'>"
        f"<div class='siteListLabel'></div></a></div>"
        for s in site_ids
    )
    return (
        f"<span id='resultfrom_top'>{start_idx + 1}</span>-<span id='resultto_top'>{start_idx + len(site_ids)}</span>"
        f"&nbsp;of&nbsp;<span id='resulttotal_top'>{total}</span>{links}"
    )


def envelope_text(html: str) -> str:
    return json.dumps({"payload": html})


SORT = "sort"


class FakeVendor:
    def __init__(self, rosters: dict[str, list[str]]):
        self.rosters = rosters
        self.current_park: str | None = None
        self.sorted_park: str | None = None
        self.calls: list[tuple[str, int | str]] = []
        self.failures: dict[tuple[str, int], list[Exception]] = {}
        self.overrides: dict[tuple[str, int], str] = {}

    def order(self, park: str) -> list[str]:
        roster = self.rosters[park]
        if self.sorted_park == park:
            return sorted(roster)
        shift = len(self.calls) % len(roster) if roster else 0
        return roster[shift:] + roster[:shift]

    def request(self, _opener, url, *, referer, timeout):
        query = parse_qs(urlparse(url).query)
        if "campsiteSort.do" in url:
            park = query["parkId"][0]
            self.calls.append((park, SORT))
            if self.current_park == park:
                self.sorted_park = park
            roster = self.order(park)
            return 200, {}, {"content-type": "text/html"}, page_html(0, len(roster), roster[:PAGE])
        if "campsiteCalendar.do" not in url:
            return 200, {}, {"content-type": "text/html"}, ""
        park, start_idx = query["parkId"][0], int(query["startIdx"][0])
        self.calls.append((park, start_idx))
        pending = self.failures.get((park, start_idx))
        if pending:
            raise pending.pop(0)
        roster = self.order(park)
        honored = start_idx < len(roster) and (start_idx == 0 or self.current_park == park)
        if self.current_park != park or start_idx == 0:
            self.sorted_park = None
        self.current_park = park
        offset = start_idx if honored else 0
        roster = self.order(park)
        html = self.overrides.get((park, start_idx)) or page_html(offset, len(roster), roster[offset : offset + PAGE])
        return 200, {}, {"content-type": "text/html"}, html


class FetchTenantTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.prefix = Path(self.tmp.name) / "reserveamerica-campsites-ny"
        self.capture_dir = self.prefix / TS
        self.vendor = FakeVendor({"1": sites("1", LARGE_PARK), "2": sites("2", SMALL_PARK)})
        source = LoadedSource(slug="reserveamerica-campsites-ny", name="NY", output_dir_prefix=self.prefix)
        for target, value in [
            ("load_source", mock.Mock(return_value=source)),
            ("make_session", mock.Mock()),
            ("enumerate_park_ids", mock.Mock(side_effect=lambda *_: sorted(self.vendor.rosters))),
            ("_request", mock.Mock(side_effect=self.vendor.request)),
            ("err", mock.Mock()),
        ]:
            patcher = mock.patch.object(frc, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        sleep = mock.patch.object(frc.time, "sleep")
        sleep.start()
        self.addCleanup(sleep.stop)

    def captured_sites(self, park: str) -> set[str]:
        found: set[str] = set()
        for page in self.capture_dir.glob(f"campsite-{park}-*.json"):
            found |= set(frc.SITE_ID_RX.findall(json.loads(page.read_text())["payload"]))
        return found

    def pages(self) -> list[str]:
        return sorted(p.stem for p in self.capture_dir.glob("*.json"))

    def on_disk(self, park: str, start_idx: int, html: str) -> None:
        self.capture_dir.mkdir(parents=True, exist_ok=True)
        (self.capture_dir / f"campsite-{park}-{start_idx}.json").write_text(envelope_text(html))

    def test_paging_stops_at_the_total_the_park_lists(self):
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(
            self.vendor.calls,
            [("1", 0), ("1", SORT), ("1", PAGE), ("1", 2 * PAGE), ("2", 0), ("2", SORT), ("2", PAGE)],
        )
        self.assertEqual(
            self.pages(),
            ["campsite-1-0", f"campsite-1-{PAGE}", f"campsite-1-{2 * PAGE}", "campsite-2-0", f"campsite-2-{PAGE}"],
        )

    def test_a_park_whose_default_order_shifts_between_pages_is_captured_whole(self):
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(self.captured_sites("1"), set(self.vendor.rosters["1"]))
        self.assertEqual(self.captured_sites("2"), set(self.vendor.rosters["2"]))

    def test_a_park_with_no_sites_writes_nothing(self):
        self.vendor.rosters["3"] = []
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertNotIn("campsite-3-0", self.pages())
        self.assertNotIn(("3", SORT), self.vendor.calls)

    def test_a_page_that_times_out_once_is_retried(self):
        self.vendor.failures[("2", 0)] = [TimeoutError("read timed out")]
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(self.vendor.calls.count(("2", 0)), 2)

    def test_a_page_that_fails_every_attempt_marks_the_capture_incomplete(self):
        self.vendor.failures[("1", 0)] = [TimeoutError("read timed out")] * frc.PAGE_ATTEMPTS
        self.assertEqual(frc.fetch_tenant(NY, TS), frc.EXIT_INCOMPLETE)
        self.assertEqual(self.vendor.calls.count(("1", 0)), frc.PAGE_ATTEMPTS)
        self.assertEqual(self.pages(), ["campsite-2-0", f"campsite-2-{PAGE}"])

    def test_resuming_mid_park_reloads_the_first_page_before_fetching_the_rest(self):
        roster = self.vendor.rosters["2"]
        self.on_disk("2", 0, page_html(0, SMALL_PARK, roster[:PAGE]))
        self.vendor.rosters = {"2": roster}
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        self.assertEqual(self.vendor.calls, [("2", PAGE), ("2", 0), ("2", SORT), ("2", PAGE)])
        self.assertEqual(self.pages(), ["campsite-2-0", f"campsite-2-{PAGE}"])

    def test_a_page_on_disk_that_restarted_at_result_one_is_refetched(self):
        roster = self.vendor.rosters["2"]
        self.on_disk("2", 0, page_html(0, SMALL_PARK, roster[:PAGE]))
        self.on_disk("2", PAGE, page_html(0, SMALL_PARK, roster[:PAGE]))
        self.vendor.rosters = {"2": roster}
        self.assertEqual(frc.fetch_tenant(NY, TS), 0)
        written = json.loads((self.capture_dir / f"campsite-2-{PAGE}.json").read_text())["payload"]
        self.assertIn(f"id='resultfrom_top'>{PAGE + 1}<", written)

    def test_a_vendor_that_never_honors_the_offset_marks_the_park_incomplete(self):
        roster = self.vendor.rosters["2"]
        self.vendor.overrides[("2", PAGE)] = page_html(0, SMALL_PARK, roster[:PAGE])
        self.assertEqual(frc.fetch_tenant(NY, TS), frc.EXIT_INCOMPLETE)
        self.assertNotIn(f"campsite-2-{PAGE}", self.pages())

    def test_pages_that_miss_sites_mark_the_park_incomplete(self):
        roster = self.vendor.rosters["2"]
        self.vendor.overrides[("2", PAGE)] = page_html(PAGE, SMALL_PARK, roster[:SMALL_PARK - PAGE])
        self.assertEqual(frc.fetch_tenant(NY, TS), frc.EXIT_INCOMPLETE)


if __name__ == "__main__":
    unittest.main()
