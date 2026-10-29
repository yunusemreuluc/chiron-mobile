package com.chiron.mobile.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.chiron.mobile.tools.ChironNotificationListener
import com.chiron.mobile.tools.MiuiBackgroundStart
import com.chiron.mobile.tools.WhatsAppSendService

/**
 * İzinlerin tek listesi. İki tür: çalışma zamanı izinleri tek bir diyalog
 * dizisiyle isteniyor; "özel erişim"ler (erişilebilirlik, bildirim, rahatsız
 * etme, üstte gösterme) Android'de yalnızca Ayarlar ekranından açılabiliyor.
 */
object Permissions {

    val runtime = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    data class Special(
        val title: String,
        val why: String,
        val granted: (Context) -> Boolean,
        val intent: (Context) -> Intent,
    )

    val special = listOf(
        Special(
            "WhatsApp'ta otomatik gönder",
            "Erişilebilirlik → Chiron mesaj gönderici. Kapalıysa mesaj yazılır ama gönder'e sen basarsın.",
            { WhatsAppSendService.isEnabled(it) },
            { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        ),
        Special(
            "Bildirimleri okuma",
            "\"Son bildirimlerim ne\" için.",
            { ChironNotificationListener.isEnabled(it) },
            { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        ),
        Special(
            "Rahatsız etme modu",
            "\"Rahatsız etme modunu aç\" için.",
            { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
        ),
        Special(
            "Diğer uygulamaların üzerinde göster",
            "Ekran kilitliyken ya da Chiron arka plandayken arama/WhatsApp açabilmek için (Android bunu başka türlü engelliyor).",
            { Settings.canDrawOverlays(it) },
            { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) },
        ),
        Special(
            "Pil kısıtlamasını kaldır",
            "Ekran kapalıyken ve arabada \"Chiron\" dinlemesi kesilmesin. Xiaomi'de ayrıca Uygulama bilgisi → Pil tasarrufu → Kısıtlama yok.",
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            {
                @android.annotation.SuppressLint("BatteryLife") // kişisel APK; mağaza politikası geçerli değil
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
                i
            },
        ),
        Special(
            "Uygulama yükleme",
            "PC'nin gönderdiği APK'ları (Dilcep güncellemesi gibi) kurabilmek için. İşteyken adb olmadan kurulum bu yoldan.",
            { it.packageManager.canRequestPackageInstalls() },
            { Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${it.packageName}")) },
        ),
        Special(
            "Xiaomi: arka planda pencere aç",
            "Diğer izinler → 'Arka planda çalışırken yeni pencereler aç'. Kapalıysa Chiron başka uygulamadayken WhatsApp/YouTube açamaz.",
            { MiuiBackgroundStart.granted(it) },
            { MiuiBackgroundStart.settingsIntent(it) },
        ),
    )

    fun missingRuntime(context: Context) = runtime.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
