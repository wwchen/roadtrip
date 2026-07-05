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

    for path in sorted(dashboard_dir.glob("*.json")):
        dashboard = json.loads(path.read_text())
        uid = dashboard.get("uid", path.name)
        for variable in dashboard.get("templating", {}).get("list", []):
            if not variable.get("includeAll"):
                continue

            # The SQL-literal rule only applies to Postgres variables, whose
            # All value is interpolated via :sqlstring. Loki (and other
            # non-SQL) variables interpolate the All value as a raw regex
            # (e.g. `level=~"$level"` with allValue ".+"), where a quoted SQL
            # literal would break matching, so they are out of scope here.
            if variable.get("datasource", {}).get("type") != "postgres":
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

    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1

    print("Grafana dashboard allValue settings are SQL-safe.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
