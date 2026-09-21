package com.airon.mobile.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import com.airon.mobile.audio.AudioIO
import com.airon.mobile.tools.ContactMatcher.Contact
import com.airon.mobile.tools.ContactMatcher.Resolution

/** Arama ve mesaj. Hangi kişinin kastedildiği `ContactMatcher`'da. */
class ContactTools(private val context: Context) {

    fun callContact(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (!granted(Manifest.permission.READ_CONTACTS)) return fail("Rehber izni verilmemiş.")
        if (!granted(Manifest.permission.CALL_PHONE)) return fail("Arama izni verilmemiş.")

        val contact = when (val match = ContactMatcher.resolve(loadContacts(), name, args.optString("contact_id"))) {
            null -> return fail("Rehberde '$name' bulunamadı.")
            is Resolution.One -> match.contact
            else -> return choice(name, match)
        }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.number}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok("${contact.name} aranıyor.", "contact" to contact.name)
    }

    /**
     * İki aşamalı (istek İ-002, "göndermeden önce düzeltebileyim"): `draft_id` olmadan
     * gelen çağrı GÖNDERMİYOR, taslak dönüyor — Aıron alıcıyı ve metni okuyup onay
     * istiyor, kullanıcı düzeltme derse yeni metinle yeni taslak alınıyor. Gönderim
     * yalnızca SON taslağın kimliğiyle ve taslakla birebir aynı kişi/kanal/metinle:
     * model okunmamış ya da okunduktan sonra değişmiş bir metni gönderemiyor.
     */
    fun sendMessage(args: JSONObject): JSONObject {
        val recipient = args.optString("recipient").trim()
        val message = args.optString("message").trim()
        val channel = if (args.optString("channel").trim().lowercase() == "sms") "sms" else "whatsapp"
        if (message.isEmpty()) return fail("Mesaj metni boş.")
        if (!granted(Manifest.permission.READ_CONTACTS)) return fail("Rehber izni verilmemiş.")

        val contact = when (val match = ContactMatcher.resolve(loadContacts(), recipient, args.optString("contact_id"))) {
            null -> return fail("Rehberde '$recipient' bulunamadı.")
            is Resolution.One -> match.contact
            else -> return choice(recipient, match)
        }

        val draftId = args.optString("draft_id").trim()
        if (draftId.isEmpty()) return draft(contact, channel, message)
        val approved = MessageDrafts.take(draftId)
            ?: return fail("Bu taslak artık geçerli değil (süresi doldu ya da yenisi hazırlandı). Mesajı draft_id olmadan yeniden hazırla ve kullanıcıya oku.")
        // Taslak hazırlandıktan sonra kullanıcının sesi HİÇ duyulmadıysa onay da yok:
        // model taslağı okumadan arka arkaya iki çağrıyla gönderemesin (Astra, kritik).
        if (AudioIO.lastUserVoiceAt <= approved.createdAt) {
            return fail("Kullanıcı onayı duyulmadı (taslak okunduktan sonra kullanıcı konuşmadı); gönderilmedi. Metni kullanıcıya oku, onayını bekle, sonra yeni taslakla gönder.")
        }
        if (approved.number != contact.number || approved.channel != channel || approved.message != message) {
            return fail("Onaylanan taslakla alıcı, kanal ya da metin aynı değil; gönderilmedi. Yeni hâlini draft_id olmadan hazırla ve kullanıcıya oku.")
        }
        return if (channel == "sms") sendSms(contact, message) else sendWhatsApp(contact, message)
    }

    private fun draft(contact: Contact, channel: String, message: String): JSONObject {
        val via = if (channel == "sms") "SMS" else "WhatsApp"
        return JSONObject()
            .put("success", true)
            .put("sent", false)
            .put("needs_confirmation", true)
            .put("draft_id", MessageDrafts.put(contact.number, channel, message))
            .put("contact", contact.name)
            .put("channel", channel)
            .put("text", message)
            .put("message", "Henüz GÖNDERİLMEDİ. Kullanıcıya alıcıyı ($via, ${contact.name}) ve metni aynen oku, 'Göndereyim mi?' diye sor.")
    }

    private fun sendSms(contact: Contact, message: String): JSONObject {
        if (!granted(Manifest.permission.SEND_SMS)) return fail("SMS izni verilmemiş.")
        val sms = context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(message), null, null)
        return ok("${contact.name} kişisine SMS gönderildi.", "contact" to contact.name)
    }

    private fun sendWhatsApp(contact: Contact, message: String): JSONObject {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull(::installed)
            ?: return fail("WhatsApp yüklü değil.")
        val phone = internationalDigits(contact.number)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${URLEncoder.encode(message, "UTF-8")}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        val autoSend = WhatsAppSendService.isEnabled(context)
        val token = if (autoSend) WhatsAppSendService.arm(message, contact.name, contact.id) else 0L
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            WhatsAppSendService.disarm()
            return fail("WhatsApp açılamadı: ${e.message}")
        }

        if (!autoSend) {
            return JSONObject().put("success", true).put("sent", false)
                .put("message", "Mesaj ${contact.name} için WhatsApp'ta yazıldı ama otomatik gönderme izni kapalı; kullanıcı gönder'e basmalı.")
        }
        // "Gönderildi" yalnızca servis gerçekten tıkladıysa söyleniyor.
        return if (WhatsAppSendService.awaitSent(token)) {
            ok("${contact.name} kişisine WhatsApp mesajı gönderildi.", "contact" to contact.name, "sent" to true)
        } else {
            JSONObject().put("success", false).put("sent", false)
                .put("message", "Mesaj ${contact.name} için WhatsApp'ta yazıldı ama alıcı ya da metin doğrulanamadığı için otomatik gönderilmedi; kullanıcı kontrol edip gönder'e basmalı.")
        }
    }

    private fun loadContacts(): List<Contact> {
        val result = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                result.add(Contact(name, number))
            }
        }
        return result
    }

    private fun choice(spoken: String, match: Resolution): JSONObject {
        val (candidates, message) = when (match) {
            is Resolution.Weak -> match.candidates to
                "Rehberde tam '$spoken' yok, yalnızca benzer adlar var. Kullanıcıya bunlardan birini mi kastettiğini sor; " +
                "onaylarsa aynı aracı o adayın contact_id'siyle tekrar çağır. Onaysız çağırma."
            is Resolution.Many -> match.candidates to
                "'$spoken' birden fazla kişiye ya da numaraya uyuyor. Adayları (gerekirse numara sonuyla) say, " +
                "kullanıcıya hangisi olduğunu sor; seçince aynı aracı o adayın contact_id'siyle tekrar çağır."
            is Resolution.One -> listOf(match.contact) to ""
        }
        val list = JSONArray()
        candidates.forEach {
            list.put(JSONObject().put("contact_id", it.id).put("name", it.name).put("number_ending", it.id.takeLast(4)))
        }
        return JSONObject().put("success", false).put("needs_choice", true).put("message", message).put("candidates", list)
    }

    private fun internationalDigits(number: String): String {
        val digits = number.filter(Char::isDigit)
        return when {
            number.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") && digits.length == 11 -> "90" + digits.drop(1) // 0555… → 90555…
            digits.length == 10 && digits.startsWith("5") -> "90$digits"
            else -> digits
        }
    }

    private fun installed(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun normalize(text: String) = ContactMatcher.normalize(text)
    }
}

