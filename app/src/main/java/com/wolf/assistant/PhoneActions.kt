package com.wolf.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * موتور اجرای فرمان‌های واقعی روی اندروید (قابلیت‌هایی که چت جی‌پی‌تی هرگز به آن‌ها دسترسی ندارد)
 */
class PhoneActions(private val ctx: Context) {

    private var torchOn = false

    private data class AppInfo(val label: String, val pkg: String)

    companion object {
        private val ALIASES = mapOf(
            "واتساپ" to "whatsapp", "واتس اپ" to "whatsapp", "تلگرام" to "telegram",
            "اینستاگرام" to "instagram", "اینستا" to "instagram", "کروم" to "chrome",
            "یوتیوب" to "youtube", "دوربین" to "camera", "تنظیمات" to "settings",
            "نقشه" to "maps", "گوگل مپ" to "maps", "نشان" to "neshan", "بلد" to "balad",
            "گالری" to "photos", "مخاطبین" to "contacts",
            "ماشین حساب" to "calculator", "ساعت" to "clock", "جیمیل" to "gmail",
            "گوگل" to "google", "اسپاتیفای" to "spotify", "بله" to "bale", "ایتا" to "eitaa",
            "روبیکا" to "rubika", "دیوار" to "divar", "اسنپ" to "snapp", "تپسی" to "tapsi",
            "پلی استور" to "vending", "فایل" to "files", "شماره گیر" to "dialer",
            "تلفن" to "dialer", "پیامک" to "messaging", "پیام" to "messaging"
        )
    }

