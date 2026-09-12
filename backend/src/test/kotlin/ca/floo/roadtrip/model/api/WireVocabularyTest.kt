package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.WatchDoneReason
import ca.floo.roadtrip.model.availability.WatchStatus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals

/** The names kotlinx actually encodes — the `@SerialName` of every constant, in declaration order. */
@OptIn(ExperimentalSerializationApi::class)
private fun serialNames(serializer: KSerializer<*>): List<String> = serializer.descriptor.elementNames.toList()

/**
 * Each constant carries its wire string twice: as `@SerialName`, which kotlinx
 * encodes and the TypeScript generator reads, and as `wireValue`, which the
 * repos write to the column. Both copies are pinned against the same list, so
 * neither can drift from the other or from the string that already went out.
 */
class WireVocabularyTest {
    @Test
    fun `the watch status vocabulary keeps its wire strings`() {
        val wire = listOf("active", "paused", "done")
        assertEquals(wire, serialNames(WatchStatus.serializer()))
        assertEquals(wire, WatchStatus.entries.map { it.wireValue })
    }

    @Test
    fun `the done reason vocabulary keeps its wire strings`() {
        val wire = listOf("triggered", "elapsed")
        assertEquals(wire, serialNames(WatchDoneReason.serializer()))
        assertEquals(wire, WatchDoneReason.entries.map { it.wireValue })
    }

    @Test
    fun `the recgov session vocabulary keeps its wire strings`() {
        val wire = listOf("not_configured", "active", "not_logged_in", "expired", "check_failed", "companion_unavailable")
        assertEquals(wire, serialNames(RecgovSessionState.serializer()))
        assertEquals(wire, RecgovSessionState.entries.map { it.wireValue })
    }

    @Test
    fun `the recgov login vocabulary keeps its wire strings`() {
        val wire = listOf("ok", "mfa_required", "failed")
        assertEquals(wire, serialNames(RecgovLoginStatus.serializer()))
        assertEquals(wire, RecgovLoginStatus.entries.map { it.wireValue })
    }

    @Test
    fun `the booking action vocabulary keeps its wire string`() {
        val wire = listOf("completed")
        assertEquals(wire, serialNames(BookingActionStatus.serializer()))
        assertEquals(wire, BookingActionStatus.entries.map { it.wireValue })
    }

    @Test
    fun `parse answers the constant for a known wire value and null otherwise`() {
        assertEquals(WatchStatus.PAUSED, WatchStatus.parse("paused"))
        assertEquals(null, WatchStatus.parse("retired"))
    }
}
