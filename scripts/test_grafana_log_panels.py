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
    for panel in panels_in(dashboard_doc):
        if panel.get("title") == title:
            return panel
    raise AssertionError(f"missing panel {title!r}")


def panels_in(dashboard_doc: dict[str, Any]) -> list[dict[str, Any]]:
    panels: list[dict[str, Any]] = []
    for panel in dashboard_doc.get("panels", []):
        if not isinstance(panel, dict):
            continue
        panels.append(panel)
        panels.extend(panels_in(panel))
    return panels


class GrafanaLogPanelTest(unittest.TestCase):
    def test_logs_explorer_logger_links_scope_current_dashboard_safely(self) -> None:
        dashboard_doc = dashboard("logs-explorer.json")
        panel_titles = [
            "Backend logs",
            "Top classes (by log lines)",
            "Warnings & errors only",
        ]

        for title in panel_titles:
            with self.subTest(panel=title):
                panel = panel_by_title(dashboard_doc, title)
                logger_override = next(
                    override
                    for override in panel["fieldConfig"]["overrides"]
                    if override["matcher"].get("options") in {"logger", "loggerName"}
                    and any(prop["id"] == "links" for prop in override["properties"])
                )
                link_url = next(
                    prop["value"][0]["url"]
                    for prop in logger_override["properties"]
                    if prop["id"] == "links"
                )

                self.assertTrue(link_url.startswith("?${__url_time_range}&"))
                self.assertIn("var-container=${container}", link_url)
                self.assertIn("var-level=$__all", link_url)
                self.assertIn("var-logger=^${__data.fields.loggerName}$", link_url)
                self.assertNotIn("/d/logs-explorer/logs-explorer", link_url)
                self.assertNotIn("var-level=${level}", link_url)

    def test_status_overview_backend_logs_use_table_format(self) -> None:
        logs_explorer_panel = panel_by_title(dashboard("logs-explorer.json"), "Backend logs")
        status_panel = panel_by_title(
            dashboard("status-overview.json"),
            "Backend logs (filter with $log_filter — e.g. a run id)",
        )

        self.assertEqual("table", status_panel["type"])
        self.assertEqual(logs_explorer_panel["transformations"], status_panel["transformations"])

        # Fields arrive as Loki structured metadata, so they are extracted from
        # labels rather than parsed out of the line with a `json` stage.
        self.assertEqual(
            {"source": "labels"},
            status_panel["transformations"][0]["options"],
        )
        self.assertEqual(
            "^(Time|level|logger|message|run_id|trace_id)$",
            status_panel["transformations"][1]["options"]["include"]["pattern"],
        )
        self.assertEqual(
            {
                "Time": 0,
                "level": 1,
                "logger": 2,
                "message": 3,
                "run_id": 4,
                "trace_id": 5,
            },
            status_panel["transformations"][2]["options"]["indexByName"],
        )

        expr = status_panel["targets"][0]["expr"]
        self.assertIn('{container=~"roadtrip-backend.*"}', expr)
        # `logger` is the abbreviated class name the table shows; `message` is
        # the raw line. Both are derived in the query, not in a transformation.
        self.assertIn("label_format logger=", expr)
        self.assertIn("message=`{{ __line__ }}`", expr)
        self.assertIn('|~ "$log_filter"', expr)

        logger_override = next(
            override
            for override in status_panel["fieldConfig"]["overrides"]
            if override["matcher"].get("options") == "logger"
        )
        logger_link = next(
            prop["value"][0]
            for prop in logger_override["properties"]
            if prop["id"] == "links"
        )
        self.assertIn("/d/logs-explorer/logs-explorer?", logger_link["url"])


if __name__ == "__main__":
    unittest.main()
