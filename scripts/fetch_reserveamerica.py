#!/usr/bin/env python3
"""Capture ReserveAmerica (Active Network) tenants — provincial/state parks.

Vendor-centric: one fetcher walks every configured tenant. Each tenant
maps to a (host, contractCode) pair on Active's HTML reservation
platform; the parser is identical across tenants because the platform
is.

Currently configured:

  ABPP  shop.albertaparks.ca   Alberta Provincial Parks  →  data/raw/reserveamerica-abpp/

Adding a tenant = one row in TENANTS below. Other Active Network park
agencies use the same `campgroundDirectoryList.do` / `campgroundDetails.do`
endpoints under their own host + contractCode.

Two-pass capture per tenant, all under one timestamped directory:

  data/raw/reserveamerica-<contract>/<UTC-ts>/directory-<letter>-<startIdx>.json
  data/raw/reserveamerica-<contract>/<UTC-ts>/park-<parkId>.json

Pass 1 — directory walk (A-Z × startIdx pagination): captures the park
list with parkId + park-centroid lat/lng.

Pass 2 — per-park detail (`campgroundDetails.do?parkId=…`): captures
the sub-campground inventory from the `<select name='suggestedCampgroundId'>`
element + any wildernessAreaDetails parkIds (non-reservable backcountry)
the ETL can chase later.

Active's WAF blocks bare-curl UAs and infinite-redirects requests
without a JSESSIONID cookie. Fetcher uses its own urllib opener with a
CookieJar (the welcome.do hit primes the session). Browser-shaped UA +
Referer + 0.5s politeness delay. Raw HTML payloads land verbatim in
the standard envelope; the Kotlin ETL parses them.

Run:
  python3 scripts/fetch_reserveamerica.py
"""
from __future__ import annotations

import http.cookiejar
import re
import string
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    parse_payload,
    utc_ts,
    write_envelope,
)

FETCHER = "fetch_reserveamerica"
FETCHER_VERSION = "1"

# Browser-shaped UA — bare python-urllib hits a 403 redirect off the WAF.
COMMON_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) "
        "Gecko/20100101 Firefox/121.0"
    ),
    "Accept": (
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    ),
    "Accept-Language": "en-CA,en;q=0.9",
}

# Politeness — 0.5s between requests (~75s wall time per tenant of ~110 parks).
DELAY_S = 0.5

# Each directory page returns up to 10 rows. The platform's "all parks"
# listing has a server-side bug that caps at ~110 rows; the A-Z letter
# filter walks past it cleanly. Empirically ≤10 pages per letter.
LETTERS = list(string.ascii_uppercase)
PAGE_STEP = 10
MAX_PAGES_PER_LETTER = 10  # safety cap


@dataclass
class Tenant:
    contract: str        # ABPP, NSPP, …
    host: str            # shop.albertaparks.ca, …
    label: str           # human label for stderr logs


TENANTS: list[Tenant] = [
    Tenant(contract="ABPP", host="shop.albertaparks.ca", label="Alberta Parks"),
    # Add NSPP / others by appending here once their hosts are confirmed.
]


def make_session(host: str) -> urllib.request.OpenerDirector:
    """Build a urllib opener with a session cookie jar.

    The campgroundDetails endpoint redirects to itself indefinitely
    without a JSESSIONID cookie set by the welcome page. _envelope's
    http_get_text builds a fresh Request per call, which can't share
    cookies, so we keep our own opener here. Headers (UA, Referer)
    inject per-request via _request().
    """
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    return opener


