package com.mebularts

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CizgiVeDiziPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CizgiVeDizi())
    }
}
