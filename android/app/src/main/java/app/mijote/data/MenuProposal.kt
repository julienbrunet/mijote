package app.mijote.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Only the confirmation endpoint publishes these slots to the shared menu. */
data class MenuProposal(
    val from: LocalDate,
    val to: LocalDate,
    val slots: List<MenuSlot>,
    val base: List<SlotVersion>,
) {
    fun withSlot(slot: MenuSlot): MenuProposal = copy(slots =
        (slots.filterNot { it.date == slot.date && it.meal == slot.meal } + slot)
            .sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" }))

    fun move(sourceId: String, targetId: String): MenuProposal {
        val from = slots.indexOfFirst { it.id == sourceId }
        val to = slots.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return this
        val contents = slots.toMutableList()
        contents.add(to, contents.removeAt(from))
        return copy(slots = slots.mapIndexed { index, position ->
            val content = contents[index]
            position.copy(recipe = content.recipe, title = content.title, locked = content.locked, people = content.people)
        })
    }

    fun toJson(): JSONObject = JSONObject().put("from", from.toString()).put("to", to.toString())
        .put("slots", proposalSlotsJson(slots))
        .put("base", JSONArray().also { array -> base.forEach { array.put(JSONObject().put("id", it.id).put("version", it.version)) } })

    companion object {
        fun parse(json: JSONObject): MenuProposal {
            val versions = json.getJSONArray("base")
            return MenuProposal(LocalDate.parse(json.getString("from")), LocalDate.parse(json.getString("to")),
                parseGenerated(json).sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" }), (0 until versions.length()).map {
                    val version = versions.getJSONObject(it)
                    SlotVersion(version.getString("id"), version.getInt("version"))
                })
        }
    }
}

data class SlotVersion(val id: String, val version: Int)

internal fun proposalSlotsJson(slots: List<MenuSlot>): JSONArray = JSONArray().also { array ->
    slots.forEach { slot ->
        array.put(JSONObject().put("id", slot.id).put("date", slot.date.toString()).put("meal", slot.meal)
            .put("recipe", slot.recipe?.let { JSONObject().put("id", it.id) } ?: JSONObject.NULL)
            .put("title", slot.title ?: JSONObject.NULL).put("locked", slot.locked).put("people", slot.people ?: JSONObject.NULL))
    }
}

/** Reorder only the affected days, with the exact saved versions as a conflict baseline. */
fun Menu.moveProposal(sourceId: String, targetId: String): MenuProposal? {
    val source = slots.find { it.id == sourceId } ?: return null
    val target = slots.find { it.id == targetId } ?: run {
        val parts = targetId.split('|')
        val date = parts.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val meal = parts.getOrNull(1)?.takeIf { it == "lunch" || it == "dinner" } ?: return null
        if (parts.size != 2 || date < today || slots.any { it.date == date && it.meal == meal }) return null
        MenuSlot(targetId, date, meal, null, false)
    }
    if (sourceId == targetId) return null
    val from = minOf(source.date, target.date)
    val to = maxOf(source.date, target.date)
    val period = slots.filter { it.date >= from && it.date <= to }
        .sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" })
    val proposal = MenuProposal(from, to, period, period.map { SlotVersion(it.id, it.version) })
    return if (slots.any { it.id == targetId }) proposal.move(sourceId, targetId)
    else proposal.copy(slots = (period.filterNot { it.id == sourceId } + source.copy(id = targetId, date = target.date, meal = target.meal))
        .sortedWith(compareBy<MenuSlot> { it.date }.thenBy { it.meal != "lunch" }))
}
