version = 5

cloudstream {
    authors     = listOf("mebularts")
    language    = "tr"
    description = "Taraftarium24 Canlı Maç İzle"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Live")
    iconUrl = "https://pbs.twimg.com/profile_images/1678085460583653382/Ol75hBtt_400x400.jpg"
}
