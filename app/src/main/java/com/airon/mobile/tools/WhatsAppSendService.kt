package com.airon.mobile.tools

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * WhatsApp'ta "gönder"e basan erişilebilirlik servisi.
 *
 * GÜVENLİK SINIRI — tıklamadan önce ÜÇ şeyin hepsi doğrulanıyor (Astra incelemesi,
 * 2026-09-15; önceki sürüm yalnızca metne bakıyordu ve metin kutusu okunamazsa o
 * kontrolü de atlıyordu):
 *  1. silahlı: Aıron `arm()` çağıralı ARM_WINDOW_MS'den az zaman geçti, tek kullanımlık
 *  2. alıcı: sohbet başlığı hazırlanan kişinin adını ya da numarasını gösteriyor
 *  3. metin: mesaj kutusu OKUNABİLİYOR ve hazırlanan metinle aynı
 * Biri bile doğrulanamazsa gönderilmiyor; mesaj kutuda kalıyor, kullanıcı elle
 * gönderebilir. Yanlış kişiye giden mesaj geri alınamaz, gönderilmeyen mesaj alınır.
 */
class WhatsAppSendService : AccessibilityService() {

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pending = pending ?: return
        if (SystemClock.elapsedRealtime() > pending.expiresAt) {
            disarm()
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return

        if (!recipientMatches(root, pkg, pending)) return

        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull() ?: return
        val typed = entry.text?.toString() ?: return
        if (normalize(typed) != normalize(pending.message)) return

        val send = SEND_IDS.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
        } ?: return

        // Önce silahı indir: tıklama olayı yeni bir pencere olayı üretip ikinci kez
        // tetiklemesin.
        disarm()
        if (clickSelfOrParent(send)) {
            sentToken = pending.token
            Log.i(TAG, "WhatsApp mesajı gönderildi: ${pending.contactName}")
        }
    }

    private fun recipientMatches(root: AccessibilityNodeInfo, pkg: String, pending: Pending): Boolean {
        val header = HEADER_IDS.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()?.text?.toString()
        } ?: return false
        val headerDigits = header.filter(Char::isDigit)
        if (headerDigits.length >= 10 && headerDigits.takeLast(10) == pending.contactId) return true
        return ContactTools.normalize(header) == ContactTools.normalize(pending.contactName)
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(3) {
            val n = current ?: return false
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = n.parent
        }
        return false
    }

    private data class Pending(
        val token: Long,
        val message: String,
        val contactName: String,
        val contactId: String,
        val expiresAt: Long,
    )

    companion object {
        private const val TAG = "AironWhatsApp"
        private const val ARM_WINDOW_MS = 12_000L
        private val SEND_IDS = listOf("send", "send_btn")
        private val HEADER_IDS = listOf("conversation_contact_name", "conversation_contact_status_name")

        @Volatile private var pending: Pending? = null
        @Volatile private var sentToken = 0L

        /** Silahlandırır; dönen belirteçle `awaitSent` gönderimin gerçekten olup olmadığını bekler. */
        fun arm(message: String, contactName: String, contactId: String): Long {
            val now = SystemClock.elapsedRealtime()
            pending = Pending(now, message, contactName, contactId, now + ARM_WINDOW_MS)
            return now
        }

        /** Araç iş parçacığında çağrılır (ana iş parçacığında DEĞİL). */
        fun awaitSent(token: Long, timeoutMs: Long = 9_000L): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (sentToken == token) return true
                Thread.sleep(150)
            }
            if (pending?.token == token) disarm()
            return false
        }

        fun disarm() {
            pending = null
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, WhatsAppSendService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        private fun normalize(text: String) = text.replace(Regex("\\s+"), " ").trim()
    }
}
