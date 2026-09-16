package com.airon.mobile.service

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

    /** WAITING: oturum yok, "Aıron" bekleniyor. */
    enum class Phase { OFF, WAITING, CONNECTING, LISTENING, THINKING, SPEAKING, WORKING }

    data class Line(val id: Long, val fromUser: Boolean, val text: String)

    data class State(
        val phase: Phase = Phase.OFF,
        val lines: List<Line> = emptyList(),
        val activeTool: String? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Mikrofon seviyesi kare başına değişiyor — ayrı akış, ana durumu her 100 ms'de kopyalamasın. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    /** Bekleme modunda son ~1 sn'nin en yüksek "Aıron" skoru — eşiği ayarlarken görmek için. */
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

    fun line(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        _state.update { s ->
            val lines = (s.lines + Line(nextId++, fromUser, text.trim())).takeLast(MAX_LINES)
            s.copy(lines = lines)
        }
    }

    private const val MAX_LINES = 60
}