    private fun fold(s: String) =
        s.lowercase(Locale.ROOT).replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک').trim()

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ برنامه‌ها
    private fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            AppInfo(fold(it.loadLabel(pm).toString()), it.activityInfo.packageName)
        }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        return 1.0 - dp[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun findApp(name: String): AppInfo? {
        val n = fold(name)
        val apps = installedApps()
        apps.firstOrNull { it.label == n }?.let { return it }
        val target = ALIASES[n] ?: n
        apps.firstOrNull { it.label.contains(target) || it.pkg.lowercase(Locale.ROOT).contains(target) }?.let { return it }
        apps.firstOrNull { it.label.contains(n) }?.let { return it }
        return apps.map { it to similarity(it.label, n) }
            .filter { it.second >= 0.7 }
            .maxByOrNull { it.second }?.first
    }

    fun openApp(name: String): String {
        val app = findApp(name) ?: return "قربان، برنامه‌ای به نام $name پیدا نکردم."
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return "قربان، نتوانستم ${app.label} را باز کنم."
        return if (launch(intent)) "در حال باز کردن ${app.label}" else "قربان، نتوانستم ${app.label} را باز کنم."
    }

    // ------------------------------------------------------------------ سخت‌افزار و تنظیمات سریع
    fun torch(on: Boolean?): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "قربان، این گوشی چراغ قوه ندارد."
            torchOn = on ?: !torchOn
            cm.setTorchMode(id, torchOn)
            if (torchOn) "چراغ قوه روشن شد." else "چراغ قوه خاموش شد."
        } catch (e: Exception) {
            "قربان، نتوانستم چراغ قوه را کنترل کنم."
        }
    }

    fun volume(direction: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (direction) {
            1 -> { repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }; "صدا زیاد شد." }
            -1 -> { repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }; "صدا کم شد." }
            2 -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                "صدا روی حداکثر تنظیم شد."
            }
            else -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); "صدا قطع شد." }
        }
    }

    fun setRingerMode(silent: Boolean): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = if (silent) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            if (silent) "گوشی روی حالت ویبره قرار گرفت." else "صدای زنگ گوشی فعال شد."
        } catch (e: Exception) {
            "قربان، تغییر حالت زنگ نیازمند مجوز عدم مزاحمت گوشی است."
        }
    }

    fun battery(): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        if (level < 0) return "قربان، نتوانستم وضعیت باتری را بخوانم."
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val stateTxt = if (charging) "در حال شارژ" else "در حال مصرف"
        return "قربان، شارژ باتری ${level * 100 / scale} درصد است ($stateTxt، دمای باتری ${temp} درجه)."
    }

    fun openWifiPanel(): String =
        if (launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) "پنل اینترنت باز شد." else "نتوانستم تنظیمات را باز کنم."

    fun openBluetoothSettings(): String =
        if (launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) "تنظیمات بلوتوث باز شد." else "نتوانستم تنظیمات را باز کنم."

    fun openDisplaySettings(): String =
        if (launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))) "تنظیمات نور و صفحه نمایش باز شد." else "نتوانستم تنظیمات صفحه نمایش را باز کنم."

    // ------------------------------------------------------------------ تماس، پیامک و واتساپ
    private fun findContact(name: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )
        c?.use { if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1)) }
        return null
    }

    private fun resolveTarget(target: String): Pair<String, String>? {
        val digits = target.replace(" ", "").replace("-", "")
        if (digits.length >= 5 && digits.all { it.isDigit() || it == '+' }) {
            return Pair(digits, digits)
        }
        return findContact(target.trim())
    }

    fun call(target: String): String {
        val resolved = resolveTarget(target)
            ?: return "قربان، مخاطبی به نام ${target.trim()} پیدا نکردم (یا مجوز مخاطبین داده نشده)."
        val (label, number) = resolved
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (launch(Intent(action, uri))) "در حال تماس با $label" else "قربان، نتوانستم تماس بگیرم."
    }

    fun sendSms(target: String, message: String): String {
        val resolved = resolveTarget(target)
            ?: return "قربان، مخاطب ${target.trim()} پیدا نشد."
        val (label, number) = resolved
        if (message.isBlank()) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            return if (launch(i)) "صفحه پیامک برای $label باز شد." else "نتوانستم پیامک را باز کنم."
        }
        return try {
            if (granted(Manifest.permission.SEND_SMS)) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
                "پیامک با متن «$message» برای $label ارسال شد."
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                    putExtra("sms_body", message)
                }
                if (launch(i)) "متن پیامک برای $label آماده شد؛ دکمه ارسال را بزنید." else "نتوانستم پیامک را باز کنم."
            }
        } catch (e: Exception) {
            "قربان، ارسال پیامک با خطا مواجه شد."
        }
    }

    fun sendWhatsApp(target: String, message: String): String {
        val resolved = resolveTarget(target)
            ?: return "قربان، مخاطب ${target.trim()} در دفترچه تلفن پیدا نشد."
        val (label, rawNum) = resolved
        var cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
        if (cleanNum.startsWith("09")) cleanNum = "98" + cleanNum.substring(1)
        cleanNum = cleanNum.removePrefix("+")
        val url = "https://api.whatsapp.com/send?phone=$cleanNum&text=" + Uri.encode(message)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return if (launch(i)) "صفحه چت واتساپ با $label باز شد." else "قربان، واتساپ روی گوشی نصب نیست."
    }

    // ------------------------------------------------------------------ مسیریابی، یوتیوب و جستجو
    fun navigateTo(destination: String): String {
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        if (launch(mapIntent)) return "مسیریابی به سمت $destination در گوگل مپ شروع شد."
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination))
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) "نقشه برای $destination باز شد." else "نتوانستم نقشه را باز کنم."
    }

    fun playYouTube(query: String): String {
        val webUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
        }
        if (launch(ytIntent)) return "در حال جستجوی «$query» در یوتیوب."
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) "یوتیوب برای «$query» باز شد." else "نتوانستم یوتیوب را باز کنم."
    }

    fun search(q: String): String {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        return if (launch(Intent(Intent.ACTION_VIEW, uri))) "نتیجه‌ی جستجوی «$q» باز شد." else "قربان، نتوانستم مرورگر را باز کنم."
    }

    // ------------------------------------------------------------------ کلیپ‌بورد و ابزارهای سریع
    fun readClipboard(): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        return if (text.isNullOrEmpty()) "قربان، کلیپ‌بورد گوشی خالی است." else "متن کپی‌شده در کلیپ‌بورد: $text"
    }

    fun flipCoin(): String = if (Random.nextBoolean()) "شیر آمد قربان!" else "خط آمد قربان!"

    fun rollDice(): String = "تاس عدد ${Random.nextInt(1, 7)} را نشان می‌دهد قربان."

    fun randomPassword(len: Int = 12): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#%&*"
        val pass = (1..len.coerceIn(6, 32)).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Wolf Password", pass))
        return "رمز عبور قوی ساخته شد و در کلیپ‌بورد کپی شد: $pass"
    }

    // ------------------------------------------------------------------ زمان و یادداشت
    fun timer(seconds: Int, label: String): String {
        val s = seconds.coerceIn(1, 86400)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (launch(i)) "تایمر تنظیم شد." else "قربان، نتوانستم تایمر بگذارم."
    }

    fun alarm(hour: Int, minute: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) "آلارم برای ساعت $hour و $minute دقیقه تنظیم شد." else "قربان، نتوانستم آلارم بگذارم."
    }

    private val notesFile get() = File(ctx.filesDir, "notes.txt")

    fun saveNote(text: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        notesFile.appendText("[$stamp] $text\n")
        return "یادداشت شد قربان."
    }

    fun readNotes(): String {
        if (!notesFile.exists()) return "قربان، هنوز یادداشتی ندارید."
        val last = notesFile.readLines().filter { it.isNotBlank() }.takeLast(5)
        if (last.isEmpty()) return "قربان، هنوز یادداشتی ندارید."
        return "آخرین یادداشت‌ها: " + last.joinToString(" . ") { it.substringAfter("] ") }
    }
}
