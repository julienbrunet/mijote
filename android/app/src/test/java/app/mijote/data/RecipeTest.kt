package app.mijote.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTest {
    @Test
    fun parsesIngredientLines() {
        parseIngredient("200 g de farine, tamisée").run {
            assertEquals(200.0, quantity!!, 0.0); assertEquals("g", unit); assertEquals("farine", name); assertEquals("tamisée", note)
        }
        parseIngredient("2 c. à soupe d'huile d'olive").run {
            assertEquals(2.0, quantity!!, 0.0); assertEquals("tbsp", unit); assertEquals("huile d'olive", name)
        }
        parseIngredient("3 œufs").run { assertEquals(3.0, quantity!!, 0.0); assertNull(unit); assertEquals("œufs", name) }
        parseIngredient("½ litre de lait").run { assertEquals(0.5, quantity!!, 0.0); assertEquals("l", unit); assertEquals("lait", name) }
        parseIngredient("1/2 citron").run { assertEquals(0.5, quantity!!, 0.0); assertEquals("citron", name) }
        parseIngredient("2 à 3 tomates").run { assertEquals(3.0, quantity!!, 0.0); assertEquals("tomates", name) }
        parseIngredient("1,5 kg de pommes de terre").run { assertEquals(1.5, quantity!!, 0.0); assertEquals("kg", unit) }
        parseIngredient("1 potiron").run { assertNull(unit); assertEquals("potiron", name) } // "pot" is not a prefix match
        parseIngredient("Sel, poivre").run { assertNull(quantity); assertNull(unit); assertEquals("Sel", name); assertEquals("poivre", note) }
    }

    @Test
    fun draftRoundTrip() {
        val r = parseRecipe(JSONObject("""{"id":"r1","title":"Soupe","dishType":"vegetarian","prepMinutes":10,"cookMinutes":null,
            "totalMinutes":10,"notes":null,"sourceUrl":"https://example.org/soupe","servings":2,"seasons":["autumn"],
            "mealTypes":["dinner"],"tags":["hiver","rapide"],"steps":["Couper","Cuire"],
            "ingredients":[{"rawText":"1 potiron","quantity":1,"unit":null,"name":"potiron","note":null}]}"""))
        val d = RecipeDraft.of(r)
        assertEquals("hiver, rapide", d.tags)
        assertEquals("Couper\nCuire", d.steps)
        assertEquals("1 potiron", d.ingredients)
        val json = d.copy(cookMinutes = "20", steps = "Couper\n\n  Cuire  \n").toJson()
        assertEquals(20, json.getInt("cookMinutes"))
        assertEquals(2, json.getJSONArray("steps").length())
        assertEquals("Cuire", json.getJSONArray("steps").getString(1))
        assertEquals("https://example.org/soupe", json.getString("sourceUrl"))
        assertEquals(2, json.getInt("servings"))
        assertEquals("autumn", json.getJSONArray("seasons").getString(0))
        assertEquals("potiron", json.getJSONArray("ingredients").getJSONObject(0).getString("name"))
        assertNull(d.problem())
        assertEquals("Donnez un titre à la recette.", d.copy(title = " ").problem())
        assertEquals("Choisissez au moins un repas : midi ou soir.", d.copy(mealTypes = emptySet()).problem())
    }

    @Test
    fun parsesManualMealsAndAdmin() {
        val s = parseSlot(JSONObject("""{"id":"s1","date":"2026-11-02","meal":"dinner","locked":true,"recipe":null,"title":"Commander une pizza"}"""))
        assertEquals("Commander une pizza", s.title)
        assertEquals(true, s.manual)
        val r = parseSlot(JSONObject("""{"id":"s2","date":"2026-11-02","meal":"lunch","locked":false,"recipe":null,"title":null}"""))
        assertEquals(false, r.manual)

        val inv = parseInvitations(JSONObject("""{"invitations":[{"id":"i1","role":"readonly","status":"pending",
            "expiresAt":"2026-11-09T10:00:00Z","usedBy":null}],"durations":[1,7,30],"defaultDays":7}"""))
        assertEquals("readonly", inv.list[0].role)
        assertNull(inv.list[0].usedBy)
        val created = parseNewInvitation(JSONObject("""{"code":"abc","link":"https://h/invite/abc","role":"member","days":7,"qr":"iVBORw=="}"""))
        assertEquals(4, created.qrPng!!.size)
        val users = parseAccounts(JSONObject("""{"users":[{"id":"u1","username":"lea","displayName":"Léa","role":"member","disabled":true}]}"""))
        assertEquals(true, users[0].disabled)
    }

    @Test
    fun filtersCombineGroupsAndChoices() {
        val veg = RecipeSummary("1", "Soupe", "vegetarian", 20, null, listOf("Hiver"), listOf("dinner"), listOf("winter"))
        val fish = RecipeSummary("2", "Saumon", "fish", 45, null, emptyList(), listOf("lunch", "dinner"))
        val meat = RecipeSummary("3", "Poulet", "meat", null, null, listOf("four"), listOf("lunch"), listOf("summer"))
        val all = listOf(veg, fish, meat)
        fun ids(f: RecipeFilters) = all.filter(f::matches).map { it.id }
        assertEquals(listOf("1", "2", "3"), ids(RecipeFilters()))
        assertEquals(listOf("1", "2"), ids(RecipeFilters(dishTypes = setOf("vegetarian", "fish"))))
        assertEquals(listOf("1"), ids(RecipeFilters(dishTypes = setOf("vegetarian", "fish"), durations = setOf("quick"))))
        assertEquals(listOf("1", "3"), ids(RecipeFilters(durations = setOf("quick")))) // no time counts as quick
        assertEquals(listOf("2", "3"), ids(RecipeFilters(meals = setOf("lunch"))))
        assertEquals(listOf("1", "2"), ids(RecipeFilters(seasons = setOf("winter")))) // no season = all year
        assertEquals(listOf("1"), ids(RecipeFilters(tags = setOf("hiver"))))
        assertEquals(3, RecipeFilters(dishTypes = setOf("fish"), meals = setOf("lunch"), tags = setOf("four")).count)
        val favs = listOf(veg.copy(favorite = true), fish, meat.copy(favorite = true))
        assertEquals(listOf("1", "3"), favs.filter(RecipeFilters(favorites = true)::matches).map { it.id })
        assertEquals(listOf("1"), favs.filter(RecipeFilters(favorites = true, durations = setOf("quick"), seasons = setOf("winter"))::matches).map { it.id })
        assertEquals(1, RecipeFilters(favorites = true).count)
    }
}
