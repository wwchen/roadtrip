#!/usr/bin/env python3
"""Capture ReserveAmerica campsite-calendar rosters per park.

For each park in a tenant, load campsiteCalendar.do, sort the site list by
site number with campsiteSort.do (the default order shifts between pages),
then page startIdx by SITE_PAGE_SIZE up to the total the page lists, writing
one envelope per page. The site roster — siteId + label — is embedded in these pages;
ReserveAmericaSitesEtl parses it. We reuse fetch_reserveamerica's WAF session
(welcome.do primes the JSESSIONID cookie) and directory walk to enumerate
parkIds.

Usage:
  python3 scripts/fetch_reserveamerica_campsites.py            # all tenants
  python3 scripts/fetch_reserveamerica_campsites.py --tenant ABPP
  python3 scripts/fetch_reserveamerica_campsites.py --tenant NY --ts 2026-11-22T16-49-49Z
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import err, load_source, parse_payload, utc_ts, write_envelope  # noqa: E402
from fetch_reserveamerica import (  # noqa: E402
    DELAY_S,
    LETTERS,
    MAX_PAGES_PER_LETTER,
    PAGE_STEP,
    TENANTS,
    Tenant,
    _request,
    directory_url,
    make_session,
    park_ids_in_html,
)

FETCHER = "fetch_reserveamerica_campsites"
FETCHER_VERSION = "1"

# campsiteCalendar.do paginates the site list 25 at a time (mirrors the
# ReserveAmericaAvailabilityClient PAGE_SIZE). Cap pages defensively.
SITE_PAGE_SIZE = 25
MAX_SITE_PAGES = 40  # ~1000 sites/park ceiling
RESULT_FROM_RX = re.compile(r"id='resultfrom_top'>(\d+)<")
RESULT_TOTAL_RX = re.compile(r"id='resulttotal_top'>(\d+)<")
SITE_ID_RX = re.compile(r"campsiteDetails\.do\?contractCode=[A-Z]+&amp;siteId=(\d+)")
WELCOME_TIMEOUT_S = 30
DIRECTORY_TIMEOUT_S = 60
PAGE_TIMEOUT_S = 120
PAGE_ATTEMPTS = 3
RETRY_BACKOFF_S = 30
EXIT_INCOMPLETE = 2
SORT_COLUMN = "BySiteNumber"
SORT_DIRECTION = "asc"


def calendar_url(host: str, contract: str, park_id: str, arvdate: str, start_idx: int) -> str:
    return (
        f"https://{host}/campsiteCalendar.do?page=calendar"
        f"&contractCode={contract}&parkId={park_id}"
        f"&calarvdate={arvdate}&sitepage=true&startIdx={start_idx}"
    )


def sort_url(host: str, contract: str, park_id: str) -> str:
    return (
        f"https://{host}/campsiteSort.do?col={SORT_COLUMN}&dir={SORT_DIRECTION}"
        f"&contractCode={contract}&parkId={park_id}"
    )


def enumerate_park_ids(tenant: Tenant, opener, welcome_url: str) -> list[str]:
    ids: set[str] = set()
    for letter in LETTERS:
        for page in range(MAX_PAGES_PER_LETTER):
            url = directory_url(tenant.host, tenant.contract, letter, page * PAGE_STEP)
            _status, _req_h, _resp_h, body = _request(opener, url, referer=welcome_url, timeout=DIRECTORY_TIMEOUT_S)
            on_page = park_ids_in_html(tenant.contract, body)
            if not on_page:
                break
            ids.update(on_page)
            time.sleep(DELAY_S)
            if len(on_page) < PAGE_STEP:
                break
    return sorted(ids, key=int)


def fetch_page(opener, url: str, welcome_url: str, label: str):
    for attempt in range(1, PAGE_ATTEMPTS):
        try:
            return _request(opener, url, referer=welcome_url, timeout=PAGE_TIMEOUT_S)
        except Exception as e:  # noqa: BLE001
            err(f"  {label} attempt {attempt}/{PAGE_ATTEMPTS} failed: {type(e).__name__}: {e}; retrying")
            time.sleep(RETRY_BACKOFF_S * attempt)
    return _request(opener, url, referer=welcome_url, timeout=PAGE_TIMEOUT_S)


class PagingError(Exception):
    pass


def page_number(rx: re.Pattern, text: str) -> int | None:
    match = rx.search(text)
    return int(match.group(1)) if match else None


def offset_honored(text: str, start_idx: int) -> bool:
    return page_number(RESULT_FROM_RX, text) == start_idx + 1


def open_park(tenant: Tenant, opener, welcome_url: str, park_id: str, arvdate: str, label: str):
    first = calendar_url(tenant.host, tenant.contract, park_id, arvdate, 0)
    _status, _req_h, _resp_h, text = fetch_page(opener, first, welcome_url, label)
    if not SITE_ID_RX.search(text):
        return None
    time.sleep(DELAY_S)
    url = sort_url(tenant.host, tenant.contract, park_id)
    return url, *fetch_page(opener, url, welcome_url, label)


def fetch_park(tenant: Tenant, opener, welcome_url: str, source_obj, ts: str, park_id: str, arvdate: str) -> tuple[int, int]:
    capture_dir = source_obj.output_dir_prefix / ts
    sites: set[str] = set()
    total: int | None = None
    start_idx = 0
    fetched = reused = 0
    while total is None or start_idx < total:
        if start_idx >= MAX_SITE_PAGES * SITE_PAGE_SIZE:
            raise PagingError(f"hit the {MAX_SITE_PAGES}-page cap with {len(sites)} of {total} sites")
        part = f"campsite-{park_id}-{start_idx}"
        existing = capture_dir / f"{part}.json"
        text = existing.read_text(encoding="utf-8") if existing.exists() else None
        if text is not None and offset_honored(text, start_idx):
            reused += 1
        else:
            label = f"[{tenant.contract}] park {park_id}@{start_idx}"
            if start_idx == 0:
                opened = open_park(tenant, opener, welcome_url, park_id, arvdate, label)
                if opened is None:
                    return fetched, reused
                url, status, req_h, resp_h, text = opened
            else:
                url = calendar_url(tenant.host, tenant.contract, park_id, arvdate, start_idx)
                status, req_h, resp_h, text = fetch_page(opener, url, welcome_url, label)
                if not offset_honored(text, start_idx):
                    if open_park(tenant, opener, welcome_url, park_id, arvdate, label) is None:
                        raise PagingError(f"park listed no sites when reloaded for startIdx={start_idx}")
                    status, req_h, resp_h, text = fetch_page(opener, url, welcome_url, label)
            if not offset_honored(text, start_idx):
                raise PagingError(f"asked for startIdx={start_idx}, page starts at result {page_number(RESULT_FROM_RX, text)}")
            write_envelope(
                source_obj=source_obj,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=url,
                request_method="GET",
                request_headers=req_h,
                response_status=status,
                response_headers=resp_h,
                payload=parse_payload(resp_h.get("content-type", ""), text),
                ts=ts,
                part=part,
            )
            fetched += 1
            time.sleep(DELAY_S)
        total = page_number(RESULT_TOTAL_RX, text)
        if total is None:
            raise PagingError(f"page startIdx={start_idx} has no resulttotal_top")
        sites |= set(SITE_ID_RX.findall(text))
        start_idx += SITE_PAGE_SIZE
    if len(sites) != total:
        raise PagingError(f"pages hold {len(sites)} distinct sites, the park lists {total}")
    return fetched, reused


def fetch_tenant(tenant: Tenant, ts: str) -> int:
    slug = f"reserveamerica-campsites-{tenant.contract.lower()}"
    source_obj = load_source(slug)
    welcome_url = f"https://{tenant.host}/welcome.do"
    opener = make_session(tenant.host)
    _request(opener, welcome_url, referer=f"https://{tenant.host}/", timeout=WELCOME_TIMEOUT_S)

    park_ids = enumerate_park_ids(tenant, opener, welcome_url)
    err(f"  [{tenant.contract}] {len(park_ids)} parks")
    # A near-term arrival date makes the calendar list the full site roster.
    arvdate = (dt.date.today() + dt.timedelta(days=14)).strftime("%m/%d/%Y")

    failed: list[str] = []
    fetched = reused = 0
    for i, park_id in enumerate(park_ids, start=1):
        try:
            park_fetched, park_reused = fetch_park(tenant, opener, welcome_url, source_obj, ts, park_id, arvdate)
            fetched += park_fetched
            reused += park_reused
        except Exception as e:  # noqa: BLE001
            err(f"  [{tenant.contract}] park {park_id} failed: {type(e).__name__}: {e}")
            failed.append(park_id)
        if i % 20 == 0:
            err(f"  [{tenant.contract}] {i}/{len(park_ids)} parks…")
        time.sleep(DELAY_S)
    err(f"  [{tenant.contract}] fetched {fetched} pages, reused {reused} from {source_obj.output_dir_prefix / ts}")
    if failed:
        err(f"  [{tenant.contract}] {len(failed)} parks incomplete: {', '.join(failed)}")
        return EXIT_INCOMPLETE
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tenant", help="ABPP or NY; omit for all")
    ap.add_argument("--ts", default="", help="capture timestamp directory to resume")
    args = ap.parse_args()
    ts = args.ts or utc_ts()
    tenants = [t for t in TENANTS if not args.tenant or t.contract == args.tenant]
    if not tenants:
        err(f"no tenant matching {args.tenant}")
        return 1
    rc = 0
    for t in tenants:
        rc |= fetch_tenant(t, ts)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
