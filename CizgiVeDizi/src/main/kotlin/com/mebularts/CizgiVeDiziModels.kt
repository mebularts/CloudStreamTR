package com.mebularts

/** İleride gerekirse JSON uçları vs. için veri sınıfları */
data class CVDSearchItem(
    val title: String? = null,
    val url: String? = null,
    val poster: String? = null,
    val type: String? = null // "film" | "dizi"
)
