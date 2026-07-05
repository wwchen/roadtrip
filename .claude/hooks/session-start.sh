#!/bin/bash
# SessionStart hook for Claude Code on the web.
#
# The backend build pins a JDK 25 toolchain (`jvmToolchain(25)` in
# backend/build.gradle.kts) and Gradle 9.5.1 (gradle/wrapper). Web-session
# containers ship only JDK 21, and the foojay toolchain resolver can't
# auto-provision a JDK (api.foojay.io is blocked by the egress policy). This
# hook installs JDK 25 from apt and points Gradle at it with auto-download off,
# so `./gradlew` builds, `ktlintCheck`, and `test` can run.
#
# Idempotent and non-interactive: safe to re-run; skips apt work when JDK 25 is
# already present. Only runs in the remote (web) environment.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK25=/usr/lib/jvm/java-25-openjdk-amd64

# 1. Install JDK 25 (only if missing — the container caches this after the hook).
if [ ! -x "$JDK25/bin/javac" ]; then
  # A stale third-party PPA can make `apt-get update` exit non-zero without
  # affecting the Ubuntu repos that carry openjdk-25, so don't fail on it.
  sudo apt-get update -y || true
  sudo apt-get install -y openjdk-25-jdk-headless
fi

# 2. Make JDK 25 the JVM for the session's tools.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export JAVA_HOME=$JDK25" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=$JDK25/bin:\$PATH" >> "$CLAUDE_ENV_FILE"
fi

# 3. Register the toolchain and disable auto-provisioning so Gradle uses the
#    apt-installed JDK 25 instead of reaching for the (blocked) foojay resolver.
mkdir -p "$HOME/.gradle"
PROPS="$HOME/.gradle/gradle.properties"
grep -qs "org.gradle.java.installations.paths" "$PROPS" \
  || echo "org.gradle.java.installations.paths=$JDK25" >> "$PROPS"
grep -qs "org.gradle.java.installations.auto-download" "$PROPS" \
  || echo "org.gradle.java.installations.auto-download=false" >> "$PROPS"

# 4. Warm the Gradle wrapper distribution + dependency cache so the first real
#    build is fast. Gradle 9.5.1's distribution is hosted on GitHub; if the
#    session's egress policy blocks it, log a clear pointer instead of failing
#    the session start.
export JAVA_HOME="$JDK25"
if ! "${CLAUDE_PROJECT_DIR}/gradlew" --project-dir "${CLAUDE_PROJECT_DIR}" --version >/dev/null 2>&1; then
  echo "WARN: could not fetch the Gradle 9.5.1 distribution. It is hosted on GitHub" >&2
  echo "      (gradle/gradle-distributions); this environment's network policy must" >&2
  echo "      allow that download for backend builds to run on the web." >&2
fi
