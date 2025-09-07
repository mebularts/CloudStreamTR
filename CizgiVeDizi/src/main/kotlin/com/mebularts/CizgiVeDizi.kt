package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CizgiVeDizi : MainAPI() {
    override var mainUrl              = "https://www.cizgivedizi.com"
    override var name                 = "Çizgi ve Dizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false   // Site public search endpoint sunmuyor
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // Aynı anda çok istek atmayalım, bazı host’lar 403 verebiliyor.
    override var sequentialMainPage = true

    private val baseHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    /* ===================== MAIN PAGE ===================== */

    // Ana sayfayı çekip film ve dizi kartlarını ayrı bölümlere ayırıyoruz
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site paginasyon sunmuyor; sadece ilk sayfayı döndür.
        if (page > 1) return newHomePageResponse(listOf(), hasNext = false)

        val doc = app.get(mainUrl, headers = baseHeaders).document

        // Kartlar, <div id="main"> içinde <a href="/film/..."> veya <a href="/dizi/..."> sarmalıyor.
        val anchors = doc.select("""#main a[href]""")
        val movies  = anchors.filter { it.attr("href").startsWith("/film/") }.mapNotNull { it.toMovieCard() }
        val series  = anchors.filter { it.attr("href").startsWith("/dizi/") }.mapNotNull { it.toSeriesCard() }

        val lists = listOfNotNull(
            if (movies.isNotEmpty()) HomePageList("Filmler", movies) else null,
            if (series.isNotEmpty()) HomePageList("Diziler", series) else null
        )

        return HomePageResponse(lists, hasNext = false)
    }

    private fun Element.posterUrlNearby(): String? {
        // Poster <img class="poster">; data-src de yoksa src kullan.
        val img = this.selectFirst("img.poster")
        val src = img?.attr("data-src")?.ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun Element.cardTitle(): String {
        // <div class="card-overlay"><h5 class="card-title">Başlık</h5>
        val t = this.selectFirst(".card-overlay .card-title")?.text()?.trim()
            ?: this.selectFirst("img.logo")?.attr("alt")?.replace(" Logo", "")?.trim()
            ?: this.selectFirst("img.poster")?.attr("alt")?.replace(" Poster", "")?.trim()
        return t?.takeIf { it.isNotBlank() } ?: "Başlık"
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href = attr("href").takeIf { it.startsWith("/") } ?: return null
        val url  = fixUrl(href)
        val title = cardTitle()
        val poster = posterUrlNearby()
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href = attr("href").takeIf { it.startsWith("/") } ?: return null
        val url  = fixUrl(href)
        val title = cardTitle()
        val poster = posterUrlNearby()
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /* ===================== LOAD (DETAIL) ===================== */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document

        // Genel poster og:image
        val poster = doc.selectFirst("""meta[property="og:image"]""")?.attr("content")
            ?: doc.selectFirst("""img.poster""")?.attr("src")

        return when {
            url.contains("/film/") -> {
                val title = doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
                    ?: "Film"

                // Film tek parça → data = aynı sayfa (loadLinks içinden iframe’den mp4 çıkaracağız)
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }

            url.contains("/dizi/") -> {
                // Dizi ana sayfası
                val title = doc.selectFirst("h4")?.text()?.trim()
                    ?: doc.selectFirst("h1.fw-light")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
                    ?: "Dizi"

                // Bölümler iki yerde de çıkabiliyor: #onizleme-gorunumu ve alt bölüm listesi
                val eps = mutableListOf<Episode>()
                fun parseEpisodeAnchors(selector: String) {
                    doc.select(selector).forEach { a ->
                        val href = a.attr("href").takeIf { it.startsWith("/") } ?: return@forEach
                        val eUrl = fixUrl(href)
                        val eName = a.selectFirst(".card-title")?.text()?.trim()
                            ?: a.attr("title").ifBlank { a.text() }.trim()
                        // URL kalıbında /dizi/slug/<bolumNo>/... → bolum no’yu çekelim
                        val epNum = href.split("/").getOrNull(4)?.toIntOrNull()
                        eps += newEpisode(eUrl) {
                            name = eName
                            episode = epNum
                            // Sezon bilgisini site çoğu dizide ayırmıyor → null bırakıyoruz
                        }
                    }
                }
                parseEpisodeAnchors("#onizleme-gorunumu a.bolum[href]")
                parseEpisodeAnchors(""".album .row a.bolum[href]""")

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.distinctBy { it.data }) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }

            else -> null
        }
    }

    /* ===================== LINKS ===================== */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: film sayfası ya da bölüm sayfası URL’si
        val doc = app.get(data, headers = baseHeaders, referer = "$mainUrl/").document

        // 1) #video içindeki iframe’i yakala
        val iframeSrc = doc.selectFirst("#video iframe[src]")?.attr("src")?.let { fixUrl(it) }
        if (iframeSrc.isNullOrBlank()) {
            Log.w("CVD", "iframe bulunamadı: $data")
            return false
        }

        // 2) iframe sayfasını indir → sibnet → mp4 linki ara
        val iframeBody = app.get(iframeSrc, referer = "$mainUrl/", headers = baseHeaders).text

        // .mp4 doğrudan
        val mp4Regex = Regex("""https?://(?:[^/"']+\.)?sibnet\.ru/v/[^\s"'<>]+\.mp4""")
        val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")

        var found = false

        // MP4 linkleri → DIRECT
        mp4Regex.findAll(iframeBody).map { it.value }.distinct().forEach { mp4 ->
            val directUrl = fixUrl(mp4)
            val link = newExtractorLink(
                source = name,
                name   = "MP4",
                url    = directUrl,
                type   = ExtractorLinkType.DIRECT
            ) {
                this.referer = "https://video.sibnet.ru/"
                this.quality = getQualityFromName(directUrl) ?: 1080
                // Ek header gerekirse:
                this.headers = mapOf("Referer" to "https://video.sibnet.ru/")
            }
            callback(link)
            found = true
        }

        // m3u8 ihtimali çok düşük ama ekleyelim
        if (!found) {
            m3u8Regex.findAll(iframeBody).map { it.value }.distinct().forEach { m3u8 ->
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = fixUrl(m3u8),
                    referer   = "https://video.sibnet.ru/",
                    name      = "HLS"
                ).forEach(callback)
                found = true
            }
        }

        // 3) Hâlâ link yoksa, extractor’a şans ver (ileride Sibnet extractor eklenirse çalışır)
        if (!found) {
            if (loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)) return true
        }

        return found
    }

    /* ===================== HELPERS ===================== */

    private fun getQualityFromName(u: String): Int? {
        // Yaygın desenleri ayıkla
        // ...1080.mp4 / ..._1080p.mp4 / ...-720p.mp4
        Regex("""(?<!\d)(2160|1440|1080|720|480)(?:p)?(?!\d)""")
            .find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }
}
