package ca.floo.roadtrip.support

/** Depth cap so a self-referential cause chain can't spin or flood a log line. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * Renders a throwable's cause chain into a log *message*.
 *
 * The throwable is also passed to SLF4J, but prod's log pipeline currently
 * drops the `stack_trace` field — so a message that says only the error code
 * leaves no trace of the real fault. Inlining `type: message <- type: message`
 * keeps the diagnosis in the one field that always survives. Shared by the
 * route and service layers so neither has to depend on the other for it.
 */
fun causeChain(e: Throwable): String {
    val parts = mutableListOf<String>()
    val seen = mutableSetOf<Throwable>()
    var t: Throwable? = e
    while (t != null && parts.size < MAX_CAUSE_DEPTH && seen.add(t)) {
        parts += "${t.javaClass.name}: ${t.message}"
        t = t.cause
    }
    return parts.joinToString(" <- ")
}
