package com.mebularts

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OxAxPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OxAx())
        registerExtractorAPI(OxAxPlayer())
    }
}