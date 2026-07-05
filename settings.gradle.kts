plugins {
    // Resolves/auto-provisions JDK toolchains (both the compile toolchain and
    // the daemon JVM pinned in gradle/gradle-daemon-jvm.properties) so the
    // build doesn't depend on a JDK already sitting in ~/.gradle/jdks.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "roadtrip"

include("backend")
