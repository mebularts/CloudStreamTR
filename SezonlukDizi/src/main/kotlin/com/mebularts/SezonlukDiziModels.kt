// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import com.fasterxml.jackson.annotation.JsonProperty


data class Kaynak(
    @JsonProperty("status") val status: String,
    @JsonProperty("data") val data: List<Veri>,
)

data class Veri(
    @JsonProperty("baslik") val baslik: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("kalite") val kalite: Int,
)