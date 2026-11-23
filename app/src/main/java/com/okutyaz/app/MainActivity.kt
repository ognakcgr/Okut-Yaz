package com.okutyaz.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class WebItem(val id: Int, val text: String)

class MainActivity : ComponentActivity() {
    companion object {
        const val WIFI_PORT = 8765
        const val WEB_PORT = 8766
    }

    private lateinit var cropView: CropImageView
    private lateinit var result: EditText
    private lateinit var status: TextView
    private lateinit var modeInfo: TextView
    private lateinit var ocrBtn: Button
    private lateinit var hintView: TextView
    private lateinit var mainRoot: View
    private var imageUri: Uri? = null
    private var photoFile: File? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var hidRequested = false
    private var enableAsked = false
    private var queueSending = false
    private var queueOffered = false
    private lateinit var queueBtn: Button
    private var webServer: java.net.ServerSocket? = null
    private val webItems = ArrayList<WebItem>()
    private var webSeq = 0
    private val webLock = Any()
    private var hidDescriptor: ByteArray? = null
    @Volatile private var sendFailures = 0
    private var screen = 0 // 0 ana ekran, 1 geçmiş, 2 ayarlar, 3 bekleyenler
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val prefs by lazy { getSharedPreferences("fk", Context.MODE_PRIVATE) }
    private lateinit var camera: ActivityResultLauncher<Uri>
    private lateinit var gallery: ActivityResultLauncher<String>
    private lateinit var enableBt: ActivityResultLauncher<Intent>

    private var dark = false
    private var cBackground = 0
    private var cCard = 0
    private var cPrimary = 0
    private var cSuccess = 0
    private var cDanger = 0
    private var cText = 0
    private var cMuted = 0
    private var cBorder = 0
    private var cChip = 0
    private var cStatus = 0
    private var cStrong = 0

