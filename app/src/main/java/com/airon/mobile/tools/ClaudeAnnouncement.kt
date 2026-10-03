package com.airon.mobile.tools

/**
 * PC'deki Claude sonucunu Gemini'ye verilen tek satırlık iç mesaja çevirir.
 * Android/JSON bağımlılığı yok; sesli duyurunun biçimi JVM testinde sabitlenebilsin.
 */
object ClaudeAnnouncement {
    data class Job(
        val id: String,
        val status: String,
        val summary: String,
        val approval: String,
    )

    fun format(job: Job): String =
        "[CLAUDE İŞİ] job_id=${oneLine(job.id)}, durum=${oneLine(job.status)}, " +
            "özet=${limited(job.summary, SUMMARY_LIMIT)}, onay=${oneLine(job.approval)}"

    private fun limited(value: String, limit: Int): String {
        val clean = oneLine(value)
        return if (clean.length <= limit) clean else clean.take(limit) + "…"
    }

    private fun oneLine(value: String): String = value.replace(Regex("\\s+"), " ").trim().ifEmpty { "-" }

    private const val SUMMARY_LIMIT = 600
}
