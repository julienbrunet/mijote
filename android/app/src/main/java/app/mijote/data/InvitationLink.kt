package app.mijote.data

import java.net.URI
import java.net.URLDecoder

/** What an invitation link carries: the server address and the single-use code. */
data class InvitationLink(val serverUrl: String, val code: String)

/** Invitation codes are lower-case base32 (see the server); be lenient on case and length. */
private val codeRegex = Regex("^[A-Za-z0-9]{8,64}$")

/** Cleans a code typed or pasted by hand; null when it cannot be a code. */
fun normalizeInvitationCode(input: String): String? =
    input.filterNot { it.isWhitespace() || it == '-' }.lowercase().takeIf { codeRegex.matches(it) }

/**
 * Parses `mijote://invite?server=<https url>&code=<code>`, the link of the
 * server's invitation page (also encoded in its QR code). Null for anything else.
 */
fun parseInvitationLink(link: String?): InvitationLink? {
    val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
    if (uri.scheme != "mijote" || uri.host != "invite") return null
    val params = (uri.rawQuery ?: return null).split('&').mapNotNull {
        val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrNull(1) }
        v?.let { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }
    }.toMap()
    val server = normalizeServerUrl(params["server"] ?: return null) ?: return null
    val code = normalizeInvitationCode(params["code"] ?: return null) ?: return null
    return InvitationLink(server, code)
}
