package ca.floo.roadtrip.service.etl.framework

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DefaultProcessFactory : ProcessFactory {
    override fun start(
        cmd: List<String>,
        workingDir: File,
    ): RunningProcess {
        val pb =
            ProcessBuilder(cmd)
                .directory(workingDir)
                .redirectErrorStream(false)
        val p = pb.start()
        return JdkRunningProcess(p)
    }
}

private class JdkRunningProcess(
    private val process: Process,
) : RunningProcess {
    override fun stdoutStream() = process.inputStream

    override fun stderrStream() = process.errorStream

    override suspend fun awaitExit(): Int =
        withContext(Dispatchers.IO) {
            process.waitFor()
        }

    override fun killTree() {
        // JDK 9+ Process.descendants() reaches grandchildren (curl spawned by
        // python, ffmpeg spawned by curl, etc). destroyForcibly() on each so
        // a hung pipeline can't outlive the timeout.
        runCatching {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
