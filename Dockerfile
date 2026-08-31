FROM eclipse-temurin:25-jre AS backend-base

WORKDIR /app

LABEL ca.floo.roadtrip.managed="true" \
      ca.floo.roadtrip.component="backend"

ARG OTEL_JAVAAGENT_VERSION=2.29.0
# sha256 of the v2.29.0 release asset. Bumping the version above without
# updating this digest fails the build, which is the point: the agent runs
# in-process with the app, so the tag alone is not enough to pin what we ship.
ARG OTEL_JAVAAGENT_SHA256=546531ca690a8603d2923b6db26bbda35c6409327b1e610430ae33c2f8f68050

ADD --checksum=sha256:${OTEL_JAVAAGENT_SHA256} \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
    /app/opentelemetry-javaagent.jar

ENV JAVA_OPTS="-Xmx3g -XX:+UseG1GC -javaagent:/app/opentelemetry-javaagent.jar"
ENV OTEL_TRACES_EXPORTER=none
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# Local Compose bind-mounts frontend/dist over the image's static assets. This
# target lets Tilt build from only the backend jar without sending frontend/
# in its filtered Docker context.
FROM backend-base AS backend-local

FROM node:22-alpine AS frontend

WORKDIR /src/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# Production and sandbox images include the frontend build in the image.
FROM backend-base AS backend

COPY --from=frontend /src/frontend/dist /app/static/frontend/dist
