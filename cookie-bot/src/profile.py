"""Profile schema and loader.

A profile describes how to warm cookies for one site. The runner loads a YAML
file from PROFILES_DIR and executes its steps in a headless browser.

Schema (fields; all paths are YAML keys):
  start_url          str           URL to navigate to first (required)
  cookie_domains     list[str]     Substring-match against cookie .domain;
                                   only matching cookies are returned
                                   (e.g. ".tesla.com"). Required.
  ttl_seconds        int           How long a harvested cookie jar is
                                   considered fresh. Default 3600.
  steps              list[dict]    Sequence of browser actions. See below.

Step types (first key of each step-dict is the verb):
  - wait_for:       "networkidle" | "load" | "domcontentloaded"
  - wait_ms:        int            sleep for N milliseconds
  - click:          str            CSS selector; clicks first match (if present)
  - click_any:      list[str]      tries each selector, clicks first that hits
  - scroll_to:      int            scroll the page by N pixels
  - eval:           str            page.evaluate(js_source)

Steps are best-effort: a missing selector logs and continues. The goal is to
trigger whatever XHR/fetch call promotes `_abck`; if one path fails we fall
back to the next selector.
"""
from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import yaml


@dataclass
class Profile:
    name: str
    start_url: str
    cookie_domains: list[str]
    steps: list[dict[str, Any]] = field(default_factory=list)
    ttl_seconds: int = 3600

    @classmethod
    def load(cls, profiles_dir: Path, name: str) -> "Profile":
        path = profiles_dir / f"{name}.yml"
        if not path.exists():
            raise FileNotFoundError(f"profile not found: {path}")
        raw = yaml.safe_load(path.read_text())
        if not isinstance(raw, dict):
            raise ValueError(f"{path}: expected a YAML mapping at top level")
        for required in ("start_url", "cookie_domains"):
            if required not in raw:
                raise ValueError(f"{path}: missing required field {required!r}")
        return cls(
            name=name,
            start_url=raw["start_url"],
            cookie_domains=list(raw["cookie_domains"]),
            steps=list(raw.get("steps") or []),
            ttl_seconds=int(raw.get("ttl_seconds", 3600)),
        )

    def cookie_matches(self, cookie_domain: str) -> bool:
        # Cookie domains are often leading-dot (e.g. ".tesla.com"); substring
        # match handles both ".tesla.com" and "tesla.com" without caring.
        cd = cookie_domain.lstrip(".")
        for want in self.cookie_domains:
            if cd.endswith(want.lstrip(".")):
                return True
        return False
