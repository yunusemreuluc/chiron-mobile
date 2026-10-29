package com.chiron.mobile.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chiron.mobile.AppSettings
import com.chiron.mobile.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude işi sürerken kısa aralıklarla bakar. Bildirim göstermek sunucudaki ack'i
 * tüketmez; Chiron sonucu sesli söylemeden iş "söylendi" sayılmamalı.
 */
class ClaudeWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = AppSettings(applicationContext)
        val claude = ClaudeJobs(applicationContext, settings)
        val updateBatch = claude.fetchUpdates()
        updateBatch.jobs.forEach { showOnce(claude, it) }
        // İş sürerken PC bir APK gönderdiyse indirip "Yükle" bildirimi (ApkInbox). Hata worker'ı düşürmez.
        runCatching { ApkInbox.check(applicationContext) }

        val startedAt = inputData.getLong(KEY_STARTED_AT, System.currentTimeMillis())
        if ((updateBatch.active > 0 || !updateBatch.reachable) &&
            System.currentTimeMillis() - startedAt < MAX_WATCH_MS
        ) {
            schedule(applicationContext, delaySec = RETRY_SECONDS, startedAtMillis = startedAt)
        }
        return Result.success()
    }

    private fun showOnce(claude: ClaudeJobs, job: JSONObject) {
        val id = job.optString("id").trim()
        if (id.isEmpty()) return
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = readShown(prefs.getString(KEY_SHOWN, null))
        if (id in shown) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "PC işleri", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val status = job.optString("status")
        val title = when (status) {
            "done" -> "Chiron · iş bitti"
            "needs_approval" -> "Chiron · onayını bekliyor"
            else -> "Chiron · iş tamamlanamadı"
        }
        val text = job.optString("approval").ifBlank {
            job.optString("summary").ifBlank { job.optString("error") }
        }.ifBlank { "İşin durumunu görmek için dokun." }
        val open = PendingIntent.getActivity(
            applicationContext,
            notificationId(id),
            // Dokununca konuşma açılır ve sonuç okunur (Astra: yalnız uygulamayı açıyordu).
            Intent(applicationContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CLAUDE_RESULT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // İzin yarışla geri alınırsa worker çökmemeli ve kimlik gösterilmiş sayılmamalı.
        val displayed = claude.unlessTold(id) {
            runCatching { manager.notify(notificationId(id), notification) }.isSuccess
        }
        if (displayed) {
            val recent = (shown + id).distinct().takeLast(MAX_SHOWN)
            prefs.edit().putString(KEY_SHOWN, JSONArray(recent).toString()).apply()
        }
    }

    private fun readShown(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }

    companion object {
        private const val UNIQUE_WORK = "claude_watch"
        private const val CHANNEL = "chiron_claude"
        private const val PREFS = "claude_watch"
        private const val KEY_SHOWN = "shown_job_ids"
        private const val KEY_STARTED_AT = "started_at"
        private const val RETRY_SECONDS = 60L
        private const val MAX_WATCH_MS = 6 * 60 * 60 * 1000L
        private const val MAX_SHOWN = 50

        /** Oturum kimliği 7; iş bildirimleri ondan ayrı bir aralıkta. */
        fun notificationId(jobId: String) = 10_000 + (jobId.hashCode() and 0x7fff)

        fun schedule(
            context: Context,
            delaySec: Long = 30,
            startedAtMillis: Long = System.currentTimeMillis(),
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ClaudeWatchWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putLong(KEY_STARTED_AT, startedAtMillis).build())
                .build()
            // Worker kendi ardılını zincire ekler; aynı anda iki yoklayıcı çalışmaz.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
