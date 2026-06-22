#!/usr/bin/env python3
"""Validate Grafana dashboard provisioning files.

Grafana inserts custom allValue text without datasource escaping. For
Postgres variables used with :sqlstring, the custom All value must already be
a SQL literal, such as "'__all'"; otherwise panels can render SQL like
__all IN (...) and fail at runtime.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def is_single_quoted_sql_literal(value: str) -> bool:
    return len(value) >= 2 and value.startswith("'") and value.endswith("'")


def main() -> int:
    dashboard_dir = Path(__file__).resolve().parents[1] / "grafana" / "dashboards"
    failures: list[str] = []

    dashboards: dict[str, dict] = {}
    for path in sorted(dashboard_dir.glob("*.json")):
        dashboard = json.loads(path.read_text())
        uid = dashboard.get("uid", path.name)
        dashboards[uid] = dashboard
        panel_ids: dict[int, str] = {}
        for panel in dashboard.get("panels", []):
            panel_id = panel.get("id")
            if panel_id is None:
                continue
            if panel_id in panel_ids:
                failures.append(
                    f"{uid} has duplicate panel id {panel_id}: "
                    f"{panel_ids[panel_id]!r} and {panel.get('title')!r}"
                )
            else:
                panel_ids[panel_id] = panel.get("title", "<untitled>")
        for variable in dashboard.get("templating", {}).get("list", []):
            if not variable.get("includeAll"):
                continue

            name = variable.get("name", "<unnamed>")
            all_value = variable.get("allValue")
            if not isinstance(all_value, str) or not all_value:
                failures.append(f"{uid}:{name} has empty allValue")
                continue

            if not is_single_quoted_sql_literal(all_value):
                failures.append(
                    f"{uid}:{name} allValue must be a SQL literal, got {all_value!r}"
                )

    expected_titles = {
        "roadtrip-reservable-detail": "Reservable / Detail",
        "roadtrip-reservable-stats": "Reservable / Stats",
    }
    for uid, expected_title in expected_titles.items():
        actual_title = dashboards.get(uid, {}).get("title")
        if actual_title != expected_title:
            failures.append(
                f"{uid} title must be {expected_title!r}, got {actual_title!r}"
            )

    fetches = dashboards.get("roadtrip-reservable-availability-fetches")
    if fetches is None:
        failures.append("missing roadtrip-reservable-availability-fetches dashboard")
    else:
        if fetches.get("title") != "Reservable / Availability Fetches":
            failures.append(
                "roadtrip-reservable-availability-fetches title must be "
                "'Reservable / Availability Fetches'"
            )
        expected_panels = {
            "Fetch Summary",
            "Runs By Status",
            "Duration And Snapshot Throughput",
            "Recent Fetch Runs",
            "Snapshot Count Audit",
            "Per-Reservable Fetch Freshness",
            "Recent Snapshot Rows",
            "Availability Provider Cache Freshness",
        }
        panel_titles = {
            panel.get("title") for panel in fetches.get("panels", []) if panel.get("title")
        }
        for panel_title in sorted(expected_panels - panel_titles):
            failures.append(
                "roadtrip-reservable-availability-fetches is missing "
                f"{panel_title!r}"
            )
        variables = {
            variable.get("name"): variable
            for variable in fetches.get("templating", {}).get("list", [])
        }
        for status_variable in ("run_status", "job_status"):
            variable = variables.get(status_variable)
            if variable is None:
                failures.append(
                    "roadtrip-reservable-availability-fetches is missing "
                    f"{status_variable!r} variable"
                )
                continue
            if variable.get("current", {}).get("value") != "all":
                failures.append(
                    "roadtrip-reservable-availability-fetches "
                    f"{status_variable} must default to explicit 'all'"
                )
            if "all" not in str(variable.get("query", "")).split(","):
                failures.append(
                    "roadtrip-reservable-availability-fetches "
                    f"{status_variable} custom options must include 'all'"
                )
        raw_sql = "\n".join(
            target.get("rawSql", "")
            for panel in fetches.get("panels", [])
            for target in panel.get("targets", [])
        )
        required_fragments = [
            "availability_job_run",
            "availability_job",
            "availability_watch",
            "availability_snapshot",
            "grafana_api_cache_metadata",
            "Reservable / Detail",
            "/d/roadtrip-reservable-detail/roadtrip-reservable-detail",
        ]
        dashboard_text = json.dumps(fetches)
        for fragment in required_fragments:
            haystack = raw_sql if fragment not in ("Reservable / Detail", "/d/roadtrip-reservable-detail/roadtrip-reservable-detail") else dashboard_text
            if fragment not in haystack:
                failures.append(
                    "roadtrip-reservable-availability-fetches missing "
                    f"{fragment!r}"
                )

    tesla_stats = dashboards["roadtrip-tesla-supercharger-stats"]
    price_map = next(
        (
            panel
            for panel in tesla_stats.get("panels", [])
            if panel.get("title") == "Average Charging Price By Region"
        ),
        None,
    )
    if price_map is None:
        failures.append(
            "roadtrip-tesla-supercharger-stats is missing "
            "Average Charging Price By Region"
        )
    else:
        if price_map.get("type") != "geomap":
            failures.append("Average Charging Price By Region must be a geomap panel")
        raw_sql = "\n".join(
            target.get("rawSql", "") for target in price_map.get("targets", [])
        )
        required_fragments = [
            "pricebook->>'feeType' = 'CHARGING'",
            "pricebook->>'uom' = 'kwh'",
            "pricebook->>'vehicleMakeType' = 'TSLA'",
            "avg(site_avg_price_per_kwh)",
            "avg_price_per_kwh",
            "priced_superchargers",
            "lng",
            "lat",
        ]
        for fragment in required_fragments:
            if fragment not in raw_sql:
                failures.append(
                    "Average Charging Price By Region SQL missing "
                    f"{fragment!r}"
                )

    tesla_detail = dashboards["roadtrip-tesla-supercharger-detail"]
    tesla_detail_panels = {
        panel.get("title"): panel
        for panel in tesla_detail.get("panels", [])
        if panel.get("title")
    }
    supercharger_details = tesla_detail_panels.get("Supercharger Details")
    if supercharger_details is None:
        failures.append(
            "roadtrip-tesla-supercharger-detail is missing "
            "'Supercharger Details'"
        )
    else:
        if supercharger_details.get("type") != "table":
            failures.append("Supercharger Details must be a table panel")
        raw_sql = "\n".join(
            target.get("rawSql", "")
            for target in supercharger_details.get("targets", [])
        )
        required_fragments = [
            "'Name'",
            "'POI ID'",
            "'Address'",
            "'Currency'",
            "'Catalog Age'",
            "'Updated At'",
            "'Tesla URL'",
        ]
        for fragment in required_fragments:
            if fragment not in raw_sql:
                failures.append(
                    "Supercharger Details SQL missing "
                    f"{fragment!r}"
                )

    pricebook_by_hour = tesla_detail_panels.get("Pricebook By Hour")
    if pricebook_by_hour is None:
        failures.append(
            "roadtrip-tesla-supercharger-detail is missing "
            "'Pricebook By Hour'"
        )
    else:
        if pricebook_by_hour.get("type") != "barchart":
            failures.append("Pricebook By Hour must be a barchart panel")
        raw_sql = "\n".join(
            target.get("rawSql", "")
            for target in pricebook_by_hour.get("targets", [])
        )
        required_fragments = [
            "generate_series(0, 23)",
            "hour_label",
            "pricebook->>'feeType' = 'CHARGING'",
            "pricebook->>'uom' = 'kwh'",
            "pricebook->>'startTime'",
            "pricebook->>'endTime'",
            "tsla_price",
            "non_tesla_price",
            "row_number() OVER",
        ]
        for fragment in required_fragments:
            if fragment not in raw_sql:
                failures.append(
                    "Pricebook By Hour SQL missing "
                    f"{fragment!r}"
                )

    raw_json_panel = tesla_detail_panels.get("Raw Tesla JSON")
    if raw_json_panel is None:
        failures.append(
            "roadtrip-tesla-supercharger-detail is missing 'Raw Tesla JSON'"
        )
    else:
        has_json_view = any(
            property_config.get("id") == "custom.cellOptions"
            and property_config.get("value", {}).get("type") == "json-view"
            for override in raw_json_panel.get("fieldConfig", {}).get("overrides", [])
            for property_config in override.get("properties", [])
        )
        if not has_json_view:
            failures.append("Raw Tesla JSON must use JSON View cell overrides")

    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1

    print("Grafana dashboard allValue settings are SQL-safe.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
