package com.wolf.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var processor: CommandProcessor

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    private var speaking = false
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusBadge: TextView
    private lateinit var liveTranscriptView: TextView
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var micIconView: ImageView
    private lateinit var micHintView: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var commandInput: EditText
    private lateinit var wakeSwitch: Switch

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ------------------------------------------------------------------ چرخه‌ی عمر
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("wolf", Context.MODE_PRIVATE)
        processor = CommandProcessor(this, PhoneActions(this), GeminiClient(prefs))
        buildModernUi()
        initTts()
        if (!granted(Manifest.permission.RECORD_AUDIO)) askPermissions()
    }

    override fun onStop() {
        super.onStop()
        if (listening) stopListening("متوقف شد (برنامه در پس‌زمینه رفت)")
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
            ),
            1
        )
    }

    // ------------------------------------------------------------------ رابط کاربری مدرن
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(
        fillHex: String,
        radiusDp: Int,
        strokeHex: String? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
            }
        }
    }

    private fun buildModernUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#060913"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // ==================== 1. هدر بالا (Top Bar) ====================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_wolf_logo)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(10)
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "WOLF AI"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        statusBadge = TextView(this).apply {
            text = "آماده"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10)
            }
        }

        titleRow.addView(titleText)
        titleRow.addView(statusBadge)

        val subtitleText = TextView(this).apply {
            text = "دستیار هوشمند صوتی و متنی فارسی"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(2), 0, 0)
        }

        titleCol.addView(titleRow)
        titleCol.addView(subtitleText)

        val clearBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_trash)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "پاک کردن گفتگو"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                chatAdapter.clearAll()
                addWelcomeMessage()
                Toast.makeText(this@MainActivity, "تاریخچه گفتگو پاک شد", Toast.LENGTH_SHORT).show()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "تنظیمات"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { showSettingsDialog() }
        }

        topBar.addView(logoIcon)
        topBar.addView(titleCol)
        topBar.addView(clearBtn)
        topBar.addView(settingsBtn)

        val topDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        // ==================== 2. بخش انیمیشن صوتی (Voice Orb Stage) ====================
        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.parseColor("#080D1A"))
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(116))
            setOnClickListener { toggleListening() }
        }

        voiceOrb = VoiceOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        micIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        }

        orbContainer.addView(voiceOrb)
        orbContainer.addView(micIconView)

        micHintView = TextView(this).apply {
            text = "برای صحبت، دایره را لمس کنید"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }

        liveTranscriptView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = View.GONE
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }

        orbStage.addView(orbContainer)
        orbStage.addView(micHintView)
        orbStage.addView(liveTranscriptView)

        // ==================== 3. لیست پیام‌های گفتگو (Chat RecyclerView) ====================
        chatAdapter = ChatAdapter(mutableListOf())
        chatRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            setPadding(dp(14), dp(8), dp(14), dp(8))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // ==================== 4. نوار دستورهای سریع (Quick Chips) ====================
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickCommands = listOf(
            "📅 تاریخ و ساعت" to "امروز چندمه و ساعت چنده؟",
            "🔋 وضعیت باتری" to "شارژ باتری چقدره؟",
            "🔦 چراغ قوه" to "چراغ قوه را روشن کن",
            "📝 خواندن یادداشت‌ها" to "یادداشت‌های من را بخوان",
            "🌐 تنظیمات وای‌فای" to "تنظیمات وای فای را باز کن",
            "📱 باز کردن تلگرام" to "تلگرام را باز کن"
        )

        for ((label, cmd) in quickCommands) {
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedBg("#111827", 20, "#1E293B", 1)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    submitUserCommand(cmd, speakOut = true)
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)

        // ==================== 5. نوار پایین: ورودی متن + حالت Wake Word ====================
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val bottomDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        // ردیف ورودی متن و دکمه ارسال
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#111827", 24, "#1E293B", 1)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }

        commandInput = EditText(this).apply {
            hint = "دستور یا سؤال خود را بنویسید…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14.5f
            background = null
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTypedText()
                    true
                } else false
            }
        }

        val sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = roundedBg("#38BDF8", 20)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "ارسال"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { sendTypedText() }
        }

        inputRow.addView(commandInput)
        inputRow.addView(sendBtn)

        // ردیف سوییچ Wake Word
        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), 0)
        }

        val wakeLabel = TextView(this).apply {
            text = "حالت بیدارباش: فقط با گفتن «ولف» یا «جارویس» عمل کند"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        wakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("need_wake", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("need_wake", checked).apply()
            }
        }

        wakeRow.addView(wakeLabel)
        wakeRow.addView(wakeSwitch)

        bottomPanel.addView(inputRow)
        bottomPanel.addView(wakeRow)

        // اسمبل کردن کل صفحه
        root.addView(topBar)
        root.addView(topDivider)
        root.addView(orbStage)
        root.addView(chatRecycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(chipScroll)
        root.addView(bottomDivider)
        root.addView(bottomPanel)

        setContentView(root)
        addWelcomeMessage()
    }

    private fun addWelcomeMessage() {
        val hasKey = !prefs.getString("gemini_key", "").isNullOrBlank()
        val msg = if (hasKey) {
            "سلام! من ولف هستم. می‌تونی با لمس دایره بالا با من حرف بزنی یا همین پایین دستورت رو بنویسی."
        } else {
            "سلام! من ولف هستم. دستورهای گوشی (تماس، چراغ قوه، باز کردن برنامه‌ها، ساعت و...) همین الان آماده است. برای هوش مصنوعی و پاسخ به سؤال‌های عمومی، از آیکون چرخ‌دنده بالا کلید Gemini API را وارد کن."
        }
        appendMessage(msg, ChatMessage.Sender.WOLF)
    }

    private fun appendMessage(text: String, sender: ChatMessage.Sender) {
        chatAdapter.addMessage(ChatMessage(text, sender, timeFmt.format(Date())))
        chatRecycler.post {
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun sendTypedText() {
        val txt = commandInput.text.toString().trim()
        if (txt.isEmpty()) return
        commandInput.setText("")
        submitUserCommand(txt, speakOut = true)
    }

    private fun setStatus(status: String, orbMode: VoiceOrbView.Mode) {
        statusBadge.text = status
        voiceOrb.mode = orbMode
        when (orbMode) {
            VoiceOrbView.Mode.IDLE -> {
                statusBadge.setTextColor(Color.parseColor("#38BDF8"))
                statusBadge.background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
                micIconView.setImageResource(R.drawable.ic_mic)
                micHintView.text = "برای صحبت، دایره را لمس کنید"
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.LISTENING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#083344", 20, "#0891B2", 1)
                micIconView.setImageResource(R.drawable.ic_stop)
                micHintView.text = "در حال گوش دادن… (برای توقف لمس کنید)"
            }
            VoiceOrbView.Mode.THINKING -> {
                statusBadge.setTextColor(Color.parseColor("#C084FC"))
                statusBadge.background = roundedBg("#2E1065", 20, "#7E22CE", 1)
                micHintView.text = "در حال پردازش دستور…"
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.SPEAKING -> {
                statusBadge.setTextColor(Color.parseColor("#34D399"))
                statusBadge.background = roundedBg("#064E3B", 20, "#059669", 1)
                micHintView.text = "ولف در حال پاسخ‌گویی است…"
                liveTranscriptView.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ دیالوگ تنظیمات
    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val desc = TextView(this).apply {
            text = "کلید API خود را از Google AI Studio دریافت کنید. این کلید فقط در حافظه امن همین گوشی ذخیره می‌شود."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "AIzaSy..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 12, "#334155", 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(prefs.getString("gemini_key", ""))
        }

        container.addView(desc)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("تنظیمات کلید Gemini API")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putString("gemini_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "کلید جمینای با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ------------------------------------------------------------------ صدا بیرون (TTS)
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                appendMessage("موتور تبدیل متن به گفتار روی گوشی در دسترس نیست.", ChatMessage.Sender.SYSTEM)
                return@TextToSpeech
            }
            val r = tts?.setLanguage(Locale.forLanguageTag("fa-IR"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                appendMessage(
                    "⚠️ صدای فارسی روی این گوشی نصب نیست. در تنظیمات گوشی ← مدیریت کلی ← تبدیل متن به گفتار، زبان فارسی را نصب کنید.",
                    ChatMessage.Sender.SYSTEM
                )
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        speaking = false
                        if (listening) resumeListening() else setStatus("آماده", VoiceOrbView.Mode.IDLE)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        speaking = false
                        if (listening) resumeListening() else setStatus("آماده", VoiceOrbView.Mode.IDLE)
                    }
                }
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || !ttsReady) {
            speaking = false
            if (listening) resumeListening() else setStatus("آماده", VoiceOrbView.Mode.IDLE)
            return
        }
        speaking = true
        setStatus("در حال صحبت", VoiceOrbView.Mode.SPEAKING)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolf")
    }

    // ------------------------------------------------------------------ گوش دادن (SpeechRecognizer)
    private fun toggleListening() {
        if (listening) {
            stopListening("آماده")
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            askPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "تشخیص گفتار روی این گوشی نصب نیست (برنامه Google لازم است)",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        listening = true
        setStatus("گوش می‌دهم", VoiceOrbView.Mode.LISTENING)
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        startRecognizer()
    }

    private fun stopListening(msg: String) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        tts?.stop()
        speaking = false
        voiceOrb.rmsLevel = 0f
        setStatus(msg, VoiceOrbView.Mode.IDLE)
    }

    private fun startRecognizer() {
        if (!listening || speaking) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(i)
    }

    private fun resumeListening(delayMs: Long = 300) {
        if (!listening || speaking) return
        handler.postDelayed({ startRecognizer() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setStatus("گوش می‌دهم", VoiceOrbView.Mode.LISTENING)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // تبدیل دسی‌بل صدا به عدد بین 0 تا 1 برای انیمیشن دایره صوتی
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            voiceOrb.rmsLevel = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            voiceOrb.rmsLevel = 0f
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) {
                    liveTranscriptView.visibility = View.VISIBLE
                    liveTranscriptView.text = "« $it … »"
                }
            }
        }

        override fun onResults(results: Bundle?) {
            liveTranscriptView.visibility = View.GONE
            voiceOrb.rmsLevel = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                resumeListening()
                return
            }
            handleSpeech(text)
        }

        override fun onError(error: Int) {
            voiceOrb.rmsLevel = 0f
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopListening("اجازه میکروفون داده نشده")
                    askPermissions()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> resumeListening(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    resumeListening(800)
                }
                else -> resumeListening(1000)
            }
        }
    }

    // ------------------------------------------------------------------ پردازش دستور
    private fun handleSpeech(text: String) {
        val (body, heard) = processor.stripWake(processor.norm(text))
        val now = SystemClock.elapsedRealtime()
        val needWake = prefs.getBoolean("need_wake", false)
        if (needWake && !heard && now > activeUntil) {
            resumeListening()
            return
        }
        if (heard) activeUntil = now + activeWindowMs

        appendMessage(text, ChatMessage.Sender.USER)
        if (body.isBlank()) {
            respond("بله قربان؟ در خدمتم.", speakOut = true)
            return
        }
        setStatus("در حال فکر کردن", VoiceOrbView.Mode.THINKING)
        processor.process(body) { reply -> respond(reply, speakOut = true) }
    }

    private fun submitUserCommand(text: String, speakOut: Boolean) {
        val (body, _) = processor.stripWake(processor.norm(text))
        appendMessage(text, ChatMessage.Sender.USER)
        val cleanBody = body.ifBlank { processor.norm(text) }
        setStatus("در حال فکر کردن", VoiceOrbView.Mode.THINKING)
        processor.process(cleanBody) { reply -> respond(reply, speakOut = speakOut) }
    }

    private fun respond(reply: String, speakOut: Boolean) {
        if (reply.isNotBlank()) {
            appendMessage(reply, ChatMessage.Sender.WOLF)
        }
        if (speakOut) {
            speak(reply)
        } else {
            setStatus("آماده", VoiceOrbView.Mode.IDLE)
        }
    }
}
