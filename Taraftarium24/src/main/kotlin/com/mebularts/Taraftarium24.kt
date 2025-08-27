// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.
package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Taraftarium24 : MainAPI() {
    override var mainUrl              = "https://xn--taraftarium24canl-svc.com.tr"
    override var name                 = "Taraftarium24"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

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
        "$mainUrl/" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, interceptor = interceptor, referer = "$mainUrl/").document
        val cards = parseChannels(doc).map { (id, title) ->
            // Data'ya id koyuyoruz: /stream/<id>
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
        val doc = app.get(mainUrl, interceptor = interceptor).document
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
        // url formatı: https://.../stream/<id>
        val id = Regex("""/stream/(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return null

        // Başlık için ana sayfadaki isim
        val doc = app.get(mainUrl, interceptor = interceptor, referer = "$mainUrl/").document
        val title = (parseChannels(doc).firstOrNull { it.first == id }?.second)
            ?: "Kanal $id"

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
        val id = Regex("""/stream/(\d+)""").find(data)?.groupValues?.getOrNull(1)
            ?: return false

        // Ana sayfadaki data-player-url'den base'i bul, id'yi bizimkisiyle değiştir
        val home = app.get(mainUrl, interceptor = interceptor, referer = "$mainUrl/").document
        val template = home.selectFirst(".x-embed-container[data-player-url]")?.attr("data-player-url")
        val embedUrl = when {
            !template.isNullOrBlank() -> {
                // id=<say>> kısmını bizim id ile değiştir
                template.replace(Regex("""id=\d+"""), "id=$id")
            }
            else -> {
                // yedek (domain dönebilir; otomatik güncel değilse bu fallback çalışır)
                "https://macizlevip315.shop/wp-content/themes/ikisifirbirdokuz/match-center.php?id=$id&autoPlay=1"
            }
        }

        Log.d("T24", "embed = $embedUrl")

        // 1) Genel extractor'lar (iframe zinciri)
        if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) return true

        // 2) Embed sayfasını ve iç iframe'leri tarayalım (.m3u8 / .mp4)
        if (extractFromPageUrl(embedUrl, referer = mainUrl, subtitleCallback, callback)) return true

        // 3) Son çare: ana sayfanın HTML’inde açık bağlantılar varsa
        if (extractFromPageUrl(mainUrl, referer = mainUrl, subtitleCallback, callback)) return true

        return false
    }

    /** Verilen URL’i indirip metin, script ve iframe içinden .m3u8 / .mp4 avlar. */
    private suspend fun extractFromPageUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(url, referer = referer, interceptor = interceptor)
        val body = res.text
        val doc  = res.document

        var found = false

        if (pushM3u8s(body, referer, callback)) found = true
        if (pushMp4s(body, referer, callback)) found = true

        // <script> içerikleri
        doc.select("script").forEach { s ->
            val code = if (s.hasAttr("src")) {
                val src = fixUrl(s.attr("src"))
                runCatching { app.get(src, referer = url, interceptor = interceptor).text }.getOrNull()
            } else s.data()
            if (!code.isNullOrBlank()) {
                if (pushM3u8s(code, referer, callback)) found = true
                if (pushMp4s(code, referer, callback)) found = true
            }
        }

        // İframe zincirini izle
        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            if (loadExtractor(src, url, subtitleCallback, callback)) found = true
            val subRes  = app.get(src, referer = url, interceptor = interceptor)
            val subBody = subRes.text
            val subDoc  = subRes.document
            if (pushM3u8s(subBody, url, callback)) found = true
            if (pushMp4s(subBody, url, callback)) found = true
            subDoc.select("iframe[src], amp-iframe[src]").forEach { deep ->
                val s2 = normalizeHref(deep.attr("src")) ?: return@forEach
                if (loadExtractor(s2, src, subtitleCallback, callback)) found = true
            }
        }

        return found
    }

    /** Metin içindeki .m3u8 linklerini ExtractorLink listesine çevir. */
    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { m3u ->
                runCatching {
                    M3u8Helper.generateM3u8(
                        source    = name,
                        streamUrl = fixUrl(m3u),
                        referer   = referer,
                        name      = name
                    ).forEach(callback)
                    any = true
                }
            }
        return any
    }

    /** Metin içindeki .mp4 linklerini yalın olarak ekle. (Header/referer ataması yapmıyoruz.) */
    private suspend fun pushMp4s(
        text: String,
        @Suppress("UNUSED_PARAMETER") referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = fixUrl(link)
                    )
                )
                any = true
            }
        return any
    }

    /* -------------------- Utils -------------------- */

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)

    /** Ana sayfadaki kanal listesini (id, ad) olarak döndür. */
    private fun parseChannels(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        // 1) Eski liste: .channels .item a[data-channel-id]
        doc.select(".channels .item a[data-channel-id]").forEach { a ->
            val id = a.attr("data-channel-id").ifBlank { null } ?: return@forEach
            val title = a.selectFirst(".name")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }
            if (title.isNotBlank()) out += id to title
        }

        // 2) Yeni liste: #streamList içinde JS ile dolduruluyor; yedek olarak sayfadaki data’lardan toplayamayız.
        // Yine de HTML’de maç başlıkları varsa alalım (data-channel-id aynı)
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
