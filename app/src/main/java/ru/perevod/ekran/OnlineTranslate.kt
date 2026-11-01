package ru.perevod.ekran

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Онлайн-перевод китайский → русский.
 * 1) Microsoft (работает в Китае без VPN, переводит напрямую, целыми фразами).
 * 2) Google (нужен VPN).
 * Если оба не ответили — вернёт null, и приложение возьмёт офлайн-перевод.
 */
object OnlineTranslate {

    private var msToken: String? = null
    private var msTokenTime = 0L

    /** Переводит список строк одним запросом. Возвращает пару (переводы, имя сервиса). */
    fun translate(texts: List<String>): Pair<List<String>, String>? {
        if (texts.isEmpty()) return null
        try {
            return microsoft(texts) to "Microsoft"
        } catch (_: Exception) {
            msToken = null
        }
        try {
            return google(texts) to "Google"
        } catch (_: Exception) {
        }
        return null
    }

    private fun open(url: String, timeout: Int = 7000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }

    // ---------- Microsoft ----------

    private fun token(): String {
        val now = System.currentTimeMillis()
        msToken?.let { if (now - msTokenTime < 8 * 60_000) return it }
        val c = open("https://edge.microsoft.com/translate/auth")
        val t = c.inputStream.bufferedReader().readText().trim()
        if (c.responseCode != 200 || t.length < 20) throw IllegalStateException("token")
        msToken = t; msTokenTime = now
        return t
    }

    private fun microsoft(texts: List<String>): List<String> {
        val body = JSONArray()
        texts.forEach { body.put(JSONObject().put("Text", it)) }
        val c = open(
            "https://api-edge.cognitive.microsofttranslator.com/translate" +
                "?from=zh-Hans&to=ru&api-version=3.0&textType=plain"
        )
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer " + token())
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (c.responseCode != 200) throw IllegalStateException("ms ${c.responseCode}")
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        return List(arr.length()) { i ->
            arr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text")
        }
    }

    // ---------- Google ----------

    private const val SEP = "\n"

    private fun google(texts: List<String>): List<String> {
        // склеиваем строки через перевод строки, чтобы был один запрос
        val q = texts.joinToString(SEP) { it.replace("\n", " ") }
        val c = open(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=zh-CN&tl=ru&dt=t&q=" +
                URLEncoder.encode(q, "UTF-8"),
            6000
        )
        if (c.responseCode != 200) throw IllegalStateException("g ${c.responseCode}")
        val root = JSONArray(c.inputStream.bufferedReader().readText())
        val parts = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONArray(i).optString(0))
        val lines = sb.toString().split(SEP).map { it.trim() }
        if (lines.size != texts.size) throw IllegalStateException("g lines")
        return lines
    }
}
