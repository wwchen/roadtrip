package ca.floo.roadtrip.exceptions

class TargetBusyException(
    val target: String,
    val runningRunId: Long,
) : RuntimeException("target=$target is already running as run_id=$runningRunId")
