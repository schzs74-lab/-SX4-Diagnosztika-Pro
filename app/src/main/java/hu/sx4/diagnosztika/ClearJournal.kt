package hu.sx4.diagnosztika

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** A new read cannot prove which vehicle an earlier write affected. Preserve unknown outcomes. */
class ClearJournal(private val prefs: SharedPreferences) {
    fun dispatch(source: String, protocol: Int, connection: String): String {
        check(!prefs.getBoolean("clearPending", false)) { "Előbb tekintsd át a korábbi bizonytalan törlési kísérletet" }
        val id = UUID.randomUUID().toString()
        val previous = prefs.getString("clearEntry", null)
        val history = JSONArray(prefs.getString("clearHistory", "[]"))
        if (previous != null) history.put(JSONObject(previous))
        val bounded = JSONArray(); for (i in maxOf(0, history.length()-20) until history.length()) bounded.put(history.get(i))
        val entry = JSONObject().put("id", id).put("source", source).put("protocol", protocol)
            .put("connection", connection).put("utc", System.currentTimeMillis()).put("outcome", "UNKNOWN")
        check(prefs.edit().putString("clearEntry", entry.toString()).putString("clearHistory", bounded.toString())
            .putBoolean("clearPending", true).commit()) { "A törlési kísérlet nem menthető; törlés tiltva" }
        return id
    }
    fun finish(id: String?, outcome: String) {
        if (id == null) return
        val entry = prefs.getString("clearEntry", null)?.let { JSONObject(it) } ?: return
        if (entry.optString("id") != id) return
        entry.put("outcome", outcome).put("resolvedUtc", System.currentTimeMillis())
        check(prefs.edit().putString("clearEntry", entry.toString()).putBoolean("clearPending", false).commit()) {
            "A törlési eredmény nem menthető; a korábbi figyelmeztetés megmarad"
        }
    }
    fun warning(): String {
        val entry = prefs.getString("clearEntry", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val details = entry?.let { "\nECU-forrás: ${it.optString("source")} • protokoll: ${it.optInt("protocol")} • ${Instant.ofEpochMilli(it.optLong("utc"))} (UTC)" } ?: ""
        return "A korábbi törlés eredménye BIZONYTALAN.$details\nA forráscím nem azonosítja egyedileg az autót. Egy új hibakódolvasás nem igazolja a korábbi törlés sikerét. Tekintsd át a korábbi kísérletet."
    }
    fun acknowledgeUnknown() {
        val entry = prefs.getString("clearEntry", null)?.let { JSONObject(it) } ?: JSONObject().put("outcome", "UNKNOWN_LEGACY")
        entry.put("userReviewedUtc", System.currentTimeMillis()).put("outcome", "USER_REVIEWED_UNKNOWN")
        check(prefs.edit().putString("clearEntry", entry.toString()).putBoolean("clearPending", false).commit())
    }
}
