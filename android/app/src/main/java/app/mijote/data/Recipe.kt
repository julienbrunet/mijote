package app.mijote.data

import org.json.JSONArray
import org.json.JSONObject

/** One ingredient line; [quantity] is for one person (recipes are stored that way). */
data class IngredientLine(val quantity: Double?, val unit: String?, val name: String, val note: String?, val rawText: String = "")

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
    val sourceUrl: String? = null,
    val servings: Int? = null,
    val seasons: List<String> = emptyList(),
    val mealTypes: List<String> = listOf("lunch", "dinner"),
    val tags: List<String> = emptyList(),
    /** In the favorites of the signed-in user (personal, not shared with the family). */
    val favorite: Boolean = false,
    val archived: Boolean = false,
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
                i.getString("name"), i.stringOrNull("note"), i.optString("rawText"))
        },
        steps = (0 until steps.length()).map { steps.getString(it) },
        sourceUrl = o.stringOrNull("sourceUrl"),
        servings = o.intOrNull("servings"),
        seasons = o.optJSONArray("seasons").strings(),
        mealTypes = o.optJSONArray("mealTypes").strings().ifEmpty { listOf("lunch", "dinner") },
        tags = o.optJSONArray("tags").strings(),
        favorite = o.optBoolean("favorite"),
        archived = !o.isNull("archivedAt"),
    )
}

private fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).map { this!!.getString(it) }

/** A recipe in the list of the Recipes tab. */
data class RecipeSummary(
    val id: String, val title: String, val dishType: String, val minutes: Int?, val image: String?, val tags: List<String>,
    val mealTypes: List<String> = listOf("lunch", "dinner"), val seasons: List<String> = emptyList(),
    val favorite: Boolean = false,
)

/** Longest time of a "quick" recipe in the filters; recipes without a time count as quick, as in generation. */
const val QUICK_MINUTES = 30

/**
 * Filters of the Recipes tab, applied on the device to the search results. Within a group the
 * choices add up (meat or fish); groups narrow each other (vegetarian and quick). A recipe without
 * season suits every season.
 */
data class RecipeFilters(
    /** Only the signed-in user's own favorites. */
    val favorites: Boolean = false,
    val dishTypes: Set<String> = emptySet(),
    val meals: Set<String> = emptySet(),
    val durations: Set<String> = emptySet(), // "quick", "long"
    val seasons: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
) {
    val count get() = (if (favorites) 1 else 0) + dishTypes.size + meals.size + durations.size + seasons.size + tags.size

    fun matches(r: RecipeSummary): Boolean {
        val quick = (r.minutes ?: 0) <= QUICK_MINUTES
        return (!favorites || r.favorite) &&
            (dishTypes.isEmpty() || r.dishType in dishTypes) &&
            (meals.isEmpty() || r.mealTypes.any { it in meals }) &&
            (durations.isEmpty() || (if (quick) "quick" else "long") in durations) &&
            (seasons.isEmpty() || r.seasons.isEmpty() || r.seasons.any { it in seasons }) &&
            (tags.isEmpty() || r.tags.any { t -> tags.any { it.equals(t, ignoreCase = true) } })
    }
}

fun parseRecipeList(o: JSONObject): List<RecipeSummary> {
    val a = o.optJSONArray("recipes") ?: JSONArray()
    return (0 until a.length()).map {
        val r = a.getJSONObject(it)
        RecipeSummary(r.getString("id"), r.getString("title"), r.optString("dishType"),
            r.intOrNull("totalMinutes") ?: r.intOrNull("prepMinutes"), r.stringOrNull("image"), r.optJSONArray("tags").strings(),
            r.optJSONArray("mealTypes").strings().ifEmpty { listOf("lunch", "dinner") }, r.optJSONArray("seasons").strings(),
            r.optBoolean("favorite"))
    }
}

/**
 * What the recipe editor sends (`POST` / `PUT /api/v1/recipes`). Ingredients and steps are
 * edited as text, one per line; ingredient lines are parsed on the device (FR-REC-21).
 * Fields the editor does not show (source link, servings) are kept from the edited recipe.
 */
