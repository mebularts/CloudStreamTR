package com.mebularts

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YabanciDiziCoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YabanciDiziCo())
    }
}
