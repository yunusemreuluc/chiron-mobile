package com.chiron.mobile.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.chiron.mobile.AppSettings
import org.json.JSONObject

/** "Eve götür", "Kadıköy'e yol tarifi" — Google Haritalar'da navigasyonu doğrudan başlatır. */
class NavigationTools(private val context: Context, private val settings: AppSettings) {

    fun navigate(args: JSONObject): JSONObject {
        val spoken = args.optString("destination").trim()
        if (spoken.isEmpty()) return fail("Nereye gidileceği belirtilmedi.")
        val destination = when (ContactTools.normalize(spoken)) {
            in HOME_WORDS -> settings.homeAddress.ifBlank {
                return fail("Ev adresi kayıtlı değil — Chiron Ayarlar'dan ev adresini gir.")
            }
            in WORK_WORDS -> settings.workAddress.ifBlank {
                return fail("İş adresi kayıtlı değil — Chiron Ayarlar'dan iş adresini gir.")
            }
            else -> spoken
        }
        val mode = when (args.optString("mode")) {
            "walking" -> "w"
            "bicycling" -> "b"
            "two_wheeler" -> "l"
            else -> "d"
        }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode"))
            .setPackage(MAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (nav.resolveActivity(context.packageManager) != null) {
            context.startActivity(nav)
            return ok("Navigasyon başladı: $destination.")
        }
        // Haritalar yoksa herhangi bir harita uygulamasında göster.
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (geo.resolveActivity(context.packageManager) == null) return fail("Yüklü bir harita uygulaması yok.")
        context.startActivity(geo)
        return ok("Google Haritalar yok; '$destination' harita uygulamasında açıldı, yolu oradan başlatman gerekiyor.")
    }

    companion object {
        private const val MAPS = "com.google.android.apps.maps"
        private val HOME_WORDS = setOf("ev", "eve", "evim", "evime", "home")
        private val WORK_WORDS = setOf("is", "ise", "isim", "isime", "ofis", "ofise", "work")
    }
}
