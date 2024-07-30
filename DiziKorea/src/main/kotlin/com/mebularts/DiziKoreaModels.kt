// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import com.fasterxml.jackson.annotation.JsonProperty

data class KoreaSearch(
    @JsonProperty("theme") val theme: String
)