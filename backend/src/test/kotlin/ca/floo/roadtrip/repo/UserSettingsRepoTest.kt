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

    @Test fun `saveNotifications with token args persists both notifications and token atomically`() {
        val u = newUser()
        val cipher = byteArrayOf(0xA, 0xB, 0xC)
        repo.saveNotifications(u, notificationEmail = "a@x.com", slackChannel = "#ch", slackTokenCipher = cipher, slackTokenHint = "hint")
        val s = repo.find(u)!!
        assertEquals("a@x.com", s.notificationEmail)
        assertEquals("#ch", s.slackChannel)
        assertContentEquals(cipher, s.slackTokenCipher)
        assertEquals("hint", s.slackTokenHint)
    }

    @Test fun `saveNotifications with null token args preserves any pre-existing token`() {
        val u = newUser()
        val existingCipher = byteArrayOf(0x1, 0x2, 0x3)
        repo.setSlackToken(u, existingCipher, "3f9a")
        repo.saveNotifications(u, notificationEmail = "b@x.com", slackChannel = "#new", slackTokenCipher = null, slackTokenHint = null)
        val s = repo.find(u)!!
        assertEquals("b@x.com", s.notificationEmail)
        assertEquals("#new", s.slackChannel)
        // Token must be untouched
        assertContentEquals(existingCipher, s.slackTokenCipher)
        assertEquals("3f9a", s.slackTokenHint)
    }

    @Test fun `saveRecgovCredentials stores username and sealed password`() {
        val u = newUser()
        val cipher = byteArrayOf(0x7, 0x8)
        repo.saveRecgovCredentials(u, username = "ada@example.com", passwordCipher = cipher)
        val s = repo.find(u)!!
        assertEquals("ada@example.com", s.recgovUsername)
        assertContentEquals(cipher, s.recgovPasswordCipher)
    }

    @Test fun `saveRecgovCredentials with null password preserves the stored one`() {
        val u = newUser()
        val cipher = byteArrayOf(0x7, 0x8)
        repo.saveRecgovCredentials(u, "ada@example.com", cipher)
        repo.saveRecgovCredentials(u, "grace@example.com", passwordCipher = null)
        val s = repo.find(u)!!
        assertEquals("grace@example.com", s.recgovUsername)
        assertContentEquals(cipher, s.recgovPasswordCipher)
    }

    @Test fun `clearRecgov nulls the credential columns but keeps the Slack token`() {
        val u = newUser()
        repo.setSlackToken(u, byteArrayOf(9), "beef")
        repo.saveRecgovCredentials(u, "ada@example.com", byteArrayOf(1))
        repo.clearRecgov(u)
        val s = repo.find(u)!!
        assertNull(s.recgovUsername)
        assertNull(s.recgovPasswordCipher)
        assertContentEquals(byteArrayOf(9), s.slackTokenCipher)
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
