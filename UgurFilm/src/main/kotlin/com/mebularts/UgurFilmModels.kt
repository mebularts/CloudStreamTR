// ! Bu araç @mebularts tarafından ♥ ile kodlanmıştır.

package com.mebularts

import com.fasterxml.jackson.annotation.JsonProperty


data class AjaxSource(
    @JsonProperty("status")      val status: String,
    @JsonProperty("iframe")      val iframe: String,
    @JsonProperty("alternative") val alternative: String,
)