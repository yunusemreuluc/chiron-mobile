package com.airon.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airon.mobile.AppSettings
import com.airon.mobile.BuildConfig
import com.airon.mobile.audio.AudioIO
import com.airon.mobile.live.GeminiLiveClient
import com.airon.mobile.live.SystemPrompt
import com.airon.mobile.service.AssistantBus.Phase
import com.airon.mobile.tools.ToolDeclarations
import com.airon.mobile.tools.MessageAnnouncer
import com.airon.mobile.tools.ToolExecutor
import com.airon.mobile.ui.MainActivity
import com.airon.mobile.wake.WakeWordListener
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Konuşma oturumu. Ön plan servisi (mikrofon tipi): ekran kilitlense de ya da
 * Aıron'un açtığı WhatsApp öne gelse de konuşma sürüyor.
 *
 * Oturum boşta kalınca kendiliğinden kapanıyor — açık bir Live oturumu sürekli
 * mikrofon + kota demek.
 *
 * "Aıron" dinlemesi açıksa servis oturum bitince KAPANMIYOR, bekleme moduna dönüyor:
 * mikrofon çevrimdışı openWakeWord dinleyicisinde (WakeWordListener), Gemini'ye hiçbir şey
 * gitmiyor. Söz duyulunca oturum açılıyor ve söz öncesi/sonrası ses Gemini'ye
 * aktarılıyor — "Aıron, annemi ara" tek nefeste söylenebiliyor.
 */
