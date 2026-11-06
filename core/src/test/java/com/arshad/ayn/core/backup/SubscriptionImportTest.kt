package com.arshad.ayn.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseSubscriptionsFile] on files shaped like Google Takeout's `subscriptions.csv` and NewPipe's
 * subscriptions export (M5-D6). Expected channels are read off the fixtures by hand.
 */
class SubscriptionImportTest {

    @Test
    fun anEnglishTakeoutFileGivesEveryChannelKeyedByItsId() {
        assertEquals(
            listOf(
                ImportedChannel(url(RAYCEVICK), "Raycevick"),
                ImportedChannel(url(JOJI), "Joji"),
                ImportedChannel(url(COMMA), "Comma, Inc. \"Official\""),
            ),
            parse("takeout-subscriptions-en.csv"),
        )
    }

    @Test
    fun aSpanishTakeoutFileWithByteOrderMarkAndCrlfKeepsOnlyValidRowsOnce() {
        assertEquals(
            listOf(
                ImportedChannel(url(RAYCEVICK), "Raycevick"),
                ImportedChannel(url(GOOGLE_DEVELOPERS), "Google Developers"),
                ImportedChannel(url(JOJI), "Joji"),
            ),
            parse("takeout-subscriptions-es.csv"),
        )
    }

    @Test
    fun aNewPipeExportGivesItsYouTubeChannels() {
        assertEquals(
            listOf(ImportedChannel(url(RAYCEVICK), "Raycevick"), ImportedChannel(url(JOJI), "Joji")),
            parse("newpipe-subscriptions.json"),
        )
    }

    @Test
    fun aChannelWithoutANameShowsItsId() {
        val csv = "$RAYCEVICK,http://www.youtube.com/channel/$RAYCEVICK,  \n"

        assertEquals(listOf(ImportedChannel(url(RAYCEVICK), RAYCEVICK)), parseSubscriptionsFile(csv.encodeToByteArray()))
    }

    @Test
    fun aChannelNameLongerThanAnyRealOneShowsTheId() {
        val csv = "$RAYCEVICK,,${"n".repeat(MAX_TEXT_LENGTH + 1)}\n"

        assertEquals(listOf(ImportedChannel(url(RAYCEVICK), RAYCEVICK)), parseSubscriptionsFile(csv.encodeToByteArray()))
    }

    @Test
    fun jsonThatIsNotANewPipeExportHoldsNoChannels() {
        val keBackup = """{"bundleVersion":1,"app":"YouTube KE","exportedAtMillis":1,"profiles":[]}"""

        assertEquals(emptyList<ImportedChannel>(), parseSubscriptionsFile(keBackup.encodeToByteArray()))
    }

    @Test
    fun aNewPipeExportNestedDeeperThanAnyExportHoldsNoChannels() {
        val text = resource("newpipe-subscriptions.json").decodeToString().trimEnd().dropLast(1) +
            ",\"later\":" + "[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH) + "}"

        assertEquals(emptyList<ImportedChannel>(), parseSubscriptionsFile(text.encodeToByteArray()))
    }

    @Test
    fun csvQuotedFieldsKeepCommasQuotesAndLineBreaks() {
        assertEquals(
            listOf(listOf("a", "b,\n\"c\"", "d"), listOf("e")),
            records("a,\"b,\n\"\"c\"\"\",d\r\ne", keepFields = 3),
        )
    }

    @Test
    fun csvFieldsPastTheKeptOnesAreSkipped() {
        assertEquals(listOf(listOf("a", "b"), listOf("")), records("a,b,\"c,\nd\",e\n\n", keepFields = 2))
    }

    private fun records(text: String, keepFields: Int): List<List<String>> =
        buildList { forEachCsvRecord(text, keepFields) { add(it) } }

    private fun parse(name: String): List<ImportedChannel> = parseSubscriptionsFile(resource(name))

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/backup/$name")).use { it.readBytes() }

    private fun url(channelId: String): String = "https://www.youtube.com/channel/$channelId"
}

private const val RAYCEVICK = "UC1JTQBa5QxZCpXrFSkMxmPw"
private const val JOJI = "UCFl7yKfcRcFmIUbKeCA-SJQ"
private const val COMMA = "UCq-Fj5jknLsUf-MWSy4_brA"
private const val GOOGLE_DEVELOPERS = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
