package app.mijote.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class RecipeWishesTest {
    private fun status(date: String) = JSONObject().put("requested", false).put("weeklyLimit", 5)
        .put("used", 0).put("remaining", 5).put("resetsAt", date)

    @Test
    fun quotaResetAcceptsTheActualFamilyTimezoneOffsetAndUtc() {
        val cases = listOf(
            "2026-11-09T00:00:00+01:00" to "2026-11-08T23:00:00Z",
            "2026-07-06T00:00:00+02:00" to "2026-07-05T22:00:00Z",
            "2026-11-08T23:00:00Z" to "2026-11-08T23:00:00Z",
            "2026-11-09T00:00:00.123456789+01:00" to "2026-11-08T23:00:00.123456789Z",
        )
        for ((input, expected) in cases) {
            assertEquals(Instant.parse(expected), WishStatus.parse(status(input)).resetsAt)
        }
    }

    @Test
    fun notificationDatesAlsoPreserveOffsetsAndFractions() {
        val page = NotificationPage.parse(JSONObject("""{"items":[{
          "id":"n1","requesterName":"Léa","recipeId":"r1","recipeTitle":"Soupe",
          "createdAt":"2026-11-04T19:15:00.25+01:00","readAt":"2026-11-04T18:20:00Z",
          "recipeAvailable":true}],"unreadCount":0,"hasMore":false}"""))
        assertEquals(Instant.parse("2026-11-04T18:15:00.25Z"), page.items.single().createdAt)
        assertEquals(Instant.parse("2026-11-04T18:20:00Z"), page.items.single().readAt)
    }

    @Test
    fun malformedTimestampBecomesAHandledNetworkResponseError() {
        val api = Api("https://srv", Http { _, _, _, _ -> 200 to status("not-a-date").toString() })
        val error = runCatching { api.wishStatus("token", "r1") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("invalid_response", (error as ApiException).code)
    }
}
