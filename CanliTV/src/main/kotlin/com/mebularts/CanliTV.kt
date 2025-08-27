// CanliTV.kt
// CloudStream FreeIPTV_TR uyarlaması (deprecated ctor ve inline-lambda continue fixleri yapıldı)

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.InputStream

class CanliTV : MainAPI() {
    // FreeIPTV_TR için doğru RAW link
    override var mainUrl =
        "https://raw.githubusercontent.com/mebularts/FreeIPTV_TR/main/ByteFixRepairsTurkIPTV.m3u"

    override var name               = "CanliTV"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes     = setOf(TvType.Live)

    // -------- Helpers --------
    private suspend fun fetchPlaylist(): Playlist {
        val txt = app.get(
            mainUrl,
            headers = mapOf("User-Agent" to "CloudStream/CanliTV (+github.com/mebularts)"),
            referer = "https://github.com/"
        ).text
        return IptvPlaylistParser().parseM3U(txt)
    }

    private fun PlaylistItem.toLoadData(): LoadData {
        val streamurl   = (this.url ?: "").trim()
        val channelname = (this.title ?: "").trim()
        val posterurl   = (this.attributes["tvg-logo"] ?: "").trim()
        val chGroup     = (this.attributes["group-title"] ?: "Diğer").trim()
        val nation      = (this.attributes["tvg-country"] ?: "tr").trim()
        return LoadData(streamurl, channelname, posterurl, chGroup, nation)
    }

    // -------- Pages & Search --------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kanallar = fetchPlaylist()

        val lists = kanallar.items
            .groupBy { it.attributes["group-title"] ?: "Diğer" }
            .map { (grp, items) ->
                val show = items.map { item ->
                    val data = item.toLoadData()
                    newLiveSearchResponse(
                        data.title,
                        data.toJson(),
                        type = TvType.Live
                    ) {
                        this.posterUrl = data.poster.nullIfBlank()
                        this.lang = data.nation.nullIfBlank() ?: "tr"
                    }
                }
                HomePageList(grp, show, isHorizontalImages = true)
            }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val kanallar = fetchPlaylist()
        return kanallar.items
            .filter { (it.title ?: "").lowercase().contains(q) }
            .map { item ->
                val data = item.toLoadData()
                newLiveSearchResponse(
                    data.title,
                    data.toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = data.poster.nullIfBlank()
                    this.lang = data.nation.nullIfBlank() ?: "tr"
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // -------- Load & Links --------
    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)

        val nationPretty: String = if (loadData.group.equals("NSFW", true)) {
            "⚠️🔞🔞🔞 » ${loadData.group} | ${loadData.nation} « 🔞🔞🔞⚠️"
        } else {
            "» ${loadData.group} | ${loadData.nation} «"
        }

        // Aynı gruptan öneriler
        val kanallar = fetchPlaylist()
        val recommendations = kanallar.items
            .filter { (it.attributes["group-title"] ?: "Diğer") == loadData.group }
            .mapNotNull { item ->
                val d = item.toLoadData()
                if (d.title == loadData.title) null
                else newLiveSearchResponse(d.title, d.toJson(), type = TvType.Live) {
                    this.posterUrl = d.poster.nullIfBlank()
                    this.lang = d.nation.nullIfBlank() ?: "tr"
                }
            }.toMutableList()

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl = loadData.poster.nullIfBlank()
            this.plot = nationPretty
            this.tags = listOf(loadData.group, loadData.nation)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = fetchDataFromUrlOrJson(data)
        Log.d("IPTV", "loadData » $loadData")

        val kanallar = fetchPlaylist()
        val kanal = kanallar.items.firstOrNull { it.url == loadData.url }
            ?: kanallar.items.first { (it.title ?: "") == loadData.title }
        Log.d("IPTV", "kanal » $kanal")

        val isM3u8 = loadData.url.contains(".m3u8", ignoreCase = true)

        // DEPRECATED ctor yerine newExtractorLink kullan
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = loadData.url,
                referer = kanal.headers["referrer"] ?: "",
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8,
                headers = kanal.headers
            )
        )
        return true
    }

    data class LoadData(val url: String, val title: String, val poster: String, val group: String, val nation: String)

    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        return if (data.startsWith("{")) {
            parseJson<LoadData>(data)
        } else {
            val kanallar = fetchPlaylist()
            val kanal = kanallar.items.firstOrNull { it.url == data }
                ?: throw RuntimeException("Kanal bulunamadı: $data")
            kanal.toLoadData()
        }
    }
}

