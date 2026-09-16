package com.airon.mobile.live

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini Live — ham WebSocket istemcisi (SDK'sız).
 *
 * Protokol 2026-09-15'te PC'den gerçek anahtarla doğrulandı:
 *  - sunucu mesajları BINARY çerçeve olarak geliyor (içi JSON)
 *  - ilk cevap `setupComplete`
 *  - model sesi `serverContent.modelTurn.parts[].inlineData` → audio/pcm;rate=24000
 *  - araç çağrısı `toolCall.functionCalls[]` (id, name, args)
 *  - mikrofon `realtimeInput.audio` (audio/pcm;rate=16000) kabul ediliyor
 *
 * Model PC'deki Aıron'la aynı: aynı ses, aynı dil — iki cihazda tek kişilik.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onAudio(pcm24k: ByteArray)
        fun onOutputTranscript(text: String)
        fun onInputTranscript(text: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onClosed(reason: String)
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile var isReady = false
        private set

    fun connect(model: String, voice: String, systemPrompt: String, tools: JSONArray) {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val setup = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", model)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject()
                                .put("languageCode", "tr-TR")
                                .put(
                                    "voiceConfig",
                                    JSONObject().put(
                                        "prebuiltVoiceConfig",
                                        JSONObject().put("voiceName", voice),
                                    ),
                                ),
                        ),
                )
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
                .put("outputAudioTranscription", JSONObject())
                // Ekrandaki "senin söylediğin" yazısı ayrı bir tanıyıcıdan geliyor (modelin anladığı
                // bu değil). Dil verilmeyince İngilizce tahmin edip "Aıron"u "Iron Man" yazıyordu.
                // languageHints + adaptationPhrases Gemini API'de destekleniyor (google-genai 2.10
                // _live_converters; 2026-09-16'da canlı setupComplete ile doğrulandı).
                .put(
                    "inputAudioTranscription",
                    JSONObject()
                        .put("languageHints", JSONObject().put("languageCodes", JSONArray().put("tr-TR")))
                        .put("adaptationPhrases", JSONArray(TRANSCRIPTION_PHRASES)),
                ),
        )

        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(setup.toString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    finish("kapandı ($code) $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Live bağlantı hatası", t)
                    finish(t.message ?: "bağlantı hatası")
                }
            },
        )
    }

    private fun finish(reason: String) {
        if (socket == null) return
        socket = null
        isReady = false
        listener.onClosed(reason)
    }

    private fun handle(raw: String) {
        // close() sonrası gelen çerçeveler yeni oturumun durumuna dokunmasın.
        if (socket == null) return
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "JSON olmayan çerçeve: ${raw.take(120)}")
            return
        }

        if (msg.has("setupComplete")) {
            isReady = true
            listener.onReady()
            return
        }

        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                listener.onToolCall(
                    call.optString("id"),
                    call.optString("name"),
                    call.optJSONObject("args") ?: JSONObject(),
                )
            }
        }

        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isNotEmpty()) listener.onAudio(Base64.decode(data, Base64.DEFAULT))
            }
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
            ?.let(listener::onOutputTranscript)
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
            ?.let(listener::onInputTranscript)
        if (content.optBoolean("interrupted")) listener.onInterrupted()
        if (content.optBoolean("turnComplete")) listener.onTurnComplete()
    }

    fun sendAudio(pcm16k: ByteArray, length: Int) {
        val ws = socket ?: return
        if (!isReady) return
        val data = Base64.encodeToString(pcm16k, 0, length, Base64.NO_WRAP)
        ws.send(
            JSONObject().put(
                "realtimeInput",
                JSONObject().put("audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000")),
            ).toString(),
        )
    }

    fun sendText(text: String) {
        socket?.send(
            JSONObject().put(
                "clientContent",
                JSONObject()
                    .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("turnComplete", true),
            ).toString(),
        )
    }

    fun sendToolResponse(id: String, name: String, response: JSONObject) {
        socket?.send(
            JSONObject().put(
                "toolResponse",
                JSONObject().put(
                    "functionResponses",
                    JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", response)),
                ),
            ).toString(),
        )
    }

    fun close() {
        val ws = socket ?: return
        socket = null
        isReady = false
        ws.close(1000, "kullanıcı kapattı")
    }

    companion object {
        /** Tanıyıcının yanlış yazdığı özel adlar. */
        private val TRANSCRIPTION_PHRASES = listOf("Aıron", "WhatsApp", "YouTube", "Spotify", "Instagram", "Google Haritalar")
        private const val TAG = "AironLive"
    }
}
