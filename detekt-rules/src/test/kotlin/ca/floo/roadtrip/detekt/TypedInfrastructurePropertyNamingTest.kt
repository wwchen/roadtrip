package ca.floo.roadtrip.detekt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedInfrastructurePropertyNamingTest {
    @Test
    fun `detects configured infrastructure type suffixes`() {
        val suffixes = listOf("Repo", "Service", "Client")

        assertEquals("Repo", requiredInfrastructureSuffix("AvailabilityWatchRepo", suffixes))
        assertEquals("Repo", requiredInfrastructureSuffix("ca.floo.SchedulableRepo<T>", suffixes))
        assertEquals("Service", requiredInfrastructureSuffix("WatchAlertService?", suffixes))
        assertEquals("Client", requiredInfrastructureSuffix("ca.floo.SlackClient", suffixes))
    }

    @Test
    fun `requires property name to end with matching suffix`() {
        assertTrue(hasInfrastructureSuffix("watchRepo", "Repo"))
        assertTrue(hasInfrastructureSuffix("alertService", "Service"))
        assertTrue(hasInfrastructureSuffix("slackClient", "Client"))
    }

    @Test
    fun `ignores non infrastructure type suffixes`() {
        assertEquals(null, requiredInfrastructureSuffix("Clock", listOf("Repo", "Service", "Client")))
    }
}
