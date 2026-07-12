package ca.floo.roadtrip.service.etl.framework

import java.io.File

// Indirection to make process spawning testable without forking real procs.
interface ProcessFactory {
    fun start(
        cmd: List<String>,
        workingDir: File,
    ): RunningProcess
}
