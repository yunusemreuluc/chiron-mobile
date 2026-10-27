package com.chiron.mobile.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.chiron.mobile.audio.AudioIO
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Özel uyandırma modeli (openWakeWord) için eğitim kaydı.
 *
 * Kayıt, bekleme modundaki `WakeWordListener` ile AYNI koşulda alınıyor:
 * VOICE_COMMUNICATION kaynağı, 16 kHz mono, gürültü bastırıcı elle açılmadan.
 * Model telefonda neyi duyacaksa onunla eğitilmeli.
 *
 * Dosyalar uygulamanın dış dizininde: PC'den
 * `adb pull /sdcard/Android/data/com.chiron.mobile/files/wake-samples` ile çekiliyor.
 * Her kayıt `manifest.tsv`'ye (dosya, tür, yönerge, tepe seviye) bir satır ekliyor.
 */
class SampleRecorder(context: Context) {

    val root: File = File(context.getExternalFilesDir(null), "wake-samples")
    private val manifest get() = File(root, "manifest.tsv")

    fun count(kind: SampleCatalog.Kind): Int =
        File(root, kind.dir).listFiles { f -> f.extension == "wav" }?.size ?: 0

    /**
     * Engelleyici — IO iş parçacığında çağır. `maxMs` dolunca ya da `shouldStop()`
     * doğru dönünce biter. Dönen PCM 16-bit little-endian.
     */
    @SuppressLint("MissingPermission") // ekran mikrofon izni yoksa kaydı açmıyor
    fun record(maxMs: Int, shouldStop: () -> Boolean, onLevel: (Float) -> Unit): ByteArray = synchronized(MIC_LOCK) {
        val minIn = AudioRecord.getMinBufferSize(AudioIO.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioIO.IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, CHUNK * 4),
        )
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon açılamadı" }
            val total = AudioIO.IN_RATE * 2 * maxMs / 1000
            val out = ByteArray(total)
            var filled = 0
            rec.startRecording()
            val buffer = ByteArray(CHUNK)
            while (filled < total && !shouldStop()) {
                val read = rec.read(buffer, 0, minOf(CHUNK, total - filled))
                // Negatif dönüş (ör. ERROR_DEAD_OBJECT) kalıcı; beklemek kaydı sonsuz açık tutar.
                check(read >= 0) { "Mikrofon okuma hatası ($read)" }
                if (read == 0) continue
                buffer.copyInto(out, filled, 0, read)
                filled += read
                onLevel(peak(buffer, read) / 32768f)
            }
            out.copyOf(filled - filled % 2)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            onLevel(0f)
        }
    }

    fun save(kind: SampleCatalog.Kind, prompt: String, pcm: ByteArray): File {
        val dir = File(root, kind.dir).apply { mkdirs() }
        val file = generateSequence(count(kind) + 1) { it + 1 }
            .map { File(dir, "${kind.dir}_%04d.wav".format(it)) }
            .first { !it.exists() }
        try {
            writeWav(file, pcm)
            manifest.appendText("${entry(file)}\t${kind.dir}\t$prompt\t${peak(pcm, pcm.size)}\n")
        } catch (e: Exception) {
            file.delete() // yarım WAV eğitime girmesin
            throw e
        }
        return file
    }

    /** Kötü çıkan kaydı manifest satırıyla birlikte siler — ad yeniden kullanılınca eski satır kalmasın. */
    fun delete(file: File): Boolean {
        if (!file.delete()) return false
        if (manifest.exists()) {
            val key = entry(file) + "\t"
            manifest.writeText(manifest.readLines().filterNot { it.startsWith(key) }.joinToString("") { it + "\n" })
        }
        return true
    }

    private fun entry(file: File) = "${file.parentFile?.name}/${file.name}"

    private fun writeWav(file: File, pcm: ByteArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val rate = AudioIO.IN_RATE
            fun int(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.writeBytes("RIFF"); int(36 + pcm.size); f.writeBytes("WAVE")
            f.writeBytes("fmt "); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            f.writeBytes("data"); int(pcm.size)
            f.write(pcm)
        }
    }

    companion object {
        private const val CHUNK = 1600 // 50 ms — seviye çubuğu akıcı görünsün

        /** Kayıt sürerken tutuluyor; ekran kapanırken bekleme modu ancak bu bırakılınca açılıyor. */
        val MIC_LOCK = Any()

        fun peak(pcm: ByteArray, length: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                peak = maxOf(peak, abs(s))
                i += 2
            }
            return peak
        }
    }
}
