package ca.floo.roadtrip.client

import ca.floo.roadtrip.models.aspira.AspiraStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AspiraAvailabilityClientTest {
    @Test
    fun `parse extracts resource availability object arrays`() {
        val parsed =
            AspiraAvailabilityClient().parse(
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
                          { "resourceId": -2147478966, "availability": 1 },
                          { "resourceId": -2147478966, "availability": 5 },
                          { "resourceId": -2147478966 }
                        ]
                      }
                    }
                    """.trimIndent(),
                mapId = -2147483516,
            )

        assertEquals(listOf(1, 6), parsed.parkRollup)
        assertEquals(listOf(1, 5), parsed.byMapLink["-2147483515"])
        assertEquals(listOf(1, 5, AspiraStatus.NO_DATA), parsed.byResource["-2147478966"])
    }
}
