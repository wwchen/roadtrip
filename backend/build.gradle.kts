// roadtrip-backend — Ktor /api/pois service + standalone importer.
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
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.flywaydb.flyway") version "10.20.1"
    id("nu.studer.jooq") version "9.0"
    // shadowJar produces a single executable fat-jar with all dependencies
    // merged in. The Dockerfile uses this so the runtime image is just
    // eclipse-temurin:21-jre + one .jar.
    id("com.gradleup.shadow") version "8.3.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
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

// Standalone importer entry. Invoke with:
//   ./gradlew importer --args="uscampgrounds"
tasks.register<JavaExec>("importer") {
    group = "application"
    description = "Run the POI importer."
    mainClass.set("ca.floo.roadtrip.importer.ImporterKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Legacy data.json → Postgres migration tool (campsite). Invoke with:
//   ./gradlew campsiteMigrate --args="/path/to/data.json"
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
    classpath = sourceSets["test"].runtimeClasspath
    args = listOf("install", "chromium")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"
val jooqVersion = "3.19.15"
val postgresVersion = "42.7.4"
val flywayVersion = "10.20.1"
val testcontainersVersion = "1.21.4"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    // SSE for /api/campsite/events stream.
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    // HttpClient powers AvailabilityClient (rec.gov) and SlackNotifier in the
    // campsite poller.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    implementation("org.jooq:jooq:$jooqVersion")
    implementation("com.zaxxer:HikariCP:6.1.0")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    jooqGenerator("org.postgresql:postgresql:$postgresVersion")
    jooqGenerator("org.testcontainers:postgresql:$testcontainersVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Playwright JVM drives a real Chromium for SmokeTest. Gated on
    // QA_BASE_URL so normal `gradle test` runs don't pull Chromium.
    testImplementation("com.microsoft.playwright:playwright:1.50.0")
}

kotlin {
    jvmToolchain(21)
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
                        // PostGIS extension adds dozens of objects to public; exclude
                        // them so generated code stays focused on roadtrip-owned tables.
                        excludes = "spatial_ref_sys|geometry_columns|geography_columns|" +
                            "raster_columns|raster_overviews|flyway_schema_history"
                    }
                    generate.apply {
                        isDeprecated = false
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

tasks.named<JooqGenerate>("generateJooq") {
    inputs.files(fileTree("src/main/resources/db/migration"))

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
            .locations("filesystem:src/main/resources/db/migration")
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
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    // Flyway and other ServiceLoader-based libraries register implementations
    // via META-INF/services/. Without merging, the last copy wins and Flyway
    // loses its CoreMigrationTypeResolver, rejecting V_*.sql migrations with
    // "Unrecognised migration name format" at runtime.
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Pass through QA_BASE_URL so SmokeTest's @EnabledIfEnvironmentVariable
    // sees it inside the Gradle test worker JVM. Without this Gradle scrubs
    // env vars and the test silently skips.
    System.getenv("QA_BASE_URL")?.let { environment("QA_BASE_URL", it) }
    // Playwright's JSON reader thread parses large evaluate() / page-event
    // payloads in the worker JVM; default 512m OOMs once the map state grows.
    maxHeapSize = "2g"
}
