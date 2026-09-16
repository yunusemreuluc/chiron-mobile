# Aıron Mobil

Android'de çalışan Türkçe sesli asistan. Bilgisayardaki [Aıron](https://github.com/yunusemreuluc/Airon)'un telefon eşi.

- **Konuşma:** Gemini Live (yerel ses modeli), Türkçe, 9 hazır ses.
- **"Aıron" uyandırma sözü:** tamamen telefonda, çevrimdışı —
  [openWakeWord](https://github.com/dscripka/openWakeWord) akış hattının Kotlin karşılığı
  (`wake/OwwDetector.kt`, onnxruntime). Model kullanıcının kendi sesiyle eğitildi
  ([airon-wake](https://github.com/yunusemreuluc/airon-wake)); uygulamada kayıt ekranı var.
- **Araçlar:** rehberden arama, WhatsApp/SMS, uygulama açma ve içinde arama, YouTube'da oynatma,
  alarm/zamanlayıcı, fener, ses/medya, bildirimler, pil, Google Haritalar navigasyonu,
  PC'deki Aıron'a soru/komut, hızlı not, istek listesi.
- **Araba modu:** Bluetooth eller serbest, müzik kısma, gelen mesajı okuyup cevap sorma.
- **Arayüz:** PC'deki tel kafes çekirdeğin Compose Canvas karşılığı.

## Derleme

Android SDK 35, JDK 17.

```
./gradlew assembleRelease
```

İsteğe bağlı varsayılanlar için proje köküne `secrets.properties` (git'e girmez):

```
GEMINI_API_KEY=...
PC_URL=https://<pc-adi>.<tailnet>.ts.net
```

İkisi de uygulamanın Ayarlar ekranından da girilebilir. PC köprüsü
[Tailscale](https://tailscale.com) ve PC'deki Aıron'un PIN kapısı üzerinden çalışır;
PC olmadan da asistanın telefon işleri çalışır.

## Notlar

- **Lisanslar:** `assets/wake/melspectrogram.onnx` ve `embedding_model.onnx`
  [openWakeWord](https://github.com/dscripka/openWakeWord)'den alındı; o projenin hazır modelleri
  [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) lisanslı (gömme modeli
  Google'ın Apache 2.0 lisanslı speech_embedding'inin yeniden uygulaması). `airon.onnx` openWakeWord
  özellikleriyle eğitildiği için aynı lisansla (CC BY-NC-SA 4.0) paylaşılıyor — ticari kullanım yok.
- Kişisel kullanım için yazıldı; bazı izinler (erişilebilirlik ile WhatsApp'ta gönder,
  tüm uygulamaları sorgulama) mağaza politikalarına uygun değildir.
