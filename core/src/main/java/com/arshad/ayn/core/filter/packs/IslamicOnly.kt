package com.arshad.ayn.core.filter.packs

import com.arshad.ayn.core.store.CHANNEL_URL_PREFIX

/** The groups Islamic content only lists its channels under. */
enum class IslamicGroup { Quran, Nasheeds, Children, Lectures }

/**
 * One channel of Islamic content only (FPK-D2, D12), by channel id only: several look-alike
 * handles are imposter or decoy channels (report §3.7). [onForAdults] and [onForKids] are its
 * defaults; [musicFree] says its videos carry no instruments (so Halal only lets it through);
 * [onlyWhen] limits a channel that posts both versions to titles carrying a no-music marker.
 */
class IslamicChannel(
    val id: String,
    val name: String,
    val group: IslamicGroup,
    /** One line for the channel's switch. */
    val note: String,
    val onForAdults: Boolean,
    val onForKids: Boolean,
    val musicFree: Boolean,
    val onlyWhen: PackRule? = null,
) {
    /** Its canonical channel URL ([com.arshad.ayn.core.store.canonicalChannelUrl]). */
    val url: String get() = CHANNEL_URL_PREFIX + id

    /** Whether this channel's video titled [title] passes [onlyWhen]; an unknown title doesn't
     *  when there is a guard. */
    fun allows(title: PackText?): Boolean = onlyWhen == null || (title != null && onlyWhen.matches(title))
}

/**
 * "Islamic content only" (FPK-D2), v1 from the 2026-10-29 research report (§3.7): an allow list of
 * [channels], plus [blocks] for what an allowed channel posts that the list leaves out.
 */
object IslamicOnly {
    private val OMAR_HANA_NO_MUSIC = PackRule("""\b(?:no\s+instruments?|no\s+music|acapella|a\s?cappella|vocals?\s+only)\b""")
    private val ZAIN_BHIKHA_VOICE = PackRule("""\b(?:voice\s*-?\s*only|vocals?\s+only|drum\s+versions?|no\s+music|behind\s+love)\b""")
    private val TOYOR_NO_MUSIC = PackRule("""بدون\s*[إا]يقاع|بدون\s*موسيقى|\b(?:without\s+music|no\s+music|acapella)\b""")

    /** Alafasy's pre-Islamic poetry (the Mu'allaqat). */
    val blocks: BlockSet = BlockSet(channelIds = emptySet(), titleRules = listOf(PackRule("""معلقة|\bmu['’ʿ]?allaq""")))

