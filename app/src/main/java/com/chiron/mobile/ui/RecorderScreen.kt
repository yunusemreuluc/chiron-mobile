package com.chiron.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chiron.mobile.AppSettings
import com.chiron.mobile.audio.AudioIO
import com.chiron.mobile.service.AssistantBus
import com.chiron.mobile.service.AssistantBus.Phase
import com.chiron.mobile.service.AssistantService
import com.chiron.mobile.wake.SampleCatalog
import com.chiron.mobile.wake.SampleCatalog.Kind
import com.chiron.mobile.wake.SampleRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val Muted = Color(0x9ED6E2F2)
private val Faint = Color(0x57CAD8EA)
private val Glass = Color(0x0FFFFFFF)
private val Hairline = Color(0x17FFFFFF)
private val Warn = Color(0xFFFF8F8F)
private val RecordRed = Color(0xFFFF5A6A)

/** Bu tepe değerin altı "duyulmadı" sayılıyor (~-30 dBFS). */
private const val MIN_PEAK = 1000
private const val CLIP_PEAK = 32000

/**
 * Uyandırma sözü eğitim kaydı. Bekleme modu mikrofonu tutuyorsa ekran açılırken
 * kapatılıyor, çıkarken geri açılıyor. Oturum sürüyorsa kayıt başlamıyor.
 */
