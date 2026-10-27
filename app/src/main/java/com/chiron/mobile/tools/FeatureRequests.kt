package com.chiron.mobile.tools

import android.content.Context
import android.os.Build
import com.chiron.mobile.AppSettings
import com.chiron.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * İstek listesi: Chiron'un yapamadığı ve kullanıcının "listeye ekle" dediği şeyler.
 *
 * İstek önce telefona yazılıyor (`requests_pending.json`), sonra PC'deki Chiron'a
 * gönderiliyor (`/api/remote/requests` → kasadaki `Notes/Istek-Listesi.md`). PC'ye
 * ulaşılamazsa telefonda bekliyor; her oturum açılışında yeniden deneniyor. Her
 * isteğin kalıcı bir kimliği var — PC tekrar gelen aynı isteği ikinci kez yazmıyor.
 */
class FeatureRequests(context: Context, settings: AppSettings) {
    // Eşitleme kısa zaman aşımıyla: PC cevap vermiyorsa oturum açılışı beklemesin.
    private val desktop = DesktopBridge(settings, readTimeoutSeconds = 12)
    private val file = File(context.applicationContext.filesDir, "requests_pending.json")
    /** Kullanıcıya söylenmiş ama PC'ye "bildirildi" diye henüz iletilemeyen numaralar. */
    private val ackFile = File(context.applicationContext.filesDir, "requests_ack_pending.json")

    fun add(args: JSONObject): JSONObject {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return fail("İsteğin başlığı boş.")
        val record = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("title", title.take(120))
            .put("said", args.optString("user_request").trim().take(300))
            .put("attempted", args.optString("attempted").trim().take(300))
            .put("detail", args.optString("detail").take(1200))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, ${BuildConfig.VERSION_NAME}")
            .put("at", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()))
        synchronized(LOCK) {
            val pending = read(file)
            pending.put(record)
            write(file, pending)
        }
        val sent = flush()
        return if (sent.containsKey(record.getString("id"))) {
            ok("İstek listeye eklendi (${sent[record.getString("id")]}).")
        } else {
            ok("İstek telefona kaydedildi; PC'ye şu an ulaşılamadı, PC açılınca kendiliğinden gönderilecek.")
        }
    }

    /** PC'deki istek listesini ve PC'ye henüz gitmemiş yerel istek başlıklarını döner. */
    fun list(): JSONObject {
        try {
            flush()
        } catch (_: Exception) {
            // Ağ hatasında yerel bekleyenleri yine de döndür.
        }
        val pendingOnPhone = synchronized(LOCK) {
            val pending = read(file)
            JSONArray().apply {
                for (i in 0 until pending.length()) {
                    val title = pending.optJSONObject(i)?.optString("title").orEmpty()
                    if (title.isNotBlank()) put(title)
                }
            }
        }
        val response = try {
            desktop.get("/api/remote/requests/list")
        } catch (_: Exception) {
            null
        }
        if (response == null || !response.optBoolean("success")) {
            return ok("PC'ye ulaşılamadı; yalnız telefonda bekleyenler")
                .put("pending_on_phone", pendingOnPhone)
        }
        val items = response.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        return ok("İstek listesi alındı.")
            .put("items", items)
            .put("pending_on_phone", pendingOnPhone)
    }

    fun remove(args: JSONObject): JSONObject {
        val number = args.optString("number").trim()
        if (number.isEmpty()) {
            val title = args.optString("title").trim()
            if (title.isEmpty()) return fail("İstek numarası boş.")
            return removePending(title)
        }

        val response = desktop.post(
            "/api/remote/requests/remove",
            JSONObject().put("number", number),
        )
        return if (!response.optBoolean("success") && response.optString("message").isBlank()) {
            fail("İstek kaldırılamadı; PC'den açıklama alınamadı.")
        } else {
            response
        }
    }

    private fun removePending(title: String): JSONObject = synchronized(LOCK) {
        val pending = read(file)
        val exactIndex = (0 until pending.length()).firstOrNull { index ->
            pending.optJSONObject(index)?.optString("title")?.trim() == title
        }
        val normalizedTitle = ContactTools.normalize(title)
        val matchIndex = exactIndex ?: (0 until pending.length()).firstOrNull { index ->
            normalizedTitle.isNotEmpty() &&
                ContactTools.normalize(pending.optJSONObject(index)?.optString("title").orEmpty()) == normalizedTitle
        }
        if (matchIndex == null) return@synchronized fail("Telefonda bekleyen bu başlıkta bir istek bulunamadı.")

        val removed = pending.getJSONObject(matchIndex)
        val left = JSONArray()
        for (i in 0 until pending.length()) {
            if (i != matchIndex) left.put(pending.getJSONObject(i))
        }
        write(file, left)
        ok(
            "İstek telefondaki bekleyen listeden kaldırıldı.",
            "data" to JSONObject().put("title", removed.optString("title")),
        )
    }

    /** Bekleyenleri PC'ye gönderir. Gönderilenlerin kimlik → numara eşlemesini döner. */
    fun flush(): Map<String, String> = synchronized(LOCK) {
        retryAcks()
        val pending = read(file)
        if (pending.length() == 0) return emptyMap()
        val sent = LinkedHashMap<String, String>()
        val left = JSONArray()
        var reachable = true
        for (i in 0 until pending.length()) {
            val record = pending.getJSONObject(i)
            // İlk başarısızlıkta dur: PC'ye ulaşılamıyorsa her kayıt için ayrı zaman aşımı bekleme.
            if (!reachable || i >= MAX_PER_FLUSH) {
                left.put(record)
                continue
            }
            val response = desktop.post("/api/remote/requests", record)
            if (response.optBoolean("success")) {
                sent[record.getString("id")] = response.optJSONObject("data")?.optString("number").orEmpty()
            } else {
                left.put(record)
                reachable = false
            }
        }
        write(file, left)
        sent
    }

    /** PC'de "yapıldı" olup kullanıcıya henüz söylenmemiş istekler. Ulaşılamazsa boş. */
    fun doneItems(): List<Pair<String, String>> {
        val response = desktop.get("/api/remote/requests/done")
        if (!response.optBoolean("success")) return emptyList()
        val items = response.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        // Söylenmiş ama onayı PC'ye henüz ulaşmamış olanları tekrar söyleme.
        val told = synchronized(LOCK) { strings(read(ackFile)) }
        return (0 until items.length()).map { items.getJSONObject(it) }
            .map { it.optString("number") to it.optString("title") }
            .filter { it.first.isNotBlank() && it.first !in told }
    }

    /** Kullanıcıya söylendi: kalıcı olarak işaretle, PC'ye iletmeyi dene (olmazsa sonraki eşitlemede). */
    fun acknowledge(numbers: List<String>) {
        if (numbers.isEmpty()) return
        synchronized(LOCK) {
            val all = (strings(read(ackFile)) + numbers).distinct()
            write(ackFile, JSONArray(all))
        }
        retryAcks()
    }

    private fun retryAcks() = synchronized(LOCK) {
        val numbers = strings(read(ackFile))
        if (numbers.isEmpty()) return
        val response = desktop.post("/api/remote/requests/ack", JSONObject().put("numbers", JSONArray(numbers)))
        if (response.optBoolean("success")) write(ackFile, JSONArray())
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

    private fun read(target: File): JSONArray = runCatching { JSONArray(target.readText()) }.getOrElse { JSONArray() }

    private fun write(target: File, array: JSONArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(array.toString())
            tmp.delete()
        }
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_PER_FLUSH = 10
    }
}
