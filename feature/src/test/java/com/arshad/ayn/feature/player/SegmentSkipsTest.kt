package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentCategory.Interaction
import com.arshad.ayn.core.access.SegmentCategory.Intro
import com.arshad.ayn.core.access.SegmentCategory.Outro
import com.arshad.ayn.core.access.SegmentCategory.Preview
import com.arshad.ayn.core.access.SegmentCategory.SelfPromo
import com.arshad.ayn.core.access.SegmentCategory.Sponsor
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.sponsorblock.RecordedSegments
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.SegmentAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The skip rules (M7-D10) on SponsorBlock's recorded answer through the real seam
 * ([RecordedSegments.VIDEO]), with the profile's default actions unless a test sets its own
 * (sponsor, intro, outro, preview: a skip button; interaction: skip automatically), and on
 * hand-made segments for the joins.
 */
class SegmentSkipsTest {

    private val firstChain = SkipSpan(0, 134_034, listOf(Preview, Intro, Sponsor))
    private val interaction = SkipSpan(681_274, 687_665, listOf(Interaction))
    private val outro = SkipSpan(687_665, 697_601, listOf(Outro))

    private suspend fun recorded(
        actions: Map<SegmentCategory, SegmentAction> = ProfileSettings().segmentActions,
        skipNote: Boolean = true,
        startsInView: Boolean = true,
        promptMs: Long = SegmentSkips.PROMPT_MS,
        noteKeptMs: Long = SegmentSkips.NOTE_KEPT_MS,
    ): SegmentSkips {
        val lookup = RecordedSegments.lookup() as SegmentLookup.Found
        return SegmentSkips(lookup.segments, actions, skipNote, startsInView, promptMs, noteKeptMs)
    }

    private fun actions(vararg set: Pair<SegmentCategory, SegmentAction>): Map<SegmentCategory, SegmentAction> =
        ProfileSettings().segmentActions + set

    @Test
    fun joinedButtonSegmentsShareOnePromptToTheirEnd() = runTest {
        val skips = recorded()

        assertNull(skips.onPosition(0))

        assertEquals(SkipPrompt(firstChain, shownAtMs = 0, untilMs = 8_000), skips.prompt)
    }

    @Test
    fun thePromptGoesAfterEightSecondsOfPlaybackAndStaysGoneInsideItsSpan() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        val shown = SkipPrompt(firstChain, shownAtMs = 0, untilMs = 8_000)

        skips.onPosition(4_000)
        assertEquals(shown, skips.prompt)
        skips.onPosition(4_000) // paused
        assertEquals(shown, skips.prompt)
        skips.onPosition(7_999)
        assertEquals(shown, skips.prompt)
        skips.onPosition(8_000)
        assertNull(skips.prompt)
        skips.onPosition(20_000)

