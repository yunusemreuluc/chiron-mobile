package com.airon.mobile.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Mikrofon (16 kHz) ve hoparlör (24 kHz), ikisi de 16-bit mono PCM — Gemini
 * Live'ın beklediği biçim.
 *
 * YANKI: telefon hoparlörü mikrofona çok yakın. Aıron konuşurken mikrofonu
 * modele göndermek, kendi sesini kullanıcı sanıp sözünü kesmesine yol açıyor.
 * PC'deki Aıron da aynı kararı verdi (main.py: konuşurken dinleme yok): çalma
 * sürerken ve bittikten sonraki kısa kuyrukta mikrofon karesi gönderilmiyor.
 * Donanım AEC varsa ayrıca açılıyor, ama tek başına ona güvenilmiyor.
 */
class AudioIO(
    context: Context,
    private val onMicFrame: (ByteArray, Int) -> Unit,
    private val onMicLevel: (Float) -> Unit,
) {
    private val route = CarAudio.get(context)
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var lastPlaybackAt = 0L
    @Volatile private var writing = false
    /** `flushPlayback` her çağrıda artırıyor; öncesinde kuyruktan alınmış parça yazılmıyor. */
    @Volatile private var playbackGeneration = 0
    /** Kullanıcının sesinin son duyulduğu an (elapsedRealtime). */
    @Volatile var lastVoiceAt = 0L
        private set
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var speakerThread: Thread? = null
    private var routeToken = 0
    @Volatile private var echoTailMs = ECHO_TAIL_MS

    /** Bu oturumun ses yolu; bekleme süresi buna göre (AssistantService). */
    @Volatile var path = CarAudio.Route.PHONE
        private set

    /**
     * Yazışma modu kilidi. Mikrofon karesi "duraklı mı?" kontrolü ile `lastUserVoiceAt`
     * güncellemesi AYNI kilit altında: aksi hâlde kontrolü geçmiş eski bir kare, mod
     * değiştikten sonra damgayı ileri alıp onay kapısını açabiliyordu (Astra, kritik).
     */
    private val modeLock = Any()

    /** Yazışma modu: mikrofon okunur ama ne seviye ne çerçeve kullanılır. */
    var micPaused = false
        get() = synchronized(modeLock) { field }
        set(value) = synchronized(modeLock) { field = value }

    /** Yazışma modu: cevap sesi çalınmaz; açılırken kuyruk da boşaltılır (aynı kilit, play ile yarışmaz). */
    var playbackMuted = false
        get() = synchronized(modeLock) { field }
        set(value) {
            synchronized(modeLock) {
                field = value
                if (value) flushPlayback()
            }
        }

    val isSpeaking: Boolean
        get() = writing || playQueue.isNotEmpty() || SystemClock.elapsedRealtime() - lastPlaybackAt < echoTailMs

    @SuppressLint("MissingPermission") // izin, servis başlamadan önce MainActivity'de alınıyor
    fun start() {
        if (running) return
        running = true
        // Bluetooth bağlıysa ses oraya (varsayılan medya akışı, mikrofon telefonda); müzik kısılır.
        val (chosen, token) = route.acquire()
        path = chosen
        routeToken = token
        // A2DP gecikmesi (~200 ms) + araba kabininin yankısı telefon mikrofonuna dönüyor.
        echoTailMs = if (chosen == CarAudio.Route.BT_MEDIA) BT_ECHO_TAIL_MS else ECHO_TAIL_MS

        val minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn, FRAME_BYTES * 4),
        )
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        record = rec

        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Bluetooth eller serbest (SCO) hattı yalnız "görüşme" sesini taşıyor.
                    .setUsage(
                        if (chosen == CarAudio.Route.HANDS_FREE) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_ASSISTANT,
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
            )
            .build()
        track = out

        micThread = thread(name = "airon-mic") {
            val buffer = ByteArray(FRAME_BYTES)
            try {
                rec.startRecording()
                while (running) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    // Yazışma modunda mikrofon tamamen duraklı: ortam sesi ne Gemini'ye gider ne de
                    // "kullanıcı konuştu" sayılıp onay kapısını (lastUserVoiceAt) açar.
                    val lvl = synchronized(modeLock) {
                        if (isSpeaking || micPaused) {
                            null
                        } else {
                            level(buffer, read).also {
                                if (it >= VOICE_LEVEL) {
                                    lastVoiceAt = SystemClock.elapsedRealtime()
                                    lastUserVoiceAt = lastVoiceAt
                                }
                            }
                        }
                    }
                    if (lvl == null) {
                        onMicLevel(0f)
                        continue
                    }
                    onMicLevel(lvl)
                    onMicFrame(buffer, read)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mikrofon hatası", e)
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }

        speakerThread = thread(name = "airon-speaker") {
            try {
                out.play()
                var applied = -1f
                while (running) {
                    // Ayarlardaki kaydırıcı konuşma sürerken de etkili olsun.
                    val vol = volume
                    if (vol != applied) {
                        out.setVolume(vol)
                        applied = vol
                    }
                    val generation = playbackGeneration
                    val chunk = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (generation != playbackGeneration) continue // arada söz kesildi
                    // write() bloklarken kuyruk boş görünüyor — `writing` o arada
                    // mikrofonu kapalı tutuyor (Astra incelemesi).
                    writing = true
                    try {
                        out.write(chunk, 0, chunk.size)
                    } finally {
                        writing = false
                        lastPlaybackAt = SystemClock.elapsedRealtime()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hoparlör hatası", e)
            } finally {
                runCatching { out.stop() }
                out.release()
            }
        }
    }

    fun play(pcm24k: ByteArray) {
        synchronized(modeLock) {
            if (running && !playbackMuted) playQueue.offer(pcm24k)
        }
    }

    /** Model sözü kesildiğini bildirdi: kuyruktaki eski cevabı çalma. */
    fun flushPlayback() {
        playbackGeneration++
        playQueue.clear()
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun stop() {
        running = false
        playQueue.clear()
        val rec = record
        val out = track
        record = null
        track = null
        val workers = listOfNotNull(micThread, speakerThread)
        micThread = null
        speakerThread = null
        val token = routeToken
        // Hat, kayıt ve çalma GERÇEKTEN kapandıktan sonra bırakılıyor: iş parçacıkları
        // AudioRecord/AudioTrack'i bir sonraki döngüde serbest bırakıyor, açık bir
        // görüşme kaydı dururken SCO temizlenirse Android hattı ayakta tutuyordu.
        thread(name = "airon-route-release") {
            // Bloklayan read()/write() bunlarla hemen dönüyor; release'i iş parçacıkları
            // kendi finally'lerinde yapıyor — burada release ETME, çifte release çökertir.
            runCatching { rec?.stop() }
            runCatching { out?.pause() }
            workers.forEach {
                runCatching { it.join(WORKER_JOIN_MS) }
                if (it.isAlive) Log.w(TAG, "${it.name} kapanmadı; ses yolu yine de bırakılıyor")
            }
            route.release(token)
        }
    }

    private fun level(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    companion object {
        /** Aıron'un sesi (0..1). AppSettings.voiceVolume'dan besleniyor. */
        @Volatile var volume = 0.6f

        /**
         * Kullanıcının sesinin son duyulduğu an (elapsedRealtime), oturumdan bağımsız.
         * Mesaj taslağının onayını doğrulamak için (ContactTools). Aıron konuşurken
         * mikrofon kapalı olduğundan bu an yalnız kullanıcı gerçekten konuştuğunda ilerler.
         */
        /**
         * Kullanıcının son GERÇEK girdisi: mikrofonda sesi ya da yazdığı mesaj (servis
         * yazılı mesajda bunu da günceller). Mesaj/Claude onay kapıları buna bakıyor.
         */
        @Volatile var lastUserVoiceAt = 0L

        /** Ayarlar'daki "Dene": konuşmayla aynı akışta (asistan sesi) kısa bir ton. */
        fun preview(vol: Float) {
            thread(name = "airon-volume-preview") {
                val n = OUT_RATE * 6 / 10
                val pcm = ByteArray(n * 2)
                for (i in 0 until n) {
                    val fade = minOf(1f, minOf(i, n - i) / (OUT_RATE * 0.03f))
                    val s = (kotlin.math.sin(2 * Math.PI * 440 * i / OUT_RATE) * 12000 * fade).toInt()
                    pcm[2 * i] = s.toByte()
                    pcm[2 * i + 1] = (s shr 8).toByte()
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size)
                    .build()
                try {
                    t.write(pcm, 0, pcm.size)
                    t.setVolume(vol)
                    t.play()
                    Thread.sleep(800)
                } catch (e: Exception) {
                    Log.w(TAG, "Ses denemesi çalınamadı", e)
                } finally {
                    runCatching { t.stop() }
                    t.release()
                }
            }
        }

        private const val TAG = "AironAudio"
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        /** 100 ms'lik kare: 1600 örnek × 2 bayt. */
        const val FRAME_BYTES = 3200
        /** Çalma bitince hoparlörün odadaki yankısı için kısa bekleme. */
        // write() tamponu doldurunca dönüyor, ses o anda henüz çalınmadı: kuyruk
        // AudioTrack tamponu (~2× minimum, yaklaşık 200 ms) + oda yankısını kapsamalı.
        private const val ECHO_TAIL_MS = 500L
        private const val BT_ECHO_TAIL_MS = 900L
        /** Mikrofon karesi 100 ms, hoparlör kuyruğu 200 ms bekliyor; bunun üstü takılma. */
        private const val WORKER_JOIN_MS = 1_000L
        /** Tepe seviye bunun üstündeyse konuşma sayılıyor (gürültü bastırıcı sessizliği sıfırlıyor). */
        private const val VOICE_LEVEL = 0.04f
    }
}