// ---------- M3U Parser ----------
data class Playlist(
    val items: List<PlaylistItem> = emptyList()
)

data class PlaylistItem(
    val title: String?                  = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String>    = emptyMap(),
    val url: String?                    = null,
    val userAgent: String?              = null
)

class IptvPlaylistParser {

    fun parseM3U(content: String): Playlist = parseM3U(content.byteInputStream())

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()

        if (!reader.readLine().isExtendedM3u()) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var currentIndex = 0

        var line: String? = reader.readLine()

        while (line != null) {
            if (line.isNotEmpty()) {
                if (line.startsWith(EXT_INF, ignoreCase = true)) {
                    val title      = line.getTitle()
                    val attributes = line.getAttributes()
                    playlistItems.add(PlaylistItem(title, attributes))
                } else if (line.startsWith(EXT_VLC_OPT, ignoreCase = true)) {
                    // inline-lambda içinde continue kullanmadan klasik kontrol
                    if (currentIndex >= playlistItems.size) {
                        line = reader.readLine()
                        continue
                    }
                    val item      = playlistItems[currentIndex]
                    val userAgent = item.userAgent ?: line.getTagValue("http-user-agent")
                    val referrer  = line.getTagValue("http-referrer")

                    val headers = item.headers.toMutableMap()
                    if (userAgent != null) headers["user-agent"] = userAgent
                    if (referrer  != null) headers["referrer"]   = referrer

                    playlistItems[currentIndex] = item.copy(
                        userAgent = userAgent,
                        headers   = headers
                    )
                } else {
                    if (!line.startsWith("#")) {
                        // inline-lambda içinde continue yok
                        if (currentIndex >= playlistItems.size) {
                            line = reader.readLine()
                            continue
                        }
                        val item       = playlistItems[currentIndex]
                        val url        = line.getUrl()
                        val userAgent  = line.getUrlParameter("user-agent")
                        val referrer   = line.getUrlParameter("referer") ?: line.getUrlParameter("referrer")

                        val headersCombined = item.headers.toMutableMap()
                        if (referrer != null) headersCombined["referrer"] = referrer

                        playlistItems[currentIndex] = item.copy(
                            url       = url,
                            headers   = headersCombined,
                            userAgent = userAgent ?: item.userAgent
                        )
                        currentIndex++
                    }
                }
            }
            line = reader.readLine()
        }
        return Playlist(playlistItems)
    }

    private fun String.replaceQuotesAndTrim(): String = replace("\"", "").trim()
    private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U, ignoreCase = true)

    private fun String.getTitle(): String? = split(",").lastOrNull()?.replaceQuotesAndTrim()

    private fun String.getUrl(): String? = split("|").firstOrNull()?.replaceQuotesAndTrim()

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex     = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val keyRegex     = Regex("$key=([^&]+)", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()
        return keyRegex.find(paramsString)?.groups?.get(1)?.value
    }

    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex      = Regex("(#EXTINF:.?[0-9-]+)\\s*", RegexOption.IGNORE_CASE)
        val attributesString = replace(extInfRegex, "").replaceQuotesAndTrim()
            .substringBeforeLast(",", missingDelimiterValue = "")
        if (attributesString.isBlank()) return emptyMap()

        return attributesString
            .split(Regex("\\s(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) // boşluklara göre ama tırnak içlerini koru
            .mapNotNull { token ->
                val pair = token.split("=", limit = 2)
                if (pair.size == 2) pair.first().lowercase() to pair.last().replaceQuotesAndTrim() else null
            }.toMap()
    }

    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

    companion object {
        const val EXT_M3U     = "#EXTM3U"
        const val EXT_INF     = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}

// -------- küçük util --------
private fun String?.nullIfBlank(): String? = this?.ifBlank { null }