        assertNull(skips.prompt)
    }

    @Test
    fun aHiddenPromptDoesNotRunOutAndShowsAgainWithItsTimeLeft() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.onPosition(3_000)

        skips.promptInView(false, 3_000)
        skips.onPosition(13_000)
        assertEquals(SkipPrompt(firstChain, shownAtMs = 0, untilMs = 8_000), skips.prompt)
        skips.promptInView(true, 13_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 10_000, untilMs = 18_000), skips.prompt)
        skips.onPosition(17_999)
        assertEquals(18_000L, skips.prompt?.untilMs)
        skips.onPosition(18_000)
        assertNull(skips.prompt)
    }

    /** A seek and the return to view can land between two ticks. */
    @Test
    fun theStretchSinceTheLastTickCountsAsHeld() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.promptInView(false, 1_000)

        skips.promptInView(true, 15_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 14_000, untilMs = 22_000), skips.prompt)
    }

    /** The screens' effects say out of view again on the way back into view. */
    @Test
    fun sayingOutOfViewTwiceKeepsTheHeldTime() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.promptInView(false, 0)
        skips.onPosition(10_000)

        skips.promptInView(false, 10_000)
        skips.promptInView(true, 10_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 10_000, untilMs = 18_000), skips.prompt)
    }

    @Test
    fun aSeekWhileHiddenKeepsTheTimeLeft() = runTest {
        val back = recorded()
        back.onPosition(0)
        back.onPosition(3_000)
        back.promptInView(false, 3_000)
        back.onPosition(1_000)
        back.promptInView(true, 1_000)
        val forward = recorded()
        forward.onPosition(0)
        forward.onPosition(3_000)
        forward.promptInView(false, 3_000)
        forward.onPosition(100_000)
        forward.promptInView(true, 100_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = -2_000, untilMs = 6_000), back.prompt)
        assertEquals(SkipPrompt(firstChain, shownAtMs = 97_000, untilMs = 105_000), forward.prompt)
    }

    @Test
    fun aPromptThatAppearsHiddenCountsFromWhenItShows() = runTest {
        val skips = recorded(startsInView = false)
        skips.onPosition(0)
        skips.onPosition(5_000)

        skips.promptInView(true, 5_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 5_000, untilMs = 13_000), skips.prompt)
    }

    @Test
    fun heldTimeStillEndsByTheSpansEnd() = runTest {
        val skips = recorded()
        skips.onPosition(126_000)
        skips.promptInView(false, 126_000)
        skips.onPosition(130_000)

        skips.promptInView(true, 130_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 130_000, untilMs = 134_034), skips.prompt)
    }

    @Test
    fun aNewPromptCountsOnlyItsOwnHiddenStretch() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.promptInView(false, 0)
        skips.onPosition(5_000)
        skips.onPosition(688_000)

        skips.promptInView(true, 690_000)

        assertEquals(SkipPrompt(outro, shownAtMs = 690_000, untilMs = 697_601), skips.prompt)
    }

    @Test
    fun hidingAgainCountsOnlyTheNewHiddenStretch() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.promptInView(false, 0)
        skips.onPosition(4_000)
        skips.promptInView(true, 4_000)
        skips.onPosition(6_000)
        skips.promptInView(false, 6_000)
        skips.onPosition(9_000)

        skips.promptInView(true, 9_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 7_000, untilMs = 15_000), skips.prompt)
    }

    @Test
    fun seekingBackIntoASpanShowsItsPromptAgain() = runTest {
        val skips = recorded()
        skips.onPosition(0)
        skips.onPosition(9_000)
        skips.onPosition(140_000)

        skips.onPosition(60_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 60_000, untilMs = 68_000), skips.prompt)
    }

    @Test
    fun aPromptNearItsSpansEndShowsUntilTheEnd() = runTest {
        val skips = recorded()

        skips.onPosition(130_000)

        assertEquals(134_034L, skips.prompt?.untilMs)
    }

    @Test
    fun skipGoesToTheSpansEndAndThePromptGivesWayToANote() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)

        assertEquals(134_034L, skips.skipPrompted())
        assertNull(skips.prompt)
        assertEquals(SkipNote(firstChain, serial = 1), skips.note)
        assertNull(skips.onPosition(134_034))
        assertNull(skips.prompt)
    }

    /** PAF-D11: Undo after a skip from the prompt plays the span, unprompted, this viewing. */
    @Test
    fun undoAfterAPromptSkipPlaysTheSpanWithoutAPromptThisViewing() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)
        skips.skipPrompted()
        skips.onPosition(134_034)

        assertEquals(0L, skips.undo())
        assertNull(skips.onPosition(0))
        assertNull(skips.prompt)
        skips.onPosition(140_000)
        skips.onPosition(60_000)

        assertNull(skips.prompt)
    }

    /** PAF-D11: a seek back into the span while the note is up (the phone holds it under the chrome), then Undo. */
    @Test
    fun undoAfterASeekBackIntoTheSpanStillLeavesItUnprompted() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)
        skips.skipPrompted()
        skips.onPosition(134_034)
        skips.onPosition(60_000)

        skips.undo()
        skips.onPosition(0)

        assertNull(skips.prompt)
    }

    /** PAF-D12: the controls offer the span from its start, with or without its prompt, and only inside it. */
    @Test
    fun aPromptSpanIsSkippableInsideItWhetherOrNotItsPromptShows() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)
        assertEquals(firstChain, skips.skippable)

        skips.onPosition(20_000)
        assertNull(skips.prompt)
        assertEquals(firstChain, skips.skippable)
        skips.onPosition(140_000)

        assertNull(skips.skippable)
    }

    @Test
    fun theControlsSkipGoesToTheSpansEndWithANote() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)
        skips.onPosition(20_000)

        assertEquals(134_034L, skips.skipSpan())

        assertEquals(SkipNote(firstChain, serial = 1), skips.note)
        assertNull(skips.prompt)
    }

    @Test
    fun anUndoneSpanIsNoLongerSkippable() = runTest {
        val skips = recorded()
        skips.onPosition(20_000)
        skips.skipSpan()
        skips.onPosition(134_034)

        skips.undo()
        skips.onPosition(0)

        assertNull(skips.skippable)
        assertNull(skips.skipSpan())
    }

    @Test
    fun anAutoSpanIsNeverOfferedToTheControls() = runTest {
        val skips = recorded()

        skips.onPosition(681_500)

        assertNull(skips.skippable)
    }

    @Test
    fun withTheSkipNoteOffAPromptSkipIsSilent() = runTest {
        val skips = recorded(skipNote = false)
        skips.onPosition(1_000)

        assertEquals(134_034L, skips.skipPrompted())

        assertNull(skips.note)
    }

    @Test
    fun backHidesThePromptForTheRestOfItsSpan() = runTest {
        val skips = recorded()
        skips.onPosition(1_000)

        skips.dismissPrompt()
        skips.onPosition(2_000)
        assertNull(skips.prompt)
        skips.onPosition(140_000)
        skips.onPosition(60_000)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 60_000, untilMs = 68_000), skips.prompt)
    }

    @Test
    fun anAutoCategoryIsSkippedWithANote() = runTest {
        val skips = recorded()

        assertEquals(687_665L, skips.onPosition(681_500))

        assertEquals(SkipNote(interaction, serial = 1), skips.note)
    }

    @Test
    fun aPromptDueWhileTheNoteIsUpShowsOnceItGoes() = runTest {
        val skips = recorded()
        skips.onPosition(681_500)

        skips.onPosition(687_665)
        assertNull(skips.prompt)
        skips.dismissNote()
        skips.onPosition(688_000)

        assertEquals(SkipPrompt(outro, shownAtMs = 688_000, untilMs = 696_000), skips.prompt)
    }

    /** Q37: a note held back from the screen for 8 s of playback goes, and a prompt it held shows. */
    @Test
    fun aNoteHeldBackEightSecondsOfPlaybackGoesAndThePromptItHeldShows() = runTest {
        val skips = recorded()
        skips.onPosition(681_500)
        skips.promptInView(false, 687_665)

        skips.onPosition(695_664)
        assertEquals(SkipNote(interaction, serial = 1), skips.note)
        assertNull(skips.prompt)
        skips.onPosition(695_665)

        assertNull(skips.note)
        assertEquals(SkipPrompt(outro, shownAtMs = 695_665, untilMs = 697_601), skips.prompt)
    }

    /** Q37: a note on screen goes by its own time, however much playback passes (2x, a long timeout). */
    @Test
    fun aNoteOnScreenIsNeverDroppedByPlayback() = runTest {
        val skips = recorded()
        skips.onPosition(681_500)

        skips.onPosition(720_000)

        assertEquals(SkipNote(interaction, serial = 1), skips.note)
    }

    /** Q37: a longer kept time (the phone's, scaled for accessibility) keeps a held-back note longer. */
    @Test
    fun aHeldBackNoteIsKeptAsLongAsItIsGiven() = runTest {
        val skips = recorded(noteKeptMs = 20_000)
        skips.onPosition(681_500)
        skips.promptInView(false, 687_665)

        skips.onPosition(707_664)
        assertEquals(SkipNote(interaction, serial = 1), skips.note)
        skips.onPosition(707_665)

        assertNull(skips.note)
    }

    /** Q36: the prompt lasts the length it is given (the phone's, scaled for accessibility). */
    @Test
    fun aPromptLastsTheLengthItIsGiven() = runTest {
        val skips = recorded(promptMs = 20_000)

        skips.onPosition(0)

        assertEquals(SkipPrompt(firstChain, shownAtMs = 0, untilMs = 20_000), skips.prompt)
    }

    @Test
    fun withTheSkipNoteOffTheSkipIsSilentAndThePromptFollowsAtOnce() = runTest {
        val skips = recorded(skipNote = false)

        assertEquals(687_665L, skips.onPosition(681_500))
        assertNull(skips.note)
        skips.onPosition(687_665)

        assertEquals(outro, skips.prompt?.span)
    }

    @Test
    fun undoReturnsToTheStartAndTheSpanPlaysThisTime() = runTest {
        val skips = recorded()
        skips.onPosition(681_500)

        assertEquals(681_274L, skips.undo())
        assertNull(skips.note)
        assertNull(skips.onPosition(681_274))
        assertNull(skips.onPosition(684_000))
        skips.onPosition(700_000)

        assertNull(skips.onPosition(682_000))
    }

    @Test
    fun aJumpLandingShortIsNotRepeatedButReEntryIsSkippedAgain() = runTest {
        val skips = recorded()
        skips.onPosition(681_500)

        assertNull(skips.onPosition(687_400))
        skips.onPosition(690_000)

        assertEquals(687_665L, skips.onPosition(682_000))
        assertEquals(2, skips.note?.serial)
    }

    @Test
    fun barOnlyAndOffCategoriesAreNeverActedOn() = runTest {
        val skips = recorded(
            actions(Preview to SegmentAction.Bar, Intro to SegmentAction.Bar, Sponsor to SegmentAction.Off, Interaction to SegmentAction.Bar),
        )

        assertNull(skips.onPosition(1_000))
        assertNull(skips.prompt)
        assertNull(skips.onPosition(100_000))
        assertNull(skips.prompt)
        assertNull(skips.onPosition(682_000))
        assertNull(skips.note)
    }

    @Test
    fun aChainOfAutoSegmentsIsOneJump() = runTest {
        val skips = recorded(actions(Preview to SegmentAction.Skip, Intro to SegmentAction.Skip, Sponsor to SegmentAction.Skip))

        assertEquals(134_034L, skips.onPosition(0))

        assertEquals(SkipNote(firstChain, serial = 1), skips.note)
    }

    @Test
    fun segmentsUpToASecondApartJoinAndFurtherApartDont() {
        val auto = mapOf(Sponsor to SegmentAction.Skip, SelfPromo to SegmentAction.Skip).withDefaults()
        val near = SegmentSkips(listOf(Segment(Sponsor, 0, 10_000), Segment(SelfPromo, 11_000, 20_000)), auto, skipNote = true)
        val far = SegmentSkips(listOf(Segment(Sponsor, 0, 10_000), Segment(SelfPromo, 11_001, 20_000)), auto, skipNote = true)

        assertEquals(20_000L, near.onPosition(0))
        assertEquals(10_000L, far.onPosition(0))
    }

    /** `b3-player-panels` sbprompt2: self-promotion right after the sponsor read; Skip goes past both. */
    @Test
    fun aPromptRunsOnThroughAJoinedAutoSegment() {
        val skips = SegmentSkips(
            listOf(Segment(Sponsor, 0, 10_000), Segment(SelfPromo, 10_000, 20_000)),
            mapOf(Sponsor to SegmentAction.Button, SelfPromo to SegmentAction.Skip).withDefaults(),
            skipNote = true,
        )

        skips.onPosition(0)

        assertEquals(SkipSpan(0, 20_000, listOf(Sponsor, SelfPromo)), skips.prompt?.span)
    }

    @Test
    fun aPromptStartsAtItsChainsFirstButtonSegment() {
        val skips = SegmentSkips(
            listOf(Segment(SelfPromo, 0, 10_000), Segment(Sponsor, 10_000, 20_000)),
            mapOf(SelfPromo to SegmentAction.Skip, Sponsor to SegmentAction.Button).withDefaults(),
            skipNote = false,
        )

        assertEquals(10_000L, skips.onPosition(0))
        skips.onPosition(10_000)

        assertEquals(SkipSpan(10_000, 20_000, listOf(Sponsor)), skips.prompt?.span)
    }

    @Test
    fun aPromptHiddenByAJumpInsideItsSpanComesBackAfterTheNote() {
        val skips = SegmentSkips(
            listOf(Segment(Sponsor, 0, 30_000), Segment(SelfPromo, 10_000, 15_000)),
            mapOf(Sponsor to SegmentAction.Button, SelfPromo to SegmentAction.Skip).withDefaults(),
            skipNote = true,
        )
        skips.onPosition(5_000)

        assertEquals(15_000L, skips.onPosition(10_000))
        assertNull(skips.prompt)
        skips.onPosition(15_000)
        assertNull(skips.prompt)
        skips.dismissNote()
        skips.onPosition(16_000)

        assertEquals(SkipPrompt(SkipSpan(0, 30_000, listOf(Sponsor, SelfPromo)), shownAtMs = 16_000, untilMs = 24_000), skips.prompt)
    }

    @Test
    fun aPromptPastItsDeadlineStaysGoneAfterAJumpInsideItsSpan() {
        val skips = SegmentSkips(
            listOf(Segment(Sponsor, 0, 30_000), Segment(SelfPromo, 10_000, 15_000)),
            mapOf(Sponsor to SegmentAction.Button, SelfPromo to SegmentAction.Skip).withDefaults(),
            skipNote = true,
        )
        skips.onPosition(0)

        assertEquals(15_000L, skips.onPosition(10_000))
        skips.dismissNote()
        skips.onPosition(16_000)

        assertNull(skips.prompt)
    }

    @Test
    fun withNothingShowingTheActionsAnswerNothing() = runTest {
        val skips = recorded()

        assertNull(skips.skipPrompted())
        assertNull(skips.undo())
    }

    private fun Map<SegmentCategory, SegmentAction>.withDefaults(): Map<SegmentCategory, SegmentAction> =
        SegmentCategory.entries.associateWith { SegmentAction.Off } + this
}
