package ca.floo.roadtrip.exceptions

class FetchFailedException(
    val exitCode: Int,
    val stderrTail: String,
) : RuntimeException("fetch phase exited $exitCode")
