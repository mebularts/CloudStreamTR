// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.Companion.fixUrl
import com.lagradost.cloudstream3.utils.AppUtils.Companion.fixUrlNull
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YabanciDiziCo : MainAPI() {
    override var name           = "YabanciDiziCo"
    override var mainUrl        = "https://yabancidizi.so"
    private val backupMainUrl   = "https://yabancidizi.co" // bazı rotalarda .co çalışıyor
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare
    override var sequentialMainPage = true
    private val cfKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { CloudflareInterceptor(cfKiller) }

    private class CloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val res = chain.proceed(chain.request())
            val body = res.peekBody(512 * 1024).string()
            val title = runCatching { Jsoup.parse(body).title() }.getOrNull().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                killer.intercept(chain)
            } else res
        }
    }

    /* =====================  MAIN PAGE  ===================== */

    override val mainPage = mainPageOf(
        "$mainUrl/"         to "Öne Çıkanlar",
        "$mainUrl/diziler"  to "Diziler",
        "$mainUrl/filmler"  to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) : HomePageResponse {
        val url = request.data.let { base ->
            if (page <= 1) base else if ('?' in base) "$base&page=$page" else "$base?page=$page"
        }

        val doc = getDoc(url)

        val cards: List<SearchResponse> = when {
            request.data.endsWith("/diziler") || request.data.endsWith("/filmler") -> {
                doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
            else -> {
                // Anasayfa: karışık bileşenlerden topla
                doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
        }

        val hasNext = doc.select("""a[rel=next], li.active + li > a, .pagination a:matchesOwn(İleri|Sonraki)""").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext)
    }

    private fun Element.cardTitle(): String {
        val t1 = attr("title").ifBlank { text() }
        val t2 = parent()?.attr("title").orEmpty()
        return (if (t1.isNotBlank()) t1 else t2).ifBlank { "İçerik" }.trim()
    }

    private fun Element.cardPoster(): String? {
        val img = selectFirst("img") ?: parent()?.selectFirst("img")
        val src = img?.attr("data-src")?.ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { return null }
        val title = cardTitle()
        val poster = cardPoster()
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    /* =====================  SEARCH  ===================== */

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val tries = listOf(
            "$mainUrl/?s=${q.encodeURL()}",
            "$mainUrl/arama?kelime=${q.encodeURL()}",
            "$backupMainUrl/?s=${q.encodeURL()}",
            "$backupMainUrl/arama?kelime=${q.encodeURL()}"
        )
        val out = mutableListOf<SearchResponse>()
        for (u in tries) {
            val doc = runCatching { getDoc(u) }.getOrNull() ?: continue
            doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                .forEach { el -> el.toCard()?.let(out::add) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* =====================  LOAD (DETAIL)  ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url)

        val poster = doc.selectFirst("""meta[property="og:image"]""")?.attr("content")
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
                val episodes = doc.select("""a[href*="/sezon-"][href*="/bolum-"]""")
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

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }

            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String): Pair<Int?, Int?> {
        // ör: /dizi/<slug>/sezon-2/bolum-10
        val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href)
        val s = m?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = m?.groupValues?.getOrNull(2)?.toIntOrNull()
        return s to e
    }

    /* =====================  LINKS (PLAYER)  ===================== */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val page = getDoc(data)
        var found = false

        // 1) Sayfadaki iframe'ler
        (page.select("iframe[src]") + page.select("amp-iframe[src]")).forEach { fr ->
            val src = fr.absUrl("src")
            if (src.isNotBlank() && handlePlayableUrl(src, data, subtitleCallback, callback)) {
                found = true
            }
        }
        if (found) return true

        // 2) AMP sürümü (varsa)
        page.selectFirst("""link[rel=amphtml][href]""")?.absUrl("href")?.let { ampUrl ->
            runCatching { getDoc(ampUrl) }.getOrNull()?.let { amp ->
                (amp.select("iframe[src]") + amp.select("amp-iframe[src]")).forEach { fr ->
                    val src = fr.absUrl("src")
                    if (src.isNotBlank() && handlePlayableUrl(src, data, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
        }

        // 3) Tüm içerikte çıplak bağlantıları tara (.m3u8 + barındırıcılar)
        val whole = page.outerHtml()
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val links = urlRegex.findAll(whole).map { it.value }.distinct().toList()

        for (u in links) {
            if (u.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    name = name,
                    streamUrl = fixUrl(u),
                    referer = data
                ).forEach(callback)
                found = true
            } else {
                if (handlePlayableUrl(u, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }

    private suspend fun handlePlayableUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        // Dahili extractor’lar (ok.ru, filemoon, streamtape, dood, voe, sibnet, vidmoly, vb.)
        val ok = loadExtractor(url, referer, subtitleCallback, callback)
        if (ok) return true

        // Embed sayfasının içinde .m3u8 olabilir – bir de sayfanın içini tara
        return runCatching {
            val body = app.get(url, referer = referer).text
            val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .findAll(body)
                .map { it.value }
                .distinct()
                .toList()
            if (m3u8s.isEmpty()) return@runCatching false
            m3u8s.forEach { hls ->
                M3u8Helper.generateM3u8(
                    source = name,
                    name = name,
                    streamUrl = fixUrl(hls),
                    referer = referer
                ).forEach(callback)
            }
            true
        }.getOrDefault(false)
    }

    /* =====================  HELPERS  ===================== */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private suspend fun getDoc(url: String): Document {
        // Önce verilen URL, sonra alternatif alan adı denenir
        val primary = runCatching { app.get(url, interceptor = cfInterceptor).document }.getOrNull()
        if (primary != null) return primary

        val swapped = if (url.startsWith(mainUrl)) url.replace(mainUrl, backupMainUrl) else url.replace(backupMainUrl, mainUrl)
        return app.get(swapped, interceptor = cfInterceptor).document
    }
}
