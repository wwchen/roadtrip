# Backend Docker image. Runtime-only: build the fat jar on the host with
# `./gradlew :backend:buildFatJar`, then build this image from the repo root.
FROM eclipse-temurin:25-jre AS backend

WORKDIR /app

ARG OTEL_JAVAAGENT_VERSION=2.29.0

ADD "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
    /app/opentelemetry-javaagent.jar

ENV JAVA_OPTS="-Xmx3g -XX:+UseG1GC -javaagent:/app/opentelemetry-javaagent.jar"
ENV OTEL_TRACES_EXPORTER=none
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
