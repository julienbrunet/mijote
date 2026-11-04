package app.mijote.data

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class WishStatus(val requested: Boolean, val weeklyLimit: Int, val used: Int, val remaining: Int, val resetsAt: Instant) {
    companion object {
        fun parse(json: JSONObject) = WishStatus(json.getBoolean("requested"), json.getInt("weeklyLimit"),
            json.getInt("used"), json.getInt("remaining"), parseApiInstant(json.getString("resetsAt")))
    }
}

data class RecipeNotification(
    val id: String, val requesterName: String, val recipeId: String, val recipeTitle: String,
    val createdAt: Instant, val readAt: Instant?, val recipeAvailable: Boolean = true,
)

data class NotificationPage(val items: List<RecipeNotification>, val unreadCount: Int, val hasMore: Boolean) {
    companion object {
        fun parse(json: JSONObject): NotificationPage {
            val items = json.getJSONArray("items")
            return NotificationPage((0 until items.length()).map {
                val item = items.getJSONObject(it)
                RecipeNotification(item.getString("id"), item.getString("requesterName"), item.getString("recipeId"), item.getString("recipeTitle"),
                    parseApiInstant(item.getString("createdAt")), if (item.isNull("readAt")) null else parseApiInstant(item.getString("readAt")),
                    item.optBoolean("recipeAvailable", true))
            }, json.getInt("unreadCount"), json.getBoolean("hasMore"))
        }
    }
}

/** Android 8's Instant.parse accepts only Z, while our RFC 3339 dates may carry offsets. */
private fun parseApiInstant(value: String): Instant = try {
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
} catch (_: DateTimeParseException) {
    // ViewModel callers handle IOException; an unchecked date exception would crash the app.
    throw ApiException(200, "invalid_response", "Le serveur a renvoyé une date illisible.")
}
