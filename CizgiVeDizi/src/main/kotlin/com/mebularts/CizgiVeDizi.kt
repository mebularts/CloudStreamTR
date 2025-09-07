package com.mebularts

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CizgiVeDizi : MainAPI() {

    override var mainUrl              = "https://www.cizgivedizi.com"
    override var name                 = "Çizgi ve Dizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true      // Kendi içinde client-side filtreleme yapıyoruz
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // Site, ayrı listeleme sayfaları yerine ana sayfada kartları gösteriyor.
    // Bu nedenle "Filmler" ve "Diziler" bölümlerini tek sayfadan çıkarıyoruz.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Filmler",
        "$mainUrl/" to "Diziler",
    )

    /* -------------------- Main Page -------------------- */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, referer = mainUrl).document

        val filmCards = parseCards(doc, wantSeries = false)
        val diziCards = parseCards(doc, wantSeries = true)

        val rows = when (request.name) {
            "Filmler" -> listOf(HomePageList("Filmler", filmCards))
            "Diziler" -> listOf(HomePageList("Diziler", diziCards))
            else      -> listOf(
                HomePageList("Filmler", filmCards),
                HomePageList("Diziler", diziCards),
            )
        }
        return newHomePageResponse(request.name, rows.flatten().firstOrNull()?.list ?: emptyList(), hasNext = false)
    }

    private fun parseCards(doc: Document, wantSeries: Boolean): List<SearchResponse> {
        // Kartlar <a> seviyesinde, href="/film/..." veya href="/dizi/..."
        val sel = if (wantSeries) """a[href^="/dizi/"]""" else """a[href^="/film/"]"""
        return doc.select(sel).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.startsWith("/") }?.let { fixUrl(it) } ?: return@mapNotNull null
            val poster = a.selectFirst("img.poster")?.attr("src")?.let { fixUrlNull(it) }
            val title  = a.selectFirst(".card-overlay h5.card-title")
                ?.text()?.trim()
                ?: a.selectFirst("img.logo")?.attr("alt")?.removeSuffix(" Logo")
                ?: a.selectFirst("img.poster")?.attr("alt")?.removeSuffix(" Poster")
                ?: href.substringAfterLast('/').replace('-', ' ')

            if (wantSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    /* -------------------- Search (client-side) -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl, referer = mainUrl).document
        val all = parseCards(doc, wantSeries = false) + parseCards(doc, wantSeries = true)
        val q = query.trim().lowercase()
        return all.filter { it.name?.lowercase()?.contains(q) == true }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        // Ortak görsel
        val poster = doc.selectFirst("""meta[property="og:image"]""")?.attr("content")
            ?: doc.selectFirst("""img.poster""")?.attr("src")

        // Film mi, dizi mi?
        return when {
            url.contains("/film/") -> {
                val title = doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
                    ?: "Film"

                val plot = doc.selectFirst("#video")?.parent()?.selectFirst("p.lead, p.lead.text-body-secondary")
                    ?.text()?.trim()

                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrlNull(poster)
                    this.plot = plot
                }
            }

            url.contains("/dizi/") -> {
                // Dizi ana sayfası: bölüm kartlarını topla
                val seriesTitle = doc.selectFirst("h4")?.text()?.trim()
                    ?: doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("""meta[property="og:title"]""")?.attr("content")
                    ?: "Dizi"

                val description = doc.selectFirst("p.lead")?.text()?.trim()
                val eps = mutableListOf<Episode>()

                // Önizleme grid
                doc.select("""a.bolum[href^="/dizi/"]""").forEach { a ->
                    val href = a.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val h5 = a.selectFirst("h5.card-title")?.text()?.trim().orEmpty()
                    // Başlık genelde: "<noInSerie>)Bölüm Adı"
                    val epNum = Regex("""(\d+)\)""").find(h5)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val name = h5.substringAfter(")").ifBlank { h5 }

                    eps += newEpisode(href) {
                        this.name = name
                        this.episode = epNum
                    }
                }

                // Eğer ana grid yoksa, aşağıdaki sezon/bölüm listesine de bak
                if (eps.isEmpty()) {
                    doc.select("""div.album a.bolum[href^="/dizi/"]""").forEach { a ->
                        val href = a.attr("href")?.let { fixUrl(it) } ?: return@forEach
                        val h5 = a.selectFirst("h5.card-title")?.text()?.trim().orEmpty()
                        val epNum = Regex("""(\d+)\)""").find(h5)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val name = h5.substringAfter(")").ifBlank { h5 }
                        eps += newEpisode(href) {
                            this.name = name
                            this.episode = epNum
                        }
                    }
                }

                newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, eps) {
                    this.posterUrl = fixUrlNull(poster)
                    this.plot = description
                }
            }

            else -> null
        }
    }

    /* -------------------- Links (player) -------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: film sayfası veya bölüm sayfası URL’si
        val page = app.get(data, referer = mainUrl)
        val doc = page.document

        // 1) #video > iframe[src]
        val iframe = doc.selectFirst("#video iframe[src]")?.attr("src")?.let { fixUrl(it) }
        if (iframe != null) {
            // a) Önce bilinen extractor’lara bırak
            if (loadExtractor(iframe, referer = data, subtitleCallback = subtitleCallback, callback = callback)) {
                return true
            }
            // b) Fallback: iframe sayfasından MP4’leri çıkar
            val ifDoc = app.get(iframe, referer = data)
            val body = ifDoc.text
            // Yaygın kalıplar: .mp4 linkleri veya playerjs/file:"...mp4"
            val mp4s = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""")
                .findAll(body).map { it.value }.distinct().toList()

            if (mp4s.isNotEmpty()) {
                mp4s.forEach { url ->
                    val isM3u8 = url.contains(".m3u8", true)
                    val link = ExtractorLink(
                        source  = name,
                        name    = if (isM3u8) "HLS" else "MP4",
                        url     = url,
                        referer = iframe,
                        quality = Qualities.Unknown.value,
                        isM3u8  = isM3u8,
                        headers = mapOf("Referer" to iframe),
                        type    = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.DIRECT
                    )
                    callback(link)
                }
                return true
            }
        }

        // 2) İframe bulamadıysan, sayfa HTML’inde doğrudan video linki aramayı dene
        val directMp4s = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""")
            .findAll(doc.html()).map { it.value }.distinct().toList()
        if (directMp4s.isNotEmpty()) {
            directMp4s.forEach { url ->
                callback(
                    ExtractorLink(
                        source  = name,
                        name    = "MP4",
                        url     = url,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8  = false,
                        headers = mapOf("Referer" to data),
                        type    = ExtractorLinkType.DIRECT
                    )
                )
            }
            return true
        }

        return false
    }
}
