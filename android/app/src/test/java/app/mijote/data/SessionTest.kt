package app.mijote.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MemoryStore(override var serverUrl: String? = "https://srv", override var tokens: Tokens? = null) : SessionStore

/** Fake server: access tokens "a<n>", refresh tokens "r<n>"; only the latest pair is valid. */
class FakeServer : Http {
    var gen = 0
    val refreshes = AtomicInteger()

    private fun pair() = """{"accessToken":"a$gen","refreshToken":"r$gen","expiresIn":900,
        "user":{"id":"u1","username":"julien","displayName":"Julien","role":"admin"}}"""

    @Synchronized
    override fun send(method: String, url: String, body: String?, bearer: String?): Pair<Int, String> = when {
        url.endsWith("/api/versions") -> 200 to """{"versions":["v1"]}"""
        url.endsWith("/invitations/redeem") ->
            if (body!!.contains("\"code\":\"goodcode1\"")) { gen++; 201 to pair() }
            else 404 to """{"error":"invalid_invitation","message":"nope"}"""
        url.endsWith("/auth/login") ->
            if (body!!.contains("\"password\":\"good\"")) { gen++; 200 to pair() }
            else 401 to """{"error":"invalid_credentials","message":"wrong"}"""
        url.endsWith("/auth/refresh") -> {
            refreshes.incrementAndGet()
            if (body!!.contains("\"r$gen\"")) { gen++; 200 to pair() }
            else 401 to """{"error":"invalid_refresh_token","message":"expired"}"""
        }
        url.endsWith("/me") ->
            if (bearer == "a$gen") 200 to """{"id":"u1","username":"julien","displayName":"Julien","role":"admin"}"""
            else 401 to """{"error":"unauthorized","message":"expired"}"""
        else -> 404 to "{}"
    }
}

class SessionTest {
    private val server = FakeServer()
    private val store = MemoryStore()
    private val session = Session(store) { Api(it, server) }

    @Test
    fun loginStoresTokens() = runTest {
        val u = session.login("julien", "good", "test")
        assertEquals("Julien", u.displayName)
        assertEquals("r1", store.tokens?.refresh)
    }

    @Test
    fun redeemingAnInvitationSignsIn() = runTest {
        val empty = MemoryStore(serverUrl = null)
        val s = Session(empty) { Api(it, server) }
        s.redeemInvitation("https://srv", "goodcode1", "lea", "Léa", "secret-pass", "test")
        assertEquals("https://srv", empty.serverUrl)
        assertEquals("r1", empty.tokens?.refresh)
    }

    @Test
    fun badInvitationSavesNothing() = runTest {
        val empty = MemoryStore(serverUrl = null)
        val s = Session(empty) { Api(it, server) }
        val e = runCatching { s.redeemInvitation("https://srv", "badcode12", "lea", "", "secret-pass", "test") }.exceptionOrNull()
        assertTrue(e is ApiException && e.code == "invalid_invitation")
        assertNull(empty.serverUrl)
        assertNull(empty.tokens)
    }

    @Test
    fun wrongPasswordIsAnApiError() = runTest {
        val e = runCatching { session.login("julien", "bad", "test") }.exceptionOrNull()
        assertTrue(e is ApiException && e.code == "invalid_credentials")
    }

    @Test
    fun expiredAccessTokenIsRefreshedOnceForConcurrentCalls() = runTest {
        session.login("julien", "good", "test")
        store.tokens = store.tokens!!.copy(access = "stale") // r1 stays valid

        val users = (1..5).map { async { session.refreshUser() } }.awaitAll()
        assertEquals(5, users.size)
        assertEquals(1, server.refreshes.get())
    }

    @Test
    fun rejectedRefreshSignsOut() = runTest {
        session.login("julien", "good", "test")
        store.tokens = store.tokens!!.copy(access = "stale", refresh = "revoked")
        val e = runCatching { session.refreshUser() }.exceptionOrNull()
        assertTrue(e is SessionExpiredException)
        assertNull(store.tokens)
    }
}
