// roadtrip-backend — Ktor /api/pois service.
//
// jOOQ codegen runs at build time against a Testcontainers Postgres so the
// schema in src/main/resources/db/migration/ is the source of truth, and no
// developer needs Postgres on their host. The generated classes live under
// build/generated/jooq/main and are NOT committed; reproducibility comes
// from pinned versions + the migration files.

import nu.studer.gradle.jooq.JooqEdition
import nu.studer.gradle.jooq.JooqGenerate
import org.flywaydb.core.Flyway
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.testcontainers:postgresql:1.21.4")
        classpath("org.flywaydb:flyway-core:10.20.1")
        classpath("org.flywaydb:flyway-database-postgresql:10.20.1")
        classpath("org.postgresql:postgresql:42.7.4")
    }
}

plugins {
    application
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("org.flywaydb.flyway") version "10.20.1"
    id("nu.studer.jooq") version "9.0"
    // shadowJar produces a single executable fat-jar with all dependencies
    // merged in. The Dockerfile uses this so the runtime image is just
    // eclipse-temurin:25-jre + one .jar.
    id("com.gradleup.shadow") version "8.3.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    // Line/branch coverage. `./gradlew :backend:koverXmlReport` produces the XML the
    // CI job uploads to Codecov.
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

ktlint {
    version.set("1.3.1")
    // jOOQ generates Kotlin under build/generated/jooq. Lint user code only.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

group = "ca.floo.roadtrip"
version = "0.1.0"

application {
    mainClass.set("ca.floo.roadtrip.MainKt")
}

// Legacy data.json → Postgres migration tool (campsite). Invoke with:
//   ./gradlew :backend:campsiteMigrate --args="/path/to/data.json"
tasks.register<JavaExec>("campsiteMigrate") {
    group = "application"
    description = "Migrate legacy campsite data.json into Postgres."
    mainClass.set("ca.floo.campsite.recgov.booker.tools.MigrateKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Idempotent Chromium download for SmokeTest. The Playwright JVM driver
// shells out to `playwright install`, which fetches into ~/Library/Caches
// /ms-playwright (macOS) or ~/.cache/ms-playwright (Linux). Re-running is a
// no-op once the browser is present.
tasks.register<JavaExec>("installPlaywrightBrowsers") {
    group = "verification"
    description = "Download Chromium for the Playwright-driven SmokeTest."
    mainClass.set("com.microsoft.playwright.CLI")
    classpath = sourceSets["smokeTest"].runtimeClasspath
    args = listOf("install", "chromium")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"
val jooqVersion = "3.19.15"
val postgresVersion = "42.7.4"
val flywayVersion = "10.20.1"
val testcontainersVersion = "1.21.4"
val bucket4jVersion = "8.10.1"
val timeshapeVersion = "2025b.26"
val resendVersion = "4.13.0"
val junitVersion = "5.11.3"
val playwrightVersion = "1.50.0"

// Isolated source set (src/smokeTest/kotlin) for the Playwright-driven
// SmokeTest. It compiles against main output only — not the ~60-class `test`
// source set — so the CI smoke job compiles two classes instead of everything
// just to launch a browser. Declared before `dependencies` so the generated
// smokeTestImplementation / smokeTestRuntimeOnly configurations exist.
sourceSets {
    create("smokeTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    // SSE for /api/campsite/events stream.
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    // HttpClient powers AvailabilityClient (rec.gov) and SlackNotifier in the
    // campsite poller.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    // JSON log encoder that emits the fully-formatted message (SLF4J {}
    // placeholders interpolated). Replaces Logback's built-in JsonEncoder,
    // which logged the raw pattern + a separate arguments array. 8.x targets
    // logback 1.5.x / Java 11+.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Self-documenting /api/docs at runtime — Swagger UI + OpenAPI 3.1 spec
    // built from the live routing tree. Compatible with Ktor 3.0.x.
    // Issue #47.
    implementation("io.github.smiley4:ktor-swagger-ui:4.1.7")

    implementation("org.jooq:jooq:$jooqVersion")
    implementation("com.zaxxer:HikariCP:6.1.0")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Durable, Postgres-backed per-vendor token bucket (the fetch governor).
    // Verified against Maven Central: bucket4j-core 8.10.1 provides the
    // BucketConfiguration/BandwidthBuilder + distributed proxy API;
    // bucket4j-postgresql 8.10.1 provides PostgreSQLSelectForUpdateBasedProxyManager.
    implementation("com.bucket4j:bucket4j-core:$bucket4jVersion")
    implementation("com.bucket4j:bucket4j-postgresql:$bucket4jVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.9.0")
    // YAML reader for the POI registry resource.
    implementation("com.charleskorn.kaml:kaml:0.74.0")
    // Coordinate -> IANA ZoneId lookup from timezone-boundary-builder data.
    implementation("net.iakovlev:timeshape:$timeshapeVersion")
    // Transactional email delivery for availability watch alerts.
    implementation("com.resend:resend-java:$resendVersion")

    jooqGenerator("org.postgresql:postgresql:$postgresVersion")
    jooqGenerator("org.testcontainers:postgresql:$testcontainersVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    // MockEngine lets SlackNotifier / AvailabilityClient tests assert request
    // shape without hitting the network.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Playwright-driven SmokeTest lives in its own `smokeTest` source set (see
    // below), so the main `test` compile + classpath stay free of Playwright
    // and the CI smoke job compiles two classes instead of the whole suite.
    "smokeTestImplementation"(kotlin("test"))
    "smokeTestImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
    "smokeTestImplementation"("com.microsoft.playwright:playwright:$playwrightVersion")
    "smokeTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

jooq {
    version.set(jooqVersion)
    edition.set(JooqEdition.OSS)

    configurations {
        create("main") {
            // true = compileKotlin depends on generateJooq, so a clean build
            // (CI, fresh clone) regenerates the classes from the Flyway
            // migrations. The codegen task already declares the migration
            // files as inputs, so Gradle skips it when nothing changed.
            generateSchemaSourceOnCompilation.set(true)
            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        // PostGIS extension adds dozens of objects to public; include
                        // only roadtrip-owned schema objects so generated code stays
                        // focused and clean builds do not emit PostGIS routine wrappers.
                        includes =
                            listOf(
                                "alerts",
                                "api_cache",
                                "availability",
                                "availability_fetch_call",
                                "availability_poller",
                                "availability_run",
                                "availability_status",
                                "availability_watch",
                                "availability_watch_poller",
                                "availability_watch_target",
                                "booking_provider",
                                "campground_vendor_refs",
                                "campgrounds",
                                "campsite_vendor_refs",
                                "campsites",
                                "governing_body",
                                "import_runs",
                                "ingest_runs",
                                "matches",
                                "planet_fitness_locations",
                                "poi_campgrounds",
                                "poi_planet_fitness_locations",
                                "poi_tesla_superchargers",
                                "pois",
                                "reservable_availability_log",
                                "reservable_availability_monitors",
                                "schedules",
                                "settings",
                                "tesla_superchargers",
                                "vendor_refs",
                            ).joinToString("|")
                        excludes = "spatial_ref_sys|geometry_columns|geography_columns|" +
                            "raster_columns|raster_overviews|flyway_schema_history"
                    }
                    generate.apply {
                        isDeprecated = false
                        isRoutines = false
                        isRecords = true
                        isImmutablePojos = false
                        isFluentSetters = true
                    }
                    target.apply {
                        packageName = "ca.floo.roadtrip.db.generated"
                        directory = "${project.layout.buildDirectory.get().asFile}/generated/jooq/main"
                    }
                }
            }
        }
    }
}

// Ephemeral Postgres + Flyway migrate before codegen reads the schema.
// doFirst mutates jdbc once Testcontainers has a real port; doLast tears it down.
val jooqContainerKey = "jooqPgContainer"
val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration")
val migrationDirPath = migrationDir.asFile.absolutePath

tasks.named<JooqGenerate>("generateJooq") {
    inputs.files(fileTree(migrationDir.asFile))

    doFirst {
        // Docker Desktop 29+ requires API version >=1.44; older docker-java defaults
        // to 1.32 and the daemon's docker-cli.sock returns sanitized info that
        // can't be parsed. Pin the API version before any Testcontainers call.
        System.setProperty("api.version", "1.44")

        // postgis/postgis is a postgres derivative; Testcontainers won't auto-detect
        // wait-for-readiness without the explicit compatibility hint.
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        val container =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_codegen")
                withUsername("codegen")
                withPassword("codegen")
            }
        container.start()
        project.extra.set(jooqContainerKey, container)

        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("filesystem:$migrationDirPath")
            .load()
            .migrate()

        val cfg = jooq.configurations.getByName("main").jooqConfiguration
        cfg.jdbc.url = container.jdbcUrl
        cfg.jdbc.user = container.username
        cfg.jdbc.password = container.password
    }

    doLast {
        @Suppress("UNCHECKED_CAST")
        val container = project.extra.get(jooqContainerKey) as? PostgreSQLContainer<*>
        container?.stop()
    }
}

flyway {
    url = (project.findProperty("flyway.url") as String?)
        ?: "jdbc:postgresql://localhost:5432/roadtrip"
    user = (project.findProperty("flyway.user") as String?) ?: "roadtrip"
    password = (project.findProperty("flyway.password") as String?) ?: "roadtrip"
    locations = arrayOf("filesystem:$migrationDirPath")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("roadtrip-backend")
    // Flyway and other ServiceLoader-based libraries register implementations
    // via META-INF/services/. Without merging, the last copy wins and Flyway
    // loses its CoreMigrationTypeResolver, rejecting V_*.sql migrations with
    // "Unrecognised migration name format" at runtime.
    mergeServiceFiles()
}

// Exclude generated jOOQ classes and application entrypoints/composition from
// coverage so the number reflects code we actually own + can test.
kover {
    reports {
        filters {
            excludes {
                packages("ca.floo.roadtrip.db.generated", "ca.floo.roadtrip.db.generated.*")
                classes(
                    "ca.floo.roadtrip.MainKt",
                    "ca.floo.roadtrip.RoadtripBootContext",
                    "ca.floo.roadtrip.RoadtripRuntime",
                    "ca.floo.roadtrip.RoadtripRuntimeKt",
                    "ca.floo.roadtrip.RoadtripRoutingKt*",
                    "ca.floo.campsite.recgov.booker.tools.*",
                )
            }
        }
        // Keep this floor slightly below measured line coverage so it catches
        // real drift without making every small cleanup PR a coverage chore.
        verify {
            rule {
                minBound(45)
            }
        }
    }
}

// koverVerify is invoked explicitly by the backend-tests CI step
// (`./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify`). It is NOT a finalizer of
// `test` because the smoke CI job runs only Playwright suites with no
// instrumented coverage, which would (correctly) report 0% and fail the
// floor — but that's a meaningless signal there.

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Headroom for the parallel test workers (classes run concurrently in one
    // JVM — see src/test/resources/junit-platform.properties).
    maxHeapSize = "4g"
}

// Playwright-driven end-to-end smoke against an already-running backend. Opt-in
// (not wired into `check`) — it needs QA_BASE_URL pointing at a live server, and
// runs the small `smokeTest` source set rather than the full `test` suite.
val smokeTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Playwright smoke test against a running backend (set QA_BASE_URL)."
    testClassesDirs = sourceSets["smokeTest"].output.classesDirs
    classpath = sourceSets["smokeTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Pass through QA_BASE_URL so SmokeTest's @EnabledIfEnvironmentVariable sees
    // it inside the Gradle test worker JVM; without this Gradle scrubs env vars
    // and the test silently skips.
    System.getenv("QA_BASE_URL")?.let { environment("QA_BASE_URL", it) }
    // Playwright's JSON reader thread parses large evaluate()/page-event
    // payloads in the worker JVM; default 512m OOMs once the map state grows.
    maxHeapSize = "4g"
}
