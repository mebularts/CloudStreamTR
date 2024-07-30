// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val partKey  = url.substringAfter("?partKey=")?.substringAfter("turkce")?.uppercase()
        val url      = url.substringBefore("?partKey=")
        val iSource  = app.post(url.replace("embed?i=", "embed/get?i="), referer=url).text

        val links = Regex("""Links\":\[\"([^\"\]]+)""").find(iSource)?.groupValues?.get(1) ?: throw ErrorLoadingException("Links not found")
        if (!links.startsWith("/")) {
            throw ErrorLoadingException("Links not valid")
        }

        val m3uLink = "${mainUrl}${links}"
        Log.d("Kekik_${this.name}", "m3uLink » ${m3uLink}")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = if (partKey != "") "${this.name} - ${partKey}" else this.name,
                url     = m3uLink,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}