/** Onay bekleyen tek mesaj taslağı; yenisi eskisini geçersiz kılıyor. */
internal object MessageDrafts {
    class Draft(
        val id: String,
        val number: String,
        val channel: String,
        val message: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    /** Arabada okuma + düşünme + düzeltme turu için yeterli, unutulmuş taslak için kısa. */
    private const val TTL_MS = 3 * 60_000L
    private var current: Draft? = null

    @Synchronized
    fun put(number: String, channel: String, message: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val now = SystemClock.elapsedRealtime()
        current = Draft(id, number, channel, message, now, now + TTL_MS)
        return id
    }

    /** Oturum kapanınca bekleyen taslak düşer: sonraki oturumda eski metin gönderilemesin. */
    @Synchronized
    fun clear() {
        current = null
    }

    /** Tek kullanımlık: kimlik tutsa da tutmasa da taslak düşüyor, yanlış denemeden sonra yeniden okunmalı. */
    @Synchronized
    fun take(id: String): Draft? {
        val draft = current
        current = null
        return draft?.takeIf { it.id == id && SystemClock.elapsedRealtime() < it.expiresAt }
    }
}

internal fun ok(message: String, vararg extra: Pair<String, Any>) = JSONObject().apply {
    put("success", true)
    put("message", message)
    extra.forEach { (k, v) -> put(k, v) }
}

internal fun fail(message: String) = JSONObject().put("success", false).put("message", message)
