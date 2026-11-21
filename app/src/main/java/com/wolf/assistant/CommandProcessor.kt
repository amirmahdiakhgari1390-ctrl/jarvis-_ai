package com.wolf.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * مغز فرماندهی ولف نسخه ۴:
 * - تولید تصویر هوش مصنوعی (Image Generation)
 * - ساخت فایل‌های واقعی PDF / Python / CSV / HTML / TXT / JSON (File Generation)
 * - موتور تحلیل ترید، دریافت قیمت زنده و RSI + ماشین‌حساب مدیریت ریسک
 * - برنامه‌نویسی پیشرفته و حفظ ساختار کد
 * - کنترل کامل سخت‌افزار و اپلیکیشن‌های اندروید
 */
class CommandProcessor(
    private val ctx: Context,
    private val phone: PhoneActions,
    private val gemini: GeminiClient,
    private val memory: MemoryStore
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class ProcessResponse(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null,
        val speakText: String? = null
    )

    companion object {
        val WAKE_WORDS = listOf("wolf", "wolfe", "woof", "wulf", "ولف", "گرگ", "jarvis", "جارویس", "جارویز")
    }

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    '\u200c' -> ' '
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("[؟?!،؛,]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
    }

    fun stripWake(cmd: String): Pair<String, Boolean> {
        val tokens = cmd.split(" ")
        val kept = tokens.filter { it !in WAKE_WORDS }
        return Pair(kept.joinToString(" ").trim(), kept.size != tokens.size)
    }

    private fun has(cmd: String, vararg keys: String) = keys.any { cmd.contains(norm(it)) }
    private fun hasW(cmd: String, vararg keys: String): Boolean {
        val t = cmd.split(" ")
        return keys.any { norm(it) in t }
    }

    fun process(raw: String, image: Bitmap? = null, onReply: (ProcessResponse) -> Unit) {
        val cmd = norm(raw)

        // اگر کاربر عکسی از گالری/دوربین فرستاده (مثلاً چارت ترید، کد خطا، متن کتاب یا...)
        if (image != null) {
            io.execute {
                val res = gemini.ask(raw, image = image, useLiveSearch = false)
                val shortSpeak = if (res.isCodeOrTechnical) "تحلیل تصویر انجام شد قربان." else res.text
                main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
            }
            return
        }

        if (cmd.isBlank()) {
            onReply(ProcessResponse(""))
            return
        }

        // ۱. تشخیص درخواست تولید تصویر (Image Generation)
        val imgGenRegex = Regex("(تولید عکس|ساخت عکس|عکس بساز|تصویر بساز|تصویر تولید کن|نقاشی بکش|طراحی کن تصویر|generate image|draw)")
        if (imgGenRegex.containsMatchIn(cmd)) {
            val promptDesc = raw.replace(
                Regex("(لطفاً|لطفا|یک|یه|تولید عکس از|تولید عکس|ساخت عکس از|ساخت عکس|عکس بساز از|عکس بساز|تصویر بساز از|تصویر بساز|تصویر تولید کن از|تصویر تولید کن|نقاشی بکش از|نقاشی بکش|generate image of|generate image|draw)"),
                " "
            ).replace(Regex("\\s+"), " ").trim()

            if (promptDesc.isEmpty()) {
                onReply(ProcessResponse("قربان، بفرمایید تصویر چه چیزی را بسازم؟ مثلاً: عکس بساز از یک گرگ سایبرپانک در شب"))
                return
            }
            io.execute {
                val aiRes = gemini.generateImage(promptDesc)
                main.post {
                    onReply(
                        ProcessResponse(
                            text = aiRes.text,
                            generatedImage = aiRes.generatedImage,
                            speakText = if (aiRes.generatedImage != null) "تصویر شما آماده شد قربان." else aiRes.text
                        )
                    )
                }
            }
            return
        }

        // ۲. تشخیص درخواست ساخت فایل واقعی (PDF, Python, CSV, HTML, TXT, JSON, MD)
        if (has(cmd, "فایل") && has(cmd, "بساز", "تولید کن", "درست کن", "ایجاد کن", "بده", "خروجی")) {
            val ext = detectRequestedExtension(cmd)
            if (ext != null) {
                io.execute {
                    val content = gemini.generateRawFileContent(ext, raw)
                    if (content.isNullOrBlank()) {
                        main.post {
                            onReply(ProcessResponse("قربان، برای تولید محتوای هوشمند فایل، ابتدا کلید Gemini API را وارد کنید یا اینترنت را بررسی نمایید."))
                        }
                    } else {
                        val gf = FileGenerator.createFile(ctx, ext, "Wolf_${ext.uppercase()}", content)
                        val msg = "فایل ${gf.displayName} با موفقیت ساخته و ذخیره شد قربان.
می‌توانید همین الان آن را باز کنید یا به اشتراک بگذارید."
                        main.post {
                            onReply(
                                ProcessResponse(
                                    text = msg,
                                    generatedFile = gf,
                                    speakText = "فایل ${ext.uppercase()} شما ساخته شد قربان."
                                )
                            )
                        }
                    }
                }
                return
            }
        }

        // ۳. ماشین‌حساب مدیریت ریسک ترید (Position Size & Leverage)
        if (has(cmd, "حجم معامله", "مدیریت ریسک", "پوزیشن سایز", "position size")) {
            val nums = Regex("\\d+(?:\\.\\d+)?").findAll(cmd).map { it.value.toDouble() }.toList()
            if (nums.size >= 3) {
                val calcResult = TradingEngine.calculatePositionSize(nums[0], nums[1], nums[2])
                onReply(ProcessResponse(calcResult, speakText = "محاسبه مدیریت ریسک انجام شد قربان."))
                return
            } else {
                onReply(ProcessResponse("قربان، سه عدد را به ترتیب بگویید: سرمایه کل، درصد ریسک و درصد حد ضرر.
مثال: حجم معامله با سرمایه 1000 ریسک 2 درصد و استاپ 5 درصد"))
                return
            }
        }

        // ۴. فرمان‌های محلی و سخت‌افزاری گوشی
        val local = handleLocal(cmd)
        if (local != null) {
            onReply(ProcessResponse(local))
            return
        }

        // ۵. تحلیل تخصصی ترید (دریافت قیمت زنده + محاسبه RSI-14 + تحلیل هوش مصنوعی)
        val isTradeQuery = has(cmd, "تحلیل", "ترید", "سیگنال", "حمایت", "مقاومت", "پرایس اکشن", "اندیکاتور", "rsi", "ارز دیجیتال", "رمزارز", "کریپتو", "فارکس")
        val detectedCoin = TradingEngine.detectCoinId(cmd)
        if (isTradeQuery || detectedCoin != null) {
            io.execute {
                val snap = detectedCoin?.let { TradingEngine.fetchMarketData(it) }
                val marketCtx = if (snap != null) TradingEngine.buildTechnicalContext(snap) else ""
                val res = gemini.ask(
                    text = raw,
                    image = null,
                    useLiveSearch = true,
                    forcePersona = if (isTradeQuery) GeminiClient.Persona.TRADER else null,
                    extraMarketContext = marketCtx
                )
                val shortSpeak = if (res.text.length > 260) {
                    "تحلیل بازار انجام شد قربان. جزئیات کامل روی صفحه نمایش داده شد."
                } else res.text
                main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
            }
            return
        }

        // ۶. سایر درخواست‌ها (برنامه‌نویسی، پرسش و پاسخ عمومی، جستجوی زنده گوگل)
        io.execute {
            val res = gemini.ask(raw, image = null, useLiveSearch = true)
            val shortSpeak = if (res.isCodeOrTechnical || res.text.length > 320) {
                "پاسخ و کدهای درخواستی شما آماده شد قربان."
            } else res.text
            main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
        }
    }

    private fun detectRequestedExtension(cmd: String): String? {
        return when {
            has(cmd, "pdf", "پی دی اف", "پی‌دی‌اف") -> "pdf"
            has(cmd, "python", "پایتون", "py") -> "py"
            has(cmd, "csv", "اکسل", "جدول") -> "csv"
            has(cmd, "html", "سایت", "صفحه وب") -> "html"
            has(cmd, "kotlin", "کاتلین", "kt") -> "kt"
            has(cmd, "javascript", "جاوااسکریپت", "جاوا اسکریپت", "js") -> "js"
            has(cmd, "json", "جیسون") -> "json"
            has(cmd, "markdown", "مارک داون", "md") -> "md"
            has(cmd, "txt", "متنی", "متن") -> "txt"
            else -> null
        }
    }

    private fun handleLocal(cmd: String): String? {

        // --- حافظه بلندمدت دائمی
        if (has(cmd, "حافظه بلندمدت را پاک کن", "حافظه رو پاک کن", "همه چیز را فراموش کن")) {
            gemini.clear()
            return memory.clearAll()
        }
        if (has(cmd, "چی یادته", "چی به خاطر داری", "حافظه ات چیه", "حافظه ت چیه")) {
            val facts = memory.getAll()
            return if (facts.isEmpty()) "قربان، هنوز مورد خاصی در حافظه بلندمدت ثبت نشده است."
            else "این موارد را به خاطر دارم: " + facts.joinToString(" ، ")
        }
        val remKey = listOf("به خاطر بسپار که", "به خاطر بسپار", "یادت باشه که", "یادت باشه", "حفظ کن که", "remember that", "remember").firstOrNull { has(cmd, it) }
        if (remKey != null) {
            val fact = cmd.substringAfter(norm(remKey)).trim()
            return memory.remember(fact)
        }

        // --- پاک کردن گفتگوی فعلی
        if (has(cmd, "گفتگو را فراموش کن", "گفتگو رو فراموش کن", "چت را پاک کن", "forget chat")) {
            gemini.clear(); return "حافظه‌ی گفتگوی فعلی پاک شد قربان."
        }

        // --- ارسال پیام واتساپ
        if (has(cmd, "واتساپ", "واتس اپ", "whatsapp") && has(cmd, "پیام", "بفرست", "بگو", "send")) {
            val afterTo = if (cmd.contains(" به ")) cmd.substringAfter(" به ") else cmd.substringAfter(" برای ")
            val parts = afterTo.split(Regex("(پیام بده|بگو|بفرست|که)"), limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                return phone.sendWhatsApp(parts[0].trim(), parts[1].trim())
            }
        }

        // --- ارسال پیامک SMS
        val smsKey = listOf("پیامک بده به", "پیامک بزن به", "اس ام اس بده به", "پیام بده به", "send sms to").firstOrNull { has(cmd, it) }
        if (smsKey != null) {
            val rest = cmd.substringAfter(norm(smsKey)).trim()
            val tokens = rest.split(" ")
            if (tokens.isNotEmpty()) {
                val target = tokens[0]
                val msg = tokens.drop(1).joinToString(" ").removePrefix("که ").trim()
                return phone.sendSms(target, msg)
            }
        }

        // --- مسیریابی با نقشه
        val navKey = listOf("مسیریابی به", "مسیریابی تا", "مسیر به", "مسیر تا", "برو به", "navigate to").firstOrNull { has(cmd, it) }
        if (navKey != null) {
            val dest = cmd.substringAfter(norm(navKey)).trim()
            if (dest.isNotEmpty()) return phone.navigateTo(dest)
        }

        // --- پخش در یوتیوب
        if (has(cmd, "یوتیوب", "youtube") && has(cmd, "پخش", "بگرد", "جستجو", "سرچ", "play", "search")) {
            val q = cmd.replace(Regex("(در یوتیوب|توی یوتیوب|یوتیوب|پخش کن|جستجو کن|سرچ کن|پیدا کن|youtube|play|search)"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (q.isNotEmpty()) return phone.playYouTube(q)
        }

        // --- کلیپ‌بورد، سکه، تاس، رمز عبور
        if (has(cmd, "کلیپ بورد", "کلیپ‌بورد", "چی کپی کردم", "متن کپی شده", "clipboard")) return phone.readClipboard()
        if (has(cmd, "شیر یا خط", "سکه بنداز", "flip a coin")) return phone.flipCoin()
        if (has(cmd, "تاس بنداز", "یه تاس", "roll a dice")) return phone.rollDice()
        if (has(cmd, "رمز عبور بساز", "پسورد بساز", "رمز قوی", "generate password")) return phone.randomPassword()

        // --- وای‌فای / بلوتوث / نور صفحه / حالت ویبره
        if (has(cmd, "وای فای", "وایفای", "wifi", "wi-fi")) return phone.openWifiPanel()
        if (has(cmd, "بلوتوث", "bluetooth")) return phone.openBluetoothSettings()
        if (has(cmd, "نور صفحه", "روشنایی", "صفحه نمایش", "brightness")) return phone.openDisplaySettings()
        if (has(cmd, "حالت ویبره", "گوشی را سایلنت", "گوشی رو سایلنت", "silent mode")) return phone.setRingerMode(true)
        if (has(cmd, "حالت عادی", "از سایلنت دربیار", "صدای زنگ را وصل کن")) return phone.setRingerMode(false)

        // --- چراغ قوه
        if (has(cmd, "چراغ قوه", "فلش", "flashlight", "torch")) {
            val off = has(cmd, "خاموش") || hasW(cmd, "off")
            val on = has(cmd, "روشن", "بزن") || hasW(cmd, "on")
            return phone.torch(if (off) false else if (on) true else null)
        }

        // --- صدا
        if (has(cmd, "صدا", "ولوم", "volume")) {
            if (has(cmd, "حداکثر", "آخر", "ماکزیمم", "max")) return phone.volume(2)
            if (has(cmd, "بی صدا", "قطع", "mute", "سایلنت")) return phone.volume(0)
            if (has(cmd, "زیاد", "بالا", "بلند", "افزایش", "increase") || hasW(cmd, "up")) return phone.volume(1)
            if (has(cmd, "کم", "پایین", "آهسته", "کاهش", "decrease") || hasW(cmd, "down")) return phone.volume(-1)
        }

        // --- باتری
        if (has(cmd, "باتری", "شارژ", "battery")) return phone.battery()

        // --- تماس
        val callKey = listOf("تماس بگیر با", "تماس بگیر", "زنگ بزن به", "زنگ بزن", "call").firstOrNull { has(cmd, it) }
        if (callKey != null) {
            val k = norm(callKey)
            val target = (cmd.substringAfter(k).ifBlank { cmd.substringBefore(k) })
                .replace(Regex("(^| )(با|به|شماره|لطفا)( |$)"), " ").trim()
            if (target.isEmpty()) return "قربان، با چه کسی تماس بگیرم؟"
            return phone.call(target)
        }

        // --- آلارم
        if (has(cmd, "آلارم", "الارم", "alarm", "بیدارم کن")) {
            val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
            var hour = nums.getOrNull(0) ?: Numbers.parse(cmd)?.toInt()
                ?: return "قربان، آلارم را برای چه ساعتی بگذارم؟"
            var minute = nums.getOrNull(1) ?: 0
            if (minute == 0 && has(cmd, "و نیم")) minute = 30
            if (hour < 12 && has(cmd, "عصر", "بعدازظهر", "بعد از ظهر", "شب") || (hour < 12 && hasW(cmd, "pm"))) hour += 12
            if (hour !in 0..23 || minute !in 0..59) return "قربان، این ساعت درست نیست."
            return phone.alarm(hour, minute)
        }

        // --- تایمر
        if (has(cmd, "تایمر", "زمان سنج", "timer", "یادآوری", "یادآور", "remind")) {
            val n = Numbers.parse(cmd) ?: return "قربان، تایمر را برای چند دقیقه بگذارم؟"
            val secs = when {
                has(cmd, "ثانیه", "second") -> n
                has(cmd, "ساعت", "hour") -> n * 3600
                else -> n * 60
            }
            val label = if (cmd.contains(" که ")) cmd.substringAfter(" که ").trim() else ""
            return phone.timer(secs.toInt(), label)
        }

        // --- یادداشت
        if (has(cmd, "یادداشت", "note")) {
            if (has(cmd, "بخون", "بخوان", "read", "چیه") || hasW(cmd, "ها")) return phone.readNotes()
            val key = listOf("یادداشت کن", "یادداشت", "note").first { has(cmd, it) }
            val k = norm(key)
            val content = cmd.substringAfter(k).ifBlank { cmd.substringBefore(k) }
                .replace(Regex("(^| )(را|رو|که)( |$)"), " ").trim()
            if (content.isEmpty()) return "قربان، چه چیزی را یادداشت کنم؟"
            return phone.saveNote(content)
        }

        // --- تاریخ و ساعت
        if (has(cmd, "تاریخ", "امروز چندم", "چندمه", "what day", "date")) return Jalali.todayText()
        if (has(cmd, "ساعت چنده", "ساعت چند", "ساعت الان", "what time", "the time") || cmd == "ساعت") {
            val now = Calendar.getInstance()
            val h = now.get(Calendar.HOUR_OF_DAY)
            val m = now.get(Calendar.MINUTE)
            return if (m == 0) "قربان، ساعت $h است." else "قربان، ساعت $h و $m دقیقه است."
        }

        // --- ماشین‌حساب
        var e = cmd
        listOf(
            "به علاوه" to "+", "بعلاوه" to "+", "plus" to "+",
            "منهای" to "-", "minus" to "-",
            "ضرب در" to "*", "ضربدر" to "*", "times" to "*", "×" to "*", " x " to "*",
            "تقسیم بر" to "/", "تقسیم" to "/", "divided by" to "/", "÷" to "/"
        ).forEach { (k, v) -> e = e.replace(k, " $v ") }
        e = e.replace(Regex("(حساب کن|محاسبه کن|چند میشه|چند می شه|چنده|برابر با|برابر|calculate|what is|=)"), " ").trim()
        if (Regex("[+\\-*/]").containsMatchIn(e) && e.matches(Regex("[\\d\\s+\\-*/().]+"))) {
            val v = Calc.eval(e)
            if (v == null || v.isNaN() || v.isInfinite()) return "قربان، این عبارت قابل محاسبه نیست."
            return "قربان، جواب ${Calc.format(v)} است."
        }

        // --- جستجو در مرورگر
        val searchKey = listOf("در گوگل سرچ کن", "در گوگل جستجو کن", "مرورگر را باز کن و").firstOrNull { has(cmd, it) }
        if (searchKey != null) {
            val q = cmd.substringAfter(norm(searchKey)).trim()
            if (q.isNotEmpty()) return phone.search(q)
        }

        // --- باز کردن برنامه
        val openKey = listOf("باز کن", "بازکن", "اجرا کن", "اجرا", "open", "launch").firstOrNull { has(cmd, it) }
        if (openKey != null) {
            val filler = Regex("(^| )(برنامه|اپ|اپلیکیشن|را|رو|لطفا|app|the|please|و)( |$)")
            var name = cmd.replace(norm(openKey), " ")
            repeat(2) { name = name.replace(filler, " ") }
            name = name.trim()
            return if (name.isEmpty()) "قربان، کدام برنامه را باز کنم؟" else phone.openApp(name)
        }

        // --- سلام و احوال‌پرسی
        if (cmd.split(" ").size <= 6) {
            if (has(cmd, "ممنون", "مرسی", "thank")) return "خواهش می‌کنم قربان."
            if (has(cmd, "تو کی", "اسمت چیه", "کی هستی", "who are you")) return "من ولف نسخه ۴ هستم؛ دستیار هوشمند، برنامه‌نویس، تحلیلگر ترید و تولیدکننده تصویر و فایل."
            if (has(cmd, "حالت چطوره", "چطوری", "how are you")) return "عالی‌ام قربان، تمام موتورهای تحلیل و هوش مصنوعی آماده هستند. در خدمتم."
            if (hasW(cmd, "سلام", "درود", "hello", "hi", "hey")) return "سلام قربان، در خدمتم."
        }

        return null
    }
}
