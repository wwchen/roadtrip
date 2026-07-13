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
STATIC_DASHBOARDS_WITH_HIDDEN_TIMEPICKER = {
    "api-sql-equivalence",
    "campground-detail",
    "campsite-detail",
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
    if "folder: roadtrip" in text:
        failures.append(
            f"{provisioning_file.relative_to(repo)}: "
            "alert rules must not create the lowercase roadtrip folder"
        )


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
