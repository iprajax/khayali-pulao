package com.khayali.pulao.ai.term

/** Every terminal feature, in one place. Adding a feature = one object + one line here. */
object Terminal {
    val FEATURES: List<Feature> = listOf(
        MarketReportFeature, SectorMoversFeature, PreopenFeature, WatchFeature,
        HeatmapFeature, ResultsFlowFeature,
        PulseIndicesFeature, PulseSectorsFeature, PulseMoversFeature, PulseBreadthFeature, PulseActiveFeature, PulseStatusFeature, PulseHighsFeature, PulseBandsFeature, PulseVolumeFeature,
        FlowsFiiDiiFeature, FlowsDealsFeature, FlowsInsiderFeature, FlowsAnnouncementsFeature, FlowsOiSpurtsFeature, FlowsFilingsFeature, FlowsEtfsFeature, FlowsSgbFeature,
        FolioXrayFeature, FolioPnlFlowFeature, FolioRiskFeature, FolioCorrelationFeature, FolioRiskReturnFeature, FolioStressFeature, FolioIncomeFeature, FolioRebalanceFeature, FolioMapFeature,
        StockQuoteFeature, StockShareholdingFeature, StockMindmapFeature, StockScorecardFeature, StockPeersFeature, StockHistoryFeature,
        TechTaFeature, TechRsiFeature, TechMacdFeature, TechDrawdownFeature, TechReturnsFeature, TechSeasonalityFeature, TechCompareFeature, TechLevelsFeature, TechVolatilityFeature,
        DerivOptionChainFeature, DerivPcrFeature, DerivMaxPainFeature, DerivIvSmileFeature, DerivStraddleFeature, DerivOiChangeFeature, DerivExpiriesFeature,
        GlobalMarketsFeature, GlobalFxFeature, GlobalCommoditiesFeature, GlobalGoldFeature, GlobalCryptoFeature, GlobalVixFeature, GlobalInflationFeature, GlobalGdpFeature, GlobalYieldsFeature,
        FundsFundFeature, FundsCompareFeature, FundsSipRealFeature, FundsSipFeature, FundsStepUpFeature, FundsSwpFeature, FundsEmiFeature, FundsRetireFeature, FundsLumpsumFeature,
    )

    private val byId by lazy { FEATURES.associateBy { it.id } }
    fun find(id: String): Feature? = byId[id.lowercase()]

    /** A short plain-language question that clearly means one feature → (feature, argument). Deliberately conservative. */
    fun match(text: String): Pair<Feature, String>? {
        val t = text.trim()
        if (t.startsWith("/") || t.length > 60 || t.split(Regex("""\s+""")).size > 8) return null
        val hit = FEATURES.firstOrNull { f -> f.triggers.any { it.containsMatchIn(t) } } ?: return null
        // the argument is whatever looks like a symbol or @mention in the question
        val arg = Regex("""@([A-Za-z0-9&\-]+)""").find(t)?.groupValues?.get(1)
            ?: Regex("""\b(?:of|for|on)\s+([A-Za-z][A-Za-z0-9&\- ]{1,24})$""").find(t)?.groupValues?.get(1)?.trim().orEmpty()
        return hit to arg
    }

    /** Command names for the model's tool description, grouped, as compact text. */
    fun catalogue(): String = Group.entries.mapNotNull { g ->
        FEATURES.filter { it.group == g }.takeIf { it.isNotEmpty() }?.joinToString(", ", "${g.title}: ") { it.id }
    }.joinToString("; ")
}

/** The only hosts terminal features may read with [Fetch.get]. Everything else is refused before a request is made. */
object Hosts {
    /** NSE's archive of company filings; results XBRL links point here. */
    const val ARCHIVES = "nsearchives.nseindia.in"
    val ALLOWED = setOf(
        com.khayali.pulao.ai.WebData.QUOTE_HOST,          // prices, FX, commodities, news search
        com.khayali.pulao.ai.MarketData.MC_HOST,          // results calendar
        com.khayali.pulao.ai.GmpData.HOST,                // IPO grey-market premium
        ARCHIVES,                                         // NSE's filings archive (results XBRL)
        GlobalWorldBank.HOST,                               // India inflation and GDP (World Bank open data, no key)
        FundsData.HOST,                                   // mutual fund NAV history (mfapi.in open data, no key)
    )
    fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
    fun allowed(url: String) = url.startsWith("https://") && host(url) in ALLOWED
}
