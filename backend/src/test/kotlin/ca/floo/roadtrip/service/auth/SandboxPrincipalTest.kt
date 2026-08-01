package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxPrincipalTest {
    private val roles: Map<Long, Set<Role>> = mapOf(1L to setOf(Role.ADMIN), 2L to emptySet())

    private fun load(id: UserId): Set<Role>? = roles[id.value]

    @Test
    fun `valid sentinel yields admin User`() {
        assertEquals(Principal.User(UserId(1L), setOf(Role.ADMIN)), sandboxPrincipal("sandbox:1", ::load))
    }

    @Test
    fun `regular user has empty roles`() {
        assertEquals(Principal.User(UserId(2L), emptySet()), sandboxPrincipal("sandbox:2", ::load))
    }

    @Test
    fun `unknown user id yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("sandbox:999", ::load))
    }

    @Test
    fun `missing token yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal(null, ::load))
    }

    @Test
    fun `non-sentinel token yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("real-session-token", ::load))
    }

    @Test
    fun `malformed id yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("sandbox:notanumber", ::load))
    }
}
