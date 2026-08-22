package ca.floo.roadtrip.client.aspira

import ca.floo.roadtrip.model.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.support.AspiraException
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Port 1 is reserved and never listening, so connect is refused immediately. */
private const val REFUSED_HOST = "127.0.0.1:1"
private const val TEST_THROTTLE_MS = 300L

class AspiraAvailabilityClientTest {
    @Test
    fun `throttle still applies after a transport failure`() =
        runBlocking {
            // Regression: lastFetchAtMs was assigned after the try/catch, so a
            // throwing sendAsync skipped it. During the 2026-07-30 outage that
            // silently disabled the 1.5s gap and every retry went straight out.
            val client = HttpAspiraAvailabilityClient(throttleMs = TEST_THROTTLE_MS)
            val day = LocalDate.of(2026, 7, 31)

            val startedAt = System.currentTimeMillis()
            repeat(2) {
                assertFailsWith<AspiraException> {
                    client.fetch(REFUSED_HOST, mapId = 1, startDate = day, endDate = day.plusDays(1))
                }
            }
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue(
                elapsedMs >= TEST_THROTTLE_MS,
                "second attempt did not wait for the throttle: ${elapsedMs}ms",
            )
        }

    @Test
    fun `parse extracts resource availability object arrays`() {
        val parsed =
            HttpAspiraAvailabilityClient().parse(
                body =
                    """
                    {
                      "mapId": -2147483516,
                      "mapAvailabilities": [1, 6],
                      "mapLinkAvailabilities": {
                        "-2147483515": [1, 5]
                      },
                      "resourceAvailabilities": {
                        "-2147478966": [
                          { "resourceId": -2147478966, "availability": 0 },
                          { "resourceId": -2147478966, "availability": 1 },
                          { "resourceId": -2147478966 }
                        ]
                      }
                    }
                    """.trimIndent(),
                mapId = -2147483516,
            )

        assertEquals(listOf(1, 6), parsed.parkRollup)
        assertEquals(listOf(1, 5), parsed.byMapLink["-2147483515"])
        assertEquals(listOf(0, 1, AspiraStatus.UNKNOWN), parsed.byResource["-2147478966"])
    }
}