    val channels: List<IslamicChannel> = listOf(
        quran("UCos52azQNBgW63_9uDJoPDA", "KSA Qur’an TV (Makkah)", "Recitation, mostly live from the Haram; Hide live streams hides it", on = true),
        quran("UCROKYPep-UuODNwyipe6JMw", "Saudi Sunnah TV (Madinah)", "Recitation from the Prophet’s Mosque", on = true),
        quran("UCmMcOjsVehVlEOteyrhjI2Q", "Alafasy", "Recitation and nasheeds; his pre-Islamic poems are left out", on = true),
        quran("UCvv7gvo7ZtNd0FTUGNdKs1w", "Omar Hisham Al Arabi", "Recitation, duas and ruqyah", on = true),
        quran("UCdAnnrdcG0t8x1qDjVgaIfQ", "Two Holy Mosques Authority", "The official channel of the Two Holy Mosques", on = false),
        quran("UCIlGlkwiA6O_N_JBPasmNIA", "Masjid Nabawi Agency", "The official channel of the Prophet’s Mosque", on = false),
        children("UC5vfWTTPnKdFp-8s0KbqJhw", "One4kids (Zaky)", "Music-free: voices and natural sounds", on = true, musicFree = true),
        children("UCCYwm5kH1vpplJz4h5eYw4w", "Omar & Hana · No Music Edition", "No instruments", on = true, musicFree = true),
        children("UC178EmfQAV3OT-UpuO6WUMg", "Omar & Hana", "Only its no-instrument versions", on = false, onlyWhen = OMAR_HANA_NO_MUSIC),
        children("UCtlcIZVBdFPSAtCoNZsTusg", "Learn with Zakaria", "Arabic for children; some newer songs aren’t labelled", on = false),
        children("UCSL4Dn-FQlvHZiSKipQZMqw", "IQRA Cartoon", "Prophets’ stories, animated", on = false),
        children("UC1l6sC1nwdBIA06FTcnVKbg", "Noor Kids", "Stories and lessons for children", on = false),
        children("UCDz4xz5CW-9n4NJ5mrn7RZA", "Muslim Kids TV", "Cartoons and lessons for children", on = false),
        children("UCIDYe6rgdROl77DDevNIcPA", "MiniMuslims", "Songs that may use music", on = false),
        children("UCozAuGJZ5MPoijz8IE_1Gtw", "Toyor Al Janah", "Only its versions without music", on = false, onlyWhen = TOYOR_NO_MUSIC),
        children("UCHAbNxkU837UAkZ-OPtdUUw", "Toyor Baby", "Only its versions without music", on = false, onlyWhen = TOYOR_NO_MUSIC),
        nasheeds("UC4d8OKjmN_ggVVbo00DM_3Q", "Omar Esa", "Voice only, no daff"),
        nasheeds("UC7oYY3yXJKw7ZU7ORlNOF2A", "Labbayk", "Voice only"),
        nasheeds("UC-2pqFUafqKCnACEfQasZGg", "Ahmed Bukhatir", "Voice only"),
        nasheeds("UCJZS2p3jZ_T8TiUMVE6bvVQ", "Muad", "Vocals only. Layered voices, which some scholars count as music"),
        nasheeds("UCTV8sHvXq5VUiTGfZv9JWjg", "Zain Bhikha", "Only his voice-only and drum versions", onlyWhen = ZAIN_BHIKHA_VOICE),
        nasheeds("UCheBIbE_7Trrhatjt9LIteQ", "Muhammad Al Muqit", "No instruments. Layered voices, which some scholars count as music", forKids = false),
        lectures("UC3vHW2h22WE-pNi5WJtRIjg", "Yaqeen Institute", "Lectures and research; some series are for adults", on = true),
        lectures("UCtm8rtofLSnaIBi3noB0INg", "Omar Suleiman", "Lectures and reminders", on = true),
        lectures("UCNB_OaI4524fASt8h0IL8dw", "Mufti Menk", "Lectures and reminders", on = true),
        lectures("UCTX8ZbNDi_HBoyjTWRw9fAg", "One Islam Productions", "Lectures and short films, music-free", on = true),
        lectures("UCNdUFOtzSx3FS71pHkQLxhQ", "FreeQuranEducation", "Lectures and animations; some videos are for members", on = false),
        lectures("UCRtiU-lpcBSi-ipFKyfIkug", "Nouman Ali Khan · Bayyinah", "Qur’an study", on = false),
        lectures("UClUa7-iHJNKEM2e_zWYSPQg", "Yasir Qadhi", "Lectures and seerah", on = false),
        lectures("UCbPVEsJhn5My75IfMWXv1_Q", "SeekersGuidance", "Courses and lectures", on = false),
        lectures("UC88lF82aS_VW3oJe7GV5zsA", "Zaytuna College", "Lectures", on = false),
        lectures("UC1B7kOnMRdPuP0rxW6mS7_A", "AlMaghrib Institute", "Lectures and seminars", on = false),
        lectures("UCHGAqdQBKTVON_FUCIYCh3Q", "The Merciful Servant", "Reminders; some stories about jinn", on = false),
        // Not checked for background music (report §3.7), so Halal only doesn't wave them through.
        lectures("UCQHRLH8RQIrdGWMhf5heWiA", "OnePath Network", "Current affairs and reminders", on = false, musicFree = false),
        lectures("UCyxM7MgZkDe5stZpb6CiOcA", "Digital Mimbar", "Reminders and debates", on = false, musicFree = false),
        lectures("UCNDpwS2nEiGiJAW3Oe-3zBg", "Islamic Guidance", "Reminders", on = false, musicFree = false),
        lectures("UCWjCSGhmSGu0VLf2mPFS0Kg", "Othman Al-Khamees", "Lectures in Arabic", on = false),
        lectures("UCpPZKwayH_pWPF20Ls0hgVw", "Nabil Al-Awadi", "Lectures in Arabic", on = false),
        lectures("UCKUOmGXE9Ytlc2EzpGqimtw", "Omar Abdelkafy", "Lectures in Arabic", on = false),
        lectures("UCct5L-DaVYK8UtmqGU8gJtw", "Tariq Jamil", "Lectures in Urdu", on = false),
    )

    private val byId: Map<String, IslamicChannel> = channels.associateBy { it.id }

    /** The listed channel with [id], or `null`. */
    fun channel(id: String): IslamicChannel? = byId[id]

    /** The ids of the channels a setter's switches turn on (FPK-D12): each channel's [switched]
     *  value, else its default for a kids profile or another. An id no longer listed is ignored. */
    fun enabledChannels(switched: Map<String, Boolean>, kids: Boolean): Set<String> =
        channels.filter { switched[it.id] ?: if (kids) it.onForKids else it.onForAdults }.mapTo(HashSet()) { it.id }
}

// Recitation carries no instruments.
private fun quran(id: String, name: String, note: String, on: Boolean): IslamicChannel =
    IslamicChannel(id, name, IslamicGroup.Quran, note, onForAdults = on, onForKids = on, musicFree = true)

// Music-free only where the channel says so (report §3.7); undetermined ones aren't.
private fun children(id: String, name: String, note: String, on: Boolean, musicFree: Boolean = false, onlyWhen: PackRule? = null): IslamicChannel =
    IslamicChannel(id, name, IslamicGroup.Children, note, onForAdults = on, onForKids = on, musicFree = musicFree, onlyWhen = onlyWhen)

// Voice-only artists; one that also posts instrument versions is guarded instead.
private fun nasheeds(id: String, name: String, note: String, forKids: Boolean = true, onlyWhen: PackRule? = null): IslamicChannel =
    IslamicChannel(id, name, IslamicGroup.Nasheeds, note, onForAdults = true, onForKids = forKids, musicFree = onlyWhen == null, onlyWhen = onlyWhen)

// Lectures start off for kids (FPK-D12) and are music-free unless the report left them unchecked.
private fun lectures(id: String, name: String, note: String, on: Boolean, musicFree: Boolean = true): IslamicChannel =
    IslamicChannel(id, name, IslamicGroup.Lectures, note, onForAdults = on, onForKids = false, musicFree = musicFree)
