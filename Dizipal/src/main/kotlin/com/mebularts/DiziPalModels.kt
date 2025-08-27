// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import com.fasterxml.jackson.annotation.JsonProperty

data class SearchItem(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("tr_title") val trTitle: String? = null,
    @JsonProperty("poster") val poster: String,
    @JsonProperty("genres") val genres: String? = null,
    @JsonProperty("imdb") val imdb: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("view") val view: Int = 0,
    @JsonProperty("type") val type: String = "series",
    @JsonProperty("url") val url: String
)
