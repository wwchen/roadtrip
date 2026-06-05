package ca.floo.campsite.recgov.booker.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Wire envelope for SSE. `id` is monotonically increasing so Last-Event-ID
 * lets a reconnecting client pick up where it dropped off.
 *
 * `type` is the SSE event name (`match`, `claimed`, `result`, `companion_offline`, `tick`).
 * `data` is the JSON payload (already serialized; the SSE writer just passes it through).
 */
data class Envelope(
    val id: Long,
    val type: String,
    val data: String,
)

/**
 * Internal envelope. Carries the typed event for in-process subscribers, plus
 * an optional [Envelope] projection when the event is wire-eligible (i.e.
 * `event.sseType()` is non-null). The same id is used on both sides so SSE
 * Last-Event-ID semantics line up.
 */
data class TypedEnvelope(
    val id: Long,
    val event: CampsiteEvent,
    val wire: Envelope?,
)

/**
 * In-memory pub/sub. Two SharedFlows backed by the same id sequence:
 *
 *   - [events]: legacy [Envelope] flow used by the SSE endpoint and any
 *     existing string-typed subscribers. Replays the last N events for
 *     Last-Event-ID resume.
 *   - [typedEvents]: [TypedEnvelope] flow consumed by lifecycle managers
 *     (TokenManager, AvailabilityManager, ...). Includes internal-only
 *     events that never hit the wire.
 *
 * On overflow we DROP_OLDEST — losing the very oldest replay history is
 * preferable to backpressuring the publisher (which would stall the poller).
 */
class EventBus(
    private val replay: Int = 256,
) {
    private val seq = AtomicLong(0)

    private val wireFlow =
        MutableSharedFlow<Envelope>(
            replay = replay,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val typedFlow =
        MutableSharedFlow<TypedEnvelope>(
            replay = replay,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<Envelope> = wireFlow.asSharedFlow()
    val typedEvents: SharedFlow<TypedEnvelope> = typedFlow.asSharedFlow()

    /**
     * Typed publish. Internal subscribers always see the [TypedEnvelope];
     * SSE clients see the wire [Envelope] only when [CampsiteEvent.sseType]
     * returns non-null.
     */
    fun publish(event: CampsiteEvent): TypedEnvelope {
        val id = seq.incrementAndGet()
        val wire =
            event.sseType()?.let { type ->
                Envelope(id = id, type = type, data = event.sseData())
            }
        val typed = TypedEnvelope(id = id, event = event, wire = wire)
        typedFlow.tryEmit(typed)
        if (wire != null) wireFlow.tryEmit(wire)
        return typed
    }

    /**
     * Legacy string-typed publish. Wraps in [CampsiteEvent.Legacy] so
     * lifecycle-manager subscribers can ignore it cleanly. Will be removed
     * once all callers move to typed events.
     */
    fun publish(
        type: String,
        data: String,
    ): Envelope {
        val typed = publish(CampsiteEvent.Legacy(type, data))
        return typed.wire!!
    }

    /** Snapshot of the wire replay buffer. Used to fill the gap after Last-Event-ID. */
    fun replayBuffer(): List<Envelope> = wireFlow.replayCache
}
