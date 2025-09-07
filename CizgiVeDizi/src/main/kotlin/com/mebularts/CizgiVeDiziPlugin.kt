// src/CizgiVeDizi/src/main/kotlin/com/mebularts/CizgiVeDiziPlugin.kt
package com.mebularts

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CizgiVeDiziPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CizgiVeDizi())
        // Eğer gerçekten ikon eklemek istiyorsan, res/drawable altında bir ikon ekleyip şunu aç:
        // pluginIcon = R.drawable.ic_cizgivedizi
    }
}
