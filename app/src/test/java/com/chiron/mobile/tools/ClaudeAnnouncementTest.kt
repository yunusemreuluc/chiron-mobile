package com.chiron.mobile.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeAnnouncementTest {
    @Test fun `claude sonucu modele tek satir ve tum alanlarla verilir`() {
        val result = ClaudeAnnouncement.format(
            ClaudeAnnouncement.Job(
                id = "job-42",
                status = "needs_approval",
                summary = "Dosyaları hazırladım.\nYükleme kaldı.",
                approval = "Dosyaları Etsy'ye yüklemek istiyorum.",
            ),
        )

        assertEquals(
            "[CLAUDE İŞİ] job_id=job-42, durum=needs_approval, özet=Dosyaları hazırladım. Yükleme kaldı., " +
                "onay=Dosyaları Etsy'ye yüklemek istiyorum.",
            result,
        )
    }

    @Test fun `uzun ozet 600 karakterde kesilir`() {
        val result = ClaudeAnnouncement.format(
            ClaudeAnnouncement.Job("job-1", "done", "x".repeat(650), ""),
        )

        assertTrue(result.contains("özet=${"x".repeat(600)}…"))
        assertFalse(result.contains("x".repeat(601)))
        assertTrue(result.endsWith("onay=-"))
    }
}
