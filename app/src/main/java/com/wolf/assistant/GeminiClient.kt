package com.wolf.assistant

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * مغز ابری جامع ولف نسخه ۴:
 * - تولید تصویر واقعی از روی متن (Image Generation)
 * - ساخت فایل‌های قابل دانلود (PDF, Python, HTML, CSV, JSON, MD, Kotlin)
 * - تحلیلگر حرفه‌ای ترید و چارت + داده‌های زنده بازار و RSI
 * - برنامه‌نویس ارشد فول‌استک (کدنویسی بدون حذف علامت‌های کد!)
 * - جستجوی زنده اینترنتی و بینایی ماشین
 */
class GeminiClient(
    private val prefs: SharedPreferences,
    private val memory: MemoryStore
) {

    private val chatModels = listOf(
        "gemini-2.5-flash",
        "gemini-3.5-flash",
        "gemini-flash-latest",
        "gemini-flash-lite-latest"
    )
    private val imageModels = listOf(
        "imagen-3.0-generate-002",
        "gemini-2.5-flash-image",
        "gemini-2.0-flash-exp-image-generation"
    )

    private val maxHistory = 24
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val title: String, val instruction: String) {
        JARVIS(
            "ولف کلاسیک (هوشمند و همه‌کاره)",
            "تو «ولف» هستی؛ یک دستیار صوتی و هوش مصنوعی فوق‌العاده باهوش، سریع و مؤدب شبیه جارویس. " +
            "کاربر را «قربان» خطاب کن. پاسخ‌هایت را روان، دقیق و کاربردی بنویس."
        ),
        TRADER(
            "تحلیلگر ارشد ترید و بازارهای مالی",
            "تو «ولف» در حالت تحلیلگر ارشد بازارهای مالی (کریپتو، فارکس، طلا و بورس) هستی. " +
            "بر اساس پرایس اکشن، سطوح حمایت و مقاومت، RSI، روند بازار و مدیریت ریسک تحلیل دقیق ارائه بده. " +
            "نقاط ورود احتمالی، حد ضرر (Stop Loss) و اهداف قیمتی (Take Profit) را مشخص کن و در انتها یادآوری کن که مدیریت سرمایه رعایت شود."
        ),
        CODER(
            "مهندس ارشد برنامه‌نویسی و معماری نرم‌افزار",
            "تو «ولف» در حالت مهندس ارشد برنامه‌نویسی هستی. کدهای کامل، تمیز، بدون باگ و آماده اجرا بنویس. " +
            "اگر کاربر خواست، توضیحات فنی کوتاه و دقیق بده و تمام بلوک‌های کد را کامل ارائه کن."
        ),
        TEACHER(
            "معلم و مترجم حرفه‌ای",
            "تو «ولف» در حالت معلم و مترجم دوزبانه هستی. مفاهیم سخت را ساده آموزش بده و ترجمه‌های روان انجام بده."
        ),
        CREATIVE(
            "ایده‌پرداز و نویسنده خلاق",
            "تو «ولف» در حالت خلاق هستی. برای تولید محتوا، سناریو، کپشن و ایده‌های کسب‌وکار پاسخ‌های جذاب بده."
        )
    }

    data class AiResult(
        val text: String,
        val generatedImage: Bitmap? = null,
        val isCodeOrTechnical: Boolean = false
    )

    fun getPersona(): Persona {
        val name = prefs.getString("persona", Persona.JARVIS.name) ?: Persona.JARVIS.name
        return try { Persona.valueOf(name) } catch (e: Exception) { Persona.JARVIS }
    }

    fun setPersona(p: Persona) {
        prefs.edit().putString("persona", p.name).apply()
    }

    @Synchronized
    fun clear() = history.clear()

    /**
     * تولید تصویر از روی توصیف متنی (فارسی یا انگلیسی)
     */
    @Synchronized
    fun generateImage(promptFaOrEn: String): AiResult {
        val key = prefs.getString("gemini_key", "").orEmpty().trim()
        if (key.isEmpty()) {
            return AiResult("قربان، برای تولید عکس ابتدا کلید Gemini API را در تنظیمات وارد کنید.")
        }

        // ابتدا پرامپت فارسی را به پرامپت انگلیسی دقیق و هنری تبدیل می‌کنیم تا خروجی عکس فوق‌العاده شود
        val englishPrompt = translatePromptForImage(key, promptFaOrEn)

        // تلاش ۱: مدل Imagen 3
        try {
            val bmp = callImagen3(key, englishPrompt)
            if (bmp != null) {
                return AiResult(
                    text = "تصویر شما آماده شد قربان:
«$promptFaOrEn»",
                    generatedImage = bmp
                )
            }
        } catch (_: Exception) { }

        // تلاش ۲: مدل‌های تولید تصویر بومی Gemini
        for (m in listOf("gemini-2.5-flash-image", "gemini-2.0-flash-exp-image-generation")) {
            try {
                val bmp = callGeminiNativeImage(m, key, englishPrompt)
                if (bmp != null) {
                    return AiResult(
                        text = "تصویر شما ساخته شد قربان:
«$promptFaOrEn»",
                        generatedImage = bmp
                    )
                }
            } catch (_: Exception) { }
        }

        return AiResult("قربان، سرور تولید تصویر در حال حاضر برای کلید API شما در دسترس نیست یا سهمیه آن پر شده است.")
    }

    private fun translatePromptForImage(key: String, prompt: String): String {
        return try {
            val sys = "Translate the user's image description into a detailed, high-quality English image generation prompt. Return ONLY the English prompt, nothing else."
            val res = callChat("gemini-2.5-flash", key, prompt, null, false, sys, includeHistory = false)
            res.trim().ifBlank { prompt }
        } catch (_: Exception) {
            prompt
        }
    }

    private fun callImagen3(key: String, englishPrompt: String): Bitmap? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 35000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)

            val payload = JSONObject()
                .put("instances", JSONArray().put(JSONObject().put("prompt", englishPrompt)))
                .put("parameters", JSONObject().put("sampleCount", 1))
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val preds = JSONObject(resp).optJSONArray("predictions") ?: return null
            if (preds.length() == 0) return null
            val b64 = preds.getJSONObject(0).optString("bytesBase64Encoded")
            if (b64.isBlank()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun callGeminiNativeImage(model: String, key: String, englishPrompt: String): Bitmap? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 35000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)

            val contents = JSONArray().put(turn("user", "Generate an image: $englishPrompt", null))
            val genConfig = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            val body = JSONObject()
                .put("contents", contents)
                .put("generationConfig", genConfig)

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inline != null) {
                    val b64 = inline.optString("data")
                    if (b64.isNotBlank()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * تولید محتوای خالص برای ساخت فایل (بدون حاشیه و توضیح اضافه)
     */
    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val key = prefs.getString("gemini_key", "").orEmpty().trim()
        if (key.isEmpty()) return null
        val sys = "You are a file content generator. Output ONLY the raw, clean, complete content for a .$ext file about the user's request. " +
            "Do NOT wrap in markdown code fences (no ```). Do NOT add conversational intro or outro. " +
            "If ext is csv, output valid comma-separated rows. If ext is py/kt/html/js/json, output valid executable code. If ext is pdf/txt/md, write a well-structured Persian document."
        for (m in chatModels) {
            try {
                val res = callChat(m, key, "File type: .$ext
Request: $topic", null, false, sys, includeHistory = false)
                    .replace(Regex("^```[a-zA-Z]*\n?"), "")
                    .replace(Regex("\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) return res
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * گفتگوی هوشمند، تحلیل کد، تحلیل ترید (با داده زنده بازار + چارت) و جستجوی وب
     */
    @Synchronized
    fun ask(
        text: String,
        image: Bitmap? = null,
        useLiveSearch: Boolean = true,
        forcePersona: Persona? = null,
        extraMarketContext: String = ""
    ): AiResult {
        val key = prefs.getString("gemini_key", "").orEmpty().trim()
        if (key.isEmpty()) {
            return AiResult("قربان، برای استفاده از هوش مصنوعی، تولید عکس، تحلیل ترید و برنامه‌نویسی، ابتدا کلید Gemini API را از دکمه تنظیمات (چرخ‌دنده بالا) وارد کنید.")
        }

        val activePersona = forcePersona ?: getPersona()
        val isCodeReq = activePersona == Persona.CODER ||
            Regex("(کد|برنامه|اسکریپت|پایتون|جاوا|کاتلین|html|css|js|python|sql|باگ|خطا|تابع|الگوریتم)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (extraMarketContext.isNotEmpty()) {
                append("

").append(extraMarketContext)
                append("
از این داده‌های زنده بازار برای تحلیل تکنیکال، بررسی وضعیت اشباع خرید/فروش RSI، حمایت و مقاومت و استراتژی ورود/خروج استفاده کن.")
            }
            if (image != null && (activePersona == Persona.TRADER || text.contains("چارت") || text.contains("نمودار") || text.contains("کندل") || text.contains("ترید"))) {
                append("
این تصویر یک چارت معاملاتی است. روند کلی، الگوهای کندلی، نواحی حمایت و مقاومت کلیدی، نقطه ورود احتمالی، حد ضرر (SL) و حد سود (TP) را با دقت تحلیل کن.")
            }
            append("
اگر درباره قیمت روز یا اخبار سؤال شد از جستجوی گوگل استفاده کن.")
            append(memory.asPromptContext())
        }

        var lastErr = ""
        for (m in chatModels) {
            try {
                val rawAnswer = callChat(m, key, text, image, useLiveSearch, sysPrompt, includeHistory = true)
                // اگر کد یا تحلیل تخصصی است، ساختار و کاراکترهای کد (* # ` _) را حفظ می‌کنیم تا کدها خراب نشوند!
                val finalAnswer = if (isCodeReq || activePersona == Persona.TRADER || extraMarketContext.isNotEmpty()) {
                    rawAnswer.trim()
                } else {
                    rawAnswer.replace(Regex("[*#`_]"), "").trim()
                }

                if (finalAnswer.isNotEmpty()) {
                    val userLog = if (image != null) "[تصویر ارسال شد] $text" else text
                    history.add("user" to userLog)
                    history.add("model" to finalAnswer)
                    while (history.size > maxHistory) history.removeAt(0)
                    return AiResult(finalAnswer, isCodeOrTechnical = isCodeReq)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        return AiResult("قربان، اتصال به مغز ابری برقرار نشد. ($lastErr)")
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val maxDim = 1024
        val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callChat(
        model: String,
        key: String,
        text: String,
        image: Bitmap?,
        useLiveSearch: Boolean,
        systemInstructionText: String,
        includeHistory: Boolean
    ): String {
        val contents = JSONArray()
        if (includeHistory) {
            for ((role, t) in history) {
                contents.put(turn(role, t, null))
            }
        }
        contents.put(turn("user", text, image))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstructionText))))
            .put("contents", contents)

        if (useLiveSearch && image == null) {
            val tools = JSONArray().put(JSONObject().put("google_search", JSONObject()))
            body.put("tools", tools)
        }

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 35000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (useLiveSearch && errBody.contains("google_search", ignoreCase = true)) {
                    return callChat(model, key, text, image, false, systemInstructionText, includeHistory)
                }
                throw IOException("HTTP $code")
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text")) sb.append(p.getString("text"))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun turn(role: String, text: String, image: Bitmap?): JSONObject {
        val parts = JSONArray()
        if (image != null) {
            val inlineData = JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", bitmapToBase64(image))
            parts.put(JSONObject().put("inline_data", inlineData))
        }
        parts.put(JSONObject().put("text", text.ifBlank { "این تصویر را تحلیل کن." }))
        return JSONObject()
            .put("role", role)
            .put("parts", parts)
    }
}
