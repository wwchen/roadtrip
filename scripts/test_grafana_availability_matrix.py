#!/usr/bin/env python3
from __future__ import annotations

import json
import re
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


class GrafanaAvailabilityMatrixTest(unittest.TestCase):
    def availability_grid_panel(self) -> dict[str, Any]:
        return panel_by_title(
            dashboard("poi-reservables.json"),
            "Availability grid (next 30 days)",
        )

    def poi_reservables_dashboard(self) -> dict[str, Any]:
        return dashboard("poi-reservables.json")

    def availability_history_panel(self) -> dict[str, Any]:
        return panel_by_title(
            self.poi_reservables_dashboard(),
            "Availability history for target date",
        )

    def test_poi_reservables_grid_uses_grafana_regexp_extractor(self) -> None:
        panel = self.availability_grid_panel()

        extract_transforms = [
            transform
            for transform in panel.get("transformations", [])
            if transform.get("id") == "extractFields"
        ]
        self.assertEqual(1, len(extract_transforms), panel["title"])
        self.assertEqual(
            "regexp",
            extract_transforms[0]["options"].get("format"),
            panel["title"],
        )

    def test_poi_reservables_grid_uses_compact_status_labels(self) -> None:
        mappings = self.availability_grid_panel()["fieldConfig"]["defaults"]["mappings"]
        status_options = mappings[0]["options"]

        self.assertEqual("A", status_options["available"]["text"])
        self.assertEqual("FC", status_options["first_come"]["text"])
        self.assertEqual("R", status_options["reserved"]["text"])
        self.assertEqual("B", status_options["booked"]["text"])
        self.assertEqual("C", status_options["closed"]["text"])
        self.assertEqual("?", status_options["unknown"]["text"])

    def test_poi_reservables_grid_has_status_key_tooltip(self) -> None:
        description = self.availability_grid_panel().get("description", "")

        self.assertIn("Status key:", description)
        self.assertIn("A = available", description)
        self.assertIn("FC = first come", description)
        self.assertIn("R = reserved", description)
        self.assertIn("B = booked", description)
        self.assertIn("C = closed", description)
        self.assertIn("? = unknown", description)

    def test_poi_reservables_grid_site_column_links_to_reservable_detail(self) -> None:
        overrides = self.availability_grid_panel()["fieldConfig"]["overrides"]
        site_override = next(
            override
            for override in overrides
            if override["matcher"] == {"id": "byName", "options": "site / day"}
        )
        link_property = next(
            prop
            for prop in site_override["properties"]
            if prop["id"] == "links"
        )
        link = link_property["value"][0]

        self.assertEqual("Open Reservable / Detail", link["title"])
        self.assertEqual(
            "/d/reservable-detail/reservable-detail?var-reservable_id=${__data.fields.reservable_id}",
            link["url"],
        )

    def test_poi_reservables_grid_date_columns_link_to_target_date_history(self) -> None:
        panel = self.availability_grid_panel()
        raw_sql = panel["targets"][0]["rawSql"]
        overrides = panel["fieldConfig"]["overrides"]
        date_override = next(
            override
            for override in overrides
            if override["matcher"] == {"id": "byRegexp", "options": "^\\d{4}-\\d{2}-\\d{2}$"}
        )
        link_property = next(
            prop
            for prop in date_override["properties"]
            if prop["id"] == "links"
        )
        link = link_property["value"][0]

        self.assertIn("to_char(l.target_date, 'YYYY-MM-DD')", raw_sql)
        self.assertEqual("Show history for this date", link["title"])
        self.assertEqual(
            "/d/poi-reservables/poi-reservables?var-poi_name=${poi_name}&var-poi_id=${poi_id}&var-target_date=${__field.name}&var-poi_category=${poi_category}",
            link["url"],
        )

    def test_poi_reservables_has_target_date_history_graph(self) -> None:
        dashboard_doc = self.poi_reservables_dashboard()
        target_date_var = next(
            variable
            for variable in dashboard_doc["templating"]["list"]
            if variable["name"] == "target_date"
        )
        panel = self.availability_history_panel()
        raw_sql = panel["targets"][0]["rawSql"]

        self.assertTrue(target_date_var["allowCustomValue"])
        self.assertEqual("Target date", target_date_var["label"])
        self.assertEqual("timeseries", panel["type"])
        self.assertIn("${target_date:sqlstring}", raw_sql)
        self.assertIn("availability s", raw_sql)
        self.assertIn("last_observed_at", raw_sql)
        self.assertIn("'available'", raw_sql)
        self.assertIn("'reserved'", raw_sql)

    def test_availability_history_graph_uses_observation_bounds_not_dashboard_time_filter(self) -> None:
        raw_sql = self.availability_history_panel()["targets"][0]["rawSql"]

        self.assertNotIn("$__timeFilter", raw_sql)
        self.assertIn("observations AS", raw_sql)
        self.assertIn("bounds AS", raw_sql)
        self.assertIn("first_observed_at", raw_sql)
        self.assertIn("last_axis_at", raw_sql)
        self.assertIn("least((target.target_date + interval '1 day')::timestamptz, now())", raw_sql)

    def test_availability_history_graph_uses_css_fixed_colors(self) -> None:
        panel = self.availability_history_panel()
        css_hex = re.compile(r"^#[0-9A-Fa-f]{6}$")

        for override in panel["fieldConfig"]["overrides"]:
            color_property = next(
                prop for prop in override["properties"] if prop["id"] == "color"
            )
            fixed_color = color_property["value"]["fixedColor"]
            self.assertRegex(
                fixed_color,
                css_hex,
                f"{override['matcher']['options']} uses unsupported color {fixed_color}",
            )


if __name__ == "__main__":
    unittest.main()
