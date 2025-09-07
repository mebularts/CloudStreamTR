package com.mebularts

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CizgiVeDizi : MainAPI() {
    override var mainUrl = "https://www.cizgivedizi.com"
    override var name = "Çizgi ve Dizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false // sitede arama uçları yok/403
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    /* -------------------- Main Page -------------------- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        // Anasayfadaki grid: <div id="main"> ... <a href="/film/..."> ve <a href="/dizi/...">
        val movieCards = doc.select("""#main a[href^="/film/"]""")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toMovieCard() }

        val seriesCards = doc.select("""#main a[href^="/dizi/"]""")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSeriesCard() }

        val sections = listOf(
            HomePageList("Filmler", movieCards),
            HomePageList("Diziler", seriesCards),
        )
        return newHomePageResponse(sections, hasNext = false)
    }

    /* -------------------- Cards -------------------- */
    private fun Element.posterUrl(): String? {
        val img = selectFirst(".poster-div img.poster")
            ?: selectFirst("img.poster")
            ?: selectFirst("img")
        return fixUrlNull(img?.attr("src"))
    }

    private fun Element.cardTitle(): String {
        val t = selectFirst(".card-overlay .card-title")?.text()
            ?: attr("title")
            ?: selectFirst("img[alt]")?.attr("alt")
            ?: text()
        return t.trim()
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = if (href.startsWith("http")) href else fixUrl(href)
        val title = cardTitle().ifBlank { url.substringAfterLast("/").replace("-", " ") }
        val poster = posterUrl()
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = if (href.startsWith("http")) href else fixUrl(href)
        val title = cardTitle().ifBlank { url.substringAfterLast("/").replace("-", " ") }
        val poster = posterUrl()
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /* -------------------- Load (Film / Dizi detay) -------------------- */
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val poster = fixUrlNull(
            doc.selectFirst("""meta[property="og:image"]""")?.attr("content")
                ?: doc.selectFirst("""meta[name="twitter:image"]""")?.attr("content")
        )

        return when {
            url.contains("/film/") -> {
                val title = doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                    ?: "Film"
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            url.contains("/dizi/") -> {
                val seriesTitle = doc.selectFirst("h4")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                    ?: "Dizi"

                // Bölümler: dizi sayfasındaki kart gridindeki <a class="bolum" href="/dizi/.../1/slug">
                val eps = doc.select("""a.bolum[href^="/dizi/"]""").mapNotNull { a ->
                    val href = a.attr("href")
                    val epUrl = if (href.startsWith("http")) href else fixUrl(href)
                    val epNum = Regex("/(\\d+)/[^/]+$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val name = a.selectFirst(".card-title")?.ownText()?.trim()
                        ?: a.attr("title").ifBlank { a.text() }
                    val preview = a.selectFirst(".preview")?.attr("data-bg")
                        ?.replace("^//".toRegex(), "https://")

                    newEpisode(epUrl) {
                        this.name = name
                        this.episode = epNum
                        this.season = 1 // linkte sezon bilgisi bulunmuyor → 1
                        this.posterUrl = preview
                    }
                }

                newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    /* -------------------- Links (Sibnet embed → MP4 / m3u8) -------------------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // İzleme sayfasındaki iframe
        val doc = app.get(data, referer = mainUrl).document
        val iframeSrc = doc.selectFirst("#video iframe[src]")?.attr("src")
            ?: doc.selectFirst("iframe[src]")?.attr("src")
            ?: return false

        val embed = if (iframeSrc.startsWith("http")) iframeSrc else fixUrl(iframeSrc)
        val body = app.get(embed, referer = data).text
        var yielded = false
        val headers = mapOf("Referer" to embed)

        // 1) MP4
        val mp4s = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.value }.distinct().toList()

        for (u in mp4s) {
            val q = Regex("""(\d{3,4})p""").find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.P720.value
            callback(
                ExtractorLink(
                    source = name,
                    name = "Video",
                    url = u,
                    referer = embed,
                    quality = q,
                    headers = headers // enum/type vermeden eski uyumlu ctor
                )
            )
            yielded = true
        }

        // 2) HLS (yedek)
        val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.value }.distinct().toList()

        for (hls in m3u8s) {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = hls,
                referer = embed,
                name = "HLS"
            ).forEach(callback)
            yielded = true
        }

        return yielded
    }
}
