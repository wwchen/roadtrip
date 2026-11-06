from __future__ import annotations

import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
CADDYFILE = ROOT / "caddy" / "Caddyfile"
HOST = "roadtrip.floo.ca"
EGRESS_HOST = "roadtrip-egress.floo.ca"
EGRESS_PATH = "/api/internal/availability/fetch"
EGRESS_HIDDEN_GETS = ["/", "/api/me", EGRESS_PATH]
SANDBOX_NAME = "routing"
SANDBOX_HOST = f"sb-{SANDBOX_NAME}.floo.ca"
SANDBOX_BACKEND_PORT = "8765"
DEPLOY_SCRIPT = ROOT / "scripts" / "deploy.sh"
SNIPPET_START = 'cat > "${CADDY_SNIPPET}" <<CADDY\n'
SNIPPET_END = "\nCADDY\n"
CLIENT_IP = "203.0.113.7"
READY_DEADLINE_SECONDS = 30
READY_POLL_SECONDS = 0.5

WEB_STUB = ":3000 {\n\trespond \"web\"\n}\n"
BACKEND_STUB = ":8765 {\n\trespond \"backend {header.X-Forwarded-For}\"\n}\n"

WEB_PATHS = [
    "/api/pois",
    "/api/pois/search?q=Mahood",
    "/api/pois/on-route",
    "/api/pois/11042",
    "/api/campgrounds/search",
    "/api/campgrounds/details",
    "/api/geocode?q=Kamloops",
    "/api/route?coords=1,2;3,4",
]
BACKEND_PATHS = [
    "/",
    "/poi",
    "/assets/index.js",
    "/api/me",
    "/api/watches",
    "/api/health",
    "/api/build-info",
    "/api/pois/abc",
    "/api/pois/11042/campsites",
    "/api/pois/11042/campsites/availability",
    "/api/pois/availability/stays",
]


def sandbox_snippet() -> str:
    script = DEPLOY_SCRIPT.read_text()
    template = script.split(SNIPPET_START, 1)[1].split(SNIPPET_END, 1)[0]
    return (
        template.replace("${SANDBOX_FQDN}", SANDBOX_HOST)
        .replace("${SANDBOX_NAME}", SANDBOX_NAME)
        .replace("${SANDBOX_BACKEND_PORT}", SANDBOX_BACKEND_PORT)
        + "\n"
    )


def caddy_image() -> str:
    compose = yaml.safe_load((ROOT / "docker-compose.yml").read_text())
    return compose["services"]["caddy"]["image"]


def docker(*args: str) -> str:
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True).stdout.strip()


@unittest.skipUnless(shutil.which("docker"), "needs docker")
class CaddyRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(tmp.cleanup)
        stubs = Path(tmp.name)
        (stubs / "web.Caddyfile").write_text(WEB_STUB)
        (stubs / "backend.Caddyfile").write_text(BACKEND_STUB)
        (stubs / "sandboxes").mkdir()
        (stubs / "sandboxes" / f"sb-{SANDBOX_NAME}.caddy").write_text(sandbox_snippet())
        image = caddy_image()
        network = f"caddy-routing-{uuid.uuid4().hex[:12]}"
        docker("network", "create", network)
        cls.addClassCleanup(subprocess.run, ["docker", "network", "rm", network], capture_output=True, check=False)
        for aliases, stub in ((["web"], "web.Caddyfile"), (["backend", f"sb-{SANDBOX_NAME}-backend"], "backend.Caddyfile")):
            alias_options = [option for alias in aliases for option in ("--network-alias", alias)]
            cls.start(image, "--network", network, *alias_options, "-v", f"{stubs / stub}:/etc/caddy/Caddyfile:ro")
        cls.proxy = cls.start(
            image, "--network", network,
            "-v", f"{CADDYFILE}:/etc/caddy/Caddyfile:ro",
            "-v", f"{stubs / 'sandboxes'}:/etc/caddy/sandboxes:ro",
        )
        deadline = time.monotonic() + READY_DEADLINE_SECONDS
        while cls.fetch("/api/me") is None:
            if time.monotonic() > deadline:
                raise AssertionError(f"caddy never answered:\n{docker('logs', cls.proxy)}")
            time.sleep(READY_POLL_SECONDS)

    @classmethod
    def start(cls, image: str, *options: str) -> str:
        container = docker("run", "-d", *options, image)
        cls.addClassCleanup(subprocess.run, ["docker", "rm", "-f", container], capture_output=True, check=False)
        return container

    @classmethod
    def fetch(cls, path: str, *headers: str, host: str = HOST, post: bool = False) -> str | None:
        args = ["exec", cls.proxy, "wget", "-qO-", "--header", f"Host: {host}"]
        if post:
            args += ["--post-data", ""]
        for header in headers:
            args += ["--header", header]
        result = subprocess.run(
            ["docker", *args, f"http://127.0.0.1:80{path}"], capture_output=True, text=True, check=False
        )
        return result.stdout if result.returncode == 0 else None

    def test_the_read_api_goes_to_web(self) -> None:
        for path in WEB_PATHS:
            self.assertEqual("web", self.fetch(path), path)

    def test_everything_else_stays_on_the_backend(self) -> None:
        for path in BACKEND_PATHS:
            self.assertTrue((self.fetch(path) or "").startswith("backend"), path)

    def test_the_egress_host_proxies_only_the_fetch_path(self) -> None:
        self.assertTrue((self.fetch(EGRESS_PATH, host=EGRESS_HOST, post=True) or "").startswith("backend"))

    def test_the_egress_host_hides_everything_else(self) -> None:
        for path in EGRESS_HIDDEN_GETS:
            self.assertIsNone(self.fetch(path, host=EGRESS_HOST), path)

    def test_internal_paths_are_refused_off_the_egress_host(self) -> None:
        for host in (HOST, SANDBOX_HOST):
            for post in (False, True):
                self.assertIsNone(self.fetch(EGRESS_PATH, host=host, post=post), f"{host} post={post}")

    def test_the_sandbox_host_still_reaches_its_backend(self) -> None:
        self.assertTrue((self.fetch("/api/me", host=SANDBOX_HOST) or "").startswith("backend"))

    def test_the_backend_still_sees_the_client_address(self) -> None:
        body = self.fetch("/api/me", f"X-Forwarded-For: {CLIENT_IP}") or ""
        self.assertIn(CLIENT_IP, body)


if __name__ == "__main__":
    unittest.main()
