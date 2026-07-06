#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

from grafana_dashboard_links import apply_shared_dashboard_links

REPO = Path(__file__).resolve().parents[1]
DASHBOARD_DIR = REPO / "grafana" / "dashboards"


def render(dashboard: dict) -> str:
    return json.dumps(dashboard, indent=2, ensure_ascii=False, sort_keys=True) + "\n"


def main() -> int:
    changed = 0
    for path in sorted(DASHBOARD_DIR.glob("*.json")):
        dashboard = json.loads(path.read_text())
        updated = apply_shared_dashboard_links(dashboard)
        new_text = render(updated)
        if path.read_text() == new_text:
            continue
        path.write_text(new_text)
        changed += 1
        print(f"updated {path.relative_to(REPO)}")

    if changed == 0:
        print("all Grafana dashboards already have shared links")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
