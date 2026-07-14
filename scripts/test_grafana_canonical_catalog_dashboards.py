#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DASHBOARD_DIR = ROOT / "grafana" / "dashboards"

CATALOG_DASHBOARDS = [
    "campground-detail.json",
    "catalog-explorer.json",
    "poi-detail.json",
    "campsite-stats.json",
    "tesla-supercharger-detail.json",
    "tesla-supercharger-stats.json",
    "api-sql-equivalence.json",
]

BANNED_SQL_PATTERNS = [
    re.compile(r"\bFROM\s+reservables\b", re.IGNORECASE),
    re.compile(r"\bJOIN\s+reservables\b", re.IGNORECASE),
    re.compile(r"\breservable_pois\b", re.IGNORECASE),
    re.compile(r"\breservable_id\b", re.IGNORECASE),
    re.compile(r"\bp\.category\b", re.IGNORECASE),
    re.compile(r"\bp\.source_id\b", re.IGNORECASE),
    re.compile(r"\bp\.properties\b", re.IGNORECASE),
]

BANNED_LINKS = [
    "/d/campsite-detail/",
    "/d/reservable-detail/",
    "var-reservable_id",
    "var-reservable_rid",
]

REQUIRED_DB_STATS_TOKENS = [
    "campgrounds",
    "campsites",
    "vendor_refs",
    "campground_vendor_refs",
    "campsite_vendor_refs",
    "tesla_superchargers",
    "planet_fitness_locations",
]


def load_dashboard(name: str) -> dict[str, Any]:
    path = DASHBOARD_DIR / name
    return json.loads(path.read_text())


def walk(value: Any):
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)
    else:
        yield value


def raw_sql_strings(dashboard: dict[str, Any]) -> list[str]:
    strings: list[str] = []
    for value in walk(dashboard):
        if isinstance(value, str) and ("SELECT" in value.upper() or "WITH " in value.upper()):
            strings.append(value)
    return strings


class GrafanaCanonicalCatalogDashboardTest(unittest.TestCase):
    def test_catalog_dashboards_do_not_query_old_catalog_tables(self) -> None:
        offenders: list[str] = []
        for name in CATALOG_DASHBOARDS:
            dashboard = load_dashboard(name)
            for sql in raw_sql_strings(dashboard):
                for pattern in BANNED_SQL_PATTERNS:
                    if pattern.search(sql):
                        offenders.append(f"{name}: {pattern.pattern}")
        self.assertEqual([], offenders)

    def test_dashboard_links_no_longer_target_old_reservable_views(self) -> None:
        offenders: list[str] = []
        for path in DASHBOARD_DIR.glob("*.json"):
            text = path.read_text()
            for token in BANNED_LINKS:
                if token in text:
                    offenders.append(f"{path.name}: {token}")
        self.assertEqual([], offenders)

    def test_db_stats_tracks_canonical_catalog_tables(self) -> None:
        text = (DASHBOARD_DIR / "db-stats.json").read_text()
        missing = [token for token in REQUIRED_DB_STATS_TOKENS if token not in text]
        self.assertEqual([], missing)

    def test_db_stats_surfaces_live_query_state(self) -> None:
        dashboard = load_dashboard("db-stats.json")
        text = (DASHBOARD_DIR / "db-stats.json").read_text()
        required = [
            "pg_stat_activity",
            "pg_blocking_pids",
            "query_age",
            "wait_event_type",
            "Live database sessions",
            "Blocked queries",
            "(state IS NULL) ASC",
        ]
        missing = [token for token in required if token not in text]
        self.assertEqual([], missing)
        self.assertEqual("5s", dashboard.get("refresh"))
        self.assertIn("5s", dashboard.get("timepicker", {}).get("refresh_intervals", []))

    def test_api_cache_dashboards_match_metadata_view(self) -> None:
        offenders: list[str] = []
        for name in ["db-stats.json", "catalog-explorer.json"]:
            dashboard = load_dashboard(name)
            for sql in raw_sql_strings(dashboard):
                if "grafana_api_cache_metadata" not in sql:
                    continue
                for stale_column in ["fetched_at", " error"]:
                    if stale_column in sql:
                        offenders.append(f"{name}: {stale_column.strip()}")
        self.assertEqual([], offenders)

    def test_canonical_campsite_dashboards_exist(self) -> None:
        missing = [name for name in CATALOG_DASHBOARDS if not (DASHBOARD_DIR / name).exists()]
        self.assertEqual([], missing)

    def test_tesla_detail_dashboard_keeps_hourly_charging_rate_graph(self) -> None:
        dashboard = load_dashboard("tesla-supercharger-detail.json")
        matching = [
            panel
            for panel in dashboard["panels"]
            if panel.get("title") == "Charging rates by hour of day"
        ]
        self.assertEqual(1, len(matching))
        self.assertEqual("barchart", matching[0].get("type"))


if __name__ == "__main__":
    unittest.main()
