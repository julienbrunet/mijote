package app.mijote.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An error answered by the server: HTTP status and the `error` code of its JSON body. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/** [people] is the user's preference: number of people to cook for (recipes are stored for one person). */
data class User(val id: String, val username: String, val displayName: String, val role: String, val people: Int = 1) {
    val isAdmin: Boolean get() = role == "admin"
    val isReadonly: Boolean get() = role == "readonly"
    val canWrite: Boolean get() = role == "admin" || role == "member"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("username", username).put("displayName", displayName).put("role", role)
        .put("preferences", JSONObject().put("people", people))

    companion object {
        fun fromJson(o: JSONObject) = User(
            id = o.getString("id"),
            username = o.getString("username"),
            displayName = o.optString("displayName", o.getString("username")),
            role = o.optString("role", "member"),
            people = o.optJSONObject("preferences")?.optInt("people", 1) ?: 1,
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

    /** Creates a member account from an invitation code and signs the device in (FR-ONB-03). */
    fun redeemInvitation(code: String, username: String, displayName: String, password: String, deviceName: String): Tokens =
        Tokens.fromJson(
            call("POST", "/api/v1/invitations/redeem", JSONObject()
                .put("code", code).put("username", username).put("displayName", displayName)
                .put("password", password).put("deviceName", deviceName)),
        )

    fun refresh(refreshToken: String): Tokens =
        Tokens.fromJson(call("POST", "/api/v1/auth/refresh", JSONObject().put("refreshToken", refreshToken)))

    fun logout(access: String) {
        call("POST", "/api/v1/auth/logout", bearer = access)
    }

    fun me(access: String): User = User.fromJson(call("GET", "/api/v1/me", bearer = access))

    /** Saves the user's preferences (number of people, 1 to 20). */
    fun setPeople(access: String, people: Int): User =
        User.fromJson(call("PUT", "/api/v1/me/preferences", JSONObject().put("people", people), bearer = access))

    /** Planned meals from today over the family horizon, and the generation info. */
    fun menu(access: String): Menu = parseMenu(call("GET", "/api/v1/menu", bearer = access))

    /** Generates [meals] meals from [start] (FR-GEN); answers 409 not_enough_recipes on a too small base. */
    fun generateMenu(access: String, start: java.time.LocalDate, meals: Int, plan: MealPlan): List<MenuSlot> =
        parseGenerated(call("POST", "/api/v1/menu/generate", JSONObject()
            .put("start", start.toString()).put("meals", meals).put("plan", plan.code), bearer = access))

    /** Replaces the recipe of a planned meal by another suitable one (FR-ADJ-01). */
    fun swapSlot(access: String, id: String): MenuSlot =
        parseSlot(call("POST", "/api/v1/menu/slots/$id/swap", bearer = access))

    /** Plans one more meal ("lunch" or "dinner") on a day; the server picks its recipe. */
    fun addSlot(access: String, date: java.time.LocalDate, meal: String): MenuSlot =
        parseSlot(call("POST", "/api/v1/menu/slots", JSONObject().put("date", date.toString()).put("meal", meal), bearer = access))

    /**
     * Plans a chosen recipe ([recipeId]) or a manual meal ([title]) at a date and time; [replace]
     * overwrites the meal already planned there (otherwise 409 slot_exists).
     */
    fun planSlot(access: String, date: java.time.LocalDate, meal: String, recipeId: String?, title: String?, replace: Boolean): MenuSlot =
        parseSlot(call("POST", "/api/v1/menu/slots", JSONObject().put("date", date.toString()).put("meal", meal)
            .put("recipeId", recipeId.orEmpty()).put("title", title.orEmpty()).put("replace", replace), bearer = access))

    /** Turns a planned meal into a recipe or a manual meal (exactly one of [recipeId] and [title]). */
    fun updateSlot(access: String, id: String, recipeId: String?, title: String?): MenuSlot =
        parseSlot(call("PUT", "/api/v1/menu/slots/$id", JSONObject().put("recipeId", recipeId.orEmpty()).put("title", title.orEmpty()),
            bearer = access))

    /** Changes the number of people of a planned meal (1–20), for the whole family. */
    fun setSlotPeople(access: String, id: String, people: Int): MenuSlot =
        parseSlot(call("PUT", "/api/v1/menu/slots/$id/people", JSONObject().put("people", people), bearer = access))

    fun removeSlot(access: String, id: String) {
        call("DELETE", "/api/v1/menu/slots/$id", bearer = access)
    }

    fun recipe(access: String, id: String): RecipeDetail = parseRecipe(call("GET", "/api/v1/recipes/$id", bearer = access))

    /** Active recipes matching [query] (title or tag), sorted by title; archived ones are left out. */
    fun recipes(access: String, query: String): List<RecipeSummary> =
        parseRecipeList(call("GET", "/api/v1/recipes?limit=500&q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"), bearer = access))

    fun createRecipe(access: String, draft: RecipeDraft): RecipeDetail =
        parseRecipe(call("POST", "/api/v1/recipes", draft.toJson(), bearer = access))

    fun updateRecipe(access: String, id: String, draft: RecipeDraft): RecipeDetail =
        parseRecipe(call("PUT", "/api/v1/recipes/$id", draft.toJson(), bearer = access))

    /** Archives a recipe (soft delete, admin only): it leaves the lists, past menus keep it. */
    fun archiveRecipe(access: String, id: String) {
        call("DELETE", "/api/v1/recipes/$id", bearer = access)
    }

    /** Adds the recipe to the signed-in user's own favorites, or removes it. */
    fun setFavorite(access: String, id: String, on: Boolean) {
        call(if (on) "PUT" else "DELETE", "/api/v1/recipes/$id/favorite", bearer = access)
    }

    // ---------- Administration (admin only) ----------

    fun invitations(access: String): Invitations = parseInvitations(call("GET", "/api/v1/admin/invitations", bearer = access))

    fun createInvitation(access: String, role: String, days: Int): NewInvitation =
        parseNewInvitation(call("POST", "/api/v1/admin/invitations", JSONObject().put("role", role).put("days", days), bearer = access))

    fun revokeInvitation(access: String, id: String) {
        call("POST", "/api/v1/admin/invitations/$id/revoke", bearer = access)
    }

    fun accounts(access: String): List<Account> = parseAccounts(call("GET", "/api/v1/admin/users", bearer = access))

    /** Changes the role ("member" / "readonly") or the disabled state of a member. */
    fun updateAccount(access: String, id: String, role: String? = null, disabled: Boolean? = null): Account {
        val body = JSONObject()
        role?.let { body.put("role", it) }
        disabled?.let { body.put("disabled", it) }
        return parseAccount(call("PUT", "/api/v1/admin/users/$id", body, bearer = access))
    }

    fun resetPassword(access: String, id: String, password: String) {
        call("PUT", "/api/v1/admin/users/$id/password", JSONObject().put("password", password), bearer = access)
    }

    /** Thumbnail bytes of a recipe image path (as sent by the server), null when missing. */
    fun image(access: String, path: String): ByteArray? {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $access")
            if (c.responseCode == 401) throw ApiException(401, "unauthorized", "HTTP 401")
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
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
