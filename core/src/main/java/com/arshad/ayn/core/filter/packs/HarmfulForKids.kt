package com.arshad.ayn.core.filter.packs

/**
 * "Harmful for kids" (FPK-D6), v1 from the 2026-10-29 research report (§3.2, §3.3): the core tier
 * is the pack; [overstimulating] and [challengesAndVlogs] are its two options. Channel ids marked
 * M have one source (to be verified in the device pass); terminated channels cost nothing to keep.
 */
object HarmfulForKids {
    private val FARM_NAMES = PackRule(
        """\b(vlad\s*(and|&|und|e|et|y|i)\s*nik(i|ita)|diana\s*(and|&|y|e|et|und|i)\s*roma|kids\s+(diana|roma)\s+show|like\s+nastya|vani\s?mani|vania\s+mania)\b""",
    )
    private val MORE_FARM_NAMES = PackRule(
        """\b(jason\s+vlogs|katya\s+(and|y)\s+dima|ryan['’]?s\s+world|ryan\s+toys\s?review|toys\s+and\s+colou?rs|caleb\s+kids\s+show|super\s+crazy\s+kids)\b""",
    )

    // Spellings from the dkberktas CSV; verify on device (report §3.3).
    private val FARM_NAMES_ARABIC_HINDI = PackRule("""(ديانا\s*و\s*روما|व्लाद\s*और\s*निकिता)""")

    private val PRESCHOOL_BRANDS = PackRule(
        """\b(cocomelon|little\s+baby\s+bum|chu\s?chu\s?tv|baby\s?bus|pinkfong|bebefinn|super\s+jo\s?jo|baby\s+shark\s+(dance|song|doo))\b""",
    )

    private const val KIDS_CHARACTERS = """\b(elsa|spider-?man|peppa|minnie|mickey|hulk|joker|minions?|paw\s+patrol|bluey)\b"""
    private const val MATURE_THEMES = """\b(pregnan\w*|kiss\w*|injection|blood\w*|toilet|poop|vomit|kidnap\w*|zombie|killer)\b"""
    private const val ANIMALS = """\b(kittens?|cats?|pupp(y|ies)|minions?)\b"""
    private const val CRUELTY = """\b(abused?|starv\w*|beaten|tortur\w*|ai)\b"""

    val core: BlockSet = BlockSet(
        channelIds = setOf(
            "UChGJGhZ9SOOHvBB0Y4DOO_w", // Ryan's World
            "UCvlE5gTbOvjiolFlEm-c_Ow", // Vlad and Niki
            "UCoxoYSL3rFXB7JSELiI5V1w", // Vlad and Niki ARA (M)
            "UCk8GzjMOrta8yxDcKfylJYw", // Kids Diana Show
            "UCx790OVgpTC1UVBQIqu3gnQ", // Kids Roma Show (M)
            "UCl_P0sCqxOdQpMwuajdXkRg", // Diana and Roma EN (M)
            "UCZMKqsx2piEt52vOoM2WXUw", // Diana and Roma ARA (M)
            "UCGRdNfemQ7iXpacd75SE7rA", // Diana and Roma IND (M)
            "UCJplp5SjeGSdVdwsfb9Q7lQ", // Like Nastya
            "UCgFXm4TI8htWmCyJ6cVPG_A", // Toys and Colors (M)
            "UCLbyJSPEe2lx3jY-G0CFR3g", // VaniMani / Vania Mania (M)
            "UCHx9svzVRtk51ScfXe9m0ug", // VaniMani arm (M)
            "UCM0UT-N90Olvdfdnb8fjwNQ", // VaniMani AR arm (M)
            "UCjmAh2qxmvWdH3xNPbg0IUQ", // Jason Vlogs (M)
            "UCsSsgPaZ2GSmO6il8Cb5iGA", // skibidi (formerly DaFuq!?Boom!)
            "UC9fUm_9ZouDuLIMlml6bw5w", // Toy Freaks (terminated)
            "UC8lV8KIVWvfsaqOi_d3Wu3w", // DaddyOFive (terminated)
            "UCX3BhQKdgh3fZX7Ji_84avQ", // The Martin Family (terminated)
        ),
        titleRules = listOf(
            PackRule("""\b(surprise\s*(eggs?|box(es)?|toys?)|kinder\s*(surprise|joy)|blind\s*bags?|un-?box(ing|ed)?|mystery\s*(box(es)?|eggs?)|toy\s+(review|haul|hunt))\b"""),
            PackRule("""\b(elsa|anna|frozen|peppa)\b.{0,60}\b(spider-?man|joker|hulk|venom)\b|\b(spider-?man|joker|hulk|venom)\b.{0,60}\b(elsa|anna|peppa)\b"""),
            PackRule("""\b(finger\s+family|wrong\s+(heads?|ears|legs|bodies)|bad\s+baby|buried\s+alive)\b"""),
            // The report's order-independent lookaheads, as both orders.
            PackRule("$KIDS_CHARACTERS.*$MATURE_THEMES|$MATURE_THEMES.*$KIDS_CHARACTERS"),
            PackRule("""$ANIMALS.*$CRUELTY|$CRUELTY.*$ANIMALS|\bai[-\s]?(generated|animated)\b"""),
            // Never a bare "sahur": the Ramadan meal.
            PackRule("""\b(skibidi|brain\s?rot|tralalero|tung\s*tung\s*tung|bombardi[rnl]o|crocodilo|cappuccino\s+assassino|ballerina\s+cappuccin[ao]|chimpanzini|patapim|gyatt|fanum)\b"""),
            PackRule("""\b(huggy\s*wuggy|kissy\s*missy|poppy\s*playtime|siren\s*head|cartoon\s*cat|banban|smiling\s+critters|backrooms|momo\s+challenge|fnaf|five\s+nights\s+at\s+freddy['’]?s)\b"""),
            PackRule("""\b(prank(s|ed|ing|ster)?|gone\s+(wrong|sexual)|(scar(ed|ing)|troll(ed|ing))\s+my\s+(kids?|son|daughter|sister|brother|mom|dad))\b"""),
            FARM_NAMES,
            MORE_FARM_NAMES,
            FARM_NAMES_ARABIC_HINDI,
            PackRule("""\b(elsagate|creepypasta|rich\s+vs\.?\s+poor)\b"""),
        ),
        channelNameRules = listOf(FARM_NAMES, MORE_FARM_NAMES, FARM_NAMES_ARABIC_HINDI),
    )

