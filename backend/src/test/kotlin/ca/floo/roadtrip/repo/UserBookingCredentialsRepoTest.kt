package ca.floo.roadtrip.repo

import ca.floo.roadtrip.fixtures.ROLL_FORWARD_CREDENTIALS_HEADING
import ca.floo.roadtrip.fixtures.bookingPortRunbook
import ca.floo.roadtrip.fixtures.runbookSqlStatements
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

    @Test fun `updateUsername renames in place and leaves the sealed secret alone`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(1))

        assertTrue(repo.updateUsername(user, BookingProvider.RECGOV, "grace@example.com"))

        val stored = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("grace@example.com", stored.username)
        assertContentEquals(byteArrayOf(1), stored.secretCipher)
    }

    @Test fun `updateUsername is false when there is no row, and writes nothing`() {
        val user = newUser()

        assertFalse(repo.updateUsername(user, BookingProvider.RECGOV, "grace@example.com"))

        assertNull(repo.find(user, BookingProvider.RECGOV))
        assertEquals(0, storedRowCount(user))
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

    /**
     * Roll-forward guard, run straight out of `docs/reservation-providers.md`:
     * V60 is versioned and will not re-run, and its `DO NOTHING` would leave a
     * stale row winning anyway. The runbook's upsert has to carry a password
     * changed under the rolled-back jar — which lives only in the V53 columns —
     * over the credential row V60 wrote.
     */
    @Test fun `the roll-forward runbook's SQL brings the newer legacy secret forward`() {
        val user = seedLegacyCredentials("ada@example.com", byteArrayOf(1, 2))
        migrationStatements(CREDENTIALS_MIGRATION).forEach(ctx::execute)
        // The old jar took a new password: the V53 columns move, the table does not.
        ctx.execute(
            "UPDATE user_settings SET recgov_username = ?, recgov_password_cipher = ? WHERE user_id = ?",
            "grace@example.com",
            byteArrayOf(9, 9),
            user.value,
        )

        runbookSqlStatements(bookingPortRunbook, ROLL_FORWARD_CREDENTIALS_HEADING).forEach(ctx::execute)

        val stored = repo.find(user, BookingProvider.RECGOV)!!
        assertEquals("grace@example.com", stored.username)
        assertContentEquals(byteArrayOf(9, 9), stored.secretCipher)
        assertEquals(1, storedRowCount(user))
    }

    @Test fun `a save mirrors the account into the V53 columns`() {
        val user = newUser()

        repo.save(user, BookingProvider.RECGOV, username = "ada@example.com", secretCipher = byteArrayOf(4, 5))

        assertEquals("ada@example.com", legacyUsername(user))
        assertContentEquals(byteArrayOf(4, 5), legacyPasswordCipher(user))
    }

    @Test fun `clear nulls the mirrored V53 columns too`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(4, 5))

        repo.clear(user, BookingProvider.RECGOV)

        assertNull(repo.find(user, BookingProvider.RECGOV))
        assertNull(legacyUsername(user))
        assertNull(legacyPasswordCipher(user))
    }

    @Test fun `updateUsername mirrors the rename into the V53 username`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(4, 5))

        repo.updateUsername(user, BookingProvider.RECGOV, "grace@example.com")

        assertEquals("grace@example.com", legacyUsername(user))
        assertContentEquals(byteArrayOf(4, 5), legacyPasswordCipher(user), "the mirrored secret is untouched")
    }

    @Test fun `updateUsername mirrors the rename even when no user_settings row exists yet`() {
        val user = newUser()
        // A row seeded directly, bypassing save(), so no user_settings mirror has
        // ever been written for this user — the rename mirror must not depend on
        // save() having created one first.
        ctx.execute(
            "INSERT INTO user_booking_credentials (user_id, provider, username, secret_cipher) VALUES (?, ?, ?, ?)",
            user.value,
            BookingProvider.RECGOV.id,
            "ada@example.com",
            byteArrayOf(4, 5),
        )

        assertTrue(repo.updateUsername(user, BookingProvider.RECGOV, "grace@example.com"))

        assertEquals("grace@example.com", legacyUsername(user))
    }

    @Test fun `another provider's account never reaches the rec_gov-only V53 columns`() {
        val user = newUser()
        repo.save(user, BookingProvider.RECGOV, "ada@example.com", byteArrayOf(4, 5))

        repo.save(user, BookingProvider.CAMPFLARE, "grace@example.com", byteArrayOf(9))
        repo.updateUsername(user, BookingProvider.CAMPFLARE, "hopper@example.com")
        repo.clear(user, BookingProvider.CAMPFLARE)

        assertEquals("ada@example.com", legacyUsername(user))
        assertContentEquals(byteArrayOf(4, 5), legacyPasswordCipher(user))
    }

    private fun legacyUsername(user: UserId): String? =
        ctx
            .fetchOne("SELECT recgov_username FROM user_settings WHERE user_id = ?", user.value)
            ?.get("recgov_username", String::class.java)

    private fun legacyPasswordCipher(user: UserId): ByteArray? =
        ctx
            .fetchOne("SELECT recgov_password_cipher FROM user_settings WHERE user_id = ?", user.value)
            ?.get("recgov_password_cipher", ByteArray::class.java)

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
