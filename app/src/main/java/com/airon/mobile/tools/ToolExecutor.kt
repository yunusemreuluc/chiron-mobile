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
    /** Servis de kullanıyor: oturum açılışında bekleyenleri gönderip "yapıldı" haberlerini soruyor. */
    val requests = FeatureRequests(app, settings)

    /** Ağ ya da bekleme içerebilir — ana iş parçacığında ÇAĞIRMA. */
    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "call_contact" -> contacts.callContact(args)
            "send_message" -> contacts.sendMessage(args)
            "control_device" -> device.controlDevice(args)
            "manage_device_audio" -> device.manageAudio(args)
            "get_recent_notifications" -> AironNotificationListener.recent(app, args)
            "get_device_health" -> device.deviceHealth()
            "query_desktop_airon" -> desktop.ask(args)
            "send_desktop_command" -> desktop.command(args)
            "save_quick_note" -> saveNote(args)
            "start_navigation" -> navigation.navigate(args)
            "add_feature_request" -> requests.add(args)
            "list_feature_requests" -> requests.list()
            else -> fail("Bilinmeyen araç: $name")
        }
    } catch (e: Exception) {
        Log.e("AironTools", "$name başarısız", e)
        fail("$name çalışırken hata: ${e.message ?: e.javaClass.simpleName}")
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
