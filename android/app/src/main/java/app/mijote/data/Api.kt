package app.mijote.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An error answered by the server: HTTP status and the `error` code of its JSON body. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

data class User(val id: String, val username: String, val displayName: String, val role: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("username", username).put("displayName", displayName).put("role", role)

    companion object {
        fun fromJson(o: JSONObject) = User(
            id = o.getString("id"),
            username = o.getString("username"),
            displayName = o.optString("displayName", o.getString("username")),
            role = o.optString("role", "member"),
        )
    }
}

data class Tokens(val access: String, val refresh: String, val user: User) {
    companion object {
        fun fromJson(o: JSONObject) = Tokens(
            access = o.getString("accessToken"),
            refresh = o.getString("refreshToken"),
            user = User.fromJson(o.getJSONObject("user")),
        )
    }
}

/** Transport used by [Api]; replaced by a fake in unit tests. */
fun interface Http {
    /** Sends a request and returns (status, body). Throws IOException on network failure. */
    fun send(method: String, url: String, body: String?, bearer: String?): Pair<Int, String>
}

/** Plain HttpURLConnection transport: no extra dependency for a handful of JSON calls. */
object UrlConnectionHttp : Http {
    override fun send(method: String, url: String, body: String?, bearer: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}

/** Client of the Mijote REST API (`/api/v1`). Blocking: call it from a background dispatcher. */
class Api(private val baseUrl: String, private val http: Http = UrlConnectionHttp) {

    private fun call(method: String, path: String, body: JSONObject? = null, bearer: String? = null): JSONObject {
        val (status, text) = http.send(method, baseUrl + path, body?.toString(), bearer)
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status !in 200..299) {
            throw ApiException(status, json?.optString("error").orEmpty().ifEmpty { "http_$status" },
                json?.optString("message").orEmpty().ifEmpty { "HTTP $status" })
        }
        return json ?: JSONObject()
    }

    /** Checks that the address answers like a Mijote server supporting API v1. */
    fun supportsV1(): Boolean {
        val versions = call("GET", "/api/versions").optJSONArray("versions") ?: return false
        return (0 until versions.length()).any { versions.optString(it) == "v1" }
    }

    fun login(username: String, password: String, deviceName: String): Tokens = Tokens.fromJson(
        call("POST", "/api/v1/auth/login", JSONObject()
            .put("username", username).put("password", password).put("deviceName", deviceName)),
    )

    fun refresh(refreshToken: String): Tokens =
        Tokens.fromJson(call("POST", "/api/v1/auth/refresh", JSONObject().put("refreshToken", refreshToken)))

    fun logout(access: String) {
        call("POST", "/api/v1/auth/logout", bearer = access)
    }

    fun me(access: String): User = User.fromJson(call("GET", "/api/v1/me", bearer = access))
}

/**
 * Normalizes what the user typed into a base URL: trims, adds https:// when no
 * scheme is given and drops trailing slashes. Returns null for anything that
 * is not an https URL (the app refuses cleartext HTTP).
 */
fun normalizeServerUrl(input: String): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty() || Regex("^[a-zA-Z]+:/*$").matches(s)) return null
    if (!s.contains("://")) s = "https://$s"
    val url = runCatching { URL(s) }.getOrNull() ?: return null
    if (url.protocol != "https" || url.host.isNullOrEmpty() || url.query != null) return null
    return s
}
