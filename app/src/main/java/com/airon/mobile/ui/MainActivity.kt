package com.airon.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airon.mobile.AppSettings
import com.airon.mobile.audio.AudioIO
import com.airon.mobile.service.AssistantBus
import com.airon.mobile.service.AssistantBus.Phase
import com.airon.mobile.service.AssistantService
import com.airon.mobile.tools.BackgroundLaunch
import com.airon.mobile.tools.ClaudeJobs
import kotlinx.coroutines.delay
import com.airon.mobile.tools.DesktopBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        BackgroundLaunch.activityVisible = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleClaudeIntent(intent)
    }

    /** "Claude bitti / onay bekliyor" bildirimi: konuşmayı açıp sonucu okut. */
    private fun handleClaudeIntent(intent: Intent?) {
        if (intent?.action != ACTION_CLAUDE_RESULT) return
        intent.action = null // döndürmede tekrar tetiklenmesin
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AssistantService.showClaudeResult(this)
        }
    }

    override fun onPause() {
        BackgroundLaunch.activityVisible = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleClaudeIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = CoreColors.Ice, background = CoreColors.Background)) {
                AironScreen()
            }
        }
    }

    companion object {
        const val ACTION_CLAUDE_RESULT = "com.airon.mobile.CLAUDE_RESULT"
    }
}

private val Muted = Color(0x9ED6E2F2)
private val Faint = Color(0x57CAD8EA)
private val Glass = Color(0x0FFFFFFF)
private val Hairline = Color(0x17FFFFFF)

@Composable
private fun AironScreen() {
    val context = LocalContext.current
    val state by AssistantBus.state.collectAsStateWithLifecycle()
    val mic by AssistantBus.micLevel.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showRecorder by remember { mutableStateOf(false) }
    // Ayarlardayken geri tuşu uygulamadan çıkmasın, ana ekrana dönsün.
    // (Kayıt ekranı kendi geri davranışını RecorderScreen içinde tanımlıyor.)
    BackHandler(enabled = showSettings && !showRecorder) { showSettings = false }

    // Özel izinler Ayarlar ekranında açılıyor; geri dönüldüğünde listeyi tazele.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missingRuntime = remember(refresh) { Permissions.missingRuntime(context) }
    val missingSpecial = remember(refresh) { Permissions.special.filterNot { it.granted(context) } }
    val settings = remember { AppSettings(context) }
    // Bildirimdeki "Dinlemeyi kapat" da değiştirebiliyor — ekrana dönünce tazele.
    var wakeOn by remember(refresh) { mutableStateOf(settings.wakeEnabled) }
    val micGranted = Permissions.runtime.first() !in missingRuntime

    // Süren Claude işinin canlı şeridi — yalnız ekran açıkken, PC oturumu varken yoklanır.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val claude = ClaudeJobs(context, settings)
            while (true) {
                val line = if (settings.pcSession.isBlank()) null else withContext(Dispatchers.IO) { claude.activeLine() }
                AssistantBus.claudeStatus(line)
                delay(if (line != null) 5_000L else 20_000L)
            }
        }
    }

    // "Aıron" dinlemesi açık bırakıldıysa uygulama her açılışta dinlemeyi geri getiriyor
    // (Android arka plandan mikrofon servisi başlatmaya izin vermiyor).
    LaunchedEffect(micGranted) {
        // Kayıt ekranı açıkken süreç öldüyse dinleme geçici kapalı kalmıştı.
        if (settings.wakePausedForRecording) {
            settings.wakePausedForRecording = false
            settings.wakeEnabled = true
            wakeOn = true
        }
        if (micGranted && settings.wakeEnabled && AssistantBus.state.value.phase == Phase.OFF) {
            AssistantService.setWake(context, true)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CoreColors.Background)
            .background(Brush.verticalGradient(listOf(Color(0x1A7FB2FF), Color.Transparent), endY = 900f)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aıron", color = Color(0xF2EDF2FA), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        phaseLabel(state.phase, state.activeTool) + if (state.typing && state.phase != Phase.OFF) " · yazışma, ses kapalı" else "",
                        color = Muted, fontSize = 13.sp,
                    )
                }
                WakeToggle(on = wakeOn, enabled = micGranted && !showRecorder) {
                    wakeOn = !wakeOn
                    AssistantService.setWake(context, wakeOn)
                }
                IconButton(enabled = !showRecorder, onClick = { showSettings = !showSettings }) {
                    Icon(Icons.Rounded.Settings, "Ayarlar", tint = Muted)
                }
            }

            AnimatedVisibility(state.claudeStatus != null && !showSettings && !showRecorder, enter = fadeIn(), exit = fadeOut()) {
                ClaudeStrip(state.claudeStatus.orEmpty())
            }

            if (showRecorder) {
                RecorderScreen(onClose = { showRecorder = false })
            } else if (showSettings) {
                SettingsPanel(
                    onClose = { showSettings = false },
                    onRecorder = { showSettings = false; showRecorder = true },
                )
            } else {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Orb(state.phase, mic, Modifier.size(230.dp))
                }
                if (state.phase == Phase.WAITING) {
                    val score by AssistantBus.wakeScore.collectAsStateWithLifecycle()
                    Text(
                        "Aıron skoru: %.2f  (eşik %.2f)".format(score, com.airon.mobile.wake.WakeWordListener.THRESHOLD),
                        color = Faint, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                if (missingRuntime.isNotEmpty() || missingSpecial.isNotEmpty()) {
                    SetupCard(
                        missingRuntime = missingRuntime.size,
                        special = missingSpecial,
                        onRuntime = { permissionLauncher.launch(Permissions.runtime) },
                        onSpecial = { context.startActivity(it.intent(context)) },
                    )
                }

                Transcript(state.lines, Modifier.weight(1f))

                AnimatedVisibility(state.notice != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        state.notice.orEmpty(), color = Color(0xFFFF8F8F), fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }

                val inSession = state.phase != Phase.OFF && state.phase != Phase.WAITING
                Composer(
                    // Yazışma modunda mikrofon "kapalı" görünür: dokununca sesli moda döner.
                    micActive = inSession && !state.typing,
                    enabled = micGranted,
                    onSend = { AssistantService.sendText(context, it) },
                    onMic = {
                        when {
                            inSession && state.typing -> AssistantService.voiceMode(context)
                            inSession -> AssistantService.stop(context)
                            else -> AssistantService.start(context)
                        }
                    },
                )
            }
        }
    }
}

