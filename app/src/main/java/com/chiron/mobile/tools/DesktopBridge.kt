package com.chiron.mobile.tools

import com.chiron.mobile.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PC'deki Chiron'a köprü — Tailscale üzerinden `tailscale serve` adresine.
 *
 * Kimlik: PC'nin PIN kapısı (backend/core/remote_auth.py). PIN bir kez
 * Ayarlar'da girilip `login()` ile HttpOnly çerez alınıyor; çerez 30 gün geçerli
 * ve burada saklanıyor. PIN'in kendisi telefonda tutulmuyor. Yerel uygulama
 * Origin başlığı göndermediği için PC'nin Origin kapısı bu istekleri geçiriyor.
 */
class DesktopBridge(private val settings: AppSettings, readTimeoutSeconds: Long = 100) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // /ask, PC'deki Chiron ekrana bakıp konuşana kadar bekliyor (sunucu tavanı 90 sn).
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    fun ask(args: JSONObject): JSONObject {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return fail("Soru boş.")
        val response = post("/api/remote/ask", JSONObject().put("question", question).put("text", question))
        if (!response.optBoolean("success")) return response
        return ok("PC'deki Chiron'un cevabı.", "answer" to response.optJSONObject("data")?.optString("answer").orEmpty())
    }

    fun command(args: JSONObject): JSONObject =
        post("/api/remote/desktop", JSONObject().put("action", args.optString("action")).put("text", args.optString("text")))

    fun note(text: String): JSONObject =
        post("/api/remote/note", JSONObject().put("text", text).put("source", "telefon"))

    /** PIN ile oturum açar. (başarılı mı, mesaj) */
    fun login(pin: String): Pair<Boolean, String> {
        return try {
            // Adres doğrulaması da try içinde: şemasız adres `.url()`'de fırlatıyor.
            val request = Request.Builder()
                .url(settings.pcUrl + "/api/remote/login")
                .post(JSONObject().put("pin", pin).toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val body = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                val cookie = response.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("chiron_remote=") }
                    ?.substringAfter("chiron_remote=")?.substringBefore(';')
                if (body.optBoolean("success") && !cookie.isNullOrBlank()) {
                    settings.pcSession = cookie
                    true to "PC'ye bağlandı."
                } else {
                    false to body.optString("message", "Giriş başarısız.")
                }
            }
        } catch (e: IOException) {
            false to unreachable()
        } catch (e: Exception) {
            // Geçersiz adres (IllegalArgumentException) ya da JSON olmayan cevap.
            false to "PC adresi ya da cevabı geçersiz: ${e.message}"
        }
    }

    fun post(path: String, body: JSONObject): JSONObject =
        call(path) { it.post(body.toString().toRequestBody(JSON)) }

    fun get(path: String): JSONObject = call(path) { it.get() }

    /** Dosya indirir (APK teslimi). Hata mesajı ya da null (başarılı). */
    fun download(path: String, dest: java.io.File): String? {
        if (settings.pcUrl.isBlank()) return "PC adresi ayarlanmamış."
        if (settings.pcSession.isBlank()) return "PC'ye giriş yapılmamış; Chiron ayarlarından PIN gir."
        return try {
            val request = Request.Builder()
                .url(settings.pcUrl + path)
                .header("Cookie", "chiron_remote=${settings.pcSession}")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    settings.pcSession = ""
                    return "PC oturumu sona ermiş; Chiron ayarlarından PIN'i tekrar gir."
                }
                if (!response.isSuccessful) return "PC dosyayı vermedi (${response.code})."
                val body = response.body ?: return "PC boş cevap verdi."
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            null
        } catch (e: IOException) {
            dest.delete()
            unreachable()
        } catch (e: Exception) {
            dest.delete()
            "İndirme başarısız: ${e.message}"
        }
    }

    private fun call(path: String, method: (Request.Builder) -> Request.Builder): JSONObject {
        if (settings.pcUrl.isBlank()) return fail("PC adresi ayarlanmamış.")
        if (settings.pcSession.isBlank()) return fail("PC'ye giriş yapılmamış; Chiron ayarlarından PIN gir.")
        return try {
            val request = method(
                Request.Builder()
                    .url(settings.pcUrl + path)
                    .header("Cookie", "chiron_remote=${settings.pcSession}"),
            ).build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    settings.pcSession = ""
                    return fail("PC oturumu sona ermiş; Chiron ayarlarından PIN'i tekrar gir.")
                }
                val text = response.body?.string().orEmpty()
                runCatching { JSONObject(text) }.getOrElse { fail("PC'den beklenmeyen cevap (${response.code}).") }
            }
        } catch (e: IOException) {
            fail(unreachable())
        } catch (e: Exception) {
            fail("PC adresi ya da cevabı geçersiz: ${e.message}")
        }
    }

    private fun unreachable() =
        "PC'ye ulaşılamadı. PC açık ve uyanık mı, Chiron çalışıyor mu, telefonda Tailscale bağlı mı?"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
