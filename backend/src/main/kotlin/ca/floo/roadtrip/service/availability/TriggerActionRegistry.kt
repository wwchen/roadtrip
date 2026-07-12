package ca.floo.roadtrip.service.availability

/**
 * Dispatch table matching a watch's `trigger_kinds` to their [TriggerActionHandler]s.
 * A kind with no registered handler resolves to `null` (inert — the current
 * `atc` behavior). Composed once at runtime startup.
 */
internal class TriggerActionRegistry(
    handlers: List<TriggerActionHandler>,
) {
    private val byKind: Map<String, TriggerActionHandler> = handlers.associateBy { it.kind }

    init {
        require(handlers.size == byKind.size) {
            "duplicate handler kinds in TriggerActionRegistry: " +
                handlers.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        }
    }

    /** `null` == inert (current `atc` behavior for unknown/absent handlers). */
    fun forKind(kind: String): TriggerActionHandler? = byKind[kind]
}
