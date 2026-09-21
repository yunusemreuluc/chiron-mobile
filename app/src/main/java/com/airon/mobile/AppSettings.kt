package com.airon.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Kullanıcı ayarları. İlk açılışta secrets.properties'ten derlenen varsayılanlar
 * (BuildConfig) kullanılıyor; Ayarlar ekranından değiştirilebiliyor.
 *
 * Şifrelenmemiş SharedPreferences bilinçli: kişisel, mağazaya konmayan bir APK ve
 * uygulama verisi zaten Android'in uygulama sanal alanında. PC oturum çerezi de
 * burada — PIN'in kendisi saklanmıyor, yalnızca çerez süresi dolunca tekrar soruluyor.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("airon", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_GEMINI_KEY
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var pcUrl: String
        get() = (prefs.getString(KEY_PC_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_PC_URL)
            .trimEnd('/')
        set(value) {
            val clean = value.trim().trimEnd('/')
            val before = prefs.getString(KEY_PC_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_PC_URL.trimEnd('/')
            val edit = prefs.edit().putString(KEY_PC_URL, clean)
            // Oturum çerezi giriş yapılan PC'ye ait; başka adrese asla gönderilmesin.
            if (clean != before) edit.putString(KEY_PC_SESSION, "")
            edit.apply()
        }

    /** Gemini Live hazır sesi — PC'deki Aıron'la aynı liste (backend/api/settings.py VOICES). */
    var voice: String
        get() = prefs.getString(KEY_VOICE, null)?.takeIf { v -> VOICES.any { it.id == v } } ?: "Charon"
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** Aıron'un konuşma sesi, telefonun ses düzeyine göre 0..1 kısma. */
    var voiceVolume: Float
        get() = prefs.getFloat(KEY_VOICE_VOLUME, 0.6f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_VOLUME, value.coerceIn(0.05f, 1f)).apply()

    /** "Aıron" dinlemesi açık mı — uygulama açılınca buna göre başlatılıyor. */
    var wakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE, value).apply()

    /**
     * Kayıt ekranı bekleme modunu geçici kapattı. Ekran düzgün kapanmadan süreç
     * ölürse (onDispose çalışmaz) bir sonraki açılışta dinleme bununla geri geliyor.
     */
    var wakePausedForRecording: Boolean
        get() = prefs.getBoolean(KEY_WAKE_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_PAUSED, value).apply()

    /** Araba modu: beklemedeyken gelen WhatsApp/SMS mesajlarını Aıron sesli okur. */
    var carMode: Boolean
        get() = prefs.getBoolean(KEY_CAR_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CAR_MODE, value).apply()

    /**
     * Bluetooth'ta telefon hattı (SCO, arabanın mikrofonu). Kapalıyken (varsayılan) Aıron'un
     * sesi müzik hattından gider, mikrofon telefonun kendisi — araba "görüşme" kaynağına
     * geçmiyor. Toyota C-HR hattan sonra müziğe dönmüyordu (audio/CarAudio.kt).
     */
    var handsFreeCall: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE_CALL, false)
        set(value) = prefs.edit().putBoolean(KEY_HANDS_FREE_CALL, value).apply()

    /** "Eve götür" / "işe götür" için. */
    var homeAddress: String
        get() = prefs.getString(KEY_HOME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME, value.trim()).apply()

    var workAddress: String
        get() = prefs.getString(KEY_WORK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORK, value.trim()).apply()

    /** PC'nin verdiği `airon_remote` çerezinin değeri. */
    var pcSession: String
        get() = prefs.getString(KEY_PC_SESSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PC_SESSION, value).apply()

    data class Voice(val id: String, val label: String)

    companion object {
        /** Google'ın ses karakteri tanımlarının Türkçesi. */
        val VOICES = listOf(
            Voice("Charon", "Erkek · bilgilendirici (varsayılan)"),
            Voice("Puck", "Erkek · neşeli, enerjik"),
            Voice("Fenrir", "Erkek · heyecanlı"),
            Voice("Orus", "Erkek · kararlı, tok"),
            Voice("Sadaltager", "Erkek · bilgili, sakin"),
            Voice("Aoede", "Kadın · ferah, rahat"),
            Voice("Kore", "Kadın · kararlı"),
            Voice("Leda", "Kadın · genç"),
            Voice("Zephyr", "Kadın · parlak, canlı"),
        )

        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_PC_URL = "pc_url"
        private const val KEY_VOICE = "voice"
        private const val KEY_PC_SESSION = "pc_session"
        private const val KEY_WAKE = "wake_enabled"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_CAR_MODE = "car_mode"
        private const val KEY_HANDS_FREE_CALL = "hands_free_call"
        private const val KEY_HOME = "home_address"
        private const val KEY_WORK = "work_address"
        private const val KEY_WAKE_PAUSED = "wake_paused_for_recording"
    }
}
