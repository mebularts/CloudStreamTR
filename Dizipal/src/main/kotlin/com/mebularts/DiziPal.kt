// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1050.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare
    override var sequentialMainPage = true
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val res = chain.proceed(req)
            val body = res.peekBody(1024 * 1024).string()
            val doc  = runCatching { Jsoup.parse(body) }.getOrNull()
            val title = doc?.selectFirst("title")?.text()?.trim().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                cloudflareKiller.intercept(chain)
            } else res
        }
    }

    /* -------------------- MainPage -------------------- */

    // Sitenin menüsü + listeler
    override val mainPage = mainPageOf(
        "$mainUrl"                    to "Son Eklenen Diziler",
        "$mainUrl/diziler"           to "Tüm Diziler",
        "$mainUrl/hd-film-izle"      to "HD Filmler",
        "$mainUrl/son-bolumler"      to "Son Bölümler",
        "$mainUrl/anime"             to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = app.get(url, interceptor = interceptor).document

        // Kart toplama: dizi/film linkleri
        val list = buildList<SearchResponse> {
            // Dizi kartları
            doc.select("""a[href*="/dizi/"]""")
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toSeriesCard() }
                .also(::addAll)

            // Film kartları
            doc.select("""a[href*="/film/"]""")
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toMovieCard() }
                .also(::addAll)

            // Bölüm kartları → dizi kartına dönüştür
            doc.select("""a[href*="/bolum/"]""")
                .mapNotNull { it.toEpisodeAsSeriesCard() }
                .also(::addAll)
        }

        val hasNext = doc.select("""a[rel="next"], a.next, li.active + li > a""").isNotEmpty()
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    /* -------------------- Helpers (cards) -------------------- */

    private fun Element.cardTitle(): String? {
        return attr("title").takeIf { it.isNotBlank() }
            ?: text().takeIf { it.isNotBlank() }
            ?: parent()?.attr("title")?.takeIf { it.isNotBlank() }
    }

    private fun Element.posterUrlNearby(): String? {
        val img = (this.selectFirst("img") ?: parent()?.selectFirst("img"))
        val src = img?.attr("data-src")?.ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)

    private fun Element.toSeriesCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Dizi" }.replace("-", " ").trim()
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Film" }.replace("-", " ").trim()
        val poster = posterUrlNearby()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    /** /bolum/... → bağlı /dizi/... URL’sini tahmin et ve dizi kartına çevir. */
    private fun Element.toEpisodeAsSeriesCard(): SearchResponse? {
        val raw = normalizeHref(attr("href")) ?: return null
        val seriesUrl = guessSeriesUrlFromEpisode(raw) ?: return null
        val title  = cardTitle() ?: seriesUrl.substringAfterLast("/").replace("-", " ").ifBlank { "Dizi" }
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = poster }
    }

    /** https://dizipalXXXX.com/bolum/butterfly-1-sezon-1-bolum-izle → https://dizipalXXXX.com/dizi/butterfly/ */
    private fun guessSeriesUrlFromEpisode(epUrl: String): String? {
        val slug = epUrl.substringAfter("/bolum/", "")
            .substringBefore("-1-sezon") // butterfly-1-sezon-1-bolum-izle -> butterfly
            .substringBefore("-2-sezon")
            .substringBefore("-3-sezon")
            .substringBefore("-4-sezon")
            .substringBefore("-5-sezon")
            .substringBefore("-izle")
            .ifBlank { return null }
        return "$mainUrl/dizi/$slug/"
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        // HTML arama (autocomplete olmadan)
        val doc = app.get("$mainUrl/arama-yap?keyword=${query.encodeURL()}",
            interceptor = interceptor, referer = "$mainUrl/").document

        val out = mutableListOf<SearchResponse>()
        doc.select("""a[href*="/dizi/"]""").forEach { it.toSeriesCard()?.let(out::add) }
        doc.select("""a[href*="/film/"]""").forEach { it.toMovieCard()?.let(out::add) }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst("""img[src*="/uploads/"]""")?.attr("src")
        )

        val titleMeta = doc.selectFirst("h1, .g-title div, .title h1, meta[property='og:title']")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim()

        return when {
            url.contains("/dizi/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()

                // Sayfadaki tüm bölüm linkleri (aynı sezonu da kapsar)
                val eps = doc.select("""a[href*="/bolum/"]""").mapNotNull { a ->
                    val href = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                    val se = Regex("""(\d+)\s*[-x]\s*(\d+)""").find(href.replace("-sezon-", "x").replace("-bolum", ""))
                    val s  = se?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val e  = se?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val en = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm" }

                    newEpisode(href) {
                        name = en
                        season = s
                        episode = e
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            }

            url.contains("/film/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    /* -------------------- Links (player) -------------------- */

    data class EncPayload(val ciphertext: String, val iv: String, val salt: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "data » $data")
        val page = app.get(data, interceptor = interceptor, referer = "$mainUrl/").document

        // 1) Şifreli payload + appCKey → çöz
        val decrypted = runCatching { decryptFromPage(page) }.getOrNull()
        if (!decrypted.isNullOrBlank()) {
            // Önce m3u8
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                .findAll(decrypted)
                .map { it.value }.distinct()
                .forEach { m3u ->
                    M3u8Helper.generateM3u8(
                        source    = name,
                        name      = name,
                        streamUrl = fixUrl(m3u),
                        referer   = "$mainUrl/"
                    ).forEach(callback)
                }
            // .m3u8 geldiyse yeter
            if (Regex("""\.m3u8""").containsMatchIn(decrypted)) return true

            // Diğer URL’ler → extractor
            Regex("""https?://[^\s"']+""")
                .findAll(decrypted)
                .map { it.value }.distinct()
                .forEach { url ->
                    if (loadExtractor(url, "$mainUrl/", subtitleCallback, callback)) return true
                }
        }

        // 2) AMP → amp-iframe/iframe
        page.select("""link[rel=amphtml][href]""").firstOrNull()?.attr("href")?.let { ampHref ->
            val amp = app.get(fixUrl(ampHref), referer = data, interceptor = interceptor).document
            (amp.select("amp-iframe[src]") + amp.select("iframe[src]")).forEach { ifr ->
                val src = normalizeHref(ifr.attr("src")) ?: return@forEach
                if (tryLink(src, subtitleCallback, callback)) return true
            }
        }

        // 3) Normal iframe’ler
        page.select("""iframe[src]""").forEach { iframe ->
            val src = normalizeHref(iframe.attr("src")) ?: return@forEach
            Log.d("DZP", "iframe » $src")
            if (tryLink(src, subtitleCallback, callback)) return@forEach
        }

        // 4) Sayfanın HTML/JS içinde doğrudan m3u8
        Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(page.html()).map { it.value }.forEach { m3u ->
            M3u8Helper.generateM3u8(
                source    = name,
                name      = name,
                streamUrl = fixUrl(m3u),
                referer   = "$mainUrl/"
            ).forEach(callback)
            return true
        }

        return false
    }

    /** iframe ya da gömülü sayfadaki linki dene: önce extractor, sonra gövde içinde .m3u8 ara. */
    private suspend fun tryLink(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadExtractor(url, "$mainUrl/", subtitleCallback, callback)) return true

        // içeriği getirip .m3u8 ara
        runCatching {
            val body = app.get(url, referer = "$mainUrl/").text
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(body).map { it.value }.forEach { m3u ->
                M3u8Helper.generateM3u8(
                    source    = name,
                    name      = name,
                    streamUrl = fixUrl(m3u),
                    referer   = "$mainUrl/"
                ).forEach(callback)
            }
        }.onSuccess {
            // bulunduysa true döndür
            val ok = it != null
            if (ok) return true
        }
        return false
    }

    /** Sayfadaki data-rm-k JSON’unu ve appCKey’i kullanarak AES/CBC/PKCS5 çöz. */
    private fun decryptFromPage(doc: org.jsoup.nodes.Document): String? {
        val encDiv = doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        // Jsoup text() &quot; → " çevirir, doğrudan JSON’a parse edebiliriz
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = jacksonObjectMapper().readValue<EncPayload>(json)

        // window.appCKey = 'BASE64';
        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: return null

        val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
        val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}\$")))
            keyStr.hexToBytes()
        else
            keyStr.toByteArray(Charsets.UTF_8) // son çare

        val ivBytes   = payload.iv.hexToBytes()
        val cipherBin = Base64.getDecoder().decode(payload.ciphertext)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val plain = cipher.doFinal(cipherBin)
        return String(plain, Charsets.UTF_8)
    }

    /* -------------------- Utils -------------------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun String.addPage(page: Int): String =
        if (page <= 1) this else if (this.contains("?")) "$this&page=$page" else "$this?page=$page"

    private fun String.hexToBytes(): ByteArray {
        val clean = this.replace(Regex("[^0-9a-fA-F]"), "")
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = ((clean[i].digitToInt(16) shl 4) + clean[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }
}
