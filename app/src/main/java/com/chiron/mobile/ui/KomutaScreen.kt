package com.chiron.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chiron.mobile.AppSettings
import com.chiron.mobile.tools.DesktopBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Komuta — PC'deki Komuta ekranının telefondaki aynısı (2026-10-29).
 *
 * Yunus: "telefondan da iş verebileyim, Komuta'dan kopuk kalmasın, ne yaptığını göreyim".
 * Aynı sohbetler, aynı Claude oturumu: PC'nin `/api/sohbet` uçları PIN oturumuyla açık. Telefondan
 * yazılan mesajı PC "telefon" diye işaretler; sonucu telefona bildirir (backend/api/sohbet.py).
 * Ekran açıkken yoklanır: süren iş varken 2 sn, yoksa 8 sn.
 */
@Composable
fun KomutaScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val api = remember { KomutaApi(DesktopBridge(AppSettings(context), readTimeoutSeconds = 15)) }
    var openId by remember { mutableStateOf<String?>(null) }
    BackHandler { if (openId != null) openId = null else onClose() }

    val id = openId
    if (id == null) {
        ChatList(api, onOpen = { openId = it }, onClose = onClose)
    } else {
        ChatView(api, id, onBack = { openId = null })
    }
}

private class KomutaApi(private val desktop: DesktopBridge) {
    /** (sohbetler, hata) — hata null değilse liste eski kalır. */
    fun list(): Pair<List<JSONObject>?, String?> {
        val r = desktop.get("/api/sohbet")
        if (!r.optBoolean("success")) return null to r.optString("message", "PC'ye ulaşılamadı.")
        return objects(r.optJSONObject("data")?.optJSONArray("chats")) to null
    }

    fun get(id: String) = chat(desktop.get("/api/sohbet/$id"))
    fun create() = chat(desktop.post("/api/sohbet", JSONObject().put("agent", "claude")))
    fun send(id: String, text: String) = chat(desktop.post("/api/sohbet/$id/mesaj", JSONObject().put("text", text)))
    fun answer(id: String, approved: Boolean) = chat(desktop.post("/api/sohbet/$id/onay", JSONObject().put("approved", approved)))
    fun stop(id: String) = chat(desktop.post("/api/sohbet/$id/durdur", JSONObject()))

    private fun chat(r: JSONObject): Pair<JSONObject?, String?> =
        if (r.optBoolean("success")) r.optJSONObject("data")?.optJSONObject("chat") to null
        else null to r.optString("message", "PC'ye ulaşılamadı.")
}

private fun objects(array: JSONArray?): List<JSONObject> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

// Ana ekranla aynı palet; turuncu PC'deki ajanın rengi (Ajan Ofisi "Şef" masası).
private val Ink = Color(0xF2EDF2FA)
private val Muted = Color(0x9ED6E2F2)
private val Faint = Color(0x57CAD8EA)
private val Glass = Color(0x0FFFFFFF)
private val Hairline = Color(0x17FFFFFF)
private val Work = Color(0xFFFFB38A)
private val Danger = Color(0xFFFF8F8F)

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Geri", tint = Muted) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action?.invoke()
    }
}

@Composable
private fun ChatList(api: KomutaApi, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var chats by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val (list, err) = withContext(Dispatchers.IO) { api.list() }
                if (list != null) chats = list
                error = err
                delay(if (list.orEmpty().any { it.optBoolean("busy") }) 2_000L else 8_000L)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Komuta", "PC'deki sohbetler", onClose) {
            IconButton(onClick = {
                scope.launch {
                    val (chat, err) = withContext(Dispatchers.IO) { api.create() }
                    if (chat != null) onOpen(chat.optString("id")) else error = err
                }
            }) { Icon(Icons.Rounded.Add, "Yeni sohbet", tint = CoreColors.Ice) }
        }
        error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        val list = chats
        when {
            list == null -> Hint("Yükleniyor…")
            list.isEmpty() -> Hint("Henüz sohbet yok. + ile başlat ya da Chiron'a sesle iş ver.")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.optString("id") }) { ChatRow(it) { onOpen(it.optString("id")) } }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Faint, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp))
}

