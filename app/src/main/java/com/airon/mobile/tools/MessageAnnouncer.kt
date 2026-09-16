package com.airon.mobile.tools

/**
 * Bildirim okuyucudan servise yeni mesaj haberi (aynı süreç, kuyruk yok).
 * Servis çalışmıyorsa (dinleme kapalı) dinleyici null — haber düşer; araba modu
 * bilerek yalnızca "Aıron" dinlemesi açıkken çalışıyor.
 */
object MessageAnnouncer {
    data class Message(val app: String, val sender: String, val text: String)

    @Volatile var listener: ((Message) -> Unit)? = null

    /** Mesajlaşma uygulamaları: WhatsApp (+ İşletme) ve yaygın SMS uygulamaları. */
    val MESSAGING = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
    )

    fun post(message: Message) {
        listener?.invoke(message)
    }
}
