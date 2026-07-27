package ca.floo.roadtrip.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ConfigLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Makes mounted secrets visible to Ktor's config, then refuses to boot without
 * the ones this environment requires.
 *
 * Docker Compose mounts each secret at `/run/secrets/<lowercase-name>` (see
 * `docker-compose.secrets.yml`, generated from `secrets/registry.yaml`). Ktor
 * resolves `${MAPBOX_TOKEN:}` placeholders in `application.yaml` against
 * environment variables and system properties, so this copies each file into a
 * system property before the config is parsed. Values therefore never become
 * environment variables and are absent from `docker inspect` and
 * `/proc/<pid>/environ`.
 *
 * Run from [ca.floo.roadtrip.main] before `EngineMain.main`, which is the last
 * point at which the config has not yet been read.
 *
 * On a host-run backend (`make run`, or the tests) there is no `/run/secrets`;
 * the values arrive as environment variables from `secrets/manage.py exec` and
 * hydration is a no-op. Validation covers both sources.
 *
 * Why validate here rather than let each feature degrade: every secret-backed
 * feature in this codebase disables itself silently when its value is missing —
 * Slack logs a warning and no-ops, auth simply never registers its routes. That
 * is the right behaviour in local development and a terrible one in production,
 * where the first symptom is a user who cannot sign in. `required_in` in the
 * registry marks which secrets stop being optional in which environment.
 */
object SecretsBootstrap {
    /** Where Compose mounts secrets. Overridable for tests. */
    const val DEFAULT_SECRETS_DIR = "/run/secrets"

    private const val REGISTRY_RESOURCE = "secrets-registry.yaml"
    private const val REQUIRED_IN_KEY = "required_in"
    private const val CONSUMERS_KEY = "consumers"

    /** This process's name in the registry's `consumers` lists. */
    private const val SELF = "backend"
    private const val PROFILE_ENV = "ROADTRIP_PROFILE"
    private const val DEFAULT_PROFILE = "local"

    private val log = org.slf4j.LoggerFactory.getLogger(SecretsBootstrap::class.java)

    /**
     * Hydrate system properties from mounted secret files, then validate.
     *
     * @throws MissingSecretsException listing every missing secret at once, so a
     * misconfigured deploy is one fix rather than one restart per secret.
     */
    fun run(
        secretsDir: Path = Path.of(DEFAULT_SECRETS_DIR),
        env: Map<String, String> = System.getenv(),
        classLoader: ClassLoader = SecretsBootstrap::class.java.classLoader,
    ) {
        val hydrated = hydrate(secretsDir)
        if (hydrated.isNotEmpty()) {
            // Names only. The values are the thing we are protecting.
            log.info("loaded {} secret(s) from {}: {}", hydrated.size, secretsDir, hydrated.sorted())
        }
        validate(profileOf(env), requiredByProfile(classLoader), env)
    }

    /**
     * Copy `<dir>/<name>` into the system property `NAME`.
     *
     * Returns the property names set. A missing directory is normal (host runs)
     * and yields an empty list.
     */
    fun hydrate(secretsDir: Path): List<String> {
        if (!Files.isDirectory(secretsDir)) return emptyList()
        return Files.list(secretsDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { path ->
                    val name = path.fileName.toString().uppercase()
                    // A trailing newline is an artefact of however the file was
                    // written, never part of a credential; anything else is
                    // preserved byte for byte because tokens and cookie headers
                    // can contain spaces, quotes and '='.
                    val value = Files.readString(path).removeSuffix("\n")
                    // An explicit environment variable wins, so a one-off
                    // `MAPBOX_TOKEN=… make run` still overrides the mount.
                    if (System.getenv(name) == null) System.setProperty(name, value)
                    name
                }.toList()
        }
    }

    /**
     * Secret name -> environments in which it is mandatory *for the backend*.
     *
     * Filtered by `consumers`, not just `required_in`: the registry also covers
     * secrets only Grafana, Postgres or cloudflared receive. Those are genuinely
     * required in production, but they are never mounted into this container, so
     * validating them here would fail a correctly configured deploy. Each
     * consumer answers for its own.
     */
    fun requiredByProfile(classLoader: ClassLoader = SecretsBootstrap::class.java.classLoader): Map<String, Set<String>> {
        val registry: ApplicationConfig =
            classLoader.getResource(REGISTRY_RESOURCE)?.let {
                withContextClassLoader(classLoader) { ConfigLoader.load(REGISTRY_RESOURCE) }
            } ?: throw IllegalStateException(
                "$REGISTRY_RESOURCE not found on the classpath. It is copied from " +
                    "secrets/registry.yaml by the backend's processResources task.",
            )
        return registry
            .toMap()
            .mapNotNull { (name, entry) ->
                val fields = entry as? Map<*, *> ?: return@mapNotNull null
                if (SELF !in stringList(fields[CONSUMERS_KEY])) return@mapNotNull null
                name to stringList(fields[REQUIRED_IN_KEY]).toSet()
            }.toMap()
    }

    private fun validate(
        profile: String,
        requiredByProfile: Map<String, Set<String>>,
        env: Map<String, String>,
    ) {
        val missing =
            requiredByProfile
                .filterValues { profile in it }
                .keys
                .filter { resolve(it, env).isNullOrBlank() }
                .sorted()
        if (missing.isEmpty()) return
        throw MissingSecretsException(profile, missing)
    }

    /**
     * Blank counts as missing on purpose: `ConfigSection.value()` already maps
     * an empty string to null, so a secret set to "" disables its feature just
     * as thoroughly as one that was never set.
     */
    private fun resolve(
        name: String,
        env: Map<String, String>,
    ): String? = env[name] ?: System.getProperty(name)

    private fun profileOf(env: Map<String, String>): String = env[PROFILE_ENV]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROFILE

    private fun stringList(raw: Any?): List<String> =
        when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            else -> emptyList()
        }

    private fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}

/** Every secret missing for this profile, so one message covers the whole fix. */
class MissingSecretsException(
    profile: String,
    val missing: List<String>,
) : IllegalStateException(
        buildString {
            append("missing required secret(s) for profile '$profile': ")
            append(missing.joinToString(", "))
            append("\n\nSet them with:  ./secrets/manage.py set <NAME> $profile")
            append("\nInspect with:   ./secrets/manage.py ls")
        },
    )
