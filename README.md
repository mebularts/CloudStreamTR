# DiziPal — Cloudstream Plugin (Standalone Repo)

Bu depo **Cloudstream** uygulaması için **DiziPal** sağlayıcısını içerir. `mainUrl` güncel olarak **https://dizipal1103.com** şeklindedir.

## Yerel Derleme (önerilen)
1. **JDK 17** kurulu olmalı.
2. Android SDK kurulu olmalı (platform 34 / build-tools 34.0.0 yeterli).
3. Terminalden çalıştırın:
   ```bash
   # İlk defa ise wrapper'ı üretmek için (Gradle yüklü ise)
   gradle wrapper

   ./gradlew --no-daemon clean make makePluginsJson
   ```
4. Çıktılar:
   - `.cs3` dosyası: `DiziPal/build/` altında
   - `plugins.json`: `build/plugins.json` (root altında)

> Not: Wrapper jar bu zipte dahil değildir. Sistemde Gradle kurulu ise `gradle wrapper` komutuyla wrapper oluşur ve `./gradlew` kullanılabilir.

## GitHub Actions (harici aksiyon kullanmadan)
`.github/workflows/build.yml` dosyası **hiç dış action** kullanmaz. `master` (veya `main`) dalına push yaptığınızda `.cs3` ve `plugins.json` üretir ve **Release ('builds' tag)** altına asset olarak yükler. `repo.json` içinde `pluginLists` URL'i olarak release yolunu kullanabilirsiniz:

```
https://github.com/<owner>/<repo>/releases/download/builds/plugins.json
```

## Cloudstream'e ekleme (repo.json)
Kökte bir `repo.json` oluşturup şunu ekleyin (örnek):

```json
{
  "name": "Mebularts TR Repo",
  "description": "Cloudstream eklentileri",
  "manifestVersion": 1,
  "pluginLists": [
    "https://github.com/<owner>/<repo>/releases/download/builds/plugins.json"
  ]
}
```

## Dizin Yapısı
```
DiziPal/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/com/mebularts/DiziPal.kt
  src/main/kotlin/com/mebularts/DiziPalModels.kt
  src/main/kotlin/com/mebularts/DiziPalPlugin.kt
  src/main/kotlin/com/mebularts/bakalim.py
.github/workflows/build.yml
settings.gradle.kts
build.gradle.kts
gradle.properties
repo.json
```

## Uyarı
Hedef sitelerin kullanım şartlarına ve yasalara uyduğunuzdan emin olun. Bu proje yalnızca teknik entegrasyon ve eklenti geliştirme örneği olarak sağlanmıştır.
