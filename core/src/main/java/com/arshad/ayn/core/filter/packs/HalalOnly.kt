package com.arshad.ayn.core.filter.packs

/**
 * "Halal only" (FPK-D2, D3, D8), v1 from the 2026-10-29 research report (§3.5, §3.6): a block
 * list. [music] and [haram] are the pack; [stageMagic], [festivals] and [lootBoxes] are its
 * contested options, off by default. Cartoons and images aren't filtered (FPK-D8).
 */
object HalalOnly {
    // Words that mark Islamic context: a lecture on the ruling isn't the thing it rules on.
    private const val ISLAMIC_CONTEXT = """islam|muslim|haram|qur['’]?an|hadith|ruqy|shaykh|sheikh|mufti"""

    /** Music in three layers (label channels, channel names, titles); a title with a nasheed or
     *  no-music marker stays (FPK-D3). */
    val music: BlockSet = BlockSet(
        channelIds = setOf(
            "UCq-Fj5jknLsUf-MWSy4_brA", // T-Series
            "UCFFbwnve3yF62-tVXkTyHqg", // Zee Music Company
            "UCaayLD9i5x4MmIoVZxXSv_g", // T-Series Bhakti Sagar (M)
            "UC3IZKseVpdzPSBaWxBxundA", // HYBE LABELS
            "UCJrDMFOdv1I2k8n9oK_V21w", // Tips Official (M)
            "UCP6uH_XlsxrXwZQ4DlqbqPg", // Shemaroo Filmi Gaane (M)
            "UC56gTxNs4f9xZ7Pa2i5xNzg", // Sony Music India
            "UCbTLwN10NoCU4WDzLf1JMOA", // YRF (M)
            "UC22nIfOTM7KLIQuFGMKzQbg", // Wave Music (M)
            "UC_A7K2dXFsTMAciGmnNxy-Q", // Saregama Music (M)
            "UC3MLnJtqc_phABBriLRhtgQ", // SonyMusicIndiaVEVO (M)
            "UCRm96I5kmb_iGFofE5N691w", // T-Series Bollywood Classics (M)
            "UCOsyDsO5tIt-VZ1iwjdQmew", // Speed Records (M)
            "UCcvNYxWXR_5TjVK7cSCdW-g", // T-Series Apna Punjab (M)
            "UCgwv23FVv3lqh567yagXfNg", // DisneyMusicVEVO (M)
            "UCNApqoVYJbYSrni4YsbXzyQ", // Aditya Music (M)
            "UC_aEa8K-EOJ3D6gOs7HcyNg", // NoCopyrightSounds
            "UCEf_Bc-KVd7onSeifS3py9g", // SMTOWN
            "UCNqFDjYTexJDET3rPDrmJKg", // 7clouds (M)
            "UC4rasfm9J-X4jNl9SvXp8xA", // Ultra Records (M)
            "UCaO6TYtlC8U5ttz62hTrZgg", // JYP Entertainment (M)
            "UCpDJl2EmP7Oh90Vylx0dZtA", // Spinnin' Records
            "UCa10nxShhzNrCE1o2ZOPztg", // Trap Nation (M)
            "UCNhqvQMXIgRfjAGmxQqdNRw", // Rotana
            "UCweOkPb1wVVH0Q0Tlj4a5Pw", // 1theK
            "UC2pmfLm7iq6Ov1UwYrWYkZA", // Vevo
            "UCM1VesJtJ9vTXcMLLr_FfdQ", // Coke Studio Pakistan (M)
            "UCe4LM_eKc9ywRmVuBm5pjQg", // Atlantic Records (M)
            "UCSJ4gkVC6NrvII8umztf0Ow", // Lofi Girl
            "UCxIoihijwX4BvHjPGjKrovg", // Mazzika (M; a second id seen in search, unresolved)
        ),
        titleRules = listOf(
            PackRule("""\b(?:official\s+(?:music\s+)?(?:video|audio|mv|m/v|clip|visuali[sz]er|lyrics?(?:\s+video)?)|music\s+video|lyric\s+video|visuali[sz]er)\b"""),
            PackRule("""[(\[【|]\s*(?:official\s+)?(?:audio|mv|m/v|video\s*clip|clip\s+officiel|full\s+video|video\s+song)\s*[)\]】|]"""),
            PackRule("""\b(?:lyrics?|lyrical)\b"""),
            // "ft." after a word, not a number: "Song ft. Artist", never "5 ft tall".
            PackRule("""(?:^|[^\d\s])\s*\b(?:ft\.?|feat\.)\s+[^\d.\s]"""),
            // No "live at/from": the Haram's Taraweeh streams.
            PackRule("""\b(?:in\s+concert|live\s+concert|concert|unplugged|tiny\s+desk|live\s+(?:session|performance|on\s+stage)|world\s+tour|acoustic\s+(?:version|session|cover|live))\b"""),
            PackRule("""[(\[]\s*cover\s*[)\]]|\bcover\s+(?:song|version)\b|\b(?:piano|guitar|violin|acoustic|drum|cello|ukulele|sax(?:ophone)?|flute)\s+cover\b"""),
            PackRule("""\b(?:remix(?:ed)?|mash-?up|nightcore|daycore|8d\s+audio|bass\s+boosted|slowed\s*(?:\+|and|&)\s*reverb|sped\s*up\s+(?:version|song)|extended\s+mix|club\s+mix|radio\s+edit)\b"""),
            PackRule("""[(\[]\s*(?:slowed|sped\s*up|reverb|8d|nightcore)[^)\]]*[)\]]"""),
            PackRule("""\b(?:karaoke|backing\s+track|minus\s+one|type\s+beat|lo-?fi|chillhop|synthwave|phonk|drill\s+beat)\b"""),
            PackRule("""\binstrumental\b""", unless = listOf("""\binstrumental\s+in\b""")),
            PackRule("""\b(?:relaxing|sleep|study|calm|meditation|piano|guitar|jazz|chill|lounge|ambient|background|workout|party|dance|wedding|romantic|sad)\s+(?:music|songs?|mix|playlist|hits)\b"""),
            PackRule("""\b(?:top\s+\d+\s+songs|best\s+songs|greatest\s+hits|hit\s+songs|full\s+album|(?:audio\s+)?jukebox|non-?stop\s+(?:songs|hits|mix)|music\s+playlist|songs\s+playlist|mixtape)\b"""),
            PackRule("""\b(?:video\s+song|full\s+song|title\s+track|item\s+song|(?:new|latest)\s+(?:\w+\s+)?songs?\s+20\d\d|(?:punjabi|hindi|haryanvi|bhojpuri|bollywood|tamil|telugu)\s+songs?|gaana|gaane)\b"""),
            PackRule("""\b(?:m/v|mv\s+teaser|dance\s+practice|choreography\s+video|stage\s+mix|fancam|comeback\s+stage|music\s+bank|inkigayo|m\s?countdown)\b"""),
            // Arabic/Urdu (verify on device); the nasheed markers below win over موسيقى.
            PackRule("""(?:فيديو\s*كليب|كليب|أغنية|اغنية|أغاني|اغاني|موسيقى|ريمكس|كاريوكي|ألبوم|البوم|گانا|گانے)"""),
            PackRule("""\b(?:no\s+copyright\s+music|copyright\s+free\s+music|royalty\s+free\s+music|ncs\s+release|free\s+music\s+download)\b"""),
            PackRule("""\b(?:vevo|edm|kpop|k-pop|dj\s+mix|coke\s+studio)\b"""),
        ),
        channelNameRules = listOf(
            PackRule("""\s[-–]\s(?:topic|tema)$"""),
            PackRule("""vevo$"""),
            PackRule("""(?:\bmusic(?:\s+(?:company|india|official|entertainment|label|world|tv))?|\brecordings|\blyrics?)$"""),
            PackRule("""\brecords$""", unless = listOf("""\bworld\s+records$""")),
            PackRule("""\b(?:lofi|lo-fi|nightcore|karaoke|type\s+beats?)\b"""),
        ),
        exemptTitles = listOf(
            PackRule("""\b(nasheeds?|anasheed|vocals?\s*-?\s*only|voice\s*-?\s*only|no\s+music|without\s+music|no\s+instruments?|a\s?cappella|acapella|drum\s+version|(drums?|daf|duff?|percussion)\s+only)\b"""),
            // No \b next to Arabic: prefixes such as و and ال attach to words.
            PackRule("""(بدون\s*موسيقى|بلا\s*موسيقى|بدون\s*[إا]يقاع|نشيد|أنشودة|انشودة|أناشيد|اناشيد|بغیر\s*موسیقی)"""),
        ),
    )

