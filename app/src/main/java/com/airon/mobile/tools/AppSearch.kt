package com.airon.mobile.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Uygulama içinde arama ve YouTube'da oynatma ("YouTube'u aç, bas gaza aç").
 *
 * 2026-09-16'da gerçek cihazda (Redmi Note 12 Pro, Android 12) ölçüldü:
 * - YouTube `MEDIA_PLAY_FROM_SEARCH` filtresini ilan ediyor ama sorguyu YOK SAYIP
 *   ana sayfayı açıyor — kullanılmıyor.
 * - `https://www.youtube.com/results?search_query=…` YouTube uygulamasında arama
 *   sonuçlarını açıyor; `watch?v=<id>` videoyu doğrudan oynatıyor.
 * - İlk videonun kimliği, arama sayfası HTML'indeki ilk `videoRenderer`'dan
 *   alınıyor (`sp=EgIQAQ==` yalnız videolar süzgeci — yoksa ilk sonuç albüm/liste
 *   olabiliyor). API anahtarı gerekmiyor; sayfa biçimi değişirse arama sonuçlarına
 *   düşülüyor ve bu modele "oynatamadım" diye söyleniyor.
 */
class AppSearch(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Uygulama adına göre bilinen arama adresleri; bilinmeyende genel ACTION_SEARCH. */
    fun search(app: InstalledApp, query: String): JSONObject {
        val q = enc(query)
        val intent = when (app.packageName) {
            YOUTUBE -> view("https://www.youtube.com/results?search_query=$q", YOUTUBE)
            YOUTUBE_MUSIC -> view("https://music.youtube.com/search?q=$q", YOUTUBE_MUSIC)
            "com.spotify.music" -> view("spotify:search:$q", "com.spotify.music")
            "com.android.chrome" -> view("https://www.google.com/search?q=$q", "com.android.chrome")
            "com.google.android.googlequicksearchbox" ->
                Intent(Intent.ACTION_WEB_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query)
            "com.android.vending" -> view("market://search?q=$q", "com.android.vending")
            "com.google.android.apps.maps" -> view("geo:0,0?q=$q", "com.google.android.apps.maps")
            else -> Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return fail("${app.label} dışarıdan arama kabul etmiyor; yalnızca uygulamayı açabilirim.")
        }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ok("${app.label} içinde '$query' arandı.")
    }

    /** YouTube'da sorgunun ilk videosunu oynatır; bulamazsa sonuçları açar. */
    fun playOnYouTube(query: String): JSONObject {
        if (context.packageManager.getLaunchIntentForPackage(YOUTUBE) == null) {
            return fail("YouTube uygulaması yüklü değil.")
        }
        BackgroundLaunch.blockedReason(context)?.let { return fail(it) }

        val first = runCatching { firstVideo(query) }.getOrNull()
        if (first == null) {
            startView("https://www.youtube.com/results?search_query=${enc(query)}")
            return ok(
                "Videoyu otomatik başlatamadım, '$query' için YouTube arama sonuçlarını açtım; kullanıcı seçmeli.",
                "played" to false,
            )
        }
        startView("https://www.youtube.com/watch?v=${first.first}")
        return ok("YouTube'da oynatılıyor: ${first.second}", "played" to true, "title" to first.second)
    }

    /** (videoId, başlık) — sayfa beklenen biçimde değilse null. */
    private fun firstVideo(query: String): Pair<String, String>? {
        val request = Request.Builder()
            .url("https://www.youtube.com/results?search_query=${enc(query)}&sp=EgIQAQ%253D%253D")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        val html = http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val match = VIDEO.find(html) ?: return null
        val window = html.substring(match.range.last, minOf(html.length, match.range.last + 4000))
        val rawTitle = TITLE.find(window)?.groupValues?.get(1)
        // Başlık JSON kaçışlı (&, \"); JSON ayrıştırıcısıyla çözülüyor.
        val title = rawTitle?.let { runCatching { JSONObject("{\"t\":\"$it\"}").getString("t") }.getOrNull() }
        return match.groupValues[1] to (title ?: query)
    }

    private fun startView(url: String) =
        context.startActivity(view(url, YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun view(uri: String, pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        private val VIDEO = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
        private val TITLE = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}

data class InstalledApp(val label: String, val packageName: String)
