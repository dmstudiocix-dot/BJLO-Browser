package com.bjlo.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Favorite(val name: String, val url: String)

/** Favoritos guardados como JSON en SharedPreferences "BJLO_FAVS". */
class FavoritesStore(context: Context) {

    private val sp = context.getSharedPreferences("BJLO_FAVS", Context.MODE_PRIVATE)

    fun all(): MutableList<Favorite> {
        val raw = sp.getString(KEY, null)
        if (raw == null) {
            val defaults = defaults()
            save(defaults)
            return defaults
        }
        val out = ArrayList<Favorite>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Favorite(o.getString("name"), o.getString("url")))
            }
        } catch (e: JSONException) {
            // datos dañados: se devuelve lo que se haya podido leer
        }
        return out
    }

    /** Devuelve false si la dirección ya estaba guardada. */
    fun add(fav: Favorite): Boolean {
        val list = all()
        if (list.any { it.url == fav.url }) return false
        list.add(fav)
        save(list)
        return true
    }

    fun remove(url: String) = save(all().filter { it.url != url })

    fun rename(url: String, newName: String) =
        save(all().map { if (it.url == url) it.copy(name = newName) else it })

    private fun save(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun defaults(): MutableList<Favorite> = mutableListOf(
        Favorite("BJLO", "https://bjlo.netlify.app"),
        Favorite("Google", "https://www.google.com")
    )

    companion object {
        private const val KEY = "favorites"
    }
}
