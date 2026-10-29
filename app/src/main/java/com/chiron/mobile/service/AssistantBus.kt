package com.chiron.mobile.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Servis ile arayüz arasındaki tek durum kaynağı. Servis yazıyor, arayüz
 * yalnızca okuyor — ekran kapansa da konuşma serviste sürüyor, ekran geri
 * açıldığında arayüz kaldığı yerden gösteriyor.
 */
object AssistantBus {

    /** WAITING: oturum yok, "Chiron" bekleniyor. */
    enum class Phase { OFF, WAITING, CONNECTING, LISTENING, THINKING, SPEAKING, WORKING }

    /** `claude`: PC'deki Claude'un sonucu/onay sorusu — sohbette ayrı balon. */
    data class Line(val id: Long, val fromUser: Boolean, val text: String, val claude: Boolean = false)

    data class State(
        val phase: Phase = Phase.OFF,
        val lines: List<Line> = emptyList(),
        val activeTool: String? = null,
        val notice: String? = null,
        /** Yazışma modu: kullanıcı klavyeyle konuşuyor — Chiron sesli cevap vermiyor, mikrofon duraklı. */
        val typing: Boolean = false,
        /** Süren Claude işinin canlı tek satırı ("Claude: pytest koşuyor · 2 dk"); yoksa null. */
        val claudeStatus: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Mikrofon seviyesi kare başına değişiyor — ayrı akış, ana durumu her 100 ms'de kopyalamasın. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    /** Bekleme modunda son ~1 sn'nin en yüksek "Chiron" skoru — eşiği ayarlarken görmek için. */
    private val _wakeScore = MutableStateFlow(0f)
    val wakeScore: StateFlow<Float> = _wakeScore.asStateFlow()
    fun wakeScore(score: Float) {
        _wakeScore.value = score
    }

    private var nextId = 0L

    fun phase(phase: Phase) = _state.update { it.copy(phase = phase) }
    fun tool(name: String?) = _state.update { it.copy(activeTool = name, phase = if (name != null) Phase.WORKING else it.phase) }
    fun notice(text: String?) = _state.update { it.copy(notice = text) }
    fun micLevel(level: Float) {
        _micLevel.value = level
    }

    /** Eklenen satırın id'si; boş metinde null. */
    fun line(fromUser: Boolean, text: String, claude: Boolean = false): Long? {
        if (text.isBlank()) return null
        var id = 0L
        _state.update { s ->
            id = nextId++
            val lines = (s.lines + Line(id, fromUser, text.trim(), claude)).takeLast(MAX_LINES)
            s.copy(lines = lines)
        }
        return id
    }

    /** Satırın metnini değiştirir (düzeltilmiş yazı, bkz. live.Transcriber); satır düştüyse bir şey yapmaz. */
    fun replaceText(id: Long, text: String) = _state.update { s ->
        s.copy(lines = s.lines.map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    /** Uygulama yeniden açılınca kayıtlı son konuşma ekrana döner (bkz. ConversationMemory). */
    fun restore(lines: List<Pair<Boolean, String>>) = _state.update { s ->
        if (s.lines.isNotEmpty()) s else s.copy(lines = lines.map { (fromUser, text) -> Line(nextId++, fromUser, text) })
    }

    fun typing(on: Boolean) = _state.update { it.copy(typing = on) }
    fun claudeStatus(text: String?) = _state.update { it.copy(claudeStatus = text) }

    private const val MAX_LINES = 60
}
