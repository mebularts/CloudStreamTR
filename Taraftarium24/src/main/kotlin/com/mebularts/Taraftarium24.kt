// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.
package com.mebularts

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

class Taraftarium24 : MainAPI() {
    override var mainUrl              = "https://xn--taraftarium24canl-svc.com.tr"
    override var name                 = "Taraftarium24"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

    /* -------------------- MainPage -------------------- */

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, referer = "$mainUrl/").document
        val cards = parseChannels(doc).map { (id, title) ->
            newMovieSearchResponse(
                title.ifBlank { "Kanal $id" },
                "$mainUrl/stream/$id",
                TvType.Live
            ) {}
        }
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl, referer = "$mainUrl/").document
        val q = query.trim().lowercase()
        return parseChannels(doc).filter { (_, name) ->
            name.lowercase().contains(q)
        }.map { (id, name) ->
            newMovieSearchResponse(name, "$mainUrl/stream/$id", TvType.Live) {}
        }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/stream/(\d+)""").find(url)?.groupValues?.getOrNull(1) ?: return null
        val doc = app.get(mainUrl, referer = "$mainUrl/").document
        val title = (parseChannels(doc).firstOrNull { it.first == id }?.second) ?: "Kanal $id"

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = null
        }
    }

    /* -------------------- Links (player) -------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""/stream/(\d+)""").find(data)?.groupValues?.getOrNull(1) ?: return false

        // Ana sayfadaki data-player-url şablonunu al, id'yi değiştir
        val home = app.get(mainUrl, referer = "$mainUrl/").document
        val template = home.selectFirst(".x-embed-container[data-player-url]")?.attr("data-player-url")
        val embedUrl = when {
            !template.isNullOrBlank() -> template.replace(Regex("""id=\d+"""), "id=$id")
            else -> "https://macizlevip315.shop/wp-content/themes/ikisifirbirdokuz/match-center.php?id=$id&autoPlay=1"
        }

        // URL zincirini dolaşarak .m3u8 ara (derinlik 3)
        val m3u8s = collectM3u8s(embedUrl, referer = mainUrl, depth = 0, maxDepth = 3).distinct()
        m3u8s.forEach { url ->
            runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fixUrl(url),
                    referer = mainUrl,
                    name = name
                ).forEach(callback)
            }
        }
        return m3u8s.isNotEmpty()
    }

    /* -------------------- Helpers -------------------- */

    /** Verilen URL'i indir; gövdede, <script>’lerde ve iframe’lerde .m3u8 ara. */
    private suspend fun collectM3u8s(
        url: String,
        referer: String,
        depth: Int,
        maxDepth: Int
    ): List<String> {
        if (depth > maxDepth) return emptyList()

        val out = mutableListOf<String>()
        val res = app.get(url, referer = referer)
        val body = res.text
        val doc  = res.document

        out += findM3u8InText(body)

        // <script> içerikleri
        doc.select("script").forEach { s ->
            val code: String? = if (s.hasAttr("src")) {
                val src = normalizeHref(s.attr("src"), base = url) ?: return@forEach
                runCatching { app.get(src, referer = url).text }.getOrNull()
            } else {
                s.data()
            }
            if (!code.isNullOrBlank()) out += findM3u8InText(code)
        }

        // iframe zinciri
        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src"), base = url) ?: return@forEach
            out += collectM3u8s(src, referer = url, depth = depth + 1, maxDepth = maxDepth)
        }

        return out
    }

    private fun findM3u8InText(text: String): List<String> {
        return Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text)
            .map { it.value }
            .toList()
    }

    private fun normalizeHref(href: String?, base: String? = null): String? {
        if (href.isNullOrBlank()) return null
        val raw = href.trim()
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            !base.isNullOrBlank() -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
                ?: fixUrl(raw)
            else -> fixUrl(raw)
        }
    }

    /** Ana sayfadaki kanal listesini (id, ad) olarak döndür. */
    private fun parseChannels(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // Eski yapı: .channels .item a[data-channel-id]
        doc.select(".channels .item a[data-channel-id]").forEach { a ->
            val id = a.attr("data-channel-id").ifBlank { null } ?: return@forEach
            val title = a.selectFirst(".name")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }
            if (title.isNotBlank()) out += id to title
        }

        // Alternatif: data-channel-id içeren başka öğeler
        if (out.isEmpty()) {
            doc.select("[data-channel-id]").forEach { e ->
                val id = e.attr("data-channel-id").ifBlank { null } ?: return@forEach
                val title = e.attr("title").ifBlank { e.text() }
                if (title.isNotBlank()) out += id to title
            }
        }

        return out.distinctBy { it.first }
    }
}
