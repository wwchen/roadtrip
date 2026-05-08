FROM python:3.12-slim

WORKDIR /app

# curl is required at runtime — the server shells out to curl --http2 for
# Tesla's Akamai-gated findus endpoint. python-urllib3 uses HTTP/1.1 and gets 403.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy static site + server
COPY server.py ./
COPY index.html ./
COPY data/ ./data/

ENV PORT=8765
ENV HOST=0.0.0.0
ENV PYTHONUNBUFFERED=1
EXPOSE 8765

CMD ["python3", "server.py"]
