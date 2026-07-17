package ca.floo.roadtrip.service.availability

/**
 * Dispatch table matching a watch's `trigger_kinds` to [TriggerActionHandler]s.
 * A handler may cover multiple trigger kinds; [forKinds] de-duplicates handlers
 * so an aggregate handler, such as notifications, fires once per watch alert.
 */
internal class TriggerActionRegistry(
    handlers: List<TriggerActionHandler>,
) {
    private val byKind: Map<String, TriggerActionHandler> =
        handlers
            .flatMap { handler -> handler.kinds.map { kind -> kind to handler } }
            .toMap()

    init {
        val registeredKinds = handlers.flatMap { it.kinds }
        require(registeredKinds.size == registeredKinds.toSet().size) {
            "duplicate handler kinds in TriggerActionRegistry: " +
                registeredKinds.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }

    /** Unknown/absent kinds are inert; repeated handlers are returned once. */
    fun forKinds(kinds: List<String>): List<TriggerActionHandler> =
        kinds
            .mapNotNull(byKind::get)
            .distinct()
}
