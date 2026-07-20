package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.repo.MAX_CATALOG_UPSERT_BATCH_SIZE
import org.jooq.DSLContext

@Suppress("DataClassContainsFunctions")
data class TerminalEtlBinding<DTO, OUT>(
    val etl: SourceEtl<DTO, OUT>,
    val sink: TerminalSink<OUT>,
) {
    val etlSlug: String get() = etl.etlSlug
    val adapterName: String? get() = etl::class.simpleName

    fun accumulator(): BatchAccumulator<OUT> = BatchAccumulator(sink)
}

interface TerminalSink<OUT> {
    val batchSize: Int

    fun write(records: List<OUT>): FlushCounts
}

data class FlushCounts(
    val upserted: Int = 0,
    val skipped: Int = 0,
)

class BatchAccumulator<OUT>(
    private val sink: TerminalSink<OUT>,
) {
    private val records = mutableListOf<OUT>()

    fun add(record: OUT): FlushCounts {
        records += record
        return if (records.size >= sink.batchSize) flush() else FlushCounts()
    }

    fun flush(): FlushCounts {
        if (records.isEmpty()) return FlushCounts()
        val batch = records.toList()
        records.clear()
        return sink.write(batch)
    }
}

@Suppress("DataClassContainsFunctions")
internal data class TerminalEtlDefinition<DTO, OUT>(
    val etl: SourceEtl<DTO, OUT>,
    private val sinkFactory: (DSLContext) -> TerminalSink<OUT>,
) {
    fun bind(ctx: DSLContext): TerminalEtlBinding<DTO, OUT> = TerminalEtlBinding(etl, sinkFactory(ctx))
}

internal fun <OUT> terminalSink(
    batchSize: Int = MAX_CATALOG_UPSERT_BATCH_SIZE,
    write: (List<OUT>) -> FlushCounts,
): TerminalSink<OUT> {
    require(batchSize > 0) { "terminal sink batch size must be positive" }
    return object : TerminalSink<OUT> {
        override val batchSize: Int = batchSize

        override fun write(records: List<OUT>): FlushCounts = write(records)
    }
}
