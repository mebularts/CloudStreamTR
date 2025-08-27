// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addPoster
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YabanciDiziCo : MainAPI() {
    override var name                 = "YabanciDiziCo"
    override var mainUrl              = "https://yabancidizi.so"
    private val altMainUrl            = "https://yabancidizi.so"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Bazı sayfalar .so'ya yönleniyor, hepsini destekleyelim
    private fun normalizeHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url
        else fixUrl(url)
    }

    // Cloudflare
    override var sequentialMainPage = true
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor   by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val res = chain.proceed(req)
            val peek = res.peekBody(512 * 1024).string()
            val doc  = runCatching { Jsoup.parse(peek) }.getOrNull()
            val title = doc?.selectFirst("title")?.text()?.trim().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                cloudflareKiller.intercept(chain)
            } else res
        }
    }

    /* ---------- MainPage ---------- */

    override val mainPage = mainPageOf(
        // Sayfa 1: Anasayfa trend / yeni içerikler
        "$mainUrl/" to "Öne Çıkanlar",
        // Listenin çok olduğu klasik diziler listesi (sayfalı)
        "$mainUrl/diziler" to "Diziler",
        // Filmler menüsü (varsa)
        "$mainUrl/filmler" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Basit sayfalama: /diziler?page=2 gibi varyantları dener
        val url = buildPagedUrl(request.data, page)
        val doc = getDoc(url)

        val items = when {
            request.data.endsWith("/diziler") || request.data.endsWith("/filmler") -> {
                // Kartlar genelde /dizi/veya /film/ deseninde
                doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
            else -> {
                // Anasayfa – farklı bileşenlerden dizi/film kartlarını topla
                doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { it.toCard() }
            }
        }

        val hasNext = doc.select("""a[rel="next"], li.active + li > a, .pagination a:matchesOwn(İleri|Sonraki)""").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
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

    /* ---------- Search ---------- */

    // Site araması alan adları arasında farklı olabilir; birkaç rota dener.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val candidates = listOf(
            "$mainUrl/?s=${q.encodeURL()}",
            "$mainUrl/arama?kelime=${q.encodeURL()}",
            "$altMainUrl/?s=${q.encodeURL()}",
            "$altMainUrl/arama?kelime=${q.encodeURL()}"
        )
        val out = mutableListOf<SearchResponse>()
        for (u in candidates) {
            val doc = runCatching { getDoc(u) }.getOrNull() ?: continue
            doc.select("""a[href*="/dizi/"], a[href*="/film/"]""")
                .forEach { it.toCard()?.let(out::add) }
            if (out.isNotEmpty()) break
        }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* ---------- Load (detay) ---------- */

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
                // Film sayfasında genellikle tek "izle" düğmesi: bölüm gibi davranırız
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = fixUrlNull(poster)
                    addPoster(this.posterUrl)
                }
            }

            url.contains("/dizi/") -> {
                // Dizi – bölüm linkleri: /sezon-X/bolum-Y
                val episodes = doc.select("""a[href*="/sezon-"][href*="/bolum-"]""")
                    .distinctBy { it.absUrl("href") }
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        val (s, e) = parseSeasonEpisode(href)
                        val name = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $e" }
                        newEpisode(href) {
                            this.name = name
                            this.season = s
                            this.episode = e
                        }
                    }

                // Bazı sayfalarda ilk bölüme kısayol butonu olabiliyor, yine de yukarıdaki seçiciler yakalıyor.
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = fixUrlNull(poster)
                    addPoster(this.posterUrl)
                }
            }

            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String): Pair<Int?, Int?> {
        // …/sezon-2/bolum-10
        val r = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href)
        val s = r?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = r?.groupValues?.getOrNull(2)?.toIntOrNull()
        return Pair(s, e)
    }

    /* ---------- Links (player) ---------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: film/dizi bölüm sayfası
        val doc = getDoc(data)

        // 1) Sayfadaki IFRAME'ler (oynatıcı sekmeleri genelde böyle yüklüyor)
        var found = false
        doc.select("iframe[src], amp-iframe[src]").forEach { frame ->
            val src = normalizeHost(frame.absUrl("src")) ?: return@forEach
            if (handlePlayableUrl(src, data, subtitleCallback, callback)) found = true
        }
        if (found) return true

        // 2) AMP sürümü varsa oradan da bak
        doc.selectFirst("""link[rel=amphtml][href]""")?.absUrl("href")?.let { ampUrl ->
            val amp = runCatching { getDoc(ampUrl) }.getOrNull()
            amp?.select("iframe[src], amp-iframe[src]")?.forEach { frame ->
                val src = normalizeHost(frame.absUrl("src")) ?: return@forEach
                if (handlePlayableUrl(src, data, subtitleCallback, callback)) return true
            }
        }

        // 3) Sayfa metninde direkt m3u8 ya da barındırıcı köprüleri olabilir (İndir linkleri vb.)
        val whole = doc.outerHtml()
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val urls = urlRegex.findAll(whole).map { it.value }.distinct().toList()

        for (u in urls) {
            // .m3u8 varsa direkt yakala
            if (u.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    name = name,
                    streamUrl = u,
                    referer = data
                ).forEach(callback)
                found = true
                // devam: başka kalite de gelebilir
            } else {
                if (handlePlayableUrl(u, data, subtitleCallback, callback)) found = true
            }
        }

        return found
    }

    private suspend fun handlePlayableUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cloudstream'in dahili extractorları: ok.ru / vidmoly / streamtape / dood / filemoon / voe / sibnet ...
        // Bunları loadExtractor ile geçelim. Başarılıysa true döner.
        val ok = loadExtractor(url, referer, subtitleCallback, callback)
        if (ok) return true

        // Bazı embed sayfalarının kendi içinde m3u8 saklı olabilir; bir de içini tara
        return runCatching {
            val body = app.get(url, referer = referer).text
            val m3u = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .findAll(body)
                .map { it.value }
                .distinct()
                .toList()
            if (m3u.isEmpty()) return@runCatching false
            m3u.forEach { hls ->
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

    /* ---------- Helpers ---------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private suspend fun getDoc(url: String): Document {
        // .co hata verirse .so dener
        val primary = runCatching { app.get(url, interceptor = cfInterceptor).document }.getOrNull()
        if (primary != null) return primary

        val alt = url.replace(mainUrl, altMainUrl)
        return app.get(alt, interceptor = cfInterceptor).document
    }
}
