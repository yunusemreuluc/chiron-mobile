package com.chiron.mobile.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hızlı yol kapısı: iş ancak kullanıcının taze sözünden ve dışarıdan metin sakinken doğrudan gider. */
class UntrustedInputTest {
    private val min = 60_000L

    @Test
    fun freshVoiceWithoutOutsideTextGoesDirect() {
        // Hiç dışarıdan metin girmedi (lastAt = 0): kullanıcı az önce konuştu.
        assertTrue(UntrustedInput.allowsDirect(now = 10 * min, voiceAt = 10 * min - 5_000))
    }

    @Test
    fun silentUserNeverGoesDirect() {
        assertFalse(UntrustedInput.allowsDirect(now = 10 * min, voiceAt = 0))
        assertFalse(UntrustedInput.allowsDirect(now = 10 * min, voiceAt = 7 * min)) // söz 3 dk eski
    }

    @Test
    fun recentOutsideTextFallsBackToConfirmation() {
        val markedAt = 20 * min
        UntrustedInput.mark(at = markedAt)
        // Mesaj/bildirim 1 dk önce girdi, kullanıcı sonra konuştu: yine de sesli onay istenir.
        assertFalse(UntrustedInput.allowsDirect(now = markedAt + min, voiceAt = markedAt + min - 1_000))
        // 3 dk geçti ve kullanıcı yeni konuştu: doğrudan.
        assertTrue(UntrustedInput.allowsDirect(now = markedAt + 4 * min, voiceAt = markedAt + 4 * min - 1_000))
        // 3 dk geçti ama son söz dışarıdan metinden ÖNCEydi: doğrudan değil.
        assertFalse(UntrustedInput.allowsDirect(now = markedAt + 4 * min, voiceAt = markedAt - 1_000))
        UntrustedInput.mark(at = 0)
    }
}
