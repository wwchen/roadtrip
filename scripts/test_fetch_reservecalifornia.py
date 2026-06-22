#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).parent.parent


class ReserveCaliforniaHandler(BaseHTTPRequestHandler):
    requests: list[dict] = []

    def log_message(self, format: str, *args) -> None:
        return

    def _json(self, payload, status: int = 200) -> None:
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _record(self, body=None) -> None:
        self.requests.append(
            {
                "method": self.command,
                "path": self.path,
                "tenant": self.headers.get("tenantId"),
                "body": body,
            }
        )

    def do_GET(self) -> None:
        self._record()
        if self.path == "/rdr/enterprise/websitesettings":
            self._json({"facility_default_place_id": 691, "default_place_results_range": 100})
            return
        if self.path == "/rdr/fd/facilities/901":
            self._json(
                {
                    "FacilityId": 901,
                    "Name": "Jedediah Smith Campground",
                    "FacilityTypeNew": 1,
                    "FacilityBehaviourType": 1,
                }
            )
            return
        self._json({"error": f"unexpected GET {self.path}"}, status=404)

    def do_POST(self) -> None:
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        body = json.loads(raw.decode("utf-8")) if raw else {}
        self._record(body)

        if self.path == "/api/webaccessfacility/futurebookingstartsendsdates":
            self._json(
                {
                    "Response": 1,
                    "Result": {
                        "FutureBookingStartDate": "2026-06-22T00:00:00",
                        "FutureBookingEndDate": "2026-12-22T00:00:00",
                    },
                }
            )
            return

        if self.path == "/rdr/search/place" and body.get("isSearchAllParks") is True:
            self._json(
                {
                    "SelectedPlace": {
                        "PlaceId": 691,
                        "Name": "Pismo SB",
                        "Latitude": 35.0,
                        "Longitude": -120.0,
                        "Facilities": {},
                    },
                    "NearbyPlaces": [
                        {
                            "PlaceId": 660,
                            "Name": "Jedediah Smith Redwoods SP",
                            "Latitude": 41.79719048,
                            "Longitude": -124.0827693,
                            "Facilities": {},
                        }
                    ],
                }
            )
            return

        if self.path == "/rdr/search/place" and body.get("PlaceId") == 691:
            self._json(
                {
                    "SelectedPlace": {
                        "PlaceId": 691,
                        "Name": "Pismo SB",
                        "Latitude": 35.0,
                        "Longitude": -120.0,
                        "Facilities": {},
                    }
                }
            )
            return

        if self.path == "/rdr/search/place" and body.get("PlaceId") == 660:
            self._json(
                {
                    "SelectedPlace": {
                        "PlaceId": 660,
                        "Name": "Jedediah Smith Redwoods SP",
                        "Latitude": 41.79719048,
                        "Longitude": -124.0827693,
                        "Facilities": {"901": {"FacilityId": 901}},
                    }
                }
            )
            return

        if self.path == "/rdr/search/grid" and body.get("FacilityId") == 901:
            self._json({"Facility": {"FacilityId": 901, "Units": {}}})
            return

        self._json({"error": f"unexpected POST {self.path}", "body": body}, status=404)


class ReserveCaliforniaFetcherTest(unittest.TestCase):
    def setUp(self) -> None:
        ReserveCaliforniaHandler.requests = []
        self.capture_root = Path(tempfile.mkdtemp(prefix="reservecalifornia-fetcher-"))
        self.capture_dir = self.capture_root / "rc-search-all-test"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ReserveCaliforniaHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.thread.join(timeout=2)
        self.server.server_close()
        shutil.rmtree(self.capture_root, ignore_errors=True)

    def test_search_all_discovers_places_without_name_queries(self) -> None:
        base = f"http://127.0.0.1:{self.server.server_port}"
        proc = subprocess.run(
            [
                "python3",
                "scripts/fetch_reservecalifornia.py",
                "--slug",
                "reservecalifornia-catalog",
                "--ts",
                "rc-search-all-test",
                "--delay",
                "0",
                "--discovery",
                "search-all",
                "--rdr-base",
                f"{base}/rdr",
                "--rd-base",
                f"{base}/api",
                "--output-dir-prefix",
                str(self.capture_root),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=30,
        )

        self.assertEqual(proc.returncode, 0, proc.stderr)
        search_all_posts = [
            req
            for req in ReserveCaliforniaHandler.requests
            if req["method"] == "POST"
            and req["path"] == "/rdr/search/place"
            and req["body"].get("isSearchAllParks") is True
        ]
        self.assertEqual(1, len(search_all_posts))
        self.assertEqual(691, search_all_posts[0]["body"]["PlaceId"])
        self.assertFalse(
            any("/fd/citypark/namecontains/" in req["path"] for req in ReserveCaliforniaHandler.requests)
        )
        self.assertTrue((self.capture_dir / "search-all.json").exists())
        self.assertTrue((self.capture_dir / "place-660.json").exists())
        self.assertTrue((self.capture_dir / "facility-901.json").exists())
        self.assertTrue((self.capture_dir / "grid-901.json").exists())


if __name__ == "__main__":
    unittest.main()
