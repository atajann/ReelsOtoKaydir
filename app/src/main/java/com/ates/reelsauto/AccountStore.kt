package com.ates.reelsauto

import android.content.Context
import org.json.JSONObject

/** Hesap hafızası: hangi hesap kaç ürün videosu paylaştı, takip edildi mi */
class AccountStore(context: Context) {
    private val sp = context.getSharedPreferences("rs_accounts", Context.MODE_PRIVATE)
    private val data: JSONObject = try { JSONObject(sp.getString("data", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    data class Info(val products: Int, val certain: Int, val followed: Boolean)

    private fun key(user: String) = user.trim().removePrefix("@").lowercase()

    fun get(user: String): Info {
        val o = data.optJSONObject(key(user)) ?: return Info(0, 0, false)
        return Info(o.optInt("p"), o.optInt("c"), o.optBoolean("f"))
    }

    fun record(user: String, confidence: Confidence) {
        if (user.isBlank() || confidence < Confidence.LIKELY) return
        val k = key(user)
        val o = data.optJSONObject(k) ?: JSONObject()
        o.put("p", o.optInt("p") + 1)
        if (confidence == Confidence.CERTAIN) o.put("c", o.optInt("c") + 1)
        o.put("t", System.currentTimeMillis())
        data.put(k, o)
        trim()
        save()
    }

    fun markFollowed(user: String) {
        val k = key(user)
        val o = data.optJSONObject(k) ?: JSONObject()
        o.put("f", true)
        o.put("ft", System.currentTimeMillis())
        o.put("t", System.currentTimeMillis())
        data.put(k, o)
        save()
    }

    fun followedList(): List<String> {
        val out = mutableListOf<Pair<String, Long>>()
        val it = data.keys()
        while (it.hasNext()) {
            val k = it.next()
            val o = data.optJSONObject(k) ?: continue
            if (o.optBoolean("f")) out += k to o.optLong("ft")
        }
        return out.sortedByDescending { it.second }.map { "@" + it.first }
    }

    fun productAccountCount(): Int {
        var n = 0
        val it = data.keys()
        while (it.hasNext()) { if ((data.optJSONObject(it.next())?.optInt("p") ?: 0) > 0) n++ }
        return n
    }

    private fun trim() {
        if (data.length() <= 1500) return
        val entries = mutableListOf<Pair<String, Long>>()
        val it = data.keys()
        while (it.hasNext()) {
            val k = it.next()
            val o = data.optJSONObject(k) ?: continue
            if (!o.optBoolean("f")) entries += k to o.optLong("t")
        }
        entries.sortedBy { it.second }.take(data.length() - 1200).forEach { data.remove(it.first) }
    }

    private fun save() = sp.edit().putString("data", data.toString()).apply()
}
