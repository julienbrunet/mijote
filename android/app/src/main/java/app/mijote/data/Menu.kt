package app.mijote.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Recipe summary shown in a menu. */
/** [image] is the API path of the thumbnail (needs the access token), null without picture. */
data class MenuRecipe(val id: String, val title: String, val dishType: String, val minutes: Int?, val image: String? = null)

/**
 * One planned meal: a [recipe], or a manual meal with a free [title] ("Commander une pizza"),
 * or neither when no recipe suited the slot. [locked] meals are kept when the period is generated again.
 */
data class MenuSlot(
    val id: String, val date: LocalDate, val meal: String, val recipe: MenuRecipe?, val locked: Boolean, val title: String? = null,
    /** Covers of the meal, shared by the family; null for older meals (use the user's preference). */
    val people: Int? = null,
    val version: Int = 0,
) {
    val manual get() = title != null
}

/** Meals planned by the family settings, Monday first: lunch_dinner, dinner or none. */
data class GenerationInfo(val recipeCount: Int, val minRecipes: Int, val horizonDays: Int, val defaultMeals: List<String>) {
    val enoughRecipes get() = recipeCount >= minRecipes && recipeCount > 0
}

data class Menu(val today: LocalDate, val slots: List<MenuSlot>, val info: GenerationInfo)

/** Meal plans of `POST /api/v1/menu/generate`. */
enum class MealPlan(val code: String) { DEFAULT("default"), LUNCH_DINNER("lunch_dinner"), LUNCH("lunch"), DINNER("dinner") }

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

private fun parseSlots(a: JSONArray?): List<MenuSlot> = (0 until (a?.length() ?: 0)).map { parseSlot(a!!.getJSONObject(it)) }

fun parseSlot(o: JSONObject): MenuSlot {
    val r = o.optJSONObject("recipe")
    return MenuSlot(
        id = o.getString("id"),
        date = LocalDate.parse(o.getString("date")),
        meal = o.getString("meal"),
        recipe = r?.let {
            MenuRecipe(it.getString("id"), it.getString("title"), it.optString("dishType"),
                it.optIntOrNull("totalMinutes") ?: it.optIntOrNull("prepMinutes"),
                if (it.isNull("image")) null else it.optString("image").ifEmpty { null })
        },
        locked = o.optBoolean("locked"),
        title = if (o.isNull("title")) null else o.optString("title").ifEmpty { null },
        people = if (!o.has("people") || o.isNull("people")) null else o.optInt("people"),
        version = o.optInt("version"),
    )
}

fun parseMenu(o: JSONObject): Menu {
    val g = o.getJSONObject("generation")
    val meals = g.optJSONArray("defaultMeals")
    return Menu(
        today = LocalDate.parse(o.getString("today")),
        slots = parseSlots(o.optJSONArray("slots")),
        info = GenerationInfo(g.optInt("recipeCount"), g.optInt("minRecipes"), g.optInt("horizonDays", 7),
            (0 until (meals?.length() ?: 0)).map { meals!!.getString(it) }),
    )
}

fun parseGenerated(o: JSONObject): List<MenuSlot> = parseSlots(o.optJSONArray("slots"))

/** Meals planned on [date] with [plan], as the server computes them. */
fun mealsOn(plan: MealPlan, defaults: List<String>, date: LocalDate): List<String> {
    val code = if (plan == MealPlan.DEFAULT) defaults.getOrElse(date.dayOfWeek.value - 1) { "lunch_dinner" } else plan.code
    return when (code) {
        "lunch_dinner" -> listOf("lunch", "dinner")
        "lunch" -> listOf("lunch")
        "dinner" -> listOf("dinner")
        else -> emptyList()
    }
}

/** Last day covered by [count] meals from [start] (null when no meal can be planned within 60 days). */
fun periodEnd(plan: MealPlan, defaults: List<String>, start: LocalDate, count: Int): LocalDate? {
    var left = count
    var d = start
    repeat(60) {
        left -= mealsOn(plan, defaults, d).size
        if (left <= 0) return d
        d = d.plusDays(1)
    }
    return null
}

/** Meals planned by the family settings over [days] days from [start]: the default count. */
fun defaultMealCount(defaults: List<String>, start: LocalDate, days: Int): Int =
    (0 until days).sumOf { mealsOn(MealPlan.DEFAULT, defaults, start.plusDays(it.toLong())).size }

/** Next Monday strictly after [d]. */
fun nextMonday(d: LocalDate): LocalDate = d.plusDays(((DayOfWeek.MONDAY.value - d.dayOfWeek.value + 6) % 7 + 1).toLong())

/**
 * What generating from [start] to [end] does to the meals already planned there (requirements.md §10):
 * (replaced, kept). Locked meals and manual meals are kept; the others are replaced or removed.
 */
fun periodImpact(slots: List<MenuSlot>, start: LocalDate, end: LocalDate): Pair<Int, Int> {
    val inPeriod = slots.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
    val kept = inPeriod.count { it.locked || it.manual }
    return (inPeriod.size - kept) to kept
}
