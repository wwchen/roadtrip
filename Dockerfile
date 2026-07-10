# Backend Docker image. Runtime-only: build the fat jar on the host with
# `./gradlew :backend:shadowJar`, then build this image from the repo root.
FROM eclipse-temurin:25-jre AS backend

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-yaml curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="-Xmx1536m -XX:+UseG1GC"

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765 8766

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
