package com.arshad.ayn.core.access.newpipe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The suggestions seam on the recorded `suggestions` flow: [SUGGESTION_QUERIES], one session. */
class NewPipeAccessSuggestionsFixtureTest {

    @Test
    fun recordedAnswersMapInYouTubesOrderWithReferencesDecoded() {
        val access = NewPipeAccess(downloader = FixtureDownloader(SUGGESTIONS_FLOW))

        val answers = runBlocking { SUGGESTION_QUERIES.map { access.suggestions(it) } }

        assertEquals(listOf(RecordedSuggestions.LOFI_HIP, RecordedSuggestions.TOKYO, emptyList()), answers)
    }

    @Test
    fun hexAndSupplementaryReferencesDecode() {
        assertEquals("caf\u00e9 \uD83D\uDE00", decodeNumericCharacterReferences("caf&#xE9; &#128512;"))
    }

    @Test
    fun textThatIsNoValidReferenceStaysAsItIs() {
        val text = "&#1114112; &#x; &#abc; rock & roll"

        assertEquals(text, decodeNumericCharacterReferences(text))
    }
}
