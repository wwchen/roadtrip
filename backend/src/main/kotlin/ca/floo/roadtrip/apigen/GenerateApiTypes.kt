package ca.floo.roadtrip.apigen

import java.io.File
import kotlin.system.exitProcess

private const val CHECK_FLAG = "--check"
private const val USAGE = "usage: GenerateApiTypes <path/to/api-types.ts> [--check]"
private const val DRIFT_EXIT_CODE = 1

/**
 * Writes the generated TypeScript, or — with `--check` — compares it against
 * what is committed and fails when they differ. `:backend:generateApiTypes` and
 * `:backend:checkApiTypes` are the two Gradle faces of this one entry point.
 */
fun main(args: Array<String>) {
    val target = args.firstOrNull { it != CHECK_FLAG } ?: error(USAGE)
    val generated = generateApiTypes()
    val file = File(target)
    if (!args.contains(CHECK_FLAG)) {
        file.parentFile?.mkdirs()
        file.writeText(generated)
        println("wrote ${file.path}")
        return
    }
    if (file.exists() && file.readText() == generated) {
        println("${file.path} is up to date")
        return
    }
    System.err.println(
        "$target is stale: the committed API types no longer match the Kotlin DTOs. " +
            "Run `make api-types` and commit the diff.",
    )
    exitProcess(DRIFT_EXIT_CODE)
}
