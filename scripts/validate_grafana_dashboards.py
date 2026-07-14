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

from grafana_dashboard_links import has_roadtrip_tag, has_shared_dashboard_links

SELECTOR_VARIABLE_TYPES = {"custom", "query"}
URL_OVERRIDE_SAFE_SQLSTRING_VARIABLES = {
    "access_type",
    "campground_id",
    "campsite_id",
    "country",
    "loop_name",
    "poi_id",
    "poi_name",
    "poi_type",
    "poller_id",
    "region",
    "run_id",
    "supercharger_id",
    "target_date",
    "window_hours",
}
STATIC_DASHBOARDS_WITH_HIDDEN_TIMEPICKER = {
    "api-sql-equivalence",
    "campground-detail",
    "campsite-stats",
    "catalog-explorer",
    "db-stats",
    "poi-detail",
    "tesla-supercharger-detail",
    "tesla-supercharger-stats",
}


def is_single_quoted_sql_literal(value: str) -> bool:
    return len(value) >= 2 and value.startswith("'") and value.endswith("'")


def validate_dashboard_provisioning(
    repo: Path,
    provisioning_file: Path,
    failures: list[str],
) -> None:
    text = provisioning_file.read_text()
    forbidden_lines = [
        "folder: Roadtrip",
        "folderUid: roadtrip",
    ]
    for forbidden_line in forbidden_lines:
        if forbidden_line in text:
            failures.append(
                f"{provisioning_file.relative_to(repo)}: "
                f"dashboard provider must not set {forbidden_line!r}"
            )


def validate_alert_provisioning(
    repo: Path,
    provisioning_file: Path,
    failures: list[str],
) -> None:
    text = provisioning_file.read_text()
    for line_number, line in enumerate(text.splitlines(), start=1):
        if line.strip().startswith("folder:"):
            failures.append(
                f"{provisioning_file.relative_to(repo)}:{line_number}: "
                "alert rules must not create dashboard-visible Grafana folders"
            )


def validate_url_override_safe_sqlstrings(
    uid: str,
    value_path: str,
    value: object,
    failures: list[str],
) -> None:
    if isinstance(value, dict):
        for key, child_value in value.items():
            validate_url_override_safe_sqlstrings(
                uid,
                f"{value_path}.{key}",
                child_value,
                failures,
            )
        return

    if isinstance(value, list):
        for index, child_value in enumerate(value):
            validate_url_override_safe_sqlstrings(
                uid,
                f"{value_path}[{index}]",
                child_value,
                failures,
            )
        return

    if not isinstance(value, str):
        return

    for variable_name in URL_OVERRIDE_SAFE_SQLSTRING_VARIABLES:
        token = f"${{{variable_name}:sqlstring}}"
        if token not in value:
            continue

        safe_interpolation = f"ARRAY[{token}]::text[]"
        cursor = 0
        while True:
            token_index = value.find(token, cursor)
            if token_index == -1:
                break

            safe_start = token_index - len("ARRAY[")
            safe_end = token_index + len(token) + len("]::text[]")
            if safe_start < 0 or value[safe_start:safe_end] != safe_interpolation:
                failures.append(
                    f"{uid}:{value_path} uses {token} without a blank URL-safe "
                    "ARRAY wrapper"
                )
                break

            cursor = token_index + len(token)


def query_text(variable: dict[str, object]) -> str:
    query = variable.get("query")
    if isinstance(query, dict):
        text = query.get("query") or query.get("rawSql")
        return text if isinstance(text, str) else ""
    return query if isinstance(query, str) else ""


def validate_variable_filter_wiring(
    uid: str,
    dashboard: dict[str, object],
    failures: list[str],
) -> None:
    variables = dashboard.get("templating", {}).get("list", [])
    if not isinstance(variables, list):
        return

    variable_names = {
        variable.get("name")
        for variable in variables
        if isinstance(variable, dict)
    }
    has_name_filter = "poi_name" in variable_names

    for variable in variables:
        if not isinstance(variable, dict):
            continue

        name = variable.get("name", "<unnamed>")
        if variable.get("type") != "query":
            continue

        text = query_text(variable)
        is_name_filtered_poi_selector = has_name_filter and name in {"poi_id", "campsite_id"}
        is_general_poi_selector = name == "poi_id" and variable.get("label") == "Campground / POI"

        if (is_name_filtered_poi_selector or is_general_poi_selector) and "$__searchFilter" in text:
            failures.append(
                f"{uid}:{name} selector must use the poi_name textbox instead of "
                "Grafana server-side dropdown search"
            )

        if is_name_filtered_poi_selector:
            if "${poi_name:sqlstring}" not in text:
                failures.append(f"{uid}:{name} selector must apply the poi_name filter")

        if is_general_poi_selector:
            if "tesla_supercharger" not in text:
                failures.append(f"{uid}:{name} selector must include Tesla superchargers")


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    dashboard_dir = repo / "grafana" / "dashboards"
    failures: list[str] = []

    validate_dashboard_provisioning(
        repo,
        repo / "grafana" / "provisioning" / "dashboards" / "roadtrip.yml",
        failures,
    )
    validate_alert_provisioning(
        repo,
        repo / "grafana" / "provisioning" / "alerting" / "roadtrip.yml",
        failures,
    )

    for path in sorted(dashboard_dir.glob("*.json")):
        dashboard = json.loads(path.read_text())
        uid = dashboard.get("uid", path.name)
        validate_url_override_safe_sqlstrings(uid, path.name, dashboard, failures)
        validate_variable_filter_wiring(uid, dashboard, failures)
        if not has_shared_dashboard_links(dashboard):
            failures.append(f"{uid}: missing shared dashboard links")
        if not has_roadtrip_tag(dashboard):
            failures.append(f"{uid}: missing roadtrip dashboard tag")
        if uid in STATIC_DASHBOARDS_WITH_HIDDEN_TIMEPICKER:
            if dashboard.get("timepicker", {}).get("hidden") is not True:
                failures.append(f"{uid}: static dashboard must hide the time picker")
        for variable in dashboard.get("templating", {}).get("list", []):
            name = variable.get("name", "<unnamed>")
            if variable.get("type") in SELECTOR_VARIABLE_TYPES:
                value = variable.get("current", {}).get("value")
                if value == "" or value == []:
                    failures.append(f"{uid}:{name} selector has empty current value")

            if (
                variable.get("type") == "query"
                and variable.get("datasource", {}).get("type") == "postgres"
            ):
                query = variable.get("query")
                if not isinstance(query, dict) or query.get("format") != "table":
                    failures.append(f"{uid}:{name} Postgres variable must use table format")

            if not variable.get("includeAll"):
                continue

            # The SQL-literal rule only applies to Postgres variables, whose
            # All value is interpolated via :sqlstring. Loki (and other
            # non-SQL) variables interpolate the All value as a raw regex
            # (e.g. `level=~"$level"` with allValue ".+"), where a quoted SQL
            # literal would break matching, so they are out of scope here.
            if variable.get("datasource", {}).get("type") != "postgres":
                continue

            all_value = variable.get("allValue")
            if not isinstance(all_value, str) or not all_value:
                failures.append(f"{uid}:{name} has empty allValue")
                continue

            if not is_single_quoted_sql_literal(all_value):
                failures.append(
                    f"{uid}:{name} allValue must be a SQL literal, got {all_value!r}"
                )

    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1

    print("Grafana dashboard provisioning files are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
