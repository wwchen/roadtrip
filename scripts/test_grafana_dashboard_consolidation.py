#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[1]
DASHBOARD_DIR = REPO / "grafana" / "dashboards"
RETIRED_WATCH_DRILL_PATH = DASHBOARD_DIR / "reservable-availability-watch-drill-down.json"
RETIRED_WATCH_DRILL_URL = "/d/reservable-watch-drill"
RETIRED_INGEST_CATALOG_PATH = DASHBOARD_DIR / "ingest-catalog-freshness.json"
RETIRED_INGEST_CATALOG_URL = "/d/ingest-catalog-freshness"
RETIRED_PROVIDER_CACHE_PATH = DASHBOARD_DIR / "provider-cache-audit.json"
RETIRED_PROVIDER_CACHE_URL = "/d/provider-cache-audit"
RETIRED_AVAILABILITY_MATRIX_PATH = DASHBOARD_DIR / "availability-cell-matrix.json"
RETIRED_AVAILABILITY_MATRIX_URL = "/d/availability-cell-matrix"
RETIRED_CAMPSITE_DETAIL_PATH = DASHBOARD_DIR / "campsite-detail.json"
RETIRED_CAMPSITE_DETAIL_URL = "/d/campsite-detail"


def dashboard(name: str) -> dict[str, Any]:
    return json.loads((DASHBOARD_DIR / name).read_text())


def panels_in(dashboard_doc: dict[str, Any]) -> list[dict[str, Any]]:
    panels: list[dict[str, Any]] = []
    for panel in dashboard_doc.get("panels", []):
        if not isinstance(panel, dict):
            continue
        panels.append(panel)
        panels.extend(panels_in(panel))
    return panels


def panel_titles(dashboard_doc: dict[str, Any]) -> set[str]:
    return {panel.get("title", "") for panel in panels_in(dashboard_doc)}


def target_expressions(panel: dict[str, Any]) -> list[str]:
    return [
        target.get("expr", "")
        for target in panel.get("targets", [])
        if isinstance(target, dict)
    ]


def all_dashboard_text() -> str:
    return "\n".join(path.read_text() for path in sorted(DASHBOARD_DIR.glob("*.json")))


class GrafanaDashboardConsolidationTest(unittest.TestCase):
    def test_watch_drill_dashboard_is_retired(self) -> None:
        self.assertFalse(RETIRED_WATCH_DRILL_PATH.exists())
        self.assertNotIn(RETIRED_WATCH_DRILL_URL, all_dashboard_text())

    def test_unique_watch_drill_panels_moved_to_current_detail_dashboards(self) -> None:
        poller_detail_titles = panel_titles(dashboard("poller-detail.json"))
        poller_run_detail_titles = panel_titles(dashboard("poller-run-detail.json"))

        self.assertIn("Watch backoff state", poller_detail_titles)
        self.assertIn("Effective poll interval over time", poller_detail_titles)
        self.assertIn("Fetch calls for this run", poller_run_detail_titles)

    def test_availability_detail_dashboards_match_current_poller_schema(self) -> None:
        poller_detail_text = (DASHBOARD_DIR / "poller-detail.json").read_text()
        poller_run_detail_text = (DASHBOARD_DIR / "poller-run-detail.json").read_text()

        self.assertNotIn("consecutive_failures", poller_detail_text)
        self.assertIn("current_failure_streak", poller_detail_text)
        self.assertIn("po.cadence_override_sec AS poi_cadence_override_sec", poller_detail_text)
        self.assertNotIn("finished_at", poller_run_detail_text)
        self.assertIn("r.completed_at", poller_run_detail_text)

    def test_ingest_catalog_freshness_dashboard_is_retired(self) -> None:
        status_overview_titles = panel_titles(dashboard("status-overview.json"))

        self.assertFalse(RETIRED_INGEST_CATALOG_PATH.exists())
        self.assertNotIn(RETIRED_INGEST_CATALOG_URL, all_dashboard_text())
        self.assertIn("Failed or stuck ingest phases", status_overview_titles)

    def test_provider_cache_audit_dashboard_is_retired(self) -> None:
        db_stats_titles = panel_titles(dashboard("db-stats.json"))
        catalog_explorer_titles = panel_titles(dashboard("catalog-explorer.json"))

        self.assertFalse(RETIRED_PROVIDER_CACHE_PATH.exists())
        self.assertNotIn(RETIRED_PROVIDER_CACHE_URL, all_dashboard_text())
        self.assertIn("API cache by namespace", db_stats_titles)
        self.assertIn("Availability Provider Cache For Selected Campsite", catalog_explorer_titles)

    def test_availability_matrix_dashboard_is_retired(self) -> None:
        campground_detail_titles = panel_titles(dashboard("campground-detail.json"))

        self.assertFalse(RETIRED_AVAILABILITY_MATRIX_PATH.exists())
        self.assertNotIn(RETIRED_AVAILABILITY_MATRIX_URL, all_dashboard_text())
        self.assertIn("Availability grid (next 30 days)", campground_detail_titles)

    def test_campsite_detail_dashboard_is_retired(self) -> None:
        catalog_titles = panel_titles(dashboard("catalog-explorer.json"))

        self.assertFalse(RETIRED_CAMPSITE_DETAIL_PATH.exists())
        self.assertNotIn(RETIRED_CAMPSITE_DETAIL_URL, all_dashboard_text())
        self.assertIn("Selected Campsite Detail", catalog_titles)

    def test_metrics_http_p95_uses_dashboard_range_without_zero_fallback(self) -> None:
        metrics_panels = {
            panel.get("title", ""): panel
            for panel in panels_in(dashboard("roadtrip-metrics.json"))
        }
        p95_expectations = {
            "Server p95": (
                "increase(http_server_request_duration_seconds_bucket[$__range])",
                "increase(http_server_request_duration_seconds_count[$__range])",
                "and on()",
            ),
            "HTTP server p95 by route": (
                "increase(http_server_request_duration_seconds_bucket[$__range])",
                "increase(http_server_request_duration_seconds_count[$__range])",
                "and on(http_route)",
            ),
            "HTTP client p95 by upstream": (
                "increase(http_client_request_duration_seconds_bucket[$__range])",
                "increase(http_client_request_duration_seconds_count[$__range])",
                "and on(server_address)",
                'label_replace(vector(0), "server_address", "no outbound traffic"',
            ),
        }

        for title, expected_fragments in p95_expectations.items():
            expressions = target_expressions(metrics_panels[title])
            self.assertEqual(1, len(expressions), title)
            expression = expressions[0]
            for expected_fragment in expected_fragments:
                self.assertIn(expected_fragment, expression, title)
            self.assertNotIn("rate(http_", expression, title)
            self.assertNotIn("or on() vector(0)", expression, title)

    def test_metrics_http_client_count_has_labeled_no_traffic_fallback(self) -> None:
        metrics_panels = {
            panel.get("title", ""): panel
            for panel in panels_in(dashboard("roadtrip-metrics.json"))
        }
        expressions = target_expressions(metrics_panels["HTTP client request count"])

        self.assertEqual(1, len(expressions))
        self.assertIn(
            'label_replace(vector(0), "server_address", "no outbound traffic"',
            expressions[0],
        )
        self.assertNotIn("or on() vector(0)", expressions[0])


if __name__ == "__main__":
    unittest.main()
