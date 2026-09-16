package com.airon.mobile.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.AlarmClock
import android.view.KeyEvent
import org.json.JSONObject

/** Uygulama, alarm, fener, ses/medya/rahatsız etme ve cihaz sağlığı. */
class DeviceTools(private val context: Context) {
    private val appSearch = AppSearch(context)

    fun controlDevice(args: JSONObject): JSONObject = when (args.optString("action")) {
        "open_app" -> openApp(args.optString("app_name"))
        "search_in_app" -> searchInApp(args.optString("app_name"), args.optString("query"))
        "play_youtube" -> args.optString("query").trim().let {
            if (it.isEmpty()) fail("Ne oynatılacağı belirtilmedi.") else appSearch.playOnYouTube(it)
        }
        "set_alarm" -> setAlarm(args)
        "set_timer" -> setTimer(args)
        "flashlight_on" -> torch(true)
        "flashlight_off" -> torch(false)
        else -> fail("Bilinmeyen cihaz komutu: ${args.optString("action")}")
    }

    private fun openApp(spoken: String): JSONObject {
        if (spoken.isBlank()) return fail("Uygulama adı boş.")
        val app = findApp(spoken) ?: return fail("'$spoken' adında yüklü uygulama bulunamadı.")
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return fail("${app.label} açılamıyor.")
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ok("${app.label} açıldı.")
    }

    private fun searchInApp(spoken: String, query: String): JSONObject {
        if (spoken.isBlank()) return fail("Uygulama adı boş.")
        if (query.isBlank()) return fail("Aranacak şey belirtilmedi.")
        val app = findApp(spoken) ?: return fail("'$spoken' adında yüklü uygulama bulunamadı.")
        return appSearch.search(app, query.trim())
    }

    private fun findApp(spoken: String): InstalledApp? {
        val pm = context.packageManager
        val query = ContactTools.normalize(spoken)
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL,
        )
        val apps = launchable.map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        return apps.firstOrNull { ContactTools.normalize(it.label) == query }
            ?: apps.firstOrNull { ContactTools.normalize(it.label).startsWith(query) }
            ?: apps.firstOrNull { ContactTools.normalize(it.label).contains(query) }
    }

    private fun setAlarm(args: JSONObject): JSONObject {
        if (!args.has("hour")) return fail("Alarm saati belirtilmedi.")
        val hour = args.optInt("hour").coerceIn(0, 23)
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        context.startActivity(intent)
        return ok("Alarm %02d:%02d için kuruldu.".format(hour, minute))
    }

    private fun setTimer(args: JSONObject): JSONObject {
        val seconds = args.optInt("duration_seconds", 0)
        if (seconds <= 0) return fail("Zamanlayıcı süresi belirtilmedi.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        context.startActivity(intent)
        return ok("$seconds saniyelik zamanlayıcı başladı.")
    }

    private fun torch(on: Boolean): JSONObject {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("Bu telefonda fener yok.")
        camera.setTorchMode(id, on)
        return ok(if (on) "Fener açıldı." else "Fener kapandı.")
    }

    fun manageAudio(args: JSONObject): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        return when (args.optString("action")) {
            "volume_up" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0); volume(audio)
            }
            "volume_down" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0); volume(audio)
            }
            "set_volume" -> {
                val pct = args.optInt("level", 50).coerceIn(0, 100)
                audio.setStreamVolume(stream, (audio.getStreamMaxVolume(stream) * pct / 100f).toInt(), 0)
                volume(audio)
            }
            "mute" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0); ok("Medya sesi kapatıldı.")
            }
            "unmute" -> {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0); volume(audio)
            }
            "media_play_pause" -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Oynat/duraklat gönderildi.")
            "media_next" -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT, "Sonraki parçaya geçildi.")
            "media_previous" -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Önceki parçaya dönüldü.")
            "dnd_on" -> dnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY, "Rahatsız etme modu açıldı.")
            "dnd_off" -> dnd(NotificationManager.INTERRUPTION_FILTER_ALL, "Rahatsız etme modu kapandı.")
            else -> fail("Bilinmeyen ses komutu: ${args.optString("action")}")
        }
    }

    private fun volume(audio: AudioManager): JSONObject {
        val stream = AudioManager.STREAM_MUSIC
        val pct = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ok("Medya sesi yüzde $pct.", "level" to pct)
    }

    private fun mediaKey(audio: AudioManager, code: Int, message: String): JSONObject {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok(message)
    }

    private fun dnd(filter: Int, message: String): JSONObject {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return fail("Rahatsız etme modu izni verilmemiş.")
        nm.setInterruptionFilter(filter)
        return ok(message)
    }

    fun deviceHealth(): JSONObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        val result = JSONObject().put("success", true).put("battery_percent", level).put("charging", charging)

        // Yalnızca şarj olurken tahmin veriyor (API 28+); boşalma süresi Android'de yok,
        // uydurmuyoruz.
        if (charging) {
            val remainingMs = battery.computeChargeTimeRemaining()
            if (remainingMs > 0) result.put("minutes_to_full", remainingMs / 60_000)
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "bağlantı yok"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "diğer"
        }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return result.put("network", network).put("vpn_active", vpn)
            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }
}
