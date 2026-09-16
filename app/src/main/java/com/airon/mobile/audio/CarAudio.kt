package com.airon.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Konuşma süresince ses yönü ve odak.
 *
 * - Araba kiti / Bluetooth kulaklık bağlıysa (eller serbest profili) konuşma sesi ve
 *   mikrofon oraya geçer. Yalnızca OTURUMDA: bekleme modunda ("Aıron" dinlemesi) açık
 *   bir SCO hattı arabaya sürekli "görüşme var" dedirtip radyoyu susturuyordu — o
 *   yüzden uyandırma dinleyicisi telefonun kendi mikrofonunda kalıyor.
 * - Müzik/navigasyon sesi Aıron konuşurken kısılır (geçici odak, kısma izinli).
 */
class CarAudio(context: Context) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var modeChanged = false

    /** Odağı alır, varsa eller serbest cihazına yönlendirir. Yönlendirildiyse true. */
    fun acquire(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
            .also { runCatching { am.requestAudioFocus(it) } }

        val routed = runCatching { route() }.onFailure { Log.w(TAG, "Bluetooth yönlendirmesi başarısız", it) }.getOrDefault(false)
        if (!routed) restoreMode()
        return routed
    }

    /**
     * Yalnız Android 12+. 10–11'deki startBluetoothSco() bağlantıyı beklemeden dönüyor ve
     * durum yayınını izlemeyi gerektiriyor; güvenilir yapılmadıkça orada telefonun kendi
     * hoparlörü/mikrofonu kullanılıyor (Astra incelemesi, 2026-09-17).
     */
    private fun route(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return false
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        modeChanged = true
        val ok = am.setCommunicationDevice(device)
        Log.i(TAG, "Eller serbest: ${device.productName} → $ok")
        return ok
    }

    private fun restoreMode() {
        if (!modeChanged) return
        runCatching { am.mode = AudioManager.MODE_NORMAL }
        modeChanged = false
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
        // Temizleme hata verse de telefon "görüşme" modunda kalmasın.
        restoreMode()
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    companion object {
        private const val TAG = "AironCarAudio"
    }
}
