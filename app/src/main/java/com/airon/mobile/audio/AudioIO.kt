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
    private val route = CarAudio(context)
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

    val isSpeaking: Boolean
        get() = writing || playQueue.isNotEmpty() || SystemClock.elapsedRealtime() - lastPlaybackAt < ECHO_TAIL_MS

    @SuppressLint("MissingPermission") // izin, servis başlamadan önce MainActivity'de alınıyor
    fun start() {
        if (running) return
        running = true
        // Araba/kulaklık bağlıysa konuşma oraya (mikrofon dahil); müzik kısılır.
        val handsFree = route.acquire()

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
                    .setUsage(if (handsFree) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
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

        thread(name = "airon-mic") {
            val buffer = ByteArray(FRAME_BYTES)
            try {
                rec.startRecording()
                while (running) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (isSpeaking) {
                        onMicLevel(0f)
                        continue
                    }
                    val lvl = level(buffer, read)
                    if (lvl >= VOICE_LEVEL) lastVoiceAt = SystemClock.elapsedRealtime()
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

        thread(name = "airon-speaker") {
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
        if (running) playQueue.offer(pcm24k)
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
        route.release()
        playQueue.clear()
        record = null
        track = null
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
        /** Tepe seviye bunun üstündeyse konuşma sayılıyor (gürültü bastırıcı sessizliği sıfırlıyor). */
        private const val VOICE_LEVEL = 0.04f
    }
}
