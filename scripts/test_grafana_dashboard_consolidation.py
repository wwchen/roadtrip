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
        poi_campsites_titles = panel_titles(dashboard("poi-campsites.json"))

        self.assertFalse(RETIRED_AVAILABILITY_MATRIX_PATH.exists())
        self.assertNotIn(RETIRED_AVAILABILITY_MATRIX_URL, all_dashboard_text())
        self.assertIn("Availability grid (next 30 days)", poi_campsites_titles)


if __name__ == "__main__":
    unittest.main()
