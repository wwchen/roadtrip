package ca.floo.roadtrip.tools

import ca.floo.roadtrip.config.DbConfig
import ca.floo.roadtrip.db.dataSourceFor
import ca.floo.roadtrip.db.dsl
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.JooqUnitOfWork
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.productionEtlRegistry
import java.io.File
import kotlin.system.exitProcess

private const val JDBC_URL_FLAG = "--jdbc-url"
private const val DB_USER_FLAG = "--db-user"
private const val DB_PASSWORD_FLAG = "--db-password"
private const val RAW_DIR_FLAG = "--raw-dir"
private const val STATIC_DIR_FLAG = "--static-dir"
private const val TARGET_FLAG = "--target"
private const val SOURCE_FLAG = "--source"
private const val PASSES_FLAG = "--passes"

private const val DEFAULT_PASSES = 1

private const val DEFAULT_DB_USER = "roadtrip"
private const val DEFAULT_DB_PASSWORD = "roadtrip"
private const val REGISTRY_RESOURCE = "poi-registry.yaml"

private const val FAILURE_EXIT_CODE = 1
private const val USAGE_EXIT_CODE = 2

private const val USAGE =
    "usage: importCatalog --jdbc-url <url> --raw-dir <dir> --static-dir <dir> " +
        "[--db-user <user>] [--db-password <password>] [--passes <n>] " +
        "[--target <name>]... [--source <slug>]..."

private class UsageError(
    message: String,
) : Exception(message)

private data class Options(
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val rawDir: File,
    val staticDir: File,
    val passes: Int,
    val targets: List<String>,
    val sources: List<String>,
)

private fun parse(args: Array<String>): Options {
    val single = mutableMapOf<String, String>()
    val targets = mutableListOf<String>()
    val sources = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        val value = args.getOrNull(index + 1) ?: throw UsageError("$flag needs a value")
        when (flag) {
            TARGET_FLAG -> targets += value
            SOURCE_FLAG -> sources += value
            JDBC_URL_FLAG, DB_USER_FLAG, DB_PASSWORD_FLAG, RAW_DIR_FLAG, STATIC_DIR_FLAG, PASSES_FLAG ->
                single[flag] = value
            else -> throw UsageError("unknown flag '$flag'")
        }
        index += 2
    }
    val required = { flag: String -> single[flag] ?: throw UsageError("$flag is required") }
    val passesValue = single[PASSES_FLAG]
    val passes =
        if (passesValue == null) {
            DEFAULT_PASSES
        } else {
            passesValue.toIntOrNull()?.takeIf { it >= DEFAULT_PASSES }
                ?: throw UsageError("$PASSES_FLAG must be a positive integer, got '$passesValue'")
        }
    return Options(
        jdbcUrl = required(JDBC_URL_FLAG),
        dbUser = single[DB_USER_FLAG] ?: DEFAULT_DB_USER,
        dbPassword = single[DB_PASSWORD_FLAG] ?: DEFAULT_DB_PASSWORD,
        rawDir = File(required(RAW_DIR_FLAG)),
        staticDir = File(required(STATIC_DIR_FLAG)),
        passes = passes,
        targets = targets,
        sources = sources,
    )
}

private fun slugsByRowName(registry: PoiRegistry): Map<String, List<String>> {
    val out = mutableMapOf<String, List<String>>()
    for (row in registry.poiData) out[row.name] = row.etls.map { it.slug }
    for (row in registry.campsiteData) out[row.name] = row.etls.map { it.slug }
    return out
}

private fun selectedNames(
    registry: PoiRegistry,
    available: Set<String>,
    options: Options,
): List<String> {
    val unknownTargets = options.targets.filterNot { it in available }
    if (unknownTargets.isNotEmpty()) {
        throw UsageError("unknown target(s): ${unknownTargets.joinToString()}; runnable: ${available.joinToString()}")
    }
    val slugs = slugsByRowName(registry)
    val unknownSources = options.sources.filterNot { slug -> slugs.values.any { slug in it } }
    if (unknownSources.isNotEmpty()) {
        throw UsageError("unknown source slug(s): ${unknownSources.joinToString()}")
    }
    return available.filter { name ->
        (options.targets.isEmpty() || name in options.targets) &&
            (options.sources.isEmpty() || slugs[name].orEmpty().any { it in options.sources })
    }
}

fun main(args: Array<String>) {
    val options =
        try {
            parse(args)
        } catch (e: UsageError) {
            System.err.println("${e.message}\n$USAGE")
            exitProcess(USAGE_EXIT_CODE)
        }

    val registry = PoiRegistry.loadResource(REGISTRY_RESOURCE)
    val importTargets = importTargetsFromRegistry(registry)
    val names =
        try {
            selectedNames(registry, importTargets.keys, options)
        } catch (e: UsageError) {
            System.err.println("${e.message}\n$USAGE")
            exitProcess(USAGE_EXIT_CODE)
        }
    if (names.isEmpty()) {
        System.err.println("no runnable targets selected; refusing to report an empty import as success")
        exitProcess(FAILURE_EXIT_CODE)
    }

    val dataSource =
        dataSourceFor(
            DbConfig(jdbcUrl = options.jdbcUrl, user = options.dbUser, password = options.dbPassword),
        )
    dataSource.use { pool ->
        val repos = JooqUnitOfWork(dsl(pool)).autocommit
        val orchestrator =
            EtlOrchestrator(
                importRunRepo = repos.importRuns,
                rawDir = options.rawDir,
                poiRegistry = registry,
                staticDir = options.staticDir,
                etlRegistry = productionEtlRegistry(repos),
            )
        for (pass in 1..options.passes) {
            for (name in names) {
                val phase = importTargets.getValue(name).importPhases.single()
                val section = phase.section
                try {
                    val summary =
                        when (section) {
                            Phase.Import.Section.POI_DATA -> orchestrator.runPoiData(name).toString()
                            Phase.Import.Section.CAMPSITE_DATA -> orchestrator.runCampsiteData(name).toString()
                        }
                    println("pass $pass ${section.rowValue}:$name $summary")
                } catch (e: Exception) {
                    System.err.println(
                        "pass $pass ${section.rowValue}:$name failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                    exitProcess(FAILURE_EXIT_CODE)
                }
            }
        }
    }
}
