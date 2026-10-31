package app.mijote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The session ended on the server (revoked, expired or account disabled): sign in again. */
class SessionExpiredException : Exception("session expired")

/**
 * Owns the server address and the tokens. Authenticated calls go through
 * [authed], which refreshes the access token once on 401. Refreshes are
 * serialized: the server treats a reused refresh token as theft.
 */
class Session(
    private val store: SessionStore,
    private val api: (String) -> Api = { Api(it) },
) {
    private val refreshLock = Mutex()

    val serverUrl: String? get() = store.serverUrl
    val user: User? get() = store.tokens?.user

    private fun client(): Api = api(store.serverUrl ?: throw SessionExpiredException())

    /** Validates and saves the server address; signing in again is required. */
    suspend fun setServer(url: String) = withContext(Dispatchers.IO) {
        if (!api(url).supportsV1()) throw ApiException(0, "not_mijote", "not a Mijote server")
        store.tokens = null
        store.serverUrl = url
    }

    suspend fun login(username: String, password: String, deviceName: String): User = withContext(Dispatchers.IO) {
        val t = client().login(username, password, deviceName)
        store.tokens = t
        t.user
    }

    /**
     * Joins the family: checks the server, then creates the member account with the
     * invitation code. Nothing is saved unless the account was created.
     */
    suspend fun redeemInvitation(
        serverUrl: String, code: String, username: String, displayName: String, password: String, deviceName: String,
    ): User = withContext(Dispatchers.IO) {
        val client = api(serverUrl)
        if (!client.supportsV1()) throw ApiException(0, "not_mijote", "not a Mijote server")
        val t = client.redeemInvitation(code, username, displayName, password, deviceName)
        store.serverUrl = serverUrl
        store.tokens = t
        t.user
    }

    /** Closes the session on the server when reachable, and always forgets it locally. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val t = store.tokens
        store.tokens = null
        if (t != null) runCatching { client().logout(t.access) }
    }

    suspend fun <T> authed(block: (Api, String) -> T): T = withContext(Dispatchers.IO) {
        val t = store.tokens ?: throw SessionExpiredException()
        try {
            block(client(), t.access)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            block(client(), refresh(t.access))
        }
    }

    /** Returns a fresh access token; [stale] is the one that was rejected. */
    private suspend fun refresh(stale: String): String = refreshLock.withLock {
        val t = store.tokens ?: throw SessionExpiredException()
        if (t.access != stale) return t.access // another call already refreshed
        try {
            val fresh = client().refresh(t.refresh)
            store.tokens = fresh
            fresh.access
        } catch (e: ApiException) {
            if (e.status == 401) {
                store.tokens = null
                throw SessionExpiredException()
            }
            throw e
        }
    }

    /** Saves the user returned by the server (e.g. after a preference change). */
    fun updateUser(u: User) {
        store.tokens?.let { store.tokens = it.copy(user = u) }
    }

    /** Reloads the user from the server (also proves the session is still valid). */
    suspend fun refreshUser(): User {
        val u = authed { api, access -> api.me(access) }
        store.tokens?.let { store.tokens = it.copy(user = u) }
        return u
    }
}
