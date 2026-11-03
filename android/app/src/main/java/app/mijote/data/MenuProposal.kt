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