def _request(opener, url: str, *, referer: str, timeout: int = 60):
    headers = {**COMMON_HEADERS, "Referer": referer}
    req = urllib.request.Request(url, headers=headers)
    with opener.open(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        resp_headers = {k.lower(): v for k, v in resp.getheaders()}
        return resp.status, headers, resp_headers, body


_PARKID_RX = re.compile(
    r"campgroundDetails\.do\?contractCode=([A-Z]+)&(?:amp;)?parkId=(\d+)"
)


def park_ids_in_html(contract: str, html: str) -> set[str]:
    return {pid for c, pid in _PARKID_RX.findall(html) if c == contract}


def directory_url(host: str, contract: str, letter: str, start_idx: int) -> str:
    return (
        f"https://{host}/campgroundDirectoryList.do"
        f"?contractCode={contract}&letter={letter}&startIdx={start_idx}"
    )


def park_detail_url(host: str, contract: str, park_id: str) -> str:
    # The slug in the path is cosmetic — the server only reads contractCode + parkId.
    return (
        f"https://{host}/camping/x/r/campgroundDetails.do"
        f"?contractCode={contract}&parkId={park_id}"
    )


def fetch_tenant(tenant: Tenant, ts: str) -> int:
    source = f"reserveamerica-{tenant.contract.lower()}"
    welcome_url = f"https://{tenant.host}/welcome.do"
    opener = make_session(tenant.host)

    err(f"  [{tenant.contract}] {tenant.label} — priming session @ {welcome_url}")
    try:
        _request(opener, welcome_url, referer=f"https://{tenant.host}/", timeout=30)
    except Exception as e:  # noqa: BLE001
        err(f"  [{tenant.contract}] welcome.do failed: {e}")
        return 1

    err(f"  [{tenant.contract}] pass 1: directory walk")
    captured_park_ids: set[str] = set()
    pages_written = 0
    for letter in LETTERS:
        for page in range(MAX_PAGES_PER_LETTER):
            start_idx = page * PAGE_STEP
            url = directory_url(tenant.host, tenant.contract, letter, start_idx)
            try:
                status, req_headers, resp_headers, body = _request(
                    opener, url, referer=welcome_url, timeout=60
                )
            except Exception as e:  # noqa: BLE001
                err(f"  [{tenant.contract}] dir {letter}/{start_idx} failed: {e}")
                return 1

            payload = parse_payload(resp_headers.get("content-type", ""), body)
            ids_on_page = park_ids_in_html(tenant.contract, body)
            if not ids_on_page:
                # Empty letter or past-end pagination — stop walking this letter.
                break
            write_envelope(
                source=source,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=url,
                request_method="GET",
                request_headers=req_headers,
                response_status=status,
                response_headers=resp_headers,
                payload=payload,
                ts=ts,
                part=f"directory-{letter}-{start_idx:03d}",
            )
            pages_written += 1
            new_ids = ids_on_page - captured_park_ids
            captured_park_ids.update(ids_on_page)
            err(
                f"  [{tenant.contract}] {letter}@{start_idx}: "
                f"{len(ids_on_page)} ids ({len(new_ids)} new, "
                f"{len(captured_park_ids)} total)"
            )
            time.sleep(DELAY_S)
            if len(ids_on_page) < PAGE_STEP:
                break

    err(
        f"  [{tenant.contract}] pass 1 complete: "
        f"{pages_written} pages, {len(captured_park_ids)} unique parkIds"
    )

    err(f"  [{tenant.contract}] pass 2: per-park details")
    park_ids_sorted = sorted(captured_park_ids, key=int)
    for i, park_id in enumerate(park_ids_sorted, start=1):
        url = park_detail_url(tenant.host, tenant.contract, park_id)
        try:
            status, req_headers, resp_headers, body = _request(
                opener, url, referer=welcome_url, timeout=60
            )
        except Exception as e:  # noqa: BLE001
            # Don't bail on a single park failure — capture what we can.
            err(f"  [{tenant.contract}] park {park_id} failed: {e}")
            continue
        payload = parse_payload(resp_headers.get("content-type", ""), body)
        write_envelope(
            source=source,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers=req_headers,
            response_status=status,
            response_headers=resp_headers,
            payload=payload,
            ts=ts,
            part=f"park-{park_id}",
        )
        if i % 20 == 0:
            err(f"  [{tenant.contract}] park-details {i}/{len(park_ids_sorted)}…")
        time.sleep(DELAY_S)

    err(
        f"  [{tenant.contract}] done: ts={ts} "
        f"directory-pages={pages_written} parks={len(park_ids_sorted)}"
    )
    return 0


def main() -> int:
    ts = utc_ts()
    rc = 0
    for tenant in TENANTS:
        if fetch_tenant(tenant, ts) != 0:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
