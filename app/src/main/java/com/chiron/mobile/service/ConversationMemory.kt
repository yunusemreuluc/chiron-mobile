package com.chiron.mobile.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Oturumlar arası kısa hafıza (2026-10-30, Yunus: "sohbeti hatırlasın; bir şey yaptırıyorum, her
 * seferinde Dilcep dememe gerek kalmasın"). Gemini Live oturumu her "Chiron"da boş başlıyor:
 * ekrandaki son konuşma diske yazılır ve yeni oturumun sistem komutuna eklenir; uygulama
 * kapanıp açılınca sohbet ekranına da geri gelir. PC'deki karşılığı memory/son_konusma.py.
 */
class ConversationMemory(context: Context) {
    private val file = File(context.applicationContext.filesDir, "son_konusma.json")

    /** PC'deki süren iş (sohbet.work_context); oturum açılırken beklememek için önceden alınır. */
    @Volatile var workContext: String = ""

    fun save(lines: List<AssistantBus.Line>) {
        val keep = lines.filter { !it.claude }.takeLast(MAX_LINES)
        if (keep.isEmpty()) return
        val array = JSONArray()
        keep.forEach { array.put(JSONObject().put("u", it.fromUser).put("t", it.text.take(MAX_CHARS))) }
        runCatching { file.writeText(JSONObject().put("at", System.currentTimeMillis()).put("lines", array).toString()) }
    }

    /** Son 12 saatin konuşması (kullanıcıdan mı, metin); eskiyse boş. */
    fun load(): List<Pair<Boolean, String>> {
        val data = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        if (System.currentTimeMillis() - data.optLong("at") > KEEP_MS) return emptyList()
        val array = data.optJSONArray("lines") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { it.optBoolean("u") to it.optString("t") }?.takeIf { it.second.isNotBlank() }
        }
    }

    /** Yeni oturumun sistem komutuna giren bloklar; ikisi de yoksa null. */
    fun prompt(): String? {
        val previous = load().takeIf { it.isNotEmpty() }?.let { lines ->
            buildString {
                append("[ÖNCEKİ KONUŞMA]\n")
                append("Bu yeni bir oturum ama aynı asistansın ve son konuşmayı hatırlıyorsun (en eskisi üstte). Kullanıcı ")
                append("\"onu\", \"şunu da ekle\", \"devam et\", \"az önceki\" derse buradan anla; baştan sorma, konuyu yeniden ")
                append("açıklatma. Bu konuşmayı kendiliğinden özetleme ya da okuma.\n")
                lines.forEach { (fromUser, text) -> append(if (fromUser) "Kullanıcı: " else "Asistan: ").append(text).append('\n') }
            }.trimEnd()
        }
        return listOfNotNull(previous, workContext.ifBlank { null }).joinToString("\n\n").ifBlank { null }
    }

    private companion object {
        const val MAX_LINES = 20
        const val MAX_CHARS = 400
        const val KEEP_MS = 12 * 3600 * 1000L
    }
}
