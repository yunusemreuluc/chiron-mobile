package com.airon.mobile.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Son bildirimleri bellekte tutar (`get_recent_notifications`). Diske yazılmıyor:
 * bildirim içerikleri özel mesajlar, telefon yeniden başlayınca silinmeleri doğru.
 */
class AironNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // Grup özetleri ("3 yeni mesaj") tek tek mesajları tekrar ediyor.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val entry = Entry(appLabel(sbn.packageName), sbn.packageName, title, text, sbn.postTime)
        val isNew = synchronized(buffer) {
            val seen = buffer.removeAll { it.pkg == entry.pkg && it.title == entry.title && it.text == entry.text }
            buffer.addFirst(entry)
            while (buffer.size > MAX) buffer.removeLast()
            !seen
        }
        announceIfMessage(sbn, entry, isNew)
    }

    /**
     * Araba modu için yeni kişisel mesajı servise haber ver. WhatsApp aynı mesajı
     * güncellemelerle tekrar yayınlıyor (yukarıdaki tekrar süzgeci), bir de
     * "WhatsApp: 3 sohbetten 5 mesaj" gibi özet ve "yeni mesajlar kontrol ediliyor"
     * gibi sürekli bildirimler atıyor — onlar okunmuyor.
     */
    private fun announceIfMessage(sbn: StatusBarNotification, entry: Entry, isNew: Boolean) {
        if (sbn.packageName !in MessageAnnouncer.MESSAGING) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.category != null && n.category != Notification.CATEGORY_MESSAGE) return
        if (System.currentTimeMillis() - sbn.postTime > 60_000) return // eski bildirimin yeniden yayını

        // MessagingStyle varsa (WhatsApp, Google Mesajlar) son mesajı oradan al: gönderen boşsa ya da
        // kullanıcının kendisiyse bu BİZİM mesajımız (Android sözleşmesi) — okunmaz. Tekrar ayrımı
        // mesajın kendi zaman damgasıyla: aynı kişiden ikinci "Tamam" da okunuyor.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val message = if (style != null && last != null) {
            val person = last.person ?: return
            if (person.name?.toString() == style.user.name?.toString()) return
            val text = last.text?.toString().orEmpty()
            if (text.isBlank()) return
            val name = person.name?.toString().orEmpty().ifBlank { entry.title }
            val convo = style.conversationTitle?.toString()
            val sender = if (style.isGroupConversation && !convo.isNullOrBlank()) "$convo grubunda $name" else name
            if (!remember("${sbn.packageName}|$convo|$name|${last.timestamp}|$text")) return
            MessageAnnouncer.Message(entry.app, sender, text.take(400))
        } else {
            if (!isNew) return
            if (entry.text.isBlank() || entry.title.isBlank() || entry.title.equals(entry.app, ignoreCase = true)) return
            if (!remember("${sbn.packageName}|${sbn.key}|${entry.title}|${entry.text}|${sbn.postTime / 1000}")) return
            MessageAnnouncer.Message(entry.app, entry.title, entry.text.take(400))
        }
        MessageAnnouncer.post(message)
    }

    /** Duyurulan mesaj kimlikleri — bildirim geçmişi tamponundan ayrı. Yeni ise true. */
    private fun remember(id: String): Boolean = synchronized(announced) {
        if (!announced.add(id)) return false
        if (announced.size > MAX_ANNOUNCED) announced.remove(announced.first())
        true
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    data class Entry(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

    companion object {
        private const val MAX = 60
        private const val MAX_ANNOUNCED = 200
        private val announced = LinkedHashSet<String>()
        private val buffer = ArrayDeque<Entry>()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, AironNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun recent(context: Context, args: JSONObject): JSONObject {
            if (!isEnabled(context)) return fail("Bildirim erişimi verilmemiş.")
            val filter = ContactTools.normalize(args.optString("app_name"))
            val limit = args.optInt("limit", 8).coerceIn(1, 20)
            val clock = SimpleDateFormat("HH:mm", Locale("tr"))
            val items = synchronized(buffer) {
                buffer.filter { filter.isBlank() || ContactTools.normalize(it.app).contains(filter) }.take(limit)
            }
            if (items.isEmpty()) {
                return ok(if (filter.isBlank()) "Yeni bildirim yok." else "${args.optString("app_name")} için bildirim yok.")
            }
            val list = JSONArray()
            items.forEach {
                list.put(
                    JSONObject().put("app", it.app).put("title", it.title)
                        .put("text", it.text.take(300)).put("time", clock.format(Date(it.time))),
                )
            }
            return ok("${items.size} bildirim.", "notifications" to list)
        }
    }
}