data class RecipeDraft(
    val title: String = "",
    val dishType: String = "",
    val mealTypes: Set<String> = setOf("lunch", "dinner"),
    val seasons: Set<String> = emptySet(),
    val prepMinutes: String = "",
    val cookMinutes: String = "",
    val tags: String = "",
    val ingredients: String = "",
    val steps: String = "",
    val notes: String = "",
    val sourceUrl: String? = null,
    val servings: Int? = null,
) {
    /** Why the server would refuse the draft, null when it can be sent. */
    fun problem(): String? = when {
        title.isBlank() -> "Donnez un titre à la recette."
        dishType.isEmpty() -> "Choisissez le type de plat : viande, poisson ou végé."
        mealTypes.isEmpty() -> "Choisissez au moins un repas : midi ou soir."
        prepMinutes.isNotBlank() && prepMinutes.trim().toIntOrNull()?.takeIf { it >= 0 } == null -> "Temps de préparation invalide."
        cookMinutes.isNotBlank() && cookMinutes.trim().toIntOrNull()?.takeIf { it >= 0 } == null -> "Temps de cuisson invalide."
        else -> null
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("title", title.trim())
            .put("dishType", dishType)
            .put("mealTypes", JSONArray(listOf("lunch", "dinner").filter { it in mealTypes }))
            .put("seasons", JSONArray(listOf("spring", "summer", "autumn", "winter").filter { it in seasons }))
            .put("tags", JSONArray(tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
            .put("steps", JSONArray(lines(steps)))
            .put("notes", notes.trim().ifEmpty { JSONObject.NULL })
            .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            .put("servings", servings ?: JSONObject.NULL)
            .put("prepMinutes", prepMinutes.trim().toIntOrNull() ?: JSONObject.NULL)
            .put("cookMinutes", cookMinutes.trim().toIntOrNull() ?: JSONObject.NULL)
        val ing = JSONArray()
        lines(ingredients).forEach { line ->
            val i = parseIngredient(line)
            ing.put(JSONObject().put("rawText", line).put("quantity", i.quantity ?: JSONObject.NULL)
                .put("unit", i.unit ?: JSONObject.NULL).put("name", i.name).put("note", i.note ?: JSONObject.NULL))
        }
        return o.put("ingredients", ing)
    }

    companion object {
        private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** The editor prefilled with an existing recipe. */
        fun of(r: RecipeDetail) = RecipeDraft(
            title = r.title, dishType = r.dishType, mealTypes = r.mealTypes.toSet(), seasons = r.seasons.toSet(),
            prepMinutes = r.prepMinutes?.toString().orEmpty(), cookMinutes = r.cookMinutes?.toString().orEmpty(),
            tags = r.tags.joinToString(", "),
            ingredients = r.ingredients.joinToString("\n") { it.rawText.ifBlank { it.text(1) } },
            steps = r.steps.joinToString("\n"), notes = r.notes.orEmpty(), sourceUrl = r.sourceUrl, servings = r.servings,
        )
    }
}

// Unit aliases of requirements.md §9.1, longest first so that "c. à soupe" wins over "c".
private val unitAliases: List<Pair<String, String>> = listOf(
    "g" to listOf("g", "gr", "gramme", "grammes"),
    "kg" to listOf("kg", "kilo", "kilos", "kilogramme", "kilogrammes"),
    "ml" to listOf("ml", "millilitre", "millilitres"),
    "cl" to listOf("cl", "centilitre", "centilitres"),
    "dl" to listOf("dl", "décilitre", "décilitres"),
    "l" to listOf("l", "litre", "litres"),
    "tbsp" to listOf("c. à soupe", "c à soupe", "càs", "cs", "cuillère à soupe", "cuillères à soupe", "tbsp"),
    "tsp" to listOf("c. à café", "c à café", "càc", "cc", "cuillère à café", "cuillères à café", "tsp"),
    "pinch" to listOf("pincée", "pincées"),
    "dash" to listOf("trait", "traits", "filet", "filets"),
    "handful" to listOf("poignée", "poignées"),
    "clove" to listOf("gousse", "gousses"),
    "slice" to listOf("tranche", "tranches"),
    "bunch" to listOf("botte", "bottes", "bouquet", "bouquets"),
    "sprig" to listOf("brin", "brins"),
    "leaf" to listOf("feuille", "feuilles"),
    "can" to listOf("boîte", "boîtes", "conserve", "conserves"),
    "pack" to listOf("sachet", "sachets", "paquet", "paquets"),
    "jar" to listOf("pot", "pots"),
).flatMap { (code, aliases) -> aliases.map { it to code } }.sortedByDescending { it.first.length }

private val fractions = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)
private val quantityRe = Regex("""^(\d+(?:[.,]\d+)?(?:\s*/\s*\d+)?|[½¼¾⅓⅔])(?:\s*(?:à|-)\s*(\d+(?:[.,]\d+)?|[½¼¾⅓⅔]))?\s*""")

private fun parseNumber(s: String): Double? {
    if (s.length == 1) fractions[s[0]]?.let { return it }
    if ('/' in s) {
        val (a, b) = s.split('/').map { it.trim().toDoubleOrNull() ?: return null }
        return if (b == 0.0) null else a / b
    }
    return s.replace(',', '.').toDoubleOrNull()
}

/**
 * Parses "200 g de farine, tamisée" into quantity, unit code, name and note (requirements.md §9.1):
 * a range keeps its upper value, fractions become decimals, an unknown unit stays in the name.
 */
fun parseIngredient(line: String): IngredientLine {
    var rest = line.trim()
    var quantity: Double? = null
    quantityRe.find(rest)?.let { m ->
        quantity = parseNumber(m.groupValues[2].ifEmpty { m.groupValues[1] })
        if (quantity != null) rest = rest.substring(m.range.last + 1)
    }
    var unit: String? = null
    if (quantity != null) {
        val lower = rest.lowercase()
        unitAliases.firstOrNull { (alias, _) ->
            lower.startsWith(alias) && (lower.length == alias.length || !lower[alias.length].isLetter())
        }?.let { (alias, code) ->
            unit = code
            rest = rest.substring(alias.length).trimStart()
            rest = rest.removePrefix("d'").removePrefix("d’").let { if (it.startsWith("de ")) it.removePrefix("de ") else it }.trimStart()
        }
    }
    val comma = rest.indexOf(',')
    val name = (if (comma >= 0) rest.substring(0, comma) else rest).trim()
    val note = if (comma >= 0) rest.substring(comma + 1).trim().ifEmpty { null } else null
    return IngredientLine(quantity, unit, name.ifEmpty { line.trim() }, note, line.trim())
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