@Composable
fun RecorderScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { SampleRecorder(context) }
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val state by AssistantBus.state.collectAsStateWithLifecycle()

    var kind by remember { mutableStateOf(Kind.POSITIVE) }
    var counts by remember { mutableStateOf(Kind.entries.associateWith { recorder.count(it) }) }
    var recording by remember { mutableStateOf(false) }
    // Kayıt iş parçacığı okuyor; ekran kapanınca da (onDispose) çekilebilmeli.
    val stopRequested = remember { AtomicBoolean(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var elapsedMs by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // metin, uyarı mı
    var lastTake by remember { mutableStateOf<Pair<File, ByteArray>?>(null) }
    var playback by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(false) }

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Bekleme modu açıksa mikrofonu bırakması için kapat; çıkışta eski hâline döndür.
    // wakePausedForRecording: süreç bu arada ölürse ana ekran dinlemeyi açılışta geri getiriyor.
    val wakeWasOn = remember { settings.wakeEnabled || settings.wakePausedForRecording }
    DisposableEffect(Unit) {
        if (wakeWasOn) {
            settings.wakePausedForRecording = true
            AssistantService.setWake(context, false)
        }
        onDispose {
            stopRequested.set(true)
            playback?.cancel()
            if (wakeWasOn) {
                val app = context.applicationContext
                // Süren kayıt AudioRecord'u bırakmadan dinleyici aynı mikrofonu açmasın.
                thread(name = "chiron-rec-handoff") {
                    synchronized(SampleRecorder.MIC_LOCK) {}
                    settings.wakePausedForRecording = false
                    // Arka plandan mikrofon servisi başlatılamazsa ayarı yaz; ana ekran açılışta getirir.
                    runCatching { AssistantService.setWake(app, true) }.onFailure { settings.wakeEnabled = true }
                }
            }
        }
    }

    // Geri: kayıt sürüyorsa önce durdur (kayıt kaydedilsin), değilse ana ekrana dön.
    BackHandler { if (recording) stopRequested.set(true) else onClose() }

    val busy = state.phase != Phase.OFF
    val index = counts.getValue(kind)
    val prompt = SampleCatalog.prompt(kind, index)
    val shortTake = kind.maxMs <= 5000

    fun refreshCounts() {
        counts = Kind.entries.associateWith { recorder.count(it) }
    }

    fun startRecording() {
        val takeKind = kind
        val takePrompt = prompt
        recording = true
        stopRequested.set(false)
        message = null
        elapsedMs = 0
        scope.launch {
            val started = System.currentTimeMillis()
            val ticker = launch {
                while (true) {
                    elapsedMs = (System.currentTimeMillis() - started).toInt()
                    delay(100)
                }
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    recorder.record(takeKind.maxMs, { stopRequested.get() }) { level = it }
                }
            }
            ticker.cancel()
            // `recording` kaydetme bitene kadar açık: arada "Son kaydı sil" basılıp yanlış dosya gitmesin.
            try {
                val pcm = result.getOrElse {
                    if (it is CancellationException) throw it
                    message = "Kayıt açılamadı: ${it.message}" to true
                    return@launch
                }
                val peak = SampleRecorder.peak(pcm, pcm.size)
                val speechKind = takeKind == Kind.POSITIVE || takeKind == Kind.NEGATIVE
                when {
                    pcm.size < AudioIO.IN_RATE -> message = "Çok kısa, kaydedilmedi." to true
                    speechKind && peak < MIN_PEAK -> message = "Ses duyulmadı (tepe $peak), kaydedilmedi. Tekrar dene." to true
                    else -> {
                        val saved = runCatching { withContext(Dispatchers.IO) { recorder.save(takeKind, takePrompt, pcm) } }
                        val file = saved.getOrElse {
                            if (it is CancellationException) throw it
                            message = "Kaydedilemedi: ${it.message}" to true
                            return@launch
                        }
                        lastTake = file to pcm
                        refreshCounts()
                        message = if (peak >= CLIP_PEAK) {
                            "Kaydedildi ama ses patlamış olabilir — biraz uzaktan tekrar dene." to true
                        } else {
                            "Kaydedildi (tepe %${peak * 100 / 32768})." to false
                        }
                    }
                }
            } finally {
                recording = false
            }
        }
    }

    fun startPlayback(pcm: ByteArray) {
        playback?.cancel()
        playing = true
        playback = scope.launch {
            val self = currentCoroutineContext()[Job]
            try {
                withContext(Dispatchers.IO) { play(pcm) }
            } finally {
                // Yeni bir çalma bunu iptal ettiyse bayrak onun.
                if (playback == null || playback === self) playing = false
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Uyandırma sözü kaydı", color = Color(0xF2EDF2FA), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, enabled = !recording) { Text("Kapat", color = CoreColors.Ice) }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k ->
                val selected = k == kind
                val done = counts.getValue(k) >= k.target
                Text(
                    "${k.title} ${counts.getValue(k)}/${k.target}${if (done) " ✓" else ""}",
                    color = if (selected) CoreColors.Background else Muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) CoreColors.Ice else Glass)
                        .border(1.dp, if (selected) Color.Transparent else Hairline, RoundedCornerShape(50))
                        .clickable(enabled = !recording) { kind = k; message = null }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xBC0A0E16))
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (kind) {
                Kind.POSITIVE -> {
                    Text("Söyle", color = Faint, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text("Chiron", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    Text(prompt, color = CoreColors.Ice, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
                Kind.NEGATIVE -> {
                    Text("Söyle (Chiron DEĞİL)", color = Faint, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(prompt, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Doğal, normal sesle", color = Muted, fontSize = 14.sp)
                }
                Kind.SPEECH, Kind.NOISE -> {
                    Text(if (kind == Kind.SPEECH) "30 saniye konuş" else "30 saniye kaydet", color = Faint, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(prompt, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
                }
            }
        }

        // Seviye çubuğu + süre
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Glass)) {
                Box(
                    Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(if (level > 0.97f) Warn else CoreColors.Ice),
                )
            }
            Text(
                when {
                    recording -> "Kayıtta — %.1f / %.1f sn".format(elapsedMs / 1000f, kind.maxMs / 1000f)
                    else -> " "
                },
                color = Muted, fontSize = 13.sp,
            )
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Hoparlörden çalan örnek yeni kayda girmesin.
            val canStart = micGranted && !busy && !playing
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(if (recording) RecordRed else Glass)
                    .border(2.dp, if (recording) Color.Transparent else RecordRed.copy(alpha = if (canStart) 0.8f else 0.25f), CircleShape)
                    .clickable(enabled = recording || canStart) {
                        if (recording) stopRequested.set(true) else startRecording()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                    if (recording) "Durdur" else "Kaydet",
                    tint = if (recording) Color.White else RecordRed,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            when {
                !micGranted -> "Mikrofon izni yok — ana ekrandan ver."
                busy -> "Chiron mikrofonu kullanıyor — oturum bitince kayıt açılır."
                recording && shortTake -> "Şimdi söyle — ${kind.maxMs / 1000f} sn sonra kendiliğinden biter."
                recording -> "Bitirmek için dokun ya da süre dolsun."
                shortTake -> "Dokun, sonra söyle. Kayıt kendiliğinden biter."
                else -> "Dokun ve başla."
            },
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )

        message?.let { (text, warn) ->
            Text(text, color = if (warn) Warn else CoreColors.Ice, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        lastTake?.let { (file, pcm) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(enabled = !recording, onClick = { if (playing) playback?.cancel() else startPlayback(pcm) }) {
                    Text(if (playing) "Durdur" else "Son kaydı dinle", color = CoreColors.Ice)
                }
                TextButton(enabled = !recording, onClick = {
                    playback?.cancel()
                    message = if (recorder.delete(file)) "Son kayıt silindi." to false else "Silinemedi." to true
                    lastTake = null
                    refreshCounts()
                }) { Text("Son kaydı sil", color = Warn) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Hedef: 40 \"Chiron\", 40 benzer kelime, 6 serbest konuşma, 4 ortam sesi. " +
                "Farklı yerlerde ve zamanlarda kaydetmek modeli güçlendirir.\n\n" +
                "PC'ye çekmek: adb pull /sdcard/Android/data/com.chiron.mobile/files/wake-samples",
            color = Faint, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }

    // Ekrandan çıkılırken kayıt sürüyorsa durur (onDispose); burada yalnız oturum
    // başlarsa süren kaydı kesiyoruz ki mikrofon çakışmasın.
    LaunchedEffect(busy) { if (busy && recording) stopRequested.set(true) }
}

/** İptal edilebilir: korutin iptal edilince çalma hemen durur. */
private suspend fun play(pcm: ByteArray) {
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioIO.IN_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(pcm.size)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        val frames = pcm.size / 2
        while (currentCoroutineContext().isActive && track.playbackHeadPosition < frames) delay(50)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}
