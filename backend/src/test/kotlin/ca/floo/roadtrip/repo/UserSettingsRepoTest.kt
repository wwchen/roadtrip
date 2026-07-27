package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSettingsRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val repo by lazy { UserSettingsRepo(ctx) }

    @BeforeEach fun cleanup() {
        ctx.execute("DELETE FROM app_user")
    }

    private fun newUser(): UserId = userRepo.create(email = "s@example.com", displayName = null, isEmailVerified = true).id

    @Test fun `find is null before any write`() = assertNull(repo.find(newUser()))

    @Test fun `upsertNotifications creates then updates without touching token`() {
        val u = newUser()
        repo.setSlackToken(u, byteArrayOf(1, 2, 3), "3f9a")
        repo.upsertNotifications(u, notificationEmail = "a@x.com", slackChannel = "#c")
        val s = repo.find(u)!!
        assertEquals("a@x.com", s.notificationEmail)
        assertEquals("#c", s.slackChannel)
        assertContentEquals(byteArrayOf(1, 2, 3), s.slackTokenCipher)
        assertEquals("3f9a", s.slackTokenHint)
    }

    @Test fun `clearSlack nulls token but keeps channel`() {
        val u = newUser()
        repo.upsertNotifications(u, null, "#c")
        repo.setSlackToken(u, byteArrayOf(9), "beef")
        repo.clearSlack(u)
        val s = repo.find(u)!!
        assertNull(s.slackTokenCipher)
        assertNull(s.slackTokenHint)
        assertEquals("#c", s.slackChannel)
    }
}
