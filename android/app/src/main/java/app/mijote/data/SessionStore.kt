package app.mijote.data

import android.content.Context
import org.json.JSONObject

/** Persistent session state: server address, tokens and the signed-in user. */
interface SessionStore {
    var serverUrl: String?
    var tokens: Tokens?
}

/** SharedPreferences storage; tokens are encrypted with an Android Keystore key. */
class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

    override var tokens: Tokens?
        get() {
            val sealed = prefs.getString("tokens", null) ?: return null
            val json = Crypto.decrypt(sealed) ?: return null
            return runCatching {
                val o = JSONObject(json)
                Tokens(o.getString("access"), o.getString("refresh"), User.fromJson(o.getJSONObject("user")))
            }.getOrNull()
        }
        set(v) {
            val sealed = v?.let {
                Crypto.encrypt(JSONObject().put("access", it.access).put("refresh", it.refresh).put("user", it.user.toJson()).toString())
            }
            // commit(): a rotated refresh token must be on disk before it is used, or a crash
            // would leave the old (already revoked) token and sign the user out.
            prefs.edit().putString("tokens", sealed).commit()
        }
}
