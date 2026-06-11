package ca.floo.campsite.recgov.booker.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecgovAuthTest {
    private fun fakeJwt(payload: String): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "$header.$body.sig"
    }

    @Test
    fun `extractCookies returns input when not curl`() {
        val raw = "foo=1; bar=2"
        assertEquals(raw, RecgovAuth.extractCookies(raw))
    }

    @Test
    fun `extractCookies parses -b form`() {
        val curl = """curl 'https://x' -b 'a=1; b=2' -H 'x-y: z'"""
        assertEquals("a=1; b=2", RecgovAuth.extractCookies(curl))
    }

    @Test
    fun `extractCookies parses -H Cookie form`() {
        val curl = """curl 'https://x' -H 'Cookie: a=1; b=2'"""
        assertEquals("a=1; b=2", RecgovAuth.extractCookies(curl))
    }

    @Test
    fun `countCookies counts assignments`() {
        assertEquals(3, RecgovAuth.countCookies("a=1; b=2; c=3"))
        assertEquals(0, RecgovAuth.countCookies(""))
    }

    @Test
    fun `extractBearer pulls token from curl`() {
        val curl = """curl 'https://x' -H 'Authorization: Bearer abc.def.ghi' -H 'x-y: z'"""
        assertEquals("abc.def.ghi", RecgovAuth.extractBearer(curl))
    }

    @Test
    fun `extractBearer returns null when missing`() {
        assertNull(RecgovAuth.extractBearer("curl 'https://x'"))
    }

    @Test
    fun `extractRefreshCreds parses --data-raw json`() {
        val curl =
            """curl -X POST 'https://x/refresh' --data-raw '{"account_id":"A1","refresh_id":"R1"}'"""
        val creds = RecgovAuth.extractRefreshCreds(curl)
        assertNotNull(creds)
        assertEquals("A1", creds.accountId)
        assertEquals("R1", creds.refreshId)
    }

    @Test
    fun `extractRefreshCreds parses recaccount-shaped JSON`() {
        val raw = """{"access_token":"x","account":{"account_id":"A2"},"refresh_id":"R2"}"""
        val creds = RecgovAuth.extractRefreshCreds(raw)
        assertNotNull(creds)
        assertEquals("A2", creds.accountId)
        assertEquals("R2", creds.refreshId)
    }

    @Test
    fun `extractRefreshCreds parses flat JSON`() {
        val raw = """{"account_id":"A3","refresh_id":"R3"}"""
        val creds = RecgovAuth.extractRefreshCreds(raw)
        assertNotNull(creds)
        assertEquals("A3", creds.accountId)
        assertEquals("R3", creds.refreshId)
    }

    @Test
    fun `refresh creds toJson serializes with dto escaping`() {
        val json = RefreshCreds(accountId = """A"3""", refreshId = """R\3""").toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject

        assertEquals("""A"3""", parsed["account_id"]!!.jsonPrimitive.content)
        assertEquals("""R\3""", parsed["refresh_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildRecaccountFromToken serializes fallback recaccount with dto`() {
        val future = (System.currentTimeMillis() / 1000) + 3600
        val token =
            fakeJwt(
                """
                {
                  "exp":$future,
                  "acct":{
                    "account_id":"acct-1",
                    "email":"a@b.c",
                    "first_name":"Ada",
                    "last_name":"Lovelace"
                  }
                }
                """.trimIndent(),
            )

        val recaccount = RecgovAuth.buildRecaccountFromToken(token)

        assertNotNull(recaccount)
        assertEquals(token, recaccount["access_token"]!!.jsonPrimitive.content)
        assertEquals(false, recaccount["is_guest"]!!.jsonPrimitive.boolean)
        assertEquals("", recaccount["refresh_id"]!!.jsonPrimitive.content)
        val account = recaccount["account"]!!.jsonObject
        assertEquals("acct-1", account["account_id"]!!.jsonPrimitive.content)
        assertEquals("Ada", account["first_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extractRefreshCreds returns null on garbage`() {
        assertNull(RecgovAuth.extractRefreshCreds("hello world"))
        assertNull(RecgovAuth.extractRefreshCreds("""{"account_id":"only"}"""))
    }

    @Test
    fun `extractAccessTokenFromRecaccount works`() {
        val raw = """{"access_token":"jwt.here","account":{"account_id":"A"}}"""
        assertEquals("jwt.here", RecgovAuth.extractAccessTokenFromRecaccount(raw))
        assertNull(RecgovAuth.extractAccessTokenFromRecaccount("not json"))
    }

    @Test
    fun `tokenInfo decodes exp and detects future expiry`() {
        val future = (System.currentTimeMillis() / 1000) + 3600
        val token = fakeJwt("""{"exp":$future,"fingerprint":"fp1"}""")
        val info = RecgovAuth.tokenInfo(token)
        assertNotNull(info.expires)
        assertFalse(info.expired)
        assertEquals("fp1", info.fingerprint)
    }

    @Test
    fun `tokenInfo flags past expiry as expired`() {
        val past = (System.currentTimeMillis() / 1000) - 3600
        val token = fakeJwt("""{"exp":$past}""")
        val info = RecgovAuth.tokenInfo(token)
        assertTrue(info.expired)
    }

    @Test
    fun `tokenInfo on empty token is expired with no expires`() {
        val info = RecgovAuth.tokenInfo("")
        assertNull(info.expires)
        assertTrue(info.expired)
    }

    @Test
    fun `tokenInfo on garbage token is expired`() {
        val info = RecgovAuth.tokenInfo("not-a-jwt")
        assertTrue(info.expired)
    }
}
