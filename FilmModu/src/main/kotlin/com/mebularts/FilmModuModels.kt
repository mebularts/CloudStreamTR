# Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import com.fasterxml.jackson.annotation.JsonProperty


data class GetSource(
    @JsonProperty("subtitle") val subtitle: String?       = null,
    @JsonProperty("sources")  val sources: List<Sources>? = arrayListOf()
)

data class Sources(
    @JsonProperty("src")   val src: String,
    @JsonProperty("label") val label: String,
)