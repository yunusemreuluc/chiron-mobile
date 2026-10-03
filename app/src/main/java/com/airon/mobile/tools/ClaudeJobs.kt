package com.airon.mobile.tools

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import com.airon.mobile.AppSettings
import com.airon.mobile.audio.AudioIO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PC'de çalışan Claude Code iş kuyruğunun telefon tarafındaki köprüsü.
 *
 * GÜVENLİK (Astra, kritik): PC'deki Claude TAM YETKİLİ. Model bir iş ya da onay
 * gönderebiliyorsa, gelen bir WhatsApp mesajı ya da Claude'un kendi özeti modeli
 * yönlendirip kullanıcının hiç söylemediği bir işi PC'ye yaptırabilir. Bu yüzden
 * `send_message`'taki kapının aynısı (bkz. ContactTools / MessageDrafts):
 *  - Yeni iş İKİ AŞAMALI: `draft_id`'siz çağrı göndermez, taslak döner; gönderim
 *    yalnızca taslakla birebir aynı görevle ve taslak okunduktan SONRA kullanıcının
 *    sesi duyulduysa.
 *  - Onay cevabı (`resume_job_id`, iş `needs_approval`): Claude'un sorusu kullanıcıya
 *    okunduğu an `ClaudeDrafts.presentApproval` ile kaydedilir; ondan sonra kullanıcı
 *    konuşmadıysa devam gönderilmez.
 * Ses kapısı "evet" dendiğini kanıtlamaz ama aynı turda sessiz, kullanıcısız bir
 * çağrıyı kesin olarak engeller — mesaj göndermede kabul edilen eşik bu.
 */
class ClaudeJobs(context: Context, settings: AppSettings) {
    private val app = context.applicationContext
    // İşin kendisi arka planda sürdüğü için telefon yalnızca kısa API cevaplarını bekler.
    private val desktop = DesktopBridge(settings, readTimeoutSeconds = 15)
    private val ackFile = File(app.filesDir, "claude_ack_pending.json")
    /** Sesli söylenmiş son işler — ack PC'ye gidip dosyadan düşse de bildirim geri gelmesin. */
    private val toldFile = File(app.filesDir, "claude_told.json")

