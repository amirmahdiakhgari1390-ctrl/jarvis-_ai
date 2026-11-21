package com.wolf.assistant

import android.content.Context
import org.json.JSONArray

/**
 * حافظه بلندمدت دائمی ولف (ذخیره در حافظه گوشی برای همیشه تا زمانی که کاربر پاک کند)
 */
class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("wolf_memory", Context.MODE_PRIVATE)

    fun remember(fact: String): String {
        val clean = fact.trim()
        if (clean.isEmpty()) return "قربان، چه چیزی را به خاطر بسپارم؟"
        val list = getAll().toMutableList()
        if (clean !in list) {
            list.add(clean)
            saveAll(list)
        }
        return "به خاطر سپردم قربان: «$clean»"
    }

    fun getAll(): List<String> {
        val raw = prefs.getString("facts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAll(): String {
        prefs.edit().remove("facts").apply()
        return "تمام حافظه بلندمدت من پاک شد قربان."
    }

    fun asPromptContext(): String {
        val facts = getAll()
        if (facts.isEmpty()) return ""
        return "\nاطلاعات مهمی که کاربر قبلاً گفته به خاطر بسپاری:\n" +
            facts.joinToString("\n") { "- $it" }
    }

    private fun saveAll(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("facts", arr.toString()).apply()
    }
}