    /** What's haram to watch beyond music: explicit content, dating, LGBTQ themes, gambling,
     *  alcohol, drugs, the occult, astrology, horror, strong swearing, Halloween. */
    val haram: BlockSet = BlockSet(
        channelIds = emptySet(),
        titleRules = listOf(
            PackRule("""\b(?:porn\w*|nsfw|onlyfans|hentai|ecchi|nudes?|nudity|lingerie|twerk\w*|strip(?:per|club|tease)s?|erotic\w*|seductive|thirst ?traps?|lap ?dance)\b"""),
            PackRule(
                """\bsex(?:y|ier|iest|ual|ually)?\b""",
                unless = listOf("""\bsex(?:y|ier|iest|ual|ually)?\b.{0,25}\b(?:determination|ratio|chromosomes?|cells?|reproduction|differences)\b"""),
            ),
            PackRule("""\bdating\b""", unless = listOf("""\b(?:carbon|radiocarbon|radiometric)\s+dating\b""")),
            PackRule("""\b(?:boyfriends?|girlfriends?|my crush|crush on|first kiss|kissing scene|makeout|flirt(?:ing|y)?|rizz\w*|couple goals)\b"""),
            PackRule("""\b(?:lgbt\w*|lesbians?|bisexual|pansexual|transgender\w*|non-?binary|queer|drag (?:queen|king|show)s?|pride (?:month|parade|flag)s?|same-sex)\b"""),
            PackRule("""\bgay\b""", unless = listOf("""\bgay-?lussac""")),
            PackRule("""\b(?:casinos?|poker|blackjack|roulette|slot machines?|betting|bet365|stake\.com|lottery|lotto|teen patti|satta(?: matka)?|parlays?|sportsbook)\b"""),
            PackRule("""\bbeers?\b""", unless = listOf("""\b(?:root|ginger|birch)\s+beers?\b""")),
            PackRule("""\bwines?\b""", unless = listOf("""\bwines?\s?vinegar\b""")),
            PackRule("""\b(?:vodka|whiske?y|tequila|liquor|brewery|bartender|cocktails?|sharaa?b|sommelier)\b"""),
            PackRule("""\b(?:cannabis|marijuana|smoking weed|weed (?:smok\w*|edibles?)|stoners?|cocaine|heroin|meth|lsd|dmt|shrooms|magic mushrooms?|ganja|charas)\b"""),
            PackRule(
                """\b(?:black magic|witchcraft|witch(?:es)?|wicca\w*|love spells?|voodoo|ouija|occult\w*|satani\w*)\b""",
                unless = listOf(ISLAMIC_CONTEXT, """\bwitch\s+hazel\b"""),
            ),
            PackRule(
                """\b(?:tarot|horoscopes?|astrology|zodiac signs?|numerology|palmistry|psychic readings?|fortune ?tell\w*|rashifal|kundli)\b""",
                unless = listOf(ISLAMIC_CONTEXT),
            ),
            PackRule("""\b(?:horror|creepypasta|jump ?scares?|haunted|paranormal|exorcis\w*|possessed|demonic|scariest)\b"""),
            PackRule("""\bzombies?\b""", unless = listOf("""\bzombies?\s+(?:ants?|fung\w*|snails?|worms?|stars?)\b""")),
            PackRule("""\b(?:fu+ck\w*|motherf\w*|sh[i1!]t(?:s|ty|ting)?|bitch(?:es|ing)?|bastards?|wtf|stfu|dickhead|a(?:ss|\$\$)holes?)\b|\b[fs][*#@]{2,}"""),
            PackRule("""\b(?:hallowe?en|trick or treat\w*|jack-?o-?lanterns?|day of the dead|d[ií]a de (?:los )?muertos)\b"""),
        ),
    )

