package com.arshad.ayn.core.filter.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped pack lists (FPK.1). Titles come from the research report's fixture list and
 * examples (2026-10-29, §3.3–§3.6 and §4): what each set must catch, and the look-alikes it must
 * leave alone.
 */
class FilterPacksTest {

    @Test
    fun everyListedChannelAppearsOnceWithTheReportsDefaults() {
        val channels = IslamicOnly.channels
        assertEquals(40, channels.size)
        assertEquals(channels.size, channels.map { it.id }.toSet().size)
        assertEquals("kids: the report's Kids-ON channels", KIDS_ON, channels.filter { it.onForKids }.map { it.id }.toSet())
        assertEquals("adults: Kids-ON + ON (adult) + Al Muqit", KIDS_ON + ADULTS_ALSO_ON, channels.filter { it.onForAdults }.map { it.id }.toSet())
        assertTrue("lectures start off for kids", channels.filter { it.group == IslamicGroup.Lectures }.none { it.onForKids })
        assertEquals(18, HarmfulForKids.core.channelIds.size)
        assertEquals(30, HalalOnly.music.channelIds.size)
    }

    @Test
    fun harmfulCoreCatchesTheReportsTitleTropes() {
        listOf(
            "SURPRISE EGGS Unboxing Toys for Kids",
            "Elsa and Spiderman Bad Baby Finger Family",
            "Spiderman gets an injection, Elsa cries",
            "AI generated kittens starving in the rain",
            "Rich vs Poor family at school",
            "Skibidi Toilet 77 (full episode)",
            "Huggy Wuggy vs Poppy Playtime",
            "PRANK on my little brother GONE WRONG",
            "Ryan’s World Giant Egg Hunt",
        ).forEach { assertTrue(it, HarmfulForKids.core.hidesTitle(it)) }
    }

    @Test
    fun kidsCharacterAndThemeRulesMatchInEitherOrder() {
        listOf(
            "Peppa pregnant at the hospital",
            "Pregnant Peppa at the hospital",
            "Kittens abused in the rain",
            "Starving puppies rescued",
        ).forEach { assertTrue(it, HarmfulForKids.core.hidesTitle(it)) }
    }

    @Test
    fun harmfulCoreNeverHidesABareSahur() {
        assertFalse(HarmfulForKids.core.hidesTitle("Sahur time in Makkah"))
        assertTrue(HarmfulForKids.core.hidesTitle("Tung Tung Tung Sahur"))
    }

    @Test
    fun aChannelIdHidesWhatItsTitlesDontSay() {
        val toysAndColors = "UCgFXm4TI8htWmCyJ6cVPG_A"
        val title = PackText.of("Maddie and Bella's Candy High Heels Are Melting!")
        assertTrue(HarmfulForKids.core.hides(toysAndColors, title, null))
        assertFalse(HarmfulForKids.core.hides(null, title, null))
    }

    @Test
    fun aNasheedMarkerPassesEvenALabelChannel() {
        val tSeries = "UCq-Fj5jknLsUf-MWSy4_brA"
        assertFalse(HalalOnly.music.hides(tSeries, PackText.of("Allahu Akbar (Vocals Only)"), null))
        assertTrue(HalalOnly.music.hides(tSeries, null, null))
    }

    @Test
    fun harmfulCoreReadsFarmNamesOnChannelsToo() {
        assertTrue(HarmfulForKids.core.hidesChannelName("Vlad and Niki Arabic"))
        assertTrue(HarmfulForKids.core.hidesChannelName("ديانا و روما"))
        assertFalse(HarmfulForKids.core.hidesChannelName("Omar & Hana"))
    }

    @Test
    fun overstimulatingCatchesPreschoolBrandsAndMarathons() {
        listOf(
            "Wheels on the Bus | CoComelon Nursery Rhymes & Kids Songs",
            "Learn Colors with Balls",
            "1 Hour Nursery Rhymes Compilation",
            "Baby | Shark | Finger | Family | Song | Kids",
            "Pretend play doctor set",
        ).forEach { assertTrue(it, HarmfulForKids.overstimulating.hidesTitle(it)) }
    }

