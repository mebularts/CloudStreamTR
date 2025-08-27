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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1103.com"
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

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenen-bolumler" to "Yeni Eklenen Bölümler",
        "$mainUrl/yabanci-dizi-izle"     to "Yabancı Diziler",
        "$mainUrl/hd-film-izle"          to "HD Filmler",
        "$mainUrl/anime"                 to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = app.get(url, interceptor = interceptor).document
        val list = when {
            request.data.contains("/yeni-eklenen-bolumler") -> {
                val seriesLinks = doc.select("""a[href*="/series/"]""").mapNotNull { it.toSeriesCard() }
                if (seriesLinks.isNotEmpty()) seriesLinks
                else doc.select("""a[href*="/bolum/"]""").mapNotNull { it.toEpisodeAsSeriesCard() }
            }
            request.data.contains("/yabanci-dizi-izle") || request.data.contains("/anime") -> {
                doc.select("""a[href*="/series/"]""").distinctBy { it.attr("href") }.mapNotNull { it.toSeriesCard() }
            }
            request.data.contains("/hd-film-izle") -> {
                doc.select("""a[href*="/movies/"]""").distinctBy { it.attr("href") }.mapNotNull { it.toMovieCard() }
            }
            else -> emptyList()
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

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)

    private fun Element.toSeriesCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Dizi" }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { /* poster yok */ }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Film" }
        return newMovieSearchResponse(title, href, TvType.Movie) { /* poster yok */ }
    }

    /** /yeni-eklenen-bolumler → bölüm linkini dizi kartına dönüştür. */
    private fun Element.toEpisodeAsSeriesCard(): SearchResponse? {
        val raw = normalizeHref(attr("href")) ?: return null
        val seriesUrl = guessSeriesUrlFromEpisode(raw) ?: return null
        val title  = cardTitle() ?: seriesUrl.substringAfterLast("/").replace("-", " ")
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { /* poster yok */ }
    }

    /** https://dizipal1103.com/bolum/modern-kadin-1x1-c05 → https://dizipal1103.com/series/modern-kadin */
    private fun guessSeriesUrlFromEpisode(epUrl: String): String? {
        val path = epUrl.substringAfter("/bolum/", "")
        if (path.isBlank()) return null
        val base = path.replace(Regex("-\\d+x\\d+(-c\\d+)?$"), "").substringBefore("?")
        return "$mainUrl/series/$base"
    }

    /* -------------------- Search -------------------- */

    data class SearchItem(
        val title: String,
        val trTitle: String? = null,
        val url: String,
        val poster: String? = null,
        val type: String // "series" | "movie"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        // JSON autocomplete
        runCatching {
            val res = app.post(
                "$mainUrl/api/search-autocomplete",
                headers     = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer     = "$mainUrl/",
                interceptor = interceptor,
                data        = mapOf("query" to query)
            )
            val map = jacksonObjectMapper().readValue<Map<String, SearchItem>>(res.text)
            return map.values.map { it.toPostSearchResult() }
        }

        // HTML fallback
        val doc = app.get("$mainUrl/arama-yap?keyword=${query.encodeURL()}",
            interceptor = interceptor, referer = "$mainUrl/").document

        val out = mutableListOf<SearchResponse>()
        doc.select("""a[href*="/series/"]""").forEach { it.toSeriesCard()?.let(out::add) }
        doc.select("""a[href*="/movies/"]""").forEach { it.toMovieCard()?.let(out::add) }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title = if (this.trTitle.isNullOrBlank()) this.title else this.trTitle
        val href  = normalizeHref("$mainUrl${this.url}")!!
        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { /* poster yok */ }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { /* poster yok */ }
        }
    }

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val titleMeta = doc.selectFirst("h1, .g-title div, .title h1, meta[property='og:title']")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim()

        return when {
            url.contains("/series/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()

                val eps = doc.select("""a[href*="/bolum/"]""").mapNotNull { a ->
                    val href = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                    val se = Regex("(\\d+)x(\\d+)").find(href)
                    val s  = se?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val e  = se?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val en = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm" }

                    newEpisode(href) {
                        name = en
                        season = s
                        episode = e
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { /* poster yok */ }
            }

            url.contains("/movies/") || url.contains("/film/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()
                newMovieLoadResponse(title, url, TvType.Movie, url) { /* poster yok */ }
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
            emitSubtitlesFromBlob(decrypted, subtitleCallback)

            // m3u8 doğrudan
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                .findAll(decrypted)
                .map { it.value }
                .distinct()
                .forEach { m3u ->
                    M3u8Helper.generateM3u8(
                        source    = name,
                        name      = name,
                        streamUrl = fixUrl(m3u),
                        referer   = "$mainUrl/"
                    ).forEach(callback)
                }
            if (Regex("""\.m3u8""").containsMatchIn(decrypted)) return true

            // Embed varsa extractor
            Regex("""https?://[^\s"']+""")
                .findAll(decrypted)
                .map { it.value }
                .distinct()
                .forEach { url ->
                    if (loadExtractor(url, "$mainUrl/", subtitleCallback, callback)) return true
                }
        }

        // 2) AMP sayfasında player olabilir
        page.select("""link[rel=amphtml][href]""").firstOrNull()?.attr("href")?.let { ampHref ->
            val amp = app.get(fixUrl(ampHref), referer = data, interceptor = interceptor).document
            (amp.select("amp-iframe[src]") + amp.select("iframe[src]")).forEach { ifr ->
                val src = normalizeHref(ifr.attr("src")) ?: return@forEach
                if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) return true
                val t = app.get(src, referer = "$mainUrl/").text
                emitSubtitlesFromBlob(t, subtitleCallback)
                Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(t).map { it.value }.forEach { m3u ->
                    M3u8Helper.generateM3u8(
                        source    = name,
                        name      = name,
                        streamUrl = fixUrl(m3u),
                        referer   = "$mainUrl/"
                    ).forEach(callback)
                    return true
                }
            }
        }

        // 3) Sayfadaki iframeleri tara
        page.select("""iframe[src]""").forEach { iframe ->
            val src = normalizeHref(iframe.attr("src")) ?: return@forEach
            Log.d("DZP", "iframe » $src")
            if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) return@forEach
            val body = app.get(src, referer = "$mainUrl/").text
            emitSubtitlesFromBlob(body, subtitleCallback)
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(body).map { it.value }.forEach { m3u ->
                M3u8Helper.generateM3u8(
                    source    = name,
                    name      = name,
                    streamUrl = fixUrl(m3u),
                    referer   = "$mainUrl/"
                ).forEach(callback)
                return true
            }
        }

        // 4) Sayfanın HTML’inde m3u8 var mı?
        emitSubtitlesFromBlob(page.html(), subtitleCallback)
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

    /** Sayfadaki data-rm-k JSON’unu ve appCKey’i kullanarak AES/CBC/PKCS5 çöz. */
    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = jacksonObjectMapper().readValue<EncPayload>(json)

        // window.appCKey = 'BASE64';
        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: return null

        val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
        val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}$")))
            keyStr.hexToBytes()
        else
            keyStr.toByteArray(Charsets.UTF_8)

        val ivBytes   = payload.iv.hexToBytes()
        val cipherBin = Base64.getDecoder().decode(payload.ciphertext)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val plain = cipher.doFinal(cipherBin)
        return String(plain, Charsets.UTF_8)
    }

    /** Şerit halinde gelen altyazıları yakala ve gönder. */
    private fun emitSubtitlesFromBlob(blob: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val sub = Regex("""(?i)"?subtitles?"?\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)"?subtitle"?\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.getOrNull(1)
        if (sub.isNullOrBlank()) return
        sub.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { item ->
            val lang = item.substringAfter("[").substringBefore("]").ifBlank { "Und" }
            val url  = item.replace("[$lang]", "")
            subtitleCallback.invoke(SubtitleFile(lang, fixUrl(url)))
        }
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
