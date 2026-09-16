package com.airon.mobile.tools

import com.airon.mobile.tools.ContactMatcher.Contact
import com.airon.mobile.tools.ContactMatcher.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Yanlış kişiyi aramanın/mesajlamanın önündeki tek kilit. Senaryolar Astra'nın
 * 2026-09-15 incelemesindeki somut hata örneklerinden ve Türkçe konuşma
 * kalıplarından.
 */
class ContactMatcherTest {

    private fun resolve(contacts: List<Contact>, spoken: String, id: String? = null) =
        ContactMatcher.resolve(contacts, spoken, id)

    private fun oneName(r: Resolution?) = (r as? Resolution.One)?.contact?.name

    @Test fun `annemi eki soyulup Annem bulunur, Annemin Doktoru degil`() {
        val c = listOf(Contact("Annem", "0555 111 22 33"), Contact("Annemin Doktoru", "0212 444 55 66"))
        assertEquals("Annem", oneName(resolve(c, "Annemi")))
    }

    @Test fun `yalniz Annemin Doktoru varsa dogrudan islem yok, sorulur`() {
        val c = listOf(Contact("Annemin Doktoru", "0212 444 55 66"))
        assertTrue(resolve(c, "Annemi") is Resolution.Weak)
    }

    @Test fun `Ali derken Halil secilmez`() {
        val c = listOf(Contact("Halil", "0555 000 00 01"))
        assertNull(resolve(c, "Ali"))
    }

    @Test fun `ayni isimli iki farkli numara tek kisiye indirilmez`() {
        val c = listOf(Contact("Ahmet", "0555 111 11 11"), Contact("Ahmet", "0532 222 22 22"))
        val r = resolve(c, "Ahmet")
        assertTrue(r is Resolution.Many)
        assertEquals(2, (r as Resolution.Many).candidates.size)
    }

    @Test fun `ayni numaranin farkli yazimlari tek kisi`() {
        val c = listOf(Contact("Ahmet", "+90 555 111 11 11"), Contact("Ahmet", "05551111111"))
        assertEquals("Ahmet", oneName(resolve(c, "Ahmet")))
    }

    @Test fun `ilk ad soyadli kayda tam kelimeyle eslesir`() {
        val c = listOf(Contact("Ahmet Yılmaz", "0555 111 11 11"), Contact("Mehmet Kaya", "0555 222 22 22"))
        assertEquals("Ahmet Yılmaz", oneName(resolve(c, "Ahmet'e")))
    }

    @Test fun `kesme isaretsiz ek de soyulur`() {
        val c = listOf(Contact("Zeynep", "0555 333 33 33"))
        assertEquals("Zeynep", oneName(resolve(c, "Zeynepe")))
    }

    @Test fun `iyelikten tureyen anne baska birinin soyadina kelime eslesmesi yapmaz`() {
        val c = listOf(Contact("Ayşe Anne", "0555 444 44 44"), Contact("Burak", "0555 555 55 55"))
        val r = resolve(c, "Annemi")
        assertTrue("dogrudan arama olmamali: $r", r !is Resolution.One)
    }

    @Test fun `Anne diye kayitliysa annemi ile tam eslesir`() {
        val c = listOf(Contact("Anne", "0555 666 66 66"))
        assertEquals("Anne", oneName(resolve(c, "Annemi")))
    }

    @Test fun `Turkce karakter farki onemsiz`() {
        val c = listOf(Contact("Çağla Öztürk", "0555 777 77 77"))
        assertEquals("Çağla Öztürk", oneName(resolve(c, "cagla")))
    }

    @Test fun `contact_id secimi yalniz o numarayi dondurur`() {
        val c = listOf(Contact("Ahmet", "0555 111 11 11"), Contact("Ahmet", "0532 222 22 22"))
        val r = resolve(c, "Ahmet", "5322222222")
        assertEquals("5322222222", (r as Resolution.One).contact.id)
    }

    @Test fun `kisa adlar kirpilmaz`() {
        assertTrue("ece" in ContactMatcher.queryVariants("Ece"))
        assertTrue(ContactMatcher.queryVariants("Ali").none { it.length < 3 })
    }

    @Test fun `bulunamayan kisi null`() {
        assertNull(resolve(listOf(Contact("Burak", "0555 555 55 55")), "Selin"))
    }
}
