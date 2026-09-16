package com.airon.mobile.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * openWakeWord'ün akış hattının Kotlin karşılığı (openwakeword/utils.py AudioFeatures +
 * model.py predict). Üç ONNX modeli:
 *   melspectrogram.onnx  : ham ses (float, int16 ölçeğinde) → (1, 1, t, 32) mel çerçevesi
 *   embedding_model.onnx : 76 mel çerçevesi → 96 boyutlu gömme (Google speech_embedding)
 *   airon.onnx           : son 16 gömme → 0..1 "Aıron" skoru (bizim eğittiğimiz)
 *
 * Her 1280 örnekte (80 ms) bir: son 1760 örneğin mel'i (8 çerçeve) tampona eklenir,
 * son 76 mel çerçevesinden bir gömme çıkar, son 16 gömme sınıflandırıcıya gider.
 * Python sürümüyle aynı adımlar — eğitimde kullanılan özelliklerle birebir olsun diye.
 */
class OwwDetector(melModel: ByteArray, embeddingModel: ByteArray, wakeModel: ByteArray) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions()
    private val opened = ArrayList<AutoCloseable>()
    private val mel: OrtSession
    private val embedding: OrtSession
    private val wake: OrtSession
    private val wakeInput: String

    private val raw = ShortArray(RAW_KEEP)
    private var rawFilled = 0
    private val pending = ShortArray(CHUNK)
    private var pendingFilled = 0
    private val melBuffer = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var frames = 0

    init {
        // Kurulum yarıda kalırsa açılmış yerel kaynaklar sızmasın (close() çağrılamaz).
        try {
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)
            mel = env.createSession(melModel, opts).also { opened += it }
            embedding = env.createSession(embeddingModel, opts).also { opened += it }
            wake = env.createSession(wakeModel, opts).also { opened += it }
            wakeInput = wake.inputNames.first()
            reset()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Tamponları başa al. openWakeWord da gömme tamponunu rastgele sesle dolduruyor. */
    fun reset() {
        rawFilled = 0
        pendingFilled = 0
        frames = 0
        melBuffer.clear()
        repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        val noise = ShortArray(SR * 4) { (Random.nextInt(-1000, 1000)).toShort() }
        val spec = melOf(noise)
        var i = 0
        while (i + MEL_WINDOW <= spec.size) {
            features.addLast(embed(spec.subList(i, i + MEL_WINDOW)))
            i += MEL_STEP
        }
        while (features.size > FEATURE_KEEP) features.removeFirst()
    }

    /**
     * PCM 16-bit little-endian baytları besle; her tamamlanan 80 ms parçası için skoru
     * `onScore`'a verir. İlk 5 parça skor 0 (openWakeWord'ün ısınma kuralı).
     */
    fun accept(pcm: ByteArray, length: Int, onScore: (Float) -> Unit) {
        var i = 0
        while (i + 1 < length) {
            pending[pendingFilled++] = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            i += 2
            if (pendingFilled == CHUNK) {
                pendingFilled = 0
                onScore(processChunk())
            }
        }
    }

    private fun processChunk(): Float {
        // raw: son RAW_KEEP örnek (kaydırmalı).
        val keep = RAW_KEEP - CHUNK
        System.arraycopy(raw, CHUNK, raw, 0, keep)
        System.arraycopy(pending, 0, raw, keep, CHUNK)
        rawFilled = minOf(RAW_KEEP, rawFilled + CHUNK)
        val window = if (rawFilled < RAW_KEEP) raw.copyOfRange(RAW_KEEP - rawFilled, RAW_KEEP) else raw

        melOf(window).forEach { melBuffer.addLast(it) }
        while (melBuffer.size > MEL_KEEP) melBuffer.removeFirst()
        if (melBuffer.size >= MEL_WINDOW) {
            features.addLast(embed(melBuffer.subList(melBuffer.size - MEL_WINDOW, melBuffer.size)))
            while (features.size > FEATURE_KEEP) features.removeFirst()
        }

        frames++
        if (frames <= WARMUP_FRAMES) return 0f
        return classify()
    }

    private fun melOf(samples: ShortArray): List<FloatArray> {
        val input = FloatArray(samples.size) { samples[it].toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, samples.size.toLong())).use { t ->
            mel.run(mapOf("input" to t)).use { out ->
                // Çalışma anındaki şekil (1, 1, t, 32) — zaman ekseni üçüncü boyut.
                @Suppress("UNCHECKED_CAST")
                val v = out[0].value as Array<Array<Array<FloatArray>>>
                check(v.size == 1 && v[0].size == 1) { "Beklenmeyen mel şekli" }
                return v[0][0].map { row -> FloatArray(MEL_BINS) { k -> row[k] / 10f + 2f } }
            }
        }
    }

    private fun embed(window: List<FloatArray>): FloatArray {
        val buf = FloatBuffer.allocate(MEL_WINDOW * MEL_BINS)
        window.forEach { buf.put(it) }
        buf.flip()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
            embedding.run(mapOf("input_1" to t)).use { out ->
                // (1, 1, 1, 96)
                @Suppress("UNCHECKED_CAST")
                val v = out[0].value as Array<Array<Array<FloatArray>>>
                return v[0][0][0].copyOf()
            }
        }
    }

    private fun classify(): Float {
        val buf = FloatBuffer.allocate(WAKE_FRAMES * EMB_DIM)
        features.toList().takeLast(WAKE_FRAMES).forEach { buf.put(it) }
        buf.flip()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, WAKE_FRAMES.toLong(), EMB_DIM.toLong())).use { t ->
            wake.run(mapOf(wakeInput to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = out[0].value as Array<FloatArray>
                return v[0][0]
            }
        }
    }

    override fun close() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
        runCatching { opts.close() }
    }

    companion object {
        const val SR = 16000
        const val CHUNK = 1280 // 80 ms
        private const val RAW_KEEP = CHUNK + 160 * 3 // 1760 örnek → 8 mel çerçevesi
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val MEL_STEP = 8
        private const val MEL_KEEP = 970 // openWakeWord melspectrogram_max_len (10 sn)
        private const val EMB_DIM = 96
        private const val WAKE_FRAMES = 16
        private const val FEATURE_KEEP = 120 // 10 sn
        private const val WARMUP_FRAMES = 5
    }
}