    private val afterLabels = listOf("Yok", "↓ Alt hücre", "→ Sağ hücre")
    private val transportLabels = listOf("Bluetooth", "Wi-Fi", "Tarayıcı")

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showMain()
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (st == BluetoothAdapter.STATE_ON && hasBtPermission()) setupHid()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initPalette()
        onBackPressedDispatcher.addCallback(this, backCallback)
        camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = photoFile
            val uri = imageUri
            if (ok && uri != null) {
                loadImage(uri, file)
            } else {
                file?.delete()
            }
        }
        gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadImage(uri, null)
        }
        enableBt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != Activity.RESULT_OK) {
                status.text = "Bluetooth kapalı. Bluetooth klavye için Bluetooth'u açmalısın."
            }
        }
        mainRoot = buildMainView()
        showMain()
        if (prefs.getInt("transport", 0) == 2) startWebServer()
        try {
            ContextCompat.registerReceiver(this, btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
        }
        if (hasBtPermission()) setupHid() else ensureBluetoothPermission()
    }

    // ---------------------------------------------------------------- tema / yardımcılar

    private fun initPalette() {
        val pref = prefs.getInt("theme", 0)
        dark = when (pref) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (dark) {
            cBackground = 0xFF0B1120.toInt()
            cCard = 0xFF172033.toInt()
            cText = 0xFFE5E7EB.toInt()
            cMuted = 0xFF94A3B8.toInt()
            cBorder = 0xFF2A3548.toInt()
            cChip = 0xFF243047.toInt()
            cStatus = 0xFF1E293B.toInt()
            cPrimary = 0xFF3B82F6.toInt()
            cSuccess = 0xFF10B981.toInt()
            cDanger = 0xFFF87171.toInt()
            cStrong = 0xFF334155.toInt()
        } else {
            cBackground = 0xFFF1F5F9.toInt()
            cCard = 0xFFFFFFFF.toInt()
            cText = 0xFF0F172A.toInt()
            cMuted = 0xFF64748B.toInt()
            cBorder = 0xFFE2E8F0.toInt()
            cChip = 0xFFE9EEF5.toInt()
            cStatus = 0xFFE0E7FF.toInt()
            cPrimary = 0xFF2563EB.toInt()
            cSuccess = 0xFF059669.toInt()
            cDanger = 0xFFDC2626.toInt()
            cStrong = 0xFF0F172A.toInt()
        }
    }

    private fun applyWindowColors() {
        window.setBackgroundDrawable(ColorDrawable(cBackground))
        window.statusBarColor = cBackground
        window.navigationBarColor = cBackground
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = !dark
        ctl.isAppearanceLightNavigationBars = !dark
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    private fun styledButton(label: String, fill: Int, textColor: Int, stroke: Int = 0, heightDp: Int = 50, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = if (heightDp < 46) 13f else 15f
        b.setTextColor(textColor)
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        val base = shape(fill, 14, stroke, if (stroke != 0) 1 else 0)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), base, null)
        b.minHeight = 0
        b.minimumHeight = dp(heightDp)
        b.setPadding(dp(12), 0, dp(12), 0)
        b.setOnClickListener { action() }
        return b
    }

    private fun iconButton(symbol: String, action: () -> Unit): TextView {
        val t = TextView(this)
        t.text = symbol
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        t.setTextColor(cText)
        t.background = shape(cCard, 22, cBorder, 1)
        t.setOnClickListener { action() }
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = shape(cCard, 18, cBorder, 1)
        return c
    }

    private fun chipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<TextView>()
        fun paint(sel: Int) {
            for (i in chips.indices) {
                val on = i == sel
                chips[i].background = shape(if (on) cPrimary else cChip, 12)
                chips[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else cText)
            }
        }
        for ((i, label) in options.withIndex()) {
            val t = TextView(this)
            t.text = label
            t.gravity = Gravity.CENTER
            t.textSize = 13f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(dp(6), dp(11), dp(6), dp(11))
            t.setOnClickListener {
                paint(i)
                onSelect(i)
            }
            chips.add(t)
            val p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(6)
            row.addView(t, p)
        }
        paint(selected)
        return row
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 11f
        t.letterSpacing = 0.08f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(cMuted)
        t.setPadding(0, 0, 0, dp(8))
        return t
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 12f
        t.setTextColor(cMuted)
        t.setPadding(0, dp(8), 0, 0)
        return t
    }

    private fun field(hintText: String, value: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(value)
        e.inputType = type
        e.textSize = 16f
        e.setTextColor(cText)
        e.setHintTextColor(cMuted)
        e.background = shape(cBackground, 12, cBorder, 1)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        return e
    }

    private fun alertBuilder(): AlertDialog.Builder {
        val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(this, theme)
    }

    private fun say(msg: String) {
        status.text = msg
        if (screen != 0) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- ekranlar

    private fun showMain() {
        screen = 0
        backCallback.isEnabled = false
        setContentView(mainRoot)
        applyWindowColors()
        updateModeInfo()
        updateQueueButton()
    }

    private fun showHistory() {
        screen = 1
        backCallback.isEnabled = true
        setContentView(buildHistoryView())
        applyWindowColors()
    }

    private fun showSettings() {
        screen = 2
        backCallback.isEnabled = true
        setContentView(buildSettingsView())
        applyWindowColors()
    }

    private fun updateModeInfo() {
        val t = prefs.getInt("transport", 0)
        val a = prefs.getInt("after", 1)
        modeInfo.text = "Gönderim: ${transportLabels[t]}   ·   Sonra: ${afterLabels[a]}"
    }

    private fun buildMainView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBackground)
        root.setPadding(dp(16), dp(14), dp(16), dp(14))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "Okut-Yaz"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(cText)
        titles.addView(title)
        val subtitle = TextView(this)
        subtitle.text = "Fotoğraftan metni oku, PC'ye klavye gibi yaz"
        subtitle.textSize = 13f
        subtitle.setTextColor(cMuted)
        titles.addView(subtitle)
        header.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val histParams = lp(dp(44), dp(44))
        histParams.leftMargin = dp(8)
        header.addView(iconButton("🕘") { showHistory() }, histParams)
        val setParams = lp(dp(44), dp(44))
        setParams.leftMargin = dp(8)
        header.addView(iconButton("⚙") { showSettings() }, setParams)
        val headerParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        headerParams.bottomMargin = dp(10)
        root.addView(header, headerParams)

        status = TextView(this)
        status.text = "Bluetooth: hazırlanıyor…"
        status.textSize = 13f
        status.setTextColor(cText)
        status.background = shape(cStatus, 12)
        status.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val photoCard = FrameLayout(this)
        photoCard.background = shape(0xFF0F172A.toInt(), 18)
        photoCard.clipToOutline = true
        cropView = CropImageView(this)
        cropView.onSelectionChanged = { text -> result.setText(text) }
        photoCard.addView(cropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val photoParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        photoParams.topMargin = dp(12)
        photoCard.minimumHeight = dp(140)
        root.addView(photoCard, photoParams)

        val modeRow = chipRow(listOf("Satır seç", "Kelime seç", "Alan çiz"), 0) { v -> setPickMode(v) }
        val modeParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        modeParams.topMargin = dp(8)
        root.addView(modeRow, modeParams)

        hintView = TextView(this)
        hintView.text = hintFor(0)
        hintView.textSize = 12f
        hintView.setTextColor(cMuted)
        hintView.setPadding(dp(2), dp(8), dp(2), dp(8))
        root.addView(hintView)

        val photoRow = LinearLayout(this)
        photoRow.orientation = LinearLayout.HORIZONTAL
        val camBtn = styledButton("📷  Kamera", cPrimary, 0xFFFFFFFF.toInt()) { takePhoto() }
        val galBtn = styledButton("🖼  Galeri", cCard, cText, cBorder) { gallery.launch("image/*") }
        val camParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        camParams.rightMargin = dp(5)
        val galParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        galParams.leftMargin = dp(5)
        photoRow.addView(camBtn, camParams)
        photoRow.addView(galBtn, galParams)
        root.addView(photoRow)

        val ocrParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ocrParams.topMargin = dp(10)
        ocrBtn = styledButton("Seçili alanı oku (OCR)", cStrong, 0xFFFFFFFF.toInt()) { runOcr() }
        ocrBtn.visibility = View.GONE
        root.addView(ocrBtn, ocrParams)

        val resultCard = card()
        resultCard.setPadding(dp(14), dp(10), dp(14), dp(10))
        val resultLabel = label("TANINAN METİN")
        resultLabel.setPadding(0, 0, 0, 0)
        resultCard.addView(resultLabel)
        result = EditText(this)
        result.hint = "Okunan metin burada görünür, düzenleyebilirsin"
        result.setHintTextColor(cMuted)
        result.minLines = 2
        result.maxLines = 4
        result.textSize = 16f
        result.gravity = Gravity.TOP
        result.setTextColor(cText)
        result.background = null
        result.setPadding(0, dp(4), 0, 0)
        resultCard.addView(result, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val resultParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        resultParams.topMargin = dp(10)
        root.addView(resultCard, resultParams)

        val sendRow = LinearLayout(this)
        sendRow.orientation = LinearLayout.HORIZONTAL
        val pcBtn = styledButton("PC'ye bağlan", cCard, cPrimary, cPrimary) { connectPc() }
        val sendBtn = styledButton("Metni gönder", cSuccess, 0xFFFFFFFF.toInt()) { sendText() }
        val pcParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pcParams.rightMargin = dp(5)
        pcParams.topMargin = dp(10)
        val sendParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sendParams.leftMargin = dp(5)
        sendParams.topMargin = dp(10)
        sendRow.addView(pcBtn, pcParams)
        sendRow.addView(sendBtn, sendParams)
        root.addView(sendRow)

        val queueRow = LinearLayout(this)
        queueRow.orientation = LinearLayout.HORIZONTAL
        val laterBtn = styledButton("➕  Sonra gönder", cCard, cText, cBorder, 44) { saveForLater() }
        queueBtn = styledButton("🗂  Bekleyenler (0)", cCard, cText, cBorder, 44) { showQueue() }
        val laterParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        laterParams.rightMargin = dp(5)
        laterParams.topMargin = dp(8)
        val queueParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        queueParams.leftMargin = dp(5)
        queueParams.topMargin = dp(8)
        queueRow.addView(laterBtn, laterParams)
        queueRow.addView(queueBtn, queueParams)
        root.addView(queueRow)

        modeInfo = TextView(this)
        modeInfo.textSize = 12f
        modeInfo.gravity = Gravity.CENTER
        modeInfo.setTextColor(cMuted)
        modeInfo.setPadding(0, dp(10), 0, 0)
        root.addView(modeInfo)
        return root
    }

    private fun subHeader(titleText: String, trailing: View?): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val backParams = lp(dp(44), dp(44))
        row.addView(iconButton("←") { showMain() }, backParams)
        val t = TextView(this)
        t.text = titleText
        t.textSize = 20f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(cText)
        t.setPadding(dp(12), 0, 0, 0)
        row.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing)
        return row
    }

    private fun addSettingCard(parent: LinearLayout, titleText: String, vararg views: View) {
        val c = card()
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        c.addView(label(titleText))
        for (v in views) c.addView(v)
        val p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(12)
        parent.addView(c, p)
    }

    private fun buildSettingsView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBackground)
        root.setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addView(subHeader("Ayarlar", null))

        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addSettingCard(col, "TEMA", chipRow(listOf("Sistem", "Açık", "Koyu"), prefs.getInt("theme", 0)) { v ->
            prefs.edit().putInt("theme", v).apply()
            recreate()
        })

        addSettingCard(col, "BAĞLANTI YÖNTEMİ", chipRow(transportLabels, prefs.getInt("transport", 0)) { v ->
            prefs.edit().putInt("transport", v).apply()
            if (v == 2) startWebServer() else stopWebServer()
        }, note("Bluetooth: telefon, PC'ye klavye gibi görünür. Wi-Fi: PC'de küçük bir alıcı program çalışır ve metni otomatik yazar. Tarayıcı: PC'ye hiçbir şey kurmadan, tarayıcıdaki sayfadan kopyalayıp yapıştırırsın."))

        addSettingCard(col, "GÖNDERDİKTEN SONRA", chipRow(afterLabels, prefs.getInt("after", 1)) { v ->
            prefs.edit().putInt("after", v).apply()
        }, note("Excel'de '↓ Alt hücre' seçiliyse metin yazıldıktan sonra Enter basılır ve bir sonraki gönderim alttaki hücreye yazılır. Bu modda satır sonları boşluğa çevrilir, böylece metin tek hücrede kalır."))

        val webInfo = TextView(this)
        webInfo.text = "Adres:  http://" + localIp() + ":" + WEB_PORT + "\nKod:  " + webCode()
        webInfo.textSize = 16f
        webInfo.typeface = Typeface.DEFAULT_BOLD
        webInfo.setTextColor(cText)
        webInfo.background = shape(cBackground, 12, cBorder, 1)
        webInfo.setPadding(dp(12), dp(10), dp(12), dp(10))
        addSettingCard(col, "TARAYICI İLE BAĞLANTI (KURULUM GEREKTİRMEZ)", webInfo, note("Bağlantı yöntemi 'Tarayıcı' iken çalışır. PC'de herhangi bir tarayıcıyı aç, bu adresi yaz ve kodu gir. Gönderilen metin sayfada görünür; Kopyala'ya basıp Ctrl+V ile yapıştır. Metin otomatik yazılmaz ve alt hücreye geçiş yapılmaz. Telefon ve PC aynı ağda olmalı."))

        val layoutIndex = when (prefs.getInt("kblayout", 1)) {
            1 -> 0
            2 -> 1
            else -> 2
        }
        addSettingCard(col, "PC KLAVYE DÜZENİ (BLUETOOTH)", chipRow(listOf("Türkçe Q", "Türkçe F", "US"), layoutIndex) { v ->
            val value = when (v) {
                0 -> 1
                1 -> 2
                else -> 0
            }
            prefs.edit().putInt("kblayout", value).apply()
        }, note("Bilgisayarın Windows klavye düzeni neyse onu seç (varsayılan Türkçe Q). Seçili düzende ç ğ ı ö ş ü ve @ # $ { } [ ] gibi karakterler doğru yazılır. Wi-Fi yönteminde bu ayar gerekmez."))

        val ipField = field("PC'nin IP adresi (örn. 192.168.1.20)", prefs.getString("wifi_ip", "") ?: "", InputType.TYPE_CLASS_PHONE)
        val codeField = field("PC'de görünen 4 haneli kod", prefs.getString("wifi_code", "") ?: "", InputType.TYPE_CLASS_NUMBER)
        val codeParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        codeParams.topMargin = dp(8)
        val saveBtn = styledButton("Kaydet ve bağlantıyı dene", cPrimary, 0xFFFFFFFF.toInt()) {
            prefs.edit().putString("wifi_ip", ipField.text.toString().filter { it.isDigit() || it == '.' }).putString("wifi_code", codeField.text.toString().trim()).apply()
            testWifi()
        }
        val saveParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        saveParams.topMargin = dp(10)
        val wifiCard = card()
        wifiCard.setPadding(dp(14), dp(14), dp(14), dp(14))
        wifiCard.addView(label("WI-FI İLE BAĞLANTI"))
        wifiCard.addView(ipField, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wifiCard.addView(codeField, codeParams)
        wifiCard.addView(saveBtn, saveParams)
        wifiCard.addView(note("PC'de 'PC-Alici' klasöründeki OkutYaz-PC-Baslat.bat dosyasını çalıştır. Ekranda IP adresi ve kod görünür. Telefon ve PC aynı Wi-Fi ağında olmalı. Windows güvenlik duvarı sorarsa 'Özel ağlar'a izin ver' de."))
        val wp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        wp.topMargin = dp(12)
        col.addView(wifiCard, wp)

        root.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------------------------------------------------------------- geçmiş

    private fun loadHistory(): JSONArray {
        return try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun addHistory(text: String, method: String) {
        val old = loadHistory()
        val o = JSONObject()
        o.put("t", text)
        o.put("ts", System.currentTimeMillis())
        o.put("m", method)
        val out = JSONArray()
        out.put(o)
        var i = 0
        while (i < old.length() && i < 199) {
            out.put(old.get(i))
            i++
        }
        prefs.edit().putString("history", out.toString()).apply()
    }

    private fun buildHistoryView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBackground)
        root.setPadding(dp(16), dp(14), dp(16), dp(14))

        val clear = TextView(this)
        clear.text = "Temizle"
        clear.textSize = 14f
        clear.typeface = Typeface.DEFAULT_BOLD
        clear.setTextColor(cDanger)
        clear.setPadding(dp(10), dp(10), dp(4), dp(10))
        clear.setOnClickListener {
            alertBuilder().setTitle("Geçmiş temizlensin mi?").setMessage("Tüm gönderim kayıtları silinecek.")
                .setPositiveButton("Sil") { _, _ ->
                    prefs.edit().putString("history", "[]").apply()
                    showHistory()
                }
                .setNegativeButton("İptal", null).show()
        }
        root.addView(subHeader("Gönderim geçmişi", clear))

        val arr = loadHistory()
        if (arr.length() == 0) {
            val empty = TextView(this)
            empty.text = "Henüz gönderim yok.\nPC'ye gönderdiğin metinler burada listelenir."
            empty.textSize = 14f
            empty.gravity = Gravity.CENTER
            empty.setTextColor(cMuted)
            root.addView(empty, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return root
        }

        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val text = o.optString("t")
            val ts = o.optLong("ts")
            val method = o.optString("m")
            val c = card()
            c.setPadding(dp(14), dp(12), dp(14), dp(12))
            val body = TextView(this)
            body.text = text
            body.textSize = 15f
            body.maxLines = 4
            body.ellipsize = TextUtils.TruncateAt.END
            body.setTextColor(cText)
            c.addView(body)
            val meta = TextView(this)
            meta.text = fmt.format(Date(ts)) + "  ·  " + method
            meta.textSize = 12f
            meta.setTextColor(cMuted)
            meta.setPadding(0, dp(6), 0, dp(10))
            c.addView(meta)
            val actions = LinearLayout(this)
            actions.orientation = LinearLayout.HORIZONTAL
            val useBtn = styledButton("Metne al", cPrimary, 0xFFFFFFFF.toInt(), 0, 40) {
                result.setText(text)
                showMain()
                status.text = "Geçmişten metin alındı. İstersen düzenleyip tekrar gönder."
            }
            val copyBtn = styledButton("Kopyala", cCard, cText, cBorder, 40) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Okut-Yaz", text))
                Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
            val delBtn = styledButton("Sil", cCard, cDanger, cBorder, 40) {
                val cur = loadHistory()
                cur.remove(i)
                prefs.edit().putString("history", cur.toString()).apply()
                showHistory()
            }
            val p1 = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val p2 = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p2.leftMargin = dp(6)
            val p3 = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p3.leftMargin = dp(6)
            actions.addView(useBtn, p1)
            actions.addView(copyBtn, p2)
            actions.addView(delBtn, p3)
            c.addView(actions)
            val cp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cp.topMargin = dp(10)
            col.addView(c, cp)
        }
        root.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------------------------------------------------------------- fotoğraf / OCR

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 11)
            return
        }
        try {
            // Fotoğraf galeriye değil, uygulamanın geçici önbellek klasörüne kaydedilir.
            val dir = File(cacheDir, "photos")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val file = File.createTempFile("capture", ".jpg", dir)
            photoFile = file
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            imageUri = uri
            camera.launch(uri)
        } catch (e: Exception) {
            status.text = "Kamera başlatılamadı: ${e.localizedMessage}"
        }
    }

    private fun loadImage(uri: Uri, temp: File?) {
        status.text = "Fotoğraf yükleniyor…"
        Thread {
            var bmp: Bitmap? = null
            var error: String? = null
            try {
                bmp = decodeUri(uri)
            } catch (e: Throwable) {
                error = e.message
            }
            try {
                temp?.delete()
            } catch (e: Exception) {
            }
            val out = bmp
            val err = error
            runOnUiThread {
                if (out != null) {
                    cropView.setBitmap(out)
                    scanAll(out)
                } else {
                    status.text = "Fotoğraf açılamadı" + (if (err != null) ": $err" else ".")
                }
            }
        }.start()
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return raw
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun hintFor(m: Int): String {
        return if (m == 2) {
            "Çerçeveyi sürükleyerek taşı, kenar ve köşelerinden çekerek boyutlandır. Dışına dokunup sürüklersen yeni alan seçersin. Sonra 'Seçili alanı oku'ya bas."
        } else {
            "Göndermek istediğin metne dokun, aşağıya yazılır. Birden fazla yere dokunarak birleştirebilirsin; tekrar dokunursan seçim kalkar."
        }
    }

    private fun setPickMode(m: Int) {
        cropView.setViewMode(m)
        ocrBtn.visibility = if (m == 2) View.VISIBLE else View.GONE
        hintView.text = hintFor(m)
    }

    /** Fotoğraftaki tüm metinleri otomatik tarar; kullanıcı dokunarak seçer. */
    private fun scanAll(bmp: Bitmap) {
        status.text = "Metinler taranıyor…"
        recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { vision ->
            val lines = ArrayList<OcrBox>()
            val words = ArrayList<OcrBox>()
            for (block in vision.textBlocks) {
                for (line in block.lines) {
                    val lb = line.boundingBox
                    if (lb != null && line.text.isNotBlank()) lines.add(OcrBox(RectF(lb), line.text.trim()))
                    for (el in line.elements) {
                        val eb = el.boundingBox
                        if (eb != null && el.text.isNotBlank()) words.add(OcrBox(RectF(eb), el.text.trim()))
                    }
                }
            }
            cropView.setBoxes(lines, words)
            status.text = if (lines.isEmpty()) "Metin bulunamadı. 'Alan çiz' ile elle seçmeyi veya daha net bir fotoğraf dene." else "${lines.size} satır bulundu. Göndermek istediğin metne dokun."
        }.addOnFailureListener { status.text = "Metinler taranamadı: ${it.localizedMessage}" }
    }

    private fun runOcr() {
        val bmp = cropView.crop()
        if (bmp == null) {
            status.text = "Önce fotoğraf çek veya seç."
            return
        }
        status.text = "Metin tanınıyor…"
        recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { vision ->
            val text = vision.text.trim()
            result.setText(text)
            status.text = if (text.isBlank()) "Metin bulunamadı. Daha net fotoğraf veya daha büyük alan dene." else "OCR tamamlandı. Gerekirse metni düzenleyip PC'ye gönder."
        }.addOnFailureListener { status.text = "OCR hatası: ${it.localizedMessage}" }
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun hasBtPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBluetoothPermission() {
        if (!hasBtPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 22)
        }
    }

    private fun setupHid() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { status.text = "Bu telefonda Bluetooth bulunamadı."; return }
            if (!adapter.isEnabled) {
                status.text = "Bluetooth kapalı. Açmak için gelen izin penceresini onayla."
                if (!enableAsked) {
                    enableAsked = true
                    enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                return
            }
            if (hid != null || hidRequested) return
            hidRequested = true
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    hid = proxy as BluetoothHidDevice
                    val descriptor=intArrayOf(0x05,0x01,0x09,0x06,0xA1,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x01,0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,0x95,0x01,0x75,0x03,0x91,0x01,0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0).map { it.toByte() }.toByteArray()
                    hidDescriptor = descriptor
                    registerHidApp()
                    runOnUiThread { status.text = "Bluetooth klavye servisi açıldı. PC'yi eşleştirip bağlan." }
                }
                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    registered = false
                    host = null
                    hidRequested = false
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            status.text = "Bluetooth izni gerekli. Uygulama izinlerini açıp yeniden başlat."
        } catch (e: Exception) {
            status.text = "Bluetooth HID başlatılamadı: ${e.localizedMessage}"
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, plugged: Boolean) {
            registered = plugged
            runOnUiThread {
                status.text = if (plugged) "Bluetooth klavye hazır. PC'ye bağlanabilirsin." else "Bluetooth klavye kaydı kapandı. Uygulamaya dönünce yeniden açılır."
            }
            if (plugged) reconnectHost()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) host = device
            runOnUiThread {
                status.text = when (state) {
                    BluetoothProfile.STATE_CONNECTED -> "PC bağlı: ${device?.name ?: "Windows"}"
                    BluetoothProfile.STATE_CONNECTING -> "PC'ye bağlanıyor…"
                    else -> "PC bağlantısı yok. Eşleştirip bağlan."
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    if (prefs.getInt("transport", 0) == 0) offerQueue()
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    queueOffered = false
                }
            }
        }
    }

    /** Klavye kaydını (yeniden) yapar. Kamera/galeri açılınca Android kaydı düşürebildiği için gerekir. */
    private fun registerHidApp() {
        val service = hid ?: return
        val desc = hidDescriptor ?: return
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings("Okut-Yaz", "OCR metnini Bluetooth klavye olarak gönderir", "Okut-Yaz", BluetoothHidDevice.SUBCLASS1_KEYBOARD, desc)
            service.registerApp(sdp, null, null, executor, hidCallback)
        } catch (e: SecurityException) {
            status.text = "Bluetooth izni gerekli."
        } catch (e: Exception) {
            status.text = "Bluetooth klavye kaydı yapılamadı: ${e.localizedMessage}"
        }
    }

    /** Daha önce bağlı olunan bilgisayara bağlantı kopmuşsa yeniden bağlanır. */
    private fun reconnectHost() {
        val d = host ?: return
        val service = hid ?: return
        try {
            if (service.getConnectionState(d) != BluetoothProfile.STATE_CONNECTED) service.connect(d)
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (hid != null) {
            if (!registered) registerHidApp() else reconnectHost()
        }
    }

    private fun connectPc() {
        when (prefs.getInt("transport", 0)) {
            1 -> testWifi()
            2 -> {
                startWebServer()
                status.text = "PC'de tarayıcıyı aç, adres: http://" + localIp() + ":" + WEB_PORT + "  Kod: " + webCode()
            }
            else -> choosePc()
        }
    }

    private fun choosePc() {
        if (!hasBtPermission()) {
            ensureBluetoothPermission()
            return
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) {
                status.text = "Önce Windows Ayarlar > Bluetooth bölümünden bu telefonu eşleştir."
                return
            }
            val names = devices.map { "${it.name ?: "Bilinmeyen cihaz"} (${it.address})" }.toTypedArray()
            alertBuilder().setTitle("Windows bilgisayarını seç").setItems(names) { _, which ->
                val d = devices[which]
                host = d
                val ok = hid?.connect(d) ?: false
                status.text = if (ok) "Bağlantı isteği gönderildi: ${d.name}" else "Bağlantı başlatılamadı. HID desteği/izinleri kontrol et."
            }.setNegativeButton("İptal", null).show()
        } catch (e: Exception) {
            status.text = "Cihaz listesi alınamadı: ${e.localizedMessage}"
        }
    }

    // ---------------------------------------------------------------- gönderim

    private fun prepareText(raw: String, after: Int): String {
        return if (after != 0) raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim() else raw
    }

    private fun sendText() {
        val raw = result.text.toString()
        if (raw.isBlank()) {
            status.text = "Gönderilecek metin boş."
            return
        }
        val after = prefs.getInt("after", 1)
        val text = prepareText(raw, after)
        when (prefs.getInt("transport", 0)) {
            1 -> sendWifi(text, after)
            2 -> sendBrowser(text)
            else -> sendBluetooth(text, after)
        }
    }

    /** Bluetooth bağlantısı hazırsa bağlı bilgisayarı döndürür, değilse nedenini yazıp null döndürür. */
    private fun btReadyDevice(): BluetoothDevice? {
        val service = hid
        if (service == null) {
            status.text = "Bluetooth klavye servisi hazır değil. Bluetooth'un açık olduğundan emin ol ve uygulamayı yeniden aç."
            return null
        }
        var connected: BluetoothDevice? = null
        try {
            connected = service.connectedDevices.firstOrNull()
        } catch (e: Exception) {
        }
        val device = connected ?: host
        if (device == null) {
            status.text = "PC bağlı değil. Önce 'PC'ye bağlan' ile bilgisayarı seç."
            return null
        }
        var state = BluetoothProfile.STATE_DISCONNECTED
        try {
            state = service.getConnectionState(device)
        } catch (e: Exception) {
        }
        if (state != BluetoothProfile.STATE_CONNECTED) {
            status.text = "Bağlantı koptu (kamera veya galeri açılınca Android klavyeyi kapatabilir). Yeniden bağlanılıyor."
            registerHidApp()
            reconnectHost()
            return null
        }
        return device
    }

    private fun typeBluetooth(device: BluetoothDevice, text: String, after: Int): Boolean {
        sendFailures = 0
        for (ch in text) {
            if (ch == '\n' || ch == '\r') {
                tapKey(device, 0x28)
                continue
            }
            val mapped = mapKey(ch) ?: continue
            sendKey(device, mapped.second, mapped.first)
            Thread.sleep(12)
            sendKey(device, 0, 0)
            Thread.sleep(12)
        }
        if (after == 1) tapKey(device, 0x28)
        if (after == 2) tapKey(device, 0x2B)
        return sendFailures == 0
    }

    private fun sendBluetooth(text: String, after: Int) {
        val device = btReadyDevice()
        if (device == null) {
            offerSaveToQueue(text)
            return
        }
        Thread {
            val ok = typeBluetooth(device, text, after)
            runOnUiThread {
                if (!ok) {
                    status.text = "Gönderilemedi: PC tuş vuruşlarını kabul etmedi. Windows'ta telefonu kaldırıp uygulama açıkken yeniden eşleştir ya da Wi-Fi yöntemini dene."
                    offerSaveToQueue(text)
                } else {
                    status.text = if (prefs.getInt("kblayout", 1) != 0) "Gönderim tamamlandı: ${text.length} karakter." else "Gönderim tamamlandı: ${text.length} karakter. Türkçe özel karakterler US düzeninde farklı görünebilir."
                    addHistory(text, "Bluetooth")
                }
            }
        }.start()
    }

    private fun tapKey(device: BluetoothDevice, key: Int) {
        sendKey(device, key, 0)
        Thread.sleep(25)
        sendKey(device, 0, 0)
        Thread.sleep(15)
    }

    private fun sendKey(device: BluetoothDevice, key: Int, modifier: Int) {
        val report = byteArrayOf(modifier.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)
        try {
            val ok = hid?.sendReport(device, 0, report) ?: false
            if (!ok) sendFailures++
        } catch (e: Exception) {
            sendFailures++
        }
    }

    // ---------------------------------------------------------------- Wi-Fi

    private fun sendWifi(text: String, after: Int) {
        val ip = prefs.getString("wifi_ip", "") ?: ""
        val code = prefs.getString("wifi_code", "") ?: ""
        if (ip.isBlank() || code.isBlank()) {
            status.text = "Önce Ayarlar'dan PC'nin IP adresini ve kodunu gir."
            return
        }
        status.text = "Wi-Fi ile gönderiliyor…"
        Thread {
            val res = wifiRequest(ip, code, text, after)
            runOnUiThread {
                if (res == "OK") {
                    status.text = "Wi-Fi ile gönderildi: ${text.length} karakter."
                    addHistory(text, "Wi-Fi")
                } else {
                    status.text = "Wi-Fi gönderimi başarısız: ${wifiError(res)}"
                    offerSaveToQueue(text)
                }
            }
        }.start()
    }

    private fun testWifi() {
        val ip = prefs.getString("wifi_ip", "") ?: ""
        val code = prefs.getString("wifi_code", "") ?: ""
        if (ip.isBlank() || code.isBlank()) {
            status.text = "Önce Ayarlar'dan PC'nin IP adresini ve kodunu gir."
            if (screen == 0) showSettings()
            return
        }
        say("PC'ye bağlanılıyor…")
        Thread {
            val res = wifiRequest(ip, code, "", 0)
            runOnUiThread {
                if (res == "OK") {
                    say("Wi-Fi bağlantısı hazır: $ip")
                    queueOffered = false
                    offerQueue()
                } else {
                    say("Wi-Fi bağlantısı kurulamadı: ${wifiError(res)}")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Tarayıcı modu (kurulum gerektirmez)

    private fun webCode(): String {
        val saved = prefs.getString("web_code", null)
        if (saved != null) return saved
        val fresh = (1000 + java.util.Random().nextInt(9000)).toString()
        prefs.edit().putString("web_code", fresh).apply()
        return fresh
    }

    private fun localIp(): String {
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs != null && ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name
                if (!name.startsWith("wlan") && !name.startsWith("swlan") && !name.startsWith("ap") && !name.startsWith("eth")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && a.isSiteLocalAddress) {
                        val host = a.hostAddress
                        if (host != null) return host
                    }
                }
            }
        } catch (e: Exception) {
        }
        return "telefonun-ip-adresi"
    }

    private fun startWebServer() {
        if (webServer != null) return
        Thread {
            try {
                val server = java.net.ServerSocket()
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(WEB_PORT))
                webServer = server
                while (!server.isClosed) {
                    val client = server.accept()
                    Thread { handleWeb(client) }.start()
                }
            } catch (e: Exception) {
                webServer = null
            }
        }.start()
    }

    private fun stopWebServer() {
        try {
            webServer?.close()
        } catch (e: Exception) {
        }
        webServer = null
    }

    private fun handleWeb(client: java.net.Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 5000
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val target = if (parts.size >= 2) parts[1] else "/"
                val path = target.substringBefore("?")
                val query = target.substringAfter("?", "")
                val params = HashMap<String, String>()
                for (kv in query.split("&")) {
                    val i = kv.indexOf('=')
                    if (i > 0) params[kv.substring(0, i)] = java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                }
                val out = sock.getOutputStream()
                if (path == "/poll") {
                    if (params["code"] != webCode()) {
                        respond(out, 403, "text/plain", "ERR")
                    } else {
                        val since = params["since"]?.toIntOrNull() ?: -1
                        respond(out, 200, "application/json", buildPollJson(since))
                    }
                } else {
                    respond(out, 200, "text/html", webPage)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + bytes.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun buildPollJson(since: Int): String {
        val arr = JSONArray()
        var last = 0
        synchronized(webLock) {
            last = webSeq
            if (since >= 0) {
                for (it in webItems) {
                    if (it.id > since) {
                        val o = JSONObject()
                        o.put("id", it.id)
                        o.put("text", it.text)
                        arr.put(o)
                    }
                }
            }
        }
        val root = JSONObject()
        root.put("items", arr)
        root.put("last", last)
        return root.toString()
    }

    private fun pushBrowser(text: String) {
        startWebServer()
        synchronized(webLock) {
            webSeq++
            webItems.add(WebItem(webSeq, text))
            while (webItems.size > 50) webItems.removeAt(0)
        }
    }

    private fun sendBrowser(text: String) {
        pushBrowser(text)
        status.text = "Tarayıcıya gönderildi. PC'deki sayfada Kopyala'ya basıp Ctrl+V ile yapıştır."
        addHistory(text, "Tarayıcı")
    }

    // ---------------------------------------------------------------- Bekleyenler (sonra aktar)

    private fun loadQueue(): JSONArray {
        return try {
            JSONArray(prefs.getString("queue", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveQueue(arr: JSONArray) {
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    private fun addToQueue(text: String) {
        val q = loadQueue()
        val now = System.currentTimeMillis()
        val o = JSONObject()
        o.put("id", now)
        o.put("t", text)
        o.put("ts", now)
        q.put(o)
        saveQueue(q)
        updateQueueButton()
    }

    private fun removeQueueId(id: Long) {
        val q = loadQueue()
        val out = JSONArray()
        for (i in 0 until q.length()) {
            val o = q.getJSONObject(i)
            if (o.optLong("id") != id) out.put(o)
        }
        saveQueue(out)
        updateQueueButton()
    }

    private fun updateQueueButton() {
        queueBtn.text = "🗂  Bekleyenler (" + loadQueue().length() + ")"
    }

    private fun saveForLater() {
        val raw = result.text.toString().trim()
        if (raw.isBlank()) {
            status.text = "Kaydedilecek metin boş. Fotoğraftan bir metin seç veya kutuya yaz."
            return
        }
        addToQueue(raw)
        result.setText("")
        cropView.clearSelection()
        status.text = "Bekleyenlere eklendi (" + loadQueue().length() + "). Bağlantı kurulunca hepsini gönderebilirsin."
    }

    private fun offerSaveToQueue(text: String) {
        if (isFinishing || isDestroyed) return
        alertBuilder().setTitle("Şu an gönderilemiyor")
            .setMessage("Bu metni bekleyenler listesine ekleyip bağlantı kurulunca göndermek ister misin?")
            .setPositiveButton("Sıraya ekle") { _, _ ->
                addToQueue(text)
                status.text = "Bekleyenlere eklendi (" + loadQueue().length() + ")."
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun offerQueue() {
        if (queueSending || queueOffered || isFinishing || isDestroyed) return
        val n = loadQueue().length()
        if (n == 0) return
        queueOffered = true
        alertBuilder().setTitle("Bekleyen kayıtlar var")
            .setMessage("$n kayıt bekliyor. Şimdi PC'ye gönderilsin mi? Göndermeden önce yazılacak alanı (örn. Excel hücresini) seç.")
            .setPositiveButton("Gönder") { _, _ -> sendQueue() }
            .setNegativeButton("Sonra", null).show()
    }

    private fun confirmSendQueue() {
        val n = loadQueue().length()
        if (n == 0) {
            Toast.makeText(this, "Bekleyen kayıt yok", Toast.LENGTH_SHORT).show()
            return
        }
        alertBuilder().setTitle("Bekleyenler gönderilsin mi?")
            .setMessage("$n kayıt " + transportLabels[prefs.getInt("transport", 0)] + " ile sırayla gönderilecek. Yazılacak alanı (örn. Excel hücresini) seçtiğinden emin ol.")
            .setPositiveButton("Gönder") { _, _ -> sendQueue() }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun sendOne(transport: Int, device: BluetoothDevice?, text: String, after: Int): String? {
        return when (transport) {
            0 -> if (device == null) "PC bağlı değil" else if (typeBluetooth(device, text, after)) null else "PC tuş vuruşlarını kabul etmedi"
            1 -> {
                val res = wifiRequest(prefs.getString("wifi_ip", "") ?: "", prefs.getString("wifi_code", "") ?: "", text, after)
                if (res == "OK") null else wifiError(res)
            }
            else -> {
                pushBrowser(text)
                null
            }
        }
    }

    private fun sendQueue() {
        if (queueSending) return
        val q = loadQueue()
        if (q.length() == 0) return
        val transport = prefs.getInt("transport", 0)
        val after = prefs.getInt("after", 1)
        var device: BluetoothDevice? = null
        if (transport == 0) {
            device = btReadyDevice()
            if (device == null) {
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                return
            }
        }
        if (transport == 1 && (prefs.getString("wifi_ip", "").isNullOrBlank() || prefs.getString("wifi_code", "").isNullOrBlank())) {
            status.text = "Önce Ayarlar'dan PC'nin IP adresini ve kodunu gir."
            return
        }
        val ids = ArrayList<Long>()
        val texts = ArrayList<String>()
        for (i in 0 until q.length()) {
            val o = q.getJSONObject(i)
            ids.add(o.optLong("id"))
            texts.add(prepareText(o.optString("t"), after))
        }
        val dev = device
        queueSending = true
        status.text = "Bekleyenler gönderiliyor…"
        Thread {
            var done = 0
            var err: String? = null
            for (i in texts.indices) {
                val e = sendOne(transport, dev, texts[i], after)
                if (e != null) {
                    err = e
                    break
                }
                done++
                val id = ids[i]
                val sentText = texts[i]
                val count = i + 1
                runOnUiThread {
                    removeQueueId(id)
                    addHistory(sentText, transportLabels[transport])
                    status.text = "Bekleyenler gönderiliyor: $count/${texts.size}"
                }
                Thread.sleep(250)
            }
            val finalDone = done
            val finalErr = err
            runOnUiThread {
                queueSending = false
                status.text = if (finalErr == null) "Bekleyen $finalDone kayıt gönderildi." else "$finalDone/${texts.size} kayıt gönderildi. Durdu: $finalErr"
                updateQueueButton()
                if (screen == 3) showQueue()
            }
        }.start()
    }

    private fun showQueue() {
        screen = 3
        backCallback.isEnabled = true
        setContentView(buildQueueView())
        applyWindowColors()
    }

    private fun buildQueueView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBackground)
        root.setPadding(dp(16), dp(14), dp(16), dp(14))

        val clear = TextView(this)
        clear.text = "Temizle"
        clear.textSize = 14f
        clear.typeface = Typeface.DEFAULT_BOLD
        clear.setTextColor(cDanger)
        clear.setPadding(dp(10), dp(10), dp(4), dp(10))
        clear.setOnClickListener {
            if (loadQueue().length() > 0) {
                alertBuilder().setTitle("Bekleyenler silinsin mi?").setMessage("Gönderilmemiş tüm kayıtlar silinecek.")
                    .setPositiveButton("Sil") { _, _ ->
                        saveQueue(JSONArray())
                        showQueue()
                    }
                    .setNegativeButton("İptal", null).show()
            }
        }
        root.addView(subHeader("Bekleyenler", clear))

        val q = loadQueue()
        if (q.length() == 0) {
            val empty = TextView(this)
            empty.text = "Bekleyen kayıt yok.\nBağlantı yokken 'Sonra gönder' ile kaydettiklerin burada listelenir."
            empty.textSize = 14f
            empty.gravity = Gravity.CENTER
            empty.setTextColor(cMuted)
            root.addView(empty, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return root
        }

        val info = TextView(this)
        info.text = "Gönderim: " + transportLabels[prefs.getInt("transport", 0)] + "   ·   Sonra: " + afterLabels[prefs.getInt("after", 1)]
        info.textSize = 12f
        info.setTextColor(cMuted)
        info.setPadding(dp(2), dp(10), dp(2), 0)
        root.addView(info)

        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        for (i in 0 until q.length()) {
            val o = q.getJSONObject(i)
            val id = o.optLong("id")
            val text = o.optString("t")
            val c = card()
            c.setPadding(dp(14), dp(12), dp(14), dp(12))
            val meta = TextView(this)
            meta.text = "#" + (i + 1) + "  ·  " + fmt.format(Date(o.optLong("ts")))
            meta.textSize = 12f
            meta.setTextColor(cMuted)
            meta.setPadding(0, 0, 0, dp(6))
            c.addView(meta)
            val body = TextView(this)
            body.text = text
            body.textSize = 15f
            body.maxLines = 4
            body.ellipsize = TextUtils.TruncateAt.END
            body.setTextColor(cText)
            body.setPadding(0, 0, 0, dp(10))
            c.addView(body)
            val actions = LinearLayout(this)
            actions.orientation = LinearLayout.HORIZONTAL
            val editBtn = styledButton("Metne al", cCard, cPrimary, cBorder, 40) {
                result.setText(text)
                removeQueueId(id)
                showMain()
                status.text = "Kayıt düzenlemek için metin kutusuna alındı."
            }
            val delBtn = styledButton("Sil", cCard, cDanger, cBorder, 40) {
                removeQueueId(id)
                showQueue()
            }
            val p1 = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val p2 = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p2.leftMargin = dp(6)
            actions.addView(editBtn, p1)
            actions.addView(delBtn, p2)
            c.addView(actions)
            val cp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cp.topMargin = dp(10)
            col.addView(c, cp)
        }
        root.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val sendAll = styledButton("Hepsini gönder (" + q.length() + ")", cSuccess, 0xFFFFFFFF.toInt()) { confirmSendQueue() }
        val sp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.topMargin = dp(10)
        root.addView(sendAll, sp)
        return root
    }

    private val webPage = """<!doctype html>
<html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Okut-Yaz</title>
<style>
:root{color-scheme:light dark;--bg:#f1f5f9;--card:#fff;--text:#0f172a;--muted:#64748b;--primary:#2563eb}
@media(prefers-color-scheme:dark){:root{--bg:#0b1120;--card:#172033;--text:#e5e7eb;--muted:#94a3b8;--primary:#3b82f6}}
body{margin:0;font-family:system-ui,Segoe UI,sans-serif;background:var(--bg);color:var(--text)}
main{max-width:640px;margin:0 auto;padding:24px 16px}
h1{margin:0 0 4px;font-size:26px}
p{color:var(--muted)}
.card{background:var(--card);border-radius:16px;padding:16px;margin-top:14px;box-shadow:0 1px 3px rgba(0,0,0,.15)}
.big{font-size:28px;font-weight:700;word-break:break-all}
button{background:var(--primary);color:#fff;border:0;border-radius:12px;padding:12px 18px;font-size:16px;font-weight:600;cursor:pointer}
input{font-size:18px;padding:10px;border-radius:10px;border:1px solid var(--muted);width:120px;background:var(--card);color:var(--text)}
.small{font-size:13px;color:var(--muted)}
.item{display:flex;gap:10px;align-items:center;justify-content:space-between}
.item span{word-break:break-all}
</style></head><body><main>
<h1>Okut-Yaz</h1>
<p>Telefondan gönderilen metinler burada görünür. Kopyala'ya basıp bilgisayardaki alana yapıştır (Ctrl+V).</p>
<div id="login" class="card"><p>Telefondaki 4 haneli kodu gir:</p><input id="code" inputmode="numeric" maxlength="4" autofocus> <button onclick="go()">Bağlan</button><p id="err" class="small"></p></div>
<div id="app" style="display:none">
<div class="card"><div class="small">SON GÖNDERİLEN</div><div class="big" id="last">–</div><p><button onclick="copyLast()">Kopyala</button> <span id="msg" class="small"></span></p></div>
<div id="list"></div></div>
<script>
var code="",since=-1,lastText="",timer=null;
function go(){code=document.getElementById('code').value.trim();since=-1;poll()}
document.getElementById('code').addEventListener('keydown',function(e){if(e.key==='Enter')go()});
function copyText(t){
  if(navigator.clipboard&&window.isSecureContext){return navigator.clipboard.writeText(t)}
  var ta=document.createElement('textarea');ta.value=t;ta.style.position='fixed';ta.style.opacity='0';document.body.appendChild(ta);ta.select();
  var ok=false;try{ok=document.execCommand('copy')}catch(e){}
  document.body.removeChild(ta);return ok?Promise.resolve():Promise.reject()
}
function msg(t){document.getElementById('msg').textContent=t}
function copyLast(){if(!lastText)return;copyText(lastText).then(function(){msg('Kopyalandı. Şimdi Ctrl+V ile yapıştır.')},function(){msg('Kopyalanamadı, metni elle seçip kopyala.')})}
function addItem(it){
  lastText=it.text;document.getElementById('last').textContent=it.text;
  var div=document.createElement('div');div.className='card item';
  var sp=document.createElement('span');sp.textContent=it.text;
  var b=document.createElement('button');b.textContent='Kopyala';b.onclick=function(){lastText=it.text;copyLast()};
  div.appendChild(sp);div.appendChild(b);
  var l=document.getElementById('list');l.insertBefore(div,l.firstChild);
  copyText(it.text).then(function(){msg('Otomatik kopyalandı. Ctrl+V ile yapıştır.')},function(){msg('Yeni metin geldi. Kopyala düğmesine bas.')})
}
function poll(){
  fetch('/poll?code='+encodeURIComponent(code)+'&since='+since).then(function(r){
    if(r.status===403){
      document.getElementById('err').textContent='Kod yanlış.';
      document.getElementById('login').style.display='block';document.getElementById('app').style.display='none';
      return null}
    return r.json()
  }).then(function(d){
    if(!d)return;
    document.getElementById('login').style.display='none';document.getElementById('app').style.display='block';
    if(since<0){since=d.last}else{d.items.forEach(function(it){since=Math.max(since,it.id);addItem(it)})}
    timer=setTimeout(poll,700)
  }).catch(function(){timer=setTimeout(poll,2000)})
}
</script></main></body></html>"""

    private fun wifiError(res: String): String {
        return if (res == "ERR:kod") "kod yanlış" else res
    }

    private fun wifiRequest(ip: String, code: String, text: String, after: Int): String {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(ip.trim(), WIFI_PORT), 4000)
                s.soTimeout = 8000
                val b64 = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val afterName = when (after) {
                    1 -> "enter"
                    2 -> "tab"
                    else -> "none"
                }
                val payload = code.trim() + "\n" + b64 + "\n" + afterName + "\n"
                val out = s.getOutputStream()
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                s.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: "PC yanıt vermedi"
            }
        } catch (e: Exception) {
            e.localizedMessage ?: "bağlantı kurulamadı"
        }
    }

    // USB HID usage IDs; ASCII-compatible US keyboard mapping.
    private fun mapKeyUs(c:Char):Pair<Int,Int>? {
        if(c in 'a'..'z') return 0 to (0x04+c.code-'a'.code)
        if(c in 'A'..'Z') return 0x02 to (0x04+c.code-'A'.code)
        if(c in '1'..'9') return 0 to (0x1E+c.code-'1'.code)
        if(c=='0') return 0 to 0x27
        return when(c){' '->0 to 0x2C;'-'->0 to 0x2D;'_'->0x02 to 0x2D;'='->0 to 0x2E;'+'->0x02 to 0x2E;'['->0 to 0x2F;']'->0 to 0x30;'\\'->0 to 0x31;';'->0 to 0x33;':'->0x02 to 0x33;'\''->0 to 0x34;'"'->0x02 to 0x34;','->0 to 0x36;'.'->0 to 0x37;'/'->0 to 0x38;else->null}
    }

    // Türkçe Q klavye düzeni (HID tuş konumları). 0x02 = Shift, 0x40 = AltGr (sağ Alt).
    private fun mapKeyTr(c: Char): Pair<Int, Int>? {
        val sh = 0x02
        val ag = 0x40
        if (c in 'a'..'z' && c != 'i') return 0 to (0x04 + c.code - 'a'.code)
        if (c in 'A'..'Z' && c != 'I') return sh to (0x04 + c.code - 'A'.code)
        if (c in '1'..'9') return 0 to (0x1E + c.code - '1'.code)
        return when (c) {
            '0' -> 0 to 0x27
            'i' -> 0 to 0x34
            'İ' -> sh to 0x34
            'ı' -> 0 to 0x0C
            'I' -> sh to 0x0C
            'ç' -> 0 to 0x37
            'Ç' -> sh to 0x37
            'ö' -> 0 to 0x36
            'Ö' -> sh to 0x36
            'ü' -> 0 to 0x30
            'Ü' -> sh to 0x30
            'ğ' -> 0 to 0x2F
            'Ğ' -> sh to 0x2F
            'ş' -> 0 to 0x33
            'Ş' -> sh to 0x33
            ' ' -> 0 to 0x2C
            '!' -> sh to 0x1E
            '\'' -> sh to 0x1F
            '^' -> sh to 0x20
            '+' -> sh to 0x21
            '%' -> sh to 0x22
            '&' -> sh to 0x23
            '/' -> sh to 0x24
            '(' -> sh to 0x25
            ')' -> sh to 0x26
            '=' -> sh to 0x27
            '*' -> 0 to 0x2D
            '?' -> sh to 0x2D
            '-' -> 0 to 0x2E
            '_' -> sh to 0x2E
            '"' -> 0 to 0x35
            ',' -> 0 to 0x31
            ';' -> sh to 0x31
            '.' -> 0 to 0x38
            ':' -> sh to 0x38
            '<' -> 0 to 0x64
            '>' -> sh to 0x64
            '@' -> ag to 0x14
            '#' -> ag to 0x20
            '$' -> ag to 0x21
            '{' -> ag to 0x24
            '[' -> ag to 0x25
            ']' -> ag to 0x26
            '}' -> ag to 0x27
            '\\' -> ag to 0x2D
            '|' -> ag to 0x2E
            '~' -> ag to 0x30
            '`' -> ag to 0x31
            '€' -> ag to 0x08
            '₺' -> ag to 0x17
            else -> null
        }
    }


    // Türkçe F klavye düzeni: küçük harflerin tuş konumları (HID kullanım kodları).
    private val fLetters: Map<Char, Int> = mapOf(
        'f' to 0x14, 'g' to 0x1A, 'ğ' to 0x08, 'ı' to 0x15, 'o' to 0x17, 'd' to 0x1C,
        'r' to 0x18, 'n' to 0x0C, 'h' to 0x12, 'p' to 0x13, 'q' to 0x2F, 'w' to 0x30,
        'u' to 0x04, 'i' to 0x16, 'e' to 0x07, 'a' to 0x09, 'ü' to 0x0A, 't' to 0x0B,
        'k' to 0x0D, 'm' to 0x0E, 'l' to 0x0F, 'y' to 0x33, 'ş' to 0x34, 'x' to 0x31,
        'j' to 0x1D, 'ö' to 0x1B, 'v' to 0x06, 'c' to 0x19, 'ç' to 0x05, 'z' to 0x11,
        's' to 0x10, 'b' to 0x36
    )

    // Türkçe F düzeni. 0x02 = Shift, 0x40 = AltGr (sağ Alt).
    private fun mapKeyF(c: Char): Pair<Int, Int>? {
        val sh = 0x02
        val ag = 0x40
        val direct = fLetters[c]
        if (direct != null) return 0 to direct
        val lower = when (c) {
            'İ' -> 'i'
            'I' -> 'ı'
            else -> c.lowercaseChar()
        }
        if (lower != c) {
            val up = fLetters[lower]
            if (up != null) return sh to up
        }
        if (c in '1'..'9') return 0 to (0x1E + c.code - '1'.code)
        return when (c) {
            '0' -> 0 to 0x27
            ' ' -> 0 to 0x2C
            '+' -> 0 to 0x35
            '*' -> sh to 0x35
            '!' -> sh to 0x1E
            '"' -> sh to 0x1F
            '^' -> sh to 0x20
            '$' -> sh to 0x21
            '%' -> sh to 0x22
            '&' -> sh to 0x23
            '\'' -> sh to 0x24
            '(' -> sh to 0x25
            ')' -> sh to 0x26
            '=' -> sh to 0x27
            '/' -> 0 to 0x2D
            '?' -> sh to 0x2D
            '-' -> 0 to 0x2E
            '_' -> sh to 0x2E
            '.' -> 0 to 0x37
            ':' -> sh to 0x37
            ',' -> 0 to 0x38
            ';' -> sh to 0x38
            '<' -> 0 to 0x64
            '>' -> sh to 0x64
            '@' -> ag to 0x14
            '#' -> ag to 0x20
            '{' -> ag to 0x24
            '[' -> ag to 0x25
            ']' -> ag to 0x26
            '}' -> ag to 0x27
            '\\' -> ag to 0x2D
            '|' -> ag to 0x2E
            '~' -> ag to 0x30
            '`' -> ag to 0x31
            '€' -> ag to 0x08
            '₺' -> ag to 0x0B
            else -> null
        }
    }

    private fun mapKey(c: Char): Pair<Int, Int>? {
        return when (prefs.getInt("kblayout", 1)) {
            1 -> mapKeyTr(c)
            2 -> mapKeyF(c)
            else -> mapKeyUs(c)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == 22 && granted) setupHid()
        if (requestCode == 11) {
            if (granted) takePhoto() else status.text = "Kamera izni verilmedi. Galeriden fotoğraf seçebilirsin."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
        try {
            unregisterReceiver(btStateReceiver)
        } catch (e: Exception) {
        }
        try {
            hid?.unregisterApp()
        } catch (e: Exception) {
        }
        executor.shutdown()
        recognizer.close()
    }
}
