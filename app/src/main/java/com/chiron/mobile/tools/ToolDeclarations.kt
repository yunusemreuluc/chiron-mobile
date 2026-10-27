package com.chiron.mobile.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Telefondaki Chiron'un araçları — kullanıcının 2026-09-15'te verdiği listeyle
 * birebir aynı adlar. Şema Gemini'nin OpenAPI alt kümesi (tip adları BÜYÜK harf).
 */
object ToolDeclarations {

    val all: JSONArray by lazy {
        JSONArray()
            .put(
                fn(
                    "call_contact",
                    "Rehberdeki bir kişiyi telefonla hemen arar.",
                    props(
                        "name" to str(
                            "Kişinin rehberdeki adının EKSİZ yalın hâli. 'Annemi ara' → 'Annem', " +
                                "'Ahmet'i ara' → 'Ahmet', 'Mehmet abiyi ara' → 'Mehmet abi'.",
                        ),
                        "contact_id" to str("Yalnızca araç daha önce needs_choice döndürdüyse: kullanıcının seçtiği adayın contact_id'si."),
                    ),
                    required = listOf("name"),
                ),
            )
            .put(
                fn(
                    "send_message",
                    "Rehberdeki bir kişiye mesaj hazırlar, kullanıcı onaylayınca gönderir. İKİ AŞAMALI: draft_id olmadan " +
                        "çağrı yalnızca taslak döner (gönderilmez); kullanıcı onaylayınca dönen draft_id ile aynı parametrelerle " +
                        "tekrar çağır. Varsayılan kanal WhatsApp; kullanıcı SMS derse sms.",
                    props(
                        "recipient" to str("Alıcının rehberdeki adının EKSİZ yalın hâli ('Ahmet'e yaz' → 'Ahmet')."),
                        "message" to str("Gönderilecek metnin kendisi, kullanıcının söylediği gibi; başına açıklama ekleme."),
                        "channel" to enumStr("Kanal.", "whatsapp", "sms"),
                        "contact_id" to str("Yalnızca araç daha önce needs_choice döndürdüyse: kullanıcının seçtiği adayın contact_id'si."),
                        "draft_id" to str("Yalnızca kullanıcı taslağı AÇIKÇA onayladıktan sonra: taslak aşamasında dönen draft_id. Onay yoksa verme."),
                        "decision" to enumStr(
                            "Yalnız [CLAUDE İŞİ] onay sorusuna kullanıcının cevabı: evet → approve, hayır → reject. Bu durumda task olarak 'onay' yaz.",
                            "approve", "reject",
                        ),
                    ),
                    required = listOf("recipient", "message"),
                ),
            )
            .put(
                fn(
                    "control_device",
                    "Uygulama açma, uygulama içinde arama, YouTube'da video/şarkı oynatma, alarm kurma, " +
                        "zamanlayıcı başlatma, feneri açma/kapama, ana ekrana dönme.",
                    props(
                        "action" to enumStr(
                            "Yapılacak iş. 'YouTube'da X aç/çal/oynat' → play_youtube (ilk videoyu başlatır). " +
                                "'X uygulamasında Y ara' → search_in_app. Yalnızca uygulamayı açmak → open_app. " +
                                "'Ana ekrana dön', 'ana ekrana çık', 'buradan çık' → go_home.",
                            "open_app", "search_in_app", "play_youtube", "set_alarm", "set_timer", "flashlight_on", "flashlight_off",
                            "go_home",
                        ),
                        "app_name" to str("open_app ve search_in_app için uygulamanın adı (ör. 'Spotify', 'YouTube', 'Chrome')."),
                        "query" to str("search_in_app ve play_youtube için aranacak/oynatılacak şey, kullanıcının söylediği gibi (ör. 'bas gaza')."),
                        "hour" to int("set_alarm için saat (0-23)."),
                        "minute" to int("set_alarm için dakika (0-59)."),
                        "duration_seconds" to int("set_timer için toplam süre, saniye."),
                        "label" to str("Alarm ya da zamanlayıcının etiketi (isteğe bağlı)."),
                    ),
                    required = listOf("action"),
                ),
            )
            .put(
                fn(
                    "manage_device_audio",
                    "Ses seviyesi, medya kontrolü (oynat/duraklat/sonraki/önceki) ve rahatsız etme modu.",
                    props(
                        "action" to enumStr(
                            "Yapılacak iş.",
                            "volume_up", "volume_down", "set_volume", "mute", "unmute",
                            "media_play_pause", "media_next", "media_previous", "dnd_on", "dnd_off",
                        ),
                        "level" to int("set_volume için medya ses seviyesi yüzdesi (0-100)."),
                    ),
                    required = listOf("action"),
                ),
            )
            .put(
                fn(
                    "get_recent_notifications",
                    "Telefondaki son bildirimleri okur. Belirli bir uygulamanın mesajları sorulursa app_name ver.",
                    props(
                        "app_name" to str("Süzülecek uygulama (ör. 'WhatsApp', 'Instagram'). Boşsa hepsi."),
                        "limit" to int("En fazla kaç bildirim (varsayılan 8)."),
                    ),
                ),
            )
            .put(
                fn(
                    "get_device_health",
                    "Telefonun şarj yüzdesi, şarj olup olmadığı, tahmini kalan süre ve internet/Wi-Fi bağlantı durumu.",
                    JSONObject(),
                ),
            )
            .put(
                fn(
                    "query_desktop_chiron",
                    "Bilgisayardaki Chiron'a soru sorar; o PC ekranına, sistemine, açık uygulamalara bakıp cevap verir. " +
                        "'PC'de ne durumdayım', 'bilgisayarda ne açık', 'indirme bitti mi' gibi her PC sorusu için.",
                    props("question" to str("PC'deki Chiron'a iletilecek soru, tam cümle.")),
                    required = listOf("question"),
                ),
            )
            .put(
                fn(
                    "send_desktop_command",
                    "Bilgisayara doğrudan komut: kilitle, uykuya al, PC'deki medyayı oynat/duraklat/geç, metni PC panosuna gönder.",
                    props(
                        "action" to enumStr("Komut.", "lock", "sleep", "media_play_pause", "media_next", "media_previous", "clipboard"),
                        "text" to str("clipboard için PC panosuna aktarılacak metin."),
                    ),
                    required = listOf("action"),
                ),
            )
            .put(
                fn(
                    "delegate_to_claude",
                    "PC'deki Claude Code'a (kod yazan, dosyalarla/Etsy/Drive/projelerle çalışan yapay zeka ajanı) iş verir. " +
                        "Uzun süren, PC'de dosya/kod/proje gerektiren işler için: 'PC'de şu projeye şunu ekle', " +
                        "'Etsy listinglerini hazırla', 'şu klasörü düzenle'. PC ekranına bakıp cevaplanacak soru için DEĞİL " +
                        "(onun için query_desktop_chiron). İş arka planda sürer; bitince haber gelir. İKİ AŞAMALI: draft_id olmadan çağrı " +
                        "işi VERMEZ, taslak döner; görevi kullanıcıya okuyup onay al, sonra aynı task ve dönen draft_id ile tekrar çağır. " +
                        "İstisna: [CLAUDE İŞİ] onay sorusuna kullanıcının cevabı — o zaman draft_id gerekmez: resume_job_id + decision ver.",
                    props(
                        "task" to str("Kullanıcının istediği iş, ayrıntısıyla, tam cümle; kendi yorumunu ekleme."),
                        "resume_job_id" to str("Yalnız önceki bir işi sürdürmek veya onay cevabı vermek için o işin job_id'si."),
                        "draft_id" to str("Yalnızca kullanıcı taslağı AÇIKÇA onayladıktan sonra: taslak aşamasında dönen draft_id. Onay yoksa verme."),
                        "decision" to enumStr(
                            "Yalnız [CLAUDE İŞİ] onay sorusuna kullanıcının cevabı: evet → approve, hayır → reject. Bu durumda task olarak 'onay' yaz.",
                            "approve", "reject",
                        ),
                    ),
                    required = listOf("task"),
                ),
            )
            .put(
                fn(
                    "claude_job_status",
                    "PC'deki Claude işlerinin durumunu okur ('Claude ne yapıyor', 'iş bitti mi').",
                    props("job_id" to str("Bakılacak işin job_id'si; verilmezse son işler okunur.")),
                ),
            )
            .put(
                fn(
                    "cancel_claude_job",
                    "Süren Claude işini durdurur.",
                    props("job_id" to str("Durdurulacak işin job_id'si.")),
                    required = listOf("job_id"),
                ),
            )
            .put(
                fn(
                    "start_navigation",
                    "Google Haritalar'da yol tarifini/navigasyonu doğrudan başlatır ('eve götür', 'Kadıköy'e yol tarifi', 'en yakın benzinlik').",
                    props(
                        "destination" to str(
                            "Varış yeri, kullanıcının söylediği gibi (adres, yer adı ya da 'en yakın benzinlik'). " +
                                "Ev için tam olarak 'ev', iş için 'iş' yaz.",
                        ),
                        "mode" to enumStr("Ulaşım türü; belirtilmezse driving.", "driving", "walking", "bicycling", "two_wheeler"),
                    ),
                    required = listOf("destination"),
                ),
            )
            .put(
                fn(
                    "add_feature_request",
                    "Chiron'un şu an YAPAMADIĞI bir şeyi kullanıcının istek listesine ekler (PC'deki Chiron kasasına gider). " +
                        "YALNIZCA kullanıcı eklemeyi onayladıktan sonra ya da 'istek listesine ekle' dediğinde çağır.",
                    props(
                        "title" to str("İsteğin kısa, genel başlığı; kişi adı/mesaj içeriği OLMADAN (ör. 'Spotify'da çalma listesi başlatma')."),
                        "user_request" to str("Kullanıcının o anki isteği, kısaca; kişisel içerik (mesaj metni, numara) yazma."),
                        "attempted" to str("Denediğin araç ve neden olmadığı (ör. 'control_device search_in_app — liste açılamadı'); hiç araç yoksa 'uygun araç yok'."),
                        "detail" to str(
                            "İsteği okuyacak geliştirici için KAPSAMLI açıklama, 2-5 cümle: kullanıcının tam olarak ne istediği, " +
                                "hangi durumda olduğu (arabada mı, hangi uygulama açıktı, müzik çalıyor muydu), hangi aracı hangi parametreyle " +
                                "denediğin ve dönen sonuç/hata metni, ideal davranışın ne olması gerektiği. Kısaltma, tahmin ekleme; bilmediğini yazma. " +
                                "Kişisel içerik YAZMA: kişi adı, mesaj metni, telefon numarası, adres yerine 'bir kişi', 'bir mesaj' de.",
                        ),
                    ),
                    required = listOf("title"),
                ),
            )
            .put(
                fn(
                    "report_bug",
                    "Kullanıcının bildirdiği bir HATAYI kaydeder ('hata bildir', 'bu çalışmadı, kaydet'). PC'deki istek " +
                        "listesine 'Hata:' başlığıyla düşer; son araç çağrılarının özeti otomatik eklenir.",
                    props(
                        "title" to str("Hatanın kısa, genel başlığı; kişi adı/mesaj içeriği OLMADAN (ör. 'Şarkı değişince ses gelmiyor')."),
                        "description" to str(
                            "Ne oldu, ne bekleniyordu, hangi durumda (arabada mı, hangi uygulama açıktı) — 1-4 cümle. " +
                                "Kişi adı, mesaj metni, numara yazma.",
                        ),
                    ),
                    required = listOf("title", "description"),
                ),
            )
            .put(
                fn(
                    "get_pc_status",
                    "PC'nin anlık durum raporu, PC'deki sesli Chiron'a sormadan: açık mı, CPU/RAM, kullanıcı ne kadar " +
                        "süredir yok, öndeki uygulama, süren ve biten Claude işleri (bitti mi, ne yapıyor), ajan ofisinde " +
                        "kim çalışıyor. 'PC ne durumda', 'PC'de şu an ne yapılıyor', 'iş bitti mi' için BUNU kullan.",
                    JSONObject(),
                ),
            )
            .put(
                fn(
                    "list_feature_requests",
                    "Kullanıcının istek listesini okur ('istek listemde ne var', 'hangi istekler yapıldı').",
                    JSONObject(),
                ),
            )
            .put(
                fn(
                    "remove_feature_request",
                    "Kullanıcının istek listesinden bir isteği kaldırır. YALNIZCA kullanıcı hangi isteği kastettiğini söyleyip kaldırmayı onayladıktan sonra çağır.",
                    props(
                        "number" to str("İstek numarası, 'İ-003' biçiminde; kullanıcı 'üç numara' derse 'İ-003'."),
                        "title" to str("Yalnız henüz PC'ye gitmemiş istek için başlık."),
                    ),
                ),
            )
            .put(
                fn(
                    "save_quick_note",
                    "Hızlı not alır ('bunu not et', 'aklıma geldi yaz'). Telefona kaydeder, PC'ye ulaşılabiliyorsa oraya da yazar.",
                    props("text" to str("Notun metni.")),
                    required = listOf("text"),
                ),
            )
    }

    private fun fn(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject {
        val decl = JSONObject().put("name", name).put("description", description)
        if (parameters.length() > 0) {
            val schema = JSONObject().put("type", "OBJECT").put("properties", parameters)
            if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            decl.put("parameters", schema)
        }
        return decl
    }

    private fun props(vararg entries: Pair<String, JSONObject>) = JSONObject().apply {
        entries.forEach { (key, value) -> put(key, value) }
    }

    private fun str(description: String) = JSONObject().put("type", "STRING").put("description", description)
    private fun int(description: String) = JSONObject().put("type", "INTEGER").put("description", description)
    private fun enumStr(description: String, vararg values: String) =
        str(description).put("enum", JSONArray(values.toList()))
}