    @Test
    fun challengesSetSkipsHajjAndUmrahAndQuranForSleep() {
        listOf(
            "I Survived 24 Hours In An Abandoned Mall",
            "Last To Leave Circle Wins $500,000",
            "Our Baby Cried At Disneyland",
            "10 Hours Baby Sleep Lullaby",
        ).forEach { assertTrue(it, HarmfulForKids.challengesAndVlogs.hidesTitle(it)) }
        listOf(
            "24 Hours in Makkah during Umrah",
            "Quran for sleep 8 hours | Surah Al-Mulk",
        ).forEach { assertFalse(it, HarmfulForKids.challengesAndVlogs.hidesTitle(it)) }
    }

    @Test
    fun islamicChannelsPassHarmfulForKids() {
        assertTrue("its title alone is a preschool trope", HarmfulForKids.overstimulating.hidesTitle("Learn colours in Arabic"))
        assertTrue("Learn with Zakaria", HarmfulForKids.exempts("UCtlcIZVBdFPSAtCoNZsTusg"))
        assertFalse("Cocomelon", HarmfulForKids.exempts("UCbCmjCuTUZos6Inko4u57UQ"))
        assertFalse(HarmfulForKids.exempts(null))
    }

    @Test
    fun musicCatchesMarkersAndLeavesFeetAlone() {
        listOf(
            "Ed Sheeran - Shape of You (Official Music Video)",
            "Song Title ft. Drake",
            "Tum Hi Ho | Full Video Song",
            "Lofi beats to study to",
            "أغنية جديدة 2026",
        ).forEach { assertTrue(it, HalalOnly.music.hidesTitle(it)) }
        listOf(
            "How tall is 5 ft 10?",
            "Was the daff instrumental in early Islam?",
        ).forEach { assertFalse(it, HalalOnly.music.hidesTitle(it)) }
        assertTrue(HalalOnly.music.hidesTitle("Piano instrumental"))
    }

    @Test
    fun nasheedMarkersBeatTheMusicRules() {
        assertFalse(HalalOnly.music.hidesTitle("Maher Zain - Insha Allah | Vocals Only - Official Music Video"))
        assertFalse(HalalOnly.music.hidesTitle("طلع البدر علينا (بدون موسيقى) | Official Lyric Video"))
    }

    @Test
    fun aMusicVideoTitledNasheedPassesOnlyByItsChannel() {
        val bukhatir = "UC-2pqFUafqKCnACEfQasZGg"
        val title = "Ahmed Bukhatir - Dar Al Salam ( Arabic Music Video )"
        assertTrue(HalalOnly.music.hidesTitle(title))
        assertTrue(HalalOnly.exempts(bukhatir, PackText.of(title)))
    }

    @Test
    fun musicChannelNamesAreRead() {
        assertTrue(HalalOnly.music.hidesChannelName("Ed Sheeran - Topic"))
        assertTrue(HalalOnly.music.hidesChannelName("SonyMusicIndiaVEVO"))
        assertTrue(HalalOnly.music.hidesChannelName("Atlantic Records"))
        assertFalse(HalalOnly.music.hidesChannelName("Guinness World Records"))
    }

    @Test
    fun aMixedChannelPassesHalalOnlyOnlyWithItsMarker() {
        val zainBhikha = "UCTV8sHvXq5VUiTGfZv9JWjg"
        assertTrue(HalalOnly.exempts(zainBhikha, PackText.of("Zain Bhikha - Allah Knows (Voice Only)")))
        assertFalse(HalalOnly.exempts(zainBhikha, PackText.of("Zain Bhikha - Allah Knows")))
        assertFalse("no title, no marker", HalalOnly.exempts(zainBhikha, null))
        assertFalse("not a listed channel", HalalOnly.exempts("UCq-Fj5jknLsUf-MWSy4_brA", PackText.of("Nasheed")))
        assertFalse("MiniMuslims: listed, music not ruled out", HalalOnly.exempts("UCIDYe6rgdROl77DDevNIcPA", PackText.of("Ramadan song")))
    }

