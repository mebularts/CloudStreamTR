version = 12

cloudstream {
    authors     = listOf("mebularts", "muratcesmecioglu")
    language    = "tr"
    description = "en yeni dizileri güvenli ve hızlı şekilde sunar."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://dizipal1103.com/assets/images/logo-dpal.svg?V=2"
}
