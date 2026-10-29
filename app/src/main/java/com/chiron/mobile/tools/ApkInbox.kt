package com.chiron.mobile.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chiron.mobile.AppSettings
import com.chiron.mobile.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * PC'nin telefona gönderdiği APK'lar (2026-10-29, PC tarafı backend/core/apk_teslim.py).
 *
 * Neden: kablosuz adb yalnız Wi-Fi'da açık; Yunus işteyken Chiron Dilcep'i derler ama kuramazdı. Burada telefon
 * mevcut Tailscale + PIN bağlantısıyla indirir, PC'nin verdiği sha256 ile doğrular ve "Yükle" bildirimi gösterir.
 * Kurulumun son dokunuşu Android gereği kullanıcıda; imzası tutmayan güncellemeyi Android zaten reddeder.
 */
object ApkInbox {
    data class Item(val id: String, val name: String, val note: String, val size: Long, val sha256: String, val created: Double)

    private const val PREFS = "apk_inbox"
    private const val KEY_SEEN = "seen"
    private const val CHANNEL = "chiron_apk"
    private const val MAX_SEEN = 30
    const val ACTION_INSTALL = "com.chiron.mobile.INSTALL_APK"
    const val EXTRA_ID = "apk_id"

    fun list(context: Context): List<Item>? {
        val response = DesktopBridge(AppSettings(context), readTimeoutSeconds = 15).get("/api/remote/apk")
        if (!response.optBoolean("success")) return null
        val array = response.optJSONObject("data")?.optJSONArray("apks") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parse) }
    }

    private fun parse(o: JSONObject): Item? {
        val id = o.optString("id")
        // Kimlik dosya adına girecek: PC'nin ürettiği biçim dışında hiçbir şey kabul edilmez.
        if (!id.matches(Regex("[0-9a-f]{6,32}"))) return null
        return Item(id, o.optString("name").take(40), o.optString("note").take(300), o.optLong("size"), o.optString("sha256"), o.optDouble("created"))
    }

    fun file(context: Context, id: String) = File(File(context.cacheDir, "apk"), "$id.apk")

    /** İndirilmiş ve doğrulanmış dosya; yoksa indirir. Hata mesajı ya da null (başarılı). */
    fun ensureDownloaded(context: Context, item: Item): String? {
        val target = file(context, item.id)
        if (target.isFile && sha256(target) == item.sha256) return null
        target.parentFile?.mkdirs()
        // Eski APK'lar önbellekte birikmesin (her biri onlarca MB).
        target.parentFile?.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        val tmp = File(target.path + ".part")
        val error = DesktopBridge(AppSettings(context), readTimeoutSeconds = 60).download("/api/remote/apk/${item.id}/indir", tmp)
        if (error != null) return error
        if (sha256(tmp) != item.sha256) {
            tmp.delete()
            return "İndirilen APK bozuk (doğrulama tutmadı); tekrar denenecek."
        }
        if (!tmp.renameTo(target)) return "APK kaydedilemedi."
        return null
    }

    /**
     * Worker'dan: en yeni, henüz haber verilmemiş APK'yı indirip bildirim gösterir. Eskiler "görüldü" sayılır —
     * aynı uygulamanın üç sürümü için üç bildirim anlamsız.
     */
    fun check(context: Context) {
        val items = list(context) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = strings(prefs.getString(KEY_SEEN, null))
        val newest = items.firstOrNull() ?: return
        if (newest.id in seen) return
        if (ensureDownloaded(context, newest) != null) return // sonraki turda yeniden denenir
        if (!notify(context, newest)) return
        val all = (seen + items.map { it.id }).distinct().takeLast(MAX_SEEN)
        prefs.edit().putString(KEY_SEEN, JSONArray(all).toString()).apply()
    }

    private fun notify(context: Context, item: Item): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Uygulama güncellemeleri", NotificationManager.IMPORTANCE_HIGH))
        }
        // Dokununca MainActivity: kurulum izni yoksa önce o ayarı açar, varsa yükleyiciyi.
        val open = PendingIntent.getActivity(
            context,
            20_000 + (item.id.hashCode() and 0x7fff),
            Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL).putExtra(EXTRA_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = item.note.ifBlank { "Yüklemek için dokun." }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${item.name} hazır · Yükle")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    /** Kurulumu başlatır. Yükleme izni yoksa Ayarlar'daki "bilinmeyen uygulamalar" sayfasını açar. Mesaj döner. */
    fun install(context: Context, id: String): String {
        if (!id.matches(Regex("[0-9a-f]{6,32}"))) return "Geçersiz APK."
        val apk = file(context, id)
        if (!apk.isFile) return "APK henüz inmedi."
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Önce Chiron'a 'uygulama yükleme' izni ver, sonra tekrar dokun."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apk", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return "Yükleyici açıldı."
    }

    private const val NOTIFICATION_ID = 20_000

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun strings(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }
}
