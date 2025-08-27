// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YabanciDiziCo : MainAPI() {
    override var name = "YabanciDiziCo"
    override var mainUrl = "https://yabancidizi.so"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true

    /** olası ayna alan adları */
    private val altDomains = listOf(
        "https://yabancidizi.so",
        "https://www.yabancidizi.so",
        "https://yabancidizi.co",
        "https://www.yabancidizi.co",
    )

    // --- Cloudflare için hafif interceptor (Cloudstream’le uyumlu) ---
    private val cfKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val res = chain.proceed(chain.request())
            val body = res.peekBody(512 * 1024).string()
            val title = runCatching { Jsoup.parse(body).title() }.getOrNull().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                cfKiller.intercept(chain)
            } else res
        }
    }}

    /* ===================== MAIN PAGE ===================== */

    override val mainPage = mainPageOf(
        "$mainUrl/"          to "Ana Sayfa",
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/film-izle" to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = getDoc(url)

        val cards: List<SearchResponse> = when {
            request.data.endsWith("/dizi-izle") -> {
                doc.select("""a[href*="/dizi/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
            request.data.endsWith("/film-izle") -> {
                doc.select("""a[href*="/film/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
            else -> {
                // ana sayfada karışık akış olabilir
                (doc.select("""a[href*="/dizi/"]""") + doc.select("""a[href*="/film/"]"""))
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
        }

        val hasNext = doc.select("""a[rel=next], li.active + li > a, .pagination a:matchesOwn(İleri|Sonraki)""").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext)
    }

    private fun Element.cardTitle(): String {
        val t = attr("title").ifBlank { text() }.ifBlank { parent()?.attr("title").orEmpty() }
        return t.trim().ifBlank { "İçerik" }
    }

    private fun Element.cardPoster(): String? {
        val img = selectFirst("img") ?: parent()?.selectFirst("img")
        val src = img?.attr("data-src")?.ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun Element.toCard(): SearchResponse? {
        val href   = absUrl("href").ifBlank { return null }
        val title  = cardTitle()
        val poster = cardPoster()
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    /* ===================== SEARCH ===================== */

    override suspend fun search(query: String): List<SearchResponse> {
        // çoğu WP sitede ?s= çalışır; sitede kapalıysa HTML fallback yine işe yarar
        val tries = altDomains.map { "$it/?s=${query.encodeURL()}" }
        val out = mutableListOf<SearchResponse>()
        for (u in tries) {
            val doc = runCatching { getDoc(u) }.getOrNull() ?: continue
            (doc.select("""a[href*="/dizi/"]""") + doc.select("""a[href*="/film/"]"""))
                .distinctBy { it.absUrl("href") }
                .forEach { el -> el.toCard()?.let(out::add) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* ===================== LOAD (DETAIL) ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url)

        val poster = doc
            .selectFirst("""meta[property="og:image"]""")?.attr("content")
            ?: doc.selectFirst("""meta[name="twitter:image"]""")?.attr("content")
            ?: doc.selectFirst("img[itemprop=thumbnailUrl]")?.attr("src")

        val title = doc.selectFirst("h1, .title h1, h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("""meta[property="og:title"]""")?.attr("content")?.trim()
            ?: doc.title().substringBefore("|").trim()

        return when {
            url.contains("/film/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
            url.contains("/dizi/") -> {
                // Bölümler: /dizi/<slug>/sezon-1/bolum-1
                val eps = doc.select("""a[href*="/sezon-"][href*="/bolum-"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        val (s, e) = parseSeasonEpisode(href)
                        val ename = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $e" }
                        newEpisode(href) {
                            name = ename
                            season = s
                            episode = e
                        }
                    }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String): Pair<Int?, Int?> {
        val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href)
        val s = m?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = m?.groupValues?.getOrNull(2)?.toIntOrNull()
        return s to e
    }

    /* ===================== LINKS (PLAYER) ===================== */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = getDoc(data)
        var found = false

        // 1) görünen iframe'ler
        (page.select("iframe[src]") + page.select("amp-iframe[src]")).forEach { fr ->
            val src = fr.absUrl("src")
            if (src.isNotBlank() && handlePlayableUrl(src, data, subtitleCallback, callback)) {
                found = true
            }
        }
        if (found) return true

        // 2) AMP sayfası
        page.selectFirst("""link[rel=amphtml][href]""")?.absUrl("href")?.let { ampUrl ->
            runCatching { getDoc(ampUrl) }.getOrNull()?.let { amp ->
                (amp.select("iframe[src]") + amp.select("amp-iframe[src]")).forEach { fr ->
                    val src = fr.absUrl("src")
                    if (src.isNotBlank() && handlePlayableUrl(src, data, subtitleCallback, callback)) {
                        return true
                    }
                }
                if (tryM3u8OnText(amp.outerHtml(), data, callback)) return true
            }
        }

        // 3) sayfanın kendi HTML’i
        if (tryM3u8OnText(page.outerHtml(), data, callback)) return true

        // 4) script içeriğinde JWPlayer/plyr kaynakları
        val scripts = page.select("script").joinToString("\n") { it.data() }
        if (tryM3u8OnText(scripts, data, callback)) return true

        return false
    }

    private suspend fun handlePlayableUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // yerleşik extractor’lar (ok.ru, dood, filemoon, voe, streamtape, sibnet, vidmoly, vudeo, etc.)
        val ok = loadExtractor(url, referer, subtitleCallback, callback)
        if (ok) return true

        // embed sayfasının iç metninde .m3u8 olabilir
        return runCatching {
            val body = app.get(url, referer = referer).text
            tryM3u8OnText(body, referer, callback)
        }.getOrDefault(false)
    }

    private fun tryM3u8OnText(text: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text)
            .map { it.value }
            .distinct()
            .forEach { hls ->
                M3u8Helper.generateM3u8(
                    source = name,
                    name = name,
                    streamUrl = fixUrl(hls),
                    referer = referer
                ).forEach(callback)
                any = true
            }
        return any
    }

    /* ===================== HELPERS ===================== */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun String.addPage(page: Int): String =
        if (page <= 1) this else if ('?' in this) "$this&page=$page" else "$this?page=$page"

    private suspend fun getDoc(u: String): Document {
        // doğrudan dene
        runCatching { return app.get(u, interceptor = cfInterceptor).document }
        // ayna domain’lerle zorla
        for (base in altDomains) {
            val candidate = swapHost(u, base)
            val resp = runCatching { app.get(candidate, interceptor = cfInterceptor).document }.getOrNull()
            if (resp != null) return resp
        }
        // son çare throw
        return app.get(u, interceptor = cfInterceptor).document
    }

    private fun swapHost(url: String, newBase: String): String {
        for (b in altDomains) if (url.startsWith(b)) return url.replaceFirst(b, newBase)
        return if (url.startsWith("/")) newBase + url else url
    }
}
