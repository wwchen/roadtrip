package ca.floo.campsite.recgov.booker.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AlertRepoTest {
    @Test
    fun `jsonbList serializes string lists with json escaping`() {
        val items = listOf("simple", "quote\"inside", "slash\\inside", "line\nbreak")

        val jsonb = jsonbList(items)

        assertIs<JsonArray>(Json.parseToJsonElement(jsonb.data()))
        assertEquals(items, parseStringList(jsonb))
    }
}
