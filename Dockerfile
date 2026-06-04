FROM python:3.12-slim

WORKDIR /app

# Akamai Bot Manager on tesla.com fingerprints the TLS ClientHello + HTTP/2
# SETTINGS frame (JA3/JA4/Akamai-H2). Stock Debian curl (OpenSSL) produces a
# different fingerprint than the Chrome session that minted _abck, so it gets
# 403 even with valid cookies. curl-impersonate-chrome is a curl build linked
# against Chrome's BoringSSL with matching cipher order, extensions, and H2
# settings. Keep stock curl too for the smoke-test/debug paths.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates wget xz-utils \
    && CURL_IMP_VER=0.6.1 \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in \
         amd64)  CURL_IMP_ARCH=x86_64 ;; \
         arm64)  CURL_IMP_ARCH=aarch64 ;; \
         *) echo "unsupported arch: $ARCH" >&2; exit 1 ;; \
       esac \
    && wget -q "https://github.com/lwthiker/curl-impersonate/releases/download/v${CURL_IMP_VER}/curl-impersonate-v${CURL_IMP_VER}.${CURL_IMP_ARCH}-linux-gnu.tar.gz" -O /tmp/ci.tgz \
    && tar -xzf /tmp/ci.tgz -C /usr/local/bin/ \
    && rm /tmp/ci.tgz \
    && apt-get purge -y wget xz-utils \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Copy static site + server
COPY server.py ./
COPY index.html ./
COPY web/ ./web/
COPY data/ ./data/

ENV PORT=8765
ENV HOST=0.0.0.0
ENV PYTHONUNBUFFERED=1
EXPOSE 8765

CMD ["python3", "server.py"]
