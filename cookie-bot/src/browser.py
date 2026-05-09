"""Browser session + profile-step executor.

patchright is a drop-in replacement for playwright.async_api that patches out
the automation fingerprints Chromium normally exposes (navigator.webdriver,
chrome.runtime, the automation CDP flag, permissions API quirks). We still
add our own navigator spoofing init script as belt-and-suspenders.

A single persistent context is reused across runs: it lives under
BROWSER_PROFILE_DIR so cookies accumulate naturally, avoiding a cold _abck
challenge on every harvest. We only recreate on startup if the dir is missing.
"""
from __future__ import annotations
import asyncio
import logging
import os
from pathlib import Path
from typing import Any

from patchright.async_api import async_playwright, BrowserContext, Page

from .profile import Profile

logger = logging.getLogger(__name__)

INIT_SCRIPT = """
// navigator.webdriver — patchright already strips this, but harmless to confirm.
Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
// Some Akamai checks look for plugin count > 0; Chromium headless returns [].
if (!navigator.plugins || navigator.plugins.length === 0) {
  Object.defineProperty(navigator, 'plugins', {
    get: () => [1, 2, 3, 4, 5].map(i => ({ name: `Plugin ${i}`, filename: '', description: '' })),
  });
}
// navigator.languages — headless returns []
if (!navigator.languages || navigator.languages.length === 0) {
  Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
}
// window.chrome — absent in headless, present in real Chrome
if (!window.chrome) {
  window.chrome = { runtime: {}, loadTimes: () => ({}), csi: () => ({}) };
}
// Permissions API — headless returns 'denied' for notifications; real returns 'default'
const origQuery = navigator.permissions && navigator.permissions.query;
if (origQuery) {
  navigator.permissions.query = (p) =>
    p.name === 'notifications' ? Promise.resolve({ state: Notification.permission }) : origQuery(p);
}
"""


class BrowserPool:
    """Single persistent Chromium context, lazily launched, serialized access."""

    def __init__(self, profile_dir: Path):
        self._profile_dir = profile_dir
        self._playwright = None
        self._context: BrowserContext | None = None
        self._lock = asyncio.Lock()

    async def _ensure(self) -> BrowserContext:
        if self._context is not None:
            return self._context
        self._profile_dir.mkdir(parents=True, exist_ok=True)
        self._playwright = await async_playwright().start()
        # channel="chrome" uses the real Google Chrome binary patchright
        # installs via `patchright install chrome`. Patchright docs recommend
        # it over "chromium" for stealth — real Chrome's TLS fingerprint is
        # what Akamai whitelists. Passing no_viewport / omitting viewport and
        # not forcing user_agent lets the binary's own values through
        # (patchright README calls this out explicitly).
        self._context = await self._playwright.chromium.launch_persistent_context(
            user_data_dir=str(self._profile_dir),
            channel="chrome",
            headless=os.environ.get("HEADLESS", "true").lower() != "false",
            no_viewport=True,
            args=["--no-sandbox"],  # required when running as root in container
        )
        await self._context.add_init_script(INIT_SCRIPT)
        return self._context

    async def close(self) -> None:
        if self._context is not None:
            await self._context.close()
            self._context = None
        if self._playwright is not None:
            await self._playwright.stop()
            self._playwright = None

    async def harvest(self, prof: Profile) -> list[dict[str, Any]]:
        """Run a profile's steps and return its cookies (scoped by cookie_domains)."""
        async with self._lock:
            ctx = await self._ensure()
            page = await ctx.new_page()
            try:
                logger.info("harvest %s: navigate %s", prof.name, prof.start_url)
                await page.goto(prof.start_url, wait_until="domcontentloaded", timeout=30_000)
                for step in prof.steps:
                    await _run_step(page, step)
                all_cookies = await ctx.cookies()
                return [c for c in all_cookies if prof.cookie_matches(c.get("domain", ""))]
            finally:
                await page.close()


async def _run_step(page: Page, step: dict[str, Any]) -> None:
    """Execute one profile step. Unknown/failed steps log and continue."""
    if "wait_for" in step:
        state = step["wait_for"]
        await page.wait_for_load_state(state, timeout=30_000)
    elif "wait_ms" in step:
        await asyncio.sleep(int(step["wait_ms"]) / 1000.0)
    elif "click" in step:
        await _try_click(page, [step["click"]])
    elif "click_any" in step:
        await _try_click(page, list(step["click_any"]))
    elif "scroll_to" in step:
        await page.evaluate(f"window.scrollTo(0, {int(step['scroll_to'])})")
    elif "eval" in step:
        await page.evaluate(step["eval"])
    else:
        logger.warning("unknown step %r", step)


async def _try_click(page: Page, selectors: list[str]) -> None:
    for sel in selectors:
        try:
            # short timeout — if the selector isn't there, fall through to next
            await page.locator(sel).first.click(timeout=3_000)
            logger.info("clicked %s", sel)
            return
        except Exception as e:
            logger.debug("click %s failed: %s", sel, e)
    logger.warning("no selector matched: %s", selectors)
