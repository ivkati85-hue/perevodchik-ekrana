package ru.perevod.ekran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "perevod"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var latest: Image? = null
    private val imgLock = Any()

    private var bubble: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var overlay: FrameLayout? = null
    private var busy = false

    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator

    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WindowManager::class.java)
        recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )

        try {
            startCapture(code, data)
        } catch (e: Exception) {
            toast("Не удалось начать: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_NOT_STICKY
    }

    // ---------- уведомление (обязательно для записи экрана) ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Переводчик экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Переводчик экрана работает")
            .setContentText("Нажмите «RU» поверх любого приложения")
            .addAction(Notification.Action.Builder(null, "Остановить", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- захват экрана ----------

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("нет доступа к экрану")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
        val dpi = resources.displayMetrics.densityDpi

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
                synchronized(imgLock) {
                    latest?.close()
                    latest = img
                }
            } catch (_: Exception) {
            }
        }, main)
        reader = r
        display = p.createVirtualDisplay(
            "perevod", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, main
        )
    }

    private fun grabBitmap(): Bitmap? = synchronized(imgLock) {
        val img = latest ?: return null
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val rowW = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowW, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            if (rowW == img.width) full
            else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- плавающая кнопка ----------

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun showBubble() {
        val size = dp(54f)
        val b = TextView(this).apply {
            text = "RU"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6D32F2F"))
                setStroke(dp(2f), Color.WHITE)
            }
            elevation = dp(6f).toFloat()
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - dp(8f)
            y = screenH / 3
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8f) || abs(dy) > dp(8f)) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(v, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleClick()
                    true
                }
                else -> false
            }
        }
        wm.addView(b, bubbleParams)
        bubble = b
    }

    private fun onBubbleClick() {
        if (overlay != null) {
            closeOverlay(); return
        }
        if (busy) return
        busy = true
        val b = bubble ?: return
        b.visibility = View.INVISIBLE
        // ждём новый кадр без кнопки
        main.postDelayed({
            val bmp = grabBitmap()
            b.visibility = View.VISIBLE
            b.text = "…"
            if (bmp == null) {
                done(); toast("Экран ещё не готов, нажмите ещё раз"); return@postDelayed
            }
            recognize(bmp)
        }, 350)
    }

    private fun done() {
        busy = false
        bubble?.text = "RU"
    }

    // ---------- распознавание и перевод ----------

    private fun hasChinese(s: String): Boolean = s.any {
        val c = it.code
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF
    }

    private data class Piece(val box: Rect, val zh: String, var ru: String = "")

    private fun recognize(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val pieces = text.textBlocks
                    .filter { it.boundingBox != null && hasChinese(it.text) }
                    .map { Piece(it.boundingBox!!, it.text.replace("\n", "")) }
                bmp.recycle()
                if (pieces.isEmpty()) {
                    done(); toast("Китайского текста не нашёл"); return@addOnSuccessListener
                }
                translateAll(pieces)
            }
            .addOnFailureListener { e ->
                bmp.recycle(); done(); toast("Ошибка распознавания: ${e.message}")
            }
    }

    private fun translateAll(pieces: List<Piece>) {
        val tasks: List<Task<String>> = pieces.map { p ->
            translator.translate(p.zh).addOnSuccessListener { p.ru = it }
        }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val failed = tasks.firstOrNull { !it.isSuccessful }
            if (pieces.all { it.ru.isEmpty() }) {
                done()
                val msg = failed?.exception?.message ?: ""
                toast("Нет словаря. Откройте приложение и нажмите «Скачать словарь» (с VPN). $msg")
                return@addOnCompleteListener
            }
            showOverlay(pieces.filter { it.ru.isNotEmpty() })
            done()
        }
    }

    // ---------- слой с переводом ----------

    private fun showOverlay(pieces: List<Piece>) {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#22000000"))
        root.setOnClickListener { closeOverlay() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, lp)
        overlay = root

        // кнопку держим поверх слоя
        bubble?.let { wm.removeView(it); wm.addView(it, bubbleParams) }

        // где на экране реально начался слой (из-за статус-бара)
        root.post {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            for (p in pieces) addLabel(root, p, loc[0], loc[1])
        }
    }

    private fun addLabel(root: FrameLayout, p: Piece, offX: Int, offY: Int) {
        val left = (p.box.left - offX).coerceAtLeast(0)
        // плашку можно немного расширить вправо, чтобы русский влез крупнее
        val maxW = screenW - left - dp(2f)
        val w = minOf(max((p.box.width() * 1.4f).toInt(), dp(60f)), maxW)
        val h = max(p.box.height(), dp(20f))
        val tv = TextView(this).apply {
            text = Shorten.apply(p.ru)
            setTextColor(Color.BLACK)
            setPadding(dp(2f), 0, dp(2f), 0)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(0f, 0.9f)
            background = GradientDrawable().apply {
                cornerRadius = dp(3f).toFloat()
                setColor(Color.parseColor("#F2FFF59D"))
                setStroke(1, Color.parseColor("#99F57F17"))
            }
            setAutoSizeTextTypeUniformWithConfiguration(8, 18, 1, TypedValue.COMPLEX_UNIT_SP)
            setOnClickListener { showDetail(p) }
        }
        val lp = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = left
            topMargin = (p.box.top - offY).coerceAtLeast(0)
        }
        root.addView(tv, lp)
    }

    private fun showDetail(p: Piece) {
        val root = overlay ?: return
        val pad = dp(16f)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(10f).toFloat()
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = p.ru
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        panel.addView(TextView(this).apply {
            text = p.zh
            textSize = 15f
            setTextColor(Color.GRAY)
            setPadding(0, dp(10f), 0, dp(10f))
        })
        panel.addView(TextView(this).apply {
            text = "Закрыть"
            textSize = 16f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.END
            setOnClickListener { root.removeView(panel.parent as View) }
        })
        val scroll = ScrollView(this).apply { addView(panel) }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins(dp(12f), 0, dp(12f), dp(48f)) }
        root.addView(scroll, lp)
    }

    private fun closeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        closeOverlay()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        display?.release()
        synchronized(imgLock) { latest?.close(); latest = null }
        reader?.close()
        projection?.stop()
        projection = null
        if (::recognizer.isInitialized) recognizer.close()
        if (::translator.isInitialized) translator.close()
        super.onDestroy()
    }
}
