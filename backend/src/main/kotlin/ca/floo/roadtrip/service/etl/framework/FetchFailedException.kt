package ca.floo.roadtrip.service.etl.framework

class FetchFailedException(
    val exitCode: Int,
    val stderrTail: String,
) : RuntimeException("fetch phase exited $exitCode")
