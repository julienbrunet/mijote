package app.mijote.data

import org.json.JSONObject
import java.time.Instant

data class WishStatus(val requested: Boolean, val weeklyLimit: Int, val used: Int, val remaining: Int, val resetsAt: Instant) {
    companion object {
        fun parse(json: JSONObject) = WishStatus(json.getBoolean("requested"), json.getInt("weeklyLimit"),
            json.getInt("used"), json.getInt("remaining"), Instant.parse(json.getString("resetsAt")))
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
                    Instant.parse(item.getString("createdAt")), if (item.isNull("readAt")) null else Instant.parse(item.getString("readAt")),
                    item.optBoolean("recipeAvailable", true))
            }, json.getInt("unreadCount"), json.getBoolean("hasMore"))
        }
    }
}
