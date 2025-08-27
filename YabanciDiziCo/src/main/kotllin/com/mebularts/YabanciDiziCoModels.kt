package com.mebularts

// Şimdilik HTML ile çalışıyoruz; ama ileride site autocomplete / arama için JSON döndürürse bu modelleri kullanabilirsin.
data class YDZSearchItem(
    val title: String? = null,
    val url: String? = null,
    val poster: String? = null,
    val type: String? = null, // "dizi" | "film"
)

data class YDZApiResponse<T>(
    val data: T? = null,
    val message: String? = null,
    val success: Boolean = false,
)
