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
        "$mainUrl/"                       to "Son Eklenenler",
        "$mainUrl/dizi/"                  to "Diziler",
        "$mainUrl/film/"                  to "Filmler",
        "$mainUrl/kategori/anime/"        to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = app.get(url, interceptor = interceptor).document

        val list = when {
            request.data.contains("/dizi/") -> parseSeriesCards(doc)
            request.data.contains("/film/") -> parseMovieCards(doc)
            else -> parseHome(doc) // Son eklenenler
        }

        val hasNext = doc.select("""a[rel=next], .pagination a.next, .pagination-btn:contains(İleri)""").isNotEmpty()
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    private fun parseHome(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        // “Güncel bölümler” gibi widget’lardan bölüm linkini → dizi kartına
        doc.select(".current-episodes a.episode-link[href*=\"/bolum/\"]")
            .mapNotNull { it.toEpisodeAsSeriesCard() }
            .also(out::addAll)

        // Ana içerik alanında görünen dizi/film kartları
        out += parseSeriesCards(doc)
        out += parseMovieCards(doc)
        return out.distinctBy { it.url }
    }

    private fun contentScope(doc: Document): Element {
        // Menü/üstbilgi/altbilgi dışında bir kapsayıcı hedefle
        return doc.selectFirst("#aa-wp .bd") ?: doc.body()
    }

    private fun parseSeriesCards(doc: Document): List<SearchResponse> {
        val scope = contentScope(doc)
        val out = mutableListOf<SearchResponse>()

        // article + içindeki /dizi/
        scope.select("article a[href*=\"/dizi/\"]").forEach { it.toSeriesCard()?.let(out::add) }

        // grid kartları (onclick ile yönlendirilenler)
        scope.select("article[onclick*=\"location.href\"]").forEach { art ->
            val href = Regex("""location\.href\s*=\s*'([^']+)'""").find(art.attr("onclick"))?.groupValues?.getOrNull(1)
            if (href?.contains("/dizi/") == true) {
                val fakeA = Element("a").attr("href", href).text(art.selectFirst(".movie-card-title")?.text().orEmpty())
                fakeA.toSeriesCard()?.let(out::add)
            }
        }

        // olası diğer bağlantılar (menüyü dışla)
        scope.select("""a[href*="/dizi/"]""")
            .filterNot { it.closest("nav, header, footer, .menu, .kw") != null }
            .forEach { it.toSeriesCard()?.let(out::add) }

        return out.distinctBy { it.url }
    }

    private fun parseMovieCards(doc: Document): List<SearchResponse> {
        val scope = contentScope(doc)
        val out = mutableListOf<SearchResponse>()

        scope.select("article a[href*=\"/film/\"]").forEach { it.toMovieCard()?.let(out::add) }

        scope.select("article[onclick*=\"location.href\"]").forEach { art ->
            val href = Regex("""location\.href\s*=\s*'([^']+)'""").find(art.attr("onclick"))?.groupValues?.getOrNull(1)
            if (href?.contains("/film/") == true) {
                val fakeA = Element("a").attr("href", href).text(art.selectFirst(".movie-card-title")?.text().orEmpty())
                fakeA.toMovieCard()?.let(out::add)
            }
        }

        scope.select("""a[href*="/film/"]""")
            .filterNot { it.closest("nav, header, footer, .menu, .kw") != null }
            .forEach { it.toMovieCard()?.let(out::add) }

        return out.distinctBy { it.url }
    }

    /* -------------------- Helpers (cards) -------------------- */

    private fun Element.cardTitle(): String? {
        val t = attr("title").ifBlank { text() }.ifBlank { parent()?.attr("title").orEmpty() }.trim()
        return t.takeIf { it.isNotBlank() && it.lowercase() !in setOf("diziler", "filmler", "anime") }
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
        if (!href.contains("/dizi/")) return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Dizi" }.replace("-", " ").trim()
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
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

    /** …/bolum/<slug>-<S>-sezon-<E>-bolum(-izle)? → …/dizi/<slug>/ */
    private fun guessSeriesUrlFromEpisode(epUrl: String): String? {
        val path = epUrl.substringAfter("/bolum/", "")
        if (path.isBlank()) return null
        val slug = path.replace(Regex("(-\\d+-sezon-\\d+-bolum(-izle)?)$"), "")
            .substringBefore("?").trimEnd('/')
        if (slug.isBlank()) return null
        return "$mainUrl/dizi/$slug/"
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress: ?s=...
        val doc = app.get("$mainUrl/?s=${query.encodeURL()}",
            interceptor = interceptor, referer = "$mainUrl/").document

        val out = mutableListOf<SearchResponse>()
        out += parseSeriesCards(doc)
        out += parseMovieCards(doc)
        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst("""img[src*="/storage/"]""")?.attr("src")
        )

        val titleMeta = doc.selectFirst("h1, .movie-title, meta[property='og:title']")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim()

        return when {
            url.contains("/dizi/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()

                // Dizi bölümleri: tüm /bolum/ linklerini tara
                val eps = doc.select("""a[href*="/bolum/"]""").mapNotNull { a ->
                    val href = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                    val se = Regex("""-(\d+)-sezon-(\d+)-bolum""").find(href)
                    val s  = se?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val e  = se?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val en = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $s×$e" }
                    newEpisode(href) { name = en; season = s; episode = e }
                }.distinctBy { it.data }

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
        Log.d("DZP", "loadLinks data = $data")
        val page = app.get(data, interceptor = interceptor, referer = "$mainUrl/").document

        // 0) #aa-options içindeki iframe
        page.select("#aa-options iframe[src], .video-player iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            if (tryLink(src, data, subtitleCallback, callback)) return true
        }

        // 1) Şifreli payload + appCKey → (bazı temalarda bulunuyor)
        runCatching { decryptFromPage(page) }.getOrNull()?.let { decrypted ->
            val found = pushM3u8s(decrypted, callback)
            if (found) return true
            Regex("""https?://[^\s"'<>]+""").findAll(decrypted).map { it.value }.distinct().forEach { u ->
                if (tryLink(u, data, subtitleCallback, callback)) return true
            }
        }

        // 2) AMP sayfası (varsa)
        page.select("""link[rel=amphtml][href]""").firstOrNull()?.attr("href")?.let { ampHref ->
            val amp = app.get(fixUrl(ampHref), referer = data, interceptor = interceptor).document
            (amp.select("amp-iframe[src]") + amp.select("iframe[src]")).forEach { ifr ->
                val src = normalizeHref(ifr.attr("src")) ?: return@forEach
                if (tryLink(src, data, subtitleCallback, callback)) return true
            }
        }

        // 3) Diğer iframe’ler
        page.select("""iframe[src]""").forEach { iframe ->
            val src = normalizeHref(iframe.attr("src")) ?: return@forEach
            if (tryLink(src, data, subtitleCallback, callback)) return true
        }

        // 4) HTML içinde doğrudan .m3u8 var mı?
        if (pushM3u8s(page.html(), callback)) return true

        return false
    }

    private suspend fun tryLink(
        url: String,
        refererPage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Önce extractor’ları dene
        if (loadExtractor(url, refererPage, subtitleCallback, callback)) return true

        // Embed sayfanın içini getir
        runCatching {
            val body = app.get(url, referer = refererPage, interceptor = interceptor).text

            // İç içe iframe → takip et
            Jsoup.parse(body).select("iframe[src]").forEach { ifr ->
                val src = normalizeHref(ifr.attr("src")) ?: return@forEach
                if (loadExtractor(src, url, subtitleCallback, callback)) return@forEach
                val nested = app.get(src, referer = url, interceptor = interceptor).text
                if (pushM3u8s(nested, callback)) return true
            }

            // Sayfanın kendi gövdesinde .m3u8
            if (pushM3u8s(body, callback)) return true

            // JWPlayer tarzı config: sources:[{file:"..."}] yakala
            Regex("""(?is)sources?\s*:\s*\[(.+?)\]""").find(body)?.groupValues?.getOrNull(1)?.let { arr ->
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>,}]*""").findAll(arr).map { it.value }.distinct().forEach { m3u ->
                    M3u8Helper.generateM3u8(
                        source    = name,
                        name      = name,
                        streamUrl = fixUrl(m3u),
                        referer   = refererPage
                    ).forEach(callback)
                }
                return true
            }
        }.getOrNull()?.let { used -> if (used is Boolean && used) return true }

        return false
    }

    private fun pushM3u8s(text: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(text).map { it.value }.distinct().forEach { m3u ->
            M3u8Helper.generateM3u8(
                source    = name,
                name      = name,
                streamUrl = fixUrl(m3u),
                referer   = "$mainUrl/"
            ).forEach(callback)
            any = true
        }
        return any
    }

    /** (Bazı eski dizipal temaları için) Sayfadaki data-rm-k JSON’u ve appCKey ile AES/CBC çöz. */
    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = jacksonObjectMapper().readValue<EncPayload>(json)

        // window.appCKey = 'BASE64';
        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: return null

        val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
        val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}\$")))
            keyStr.hexToBytes()
        else keyStr.toByteArray(Charsets.UTF_8)

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
        if (page <= 1) this else if (this.contains("?")) "$this&paged=$page" else "$this?page=$page"

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
