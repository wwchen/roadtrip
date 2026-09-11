package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CREDENTIALS_MIGRATION = "V60__user_booking_credentials.sql"

class UserBookingCredentialsRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val repo by lazy { UserBookingCredentialsRepo(ctx) }

    @BeforeEach fun cleanup() {
        ctx.execute("DELETE FROM app_user")
    }

    private fun newUser(email: String = "ada@example.com"): UserId =
        userRepo.create(email = email, displayName = null, isEmailVerified = true).id

    @Test fun `find is null before any write`() = assertNull(repo.find(newUser(), BookingProvider.RECGOV))

    @Test fun `save round-trips the username and the sealed secret`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, username = "ada@example.com", secretCipher = byteArrayOf(7, 8))

        val stored = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("ada@example.com", stored.username)
        assertContentEquals(byteArrayOf(7, 8), stored.secretCipher)
    }

    @Test fun `a second save replaces the row for that provider`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(1))
        repo.save(user, BookingProvider.RECGOV, "grace@example.com", byteArrayOf(2))

        val stored = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("grace@example.com", stored.username)
        assertContentEquals(byteArrayOf(2), stored.secretCipher)
        assertEquals(1, storedRowCount(user))
    }

    @Test fun `one user's providers are stored and cleared independently`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(1))
        repo.save(user, BookingProvider.CAMPFLARE, "grace@example.com", byteArrayOf(2))

        assertEquals("ada@example.com", repo.find(user, BookingProvider.RECGOV)!!.username)
        assertEquals("grace@example.com", repo.find(user, BookingProvider.CAMPFLARE)!!.username)

        repo.clear(user, BookingProvider.RECGOV)

        assertNull(repo.find(user, BookingProvider.RECGOV))
        assertEquals("grace@example.com", repo.find(user, BookingProvider.CAMPFLARE)!!.username)
    }

    @Test fun `clear reports whether a row existed`() {
        val user = newUser()
        assertFalse(repo.clear(user, BookingProvider.RECGOV), "nothing was stored")

        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(1))

        assertTrue(repo.clear(user, BookingProvider.RECGOV))
        assertFalse(repo.clear(user, BookingProvider.RECGOV), "the row is already gone")
    }

    @Test fun `userIdsWithCredentials lists only the users holding that provider`() {
        val ada = newUser("ada@example.com")
        val grace = newUser("grace@example.com")
        repo.save(ada, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(1))
        repo.save(grace, BookingProvider.CAMPFLARE, "grace@example.com", byteArrayOf(2))

        assertEquals(listOf(ada), repo.userIdsWithCredentials(BookingProvider.RECGOV))
        assertEquals(listOf(grace), repo.userIdsWithCredentials(BookingProvider.CAMPFLARE))
    }

    @Test fun `the migration copies a seeded V53 row into provider recgov`() {
        val user = seedLegacyCredentials("ada@example.com", byteArrayOf(1, 2))

        migrationStatements(CREDENTIALS_MIGRATION).forEach(ctx::execute)

        val copied = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("ada@example.com", copied.username)
        assertContentEquals(byteArrayOf(1, 2), copied.secretCipher)
    }

    @Test fun `a replay of the migration leaves the migrated row alone`() {
        val user = seedLegacyCredentials("ada@example.com", byteArrayOf(1, 2))

        repeat(2) { migrationStatements(CREDENTIALS_MIGRATION).forEach(ctx::execute) }
        // The user has since changed accounts; a rerun must not resurrect the old one.
        repo.save(user, BookingProvider.RECGOV, "grace@example.com", byteArrayOf(9))
        migrationStatements(CREDENTIALS_MIGRATION).forEach(ctx::execute)

        val stored = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("grace@example.com", stored.username)
        assertContentEquals(byteArrayOf(9), stored.secretCipher)
        assertEquals(1, storedRowCount(user))
    }

    /** A row in the shape V53 left behind, which V60 is meant to carry forward. */
    private fun seedLegacyCredentials(
        username: String,
        passwordCipher: ByteArray,
    ): UserId {
        val user = newUser()
        ctx.execute(
            "INSERT INTO user_settings (user_id, recgov_username, recgov_password_cipher) VALUES (?, ?, ?)",
            user.value,
            username,
            passwordCipher,
        )
        return user
    }

    private fun storedRowCount(user: UserId): Int =
        ctx.fetchCount(
            DSL.table("user_booking_credentials"),
            DSL.field("user_id", Long::class.java).eq(user.value),
        )
}
