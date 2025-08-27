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

    override val mainPage = mainPageOf(
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/"      to "Son Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPagePath(page)
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        val items = doc.select("article a[href]")
            .filter { a ->
                val h = a.attr("href")
                h.contains("/dizi/") || h.contains("/film/")
            }
            .distinctBy { it.attr("href") }

        items.forEach { a ->
            val href   = normalizeHref(a.attr("href")) ?: return@forEach
            val poster = a.closest("article")?.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let(::fixUrlNull)
            val title  = a.closest("article")?.selectFirst("h2, h3, .tt, .title")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }.ifBlank { href.substringAfterLast("/").replace("-", " ").trim() }

            if (href.contains("/dizi/")) {
                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                cards += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.select("a[rel=next], .pagination a[aria-label='Next'], .pagination a.next").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeURL()}"
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        val out = mutableListOf<SearchResponse>()

        doc.select("article a[href]").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            val poster = a.closest("article")?.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let(::fixUrlNull)
            val title  = a.closest("article")?.selectFirst("h2, h3, .tt, .title")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }.ifBlank { href.substringAfterLast("/").replace("-", " ").trim() }

            when {
                href.contains("/dizi/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                href.contains("/film/") -> out += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst("img[src*='/storage/'], img[src*='/uploads/']")?.attr("src")
        )

        val title = doc.selectFirst("h1, .title h1, meta[property='og:title']")?.let { it.attr("content").ifBlank { it.text() } }?.trim()
            ?: doc.title().substringBefore("|").trim()

        return when {
            url.contains("/dizi/") -> {
                val eps = doc.select("""a[href*="/bolum/"]""")
                    .mapNotNull { a ->
                        val href = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                        val (s, e) = parseSeasonEpisode(href, a.text())
                        newEpisode(href) {
                            name = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $e" }
                            season = s
                            episode = e
                        }
                    }
                    .distinctBy { it.data }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            }

            url.contains("/film/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            url.contains("/bolum/") -> {
                val (s, e) = parseSeasonEpisode(url, doc.selectFirst("h1")?.text().orEmpty())
                val ep = newEpisode(url) {
                    name = doc.selectFirst("h1")?.text()?.trim()
                    season = s
                    episode = e
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(ep)) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String, textRaw: String): Pair<Int?, Int?> {
        Regex("""-(\d+)-sezon-(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val text = textRaw.lowercase().replace('ö','o').replace('ü','u')
        val s = Regex("""(\d+)\s*\.?\s*sezon""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = Regex("""(\d+)\s*\.?\s*bolum""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return s to e
    }

    /* -------------------- Links (player) -------------------- */

    data class EncPayload(val ciphertext: String, val iv: String, val salt: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "loadLinks: $data")

        // 1) Önce sayfanın kendisinde ara
        if (extractFromPageUrl(data, referer = "$mainUrl/", subtitleCallback, callback)) return true

        // 2) Sayfadaki iframeleri tara
        val page = app.get(data, interceptor = interceptor, referer = "$mainUrl/").document
        val iframes = page.select("iframe[src], amp-iframe[src]")
        for (ifr in iframes) {
            val src = normalizeHref(ifr.attr("src")) ?: continue
            Log.d("DZP", "iframe: $src")

            if (loadExtractor(src, data, subtitleCallback, callback)) return true
            if (extractFromPageUrl(src, referer = data, subtitleCallback, callback)) return true
        }

        // 3) Son çare: HTML’de saklı bağlantılar
        val html = page.html()
        if (pushM3u8s(html, referer = "$mainUrl/", callback)) return true
        if (pushMp4s(html, referer = "$mainUrl/", callback)) return true

        return false
    }

    /** Verilen sayfayı indirip: şifre çöz, <script> ve gövde içinden .m3u8/.mp4 çıkar. */
    private suspend fun extractFromPageUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(url, referer = referer, interceptor = interceptor)
        val body = res.text
        val doc  = res.document

        decryptFromPage(doc)?.let { dec ->
            if (pushM3u8s(dec, referer, callback)) return true
            if (pushMp4s(dec, referer, callback)) return true
            Regex("""https?://[^\s"'<>]+""").findAll(dec).map { it.value }.distinct().forEach { u ->
                if (loadExtractor(u, referer, subtitleCallback, callback)) return true
            }
        }

        if (pushM3u8s(body, referer, callback)) return true
        if (pushMp4s(body, referer, callback)) return true
        if (huntInScripts(doc, url, callback)) return true

        // iframe içleri
        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            if (loadExtractor(src, referer, subtitleCallback, callback)) return@forEach
            val subRes  = app.get(src, referer = url, interceptor = interceptor)
            val subBody = subRes.text
            val subDoc  = subRes.document
            if (pushM3u8s(subBody, url, callback)) return@forEach
            if (pushMp4s(subBody, url, callback)) return@forEach
            if (huntInScripts(subDoc, src, callback)) return@forEach
        }

        return false
    }

    /** JS içinde gizlenmiş bağlantıları (.m3u8 / atob) avla. */
    private suspend fun huntInScripts(
        doc: Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val scripts = doc.select("script")

        for (s in scripts) {
            val code = if (s.hasAttr("src")) {
                val src = fixUrl(s.attr("src"))
                runCatching { app.get(src, referer = referer, interceptor = interceptor).text }.getOrNull()
            } else s.data()

            if (code.isNullOrBlank()) continue

            if (pushM3u8s(code, referer, callback)) found = true

            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
                .findAll(code).map { it.groupValues[1] }.distinct().forEach { m3u ->
                    runCatching {
                        M3u8Helper.generateM3u8(
                            source    = name,
                            streamUrl = fixUrl(m3u),
                            referer   = referer,
                            name      = name
                        ).forEach(callback)
                        found = true
                    }
                }

            Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)""")
                .findAll(code).mapNotNull {
                    runCatching { String(Base64.getDecoder().decode(it.groupValues[1])) }.getOrNull()
                }.forEach { decoded ->
                    if (pushM3u8s(decoded, referer, callback)) found = true
                    if (pushMp4s(decoded, referer, callback)) found = true
                }

            if (pushMp4s(code, referer, callback)) found = true
        }

        return found
    }

    /** Metin içindeki .m3u8 linklerini ExtractorLink listesine çevir. */
    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { m3u ->
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = fixUrl(m3u),
                    referer   = referer,
                    name      = name
                ).forEach(callback)
                any = true
            }
        return any
    }

    /** Metin içindeki .mp4 linklerini ekle (yalın). */
    private suspend fun pushMp4s(
        text: String,
        referer: String, // şu an header set etmiyoruz; API alanları val olduğu için
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = fixUrl(link)
                    )
                )
                any = true
            }
        return any
    }

    /** Sayfadaki data-rm-k JSON’unu ve appCKey’i kullanarak AES/CBC/PKCS5 çöz (varsa). */
    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = jacksonObjectMapper().readValue<EncPayload>(json)

        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: return null

        val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
        val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}\$")))
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

    /* -------------------- Utils -------------------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun String.addPagePath(page: Int): String {
        if (page <= 1) return this
        return if (this.contains("?")) {
            if (this.contains("paged=")) this.replace(Regex("paged=\\d+"), "paged=$page")
            else if (this.endsWith("?") || this.endsWith("&")) this + "paged=$page"
            else "$this&paged=$page"
        } else {
            val base = this.trimEnd('/')
            "$base/page/$page/"
        }
    }

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)

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
