package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import kotlin.test.fail

fun <DTO> parsedDto(
    etl: SourceEtl<DTO, *>,
    bundle: InputBundle,
): DTO {
    val ok = mutableListOf<DTO>()
    val bad = mutableListOf<ParseResult.Bad>()
    for (result in etl.parse(bundle)) {
        when (result) {
            is ParseResult.Ok -> ok += result.dto
            is ParseResult.Bad -> bad += result
        }
    }
    if (bad.isNotEmpty()) {
        fail("unexpected parse errors: $bad")
    }
    return ok.single()
}

fun <OUT> records(results: Sequence<TransformResult<OUT>>): List<OUT> {
    val ok = mutableListOf<OUT>()
    val bad = mutableListOf<TransformResult.Bad>()
    for (result in results) {
        when (result) {
            is TransformResult.Ok -> ok += result.record
            is TransformResult.Bad -> bad += result
        }
    }
    if (bad.isNotEmpty()) {
        fail("unexpected transform errors: $bad")
    }
    return ok
}

fun <OUT> okRecords(results: Sequence<TransformResult<OUT>>): List<OUT> =
    results
        .mapNotNull { result ->
            when (result) {
                is TransformResult.Ok -> result.record
                is TransformResult.Bad -> null
            }
        }.toList()

fun <DTO, OUT> terminalRecords(
    etl: SourceEtl<DTO, OUT>,
    bundle: InputBundle,
    ctx: TransformCtx,
): List<OUT> {
    val ok = mutableListOf<DTO>()
    val bad = mutableListOf<ParseResult.Bad>()
    for (result in etl.parse(bundle)) {
        when (result) {
            is ParseResult.Ok -> ok += result.dto
            is ParseResult.Bad -> bad += result
        }
    }
    if (bad.isNotEmpty()) {
        fail("unexpected parse errors: $bad")
    }
    return records(ok.asSequence().flatMap { etl.transform(it, ctx) })
}

fun <DTO, OUT> terminalOkRecords(
    etl: SourceEtl<DTO, OUT>,
    bundle: InputBundle,
    ctx: TransformCtx,
): List<OUT> =
    etl
        .parse(bundle)
        .flatMap { result ->
            when (result) {
                is ParseResult.Ok -> etl.transform(result.dto, ctx)
                is ParseResult.Bad -> emptySequence()
            }
        }.let(::okRecords)
