#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[1]
DASHBOARD_DIR = REPO / "grafana" / "dashboards"


def dashboard(name: str) -> dict[str, Any]:
    return json.loads((DASHBOARD_DIR / name).read_text())


def panel_by_title(dashboard_doc: dict[str, Any], title: str) -> dict[str, Any]:
    for panel in dashboard_doc.get("panels", []):
        if panel.get("title") == title:
            return panel
    raise AssertionError(f"panel not found: {title}")


class GrafanaReservableDetailTest(unittest.TestCase):
    def reservable_detail_dashboard(self) -> dict[str, Any]:
        return dashboard("reservable-detail.json")

    def test_latest_availability_by_target_date_plots_status_values(self) -> None:
        panel = panel_by_title(
            self.reservable_detail_dashboard(),
            "Latest availability by target date",
        )
        raw_sql = panel["targets"][0]["rawSql"]

        self.assertEqual("timeseries", panel["type"])
        self.assertNotIn("$__timeFilter", raw_sql)
        self.assertNotIn("transformations", panel)
        self.assertIn("s.target_date::text AS metric", raw_sql)
        self.assertIn("WHEN 'available' THEN 4", raw_sql)
        self.assertIn("WHEN 'reserved' THEN 2", raw_sql)
        self.assertIn("END::double precision AS value", raw_sql)
        self.assertEqual(
            "status",
            panel["fieldConfig"]["defaults"]["custom"]["axisLabel"],
        )

    def test_recent_availability_snapshots_do_not_use_hidden_time_filter(self) -> None:
        panel = panel_by_title(
            self.reservable_detail_dashboard(),
            "Recent availability snapshots",
        )
        raw_sql = panel["targets"][0]["rawSql"]

        self.assertNotIn("$__timeFilter", raw_sql)
        self.assertIn("ORDER BY s.last_observed_at DESC", raw_sql)
        self.assertIn("LIMIT 500", raw_sql)


if __name__ == "__main__":
    unittest.main()
