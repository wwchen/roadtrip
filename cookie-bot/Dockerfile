# Akamai's WAF on tesla.com blocks headless-shell Chromium at the TLS
# fingerprint layer — before any JS runs — so patchright's JS-level
# anti-detection can't help. Real Google Chrome's TLS handshake passes.
# Chrome has no aarch64 Linux build, so we pin the image to linux/amd64
# (Docker Desktop on Apple Silicon runs it via Rosetta 2 emulation; slow
# but fine for an on-demand harvester). patchright's `install chrome`
# pulls the official Chrome stable deb and its deps.
FROM --platform=linux/amd64 mcr.microsoft.com/playwright/python:v1.55.0-jammy

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && patchright install chrome

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