private fun phaseLabel(phase: Phase, tool: String?) = when (phase) {
    Phase.OFF -> "Kapalı — mikrofona dokun"
    Phase.WAITING -> "\"Aıron\" de ya da mikrofona dokun"
    Phase.CONNECTING -> "Bağlanıyor"
    Phase.LISTENING -> "Dinliyor"
    Phase.THINKING -> "Düşünüyor"
    Phase.SPEAKING -> "Konuşuyor"
    Phase.WORKING -> TOOL_LABELS[tool] ?: "Çalışıyor"
}

private val TOOL_LABELS = mapOf(
    "call_contact" to "Arıyor",
    "send_message" to "Mesaj gönderiyor",
    "control_device" to "Telefonu ayarlıyor",
    "manage_device_audio" to "Sesi ayarlıyor",
    "get_recent_notifications" to "Bildirimleri okuyor",
    "get_device_health" to "Pile bakıyor",
    "query_desktop_airon" to "PC'ye soruyor",
    "send_desktop_command" to "PC'ye komut gönderiyor",
    "delegate_to_claude" to "Claude'a iş veriyor",
    "claude_job_status" to "Claude'a bakıyor",
    "cancel_claude_job" to "Claude işini durduruyor",
    "save_quick_note" to "Not alıyor",
    "remove_feature_request" to "İsteği kaldırıyor",
    "report_bug" to "Hatayı kaydediyor",
    "get_pc_status" to "PC'ye bakıyor",
)

