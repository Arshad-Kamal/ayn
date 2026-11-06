package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.SegmentCategory
import java.util.Locale

// The playback names both UIs show, in their players and Settings (`b3-settings` v13).

/** A SponsorBlock category's name and what its segments are. */
class CategoryText(val name: String, val about: String)

val SegmentCategory.text: CategoryText
    get() = when (this) {
        SegmentCategory.Sponsor -> CategoryText("Sponsor", "Paid promotion and sponsored segments")
        SegmentCategory.SelfPromo -> CategoryText("Self-promotion", "Merch, Patreon, the creator’s other channels")
        SegmentCategory.Interaction -> CategoryText("Interaction reminder", "“Like and subscribe” in the middle of a video")
        SegmentCategory.Intro -> CategoryText("Intro animation", "Intro sequences with no content")
        SegmentCategory.Outro -> CategoryText("Endcards and credits", "End screens after the content is over")
        SegmentCategory.Preview -> CategoryText("Preview and recap", "Clips of what’s coming or what happened")
        SegmentCategory.Hook -> CategoryText("Hook and greetings", "Opening teasers and “hey everyone”")
        SegmentCategory.MusicOffTopic -> CategoryText("Non-music section", "Talking in music videos")
        SegmentCategory.Filler -> CategoryText("Tangents and jokes", "Off-topic asides; the most aggressive category")
    }

/** A speed as the player's speed choice names it: "Normal", "0.75×", "2×". */
fun speedLabel(percent: Int): String =
    if (percent == 100) "Normal" else (percent / 100.0).toString().removeSuffix(".0") + "×"

/** A picture height's common name, where it has one: "4K", "Full HD". */
fun heightName(lines: Int): String? = when (lines) {
    2160 -> "4K"
    1080 -> "Full HD"
    else -> null
}

/**
 * The offered captions languages' names (Q16). Ayn names them itself: the platform's names differ
 * ("Bangla", "Ojibwa", "Siksiká", "Chipewyan") or are missing (Stoney Nakoda shows "sto").
 */
val CAPTIONS_NAMES: Map<String, String> = mapOf(
    "en" to "English",
    "bn" to "Bengali",
    "ar" to "Arabic",
    "ga" to "Irish",
    "gd" to "Scottish Gaelic",
    "crk" to "Plains Cree",
    "bla" to "Blackfoot",
    "chp" to "Dene",
    "sto" to "Stoney Nakoda",
    "oj" to "Ojibwe",
    "iu" to "Inuktitut",
)

/** A captions language's English name ("Plains Cree"; another language's as the platform says it); Off for none. */
fun captionsLabel(tag: String?): String =
    tag?.let { CAPTIONS_NAMES[it] ?: Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH).ifEmpty { it } } ?: "Off"
