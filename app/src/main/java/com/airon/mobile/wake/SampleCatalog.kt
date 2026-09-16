package com.airon.mobile.wake

/**
 * Kayıt ekranının yönergeleri. Olumlu örnekler çeşitli olmalı (ton, hız, mesafe) —
 * openWakeWord tek tip kayıtla eğitilince sesin başka hâlini kaçırıyor. Olumsuzlar
 * Vosk'un "Aıron"la karıştırdığı ve Türkçede ona sesçe yakın kelimeler.
 */
object SampleCatalog {

    enum class Kind(val dir: String, val title: String, val target: Int, val maxMs: Int, val prompts: List<String>) {
        POSITIVE(
            "positive", "Aıron", 40, 2500,
            listOf(
                "Normal sesle", "Fısıltıyla", "Yüksek sesle, seslenir gibi", "Hızlı", "Yavaş, uzatarak",
                "Soru sorar gibi: Aıron?", "Sıkılmış gibi", "Yorgun, uykulu", "Gülümseyerek", "Kısık sesle",
                "Telefon kolunun ucunda", "Telefon masada, 1 metre uzakta", "Telefon masada, 2 metre uzakta",
                "Başını çevirip, yana doğru", "Telefon cebinde / çantada gibi uzakta",
                "Arabada sürer gibi, telefon tutucuda", "Arkada müzik/TV açıkken", "Yürürken",
                "Tek nefeste, kesik", "Vurguyu başa: AI-ron", "Vurguyu sona: ai-RON",
                "Aceleyle", "Sakin ve alçak", "Emir verir gibi", "Birine seslenir gibi, uzaktan",
            ),
        ),
        NEGATIVE(
            "negative", "Benzer kelimeler", 40, 2500,
            listOf(
                "ayran", "Aaron", "hayır", "Aydın", "Erol", "heron", "hayran", "ayrıl", "ayna", "iron",
                "Ayten", "haydi", "Oran", "Arın", "Aras", "hey Ali", "hey oğlum", "Ayhan",
                "Ayşe", "aynen", "hayırdır", "ayar", "Harun", "Orhan", "Ay'dan", "Eren", "Airbnb", "ayrı",
            ),
        ),
        SPEECH(
            "speech", "Serbest konuşma", 6, 30_000,
            listOf(
                "Bugün ne yaptığını anlat (\"Aıron\" deme)",
                "Bir tarif ya da yol tarifi anlat",
                "Telefonda biriyle konuşur gibi konuş",
                "Aklına gelen her şeyi söyle, \"Aıron\" hariç",
                "Bir haber metni ya da yazı oku",
                "İsimler say: arkadaşlar, aile, şehirler",
            ),
        ),
        NOISE(
            "noise", "Ortam sesi", 4, 30_000,
            listOf(
                "Sessiz oda, konuşma yok",
                "TV / müzik açık, konuşma yok",
                "Araba içi (motor/yol sesi), konuşma yok",
                "Dışarıda / kalabalık ortam",
            ),
        ),
    }

    fun prompt(kind: Kind, index: Int): String = kind.prompts[index % kind.prompts.size]
}