class AssistantService : Service(), GeminiLiveClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val toolThread = Executors.newSingleThreadExecutor()
    /** İstek listesi eşitlemesi: ağ beklemesi araç çağrılarını (arama, mesaj) bekletmesin. */
    private val syncThread = Executors.newSingleThreadExecutor()
    private lateinit var settings: AppSettings
    private lateinit var tools: ToolExecutor
    private var live: GeminiLiveClient? = null
    private var audio: AudioIO? = null
    private var wake: WakeWordListener? = null
    private var models: WakeWordListener.Models? = null
    private val modelLoader = Executors.newSingleThreadExecutor()
    private var destroyed = false

    /** Her yeni oturumda artıyor. Kapanmış oturumun geç gelen geri çağrıları bununla ayıklanıyor. */
    @Volatile private var generation = 0
    private val runningTools = java.util.concurrent.atomic.AtomicInteger(0)
    private val outBuf = StringBuilder()
    private val inBuf = StringBuilder()
    /** Bu oturum "Aıron" sözüyle mi açıldı — söz sonrası ses Gemini'ye aktarılacak mı. */
    private var wakeTriggered = false
    /** Araba modunda okunmayı bekleyen gelen mesajlar (oturum hazır ve dinlerken iletilir). */
    private val pendingMessages = ArrayDeque<MessageAnnouncer.Message>()
    /** PC'de "yapıldı" olmuş istekler: (numara, başlık). Kullanıcının ilk isteği bittikten sonra söyleniyor. */
    private val pendingDone = ArrayList<Pair<String, String>>()
    private var turnsThisSession = 0
    /** Söylenmesi için gönderilen "yapıldı" numaraları — cevap turu bitince onaylanır. */
    private val awaitingAck = ArrayList<String>()
    private val announcer: (MessageAnnouncer.Message) -> Unit = { m -> main.post { onIncomingMessage(m) } }
    private val idleStop = Runnable { stopSession("Uzun süre konuşulmadı, oturum kapandı.") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        AudioIO.volume = settings.voiceVolume
        tools = ToolExecutor(this, settings)
        MessageAnnouncer.listener = announcer
    }

    /**
     * Araba modu: yeni WhatsApp/SMS. Aıron beklemedeyse oturum açıp okutuyor; oturum
     * sürüyorsa sıradaki dinleme anına bırakıyor (konuşmasının ortasına girmesin).
     * Dinleme kapalıyken servis çalışmadığı için buraya hiç haber gelmiyor.
     */
    private fun onIncomingMessage(message: MessageAnnouncer.Message) {
        if (destroyed || !settings.carMode) return
        pendingMessages.addLast(message)
        while (pendingMessages.size > MAX_PENDING_MESSAGES) pendingMessages.removeFirst()
        if (live == null) {
            if (settings.wakeEnabled) startSession()
        } else {
            flushMessages()
        }
    }

    private fun flushMessages() {
        val client = live ?: return
        if (!settings.carMode) pendingMessages.clear() // kullanıcı bu arada araba modunu kapattı
        // "Yapıldı" haberi kullanıcının kendi işinin önüne geçmesin.
        val doneReady = pendingDone.isNotEmpty() && turnsThisSession > 0
        if ((pendingMessages.isEmpty() && !doneReady) || runningTools.get() > 0) return
        val io = audio ?: return
        if (AssistantBus.state.value.phase != Phase.LISTENING || io.isSpeaking) return
        // Kullanıcı konuşuyor olabilir (yazıya dökme henüz gelmemiş): sustuğundan emin ol.
        val quietFor = android.os.SystemClock.elapsedRealtime() - io.lastVoiceAt
        if (quietFor < USER_QUIET_MS) {
            main.postDelayed(::flushMessages, USER_QUIET_MS - quietFor + 50)
            return
        }
        val parts = ArrayList<String>()
        pendingMessages.forEach {
            parts += "[YENİ MESAJ] ${it.app} — gönderen: ${it.sender}. Mesaj: ${it.text}"
            AssistantBus.line(fromUser = false, text = "📩 ${it.sender}: ${it.text}")
        }
        pendingMessages.clear()
        if (doneReady) {
            parts += "[TAMAMLANAN İSTEK] " + pendingDone.joinToString("; ") { it.second }
            // Onay, Aıron bunu gerçekten söyleyip turu bitirince (onTurnComplete) gidiyor;
            // oturum arada kapanırsa onaylanmaz, sonraki konuşmada yeniden söylenir.
            awaitingAck += pendingDone.map { it.first }
            pendingDone.clear()
        }
        client.sendText(parts.joinToString("\n"))
        AssistantBus.phase(Phase.THINKING)
        touch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession(null)
            ACTION_WAKE_ON -> {
                settings.wakeEnabled = true
                if (live == null) enterWakeMode()
            }
            ACTION_WAKE_OFF -> {
                settings.wakeEnabled = false
                if (live == null) stopSession(null)
            }
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    /** Bekleme: mikrofon yalnızca yerel dinleyicide. Modeller ilk seferde assets'ten okunuyor. */
    private fun enterWakeMode() {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(waiting = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        AssistantBus.phase(Phase.WAITING)
        if (wake != null) return
        val gen = generation
        modelLoader.execute {
            val loaded = models ?: runCatching { WakeWordListener.loadModels(this) }
                .onFailure { android.util.Log.e("AironWake", "Model yüklenemedi", it) }
                .getOrNull()
            main.post {
                if (loaded == null) {
                    settings.wakeEnabled = false
                    stopSession("'Aıron' uyandırma modeli yüklenemedi.")
                    return@post
                }
                models = loaded
                // Arada oturum açıldıysa ya da dinleme kapatıldıysa başlatma.
                if (gen != generation || live != null || wake != null || !settings.wakeEnabled) return@post
                lateinit var listener: WakeWordListener
                listener = WakeWordListener(
                    loaded,
                    // stopSession() kuyruğu temizledikten sonra gelen geç sinyal oturum açmasın.
                    onWake = { main.post { if (wake === listener && !destroyed && settings.wakeEnabled) onWakeWord() } },
                    onScore = AssistantBus::wakeScore,
                    onFailed = { e ->
                        main.post {
                            if (wake !== listener) return@post
                            wake = null
                            settings.wakeEnabled = false
                            stopSession("'Aıron' dinlemesi durdu: ${e.message ?: e.javaClass.simpleName}")
                        }
                    },
                )
                wake = listener.also { it.start() }
            }
        }
    }

    private fun onWakeWord() {
        if (live != null) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, (AudioIO.volume * 100).toInt())
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            main.postDelayed({ tone.release() }, 400)
        }
        startSession(fromWake = true)
    }

    private fun startSession(fromWake: Boolean = false) {
        if (live != null) return
        wakeTriggered = fromWake
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(waiting = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val key = settings.geminiKey
        if (key.isBlank()) {
            stopSession("Gemini API anahtarı yok — Ayarlar'dan gir.")
            return
        }
        AssistantBus.notice(null)
        AssistantBus.phase(Phase.CONNECTING)
        generation++
        runningTools.set(0)

        audio = AudioIO(
            this,
            onMicFrame = { frame, length -> live?.sendAudio(frame, length) },
            onMicLevel = AssistantBus::micLevel,
        )
        live = GeminiLiveClient(key, this).also {
            it.connect(MODEL, settings.voice, SystemPrompt.build(settings.carMode), ToolDeclarations.all)
        }
    }

    private fun stopSession(reason: String?) {
        generation++ // kuyrukta bekleyen onReady/araç sonuçları artık bu oturuma dokunmasın
        main.removeCallbacksAndMessages(null)
        pendingMessages.clear()
        pendingDone.clear()
        awaitingAck.clear()
        turnsThisSession = 0
        live?.close()
        live = null
        audio?.stop()
        audio = null
        wake?.stop()
        wake = null
        AssistantBus.micLevel(0f)
        AssistantBus.tool(null)
        AssistantBus.phase(Phase.OFF)
        if (reason != null) AssistantBus.notice(reason)
        if (settings.wakeEnabled && !destroyed) {
            // AudioIO'nun mikrofon iş parçacığı kaydı bırakana kadar kısa bekleme.
            AssistantBus.phase(Phase.WAITING)
            val gen = generation
            main.postDelayed({ if (gen == generation && live == null) enterWakeMode() }, MIC_RELEASE_MS)
            return
        }
        models = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun touch() {
        main.removeCallbacks(idleStop)
        // Bir araç (ör. 90 sn'lik PC sorusu) hâlâ çalışıyorsa boşta sayılmaz.
        if (runningTools.get() > 0 || live == null) return
        main.postDelayed(idleStop, IDLE_TIMEOUT_MS)
    }

    // ── GeminiLiveClient.Listener (OkHttp iş parçacığı) ─────────────────────

    override fun onReady() {
        val gen = generation
        main.post {
            if (gen != generation || live == null) return@post
            // Mikrofonu uyandırma dinleyicisinden devral; "Aıron …" ile gelen sesi önce Gemini'ye ver.
            val heldAudio = wake?.handOff(keepAudio = wakeTriggered).orEmpty()
            wake = null
            heldAudio.forEach { live?.sendAudio(it, it.size) }
            audio?.start()
            AssistantBus.phase(Phase.LISTENING)
            touch()
            flushMessages()
            // İstek listesi: telefonda bekleyenleri PC'ye gönder, "yapıldı" olanları sor.
            syncThread.execute {
                tools.requests.flush()
                val done = tools.requests.doneItems()
                if (done.isNotEmpty()) {
                    main.post {
                        if (gen != generation) return@post
                        pendingDone.clear()
                        pendingDone += done
                        flushMessages()
                    }
                }
            }
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        audio?.play(pcm24k)
        if (AssistantBus.state.value.phase != Phase.SPEAKING) AssistantBus.phase(Phase.SPEAKING)
        main.post(::touch)
    }

    override fun onOutputTranscript(text: String) {
        synchronized(outBuf) { outBuf.append(text) }
    }

    override fun onInputTranscript(text: String) {
        synchronized(inBuf) { inBuf.append(text) }
        if (AssistantBus.state.value.phase == Phase.LISTENING) AssistantBus.phase(Phase.THINKING)
        main.post(::touch)
    }

    override fun onTurnComplete() {
        main.post {
            turnsThisSession++
            if (awaitingAck.isNotEmpty()) {
                val numbers = awaitingAck.toList()
                awaitingAck.clear()
                syncThread.execute { tools.requests.acknowledge(numbers) }
            }
        }
        val heard = synchronized(inBuf) { inBuf.toString().also { inBuf.clear() } }
        val said = synchronized(outBuf) { outBuf.toString().also { outBuf.clear() } }
        AssistantBus.line(fromUser = true, text = heard)
        AssistantBus.line(fromUser = false, text = said)
        // Çalma kuyruğu bitene kadar "konuşuyor" — sonra dinlemeye dön.
        main.post(object : Runnable {
            override fun run() {
                val io = audio ?: return
                if (io.isSpeaking) {
                    main.postDelayed(this, 150)
                } else if (live != null) {
                    AssistantBus.phase(Phase.LISTENING)
                    flushMessages()
                }
            }
        })
        main.post(::touch)
    }

    override fun onInterrupted() {
        audio?.flushPlayback()
        synchronized(outBuf) { outBuf.clear() }
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        // Kullanıcının söylediği o ana kadar yazılsın: araç sonucu gelmeden sohbette görünsün.
        val heard = synchronized(inBuf) { inBuf.toString().also { inBuf.clear() } }
        AssistantBus.line(fromUser = true, text = heard)
        val gen = generation
        runningTools.incrementAndGet()
        AssistantBus.tool(name)
        main.post { main.removeCallbacks(idleStop) }
        toolThread.execute {
            val result = tools.execute(name, args)
            val remaining = runningTools.decrementAndGet()
            if (gen != generation) return@execute // kullanıcı bu arada oturumu kapattı
            live?.sendToolResponse(id, name, result)
            if (remaining == 0) {
                AssistantBus.tool(null)
                AssistantBus.phase(Phase.THINKING)
            }
            main.post(::touch)
        }
    }

    override fun onClosed(reason: String) {
        main.post { if (live != null) stopSession("Bağlantı kapandı: $reason") }
    }

    override fun onDestroy() {
        destroyed = true
        if (MessageAnnouncer.listener === announcer) MessageAnnouncer.listener = null
        stopSession(null)
        toolThread.shutdownNow()
        syncThread.shutdown()
        modelLoader.shutdownNow()
        super.onDestroy()
    }

    private fun notification(waiting: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aıron oturumu", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        if (waiting) {
            val off = PendingIntent.getService(
                this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_WAKE_OFF), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("\"Aıron\" bekleniyor")
                .setContentText("Telefonda dinleniyor, internete ses gitmiyor")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Dinlemeyi kapat", off).build())
                .setOngoing(true)
                .build()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aıron dinliyor")
            .setContentText("Dokun: aç · Kapat: oturumu bitir")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Kapat", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        /** PC'deki Aıron'la aynı model (main.py LIVE_MODEL). */
        const val MODEL = "models/gemini-2.5-flash-native-audio-latest"
        private const val CHANNEL = "airon_session"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.airon.mobile.STOP"
        private const val IDLE_TIMEOUT_MS = 45_000L
        private const val ACTION_WAKE_ON = "com.airon.mobile.WAKE_ON"
        private const val ACTION_WAKE_OFF = "com.airon.mobile.WAKE_OFF"
        private const val MIC_RELEASE_MS = 400L
        private const val MAX_PENDING_MESSAGES = 5
        private const val USER_QUIET_MS = 1500L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))

        /** Ön plandan (ekran açıkken) çağrılmalı — arka plandan mikrofon servisi başlatılamıyor. */
        fun setWake(context: Context, enabled: Boolean) {
            val intent = Intent(context, AssistantService::class.java).setAction(if (enabled) ACTION_WAKE_ON else ACTION_WAKE_OFF)
            if (enabled) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
