package com.chiron.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.chiron.mobile.AppSettings

/**
 * Konuşma süresince ses yönü ve odak. Üç yol var ([Route]):
 *
 * - PHONE: Bluetooth yok; telefonun kendi hoparlörü ve mikrofonu.
 * - BT_MEDIA (varsayılan, 2026-09-21): Bluetooth bağlı ama eller serbest hattı AÇILMIYOR.
 *   Chiron'un sesi medya akışından (A2DP) arabaya gidiyor, mikrofon telefonun kendisi.
 *   Neden: Toyota C-HR'da SCO hattı arabayı "telefon görüşmesi" kaynağına çeviriyor ve
 *   hat kapanınca müziğe geri DÖNMÜYOR. Gerçek aramada dönüyor, çünkü "arama bitti"
 *   göstergesini (HFP +CIEV) telefonun şebeke yığını gönderiyor — uygulamalar bu
 *   göstergeyi gönderemez. A2DP'de kaynak hiç değişmiyor, müzik kısılıp geri açılıyor.
 * - HANDS_FREE: Ayarlar'da "telefon hattı" açıksa eski yol — SCO, arabanın mikrofonu.
 *   Yalnızca OTURUMDA: bekleme modunda açık bir SCO hattı arabaya sürekli "görüşme var"
 *   dedirtip radyoyu susturuyordu.
 *
 * Tek örnek ([get]): her oturum yeni bir AudioIO açıyor, ama odak ve iletişim modu
 * sistem genelinde tek. Eski oturumun geç gelen [release]'i yeni oturumun odağını
 * bırakmasın diye durum burada, belirteçle korunuyor.
 */
class CarAudio private constructor(context: Context) {
    enum class Route { PHONE, BT_MEDIA, HANDS_FREE }

    private val am = context.getSystemService(AudioManager::class.java)
    private val settings = AppSettings(context)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var focus: AudioFocusRequest? = null
    private var modeChanged = false
    private var musicWasActive = false
    private var generation = 0

    /** Odağı alır, yolu seçer. Dönen belirteç [release]'e verilir. */
    fun acquire(): Pair<Route, Int> = synchronized(lock) {
        generation++
        // Önceki oturumun ertelenmiş odak bırakması artık bu oturumun odağını bırakırdı.
        main.removeCallbacksAndMessages(null)
        musicWasActive = runCatching { am.isMusicActive }.getOrDefault(false)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
            .also { runCatching { am.requestAudioFocus(it) } }

        val handsFree = settings.handsFreeCall && runCatching { routeHandsFree() }
            .onFailure { Log.w(TAG, "Bluetooth yönlendirmesi başarısız", it) }
            .getOrDefault(false)
        if (!handsFree) {
            // Önceki oturum SCO'ya yönlendirmiş olabilir: modu sıfırlamak yetmiyor,
            // cihaz seçimi kalırsa hat açık kalıyor (Astra incelemesi).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
            restoreMode()
        }
        val route = when {
            handsFree -> Route.HANDS_FREE
            bluetoothOutput() -> Route.BT_MEDIA
            else -> Route.PHONE
        }
        Log.i(TAG, "Ses yolu: $route (müzik çalıyordu: $musicWasActive)")
        route to generation
    }

    /**
     * Yalnız Android 12+. 10–11'deki startBluetoothSco() bağlantıyı beklemeden dönüyor ve
     * durum yayınını izlemeyi gerektiriyor; güvenilir yapılmadıkça orada telefonun kendi
     * hoparlörü/mikrofonu kullanılıyor (Astra incelemesi, 2026-09-17).
     */
    private fun routeHandsFree(): Boolean {
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

    private fun bluetoothOutput(): Boolean = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }.getOrDefault(false)

    private fun restoreMode() {
        if (!modeChanged) return
        runCatching { am.mode = AudioManager.MODE_NORMAL }
        modeChanged = false
    }

    /**
     * Mikrofon ve konuşma çıkışı KAPANDIKTAN SONRA çağrılmalı (AudioIO.stop): açık bir
     * görüşme kaydı dururken hat temizlenirse Android SCO'yu ayakta tutuyor.
     *
     * Sıra: iletişim cihazı → MODE_NORMAL → (SCO'nun düşmesi için bekleme) → odak. Odak
     * hattan önce bırakılırsa müzik, araba hâlâ "görüşme" kaynağındayken çalmaya
     * başlıyor ve hiçbir yere gitmiyor.
     */
    fun release(token: Int) {
        val hadCall: Boolean
        val wasPlaying: Boolean
        val request: AudioFocusRequest?
        synchronized(lock) {
            if (token != generation) return // arada yeni oturum başladı, durum artık onun
            hadCall = modeChanged
            wasPlaying = musicWasActive
            request = focus
            focus = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
            // Temizleme hata verse de telefon "görüşme" modunda kalmasın.
            restoreMode()
        }
        main.postDelayed({
            synchronized(lock) { if (token != generation) return@postDelayed }
            request?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            // Kaynağı müziğe çevirmeyen kafa üniteleri A2DP akışı başlayınca çeviriyor.
            // Çalıyorsa PLAY etkisiz; hat sırasında durdurulduysa geri başlatır.
            if (hadCall && wasPlaying) mediaPlay()
        }, if (hadCall) SCO_SETTLE_MS else 0L)
    }

    private fun mediaPlay() {
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
    }

    companion object {
        private const val TAG = "ChironCarAudio"
        /** SCO kapanışı ile odak bırakma arası; hattın gerçekten düşmesine süre. */
        private const val SCO_SETTLE_MS = 600L

        @Volatile private var instance: CarAudio? = null

        fun get(context: Context): CarAudio =
            instance ?: synchronized(this) {
                instance ?: CarAudio(context.applicationContext).also { instance = it }
            }
    }
}
