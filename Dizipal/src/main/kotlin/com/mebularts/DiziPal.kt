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
            val req  = chain.request()
            val res  = chain.proceed(req)
            val body = res.peekBody(1024 * 1024).string()
            val doc  = runCatching { Jsoup.parse(body) }.getOrNull()
            val title = doc?.selectFirst("title")?.text()?.trim().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                cloudflareKiller.intercept(chain)
            } else res
        }
    }

    // Ana sayfa menüsü (hafif)
    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenen-bolumler" to "Yeni Eklenen Bölümler",
        "$mainUrl/yabanci-diziler"       to "Yabancı Diziler",
        "$mainUrl/film"                  to "Yeni Filmler",
        "$mainUrl/anime"                 to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPage(page)
        val doc = app.get(url, interceptor = interceptor).document

        val items = when {
            request.data.contains("/yeni-eklenen-bolumler") -> {
                // /bolum/... linklerinden dizi kartı üret (mutlak + göreli destek)
                doc.select("""a[href*="/bolum/"]""").mapNotNull { it.toEpisodeAsSeriesCard() }
            }
            request.data.contains("/yabanci-diziler") || request.data.contains("/anime") -> {
                doc.select("""a[href*="/series/"]""")
                    .distinctBy { it.attr("href") }
                    .mapNotNull { it.toSeriesCard() }
            }
            request.data.contains("/film") -> {
                (doc.select("""a[href*="/movies/"]""") + doc.select("""a[href*="/film/"] a[href*="/movies/"]"""))
                    .distinctBy { it.attr("href") }
                    .mapNotNull { it.toMovieCard() }
            }
            else -> emptyList()
        }

        val hasNext = doc.select("""a[rel="next"], a.next, li.active + li > a""").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ---------- Helpers ----------

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

    private fun normalizeHref(href: String?): String? {
        if (href.isNullOrBlank()) return null
        return if (href.startsWith("http")) href else fixUrl(href)
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Dizi" }
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href   = normalizeHref(attr("href")) ?: return null
        val title  = cardTitle() ?: href.substringAfterLast("/").ifBlank { "Film" }
        val poster = posterUrlNearby()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    /** /yeni-eklenen-bolumler sayfasındaki /bolum/... linkinden /series/<slug> üret */
    private fun Element.toEpisodeAsSeriesCard(): SearchResponse? {
        val rawHref  = normalizeHref(attr("href")) ?: return null
        val seriesSlug = extractSeriesSlugFromEpisodeUrl(rawHref) ?: return null
        val seriesUrl  = "$mainUrl/series/$seriesSlug"
        val title  = cardTitle() ?: seriesSlug.replace("-", " ")
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = poster }
    }

    /** https://dizipal1103.com/bolum/the-last-of-us-2x4-c13 → "the-last-of-us" */
    private fun extractSeriesSlugFromEpisodeUrl(href: String): String? {
        val idx = href.indexOf("/bolum/")
        if (idx < 0) return null
        val p = href.substring(idx + "/bolum/".length)
        if (p.isBlank()) return null
        val noCode = p.replace(Regex("-c\\d+$"), "")          // sondaki -c13 vb.
        return noCode.replace(Regex("-\\d+x\\d+.*$"), "")     // sondaki 2x4 vb.
    }

    // ---------- Arama ----------

    override suspend fun search(query: String): List<SearchResponse> {
        // JSON autocomplete varsa dene
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

        // HTML arama (fallback)
        val doc = app.get("$mainUrl/arama-yap?keyword=${query.encodeURL()}",
            interceptor = interceptor, referer = "$mainUrl/").document

        val results = mutableListOf<SearchResponse>()
        doc.select("""a[href*="/series/"]""").forEach { it.toSeriesCard()?.let(results::add) }
        doc.select("""a[href*="/movies/"]""").forEach { it.toMovieCard()?.let(results::add) }
        return results
    }

    override suspend fun quickSearch(query: String) = search(query)

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title = if (this.trTitle.isNullOrBlank()) this.title else this.trTitle
        val href  = normalizeHref("$mainUrl${this.url}")!!
        val posterUrl = fixUrlNull(this.poster)
        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    // ---------- Load ----------

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
            url.contains("/series/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()
                val eps = doc.select("""a[href*="/bolum/"]""").mapNotNull { a ->
                    val ehref = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                    val se = Regex("(\\d+)x(\\d+)").find(ehref)
                    val s  = se?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val e  = se?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val en = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm" }

                    newEpisode(ehref) {
                        name = en
                        season = s
                        episode = e
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            }

            url.contains("/movies/") || url.contains("/film/") -> {
                val title = titleMeta ?: doc.title().substringBefore("|").trim()
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    // ---------- Linkler ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "data » $data")
        val page = app.get(data, interceptor = interceptor, referer = "$mainUrl/").document

        // 1) iframeler → extractor veya m3u8
        page.select("""iframe[src]""").forEach { iframe ->
            val src = normalizeHref(iframe.attr("src")) ?: return@forEach
            Log.d("DZP", "iframe » $src")

            if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) return@forEach

            val body = app.get(src, referer = "$mainUrl/").text
            Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)""").find(body)
                ?.groupValues?.getOrNull(1)
                ?.let { m3u ->
                    M3u8Helper.generateM3u8(
                        source    = name,
                        name      = name,
                        streamUrl = fixUrl(m3u),
                        referer   = "$mainUrl/"
                    ).forEach(callback)
                    return true
                }
        }

        // 2) Sayfa HTML içinde doğrudan m3u8
        Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)""").find(page.html())
            ?.groupValues?.getOrNull(1)
            ?.let { m3u ->
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

    // ---------- Utilities ----------

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun String.addPage(page: Int): String =
        if (page <= 1) this else if (this.contains("?")) "$this&page=$page" else "$this?page=$page"
}