    /** Fast-paced preschool channels (Cocomelon-style): contested evidence, off by default. */
    val overstimulating: BlockSet = BlockSet(
        channelIds = setOf(
            "UCbCmjCuTUZos6Inko4u57UQ", // Cocomelon - Nursery Rhymes
            "UCKAqou7V9FAWXpZd9xtOg3Q", // Little Baby Bum
            "UCBnZ16ahKA2DZ_T5W0FPUXg", // ChuChu TV
            "UCpYye8D5fFMUPf9nSfgd4bA", // BabyBus
            "UCcdwLMPsaU2ezNSJU1nFoBQ", // Baby Shark - Pinkfong
            "UC-cY4X2sLECUhishGNiyofQ", // Bebefinn (M)
            "UCvksjnr7kInzcIjMpJpqF2A", // Little Angel (M)
            "UCHN9P-CQVBQ1ba8o1NQJVCA", // Super JoJo (terminated; re-uploads match by name)
        ),
        titleRules = listOf(
            PRESCHOOL_BRANDS,
            PackRule("""\b(learn(ing)?\s+colou?rs?|colou?rs\s+for\s+(kids|children|toddlers|babies)|johny\s+johny)\b"""),
            PackRule("""\b(\d+\s*(hours?|hrs?)|[4-9]\d\s*min(ute)?s?)\b.{0,60}\b(compilation|non-?stop|marathon|nursery|rhymes?|kids\s+songs?|cartoons?)\b"""),
            PackRule("""\bpretend\s+play\b"""),
            // Keyword-salad titles: four or more pipes.
            PackRule("""^(?:[^|]*\|){4,}"""),
        ),
        channelNameRules = listOf(PRESCHOOL_BRANDS),
    )

    /** Challenges, money stunts, family vlogs and sleep autoplay: off by default. */
    val challengesAndVlogs: BlockSet = BlockSet(
        channelIds = setOf(
            "UCX6OQ3DkcsbYNE6H8uQQuVA", // MrBeast
            "UCbp9MyKCTEww4CxEzc_Tp0Q", // Stokes Twins (M)
            "UC6sSkkemzPjmrzS0Y0V_2zw", // Tiana (formerly Toys AndMe) (M)
        ),
        titleRules = listOf(
            PackRule(
                """\b(24|48|72|100)\s*(hours?|hrs)\s+(in|inside|at|with|trapped|challenge)\b|\blast\s+to\s+(leave|stop)\b""",
                unless = listOf("""makkah|mecca|madinah|medina|umrah|hajj|masjid"""),
            ),
            PackRule("""\b(wins?|won|keeps?\s+it|for)\s+\$\s?[\d,.]+\s*(k|m|million)?\b|\$1\s+vs\.?\s+\$"""),
            PackRule("""\b(family\s+vlog|daily\s+vlog|vlogmas|(my|our)\s+(baby|toddler|kids?|son|daughter)\s+(cried|cries|crying|meltdown)|(gender|pregnancy)\s+reveal)\b"""),
            // Quran-for-sleep isn't matched: no lullaby, white noise or baby sleep in it.
            PackRule("""\b([5-9]|1\d|2[0-4])\s*(hours?|hrs?)\b.{0,50}\b(lullab(y|ies)|white\s+noise|baby\s+sleep)\b"""),
            PackRule("""\bmrbeast\b"""),
        ),
    )

    /** Whether [channelId] passes the whole pack (FPK-D5): every Islamic content only channel
     *  does ("learn colours in Arabic" is no farm title on Learn with Zakaria). */
    fun exempts(channelId: String?): Boolean = channelId != null && IslamicOnly.channel(channelId) != null
}
