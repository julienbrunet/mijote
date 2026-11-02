package app.mijote.data

import org.json.JSONObject

/** An invitation as listed for the admin; the code itself is never listed (only its hash is stored). */
data class Invitation(val id: String, val role: String, val status: String, val expiresAt: String, val usedBy: String?)

/** Invitations and the lifetimes the admin can pick, in days. */
data class Invitations(val list: List<Invitation>, val durations: List<Int>, val defaultDays: Int)

/** An invitation just created: its clear code, the link to share and its QR code (PNG bytes), shown once. */
data class NewInvitation(val code: String, val link: String, val role: String, val days: Int, val qrPng: ByteArray?) {
    override fun equals(other: Any?) = other is NewInvitation && other.code == code
    override fun hashCode() = code.hashCode()
}

/** An account as managed by the admin. */
data class Account(val id: String, val username: String, val displayName: String, val role: String, val disabled: Boolean)

fun parseInvitations(o: JSONObject): Invitations {
    val a = o.optJSONArray("invitations")
    val d = o.optJSONArray("durations")
    return Invitations(
        list = (0 until (a?.length() ?: 0)).map {
            val i = a!!.getJSONObject(it)
            Invitation(i.getString("id"), i.optString("role", "member"), i.optString("status"),
                i.optString("expiresAt"), if (i.isNull("usedBy")) null else i.optString("usedBy"))
        },
        durations = (0 until (d?.length() ?: 0)).map { d!!.getInt(it) }.ifEmpty { listOf(1, 7, 30) },
        defaultDays = o.optInt("defaultDays", 7),
    )
}

fun parseNewInvitation(o: JSONObject): NewInvitation = NewInvitation(
    code = o.getString("code"), link = o.getString("link"), role = o.optString("role", "member"), days = o.optInt("days", 7),
    qrPng = if (o.isNull("qr")) null else runCatching { java.util.Base64.getDecoder().decode(o.getString("qr")) }.getOrNull(),
)

fun parseAccount(o: JSONObject) = Account(o.getString("id"), o.getString("username"),
    o.optString("displayName", o.getString("username")), o.optString("role", "member"), o.optBoolean("disabled"))

fun parseAccounts(o: JSONObject): List<Account> {
    val a = o.optJSONArray("users")
    return (0 until (a?.length() ?: 0)).map { parseAccount(a!!.getJSONObject(it)) }
}
