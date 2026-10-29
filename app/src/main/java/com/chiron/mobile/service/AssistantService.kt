package com.chiron.mobile.service

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
import com.chiron.mobile.AppSettings
import com.chiron.mobile.BuildConfig
import com.chiron.mobile.audio.AudioIO
import com.chiron.mobile.audio.CarAudio
import com.chiron.mobile.live.GeminiLiveClient
import com.chiron.mobile.live.SystemPrompt
import com.chiron.mobile.service.AssistantBus.Phase
import com.chiron.mobile.tools.ToolDeclarations
import com.chiron.mobile.tools.ClaudeAnnouncement
import com.chiron.mobile.tools.ClaudeDrafts
import com.chiron.mobile.tools.MessageAnnouncer
import com.chiron.mobile.tools.MessageDrafts
import com.chiron.mobile.tools.ToolExecutor
import com.chiron.mobile.tools.UntrustedInput
import com.chiron.mobile.ui.MainActivity
import com.chiron.mobile.wake.WakeWordListener
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Konuşma oturumu. Ön plan servisi (mikrofon tipi): ekran kilitlense de ya da
 * Chiron'un açtığı WhatsApp öne gelse de konuşma sürüyor.
 *
 * Oturum boşta kalınca kendiliğinden kapanıyor — açık bir Live oturumu sürekli
 * mikrofon + kota demek.
 *
 * "Chiron" dinlemesi açıksa servis oturum bitince KAPANMIYOR, bekleme moduna dönüyor:
 * mikrofon çevrimdışı openWakeWord dinleyicisinde (WakeWordListener), Gemini'ye hiçbir şey
 * gitmiyor. Söz duyulunca oturum açılıyor ve söz öncesi/sonrası ses Gemini'ye
 * aktarılıyor — "Chiron, annemi ara" tek nefeste söylenebiliyor.
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
    /** Bu oturum "Chiron" sözüyle mi açıldı — söz sonrası ses Gemini'ye aktarılacak mı. */
    private var wakeTriggered = false
    /** Araba modunda okunmayı bekleyen gelen mesajlar (oturum hazır ve dinlerken iletilir). */
    private val pendingMessages = ArrayDeque<MessageAnnouncer.Message>()
    /** PC'de "yapıldı" olmuş istekler: (numara, başlık). Kullanıcının ilk isteği bittikten sonra söyleniyor. */
    private val pendingDone = ArrayList<Pair<String, String>>()
    /** PC'deki Claude'un kullanıcıya henüz söylenmemiş sonuçları. */
    private val pendingClaude = ArrayList<ClaudeAnnouncement.Job>()
    private var turnsThisSession = 0
    /** Söylenmesi için gönderilen "yapıldı" numaraları — cevap turu bitince onaylanır. */
    private val awaitingAck = ArrayList<String>()
    private val awaitingClaudeAck = ArrayList<String>()
    /** Onay sorusu modele verildi, okunması sürüyor: bitince ClaudeDrafts.presentApproval. */
    private var announcingApproval: String? = null
    /**
     * Yazışma modu (2026-10-03, kullanıcı isteği "telefonda Claude ile yazışır gibi"):
     * kullanıcı klavyeyle yazdı → Chiron sesli cevap vermez, mikrofon duraklar (toplantıda,
     * sessiz ortamda). Mikrofona dokunmak sesli moda döndürür.
     */
    @Volatile private var typedMode = false
    /**
     * İ-008: oturum açıkken ses odağı (CarAudio, GAIN_TRANSIENT_MAY_DUCK) müziği kısıyor —
     * "şarkıyı değiştir" deyince şarkı değişiyor ama oturum kapanana kadar ses gelmiyordu.
     * Müzik komutu başarılı olunca Chiron cevabını bitirir bitirmez oturum kapanır, odak bırakılır.
     */
    private var endAfterTurn = false
    /** Oturum hazır olmadan yazılan mesajlar — onReady'de sırayla gider. */
    private val pendingTyped = ArrayList<String>()
    private var liveReady = false
    private val announcer: (MessageAnnouncer.Message) -> Unit = { m -> main.post { onIncomingMessage(m) } }
    private val idleStop = Runnable {
        // Uzun bir okuma sürerken (kuyrukta ses var) boşta sayılmaz; kısa Bluetooth
        // süresi cümlenin ortasında kesmesin (Astra incelemesi).
        if (audio?.isSpeaking == true) touch() else stopSession("Uzun süre konuşulmadı, oturum kapandı.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        AudioIO.volume = settings.voiceVolume
        tools = ToolExecutor(this, settings)
        MessageAnnouncer.listener = announcer
    }

    /**
     * Araba modu: yeni WhatsApp/SMS. Chiron beklemedeyse oturum açıp okutuyor; oturum
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
        // Claude sonucu ise beklemez: kullanıcı oturumu çoğunlukla "Claude bitti"
        // bildirimine dokunarak açıyor, yani duymak istediği şey tam da bu.
        // Aynı anda TEK onay sorusu: kullanıcının bir "evet"i iki işin kapısını açmasın
        // (Astra). Açık bir soru varken sıradaki onay bekler; biten işler beklemez.
        var approvalTaken = ClaudeDrafts.hasOpenApproval() || announcingApproval != null
        val claudeBatch = pendingClaude.filter {
            if (it.status != "needs_approval") return@filter true
            if (approvalTaken) return@filter false
            approvalTaken = true
            true
        }
        val claudeReady = claudeBatch.isNotEmpty()
        if ((pendingMessages.isEmpty() && !doneReady && !claudeReady) || runningTools.get() > 0) return
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
            // Onay, Chiron bunu gerçekten söyleyip turu bitirince (onTurnComplete) gidiyor;
            // oturum arada kapanırsa onaylanmaz, sonraki konuşmada yeniden söylenir.
            awaitingAck += pendingDone.map { it.first }
            pendingDone.clear()
        }
        if (claudeReady) {
            claudeBatch.forEach {
                parts += ClaudeAnnouncement.format(it)
                AssistantBus.line(fromUser = false, text = claudeBubble(it), claude = true)
            }
            // Onay kapısı soru OKUNUP BİTİNCE açılır (onTurnComplete → ses bitti), gönderimde değil.
            announcingApproval = claudeBatch.firstOrNull { it.status == "needs_approval" }?.id
            // Bildirim ack değildir: yalnız model sonucu sesli söyleyip bitirince PC'ye ack gider.
            awaitingClaudeAck += claudeBatch.map { it.id }
            pendingClaude.removeAll(claudeBatch.toSet())
        }
        // Mesaj ve PC sonucu dışarıdan gelen metin: hemen ardından iş doğrudan başlatılmasın.
        if (parts.any { it.startsWith("[YENİ MESAJ]") || it.startsWith("[CLAUDE İŞİ]") }) UntrustedInput.mark()
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
            ACTION_TEXT -> intent?.getStringExtra(EXTRA_TEXT)?.let(::onTyped)
            ACTION_VOICE -> setTyped(false)
            // "Claude bitti" bildirimine dokunuldu: oturum açıksa sonucu hemen çek.
            ACTION_CLAUDE -> if (live == null) startSession() else syncClaudeUpdates(generation)
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
                .onFailure { android.util.Log.e("ChironWake", "Model yüklenemedi", it) }
                .getOrNull()
            main.post {
                if (loaded == null) {
                    settings.wakeEnabled = false
                    stopSession("'Chiron' uyandırma modeli yüklenemedi.")
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
                            stopSession("'Chiron' dinlemesi durdu: ${e.message ?: e.javaClass.simpleName}")
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
        ).also {
            // Mikrofon başlamadan ÖNCE: yazılı mesajla açılan oturumda tek kare bile sayılmasın.
            it.micPaused = typedMode
            it.playbackMuted = typedMode
        }
        live = GeminiLiveClient(key, this).also {
            it.connect(MODEL, settings.voice, SystemPrompt.build(settings.carMode, settings.assistantName), ToolDeclarations.all)
        }
    }

    private fun stopSession(reason: String?) {
        generation++ // kuyrukta bekleyen onReady/araç sonuçları artık bu oturuma dokunmasın
        main.removeCallbacksAndMessages(null)
        pendingMessages.clear()
        pendingDone.clear()
        pendingClaude.clear()
        awaitingAck.clear()
        awaitingClaudeAck.clear()
        announcingApproval = null
        endAfterTurn = false
        pendingTyped.clear()
        liveReady = false
        typedMode = false
        AssistantBus.typing(false)
        turnsThisSession = 0
        live?.close()
        live = null
        MessageDrafts.clear() // onaylanmamış mesaj taslağı oturumla birlikte düşer
        ClaudeDrafts.clear() // okunmuş onay soruları da: sonraki oturumda yeniden okunur
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
        // Bluetooth'ta oturum sürdükçe müzik kısık (ya da araba görüşme kaynağında) kalıyor:
        // 45 sn orada çok uzun.
        val bluetooth = audio?.path?.let { it != CarAudio.Route.PHONE } == true
        val timeout = when {
            typedMode -> TYPED_IDLE_TIMEOUT_MS // yazmak konuşmaktan uzun sürüyor
            bluetooth -> BT_IDLE_TIMEOUT_MS
            else -> IDLE_TIMEOUT_MS
        }
        main.postDelayed(idleStop, timeout)
    }

    // ── GeminiLiveClient.Listener (OkHttp iş parçacığı) ─────────────────────

    override fun onReady() {
        val gen = generation
        main.post {
            if (gen != generation || live == null) return@post
            // Mikrofonu uyandırma dinleyicisinden devral; "Chiron …" ile gelen sesi önce Gemini'ye ver.
            // Yazışma moduna geçildiyse uyandırma tamponundaki ortam sesi Gemini'ye gitmez.
            val heldAudio = wake?.handOff(keepAudio = wakeTriggered && !typedMode).orEmpty()
            wake = null
            heldAudio.forEach { live?.sendAudio(it, it.size) }
            audio?.start()
            liveReady = true
            AssistantBus.phase(Phase.LISTENING)
            touch()
            if (pendingTyped.isNotEmpty()) {
                val typed = pendingTyped.joinToString("\n")
                pendingTyped.clear()
                sendTyped(typed)
            } else {
                flushMessages()
            }
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
            syncClaudeUpdates(gen)
        }
    }

    private fun isMediaCommand(name: String, args: JSONObject): Boolean = when (name) {
        "manage_device_audio" -> args.optString("action") in MEDIA_ACTIONS
        "control_device" -> args.optString("action") == "play_youtube"
        else -> false
    }

    /** Yazılan mesaj: ekrana yazılır, yazışma moduna geçilir, oturum yoksa açılır. */
    private fun onTyped(raw: String) {
        val text = raw.trim().take(MAX_TYPED_CHARS)
        if (text.isEmpty() || destroyed) return
        // Yazılan mesaj kullanıcının kendi sözü: mesaj taslağı / Claude onay kapıları
        // (AudioIO.lastUserVoiceAt) bunu "kullanıcı cevap verdi" olarak görmeli.
        AudioIO.lastUserVoiceAt = android.os.SystemClock.elapsedRealtime()
        AssistantBus.line(fromUser = true, text = text)
        setTyped(true)
        if (live == null) {
            pendingTyped += text
            startSession()
        } else if (!liveReady) {
            pendingTyped += text
        } else {
            sendTyped(text)
        }
    }

    private fun sendTyped(text: String) {
        val client = live ?: return
        audio?.flushPlayback() // yarım kalan sesli cevap kullanıcının yeni mesajını bastırmasın
        client.sendText(text)
        AssistantBus.phase(Phase.THINKING)
        touch()
    }

    private fun setTyped(on: Boolean) {
        typedMode = on
        audio?.micPaused = on
        audio?.playbackMuted = on // kuyruğu aynı kilitle boşaltır; geç gelen parça çalınmaz
        AssistantBus.typing(on)
        main.post(::touch)
    }

    private fun claudeBubble(job: ClaudeAnnouncement.Job): String {
        val body = job.summary.take(1500)
        return when (job.status) {
            "done" -> "PC'de · bitti\n$body"
            "needs_approval" -> "PC'de · onay bekliyor\n${job.approval.ifBlank { body }}"
            "timeout" -> "PC'de · süre doldu\n$body"
            "interrupted" -> "PC'de · yarıda kaldı\n$body"
            else -> "PC'de · tamamlanamadı\n$body"
        }
    }

    /** PC'deki Claude'un söylenmemiş sonuçlarını çeker ve sıradaki dinleme anında okutur. */
    private fun syncClaudeUpdates(gen: Int) {
        syncThread.execute {
            val updates = tools.claude.updates().map {
                ClaudeAnnouncement.Job(
                    id = it.optString("id"),
                    status = it.optString("status"),
                    summary = it.optString("summary").ifBlank { it.optString("error") },
                    approval = it.optString("approval"),
                )
            }
            if (updates.isEmpty()) return@execute
            main.post {
                if (gen != generation || live == null) return@post
                val known = pendingClaude.map { it.id }.toSet() + awaitingClaudeAck
                pendingClaude += updates.filter { it.id !in known }
                flushMessages()
            }
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        // Yazışma modu: cevap ekranda yazı olarak gelir (output transcript), ses çalınmaz.
        if (typedMode) {
            main.post(::touch)
            return
        }
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
                    // Claude sonucu ancak ses sonuna kadar çalındıysa "söylendi" sayılır
                    // (Astra: tur bitince ack gidiyordu, ses kesilse de).
                    if (awaitingClaudeAck.isNotEmpty()) {
                        val ids = awaitingClaudeAck.toList()
                        awaitingClaudeAck.clear()
                        syncThread.execute { tools.claude.acknowledge(ids) }
                    }
                    // Onay sorusu sonuna kadar okundu: ancak bundan SONRAKİ kullanıcı sesi
                    // "onaylıyorum"u PC'ye geçirebilir (ClaudeJobs ses kapısı).
                    announcingApproval?.let { ClaudeDrafts.presentApproval(it) }
                    announcingApproval = null
                    if (endAfterTurn && runningTools.get() == 0) {
                        endAfterTurn = false
                        stopSession(null) // müzik duyulsun: ses odağı bırakılır
                        return
                    }
                    AssistantBus.phase(Phase.LISTENING)
                    flushMessages()
                }
            }
        })
        main.post(::touch)
    }

    override fun onInterrupted() {
        // Kullanıcı sözü kesti: Claude sonucu tam duyulmadı, ack gitmez; sonraki
        // oturumda yeniden okunur.
        main.post {
            awaitingClaudeAck.clear()
            announcingApproval = null // soru yarım kaldı: kapı açılmaz, sonraki oturumda yeniden sorulur
        }
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
            if (result.optBoolean("success") && isMediaCommand(name, args)) main.post { endAfterTurn = true }
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chiron oturumu", NotificationManager.IMPORTANCE_LOW))
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
                .setContentTitle("\"Chiron\" bekleniyor")
                .setContentText("Telefonda dinleniyor, internete ses gitmiyor")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Dinlemeyi kapat", off).build())
                .setOngoing(true)
                .build()
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Chiron dinliyor")
            .setContentText("Dokun: aç · Kapat: oturumu bitir")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Kapat", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        /** PC'deki Chiron'la aynı model (main.py LIVE_MODEL). */
        const val MODEL = "models/gemini-2.5-flash-native-audio-latest"
        private const val CHANNEL = "chiron_session"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.chiron.mobile.STOP"
        private const val IDLE_TIMEOUT_MS = 45_000L
        private const val BT_IDLE_TIMEOUT_MS = 15_000L
        private const val ACTION_WAKE_ON = "com.chiron.mobile.WAKE_ON"
        private const val ACTION_WAKE_OFF = "com.chiron.mobile.WAKE_OFF"
        private const val ACTION_CLAUDE = "com.chiron.mobile.CLAUDE"
        private const val ACTION_TEXT = "com.chiron.mobile.TEXT"
        private const val ACTION_VOICE = "com.chiron.mobile.VOICE"
        private const val EXTRA_TEXT = "text"
        private const val TYPED_IDLE_TIMEOUT_MS = 5 * 60_000L
        private val MEDIA_ACTIONS = setOf("media_play_pause", "media_next", "media_previous", "unmute")
        private const val MAX_TYPED_CHARS = 4000
        private const val MIC_RELEASE_MS = 400L
        private const val MAX_PENDING_MESSAGES = 5
        private const val USER_QUIET_MS = 1500L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))

        /** Ekrandan yazılan mesaj. Ön plandan çağrılmalı (oturum yoksa mikrofon servisi açılır). */
        fun sendText(context: Context, text: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_TEXT).putExtra(EXTRA_TEXT, text),
            )

        /** Yazışma modundan sesli moda dön. */
        fun voiceMode(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_VOICE))

        /** Bildirimden: Claude sonucunu okutmak için oturum aç (ya da açık oturumda çek). Ön plandan çağrılmalı. */
        fun showClaudeResult(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java).setAction(ACTION_CLAUDE))

        fun stop(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))

        /** Ön plandan (ekran açıkken) çağrılmalı — arka plandan mikrofon servisi başlatılamıyor. */
        fun setWake(context: Context, enabled: Boolean) {
            val intent = Intent(context, AssistantService::class.java).setAction(if (enabled) ACTION_WAKE_ON else ACTION_WAKE_OFF)
            if (enabled) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
