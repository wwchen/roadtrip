"""FastAPI HTTP service: profile harvest + cookie retrieval.

Endpoints:
  GET  /health                         -> 200 if alive
  GET  /cookies/{profile}?format=...   -> cached cookies; harvests on miss/expiry
  POST /refresh/{profile}              -> force a harvest, bypass cache

Auth: optional shared-secret via COOKIE_BOT_TOKEN. When set, callers must
send `Authorization: Bearer <token>` or `X-Cookie-Bot-Token: <token>`.
"""
from __future__ import annotations
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request

from . import cache
from .browser import BrowserPool
from .profile import Profile

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("cookie-bot")

PROFILES_DIR = Path(os.environ.get("PROFILES_DIR", "profiles"))
CACHE_DIR = Path(os.environ.get("CACHE_DIR", "/var/cache/cookie-bot"))
BROWSER_PROFILE_DIR = Path(os.environ.get("BROWSER_PROFILE_DIR", CACHE_DIR / "chromium-profile"))
AUTH_TOKEN = os.environ.get("COOKIE_BOT_TOKEN", "").strip()

_pool: BrowserPool | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _pool
    _pool = BrowserPool(BROWSER_PROFILE_DIR)
    logger.info("cookie-bot ready (profiles=%s, cache=%s)", PROFILES_DIR, CACHE_DIR)
    try:
        yield
    finally:
        if _pool is not None:
            await _pool.close()


app = FastAPI(lifespan=lifespan)


def _check_auth(request: Request) -> None:
    if not AUTH_TOKEN:
        return
    presented = (
        request.headers.get("x-cookie-bot-token")
        or (request.headers.get("authorization", "").removeprefix("Bearer ").strip() or None)
    )
    if presented != AUTH_TOKEN:
        raise HTTPException(status_code=401, detail="unauthorized")


def _load_profile(name: str) -> Profile:
    try:
        return Profile.load(PROFILES_DIR, name)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"unknown profile: {name}")
    except ValueError as e:
        raise HTTPException(status_code=500, detail=f"bad profile: {e}")


async def _harvest(prof: Profile) -> dict:
    if _pool is None:
        raise HTTPException(status_code=503, detail="browser not ready")
    try:
        cookies = await _pool.harvest(prof)
    except Exception as e:
        logger.exception("harvest failed")
        raise HTTPException(status_code=502, detail=f"harvest failed: {e}")
    entry = cache.write(CACHE_DIR, prof.name, cookies)
    logger.info("harvest %s: %d cookies", prof.name, len(cookies))
    return entry


def _render(entry: dict, fmt: str) -> dict | str:
    cookies = entry["cookies"]
    if fmt == "header":
        return {"cookie_header": cache.as_header(cookies), "harvested_at": entry["harvested_at"], "count": len(cookies)}
    if fmt == "json":
        return {"cookies": cookies, "harvested_at": entry["harvested_at"]}
    raise HTTPException(status_code=400, detail=f"unknown format: {fmt}")


@app.get("/health")
async def health():
    return {"ok": True}


@app.get("/cookies/{name}")
async def get_cookies(name: str, request: Request, format: str = Query("header")):
    _check_auth(request)
    prof = _load_profile(name)
    entry = cache.read(CACHE_DIR, name)
    if entry is None or not cache.is_fresh(entry, prof.ttl_seconds):
        entry = await _harvest(prof)
    return _render(entry, format)


@app.post("/refresh/{name}")
async def refresh(name: str, request: Request, format: str = Query("header")):
    _check_auth(request)
    prof = _load_profile(name)
    entry = await _harvest(prof)
    return _render(entry, format)
