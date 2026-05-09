# The Playwright base image ships stock Chromium + all system libs, but patchright
# has its own patched Chromium binary that we must install separately via
# `patchright install chromium`. We start from Microsoft's image to get the
# system library baseline for free, then overlay patchright's browser.
FROM mcr.microsoft.com/playwright/python:v1.55.0-jammy

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && patchright install chromium

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
