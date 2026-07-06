#!/usr/bin/env python3
"""Snapshot Grafana dashboards from the running container into grafana/dashboards/.

Workflow: iterate in the Grafana UI (allowUiUpdates=true in dev), then run this
to write the current state back to disk before committing. Provisioning is the
source of truth in CI; this script keeps disk in sync with the live DB.

No hardcoded UID list. The script discovers the set of tracked dashboards by
reading every *.json in grafana/dashboards/ and using each file's existing
"uid" field as the export target. To start tracking a new dashboard, save it
in the UI with a stable UID, then either:
  • create grafana/dashboards/<slug>.json with at minimum '{"uid": "<uid>"}',
    then `make grafana-export` to fill it in; or
  • run `./scripts/export_grafana_dashboards.py --uid <uid> --slug <slug>`
    once to seed the file.

After the file exists, future `make grafana-export` runs sync it automatically.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

from grafana_dashboard_links import apply_shared_dashboard_links

REPO = Path(__file__).resolve().parents[1]
DASHBOARD_DIR = REPO / "grafana" / "dashboards"


def http_get(url: str, auth: tuple[str, str] | None) -> dict:
    req = urllib.request.Request(url)
    if auth:
        import base64

        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def normalize(dashboard: dict, canonical_uid: str) -> dict:
    # Strip Grafana-DB-only fields and force the canonical UID + a stable
    # version. Provisioning ignores both, but pinning them keeps re-exports
    # idempotent so `make grafana-export` is a no-op when nothing changed.
    dashboard = dict(dashboard)
    dashboard.pop("id", None)
    dashboard["uid"] = canonical_uid
    dashboard["version"] = 1
    dashboard["editable"] = True
    return apply_shared_dashboard_links(dashboard)


def discover_tracked() -> dict[str, Path]:
    """Return uid -> path for every grafana/dashboards/*.json with a "uid"."""
    tracked: dict[str, Path] = {}
    for path in sorted(DASHBOARD_DIR.glob("*.json")):
        try:
            uid = json.loads(path.read_text()).get("uid")
        except (json.JSONDecodeError, OSError):
            continue
        if isinstance(uid, str) and uid:
            tracked[uid] = path
    return tracked


def render(dashboard: dict) -> str:
    return json.dumps(dashboard, indent=2, ensure_ascii=False, sort_keys=True) + "\n"


def json_equivalent(left: str, right: str) -> bool:
    try:
        return json.loads(left) == json.loads(right)
    except json.JSONDecodeError:
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("GRAFANA_URL", "http://localhost:3000/dash"))
    parser.add_argument("--user", default=os.environ.get("GRAFANA_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("GRAFANA_PASSWORD", "admin"))
    parser.add_argument("--uid", action="append", help="Export only these UIDs (repeatable). Default: every tracked dashboard.")
    parser.add_argument("--slug", help="Required only with a single --uid that isn't yet tracked on disk; seeds a new file.")
    parser.add_argument("--list-remote", action="store_true", help="List dashboards present in the running Grafana and exit.")
    args = parser.parse_args()

    auth = (args.user, args.password) if args.user else None

    if args.list_remote:
        rows = http_get(f"{args.base_url}/api/search?type=dash-db&limit=5000", auth)
        for row in rows:
            print(f"{row['uid']}\t{row['title']}")
        return 0

    tracked = discover_tracked()

    if args.uid:
        targets: list[tuple[str, Path]] = []
        for uid in args.uid:
            path = tracked.get(uid)
            if path is None:
                if not args.slug:
                    print(
                        f"ERROR {uid}: not tracked on disk. Pass --slug <name> to seed grafana/dashboards/<name>.json.",
                        file=sys.stderr,
                    )
                    return 2
                path = DASHBOARD_DIR / f"{args.slug}.json"
            targets.append((uid, path))
    else:
        targets = sorted(tracked.items())

    failures: list[str] = []
    written = 0
    unchanged = 0
    for uid, path in targets:
        try:
            payload = http_get(f"{args.base_url}/api/dashboards/uid/{uid}", auth)
        except urllib.error.HTTPError as exc:
            failures.append(f"{uid}: HTTP {exc.code} (not in Grafana DB? save once in UI).")
            continue
        except urllib.error.URLError as exc:
            failures.append(f"{uid}: {exc}")
            continue

        new_text = render(normalize(payload["dashboard"], uid))
        existing = path.read_text() if path.exists() else None
        if existing == new_text or (existing is not None and json_equivalent(existing, new_text)):
            unchanged += 1
            continue
        path.write_text(new_text)
        written += 1
        title = json.loads(new_text).get("title")
        print(f"wrote {path.relative_to(REPO)}  title={title!r}")

    if unchanged and not written:
        print(f"no changes ({unchanged} dashboard{'s' if unchanged != 1 else ''} already in sync)")
    elif unchanged:
        print(f"{unchanged} dashboard{'s' if unchanged != 1 else ''} already in sync")

    if failures:
        for f in failures:
            print(f"WARN  {f}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
