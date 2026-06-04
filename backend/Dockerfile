# Backend Docker image. Runtime-only — the fat-jar is built on the host with
# `gradle shadowJar` (Testcontainers-driven jOOQ codegen needs Docker, which
# is painful to nest). Makefile runs the gradle build before `docker compose
# up` so this image is reproducible from the committed source + jar.
FROM eclipse-temurin:21-jre

WORKDIR /app

# JVM container ergonomics. UseContainerSupport is on by default in 21 but
# we set MaxRAMPercentage explicitly so Hikari + Netty don't eat the whole
# container if the host has lots of RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

# If this COPY fails with "no source files were specified", the fat-jar
# wasn't built — run `make backend-build` (or `cd backend && gradle shadowJar`)
# before `docker compose up`. Makefile's `docker-run` / `deploy-local`
# handle this automatically.
COPY build/libs/roadtrip-backend-*-all.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
