# Backend Docker image. Runtime-only: build the fat jar on the host with
# `./gradlew :backend:shadowJar`, then build this image from the repo root.
FROM eclipse-temurin:21-jre AS backend

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

COPY backend/build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8765

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
