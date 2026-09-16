package com.airon.mobile.tools

import java.text.Normalizer

/**
 * Konuşulan adı rehber kaydına eşleştirir. Android'e bağımlı değil — bu yüzden
 * JVM birim testiyle doğrulanıyor (src/test/.../ContactMatcherTest.kt). Yanlış
 * kişiyi aramak ya da ona mesaj atmak geri alınamaz; bu dosya o riskin tamamı.
 *
 * Model adları çoğu zaman EKİYLE gönderiyor — protokol denemesinde "Annemi ara"
 * için `name="Annemi"` geldi. Araç açıklaması yalın hâli istiyor ama modele
 * güvenilmiyor: eşleştirme Türkçe hâl eklerini de soyarak deniyor.
 */
object ContactMatcher {

    data class Contact(val name: String, val number: String) {
        /** Son 10 hane — aynı numaranın "+90 555…" ve "0555…" yazımları tek kişi sayılsın. */
        val id: String = number.filter(Char::isDigit).takeLast(10)
    }

    sealed interface Resolution {
        data class One(val contact: Contact) : Resolution
        /** Tam/kelime eşleşmesi birden fazla kişiye (ya da aynı kişinin birden fazla numarasına) çıktı. */
        data class Many(val candidates: List<Contact>) : Resolution
        /** Yalnızca KISMİ eşleşme var ("Ali" → "Halil") — tek aday olsa bile işlem yapılmaz, sorulur. */
        data class Weak(val candidates: List<Contact>) : Resolution
    }

    private const val MAX_CANDIDATES = 6

    /**
     * Eşleştirme sırası (Astra incelemesi, 2026-09-15 — önceki sürüm ilk varyantta
     * prefix/contains ile tek adaya düşüp "Annemi" için "Annemin Doktoru"nu
     * arayabiliyordu):
     *  1. `contactId` verildiyse yalnızca o numara (model seçimden sonra böyle çağırıyor)
     *  2. BÜTÜN varyantlarda tam ad eşleşmesi
     *  3. BÜTÜN varyantlarda tam KELİME eşleşmesi ("Ahmet" → "Ahmet Yılmaz")
     *  4. kelime başı kısmi eşleşme → asla doğrudan işlem yok, `Weak`
     * Aynı ad + farklı numara ayrı aday sayılıyor: ev/cep ya da iki farklı Ahmet.
     */
    fun resolve(allContacts: List<Contact>, spoken: String, contactId: String?): Resolution? {
        val contacts = allContacts.filter { it.id.isNotEmpty() }.distinctBy { normalize(it.name) + "|" + it.id }
        if (contacts.isEmpty()) return null

        if (!contactId.isNullOrBlank()) {
            val wanted = contactId.filter(Char::isDigit).takeLast(10)
            return contacts.firstOrNull { it.id == wanted }?.let { Resolution.One(it) }
        }
        if (spoken.isBlank()) return null
        val variants = queryVariants(spoken)
        // İyelik soyulmuş hâl ("annem" → "anne") yalnızca TAM ad eşleşmesinde
        // kullanılıyor: kelime eşleşmesinde "Ayşe Anne" gibi başka birine çıkabilir.
        val primary = caseVariants(spoken)

        val exact = contacts.filter { normalize(it.name) in variants }
        if (exact.isNotEmpty()) return decide(exact)

        val wordExact = contacts.filter { c ->
            val name = normalize(c.name)
            primary.any { v -> name.split(" ").contains(v) || name.startsWith("$v ") }
        }
        if (wordExact.isNotEmpty()) return decide(wordExact)

        val partial = contacts.filter { c ->
            val words = normalize(c.name).split(" ")
            variants.any { v -> v.length >= 3 && words.any { it.startsWith(v) } }
        }
        return if (partial.isNotEmpty()) Resolution.Weak(partial.take(MAX_CANDIDATES)) else null
    }

    private fun decide(hits: List<Contact>): Resolution =
        if (hits.size == 1) Resolution.One(hits.first()) else Resolution.Many(hits.take(MAX_CANDIDATES))

    /**
     * "annemi" → ["annemi", "annem", "anne"]. Türkçe hâl ekleri ve iyelik (-m)
     * sırayla soyuluyor; en az soyulmuş hâl önce geliyor. Kök en az 3 harf kalıyor
     * ki "Ece", "Ali" gibi kısa adlar kırpılmasın.
     */
    fun queryVariants(spoken: String): List<String> {
        val cases = caseVariants(spoken)
        return (cases + possessiveVariants(cases)).distinct()
    }

    /** Yalnızca hâl eki soyulmuş hâller (iyelik hariç). */
    private fun caseVariants(spoken: String): List<String> {
        val base = normalize(spoken.substringBefore('\'').ifBlank { spoken })
        val out = linkedSetOf(normalize(spoken), base)
        val suffixes = listOf("ile", "yle", "yla", "nin", "nun", "yi", "yu", "ye", "ya", "le", "la", "in", "un", "i", "u", "e", "a")
        var current = base
        repeat(2) {
            val stripped = suffixes.firstOrNull { current.length - it.length >= 3 && current.endsWith(it) }
                ?.let { current.dropLast(it.length) }
            if (stripped != null) {
                out.add(stripped)
                current = stripped
            }
        }
        return out.filter { it.length >= 2 }
    }

    /** iyelik: "annem" → "anne", "babam" → "baba". */
    private fun possessiveVariants(variants: List<String>): List<String> =
        variants.filter { it.length > 4 && it.endsWith("m") }.map { it.dropLast(1) }.filterNot { it in variants }

    private val TR = mapOf(
        'ı' to 'i', 'İ' to 'i', 'ş' to 's', 'Ş' to 's', 'ğ' to 'g', 'Ğ' to 'g',
        'ü' to 'u', 'Ü' to 'u', 'ö' to 'o', 'Ö' to 'o', 'ç' to 'c', 'Ç' to 'c',
    )

    /** Türkçe harfleri ASCII'ye indirip küçültür — "Ahmet Çelik" == "ahmet celik". */
    fun normalize(text: String): String {
        val mapped = text.map { TR[it] ?: it }.joinToString("")
        return Normalizer.normalize(mapped, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