    fun delegate(args: JSONObject): JSONObject {
        val task = args.optString("task").trim()
        if (task.isEmpty()) return fail("Claude'a verilecek iş boş.")
        if (task.length > MAX_TASK_LENGTH) return fail("Claude işi en fazla $MAX_TASK_LENGTH karakter olabilir.")
        val resumeId = args.optString("resume_job_id").trim()
        val draftId = args.optString("draft_id").trim()

        val decision = args.optString("decision").trim().lowercase()
        var sendTask = task
        when {
            // Okunmuş onay sorusuna cevap: taslak gerekmez, ses kapısı yeter. Ama PC'ye
            // gidecek metni MODEL değil uygulama yazar — yoksa onay kaydı varken
            // resume ile bambaşka bir iş taslaksız gönderilebiliyordu (Astra, kritik).
            resumeId.isNotEmpty() && decision in DECISIONS && ClaudeDrafts.isPresentedApproval(resumeId) -> {
                val presentedAt = ClaudeDrafts.takeApproval(resumeId)
                    ?: return fail("Bu onay sorusu artık geçerli değil; işin durumuna claude_job_status ile bak.")
                if (AudioIO.lastUserVoiceAt <= presentedAt) {
                    return fail(
                        "Kullanıcının cevabı duyulmadı (onay sorusu okunduktan sonra kullanıcı konuşmadı); PC'ye gönderilmedi. " +
                            "Claude'un ne yapmak istediğini kullanıcıya oku ve cevabını bekle.",
                    )
                }
                sendTask = if (decision == "approve") APPROVE_TEXT else REJECT_TEXT
            }
            draftId.isEmpty() -> return draft(task, resumeId)
            else -> {
                val approved = ClaudeDrafts.take(draftId)
                    ?: return fail("Bu taslak artık geçerli değil (süresi doldu ya da yenisi hazırlandı). İşi draft_id olmadan yeniden hazırla ve kullanıcıya oku.")
                if (AudioIO.lastUserVoiceAt <= approved.createdAt) {
                    return fail("Kullanıcı onayı duyulmadı (taslak okunduktan sonra kullanıcı konuşmadı); gönderilmedi. Görevi kullanıcıya oku, onayını bekle, sonra yeni taslakla gönder.")
                }
                if (approved.task != task || approved.resumeOf != resumeId) {
                    return fail("Onaylanan taslakla görev aynı değil; gönderilmedi. Yeni hâlini draft_id olmadan hazırla ve kullanıcıya oku.")
                }
            }
        }

        val body = JSONObject()
            .put("task", sendTask)
            .put("resume_of", resumeId.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        val response = desktop.post("/api/remote/claude", body)
        if (!response.optBoolean("success")) return response

        val job = response.optJSONObject("data") ?: JSONObject()
        ClaudeWatchWorker.schedule(app)
        return ok(
            "İş PC'deki Claude'a verildi; bitince haber vereceğim.",
            "job_id" to job.optString("id"),
            "status" to job.optString("status"),
            "queue_position" to if (job.has("queue_position") && !job.isNull("queue_position")) {
                job.optInt("queue_position")
            } else {
                JSONObject.NULL
            },
        )
    }

    private fun draft(task: String, resumeId: String): JSONObject = JSONObject()
        .put("success", true)
        .put("sent", false)
        .put("needs_confirmation", true)
        .put("draft_id", ClaudeDrafts.put(task, resumeId))
        .put("task", task)
        .put(
            "message",
            "Henüz VERİLMEDİ. Kullanıcıya Claude'a ne verileceğini kısaca oku ve 'Claude'a vereyim mi?' diye sor. " +
                "Onaylarsa aynı task ve bu draft_id ile tekrar çağır.",
        )

    fun status(args: JSONObject): JSONObject {
        val jobId = args.optString("job_id").trim()
        if (jobId.isNotEmpty()) {
            val response = desktop.get("/api/remote/claude/$jobId")
            if (!response.optBoolean("success")) return response
            val job = response.optJSONObject("data") ?: return fail("Claude işi cevabında iş bilgisi yok.")
            return ok("Claude işinin durumu alındı.", "job" to summarized(job, includeDetails = true))
        }

        val response = desktop.get("/api/remote/claude")
        if (!response.optBoolean("success")) return response
        val jobs = response.optJSONObject("data")?.optJSONArray("jobs") ?: JSONArray()
        val firstThree = JSONArray()
        for (i in 0 until minOf(3, jobs.length())) {
            jobs.optJSONObject(i)?.let { firstThree.put(summarized(it, includeDetails = false)) }
        }
        return ok("Claude'un son işleri alındı.", "jobs" to firstThree)
    }

    fun cancel(args: JSONObject): JSONObject {
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return fail("Durdurulacak Claude işinin job_id değeri boş.")
        val response = desktop.post("/api/remote/claude/$jobId/cancel", JSONObject())
        if (!response.optBoolean("success")) return response
        return ok(response.optString("message").ifBlank { "Claude işi durduruldu." }, "job_id" to jobId)
    }

    /**
     * Ekrandaki canlı şerit için: süren (ya da sıradaki) Claude işinin tek satırı, yoksa null.
     * Ağ hatasında da null — şerit yanlış bir "çalışıyor" göstermesin.
     */
    fun activeLine(): String? {
        val response = desktop.get("/api/remote/claude")
        if (!response.optBoolean("success")) return null
        val jobs = response.optJSONObject("data")?.optJSONArray("jobs") ?: return null
        val job = (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
            .firstOrNull { it.optString("status") == "running" }
            ?: (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
                .firstOrNull { it.optString("status") == "queued" }
            ?: return null
        if (job.optString("status") == "queued") return "Claude · sırada"
        val step = job.optString("last_step").ifBlank { "başlıyor" }.take(90)
        val minutes = job.optInt("elapsed_seconds") / 60
        return "Claude · $step" + if (minutes > 0) " · $minutes dk" else ""
    }

    /** Kullanıcıya henüz söylenmemiş sonuçlar. Ağ hatası konuşma oturumunu bozmaz. */
    fun updates(): List<JSONObject> = fetchUpdates().jobs

    /** Worker'ın geçici hatada yoklamayı bırakmaması için erişim ve süren iş bilgisini de taşır. */
    internal fun fetchUpdates(): UpdateBatch = synchronized(ACK_LOCK) {
        retryAcksLocked()
        val response = desktop.get("/api/remote/claude/updates")
        if (!response.optBoolean("success")) return@synchronized UpdateBatch(reachable = false, active = 0, jobs = emptyList())
        val data = response.optJSONObject("data")
        // PC sürülen işleri liste sınırından bağımsız sayıyor (Astra: "son 10" listesinden
        // çıkarım uzun işi kaçırıyordu).
        val active = data?.optInt("active", 0) ?: 0
        val jobs = data?.optJSONArray("jobs")
            ?: return@synchronized UpdateBatch(reachable = true, active = active, jobs = emptyList())
        // PC'ye ack ulaşamadıysa daha önce söylenen sonucu aynı telefonda yeniden okutma.
        val alreadyTold = strings(readAckFile()).toSet() + strings(read(toldFile))
        val unseen = (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }
            .filter { it.optString("id").isNotBlank() && it.optString("id") !in alreadyTold }
        UpdateBatch(reachable = true, active = active, jobs = unseen)
    }

    /** Söylenen kimlikleri önce kalıcılaştırır; ağ yoksa sonraki updates/ack çağrısı yeniden dener. */
    fun acknowledge(ids: List<String>) = synchronized(ACK_LOCK) {
        val clean = ids.map(String::trim).filter(String::isNotEmpty)
        if (clean.isEmpty()) return@synchronized
        writeAckFile((strings(readAckFile()) + clean).distinct())
        writeAtomic(toldFile, (strings(read(toldFile)) + clean).distinct().takeLast(TOLD_KEEP))
        // Sesli söylendi: "onay bekliyor / bitti" bildirimi artık yanıltıcı.
        val notifications = app.getSystemService(NotificationManager::class.java)
        clean.forEach { runCatching { notifications.cancel(ClaudeWatchWorker.notificationId(it)) } }
        retryAcksLocked()
    }

    /** PC şeması en fazla 50 kimlik kabul ediyor: partiler hâlinde, başarılı parti dosyadan düşer. */
    private fun retryAcksLocked() {
        var pending = strings(readAckFile())
        while (pending.isNotEmpty()) {
            val batch = pending.take(ACK_BATCH)
            val response = desktop.post("/api/remote/claude/ack", JSONObject().put("ids", JSONArray(batch)))
            if (!response.optBoolean("success")) return
            pending = pending.drop(batch.size)
            writeAckFile(pending)
        }
    }

    private fun summarized(job: JSONObject, includeDetails: Boolean): JSONObject = JSONObject()
        .put("id", job.optString("id"))
        .put("status", job.optString("status"))
        .put("queue_position", job.opt("queue_position"))
        .put("last_step", job.optString("last_step"))
        .put(
            "summary",
            job.optString("summary").let { if (includeDetails) it else it.take(STATUS_SUMMARY_LIMIT) },
        )
        .put("approval", job.optString("approval"))
        .apply {
            if (includeDetails) {
                put("steps", job.opt("steps"))
                put("error", job.optString("error"))
                put("elapsed_seconds", job.opt("elapsed_seconds"))
                put("resume_of", job.opt("resume_of"))
                put("cost_usd", job.opt("cost_usd"))
            }
        }

    /**
     * Bildirimi yalnız iş henüz sesli söylenmediyse gösterir. Ack ile AYNI kilit altında:
     * worker sonucu çektikten sonra servis ack verip bildirimi kaldırırsa, worker eski
     * sonucu yeniden bildirmesin (Astra).
     */
    internal fun unlessTold(jobId: String, show: () -> Boolean): Boolean = synchronized(ACK_LOCK) {
        if (jobId in strings(readAckFile()) || jobId in strings(read(toldFile))) return@synchronized false
        show()
    }

    private fun readAckFile(): JSONArray = read(ackFile)

    private fun read(file: File): JSONArray =
        runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }

    private fun writeAckFile(ids: List<String>) = writeAtomic(ackFile, ids)

    /** Atomik: önce geçici dosya, sonra yerine taşıma. Süreç arada ölse eski liste kalır. */
    private fun writeAtomic(file: File, ids: List<String>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(ids).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun strings(array: JSONArray) =
        (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }

    companion object {
        private val ACK_LOCK = Any()
        private const val MAX_TASK_LENGTH = 4000
        private const val STATUS_SUMMARY_LIMIT = 400
        private const val ACK_BATCH = 50
        private const val TOLD_KEEP = 200
        private val DECISIONS = setOf("approve", "reject")
        private const val APPROVE_TEXT = "Onaylıyorum, devam et."
        private const val REJECT_TEXT = "Onaylamıyorum, o adımı yapma. Yapmadan bitir ve durumu özetle."
    }

    internal data class UpdateBatch(val reachable: Boolean, val active: Int, val jobs: List<JSONObject>)
}

/**
 * Onay bekleyen tek Claude iş taslağı ve kullanıcıya okunmuş onay soruları.
 * Oturum kapanınca hepsi düşer (AssistantService.stopSession).
 */
internal object ClaudeDrafts {
    class Draft(val id: String, val task: String, val resumeOf: String, val createdAt: Long, val expiresAt: Long)

    private const val TTL_MS = 3 * 60_000L
    private const val APPROVAL_TTL_MS = 15 * 60_000L
    private var current: Draft? = null
    /** job_id → onay sorusunun modele verildiği an (elapsedRealtime). */
    private val approvals = HashMap<String, Long>()

    @Synchronized
    fun put(task: String, resumeOf: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val now = SystemClock.elapsedRealtime()
        current = Draft(id, task, resumeOf, now, now + TTL_MS)
        return id
    }

    /** Tek kullanımlık: kimlik tutsa da tutmasa da taslak düşer. */
    @Synchronized
    fun take(id: String): Draft? {
        val draft = current
        current = null
        return draft?.takeIf { it.id == id && SystemClock.elapsedRealtime() < it.expiresAt }
    }

    @Synchronized
    fun presentApproval(jobId: String, at: Long = SystemClock.elapsedRealtime()) {
        approvals[jobId] = at
    }

    @Synchronized
    fun isPresentedApproval(jobId: String) = approvals.containsKey(jobId)

    /** Cevap bekleyen (süresi dolmamış) bir onay sorusu var mı — varken ikincisi sorulmaz. */
    @Synchronized
    fun hasOpenApproval(): Boolean {
        val now = SystemClock.elapsedRealtime()
        approvals.entries.removeAll { now - it.value >= APPROVAL_TTL_MS }
        return approvals.isNotEmpty()
    }

    /** Tek kullanımlık: onay ya da red bir kez gönderilir. */
    @Synchronized
    fun takeApproval(jobId: String): Long? {
        val at = approvals.remove(jobId) ?: return null
        return at.takeIf { SystemClock.elapsedRealtime() - it < APPROVAL_TTL_MS }
    }

    @Synchronized
    fun clear() {
        current = null
        approvals.clear()
    }
}
