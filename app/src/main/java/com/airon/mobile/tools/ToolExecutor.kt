package com.airon.mobile.tools

import android.content.Context
import android.util.Log
import com.airon.mobile.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Araç çağrısını ilgili uygulamaya yönlendirir. Hiçbir araç hatası oturumu
 * düşürmemeli: istisna da `success=false` JSON'u olarak modele dönüyor, model
 * kullanıcıya ne olduğunu söylüyor.
 */
class ToolExecutor(context: Context, settings: AppSettings) {
    private val app = context.applicationContext
    private val contacts = ContactTools(app)
    private val device = DeviceTools(app)
    private val desktop = DesktopBridge(settings)
    private val navigation = NavigationTools(app, settings)
    /** Servis de kullanıyor: tamamlanan PC işlerini oturum açılışında sesli duyuruyor. */
    val claude = ClaudeJobs(app, settings)
    /** Servis de kullanıyor: oturum açılışında bekleyenleri gönderip "yapıldı" haberlerini soruyor. */
    val requests = FeatureRequests(app, settings)

    /** Ağ ya da bekleme içerebilir — ana iş parçacığında ÇAĞIRMA. */
    fun execute(name: String, args: JSONObject): JSONObject = run(name, args).also { RecentCalls.record(name, args, it) }

    private fun run(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "call_contact" -> contacts.callContact(args)
            "send_message" -> contacts.sendMessage(args)
            "control_device" -> device.controlDevice(args)
            "manage_device_audio" -> device.manageAudio(args)
            "get_recent_notifications" -> AironNotificationListener.recent(app, args)
            "get_device_health" -> device.deviceHealth()
            "query_desktop_airon" -> desktop.ask(args)
            "send_desktop_command" -> desktop.command(args)
            "delegate_to_claude" -> claude.delegate(args)
            "claude_job_status" -> claude.status(args)
            "cancel_claude_job" -> claude.cancel(args)
            "save_quick_note" -> saveNote(args)
            "start_navigation" -> navigation.navigate(args)
            "add_feature_request" -> requests.add(args)
            "report_bug" -> reportBug(args)
            "get_pc_status" -> desktop.get("/api/remote/status")
            "list_feature_requests" -> requests.list()
            "remove_feature_request" -> requests.remove(args)
            else -> fail("Bilinmeyen araç: $name")
        }
    } catch (e: Exception) {
        Log.e("AironTools", "$name başarısız", e)
        fail("$name çalışırken hata: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * İ-007: hata bildirimi. İstek listesine "Hata:" başlığıyla düşer ve son araç
     * çağrılarının ÖZETİNİ taşır (araç adı, eylem, başarı, mesajın başı) — hatayı ayıklarken
     * "o an ne denendi" görünsün. Kişi adı / mesaj metni gibi argümanlar EKLENMEZ (kasa notu).
     */
    private fun reportBug(args: JSONObject): JSONObject {
        val title = args.optString("title").trim().removePrefix("Hata:").trim()
        if (title.isEmpty()) return fail("Hatanın kısa başlığı boş.")
        val detail = buildString {
            append(args.optString("description").trim().take(700))
            val recent = RecentCalls.summary()
            if (recent.isNotEmpty()) append("\nSon araç çağrıları: ").append(recent)
        }
        return requests.add(
            JSONObject()
                .put("title", "Hata: $title")
                .put("user_request", args.optString("description").trim().take(300))
                .put("attempted", "hata bildirimi (report_bug)")
                .put("detail", detail),
        )
    }

    /** Not önce telefona yazılıyor — PC'ye ulaşılamasa da not kaybolmasın. */
    private fun saveNote(args: JSONObject): JSONObject {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return fail("Not boş.")
        val file = File(app.filesDir, "notes.json")
        val notes = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("tr")).format(Date())
        notes.put(JSONObject().put("text", text).put("at", stamp))
        file.writeText(notes.toString())

        val pc = desktop.note(text)
        return if (pc.optBoolean("success")) {
            ok("Not telefona ve PC'ye kaydedildi.")
        } else {
            ok("Not telefona kaydedildi; PC'ye yazılamadı (${pc.optString("message")}).")
        }
    }
}

/** Son araç çağrılarının kişisel içerik taşımayan özeti — hata bildirimine eklenir. */
internal object RecentCalls {
    private const val KEEP = 6
    private val items = ArrayDeque<String>()

    @Synchronized
    fun record(name: String, args: JSONObject, result: JSONObject) {
        if (name == "report_bug") return
        val stamp = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
        // Yalnız sabit küme değerli "action" alanı; serbest metin argümanları (kişi, mesaj) yazılmaz.
        val action = args.optString("action").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val ok = if (result.optBoolean("success")) "başarılı" else "BAŞARISIZ"
        val message = result.optString("message").take(80)
        items.addLast("$stamp $name$action → $ok ($message)")
        while (items.size > KEEP) items.removeFirst()
    }

    @Synchronized
    fun summary(): String = items.joinToString(" | ")
}
