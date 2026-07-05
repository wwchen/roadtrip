#!/usr/bin/env python3
"""Capture ReserveAmerica campsite-calendar rosters per park.

For each park in a tenant, fetch campsiteCalendar.do (paginating startIdx by
SITE_PAGE_SIZE until the site list is exhausted) and write one envelope per
page. The site roster — siteId + label — is embedded in these pages;
ReserveAmericaSitesEtl parses it. We reuse fetch_reserveamerica's WAF session
(welcome.do primes the JSESSIONID cookie) and directory walk to enumerate
parkIds.

Usage:
  python3 scripts/fetch_reserveamerica_campsites.py            # all tenants
  python3 scripts/fetch_reserveamerica_campsites.py --tenant ABPP
"""
from __future__ import annotations

import argparse
import datetime as dt
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


def calendar_url(host: str, contract: str, park_id: str, arvdate: str, start_idx: int) -> str:
    return (
        f"https://{host}/campsiteCalendar.do?page=calendar"
        f"&contractCode={contract}&parkId={park_id}"
        f"&calarvdate={arvdate}&sitepage=true&startIdx={start_idx}"
    )


def enumerate_park_ids(tenant: Tenant, opener, welcome_url: str) -> list[str]:
    ids: set[str] = set()
    for letter in LETTERS:
        for page in range(MAX_PAGES_PER_LETTER):
            url = directory_url(tenant.host, tenant.contract, letter, page * PAGE_STEP)
            _status, _req_h, _resp_h, body = _request(opener, url, referer=welcome_url, timeout=60)
            on_page = park_ids_in_html(tenant.contract, body)
            if not on_page:
                break
            ids.update(on_page)
            time.sleep(DELAY_S)
            if len(on_page) < PAGE_STEP:
                break
    return sorted(ids, key=int)


def fetch_tenant(tenant: Tenant, ts: str) -> int:
    slug = f"reserveamerica-campsites-{tenant.contract.lower()}"
    source_obj = load_source(slug)
    welcome_url = f"https://{tenant.host}/welcome.do"
    opener = make_session(tenant.host)
    _request(opener, welcome_url, referer=f"https://{tenant.host}/", timeout=30)

    park_ids = enumerate_park_ids(tenant, opener, welcome_url)
    err(f"  [{tenant.contract}] {len(park_ids)} parks")
    # A near-term arrival date makes the calendar list the full site roster.
    arvdate = (dt.date.today() + dt.timedelta(days=14)).strftime("%m/%d/%Y")

    for i, park_id in enumerate(park_ids, start=1):
        start_idx = 0
        for _ in range(MAX_SITE_PAGES):
            url = calendar_url(tenant.host, tenant.contract, park_id, arvdate, start_idx)
            try:
                status, req_h, resp_h, body = _request(opener, url, referer=welcome_url, timeout=60)
            except Exception as e:  # noqa: BLE001
                err(f"  [{tenant.contract}] park {park_id}@{start_idx} failed: {e}")
                break
            payload = parse_payload(resp_h.get("content-type", ""), body)
            row_count = body.count("siteListLabel")
            if row_count == 0:
                break
            write_envelope(
                source_obj=source_obj,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=url,
                request_method="GET",
                request_headers=req_h,
                response_status=status,
                response_headers=resp_h,
                payload=payload,
                ts=ts,
                part=f"campsite-{park_id}-{start_idx}",
            )
            if row_count < SITE_PAGE_SIZE:
                break
            start_idx += SITE_PAGE_SIZE
            time.sleep(DELAY_S)
        if i % 20 == 0:
            err(f"  [{tenant.contract}] {i}/{len(park_ids)} parks…")
        time.sleep(DELAY_S)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tenant", help="ABPP or NY; omit for all")
    args = ap.parse_args()
    ts = utc_ts()
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
