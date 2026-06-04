package ca.floo.campsite.recgov.booker.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * SSE event envelope. `id` is monotonically increasing so Last-Event-ID
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
 * In-memory pub/sub for SSE. The replay buffer keeps the last N events so a
 * client reconnecting with `Last-Event-ID: 42` can replay 43..head before
 * subscribing live. 256 is more than the campsite lifecycle ever needs but
 * cheap.
 *
 * On overflow we DROP_OLDEST — losing the very oldest replay history is
 * preferable to backpressuring the publisher (which would stall the poller).
 */
class EventBus(private val replay: Int = 256) {
    private val seq = AtomicLong(0)
    private val flow = MutableSharedFlow<Envelope>(
        replay = replay,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Envelope> = flow.asSharedFlow()

    fun publish(type: String, data: String): Envelope {
        val env = Envelope(id = seq.incrementAndGet(), type = type, data = data)
        flow.tryEmit(env)
        return env
    }

    /** Snapshot of the replay buffer. Used to fill the gap after Last-Event-ID. */
    fun replayBuffer(): List<Envelope> = flow.replayCache
}
