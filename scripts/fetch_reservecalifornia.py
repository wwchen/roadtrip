#!/usr/bin/env python3
"""Capture ReserveCalifornia place, facility, and site catalog payloads.

ReserveCalifornia exposes public search/detail APIs. This fetcher uses the
same "search all parks" /search/place request shape as the public SPA, then
captures per-place details and standard facility grids:

  data/raw/reservecalifornia-catalog/<ts>/citypark-<query>.json
  data/raw/reservecalifornia-catalog/<ts>/website-settings.json
  data/raw/reservecalifornia-catalog/<ts>/search-all.json
  data/raw/reservecalifornia-catalog/<ts>/place-<PlaceId>.json
  data/raw/reservecalifornia-catalog/<ts>/facility-<FacilityId>.json
  data/raw/reservecalifornia-catalog/<ts>/grid-<FacilityId>.json

The grid call is used as the site catalog source because Units are present
there. Request-time availability still goes through the Kotlin provider.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    LoadedSource,
    err,
    http_get_text,
    http_post_text,
    load_source,
    parse_payload,
    utc_ts,
    write_envelope,
)

FETCHER = "fetch_reservecalifornia"
FETCHER_VERSION = "1"

RDR_BASE = "https://california-rdr.prod.cali.rd12.recreation-management.tylerapp.com/rdr"
RD_BASE = "https://rdapi.reservecalifornia.com/api"
DEFAULT_SEARCH_ALL_PLACE_ID = 691
DEFAULT_SEARCH_LATITUDE = 34.2570034764866
DEFAULT_SEARCH_LONGITUDE = -114.162234470524
DEFAULT_SEARCH_RADIUS = 100

COMMON_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json",
}
RDR_HEADERS = {**COMMON_HEADERS, "tenantId": "cali", "Content-Type": "application/json"}
RD_HEADERS = {
    **COMMON_HEADERS,
    "InstallationsIdentity": "cali",
    "StoreId": "111",
    "Content-Type": "application/json",
}


@dataclass(frozen=True)
class Endpoints:
    rdr_base: str = RDR_BASE
    rd_base: str = RD_BASE


def _json_or_none(content_type: str, body: str):
    payload = parse_payload(content_type, body)
    return payload if isinstance(payload, (dict, list)) else None


def _get_json(url: str, headers: dict) -> tuple[int, dict, object | None, str]:
    status, resp_headers, body = http_get_text(url, headers=headers, timeout=60)
    return status, resp_headers, _json_or_none(resp_headers.get("content-type", ""), body), body


def _post_json(url: str, headers: dict, body_obj: dict) -> tuple[int, dict, object | None, str]:
    body = json.dumps(body_obj).encode("utf-8")
    status, resp_headers, text = http_post_text(url, data=body, headers=headers, timeout=120)
    return status, resp_headers, _json_or_none(resp_headers.get("content-type", ""), text), text


def _part_safe(raw: str) -> str:
    clean = "".join(ch.lower() if ch.isalnum() else "-" for ch in raw).strip("-")
    while "--" in clean:
        clean = clean.replace("--", "-")
    return clean or "query"


def _queries(raw: str | None) -> list[str]:
    if not raw:
        return []
    return [q.strip() for q in raw.split(",") if q.strip()]


def _valid_place(row: object, *, require_active: bool) -> dict | None:
    if not isinstance(row, dict):
        return None
    if require_active and row.get("IsActive") is not True:
        return None
    if row.get("PlaceId") is None or row.get("Latitude") is None or row.get("Longitude") is None:
        return None
    return row


def _booking_window(source_obj, endpoints: Endpoints, ts: str, delay_s: float) -> tuple[str, str] | None:
    url = f"{endpoints.rd_base}/webaccessfacility/futurebookingstartsendsdates"
    status, headers, payload, text = _post_json(url, RD_HEADERS, {"customerClassificationId": 0})
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="POST",
        request_headers=RD_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part="booking-window",
    )
    time.sleep(delay_s)
    if not isinstance(payload, dict):
        return None
    result = payload.get("Result") or {}
    start = str(result.get("FutureBookingStartDate") or "")[:10]
    end = str(result.get("FutureBookingEndDate") or "")[:10]
    if start and end:
        return start, end
    return None


def _citypark_search(source_obj, endpoints: Endpoints, ts: str, query: str, delay_s: float) -> list[dict]:
    encoded = urllib.parse.quote(query)
    url = f"{endpoints.rdr_base}/fd/citypark/namecontains/{encoded}"
    status, headers, payload, text = _get_json(url, RDR_HEADERS)
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="GET",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part=f"citypark-{_part_safe(query)}",
    )
    time.sleep(delay_s)
    if not isinstance(payload, list):
        return []
    return [place for row in payload if (place := _valid_place(row, require_active=True)) is not None]


def _website_settings(source_obj, endpoints: Endpoints, ts: str, delay_s: float) -> dict:
    url = f"{endpoints.rdr_base}/enterprise/websitesettings"
    status, headers, payload, text = _get_json(url, RDR_HEADERS)
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="GET",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part="website-settings",
    )
    time.sleep(delay_s)
    return payload if isinstance(payload, dict) else {}


def _int_or_default(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _search_all_body(settings: dict, start_date: str) -> dict:
    return {
        "PlaceId": _int_or_default(settings.get("facility_default_place_id"), DEFAULT_SEARCH_ALL_PLACE_ID),
        "Latitude": DEFAULT_SEARCH_LATITUDE,
        "Longitude": DEFAULT_SEARCH_LONGITUDE,
        "Nights": 1,
        "CustomerId": 0,
        "StartDate": start_date,
        "UnitCategoryId": 0,
        "SleepingUnitId": 0,
        "MinVehicleLength": 0,
        "UnitTypesGroupIds": None,
        "AmenityIds": None,
        "Sort": "Distance",
        "IsADA": False,
        "RestrictADA": False,
        "NearbyLimit": _int_or_default(settings.get("default_place_results_range"), DEFAULT_SEARCH_RADIUS),
        "isSearchAllParks": True,
        "customerClassificationId": 0,
        "InSeasonOnly": True,
        "WebOnly": True,
        "NearbyCountLimit": 10,
        "NearbyOnlyAvailable": False,
        "CountNearby": True,
        "CountUnits": True,
        "HighlightedPlaceId": 0,
    }


def _search_all_places(source_obj, endpoints: Endpoints, ts: str, window: tuple[str, str], delay_s: float) -> list[dict]:
    start_date, _ = window
    settings = _website_settings(source_obj, endpoints, ts, delay_s)
    url = f"{endpoints.rdr_base}/search/place"
    status, headers, payload, text = _post_json(url, RDR_HEADERS, _search_all_body(settings, start_date))
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="POST",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part="search-all",
    )
    time.sleep(delay_s)
    if not isinstance(payload, dict):
        return []

    candidates = []
    selected = _valid_place(payload.get("SelectedPlace"), require_active=False)
    if selected is not None:
        candidates.append(selected)
    nearby = payload.get("NearbyPlaces") or []
    if isinstance(nearby, list):
        candidates.extend(
            place for row in nearby if (place := _valid_place(row, require_active=False)) is not None
        )
    return candidates


def _place_body(place: dict, start_date: str) -> dict:
    return {
        "PlaceId": place["PlaceId"],
        "Latitude": place["Latitude"],
        "Longitude": place["Longitude"],
        "Nights": 1,
        "CustomerId": 0,
        "StartDate": start_date,
        "UnitCategoryId": 0,
        "SleepingUnitId": 0,
        "MinVehicleLength": 0,
        "UnitTypesGroupIds": [],
        "AmenityIds": [],
        "Sort": "distance",
        "IsADA": False,
        "RestrictADA": False,
        "NearbyLimit": 100,
        "isSearchAllParks": False,
        "customerClassificationId": 0,
        "InSeasonOnly": True,
        "WebOnly": True,
        "NearbyCountLimit": 10,
        "NearbyOnlyAvailable": False,
        "CountNearby": True,
        "CountUnits": True,
        "HighlightedPlaceId": 0,
    }


def _grid_body(facility_id: int, start_date: str, end_date: str, min_date: str, max_date: str) -> dict:
    return {
        "FacilityId": facility_id,
        "UnitSort": "availability",
        "StartDate": start_date,
        "EndDate": end_date,
        "InSeasonOnly": True,
        "WebOnly": True,
        "MaxDate": f"{max_date}T00:00:00",
        "MinDate": f"{min_date}T00:00:00",
        "IsADA": False,
        "RestrictADA": False,
        "UnitCategoryId": 0,
        "SleepingUnitId": 0,
        "MinVehicleLength": 0,
        "UnitTypesGroupIds": [],
        "AmenityIds": [],
        "CustomerId": 0,
        "customerClassificationId": 0,
    }


def _capture_place(source_obj, endpoints: Endpoints, ts: str, place: dict, window: tuple[str, str], delay_s: float) -> list[int]:
    start_date, end_date = window
    url = f"{endpoints.rdr_base}/search/place"
    status, headers, payload, text = _post_json(url, RDR_HEADERS, _place_body(place, start_date))
    place_id = int(place["PlaceId"])
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="POST",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part=f"place-{place_id}",
    )
    time.sleep(delay_s)
    if not isinstance(payload, dict):
        return []
    selected = payload.get("SelectedPlace") or {}
    facilities = selected.get("Facilities") or {}
    ids: list[int] = []
    if isinstance(facilities, dict):
        for key, raw in facilities.items():
            if isinstance(raw, dict):
                fid = raw.get("FacilityId")
            else:
                fid = key
            try:
                ids.append(int(fid))
            except (TypeError, ValueError):
                continue
    return sorted(set(ids))


def _capture_facility(source_obj, endpoints: Endpoints, ts: str, facility_id: int, delay_s: float) -> dict | None:
    url = f"{endpoints.rdr_base}/fd/facilities/{facility_id}"
    status, headers, payload, text = _get_json(url, RDR_HEADERS)
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="GET",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part=f"facility-{facility_id}",
    )
    time.sleep(delay_s)
    return payload if isinstance(payload, dict) else None


def _capture_grid(source_obj, endpoints: Endpoints, ts: str, facility_id: int, window: tuple[str, str], delay_s: float) -> None:
    start_date, end_date = window
    url = f"{endpoints.rdr_base}/search/grid"
    status, headers, payload, text = _post_json(
        url,
        RDR_HEADERS,
        _grid_body(facility_id, start_date, end_date, start_date, end_date),
    )
    write_envelope(
        source_obj=source_obj,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="POST",
        request_headers=RDR_HEADERS,
        response_status=status,
        response_headers=headers,
        payload=payload if payload is not None else text,
        ts=ts,
        part=f"grid-{facility_id}",
    )
    time.sleep(delay_s)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True, help="data_source slug from poi-registry.yaml")
    parser.add_argument("--queries", default="", help="comma-separated citypark namecontains queries")
    parser.add_argument(
        "--discovery",
        choices=["search-all", "queries"],
        default="",
        help="place discovery strategy (default: search-all unless queries are explicitly configured)",
    )
    parser.add_argument("--rdr-base", default="", help=argparse.SUPPRESS)
    parser.add_argument("--rd-base", default="", help=argparse.SUPPRESS)
    parser.add_argument("--output-dir-prefix", default="", help=argparse.SUPPRESS)
    parser.add_argument("--ts", default="", help="capture timestamp directory")
    parser.add_argument("--delay", type=float, default=0.5, help="seconds between upstream calls")
    args = parser.parse_args()

    source_obj = load_source(args.slug)
    if args.output_dir_prefix:
        source_obj = LoadedSource(
            slug=source_obj.slug,
            name=source_obj.name,
            output_dir_prefix=Path(args.output_dir_prefix),
            args=source_obj.args,
        )
    endpoints = Endpoints(
        rdr_base=(args.rdr_base or RDR_BASE).rstrip("/"),
        rd_base=(args.rd_base or RD_BASE).rstrip("/"),
    )
    queries = _queries(args.queries or source_obj.args.get("queries"))
    discovery = args.discovery or source_obj.args.get("discovery") or ("queries" if queries else "search-all")
    if discovery == "queries" and not queries:
        err("ReserveCalifornia query discovery requires --queries or fetcher.args.queries")
        return 1

    ts = args.ts or utc_ts()
    window = _booking_window(source_obj, endpoints, ts, args.delay)
    if window is None:
        err("could not resolve ReserveCalifornia booking window")
        return 1

    seen_places: set[int] = set()
    seen_facilities: set[int] = set()
    places: list[dict] = []
    if discovery == "search-all":
        places = _search_all_places(source_obj, endpoints, ts, window, args.delay)
    else:
        for query in queries:
            places.extend(_citypark_search(source_obj, endpoints, ts, query, args.delay))

    for place in places:
        place_id = int(place["PlaceId"])
        if place_id in seen_places:
            continue
        seen_places.add(place_id)
        err(f"  place {place_id}: {place.get('Name')}")
        facility_ids = _capture_place(source_obj, endpoints, ts, place, window, args.delay)
        for facility_id in facility_ids:
            if facility_id in seen_facilities:
                continue
            seen_facilities.add(facility_id)
            facility = _capture_facility(source_obj, endpoints, ts, facility_id, args.delay)
            if facility:
                if facility.get("FacilityTypeNew") == 2 or facility.get("FacilityBehaviourType") == 2:
                    err(f"    facility {facility_id}: special grid shape, catalog grid skipped")
                    continue
            _capture_grid(source_obj, endpoints, ts, facility_id, window, args.delay)

    err(f"done: ts={ts} places={len(seen_places)} facilities={len(seen_facilities)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
