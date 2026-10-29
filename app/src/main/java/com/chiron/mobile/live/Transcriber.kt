package com.chiron.mobile.live

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Ekrandaki "senin söylediğin" satırının düzeltilmiş hali (2026-10-30, Yunus: "nasıl duyduysa
 * öyle yazsın"). Live'ın inputTranscription'ı modelin anladığı değil, yanındaki zayıf bir
 * tanıyıcı: model doğru anlarken ekrana başka şey yazıyordu. Tur bitince aynı ses güçlü bir
 * modele kelimesi kelimesine yazdırılıyor; asistanın cevabı bağlam olarak veriliyor ki yazı
 * modelin anladığıyla örtüşsün. Başarısızsa null — ekranda hızlı yazı kalır.
 */
class Transcriber(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun transcribe(pcm16k: ByteArray, assistantReply: String): String? {
        val prompt = buildString {
            append("Bu ses kaydında kullanıcı Türkçe konuşuyor. Söylediğini kelimesi kelimesine, ")
            append("doğru Türkçe yazımla yaz. Yalnız kullanıcının sözünü ver: tırnak, açıklama, etiket ekleme. ")
            append("Ses yoksa ya da anlaşılmıyorsa boş döndür.")
            if (assistantReply.isNotBlank()) {
                append("\n\nAsistan bu sözü duyup şöyle cevap verdi (yalnız bağlam, yazıya katma): ")
                append(assistantReply.take(600))
            }
            append("\n\nGeçebilecek özel adlar: ").append(PHRASES.joinToString(", "))
        }
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject()
                                    .put("mimeType", "audio/wav")
                                    .put("data", Base64.encodeToString(wav(pcm16k), Base64.NO_WRAP)),
                            ),
                        ),
                ),
            ),
        ).put("generationConfig", JSONObject().put("temperature", 0))
        return MODELS.firstNotNullOfOrNull { request(it, body) }
    }

    private fun request(model: String, body: JSONObject): String? {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "yazıya çevirme $model ${resp.code}: ${text.take(200)}")
                    return null
                }
                val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
                (0 until parts.length())
                    .mapNotNull { parts.getJSONObject(it).optString("text").takeIf(String::isNotBlank) }
                    .joinToString(" ")
                    .trim()
                    .trim('"', '“', '”')
                    .takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "yazıya çevirme $model başarısız", e)
            null
        }
    }

    /** 16 kHz mono 16-bit PCM → WAV (başlık 44 bayt). */
    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }

    companion object {
        private const val TAG = "ChironTranscribe"
        // 2026-10-30 ölçümü: 3.5-flash-lite 1,6 sn ve doğru; gemini-3.8-flash aşırı yüklüydü
        // (503 / 20 sn'de cevap yok). Sırayla denenir.
        private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        private const val SAMPLE_RATE = 16000
        private val PHRASES = listOf("Chiron", "Daphne", "Dilcep", "Claude", "WhatsApp", "YouTube", "Spotify", "Instagram")
    }
}
