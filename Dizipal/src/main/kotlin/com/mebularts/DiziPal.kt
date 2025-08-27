// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {

    // ——— Site bilgileri ———
    override var mainUrl              = "https://dizipal1103.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ——— Cloudflare bypass ———
    override var sequentialMainPage = true
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req  = chain.request()
            val resp = chain.proceed(req)
            val body = resp.peekBody(1024 * 1024).string()
            val doc  = Jsoup.parse(body)

            val title = doc.selectFirst("title")?.text()?.trim()
            if (title == "Just a moment..." || title == "Bir dakika lütfen...") {
                return cloudflareKiller.intercept(chain)
            }
            return resp
        }
    }

    // ——— Ana sayfa sekmeleri ———
    override val mainPage = mainPageOf(
        "$mainUrl/"               to "Son Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/hd-film-izle"   to "Filmler",
        "$mainUrl/anime"          to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, interceptor = interceptor).document
        val items: List<SearchResponse> = when {
            request.data == "$mainUrl/" || request.data == mainUrl -> {
                // Ana sayfadaki “Son eklenen bölüm” kartlarını dene
                doc.select("a[href*=\"/bolum/\"]").mapNotNull { it.toEpisodeCard() }
                    .ifEmpty {
                        // Yedek: genel kartlar
                        doc.select("a[href*=\"/series/\"], a[href*=\"/film/\"]").mapNotNull { it.toCard() }
                    }
            }
            request.data.contains("/yabanci-dizi-izle") || request.data.contains("/anime") -> {
                doc.select("a[href*=\"/series/\"]").mapNotNull { it.toCard() }
            }
            request.data.contains("/hd-film-izle") -> {
                doc.select("a[href*=\"/film/\"]").mapNotNull { it.toCard() }
            }
            else -> {
                doc.select("a[href*=\"/series/\"], a[href*=\"/film/\"]").mapNotNull { it.toCard() }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ——— Kart parse yardımcıları ———
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img  = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")
        val title = attr("title").takeIf { !it.isNullOrBlank() }
            ?: selectFirst("h2, h3, .title, .name")?.text()
            ?: text().trim()
        if (title.isNullOrBlank()) return null

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = fixUrlNull(img) }
        } else if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = fixUrlNull(img) }
        } else null
    }

    private fun Element.toEpisodeCard(): SearchResponse? {
        // Bölüm kartından dizinin köküne dön: /bolum/<slug>-1x1  -> /series/<slug>
        val epUrl = fixUrlNull(attr("href")) ?: return null
        val slug  = epUrl.substringAfter("/bolum/").substringBeforeLast("-")
            .substringBeforeLast("-") // bazen -cXX sonek oluyor
        val seriesUrl = "$mainUrl/series/$slug"
        val title = selectFirst(".title, .name")?.text()?.trim()
            ?: ownText().ifBlank { "Bölüm" }
        val img  = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { posterUrl = fixUrlNull(img) }
    }

    // ——— Arama ———
    data class SearchItem(
        val title: String = "",
        val url: String = "",
        val type: String = "series", // "series" / "movie"
        val poster: String? = null
    )

    private fun SearchItem.toResponse(): SearchResponse =
        if (type.equals("series", true)) {
            newTvSeriesSearchResponse(title, fixUrl("$mainUrl/$url"), TvType.TvSeries) { posterUrl = fixUrlNull(poster) }
        } else {
            newMovieSearchResponse(title, fixUrl("$mainUrl/$url"), TvType.Movie) { posterUrl = fixUrlNull(poster) }
        }

    override suspend fun search(query: String): List<SearchResponse> {
        // 1) Ajax autocomplete (varsa)
        runCatching {
            val resp = app.post(
                "$mainUrl/api/search-autocomplete",
                data = mapOf("query" to query),
                headers = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "$mainUrl/",
                interceptor = interceptor
            )
            val map: Map<String, SearchItem> = jacksonObjectMapper().readValue(resp.text)
            if (map.isNotEmpty()) return map.values.map { it.toResponse() }
        }

        // 2) HTML arama sayfası yedeği
        val doc = app.get("$mainUrl/ara?search=${query}", interceptor = interceptor).document
        return doc.select("a[href*=\"/series/\"], a[href*=\"/film/\"]").mapNotNull { it.toCard() }
    }

    override suspend fun quickSearch(query: String) = search(query)

    // ——— Load (dizi/film) ———
    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, interceptor = interceptor)
        val doc  = resp.document

        val poster = doc.selectFirst("[property='og:image']")?.attr("content")
            ?: doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("img.poster, .poster img")?.attr("src")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".summary p, .overview, .description")?.text()?.trim()

        // DİZİ
        if (url.contains("/series/") || doc.selectFirst("a[href*=\"/series/\"]") != null) {
            val title = doc.selectFirst("h1, .g-title div, .title h1, .title h2")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null

            // Tüm sezon/bolum listelerini topla (farklı sınıf isimleri olabilir)
            val episodeLinks = doc.select("ul.episodes a[href*=\"/bolum/\"]")
                .ifEmpty { doc.select("a[href*=\"/bolum/\"]") }

            val episodes = episodeLinks.mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epName = a.text().trim().ifBlank { a.attr("title").ifBlank { "Bölüm" } }

                // URL deseni: /bolum/<slug>-<S>x<E>(-cXX opsiyonel)
                val re = Regex("""-(\d+)x(\d+)(?:-[cC]\d+)?$""")
                val m  = re.find(epHref)
                val s  = m?.groupValues?.getOrNull(1)?.toIntOrNull()
                val e  = m?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    name = epName
                    season = s
                    episode = e
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster)
                plot = description
            }
        }

        // FİLM
        val isMovie = url.contains("/film/") || url.contains("/movie/")
        if (isMovie) {
            val title = doc.selectFirst("h1, .g-title div, .title h1, .title h2")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "Film"

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = fixUrlNull(poster)
                plot = description
            }
        }

        // Bölüm sayfasından gelindiyse, üstteki “dizi linkine” dön ve oradan yükle
        val seriesHref = doc.selectFirst("a[href*=\"/series/\"]")?.attr("href")
        if (!seriesHref.isNullOrBlank()) {
            return load(fixUrl(seriesHref))
        }

        return null
    }

    // ——— Player çözümleme ———
    data class EncPayload(
        val ciphertext: String,
        val iv: String,
        val salt: String? = null, // şimdilik gerekmiyor ama dursun
    )

    private fun String.hexToBytes(): ByteArray {
        val s = replace("[^0-9A-Fa-f]".toRegex(), "")
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = s.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }

    private fun maybePickUrlFromJson(node: JsonNode): String? {
        return when {
            node.has("file") -> node.get("file").asText()
            node.has("hls")  -> node.get("hls").asText()
            node.has("src")  -> node.get("src").asText()
            node.has("url")  -> node.get("url").asText()
            node.has("sources") && node.get("sources").isArray && node.get("sources").size() > 0 -> {
                val s0 = node.get("sources")[0]
                s0.get("file")?.asText() ?: s0.get("src")?.asText()
            }
            else -> null
        }
    }

    private fun collectSubs(node: JsonNode): List<SubtitleFile> {
        val arr = when {
            node.has("subtitles") -> node.get("subtitles")
            node.has("tracks")    -> node.get("tracks")
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { s ->
            val u = s.get("file")?.asText() ?: s.get("src")?.asText()
            val l = s.get("label")?.asText() ?: s.get("lang")?.asText() ?: "Subtitle"
            u?.let { SubtitleFile(l, fixUrl(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val resp = app.get(data, interceptor = interceptor)
            val doc  = resp.document

            // Sayfadaki şifreli payload
            val encText = doc.selectFirst("[data-rm-k]")?.text()?.trim()
            // appCKey base64 -> hex string
            val appKeyB64 = Regex("""window\.appCKey\s*=\s*'([^']+)'""")
                .find(doc.toString())?.groupValues?.getOrNull(1)

            if (!encText.isNullOrBlank() && !appKeyB64.isNullOrBlank()) {
                val mapper  = jacksonObjectMapper()
                val enc     = mapper.readValue<EncPayload>(encText)

                val keyHex  = String(Base64.decode(appKeyB64, Base64.DEFAULT))
                val key     = keyHex.hexToBytes()
                val ivBytes = enc.iv.hexToBytes()
                val cipherB = Base64.decode(enc.ciphertext, Base64.DEFAULT)

                val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
                val clear   = String(cipher.doFinal(cipherB)).trim()

                // Altyazı dene (varsa)
                runCatching {
                    val node = mapper.readTree(clear)
                    collectSubs(node).forEach(subtitleCallback)
                }

                // URL çıkar
                val resolved: String? = when {
                    clear.startsWith("http", true)            -> clear
                    clear.contains("m3u8", true)              -> Regex("""https?://[^\s"']+m3u8[^\s"']*""").find(clear)?.value
                    else -> runCatching {
                        maybePickUrlFromJson(mapper.readTree(clear))
                    }.getOrNull()
                }?.let { fixUrl(it) }

                if (!resolved.isNullOrBlank()) {
                    val isHls = resolved.contains(".m3u8", true)

                    // Doğrudan akışsa doğrudan ver; değilse extractor'a bırak
                    if (isHls || resolved.endsWith(".mp4", true)) {
                        callback.invoke(
                            newExtractorLink(
                                name,              // source
                                "DiziPal",         // name
                                resolved,          // url
                                mainUrl,           // referer  (konumsal argüman!)
                                Qualities.Unknown.value,
                                isHls
                            )
                        )
                        return true
                    } else {
                        // Örn: vidmoly/dood vb. gömülü sayfa
                        if (loadExtractor(resolved, data, subtitleCallback, callback)) return true
                    }
                }
            }

            // Yedek: sayfadaki ilk iframe’i dene
            val iframe = doc.selectFirst("iframe[src]")?.attr("src")
                ?: doc.selectFirst(".series-player-container iframe[src]")?.attr("src")
                ?: doc.selectFirst("#vast_new iframe[src]")?.attr("src")

            if (!iframe.isNullOrBlank()) {
                return loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
            }
        } catch (e: Throwable) {
            Log.e("DZP", "loadLinks error: ${e.message}")
        }
        return false
    }
}
