#!/usr/bin/env python3
"""Print the origin the production app serves itself on.

`roadtrip.web.root-url` in application-prod.yaml is the single source for this
value: AuthConfig derives the OIDC redirect URI from it, and the deploy workflow
probes it. Restating the hostname in CI is how you end up health-checking an
origin the app stopped serving, so CI reads it from here instead.

The trailing slash is stripped the way WebAppConfig strips it, so callers can
build "$base/path" without doubling up.
"""

from __future__ import annotations

import os
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "backend" / "src" / "main" / "resources" / "application-prod.yaml"
KTOR_ENV_DEFAULT = re.compile(r"^\$\{([A-Z][A-Z0-9_]*):(.*)\}$")


def prod_base_url(config_text: str) -> str:
    """The prod web origin, without its trailing slash.

    Raises on a missing key rather than returning "": an empty base would surface
    as a pile of confusing curl errors several deploy steps later.
    """
    config = yaml.safe_load(config_text) or {}
    raw = str(config["roadtrip"]["web"]["root-url"])
    match = KTOR_ENV_DEFAULT.match(raw)
    if match:
        raw = os.environ.get(match.group(1), match.group(2))
    return raw.rstrip("/")


def main() -> int:
    try:
        print(prod_base_url(CONFIG.read_text()))
    except (KeyError, TypeError):
        print(f"roadtrip.web.root-url missing from {CONFIG}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
