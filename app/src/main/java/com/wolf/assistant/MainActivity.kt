package com.wolf.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var memoryStore: MemoryStore
    private lateinit var geminiClient: GeminiClient
    private lateinit var processor: CommandProcessor

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    private var speaking = false
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())

    private var pendingImage: Bitmap? = null

    private lateinit var statusBadge: TextView
    private lateinit var personaChip: TextView
    private lateinit var liveTranscriptView: TextView
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var micIconView: ImageView
    private lateinit var micHintView: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var commandInput: EditText
    private lateinit var wakeSwitch: Switch
    private lateinit var imagePreviewBar: LinearLayout
    private lateinit var imagePreviewThumb: ImageView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bmp = result.data?.extras?.get("data") as? Bitmap
            if (bmp != null) attachImage(bmp)
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) attachImage(bmp)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "خطا در خواندن تصویر", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("wolf", Context.MODE_PRIVATE)
        memoryStore = MemoryStore(this)
        geminiClient = GeminiClient(prefs, memoryStore)
        processor = CommandProcessor(this, PhoneActions(this), geminiClient, memoryStore)
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
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CAMERA
            ),
            1
        )
    }

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

        // ==================== 1. هدر بالا ====================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
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
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        statusBadge = TextView(this).apply {
            text = "آماده"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
            setPadding(dp(9), dp(2), dp(9), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        titleRow.addView(titleText)
        titleRow.addView(statusBadge)

        personaChip = TextView(this).apply {
            text = "حالت: ${geminiClient.getPersona().title} ▾"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(3), 0, 0)
            setOnClickListener { showPersonaDialog() }
        }

        titleCol.addView(titleRow)
        titleCol.addView(personaChip)

        val clearBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_trash)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "پاک کردن گفتگو"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                chatAdapter.clearAll()
                addWelcomeMessage()
                Toast.makeText(this@MainActivity, "گفتگوی فعلی پاک شد", Toast.LENGTH_SHORT).show()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "تنظیمات و حافظه"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
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

        // ==================== 2. بخش انیمیشن صوتی ====================
        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(6))
            setBackgroundColor(Color.parseColor("#080D1A"))
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
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
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        }

        orbContainer.addView(voiceOrb)
        orbContainer.addView(micIconView)

        micHintView = TextView(this).apply {
            text = "برای صحبت، دایره را لمس کنید"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
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

        // ==================== 3. لیست پیام‌ها ====================
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

        // ==================== 4. دستورهای سریع (Quick Chips) ====================
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickCommands = listOf(
            "🎨 تولید عکس" to "عکس بساز از یک گرگ آینده‌نگر با چشمان نئونی آبی در شهر سایبرپانک",
            "📈 تحلیل بیت‌کوین" to "تحلیل تکنیکال و روند لحظه‌ای بیت کوین را بگو",
            "🧮 مدیریت ریسک ترید" to "حجم معامله با سرمایه 1000 ریسک 2 درصد و استاپ 5 درصد",
            "📄 ساخت فایل PDF" to "یک فایل pdf بساز درباره اصول مدیریت سرمایه در ترید",
            "🐍 ساخت فایل پایتون" to "یک فایل پایتون بساز برای ربات محاسبه RSI ارز دیجیتال",
            "📊 تحلیل چارت از گالری" to "__GALLERY__",
            "💵 قیمت دلار و طلا" to "قیمت لحظه‌ای دلار و طلای ۱۸ عیار امروز چنده؟",
            "🔋 وضعیت باتری" to "شارژ باتری چقدره؟"
        )

        for ((label, cmd) in quickCommands) {
            val chip = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedBg("#111827", 20, "#1E293B", 1)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    when (cmd) {
                        "__CAMERA__" -> openCamera()
                        "__GALLERY__" -> openGallery()
                        else -> submitUserCommand(cmd, speakOut = true)
                    }
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)

        // ==================== 5. پنل پایین: پیش‌نمایش عکس + ورودی + بیدارباش ====================
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(dp(10), dp(7), dp(10), dp(10))
        }

        imagePreviewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#111827", 12, "#38BDF8", 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        imagePreviewThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(10)
            }
        }

        val imagePreviewLabel = TextView(this).apply {
            text = "تصویر / چارت آماده تحلیل است (بپرسید یا ارسال بزنید)"
            textSize = 12f
            setTextColor(Color.parseColor("#BAE6FD"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeImgBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F87171"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { clearAttachedImage() }
        }

        imagePreviewBar.addView(imagePreviewThumb)
        imagePreviewBar.addView(imagePreviewLabel)
        imagePreviewBar.addView(removeImgBtn)

        val bottomDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#111827", 24, "#1E293B", 1)
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }

        val cameraBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_camera)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "دوربین"
            setOnClickListener { openCamera() }
        }

        val galleryBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_gallery)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "گالری و چارت"
            setOnClickListener { openGallery() }
        }

        commandInput = EditText(this).apply {
            hint = "تولید عکس، ساخت فایل، کدنویسی یا تحلیل ترید…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
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
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "ارسال"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { sendTypedText() }
        }

        inputRow.addView(cameraBtn)
        inputRow.addView(galleryBtn)
        inputRow.addView(commandInput)
        inputRow.addView(sendBtn)

        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), 0)
        }

        val wakeLabel = TextView(this).apply {
            text = "حالت بیدارباش: فقط با گفتن «ولف» یا «جارویس» عمل کند"
            textSize = 11.5f
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

        bottomPanel.addView(imagePreviewBar)
        bottomPanel.addView(inputRow)
        bottomPanel.addView(wakeRow)

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

    private fun openCamera() {
        if (!granted(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "دوربین در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            galleryLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "گالری در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachImage(bmp: Bitmap) {
        pendingImage = bmp
        imagePreviewThumb.setImageBitmap(bmp)
        imagePreviewBar.visibility = View.VISIBLE
        commandInput.hint = "مثلاً: این چارت را تحلیل کن / کد را دیباگ کن…"
    }

    private fun clearAttachedImage() {
        pendingImage = null
        imagePreviewThumb.setImageDrawable(null)
        imagePreviewBar.visibility = View.GONE
        commandInput.hint = "تولید عکس، ساخت فایل، کدنویسی یا تحلیل ترید…"
    }

    private fun addWelcomeMessage() {
        val msg = "سلام قربان! ولف نسخه ۴ با امکانات کامل آماده است:
" +
            "• 🎨 تولید عکس واقعی (بگویید: «عکس بساز از...»)
" +
            "• 📂 ساخت فایل واقعی PDF، پایتون، اکسل CSV و HTML
" +
            "• 📈 تحلیل ترید زنده (قیمت لحظه‌ای، RSI، تحلیل چارت از گالری و مدیریت ریسک)
" +
            "• 💻 برنامه‌نویسی حرفه‌ای و دیباگ کد"
        appendMessage(msg, ChatMessage.Sender.WOLF)
    }

    private fun appendMessage(
        text: String,
        sender: ChatMessage.Sender,
        image: Bitmap? = null,
        generatedFile: FileGenerator.GeneratedFile? = null
    ) {
        chatAdapter.addMessage(ChatMessage(text, sender, timeFmt.format(Date()), image, generatedFile))
        chatRecycler.post {
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun sendTypedText() {
        val txt = commandInput.text.toString().trim()
        val img = pendingImage
        if (txt.isEmpty() && img == null) return
        commandInput.setText("")
        clearAttachedImage()
        val prompt = txt.ifEmpty { "این تصویر را بررسی و تحلیل کن." }
        submitUserCommand(prompt, speakOut = true, image = img)
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
                micHintView.text = "در حال پردازش / ساخت خروجی…"
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

    private fun showPersonaDialog() {
        val personas = GeminiClient.Persona.values()
        val titles = personas.map { it.title }.toTypedArray()
        val currentIdx = personas.indexOf(geminiClient.getPersona()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("انتخاب تخصص هوشمند ولف")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selected = personas[which]
                geminiClient.setPersona(selected)
                personaChip.text = "حالت: ${selected.title} ▾"
                Toast.makeText(this, "حالت به «${selected.title}» تغییر کرد", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val desc = TextView(this).apply {
            text = "کلید API خود را از Google AI Studio دریافت کنید:"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(8))
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

        val memTitle = TextView(this).apply {
            val count = memoryStore.getAll().size
            text = "حافظه بلندمدت دائمی ($count مورد ثبت شده):"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(16), 0, dp(6))
        }

        val memList = TextView(this).apply {
            val facts = memoryStore.getAll()
            text = if (facts.isEmpty()) "هنوز موردی ثبت نشده است." else facts.joinToString("
") { "• $it" }
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(10))
        }

        val clearMemBtn = Button(this).apply {
            text = "پاک کردن کل حافظه بلندمدت"
            setTextColor(Color.parseColor("#FCA5A5"))
            background = roundedBg("#3F1D2E", 10, "#991B1B", 1)
            setOnClickListener {
                memoryStore.clearAll()
                memList.text = "حافظه بلندمدت پاک شد."
                Toast.makeText(this@MainActivity, "حافظه بلندمدت پاک شد", Toast.LENGTH_SHORT).show()
            }
        }

        container.addView(desc)
        container.addView(input)
        container.addView(memTitle)
        container.addView(memList)
        container.addView(clearMemBtn)

        AlertDialog.Builder(this)
            .setTitle("تنظیمات و حافظه ولف")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putString("gemini_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

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

    private fun handleSpeech(text: String) {
        val (body, heard) = processor.stripWake(processor.norm(text))
        val now = SystemClock.elapsedRealtime()
        val needWake = prefs.getBoolean("need_wake", false)
        if (needWake && !heard && now > activeUntil) {
            resumeListening()
            return
        }
        if (heard) activeUntil = now + activeWindowMs

        val img = pendingImage
        if (img != null) clearAttachedImage()

        appendMessage(text, ChatMessage.Sender.USER, img)
        if (body.isBlank() && img == null) {
            respond(CommandProcessor.ProcessResponse("بله قربان؟ در خدمتم."), speakOut = true)
            return
        }
        setStatus("در حال پردازش", VoiceOrbView.Mode.THINKING)
        processor.process(body.ifBlank { text }, image = img) { res -> respond(res, speakOut = true) }
    }

    private fun submitUserCommand(text: String, speakOut: Boolean, image: Bitmap? = null) {
        val (body, _) = processor.stripWake(processor.norm(text))
        appendMessage(text, ChatMessage.Sender.USER, image)
        val cleanBody = if (image != null) text else body.ifBlank { text }
        setStatus("در حال پردازش", VoiceOrbView.Mode.THINKING)
        processor.process(cleanBody, image = image) { res -> respond(res, speakOut = speakOut) }
    }

    private fun respond(res: CommandProcessor.ProcessResponse, speakOut: Boolean) {
        if (res.text.isNotBlank() || res.generatedImage != null || res.generatedFile != null) {
            appendMessage(
                text = res.text,
                sender = ChatMessage.Sender.WOLF,
                image = res.generatedImage,
                generatedFile = res.generatedFile
            )
        }
        val toSpeak = res.speakText ?: res.text
        if (speakOut) {
            speak(toSpeak)
        } else {
            setStatus("آماده", VoiceOrbView.Mode.IDLE)
        }
    }
}
