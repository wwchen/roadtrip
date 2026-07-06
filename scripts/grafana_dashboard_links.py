#!/usr/bin/env python3
from __future__ import annotations

from copy import deepcopy
from typing import Any

ROADTRIP_TAG = "roadtrip"
RETIRED_SHARED_DASHBOARD_NAV_PANEL_ID = 100000

SHARED_DASHBOARD_LINKS: list[dict[str, Any]] = [
    {
        "asDropdown": True,
        "icon": "dashboard",
        "includeVars": False,
        "keepTime": False,
        "tags": [ROADTRIP_TAG],
        "targetBlank": False,
        "title": "All dashboards",
        "tooltip": "",
        "type": "dashboards",
    },
]

SHARED_LINK_TITLES = {link["title"] for link in SHARED_DASHBOARD_LINKS}
RETIRED_SHARED_LINK_TITLES = {
    "All Roadtrip dashboards",
    "Status Overview",
    "Watch drill-down",
}


def shared_dashboard_links() -> list[dict[str, Any]]:
    return deepcopy(SHARED_DASHBOARD_LINKS)


def ensure_roadtrip_tag(dashboard: dict[str, Any]) -> dict[str, Any]:
    tags = dashboard.get("tags")
    if not isinstance(tags, list):
        tags = []
    tags = [tag for tag in tags if isinstance(tag, str)]
    if ROADTRIP_TAG not in tags:
        tags.insert(0, ROADTRIP_TAG)
    dashboard["tags"] = tags
    return dashboard


def is_retired_shared_dashboard_navigation_panel(panel: Any) -> bool:
    return (
        isinstance(panel, dict)
        and panel.get("id") == RETIRED_SHARED_DASHBOARD_NAV_PANEL_ID
    )


def shifted_panel(panel: Any, delta_y: int) -> Any:
    if not isinstance(panel, dict) or delta_y == 0:
        return panel
    panel = deepcopy(panel)
    grid_pos = panel.get("gridPos")
    if isinstance(grid_pos, dict) and isinstance(grid_pos.get("y"), int):
        grid_pos["y"] = max(0, grid_pos["y"] + delta_y)
    return panel


def remove_retired_shared_dashboard_navigation_panel(dashboard: dict[str, Any]) -> dict[str, Any]:
    panels = dashboard.get("panels")
    if not isinstance(panels, list):
        return dashboard

    retired_nav_panel = next(
        (panel for panel in panels if is_retired_shared_dashboard_navigation_panel(panel)),
        None,
    )
    if not isinstance(retired_nav_panel, dict):
        return dashboard

    retired_nav_height = (
        retired_nav_panel.get("gridPos", {}).get("h")
        if isinstance(retired_nav_panel.get("gridPos"), dict)
        else None
    )
    delta_y = -retired_nav_height if isinstance(retired_nav_height, int) else 0

    dashboard["panels"] = [
        shifted_panel(panel, delta_y)
        for panel in panels
        if not is_retired_shared_dashboard_navigation_panel(panel)
    ]
    return dashboard


def apply_shared_dashboard_links(dashboard: dict[str, Any]) -> dict[str, Any]:
    dashboard = ensure_roadtrip_tag(deepcopy(dashboard))
    existing_links = dashboard.get("links")
    if not isinstance(existing_links, list):
        existing_links = []
    custom_links = [
        link
        for link in existing_links
        if not isinstance(link, dict)
        or link.get("title") not in SHARED_LINK_TITLES | RETIRED_SHARED_LINK_TITLES
    ]
    dashboard["links"] = shared_dashboard_links() + deepcopy(custom_links)
    return remove_retired_shared_dashboard_navigation_panel(dashboard)


def has_shared_dashboard_links(dashboard: dict[str, Any]) -> bool:
    return dashboard.get("links", [])[: len(SHARED_DASHBOARD_LINKS)] == SHARED_DASHBOARD_LINKS


def has_roadtrip_tag(dashboard: dict[str, Any]) -> bool:
    tags = dashboard.get("tags")
    return isinstance(tags, list) and ROADTRIP_TAG in tags