@Composable
private fun ChatRow(chat: JSONObject, onClick: () -> Unit) {
    val busy = chat.optBoolean("busy")
    val waiting = chat.optString("status") == "onay"
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (busy) Color(0x14FFB38A) else Glass)
            .border(1.dp, if (busy || waiting) Color(0x40FFB38A) else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                chat.optString("title"), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(ago(chat.optDouble("updated")), color = Faint, fontSize = 12.sp)
        }
        val line = when {
            busy -> chat.optString("last_step").ifBlank { "Çalışıyor" }
            waiting -> "Onay bekliyor · " + chat.optString("approval")
            else -> chat.optString("preview")
        }
        if (line.isNotBlank()) {
            Text(
                line, color = if (busy || waiting) Work else Muted, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatView(api: KomutaApi, id: String, onBack: () -> Unit) {
    var chat by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    // Gönderim / onay sonrası yoklamayı hemen yenile.
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, id, tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val (got, err) = withContext(Dispatchers.IO) { api.get(id) }
                if (got != null) chat = got
                error = err
                delay(if (got?.optBoolean("busy") == true) 2_000L else 8_000L)
            }
        }
    }

    fun act(call: () -> Pair<JSONObject?, String?>) {
        if (sending) return
        sending = true
        scope.launch {
            val (got, err) = withContext(Dispatchers.IO) { call() }
            if (got != null) chat = got
            error = err
            sending = false
            tick++
        }
    }

    val current = chat
    val busy = current?.optBoolean("busy") == true
    val status = when {
        current == null -> ""
        busy -> current.optString("last_step").ifBlank { "Çalışıyor" }
        current.optString("status") == "onay" -> "Onay bekliyor"
        else -> "Hazır"
    }
    Column(Modifier.fillMaxSize()) {
        Header(current?.optString("title") ?: "Komuta", status, onBack) {
            if (busy) IconButton(onClick = { act { api.stop(id) } }) { Icon(Icons.Rounded.Stop, "Durdur", tint = Danger) }
        }
        error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        val messages = objects(current?.optJSONArray("messages"))
        val listState = rememberLazyListState()
        val lastSteps = messages.lastOrNull()?.optJSONArray("steps")?.length() ?: 0
        LaunchedEffect(messages.size, lastSteps) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages.size) { i ->
                val message = messages[i]
                if (message.optString("role") == "ben") {
                    UserBubble(message)
                } else {
                    AgentBubble(
                        message,
                        isLast = i == messages.lastIndex,
                        onAnswer = { approved -> act { api.answer(id, approved) } },
                    )
                }
            }
        }
        KomutaComposer(enabled = current != null && !busy && !sending) { text -> act { api.send(id, text) } }
    }
}

@Composable
private fun UserBubble(message: JSONObject) {
    val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            message.optString("text"), color = Ink, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier
                .padding(start = 48.dp)
                .clip(shape)
                .background(Color(0x13FFFFFF))
                .border(1.dp, Hairline, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Text(
            if (message.optString("via") == "telefon") "telefondan" else "PC'den",
            color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, end = 6.dp),
        )
    }
}

@Composable
private fun AgentBubble(message: JSONObject, isLast: Boolean, onAnswer: (Boolean) -> Unit) {
    val status = message.optString("status")
    val live = status == "sirada" || status == "calisiyor"
    val shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
    Column(
        Modifier
            .padding(end = 24.dp)
            .clip(shape)
            .background(if (live || status == "onay") Color(0x14FFB38A) else Color(0xBC0A0E16))
            .border(1.dp, if (live || status == "onay") Color(0x40FFB38A) else Hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (live) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                Text(if (status == "sirada") "Sırada" else "Çalışıyor", color = Work, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (status != "bitti") {
            Text(STATUS_LABELS[status] ?: status, color = if (status == "onay") Work else Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // Ne yaptı: canlıyken son adımlar, bitince gizli kalır (özet zaten anlatıyor).
        val steps = message.optJSONArray("steps")
        if (live && steps != null && steps.length() > 0) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val from = maxOf(0, steps.length() - VISIBLE_STEPS)
                for (k in from until steps.length()) {
                    val latest = k == steps.length() - 1
                    Text(
                        steps.optString(k), color = if (latest) Ink else Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(if (latest) 1f else 0.7f),
                    )
                }
            }
        }
        val text = message.optString("text")
        if (text.isNotBlank()) {
            Text(text, color = Color(0xFFDCE8FA), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = if (live) 6.dp else 2.dp))
        }
        if (status == "onay" && isLast) {
            message.optString("approval").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Work, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Onayla", filled = true) { onAnswer(true) }
                Pill("Reddet", filled = false) { onAnswer(false) }
            }
        }
    }
}

@Composable
private fun Pill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (filled) CoreColors.Background else Ink,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) Work else Glass)
            .border(1.dp, if (filled) Color.Transparent else Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun LiveDot() {
    val pulse by rememberInfiniteTransition(label = "canli").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "nabiz",
    )
    Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(Work))
}

@Composable
private fun KomutaComposer(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && enabled) {
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
            placeholder = { Text(if (enabled) "PC'ye iş yaz…" else "Bitmesini bekle…", color = Faint) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedBorderColor = Color(0x40FFB38A), unfocusedBorderColor = Hairline,
                cursorColor = Work, focusedContainerColor = Glass, unfocusedContainerColor = Glass,
            ),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(enabled = enabled && text.isNotBlank(), onClick = send) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Gönder", tint = if (enabled && text.isNotBlank()) Work else Faint)
        }
    }
}

private const val VISIBLE_STEPS = 6

private val STATUS_LABELS = mapOf(
    "onay" to "Onay bekliyor",
    "hata" to "Tamamlanamadı",
    "durdu" to "Durduruldu",
)

/** PC'nin saniye cinsinden zaman damgası → "şimdi / 5 dk / 3 sa / 2 gün". */
private fun ago(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0) return ""
    val diff = (System.currentTimeMillis() / 1000.0 - seconds).toLong().coerceAtLeast(0)
    return when {
        diff < 60 -> "şimdi"
        diff < 3600 -> "${diff / 60} dk"
        diff < 86400 -> "${diff / 3600} sa"
        else -> "${diff / 86400} gün"
    }
}