    @Test
    fun haramSetLeavesTheReportsFixturesAlone() {
        listOf(
            "Carbon dating explained",
            "Gay-Lussac's law in two minutes",
            "Witch hazel for skin",
            "The zombie ant fungus",
            "Homemade root beer",
            "Red wine vinegar dressing",
            "Ruqyah for black magic and evil eye",
            "Sex determination in turtles",
            "Sexually reproducing cells",
            "Black magic in the Qur’an",
        ).forEach { assertFalse(it, HalalOnly.haram.hidesTitle(it)) }
    }

    @Test
    fun haramSetCatchesItsCategories() {
        listOf(
            "Pride parade 2026 highlights",
            "Witchcraft for beginners",
            "Best horror movies of the year",
            "Zombie apocalypse survival",
            "Tarot reading for October",
            "Poker night with friends",
            "Halloween costume ideas",
            "Online dating tips",
            "Gay rights march",
            "Craft beer tasting",
            "Wine tasting for beginners",
            "Sexy dance challenge",
        ).forEach { assertTrue(it, HalalOnly.haram.hidesTitle(it)) }
    }

    @Test
    fun contestedOptionsCatchTheirOwnTopics() {
        assertTrue(HalalOnly.festivals.hidesTitle("Christmas lights tour"))
        assertFalse(HalalOnly.festivals.hidesTitle("Christmas Island red crab migration"))
        assertTrue(HalalOnly.stageMagic.hidesTitle("Street magic in London"))
        assertTrue(HalalOnly.lootBoxes.hidesTitle("Opening 100 loot boxes"))
    }

    @Test
    fun guardedIslamicChannelsNeedTheirMarker() {
        val omarHana = IslamicOnly.channel("UC178EmfQAV3OT-UpuO6WUMg")!!
        assertTrue(omarHana.allows(PackText.of("Omar & Hana | Ramadan Song (NO INSTRUMENTS)")))
        assertFalse(omarHana.allows(PackText.of("Omar & Hana | Ramadan Song")))
        assertTrue("an unguarded channel", IslamicOnly.channel("UC4d8OKjmN_ggVVbo00DM_3Q")!!.allows(null))
        val toyor = IslamicOnly.channel("UCozAuGJZ5MPoijz8IE_1Gtw")!!
        assertTrue(toyor.allows(PackText.of("طيور الجنة - نشيد العيد (بدون إيقاع)")))
        assertFalse(toyor.allows(PackText.of("طيور الجنة - نشيد العيد")))
        assertTrue(IslamicOnly.blocks.hidesTitle("Mu'allaqat Imru al-Qais | Alafasy"))
        assertTrue(IslamicOnly.blocks.hidesTitle("Mu’allaqat Imru al-Qais | Alafasy"))
    }

    private companion object {
        // Report §3.7's Default column, by channel id.
        val KIDS_ON = setOf(
            "UCos52azQNBgW63_9uDJoPDA", "UCROKYPep-UuODNwyipe6JMw", "UCmMcOjsVehVlEOteyrhjI2Q", "UCvv7gvo7ZtNd0FTUGNdKs1w",
            "UC5vfWTTPnKdFp-8s0KbqJhw", "UCCYwm5kH1vpplJz4h5eYw4w", "UC4d8OKjmN_ggVVbo00DM_3Q", "UC7oYY3yXJKw7ZU7ORlNOF2A",
            "UC-2pqFUafqKCnACEfQasZGg", "UCJZS2p3jZ_T8TiUMVE6bvVQ", "UCTV8sHvXq5VUiTGfZv9JWjg",
        )
        val ADULTS_ALSO_ON = setOf(
            "UCheBIbE_7Trrhatjt9LIteQ", "UC3vHW2h22WE-pNi5WJtRIjg", "UCtm8rtofLSnaIBi3noB0INg", "UCNB_OaI4524fASt8h0IL8dw",
            "UCTX8ZbNDi_HBoyjTWRw9fAg",
        )
    }

    private fun BlockSet.hidesTitle(title: String): Boolean = hides(channelId = null, title = PackText.of(title), channelName = null)

    private fun BlockSet.hidesChannelName(name: String): Boolean = hides(channelId = null, title = null, channelName = PackText.of(name))
}
