# Akamai's WAF on tesla.com blocks Playwright's bundled headless-shell at
# the TLS fingerprint layer. Real Google Chrome has no aarch64 Linux
# build and emulating amd64 via qemu/Rosetta crashes Chrome. Debian's
# `chromium` package is a native aarch64 full Chromium build (not the
# headless-shell variant) whose TLS fingerprint differs enough that
# Akamai lets it through.
FROM python:3.12-slim-bookworm

WORKDIR /app

# Chromium + the minimal runtime libs it needs. Debian's chromium package
# pulls in X11/GTK deps; we don't need a display server because Chromium
# has --headless=new which runs headlessly but produces the full-browser
# fingerprint (unlike the separate headless-shell binary).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        chromium \
        fonts-liberation \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Tell patchright to use the system chromium rather than its bundled one.
# (patchright respects PLAYWRIGHT_BROWSERS_PATH / executable_path overrides.)
ENV SYSTEM_CHROMIUM=/usr/bin/chromium

COPY src/ ./src/
COPY profiles/ ./profiles/

ENV PYTHONUNBUFFERED=1 \
    PORT=8080 \
    HOST=0.0.0.0 \
    PROFILES_DIR=/app/profiles \
    CACHE_DIR=/var/cache/cookie-bot \
    BROWSER_PROFILE_DIR=/var/cache/cookie-bot/chromium-profile

EXPOSE 8080

CMD ["python", "-m", "uvicorn", "src.server:app", "--host", "0.0.0.0", "--port", "8080"]