@Composable
private fun Transcript(lines: List<AssistantBus.Line>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            if (line.claude) {
                ClaudeBubble(line.text)
                return@items
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                Text(
                    line.text,
                    color = if (line.fromUser) Color(0xF2EDF2FA) else Color(0xFFDCE8FA),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .padding(start = if (line.fromUser) 48.dp else 0.dp, end = if (line.fromUser) 0.dp else 32.dp)
                        .clip(RoundedCornerShape(if (line.fromUser) 20.dp else 6.dp, 20.dp, if (line.fromUser) 6.dp else 20.dp, 20.dp))
                        .background(if (line.fromUser) Color(0x13FFFFFF) else Color(0xBC0A0E16))
                        .border(1.dp, Hairline, RoundedCornerShape(if (line.fromUser) 20.dp else 6.dp, 20.dp, if (line.fromUser) 6.dp else 20.dp, 20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun WakeToggle(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        if (on) "Aıron: açık" else "Aıron: kapalı",
        color = if (on) CoreColors.Background else Muted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) CoreColors.Ice else Glass)
            .border(1.dp, if (on) Color.Transparent else Hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** PC'deki Claude'un rengi — Ajan Ofisi'ndeki "Şef" masasıyla aynı. */
private val ClaudeTint = Color(0xFFFFB38A)

@Composable
private fun ClaudeStrip(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14FFB38A))
            .border(1.dp, Color(0x33FFB38A), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(ClaudeTint))
        Spacer(Modifier.size(8.dp))
        Text(text, color = ClaudeTint, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ClaudeBubble(text: String) {
    val title = text.substringBefore('\n')
    val body = text.substringAfter('\n', "")
    val shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
    Column(
        Modifier
            .padding(end = 24.dp)
            .clip(shape)
            .background(Color(0x1AFFB38A))
            .border(1.dp, Color(0x40FFB38A), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, color = ClaudeTint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (body.isNotBlank()) Text(body, color = Color(0xFFF2E6DE), fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** Alt çubuk: yazı kutusu + gönder; kutu boşken mikrofon. */
@Composable
private fun Composer(micActive: Boolean, enabled: Boolean, onSend: (String) -> Unit, onMic: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(4000) },
            placeholder = { Text("Aıron'a yaz…", color = Faint) },
            enabled = enabled,
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0x597FB2FF),
                unfocusedBorderColor = Hairline,
                focusedContainerColor = Glass,
                unfocusedContainerColor = Glass,
                focusedTextColor = Color(0xF2EDF2FA),
                unfocusedTextColor = Color(0xF2EDF2FA),
                cursorColor = CoreColors.Ice,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(10.dp))
        val hasText = text.isNotBlank()
        val active = micActive && !hasText
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active || hasText) CoreColors.Ice else Glass)
                .border(1.dp, if (active || hasText) Color.Transparent else Color(0x597FB2FF), CircleShape)
                .clickable(enabled = enabled) { if (hasText) send() else onMic() },
            contentAlignment = Alignment.Center,
        ) {
            val (icon, label) = when {
                hasText -> Icons.AutoMirrored.Rounded.Send to "Gönder"
                active -> Icons.Rounded.Stop to "Oturumu bitir"
                else -> Icons.Rounded.Mic to "Konuş"
            }
            Icon(
                icon, label,
                tint = if (active || hasText) CoreColors.Background else CoreColors.Ice,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun MicButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(if (active) CoreColors.Ice else Glass)
                .border(1.dp, if (active) Color.Transparent else Color(0x597FB2FF), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (active) Icons.Rounded.Stop else Icons.Rounded.Mic,
                if (active) "Oturumu bitir" else "Konuş",
                tint = if (active) CoreColors.Background else CoreColors.Ice,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun SetupCard(
    missingRuntime: Int,
    special: List<Permissions.Special>,
    onRuntime: () -> Unit,
    onSpecial: (Permissions.Special) -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xBC0A0E16))
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("KURULUM", color = Faint, fontSize = 10.sp, letterSpacing = 2.sp)
        if (missingRuntime > 0) {
            SetupRow("Temel izinler ($missingRuntime eksik)", "Mikrofon, rehber, arama, SMS, bildirim.", onRuntime)
        }
        special.forEach { SetupRow(it.title, it.why) { onSpecial(it) } }
    }
}

@Composable
private fun SetupRow(title: String, why: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xF2EDF2FA), fontSize = 14.sp)
            Text(why, color = Faint, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Text("Aç", color = CoreColors.Ice, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPanel(onClose: () -> Unit, onRecorder: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(settings.geminiKey) }
    var url by remember { mutableStateOf(settings.pcUrl) }
    var pin by remember { mutableStateOf("") }
    var pcStatus by remember { mutableStateOf(if (settings.pcSession.isNotBlank()) "PC'ye bağlı (oturum kayıtlı)." else "PC'ye henüz giriş yapılmadı.") }
    var busy by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(settings.voiceVolume) }
    var voice by remember { mutableStateOf(settings.voice) }
    var carMode by remember { mutableStateOf(settings.carMode) }
    var handsFreeCall by remember { mutableStateOf(settings.handsFreeCall) }
    var home by remember { mutableStateOf(settings.homeAddress) }
    var work by remember { mutableStateOf(settings.workAddress) }

    // Anahtar ve PC adresi her tuşta değil, panelden çıkarken (Kapat, geri tuşu) kaydediliyor:
    // yarım yazılmış adres etkin olup PC oturum çerezini başka yere göndermesin.
    val latestKey by rememberUpdatedState(key)
    val latestUrl by rememberUpdatedState(url)
    DisposableEffect(Unit) {
        onDispose {
            if (latestKey != settings.geminiKey) settings.geminiKey = latestKey
            if (latestUrl.trim().trimEnd('/') != settings.pcUrl) settings.pcUrl = latestUrl
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CoreColors.Ice, unfocusedBorderColor = Hairline,
        focusedTextColor = Color.White, unfocusedTextColor = Color(0xF2EDF2FA),
        focusedLabelColor = CoreColors.Ice, unfocusedLabelColor = Faint, cursorColor = CoreColors.Ice,
    )

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aıron'un sesi: %${(volume * 100).toInt()}", color = Color(0xF2EDF2FA), fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { AudioIO.preview(volume) }) { Text("Dene", color = CoreColors.Ice) }
            }
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    AudioIO.volume = it // konuşma sürüyorsa anında
                },
                onValueChangeFinished = { settings.voiceVolume = volume },
                valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(thumbColor = CoreColors.Ice, activeTrackColor = CoreColors.Ice, inactiveTrackColor = Hairline),
            )
            Text("Telefonun kendi ses düzeyinin üstüne ek kısma; tam sessize almaz.", color = Faint, fontSize = 12.sp)
        }
        var voiceMenu by remember { mutableStateOf(false) }
        val current = AppSettings.VOICES.first { it.id == voice }
        ExposedDropdownMenuBox(expanded = voiceMenu, onExpandedChange = { voiceMenu = it }) {
            OutlinedTextField(
                value = "${current.id} — ${current.label}",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Aıron'un ses tonu") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenu) },
                colors = fieldColors,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                AppSettings.VOICES.forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(v.id, fontWeight = if (v.id == voice) FontWeight.SemiBold else FontWeight.Normal)
                                Text(v.label, fontSize = 12.sp, color = Muted)
                            }
                        },
                        onClick = {
                            voice = v.id
                            settings.voice = v.id
                            voiceMenu = false
                        },
                    )
                }
            }
        }
        Text("Yeni ses bir sonraki konuşmada devreye girer. Aıron her seste Türkçe konuşur.", color = Faint, fontSize = 12.sp)
        OutlinedTextField(key, { key = it }, label = { Text("Gemini API anahtarı") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("PC adresi (Tailscale)") }, singleLine = true,
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PC PIN'i") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        Text(pcStatus, color = Muted, fontSize = 13.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text("Kapat", color = CoreColors.Ice) }
            TextButton(enabled = pin.length >= 6 && !busy, onClick = {
                settings.pcUrl = url
                busy = true
                scope.launch {
                    val (ok, message) = withContext(Dispatchers.IO) { DesktopBridge(settings).login(pin) }
                    pcStatus = message
                    if (ok) pin = ""
                    busy = false
                }
            }) { Text(if (busy) "Bağlanıyor…" else "PC'ye bağlan", color = CoreColors.Ice) }
        }
        Text("ARABA", color = Faint, fontSize = 10.sp, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Araba modu", color = Color(0xF2EDF2FA), fontSize = 14.sp)
                Text(
                    "Aıron dinlerken gelen WhatsApp/SMS mesajlarını sesli okur ve cevap vermek isteyip istemediğini sorar. " +
                        "\"Aıron: açık\" olmalı. Bluetooth/araba bağlıysa Aıron'un sesi oradan gelir.",
                    color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Switch(
                checked = carMode,
                onCheckedChange = { carMode = it; settings.carMode = it },
                colors = SwitchDefaults.colors(checkedThumbColor = CoreColors.Background, checkedTrackColor = CoreColors.Ice),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Telefon hattı kullan", color = Color(0xF2EDF2FA), fontSize = 14.sp)
                Text(
                    "Kapalı (önerilen): Aıron'un sesi müzik hattından gelir, mikrofon telefonun kendisi; araba görüşme " +
                        "moduna geçmez, konuşma bitince müzik geri açılır. Açık: arabanın mikrofonu kullanılır ama araba " +
                        "bunu telefon görüşmesi sanar, bazı arabalar sonra müziğe dönmez.",
                    color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Switch(
                checked = handsFreeCall,
                onCheckedChange = { handsFreeCall = it; settings.handsFreeCall = it },
                colors = SwitchDefaults.colors(checkedThumbColor = CoreColors.Background, checkedTrackColor = CoreColors.Ice),
            )
        }
        OutlinedTextField(home, { home = it; settings.homeAddress = it }, label = { Text("Ev adresi (\"eve götür\")") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(work, { work = it; settings.workAddress = it }, label = { Text("İş adresi (\"işe götür\")") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())

        TextButton(onClick = onRecorder) { Text("Uyandırma sözü kaydı (\"Aıron\" eğitimi)", color = CoreColors.Ice) }
        Spacer(Modifier.height(8.dp))
        Text(
            "PC'ye ulaşmak için telefonda Tailscale açık olmalı. PIN telefonda saklanmaz; PC 30 günlük oturum verir.",
            color = Faint, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
