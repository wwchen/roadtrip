package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.RunKind
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals

class IngestControllerFailurePersistenceTest : SharedDbTest() {
    @Test
    fun `phase failure still returns failed outcome when failure row persistence is unavailable`() =
        runBlocking {
            val target = Target("t", listOf(Phase.Fetch("step1", listOf("false"))), emptyList())
            val controller =
                IngestController(
                    ctx = ctx,
                    etl = EtlOrchestrator(ctx, File("/tmp"), PoiRegistry(emptyList(), emptyList())),
                    fetchTargets = mapOf("t" to target),
                    importTargets = mapOf("t" to target),
                    workingDir = File("/tmp"),
                    ioDispatcher = Dispatchers.IO,
                    processFactory = ClosingFailureProcessFactory(),
                )

            val outcome = controller.startRun("t", RunKind.FETCH, "test")

            assertEquals("failed", outcome.status)
            assertEquals("step1", outcome.failedPhase)
        }

    private inner class ClosingFailureProcessFactory : ProcessFactory {
        override fun start(
            cmd: List<String>,
            workingDir: File,
        ): RunningProcess = ClosingFailureProcess()
    }

    private inner class ClosingFailureProcess : RunningProcess {
        override fun stdoutStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun stderrStream(): InputStream = ByteArrayInputStream("boom\n".toByteArray())

        override suspend fun awaitExit(): Int {
            ds.close()
            return FETCH_FAILURE_EXIT_CODE
        }

        override fun killTree() {}
    }

    private companion object {
        const val FETCH_FAILURE_EXIT_CODE = 1
    }
}
