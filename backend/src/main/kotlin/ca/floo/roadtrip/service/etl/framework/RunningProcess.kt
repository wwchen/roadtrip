package ca.floo.roadtrip.service.etl.framework

interface RunningProcess {
    fun stdoutStream(): java.io.InputStream

    fun stderrStream(): java.io.InputStream

    suspend fun awaitExit(): Int

    fun killTree()
}
