package com.airon.mobile.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.airon.mobile.audio.AudioIO
import kotlin.concurrent.thread

/**
 * "Aıron" dinleyicisi — çevrimdışı, Yunus'un sesiyle eğitilmiş openWakeWord modeli
 * (airon-wake projesi). Vosk denendi, "Aıron"u sözlüğünde bulamadığı için bırakıldı.
 *
 * Mikrofonun sahibi, oturum açılana kadar bu sınıf. Söz duyulunca kaydı KESMİYOR:
 * Gemini bağlanana kadar (~1 sn) söylenen "…annemi ara" kaybolmasın diye kareler
 * biriktiriliyor. `handOff()` kaydı bitirip söz öncesi 1,5 sn + sonrasını veriyor;
 * servis bunları Gemini'ye gönderip mikrofonu AudioIO'ya devrediyor.
 */
class WakeWordListener(
    private val models: Models,
    private val onWake: () -> Unit,
    /** Ayar için: son ~1 sn'nin en yüksek skoru (ekranda gösteriliyor). */
    private val onScore: (Float) -> Unit = {},
    /** Dinleyici hatayla kendiliğinden durdu (stop() çağrılmadan). */
    private val onFailed: (Throwable) -> Unit = {},
) {
    /** Model dosyaları bir kez okunuyor; dedektör her başlatmada yeniden kuruluyor (durum sıfır). */
    class Models(val mel: ByteArray, val embedding: ByteArray, val wake: ByteArray)

    @Volatile private var running = false
    @Volatile private var triggered = false
    private var worker: Thread? = null
    @Volatile private var record: AudioRecord? = null
    private val preRoll = ArrayDeque<ByteArray>()
    private val captured = ArrayList<ByteArray>()

    @SuppressLint("MissingPermission") // mikrofon izni olmadan servis bu modu açmıyor
    fun start() {
        if (running) return
        running = true
        triggered = false
        synchronized(this) { preRoll.clear(); captured.clear() }

        worker = thread(name = "airon-wake") {
            var detector: OwwDetector? = null
            val minIn = AudioRecord.getMinBufferSize(AudioIO.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                // VOICE_RECOGNITION bu Xiaomi'de (Android 12) sürekli sıfır örnek verdi (2026-09-16 ölçümü);
                // eğitim kayıtları da VOICE_COMMUNICATION ile alındı — model bu kaynağın sesini tanıyor.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioIO.IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, FRAME * 4),
            )
            record = rec
            try {
                val det = OwwDetector(models.mel, models.embedding, models.wake).also { detector = it }
                rec.startRecording()
                val buffer = ByteArray(FRAME)
                var above = 0
                var peakScore = 0f
                var chunks = 0
                while (running) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read < 0) error("Mikrofon okuma hatası ($read)")
                    if (read == 0) continue
                    val frame = buffer.copyOf(read)
                    synchronized(this) {
                        if (triggered) {
                            if (captured.size < MAX_CAPTURE_FRAMES) captured.add(frame)
                        } else {
                            preRoll.addLast(frame)
                            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        }
                    }
                    if (triggered) continue

                    det.accept(frame, read) { score ->
                        peakScore = maxOf(peakScore, score)
                        if (++chunks % SCORE_REPORT_CHUNKS == 0) {
                            onScore(peakScore)
                            if (peakScore >= LOG_SCORE) Log.d(TAG, "skor tepe=%.3f".format(peakScore))
                            peakScore = 0f
                        }
                        above = if (score >= THRESHOLD) above + 1 else 0
                        if (!triggered && above >= CONSECUTIVE) {
                            Log.i(TAG, "UYANDI: skor=%.3f".format(score))
                            triggered = true
                            onWake()
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Uyandırma dinleyicisi hatası", e)
                if (running) onFailed(e)
            } finally {
                running = false
                record = null
                runCatching { rec.stop() }
                rec.release()
                detector?.close()
            }
        }
    }

    /** Kaydı durdurur; `keepAudio` ise söz öncesi + sonrası kareleri döner. */
    fun handOff(keepAudio: Boolean): List<ByteArray> {
        stop()
        return synchronized(this) {
            val frames = if (keepAudio && triggered) preRoll.toList() + captured else emptyList()
            preRoll.clear()
            captured.clear()
            frames
        }
    }

    fun stop() {
        running = false
        // Bekleyen read() hemen dönsün.
        runCatching { record?.stop() }
        // AudioRecord bırakılmadan AudioIO aynı mikrofonu açmasın. Model kurulumu sürüyorsa
        // birkaç yüz ms alabiliyor; bekleme sınırı ANR'ye yol açmayacak kadar kısa.
        worker?.join(3000)
        if (worker?.isAlive == true) Log.w(TAG, "Dinleyici 3 sn'de kapanmadı")
        worker = null
    }

    companion object {
        private const val TAG = "AironWake"
        private const val FRAME = AudioIO.FRAME_BYTES // 100 ms
        private const val PRE_ROLL_FRAMES = 15 // 1,5 sn: "Aıron" kısmı dahil
        private const val MAX_CAPTURE_FRAMES = 100 // bağlantı takılırsa 10 sn'den fazlası tutulmaz

        /** airon_r3 (2026-09-16): 0,5 + 2 ardışık → İngilizce akışta ~1,9 yanlış/sa, sınama geri çağırma %63–75, benzer kelime %0. */
        const val THRESHOLD = 0.5f
        /** Art arda kaç 80 ms parçası eşiği geçmeli — tek parçalık sıçramalar yanlış uyanma. */
        const val CONSECUTIVE = 2
        private const val SCORE_REPORT_CHUNKS = 12 // ~1 sn
        private const val LOG_SCORE = 0.2f

        fun loadModels(context: Context): Models {
            fun asset(name: String) = context.assets.open("wake/$name").use { it.readBytes() }
            return Models(asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("airon.onnx"))
        }
    }
}
