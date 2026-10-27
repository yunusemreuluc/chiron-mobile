package com.chiron.mobile.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Yalnızca adb'den tetiklenen doğrulama: telefondaki eğitim kayıtlarını
 * OwwDetector'dan geçirip dosya başına en yüksek skoru loglar. PC'deki Python
 * referansıyla (chiron-wake/device_parity.py) karşılaştırılıyor.
 *
 *   adb shell am broadcast -a com.chiron.mobile.WAKE_TEST -n com.chiron.mobile/.wake.WakeTestReceiver
 *   adb logcat -s ChironWakeTest
 *
 * Manifestte DUMP iznine bağlı — bu izni yalnız sistem ve adb kabuğu taşıyor.
 */
class WakeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "chiron-wake-test") {
            try {
                val models = WakeWordListener.loadModels(app)
                val root = File(app.getExternalFilesDir(null), "wake-samples")
                val kinds = intent.getStringExtra("kinds")?.split(',') ?: listOf("positive", "negative")
                OwwDetector(models.mel, models.embedding, models.wake).use { det ->
                    for (kind in kinds) {
                        val wavs = File(root, kind).listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }
                        for (f in wavs) {
                            det.reset()
                            // Python referansıyla aynı: 2 sn sessizlik + kayıt, 1280 örneklik parçalar.
                            val pcm = ByteArray(PAD_BYTES) + readPcm(f)
                            var best = 0f
                            var i = 0
                            while (i + CHUNK_BYTES <= pcm.size) {
                                det.accept(pcm.copyOfRange(i, i + CHUNK_BYTES), CHUNK_BYTES) { best = maxOf(best, it) }
                                i += CHUNK_BYTES
                            }
                            Log.i(TAG, "SKOR $kind/${f.name} %.4f".format(java.util.Locale.ROOT, best))
                        }
                    }
                }
                Log.i(TAG, "BİTTİ")
            } catch (e: Exception) {
                Log.e(TAG, "Test hatası", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun readPcm(f: File): ByteArray = RandomAccessFile(f, "r").use { r ->
        // SampleRecorder'ın yazdığı sabit 44 baytlık başlık.
        val bytes = ByteArray((r.length() - 44).toInt())
        r.seek(44)
        r.readFully(bytes)
        bytes
    }

    companion object {
        private const val TAG = "ChironWakeTest"
        private const val CHUNK_BYTES = OwwDetector.CHUNK * 2
        private const val PAD_BYTES = OwwDetector.SR * 2 * 2
    }
}
