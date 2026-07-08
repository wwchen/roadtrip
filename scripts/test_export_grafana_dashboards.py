#!/usr/bin/env python3
from __future__ import annotations

import unittest

from export_grafana_dashboards import json_equivalent, normalize, render
from grafana_dashboard_links import (
    RETIRED_SHARED_DASHBOARD_NAV_PANEL_ID,
    SHARED_DASHBOARD_LINKS,
    has_shared_dashboard_links,
)


class GrafanaDashboardExportTest(unittest.TestCase):
    def test_render_is_stable_when_object_key_order_changes(self) -> None:
        first = {
            "title": "Availability",
            "uid": "from-grafana",
            "id": 7,
            "panels": [
                {
                    "title": "Runs",
                    "type": "table",
                    "datasource": {"uid": "roadtrip-postgres", "type": "postgres"},
                }
            ],
            "templating": {
                "list": [
                    {
                        "name": "run_id",
                        "type": "query",
                        "datasource": {"uid": "roadtrip-postgres", "type": "postgres"},
                    }
                ]
            },
        }
        second = {
            "templating": {
                "list": [
                    {
                        "datasource": {"type": "postgres", "uid": "roadtrip-postgres"},
                        "type": "query",
                        "name": "run_id",
                    }
                ]
            },
            "panels": [
                {
                    "datasource": {"type": "postgres", "uid": "roadtrip-postgres"},
                    "type": "table",
                    "title": "Runs",
                }
            ],
            "id": 99,
            "uid": "other-grafana",
            "title": "Availability",
        }

        self.assertEqual(
            render(normalize(first, "availability")),
            render(normalize(second, "availability")),
        )

    def test_json_equivalent_ignores_object_order_only(self) -> None:
        first = '{"panel":{"title":"Runs","datasource":{"uid":"pg","type":"postgres"}}}'
        second = '{"panel":{"datasource":{"type":"postgres","uid":"pg"},"title":"Runs"}}'
        changed = '{"panel":{"datasource":{"type":"postgres","uid":"loki"},"title":"Runs"}}'

        self.assertTrue(json_equivalent(first, second))
        self.assertFalse(json_equivalent(first, changed))

    def test_normalize_adds_shared_dashboard_links_before_custom_links(self) -> None:
        custom_link = {
            "asDropdown": False,
            "icon": "external link",
            "includeVars": False,
            "keepTime": False,
            "tags": [],
            "targetBlank": False,
            "title": "POI Detail",
            "tooltip": "",
            "type": "link",
            "url": "/d/poi-detail/poi-detail",
        }
        dashboard = {
            "id": 7,
            "uid": "catalog-explorer",
            "title": "Catalog",
            "links": [custom_link],
            "tags": ["catalog"],
        }

        normalized = normalize(dashboard, "catalog-explorer")

        self.assertEqual(2, len(SHARED_DASHBOARD_LINKS))
        self.assertEqual("🚦 Status Overview", SHARED_DASHBOARD_LINKS[0]["title"])
        self.assertEqual("/d/status-overview/roadtrip-status-overview", SHARED_DASHBOARD_LINKS[0]["url"])
        self.assertEqual(SHARED_DASHBOARD_LINKS, normalized["links"][: len(SHARED_DASHBOARD_LINKS)])
        self.assertEqual([custom_link], normalized["links"][len(SHARED_DASHBOARD_LINKS) :])
        self.assertIn("roadtrip", normalized["tags"])
        self.assertIn("catalog", normalized["tags"])
        self.assertTrue(has_shared_dashboard_links(normalized))

    def test_normalize_removes_retired_body_navigation_row(self) -> None:
        dashboard = {
            "uid": "catalog-explorer",
            "title": "Catalog",
            "panels": [
                {
                    "id": RETIRED_SHARED_DASHBOARD_NAV_PANEL_ID,
                    "title": "",
                    "type": "text",
                    "gridPos": {"h": 2, "w": 24, "x": 0, "y": 0},
                },
                {
                    "id": 7,
                    "title": "Catalog table",
                    "type": "table",
                    "gridPos": {"h": 8, "w": 24, "x": 0, "y": 2},
                },
            ],
        }

        normalized = normalize(dashboard, "catalog-explorer")

        self.assertEqual(7, normalized["panels"][0]["id"])
        self.assertEqual(0, normalized["panels"][0]["gridPos"]["y"])

    def test_normalize_removes_retired_shared_links_without_duplicates(self) -> None:
        stale_status_link = {
            "title": "Status Overview",
            "type": "link",
            "url": "/old-status",
        }
        stale_emoji_status_link = {
            "title": "🚦 Status Overview",
            "type": "link",
            "url": "/old-emoji-status",
        }
        stale_watch_link = {
            "title": "Watch drill-down",
            "type": "link",
            "url": "/old-watch",
        }
        stale_dropdown = {
            "title": "All Roadtrip dashboards",
            "type": "dashboards",
            "tags": ["roadtrip"],
            "asDropdown": True,
        }
        stale_all_dashboards = {
            "title": "All dashboards",
            "type": "dashboards",
            "tags": ["roadtrip"],
            "asDropdown": True,
        }
        dashboard = {
            "uid": "logs-explorer",
            "title": "Logs",
            "links": [
                stale_status_link,
                stale_emoji_status_link,
                stale_watch_link,
                stale_dropdown,
                stale_all_dashboards,
            ],
        }

        normalized = normalize(dashboard, "logs-explorer")

        self.assertEqual(SHARED_DASHBOARD_LINKS, normalized["links"])

    def test_all_dashboards_link_is_tag_driven_not_curated(self) -> None:
        dropdown = SHARED_DASHBOARD_LINKS[1]

        self.assertEqual("All dashboards", dropdown["title"])
        self.assertEqual("dashboards", dropdown["type"])
        self.assertEqual(["roadtrip"], dropdown["tags"])
        self.assertTrue(dropdown["asDropdown"])
        self.assertNotIn("url", dropdown)


if __name__ == "__main__":
    unittest.main()