    val stageMagic: BlockSet = BlockSet(
        channelIds = emptySet(),
        titleRules = listOf(PackRule("""\b(?:magic tricks?|magicians?|illusionists?|card tricks?|street magic|harry potter|hogwarts)\b""")),
    )

    val festivals: BlockSet = BlockSet(
        channelIds = emptySet(),
        titleRules = listOf(
            PackRule("""\bchristmas\b""", unless = listOf("""\bchristmas\s+island\b""")),
            PackRule("""\b(?:xmas|santa claus|easter (?:bunny|egg hunt)|happy easter|diwali|holi|valentine['’]?s day|valentines)\b"""),
        ),
    )

    val lootBoxes: BlockSet = BlockSet(
        channelIds = emptySet(),
        titleRules = listOf(PackRule("""\b(?:gacha|loot ?box(?:es)?|case openings?|crash gambling|plinko)\b""")),
    )

    /**
     * Whether a video from [channelId] titled [title] passes the whole pack (FPK-D5): a listed
     * Islamic channel without instruments, or one that posts both versions when the title
     * carries its no-music marker.
     */
    fun exempts(channelId: String?, title: PackText?): Boolean {
        val channel = channelId?.let(IslamicOnly::channel) ?: return false
        return channel.musicFree || (title != null && channel.onlyWhen?.matches(title) == true)
    }
}
