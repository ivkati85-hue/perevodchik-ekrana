package ru.perevod.ekran

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val reqCapture = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Переводчик экрана\nкитайский → русский"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, pad, 0, pad)
        }
        col.addView(status)

        fun btn(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                setOnClickListener { onClick() }
            })
        }

        btn("1. Разрешить показ поверх приложений") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btn("2. Скачать словарь (один раз, с VPN)") { downloadModel() }
        btn("3. ЗАПУСТИТЬ") { start() }
        btn("Проверить обновление (нужен VPN)") { checkUpdate() }
        btn("Остановить") {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
        }
        col.addView(TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, pad, 0, 0)
            text = "Как пользоваться:\n" +
                "• Откройте любое приложение (Мейтуан и др.).\n" +
                "• Нажмите красную кнопку «RU» — поверх появится перевод.\n" +
                "• Мелкий текст: нажмите на жёлтую плашку — откроется крупно.\n" +
                "• Нажмите на пустое место — перевод закроется.\n" +
                "• Кнопку «RU» можно перетаскивать пальцем.\n\n" +
                "Перевод идёт онлайн через Microsoft — в Китае работает без VPN. Без интернета включается офлайн-словарь (менее точный)."
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun checkUpdate() {
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val c = java.net.URL(
                    "https://api.github.com/repos/ivkati85-hue/perevodchik-ekrana/releases/latest"
                ).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val json = org.json.JSONObject(c.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val cur = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                if (newer(tag, cur) && url != null) {
                    runOnUiThread {
                        Toast.makeText(this, "Есть версия $tag — скачиваю. Потом нажмите на файл.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    null
                } else "У вас последняя версия ($cur)"
            } catch (e: Exception) {
                "Не удалось проверить. Включите VPN и попробуйте снова."
            }
            if (msg != null) runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val mm = RemoteModelManager.getInstance()
        mm.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build())
            .continueWithTask { zh ->
                mm.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build())
                    .continueWith { ru -> (zh.result == true) && (ru.result == true) }
            }
            .addOnCompleteListener { t ->
                val model = t.isSuccessful && t.result == true
                status.text =
                    (if (overlay) "✅" else "❌") + " Показ поверх приложений\n" +
                    (if (model) "✅" else "❌") + " Словарь китайский-русский"
            }
    }

    private fun downloadModel() {
        status.text = "Скачиваю словарь (около 60 МБ)…\nНужен включённый VPN."
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                Toast.makeText(this, "Словарь скачан. VPN можно выключить.", Toast.LENGTH_LONG).show()
                translator.close()
                refreshStatus()
            }
            .addOnFailureListener { e ->
                status.text = "Не скачалось: ${e.message}\nВключите VPN и нажмите ещё раз."
                translator.close()
            }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала шаг 1", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Без разрешения на экран перевод не работает", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "Готово! Кнопка «RU» на экране", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }
}
