package app.mijote.data

import org.json.JSONArray
import org.json.JSONObject

/** One ingredient line; [quantity] is for one person (recipes are stored that way). */
data class IngredientLine(val quantity: Double?, val unit: String?, val name: String, val note: String?)

/** A full recipe as shown in the detail screen. */
data class RecipeDetail(
    val id: String,
    val title: String,
    val image: String?,
    val dishType: String,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val totalMinutes: Int?,
    val notes: String?,
    val ingredients: List<IngredientLine>,
    val steps: List<String>,
)

private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

fun parseRecipe(o: JSONObject): RecipeDetail {
    val ing = o.optJSONArray("ingredients") ?: JSONArray()
    val steps = o.optJSONArray("steps") ?: JSONArray()
    return RecipeDetail(
        id = o.getString("id"),
        title = o.getString("title"),
        image = o.stringOrNull("image"),
        dishType = o.optString("dishType"),
        prepMinutes = o.intOrNull("prepMinutes"),
        cookMinutes = o.intOrNull("cookMinutes"),
        totalMinutes = o.intOrNull("totalMinutes"),
        notes = o.stringOrNull("notes"),
        ingredients = (0 until ing.length()).map {
            val i = ing.getJSONObject(it)
            IngredientLine(if (i.isNull("quantity")) null else i.optDouble("quantity"), i.stringOrNull("unit"),
                i.getString("name"), i.stringOrNull("note"))
        },
        steps = (0 until steps.length()).map { steps.getString(it) },
    )
}

private val unitLabels = mapOf(
    "tbsp" to "c. à soupe", "tsp" to "c. à café", "pinch" to "pincée", "dash" to "trait", "handful" to "poignée",
    "piece" to "", "clove" to "gousse", "slice" to "tranche", "bunch" to "botte", "sprig" to "brin",
    "leaf" to "feuille", "can" to "boîte", "pack" to "sachet", "jar" to "pot",
)

private fun number(v: Double): String {
    val r = Math.round(v * 100) / 100.0
    return (if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0')).replace('.', ',')
}

/** "400 g farine, tamisée", quantities multiplied by [people] (FR-PLAN-05). */
fun IngredientLine.text(people: Int): String {
    val parts = mutableListOf<String>()
    quantity?.let { parts += number(it * people) }
    unit?.let { u -> (unitLabels[u] ?: u).takeIf { it.isNotEmpty() }?.let(parts::add) }
    parts += name
    return parts.joinToString(" ") + (note?.let { ", $it" } ?: "")
}
