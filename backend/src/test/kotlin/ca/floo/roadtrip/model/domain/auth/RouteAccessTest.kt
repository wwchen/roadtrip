package ca.floo.roadtrip.model.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteAccessTest {
    private val anonymous = Principal.Anonymous
    private val plainUser = Principal.User(UserId(1), roles = emptySet())
    private val admin = Principal.User(UserId(2), roles = setOf(Role.ADMIN))
    private val system = Principal.System

    @Test
    fun `anonymous access allows everyone`() {
        for (principal in listOf(anonymous, plainUser, admin, system)) {
            assertEquals(AccessCheck.Allow, RouteAccess.Anonymous.check(principal))
        }
    }

    @Test
    fun `signed access allows everyone - it is authenticated by signature, not session`() {
        for (principal in listOf(anonymous, plainUser, admin, system)) {
            assertEquals(AccessCheck.Allow, RouteAccess.Signed.check(principal))
        }
    }

    @Test
    fun `user access refuses anonymous with 401 and admits any signed-in principal`() {
        assertEquals(AccessCheck.Unauthenticated, RouteAccess.User.check(anonymous))
        assertEquals(AccessCheck.Allow, RouteAccess.User.check(plainUser))
        assertEquals(AccessCheck.Allow, RouteAccess.User.check(admin))
        assertEquals(AccessCheck.Allow, RouteAccess.User.check(system))
    }

    @Test
    fun `role access is 401 for anonymous, 403 for a user without the role, allow with it`() {
        val needsAdmin = RouteAccess.HasRole(Role.ADMIN)
        assertEquals(AccessCheck.Unauthenticated, needsAdmin.check(anonymous))
        assertEquals(AccessCheck.Forbidden, needsAdmin.check(plainUser))
        assertEquals(AccessCheck.Allow, needsAdmin.check(admin))
    }

    @Test
    fun `system holds every role`() {
        assertEquals(AccessCheck.Allow, RouteAccess.HasRole(Role.ADMIN).check(system))
    }
}
