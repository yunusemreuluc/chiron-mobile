package com.airon.mobile.tools

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 10+ arka plandan ekran açmayı (arama, WhatsApp, alarm, uygulama) sessizce
 * ENGELLİYOR: startActivity istisna atmıyor, sadece hiçbir şey olmuyor. Önceki
 * sürüm bu durumda da "aranıyor" diyordu (Astra incelemesi, 2026-09-15).
 *
 * Açılışa izin veren durumlar: Aıron ekranı öndeyse, "diğer uygulamaların
 * üzerinde göster" izni varsa ya da sistemin bağladığı bir erişilebilirlik
 * servisi çalışıyorsa (Android bunu arka plan açılışı muafiyeti sayıyor).
 *
 * Xiaomi (MIUI/HyperOS) bunun ÜSTÜNE kendi kapısını koyuyor: 2026-09-16'da gerçek
 * cihazda logcat "Background activity start ... allowed because SYSTEM_ALERT_WINDOW"
 * dedikten hemen sonra "MIUILOG- Permission Denied Activity" ile açılışı iptal etti.
 * Bu kapı MIUI'nin 10021 numaralı app-op'u ("Arka planda çalışırken yeni pencere
 * aç"); Android izinlerinden bağımsız, ayrıca açılması gerekiyor.
 */
object BackgroundLaunch {
    @Volatile var activityVisible = false

    fun blockedReason(context: Context): String? {
        if (activityVisible) return null
        if (!MiuiBackgroundStart.granted(context)) {
            return "Xiaomi, Aıron arka plandayken ekran açmayı engelliyor. Ayarlar → Uygulamalar → Aıron → " +
                "Diğer izinler → 'Arka planda çalışırken yeni pencereler aç' iznini açmak gerekiyor."
        }
        if (Settings.canDrawOverlays(context)) return null
        if (WhatsAppSendService.isEnabled(context)) return null
        return "Aıron arka plandayken ekran açamıyorum. Kurulumdan 'Diğer uygulamaların üzerinde göster' iznini açmak gerekiyor."
    }
}

object MiuiBackgroundStart {
    private const val OP_BACKGROUND_START_ACTIVITY = 10021

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Xiaomi değilse ya da durum okunamıyorsa true — okuyamadığımız şey için engel uydurmuyoruz. */
    fun granted(context: Context): Boolean {
        if (!isXiaomi) return true
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            val mode = check.invoke(ops, OP_BACKGROUND_START_ACTIVITY, context.applicationInfo.uid, context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true)
    }

    /** MIUI izin düzenleyicisi; yoksa uygulama bilgi ekranı. */
    fun settingsIntent(context: Context): Intent {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            .putExtra("extra_pkgname", context.packageName)
        if (miui.resolveActivity(context.packageManager) != null) return miui
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}
