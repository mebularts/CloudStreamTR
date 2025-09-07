// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CizgiVeDizi : MainAPI() {
    override var name = "Çizgi ve Dizi"
    override var mainUrl = "https://www.cizgivedizi.com"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true

    /** Site bazen www’siz kullanılıyor, otomatik dene */
    private val mirrors = listOf(
        "https://www.cizgivedizi.com",
        "https://cizgivedizi.com"
    )

    /** Hafif CF/anti-robot yakalayıcı: başlıkta “Just a moment…” görürse tekrar dener */
    private val softCf by lazy {
        object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val res = chain.proceed(chain.request())
                val body = res.peekBody(640 * 1024).string()
                val title = runCatching { Jsoup.parse(body).title() }.getOrNull().orEmpty()
                return if (title.equals("Just a moment...", true)) {
                    // basit tekrar dene
                    chain.proceed(chain.request())
                } else res
            }
        }
    }

    /* ===================== MAIN PAGE ===================== */

    override val mainPage = mainPageOf(
        "$mainUrl/"     to "Ana Sayfa",
        "$mainUrl/film" to "Filmler",
        "$mainUrl/dizi" to "Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = getDoc(url)

        // Verdiğin yapıya göre: #main içindeki <a href="..."> kartları
        val anchors = doc.select("#main > a[href], .album #main > a[href]")
        val cards = anchors.mapNotNull { it.toCard() }

        val hasNext = doc.select("""a[rel=next], .pagination a:matchesOwn(İleri|Sonraki)""").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext)
    }

    private fun Element.cardPoster(): String? {
        // poster-div içindeki img.poster öncelikli
        val img = selectFirst(".poster-div img.poster")
            ?: selectFirst("img.poster")
            ?: selectFirst("img.card-img-top")
        val src = img?.attr("data-src")?.ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun Element.cardTitle(): String {
        val t = selectFirst(".card-overlay h5.card-title")?.text()
            ?: selectFirst("h5.card-title")?.text()
            ?: attr("title").ifBlank { text() }
        return t?.trim().orEmpty().ifBlank { "İçerik" }
    }

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { return null }
        val poster = cardPoster()
        val title  = cardTitle()
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else null
    }

    /* ===================== SEARCH ===================== */

    override suspend fun search(query: String): List<SearchResponse> {
        // Sitede net bir arama ucu paylaşılmadı; yaygın kalıpları dene:
        val tries = listOf(
            "$mainUrl/ara?q=${query.encodeURL()}",
            "$mainUrl/search?q=${query.encodeURL()}",
            "$mainUrl/?s=${query.encodeURL()}",
            "$mainUrl/?arama=${query.encodeURL()}"
        )
        val out = mutableListOf<SearchResponse>()
        for (u in tries) {
            val doc = runCatching { getDoc(u) }.getOrNull() ?: continue
            // listeler aynı #main düzenine oturuyor
            doc.select("#main > a[href]").forEach { a -> a.toCard()?.let(out::add) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* ===================== LOAD (DETAY) ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url)

        val poster = doc.selectFirst("""meta[property="og:image"]""")?.attr("content")
            ?: doc.selectFirst("""meta[name="twitter:image"]""")?.attr("content")
            ?: doc.selectFirst(".poster-div img.poster, img.poster, .thumb img")?.attr("src")

        return when {
            url.contains("/film/") -> {
                val title = doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("""meta[property="og:title"]""")?.attr("content")?.trim()
                    ?: doc.title().substringBefore("|").trim()

                // açıklama (bilgi amaçlı — player linkleri loadLinks’te çözülecek)
                val plot = doc.selectFirst("p.lead.text-body-secondary, p.lead")?.text()?.trim()

                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrlNull(poster)
                    this.plot = plot
                }
            }

            url.contains("/dizi/") -> {
                // Dizi adı sayfada <div><h4>…</h4></div> şeklinde veriliyor olabilir
                val title = doc.selectFirst("div > h4")?.text()?.trim()
                    ?: doc.selectFirst("h1, h2")?.text()?.trim()
                    ?: doc.selectFirst("""meta[property="og:title"]""")?.attr("content")?.trim()
                    ?: doc.title().substringBefore("|").trim()

                val description = doc.selectFirst("p.lead")?.text()?.trim()

                // Bölümler: tüm varyasyonlar için a.bolum
                val episodes = doc.select("a.bolum[href]")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        // H5 başlık içinde: <div class=noInSeason>…</div><div class=noInSerie>…</div>)Ad
                        val h5 = a.selectFirst("h5.card-title")
                        val sTxt = h5?.selectFirst(".noInSeason")?.text()?.trim().orEmpty()
                        val eTxt = h5?.selectFirst(".noInSerie")?.text()?.trim().orEmpty()

                        val season = sTxt.toIntOrNull() ?: 1 // boşsa tek sezon say
                        val episode = eTxt.toIntOrNull()
                            ?: Regex("""/dizi/[^/]+/[^/]+/(\d+)/""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                        val ename = h5?.ownText()?.substringAfter(")")?.trim()
                            ?: a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm ${episode ?: ""}".trim() }

                        newEpisode(href) {
                            name = ename
                            this.season = season
                            this.episode = episode
                        }
                    }
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = fixUrlNull(poster)
                    this.plot = description
                }
            }

            else -> null
        }
    }

    /* ===================== LINKS (PLAYER) ===================== */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = getDoc(data)

        // 1) #video içindeki iframe
        page.select("#video iframe[src], iframe[src]").forEach { fr ->
            val src = fr.absUrl("src")
            if (src.isBlank()) return@forEach

            // Bilinen host’ları Cloudstream extractor’larına bırak
            if (loadExtractor(src, data, subtitleCallback, callback)) return true

            // Embed sayfasının kendi HTML’inde .m3u8 arayalım (Sibnet vs.)
            val text = runCatching { app.get(src, referer = data).text }.getOrNull().orEmpty()
            if (tryM3u8(text, src, callback)) return true
        }

        // 2) sayfa HTML'i içinde m3u8
        if (tryM3u8(page.outerHtml(), data, callback)) return true

        // 3) script’ler içinde m3u8 (jwplayer/plyr)
        val scripts = page.select("script").joinToString("\n") { it.data() }
        if (tryM3u8(scripts, data, callback)) return true

        return false
    }

    private fun tryM3u8(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(html)
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
        runCatching { return app.get(u, interceptor = softCf).document }
        // ayna domain’leri sırayla dene
        for (m in mirrors) {
            val alt = swapHost(u, m)
            val doc = runCatching { app.get(alt, interceptor = softCf).document }.getOrNull()
            if (doc != null) return doc
        }
        return app.get(u, interceptor = softCf).document
    }

    private fun swapHost(url: String, newBase: String): String {
        for (b in mirrors) if (url.startsWith(b)) return url.replaceFirst(b, newBase)
        return if (url.startsWith("/")) newBase + url else url
    }
}
