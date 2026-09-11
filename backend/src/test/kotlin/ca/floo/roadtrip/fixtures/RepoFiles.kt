package ca.floo.roadtrip.fixtures

import java.io.File

private const val REPO_ROOT_MARKER = "secrets/registry.yaml"

/** The repo root, found by walking up from the test's working directory. */
internal val repoRoot: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, REPO_ROOT_MARKER).isFile }

internal fun repoFile(path: String): File = File(repoRoot, path).also { check(it.isFile) { "missing repo file $path" } }
